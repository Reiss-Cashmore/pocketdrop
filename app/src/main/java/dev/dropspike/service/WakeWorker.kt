package dev.dropspike.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.dropspike.DropSpikeApp
import dev.dropspike.R
import dev.dropspike.data.DiagLog
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Periodic background check: is a watched (or in-progress) game live with drops? If so, start
 * the mining service. Android only lets a background job start a foreground service when the app
 * is exempt from battery optimisation; otherwise we post a "tap to start" notification instead.
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = DropSpikeApp.instance
        val prefs = app.prefs
        val stamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        if (prefs.authToken == null) return Result.success()
        if (MinerState.status.value.running) {
            prefs.lastWakeCheck = "$stamp · already mining"
            return Result.success()
        }
        val found = try {
            Miner(applicationContext, prefs, app.api).hasWork()
        } catch (e: Exception) {
            prefs.lastWakeCheck = "$stamp · check failed: ${e.message}"
            DiagLog.i("wake: check failed: ${e.message}")
            return Result.retry()
        }
        if (found == null) {
            prefs.lastWakeCheck = "$stamp · nothing live"
            DiagLog.i("wake: nothing to mine")
            return Result.success()
        }
        val started = runCatching { MinerService.start(applicationContext) }
        prefs.lastWakeCheck = "$stamp · $found · ${if (started.isSuccess) "started mining" else "sent notification"}"
        DiagLog.i("wake: $found → ${started.exceptionOrNull()?.let { "start blocked (${it.javaClass.simpleName}), notifying" } ?: "started mining"}")
        if (started.isFailure) notifyAvailable(found)
        return Result.success()
    }

    private fun notifyAvailable(what: String) {
        val ctx = applicationContext
        // A tap is a user action, so starting the foreground service from it is allowed.
        val start = PendingIntent.getForegroundService(
            ctx, 2, Intent(ctx, MinerService::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, DropSpikeApp.CHANNEL_MINER)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle("Drops available")
            .setContentText("$what. Tap to start mining.")
            .setContentIntent(start)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, notification) }
    }

    companion object {
        private const val NAME = "wake-check"
        private const val NOTIFY_ID = 2

        /** (Re)schedule for the saved interval, or cancel when it is 0. */
        fun schedule(context: Context) {
            val minutes = DropSpikeApp.instance.prefs.wakeIntervalMin
            val wm = WorkManager.getInstance(context)
            if (minutes <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<WakeWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
