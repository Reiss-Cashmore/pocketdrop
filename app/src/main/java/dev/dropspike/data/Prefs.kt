package dev.dropspike.data

import android.content.Context
import androidx.core.content.edit
import dev.dropspike.twitch.WatchedGame
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * App-private settings. The Twitch sign-in and integrity tokens are encrypted with a
 * Keystore key ([SecureStore]); everything else is plain.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dropspike", Context.MODE_PRIVATE)

    var authToken: String?
        get() = secret("auth_token")
        set(v) = setSecret("auth_token", v)

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
        get() = secret("integrity_token")
        set(v) = setSecret("integrity_token", v)

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

    /** Start mining without a tap whenever the app wakes (background check, app opened, reboot, update). */
    var autoMine: Boolean
        get() = sp.getBoolean("auto_mine", true)
        set(v) = sp.edit { putBoolean("auto_mine", v) }

    /** Epoch millis until which auto mine holds off, after the user taps Stop. */
    var autoMinePausedUntil: Long
        get() = sp.getLong("auto_mine_paused_until", 0L)
        set(v) = sp.edit { putLong("auto_mine_paused_until", v) }

    /** Watch two channels (different games) at once. Twitch may not credit both consistently. */
    var twoChannels: Boolean
        get() = sp.getBoolean("two_channels", false)
        set(v) = sp.edit { putBoolean("two_channels", v) }

    /** Only mine while the phone is charging. */
    var onlyCharging: Boolean
        get() = sp.getBoolean("only_charging", false)
        set(v) = sp.edit { putBoolean("only_charging", v) }

    /** Only mine on Wi-Fi (or Ethernet), never mobile data. */
    var onlyWifi: Boolean
        get() = sp.getBoolean("only_wifi", false)
        set(v) = sp.edit { putBoolean("only_wifi", v) }

    /** App theme: "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString("theme_mode", null) ?: "system"
        set(v) = sp.edit { putString("theme_mode", v) }

    /** Material You wallpaper colours instead of the PocketDrop palette. */
    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", false)
        set(v) = sp.edit { putBoolean("dynamic_color", v) }

    var lastWakeCheck: String
        get() = sp.getString("last_wake_check", null).orEmpty()
        set(v) = sp.edit { putString("last_wake_check", v) }

    fun signOut() {
        sp.edit {
            remove("auth_token"); remove("auth_token_enc"); remove("client_id"); remove("login"); remove("user_id")
            remove("integrity_token"); remove("integrity_token_enc"); remove("integrity_expiry")
        }
        secrets.clear()
    }

    fun clearIntegrity() {
        sp.edit { remove("integrity_token"); remove("integrity_token_enc"); remove("integrity_expiry") }
        secrets.remove("integrity_token")
    }

    // Secrets live encrypted under "<name>_enc". A plain value from an older version is
    // encrypted and removed the first time it's read.
    // Decrypted values are cached in memory: tokens are read on every API call.
    private val secrets = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun secret(name: String): String? {
        secrets[name]?.let { return it }
        sp.getString("${name}_enc", null)?.let { enc -> return SecureStore.decrypt(enc)?.also { secrets[name] = it } }
        val legacy = sp.getString(name, null) ?: return null
        setSecret(name, legacy)
        DiagLog.i("prefs: moved $name into encrypted storage")
        return legacy
    }

    private fun setSecret(name: String, value: String?) = sp.edit {
        if (value == null) { secrets.remove(name) } else { secrets[name] = value }
        remove(name)
        if (value == null) remove("${name}_enc") else putString("${name}_enc", SecureStore.encrypt(value))
    }

    private fun encodeGames(games: List<WatchedGame>) = JSONArray().apply {
        games.forEach { put(
                JSONObject().put("id", it.id).put("name", it.name).put("slug", it.slug).put("campaigns", it.campaigns)
                    .put("needsLink", it.needsLink).put("linkUrl", it.linkUrl ?: ""),
            ) }
    }.toString()

    private fun decodeGames(raw: String?): List<WatchedGame> = runCatching {
        JSONArray(raw ?: return emptyList()).let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                WatchedGame(
                    j.optString("id"), j.optString("name"), j.optString("slug"), j.optInt("campaigns"),
                    needsLink = j.optBoolean("needsLink"), linkUrl = j.optString("linkUrl").ifEmpty { null },
                )
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
