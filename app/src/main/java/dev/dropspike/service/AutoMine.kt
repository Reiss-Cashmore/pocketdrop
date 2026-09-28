package dev.dropspike.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs
import dev.dropspike.data.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Auto mine": whenever the app wakes up and isn't mining, look for a live drops channel for a
 * watched (or in-progress) game and start mining without waiting for a tap. The background check
 * (WakeWorker) does the same on its schedule; this covers the app being opened, and via
 * [BootReceiver] a reboot or an app update.
 */
object AutoMine {
    private const val MIN_GAP_MS = 2 * 60_000L
    private const val PAUSE_MS = 60 * 60_000L

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastCheckAt = 0L

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** Checks once (throttled) and starts mining if something is live. Safe to call often. */
    suspend fun check(context: Context, trigger: String) {
        val prefs = DropSpikeApp.instance.prefs
        if (!prefs.autoMine || prefs.authToken == null || MinerState.status.value.running) return
        if (paused(prefs)) {
            DiagLog.i("auto mine: skipped ($trigger), paused after Stop until ${SystemInfo.time(prefs.autoMinePausedUntil)}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastCheckAt != 0L && now - lastCheckAt < MIN_GAP_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastCheckAt = now
        _checking.value = true
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        try {
            DiagLog.i("auto mine: checking ($trigger)")
            val found = Miner(context, prefs, DropSpikeApp.instance.api).hasWork()
            if (found == null) {
                prefs.lastWakeCheck = "$stamp · nothing live ($trigger)"
                DiagLog.i("auto mine: nothing to mine")
                return
            }
            if (MinerState.status.value.running) return
            val started = runCatching { MinerService.start(context, reason = "auto mine, $trigger: $found") }
            prefs.lastWakeCheck = "$stamp · $found · ${if (started.isSuccess) "started mining" else "start blocked"}"
            DiagLog.i("auto mine: $found → ${started.exceptionOrNull()?.let { "start blocked (${it.javaClass.simpleName}: ${it.message})" } ?: "started mining"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.lastWakeCheck = "$stamp · check failed: ${e.message}"
            DiagLog.i("auto mine: check failed: ${e.message}")
        } finally {
            inFlight.set(false)
            _checking.value = false
        }
    }

    /** The user tapped Stop: don't undo that the next time the app opens or a check runs. */
    fun pauseAfterStop() {
        val prefs = DropSpikeApp.instance.prefs
        prefs.autoMinePausedUntil = System.currentTimeMillis() + PAUSE_MS
        DiagLog.i("auto mine: paused for ${PAUSE_MS / 60_000} min after Stop")
    }

    fun paused(prefs: Prefs) = prefs.autoMinePausedUntil > System.currentTimeMillis()

    /** Runs the wake check once, soon, from the background (reboot, update). */
    fun checkSoon(context: Context, trigger: String) {
        val request = OneTimeWorkRequestBuilder<WakeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(WakeWorker.KEY_TRIGGER to trigger))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("wake-now", ExistingWorkPolicy.REPLACE, request)
        DiagLog.i("auto mine: one-off check queued ($trigger)")
    }
}

/** After a reboot or an app update: re-arm the background check and look for drops once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone restarted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        DiagLog.i("event: $trigger")
        WakeWorker.schedule(context)
        val prefs = DropSpikeApp.instance.prefs
        if (prefs.autoMine && prefs.authToken != null) AutoMine.checkSoon(context, trigger)
    }
}
