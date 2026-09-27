package dev.dropspike.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dropspike.DropSpikeApp
import dev.dropspike.R
import dev.dropspike.data.DiagLog
import dev.dropspike.twitch.DropProgress
import dev.dropspike.ui.MainActivity
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (loop == null) start()
        return START_STICKY
    }

    private fun start() {
        val prefs = DropSpikeApp.instance.prefs
        prefs.sessionActive = true
        prefs.sessionStartedAt = System.currentTimeMillis()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropSpike:miner")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        MinerState.update { MinerStatus(running = true) }
        DiagLog.i("service: started")

        loop = scope.launch {
            var last = SystemClock.elapsedRealtime()
            var tick = 0
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val gap = (now - last) / 1000
                last = now
                tick++
                prefs.lastTickAt = System.currentTimeMillis()

                var summary = MinerState.status.value.summary
                if (tick == 1 || tick % INVENTORY_EVERY_TICKS == 0) {
                    summary = runCatching { summarise(DropSpikeApp.instance.api.inventory()) }
                        .getOrElse { "Inventory failed: ${it.message}" }
                    DiagLog.i("service: tick $tick, gap ${gap}s, $summary")
                }
                MinerState.update {
                    it.copy(
                        ticks = tick,
                        maxGapSec = if (tick == 1) 0 else maxOf(it.maxGapSec, gap),
                        lastTickAt = prefs.lastTickAt,
                        summary = summary,
                    )
                }
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Tick $tick · $summary"))
                delay(TICK_MS)
            }
        }
    }

    private fun summarise(drops: List<DropProgress>): String {
        val open = drops.filter { !it.claimed }
        if (open.isEmpty()) return "No drops in progress"
        val best = open.maxBy { it.minutes.toFloat() / it.required.coerceAtLeast(1) }
        return "${open.size} in progress · ${best.game}: ${best.minutes}/${best.required} min"
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        DropSpikeApp.instance.prefs.sessionActive = false
        MinerState.update { it.copy(running = false) }
        DiagLog.i("service: stopped")
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
            .setContentTitle("DropSpike is running")
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
        private const val INVENTORY_EVERY_TICKS = 5

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MinerService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, MinerService::class.java).setAction(ACTION_STOP))
    }
}
