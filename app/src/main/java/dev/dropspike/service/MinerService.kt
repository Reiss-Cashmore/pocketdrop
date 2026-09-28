package dev.dropspike.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dropspike.DropSpikeApp
import dev.dropspike.R
import dev.dropspike.data.DiagLog
import dev.dropspike.data.MinuteStatus
import dev.dropspike.data.SystemInfo
import dev.dropspike.data.UptimeLog
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MinerStatus(
    val running: Boolean = false,
    val ticks: Int = 0,
    val maxGapSec: Long = 0,
    val lastTickAt: Long = 0,
    val summary: String = "",
    /** Miner internals for the report. */
    val detail: List<Pair<String, String>> = emptyList(),
    val startedAt: Long = 0,
    val now: MiningNow? = null,
    val campaigns: List<InvCampaign> = emptyList(),
)

object MinerState {
    private val _status = MutableStateFlow(MinerStatus())
    val status: StateFlow<MinerStatus> = _status
    internal fun update(f: (MinerStatus) -> MinerStatus) = _status.update(f)
}

/**
 * Spike version of the background miner: a foreground service that wakes every minute
 * (the cadence real watch heartbeats need) and polls inventory progress every few ticks.
 * The tick counter and the largest gap between ticks show whether Android lets it run.
 */
class MinerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var systemEvents: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.i("service: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLog.i(
            "service: onStartCommand startId=$startId flags=$flags action=${intent?.action}" +
                (if (intent == null) " (restarted by the system after being killed)" else "") +
                ", loop ${if (loop == null) "not running" else "running"}",
        )
        if (intent?.action == ACTION_STOP) {
            DiagLog.i("service: stop requested from notification or app")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            DiagLog.e("service: startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) start()
        return START_STICKY
    }

    private fun start() {
        val prefs = DropSpikeApp.instance.prefs
        prefs.sessionActive = true
        prefs.sessionStartedAt = System.currentTimeMillis()
        // Not reference-counted, and re-acquired every tick with a short timeout: it can never
        // silently expire mid-run (a single 12h acquire used to), and can't leak if we die.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropSpike:miner")
            .apply { setReferenceCounted(false) }
        MinerState.update { MinerStatus(running = true, startedAt = prefs.sessionStartedAt) }
        DiagLog.i("service: started mining session")
        SystemInfo.power(this).forEach { (k, v) -> DiagLog.i("service: $k = $v") }
        registerSystemEvents()

        val miner = Miner(this, prefs, DropSpikeApp.instance.api)
        loop = scope.launch {
            var last = SystemClock.elapsedRealtime()
            var tick = 0
            while (isActive) {
                wakeLock?.acquire(WAKELOCK_MS)
                val started = SystemClock.elapsedRealtime()
                val gap = (started - last) / 1000
                last = started
                tick++
                prefs.lastTickAt = System.currentTimeMillis()
                if (tick > 1 && gap > 90) DiagLog.w("service: tick $tick is late, ${gap}s since the previous one (expected ~60s)")

                val (status, note) = try {
                    miner.tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.e("service: miner tick threw", e)
                    MinuteStatus.Failed to "Miner error: ${e.message}"
                }
                UptimeLog.record(status, note)
                val took = SystemClock.elapsedRealtime() - started
                DiagLog.i("service: tick $tick (gap ${gap}s, took ${took}ms) → $status · $note")

                MinerState.update {
                    it.copy(
                        ticks = tick,
                        maxGapSec = if (tick == 1) 0 else maxOf(it.maxGapSec, gap),
                        lastTickAt = prefs.lastTickAt,
                        summary = miner.describe,
                        detail = miner.detail(),
                        now = miner.now,
                        campaigns = miner.campaigns,
                    )
                }
                // With the wake timer on, don't burn battery idling: stop and let the next check restart us.
                if (prefs.wakeIntervalMin > 0 && miner.idleTicks >= IDLE_STOP_TICKS) {
                    DiagLog.i("service: nothing to mine for $IDLE_STOP_TICKS min, stopping until the next wake check")
                    stopSelf()
                    break
                }
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(miner.describe))
                // One tick per minute, measured from the start of this tick.
                delay((TICK_MS - took).coerceAtLeast(5_000))
            }
        }
    }

    /** Log everything that could explain a stalled overnight session. */
    private fun registerSystemEvents() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = getSystemService(PowerManager::class.java)
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> DiagLog.i("event: screen on")
                    Intent.ACTION_SCREEN_OFF -> DiagLog.i("event: screen off")
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> DiagLog.i("event: Doze ${if (pm.isDeviceIdleMode) "entered" else "exited"}")
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> DiagLog.i("event: power saver ${if (pm.isPowerSaveMode) "on" else "off"}")
                    Intent.ACTION_POWER_CONNECTED -> DiagLog.i("event: charger connected")
                    Intent.ACTION_POWER_DISCONNECTED -> DiagLog.i("event: charger disconnected")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        systemEvents = receiver

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = DiagLog.i("event: network available (${SystemInfo.network(this@MinerService)})")
            override fun onLost(network: Network) = DiagLog.w("event: network lost")
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagLog.i("service: app swiped away from recents (service keeps running)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        DiagLog.w("service: onTrimMemory level $level")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        DiagLog.w("service: onLowMemory")
        super.onLowMemory()
    }

    // Android 15+: called when a time-limited foreground service type runs out of time.
    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagLog.e("service: onTimeout startId=$startId type=$fgsType (Android foreground-service time limit)")
        stopSelf()
    }

    override fun onDestroy() {
        val ranFor = (System.currentTimeMillis() - DropSpikeApp.instance.prefs.sessionStartedAt) / 60_000
        DiagLog.i("service: onDestroy after ${ranFor} min, ${MinerState.status.value.ticks} ticks")
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        systemEvents?.let { runCatching { unregisterReceiver(it) } }
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        DropSpikeApp.instance.prefs.sessionActive = false
        MinerState.update { it.copy(running = false, now = null) }
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MinerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DropSpikeApp.CHANNEL_MINER)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle("PocketDrop is mining")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.dropspike.STOP"
        private const val TICK_MS = 60_000L
        private const val IDLE_STOP_TICKS = 10
        private const val WAKELOCK_MS = 3 * 60 * 1000L

        fun start(context: Context, reason: String = "user tapped Start") {
            DiagLog.i("service: start requested ($reason)")
            ContextCompat.startForegroundService(context, Intent(context, MinerService::class.java))
        }

        fun stop(context: Context) {
            DiagLog.i("service: stop requested (user tapped Stop)")
            context.startService(Intent(context, MinerService::class.java).setAction(ACTION_STOP))
        }
    }
}
