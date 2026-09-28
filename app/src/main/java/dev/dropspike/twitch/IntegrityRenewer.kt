package dev.dropspike.twitch

import android.content.Context
import android.webkit.WebView
import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a Client-Integrity token available for background work (claims) by minting in an
 * off-screen WebView, "Light" page, like the manual mint. Web-client tokens last about an hour.
 */
object IntegrityRenewer {
    private const val MARGIN_MS = 5 * 60 * 1000L

    fun isFresh(prefs: Prefs) =
        prefs.integrityToken != null && prefs.integrityExpiry - MARGIN_MS > System.currentTimeMillis()

    suspend fun ensureFresh(context: Context, prefs: Prefs, siteUrl: String): Boolean {
        if (isFresh(prefs)) return true
        val token = prefs.authToken ?: return false
        return withContext(Dispatchers.Main) {
            val view = WebView(context.applicationContext)
            view.measure(1080, 2400)
            view.layout(0, 0, 1080, 2400)
            try {
                val minted = IntegrityMinter(view).mint(
                    page = MintPage.Light,
                    siteUrl = siteUrl,
                    authToken = token,
                    setSessionCookie = prefs.clientId == Prefs.WEB_CLIENT_ID,
                    headers = mapOf(
                        "Client-ID" to prefs.clientId,
                        "Authorization" to "OAuth $token",
                        "Client-Session-Id" to prefs.sessionId,
                    ),
                    deviceId = prefs.deviceId,
                    scriptUrl = prefs.kasadaScriptUrl,
                    probeQuery = null,
                )
                prefs.integrityToken = minted.token
                prefs.integrityExpiry = minted.expiresAtMs
                DiagLog.i("integrity: renewed off screen, ${(minted.expiresAtMs - System.currentTimeMillis()) / 60_000}min")
                true
            } catch (e: Exception) {
                DiagLog.i("integrity: off-screen renewal failed: ${e.message}")
                false
            } finally {
                view.destroy()
            }
        }
    }
}
