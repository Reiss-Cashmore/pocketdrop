package dev.dropspike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.WebSettings
import dev.dropspike.data.Prefs
import dev.dropspike.twitch.TwitchApi

class DropSpikeApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var api: TwitchApi
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // Use the WebView's own user agent for API calls so requests look like they come
        // from the same browser that minted the integrity token.
        api = TwitchApi(prefs, userAgent = WebSettings.getDefaultUserAgent(this))

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_MINER,
                getString(R.string.channel_miner),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_MINER = "miner"
        lateinit var instance: DropSpikeApp
            private set
    }
}
