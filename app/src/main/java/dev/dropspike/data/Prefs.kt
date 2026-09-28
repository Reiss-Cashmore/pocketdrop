package dev.dropspike.data

import android.content.Context
import androidx.core.content.edit
import dev.dropspike.twitch.WatchedGame
import org.json.JSONArray
import org.json.JSONObject
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

    /** Games to prefer when mining, in the user's order. */
    var watchedGames: List<WatchedGame>
        get() = decodeGames(sp.getString("watched_games", null))
        set(v) = sp.edit { putString("watched_games", encodeGames(v)) }

    /** Last known games with active campaigns, for the picker. */
    var gameCatalog: List<WatchedGame>
        get() = decodeGames(sp.getString("game_catalog", null))
        set(v) = sp.edit { putString("game_catalog", encodeGames(v)) }

    var gameCatalogAt: Long
        get() = sp.getLong("game_catalog_at", 0L)
        set(v) = sp.edit { putLong("game_catalog_at", v) }

    /** Only mine watched games (otherwise watched first, then anything in progress). */
    var onlyWatched: Boolean
        get() = sp.getBoolean("only_watched", false)
        set(v) = sp.edit { putBoolean("only_watched", v) }

    /** Background check interval in minutes; 0 = off. */
    var wakeIntervalMin: Int
        get() = sp.getInt("wake_interval", 0)
        set(v) = sp.edit { putInt("wake_interval", v) }

    var lastWakeCheck: String
        get() = sp.getString("last_wake_check", null).orEmpty()
        set(v) = sp.edit { putString("last_wake_check", v) }

    fun signOut() {
        sp.edit {
            remove("auth_token"); remove("client_id"); remove("login"); remove("user_id")
            remove("integrity_token"); remove("integrity_expiry")
        }
    }

    fun clearIntegrity() {
        sp.edit { remove("integrity_token"); remove("integrity_expiry") }
    }

    private fun encodeGames(games: List<WatchedGame>) = JSONArray().apply {
        games.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("slug", it.slug).put("campaigns", it.campaigns)) }
    }.toString()

    private fun decodeGames(raw: String?): List<WatchedGame> = runCatching {
        JSONArray(raw ?: return emptyList()).let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                WatchedGame(j.optString("id"), j.optString("name"), j.optString("slug"), j.optInt("campaigns"))
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        // Same Kasada loader Streamlink injects (plugins/twitch.py, TwitchClientIntegrity).
        // Twitch rotates it occasionally; it is editable in the app.
        const val DEFAULT_KASADA_URL =
            "https://k.twitchcdn.net/149e9513-01fa-4fb0-aad4-566afd725d1b/2d206a39-8ed7-437e-a3be-862e0f06eea3/p.js"
    }
}
