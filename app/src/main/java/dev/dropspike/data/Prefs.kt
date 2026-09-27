package dev.dropspike.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Plain app-private storage. Fine for a spike on a personal device; move the auth token
 * into Keystore-backed storage before this ships to anyone else.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dropspike", Context.MODE_PRIVATE)

    var authToken: String?
        get() = sp.getString("auth_token", null)
        set(v) = sp.edit { putString("auth_token", v) }

    /** Client ID the auth token was issued to (from /oauth2/validate). */
    var clientId: String
        get() = sp.getString("client_id", null) ?: WEB_CLIENT_ID
        set(v) = sp.edit { putString("client_id", v) }

    var login: String?
        get() = sp.getString("login", null)
        set(v) = sp.edit { putString("login", v) }

    var userId: String?
        get() = sp.getString("user_id", null)
        set(v) = sp.edit { putString("user_id", v) }

    /** Twitch's `unique_id` cookie when we have it, otherwise a stable random ID. */
    var deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").also { deviceId = it }
        set(v) = sp.edit { putString("device_id", v) }

    /** Per-process session ID, sent both when minting and on GQL calls (as DropForge does). */
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(16)

    var integrityToken: String?
        get() = sp.getString("integrity_token", null)
        set(v) = sp.edit { putString("integrity_token", v) }

    /** Epoch millis. */
    var integrityExpiry: Long
        get() = sp.getLong("integrity_expiry", 0L)
        set(v) = sp.edit { putLong("integrity_expiry", v) }

    var kasadaScriptUrl: String
        get() = sp.getString("kasada_url", null) ?: DEFAULT_KASADA_URL
        set(v) = sp.edit { putString("kasada_url", v) }

    // Background-session bookkeeping, so a process kill by the OS is visible afterwards.
    var sessionActive: Boolean
        get() = sp.getBoolean("session_active", false)
        set(v) = sp.edit { putBoolean("session_active", v) }

    var sessionStartedAt: Long
        get() = sp.getLong("session_started", 0L)
        set(v) = sp.edit { putLong("session_started", v) }

    var lastTickAt: Long
        get() = sp.getLong("last_tick", 0L)
        set(v) = sp.edit { putLong("last_tick", v) }

    fun signOut() {
        sp.edit {
            remove("auth_token"); remove("client_id"); remove("login"); remove("user_id")
            remove("integrity_token"); remove("integrity_expiry")
        }
    }

    fun clearIntegrity() {
        sp.edit { remove("integrity_token"); remove("integrity_expiry") }
    }

    companion object {
        const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        // Same Kasada loader Streamlink injects (plugins/twitch.py, TwitchClientIntegrity).
        // Twitch rotates it occasionally; it is editable in the app.
        const val DEFAULT_KASADA_URL =
            "https://k.twitchcdn.net/149e9513-01fa-4fb0-aad4-566afd725d1b/2d206a39-8ed7-437e-a3be-862e0f06eea3/p.js"
    }
}
