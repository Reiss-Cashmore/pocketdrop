package dev.dropspike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.WebSettings
import android.webkit.WebView
import dev.dropspike.data.ClaimLog
import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs
import dev.dropspike.data.SystemInfo
import dev.dropspike.data.UptimeLog
import dev.dropspike.service.WakeWorker
import dev.dropspike.twitch.TwitchApi
import dev.dropspike.ui.applyNightMode

class DropSpikeApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var api: TwitchApi
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        installCrashLogger()
        prefs = Prefs(this)
        UptimeLog.init(this)
        ClaimLog.init(this)
        DiagLog.i("process start: DropSpike ${SystemInfo.appVersion(this)} · ${SystemInfo.device()} · WebView ${SystemInfo.webView()}")
        SystemInfo.power(this).forEach { (k, v) -> DiagLog.i("process start: $k = $v") }
        // The most recent exit tells us how the previous process died (memory, user, crash, …).
        SystemInfo.exitReasons(this, max = 3).forEach { DiagLog.i("previous exit: $it") }
        // Debug builds only: lets chrome://inspect on a desktop attach to the app's WebViews
        // (which hold a signed-in Twitch session) over USB.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        // Use the WebView's own user agent for API calls so requests look like they come
        // from the same browser that minted the integrity token.
        api = TwitchApi(prefs, userAgent = WebSettings.getDefaultUserAgent(this))
        applyNightMode(this, prefs.themeMode)
        WakeWorker.schedule(this)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_MINER,
                getString(R.string.channel_miner),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** Write uncaught exceptions to the persistent log before the process dies. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                DiagLog.e("CRASH on thread ${thread.name}", e)
                e.stackTrace.take(25).forEach { DiagLog.e("    at $it") }
                e.cause?.let { c -> DiagLog.e("  caused by ${c.javaClass.name}: ${c.message}") }
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        const val CHANNEL_MINER = "miner"
        lateinit var instance: DropSpikeApp
            private set
    }
}
