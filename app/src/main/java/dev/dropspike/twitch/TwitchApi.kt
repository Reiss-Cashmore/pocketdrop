package dev.dropspike.twitch

import dev.dropspike.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.dropspike.data.DiagLog
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TokenInfo(val login: String, val userId: String, val clientId: String, val expiresInSec: Long)

data class Campaign(
    val id: String,
    val name: String,
    val game: String,
    val status: String,
    val linked: Boolean?,
    val gameId: String = "",
    val gameSlug: String? = null,
    val endsAtMs: Long = Long.MAX_VALUE,
)

/** `campaigns == null` means Twitch returned `dropCampaigns: null`, i.e. the integrity gate. */
data class DashboardResult(val campaigns: List<Campaign>?, val errors: List<String>)

/**
 * Clients whose device-code login Twitch still accepted on 2026-09-23 (rangermix/TwitchDropsMiner#118).
 * Smart TV tokens work on GQL (campaign list gated). Mobile-web tokens validate but GQL answered
 * every request with 401 "Authorization token is invalid" in testing on 2026-09-28.
 */
enum class DeviceClient(val label: String, val clientId: String) {
    SmartTv("Smart TV", "ue6666qo983tsx6so1t0vnawi233wa"),
    MobileWeb("Mobile web", "r8s4dac0uhzifbpu9sjdiwzctle17ff"),
}

/** Which Twitch site the GQL request claims to come from (Origin/Referer). */
enum class ApiOrigin(val label: String, val url: String) {
    Www("www", "https://www.twitch.tv"),
    Mobile("m.", "https://m.twitch.tv"),
}

data class DeviceCode(
    val client: DeviceClient,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSec: Long,
    val expiresAtMs: Long,
)

data class InvDrop(
    val id: String,
    val name: String,
    val required: Int,
    val minutes: Int,
    val claimed: Boolean,
    val instanceId: String?,
    val preconditionsMet: Boolean,
    val endsAtMs: Long,
) {
    val done get() = required in 1..minutes
}

data class InvCampaign(
    val id: String,
    val name: String,
    val gameId: String,
    val gameName: String,
    val gameSlug: String?,
    val endsAtMs: Long,
    /** Non-empty when only these channels count towards the campaign. */
    val allowedLogins: List<String>,
    val drops: List<InvDrop>,
)

/** A game the user asked to mine; also the entries of the game picker. */
data class WatchedGame(val id: String, val name: String, val slug: String, val campaigns: Int = 0) {
    fun matches(gameId: String, gameName: String) =
        (id.isNotEmpty() && id == gameId) || name.equals(gameName, ignoreCase = true)
}

data class LiveStream(
    val channelId: String,
    val login: String,
    val broadcastId: String,
    val gameId: String,
    val gameName: String,
    val viewers: Int,
)

data class DropProgress(val campaign: String, val game: String, val drop: String, val minutes: Int, val required: Int, val claimed: Boolean)

class TwitchApi(private val prefs: Prefs, private val userAgent: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(::logCall)
        .build()

    /** One log line per request: what it was (tag), status, time, size. Never headers or bodies. */
    private fun logCall(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val label = req.tag(String::class.java) ?: "${req.url.host}${req.url.encodedPath}"
        val started = System.nanoTime()
        return try {
            chain.proceed(req).also { resp ->
                val ms = (System.nanoTime() - started) / 1_000_000
                val size = resp.body?.contentLength()?.takeIf { it >= 0 }?.let { " ${it}B" } ?: ""
                val line = "http $label → ${resp.code} in ${ms}ms$size"
                if (resp.isSuccessful) DiagLog.i(line) else DiagLog.w(line)
            }
        } catch (e: IOException) {
            DiagLog.e("http $label failed after ${(System.nanoTime() - started) / 1_000_000}ms", e)
            throw e
        }
    }

    suspend fun validate(token: String): TokenInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://id.twitch.tv/oauth2/validate")
            .tag(String::class.java, "validate")
            .header("Authorization", "OAuth $token")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("validate HTTP ${resp.code}: ${body.take(200)}")
            val j = JSONObject(body)
            TokenInfo(
                login = j.optString("login"),
                userId = j.optString("user_id"),
                clientId = j.optString("client_id"),
                expiresInSec = j.optLong("expires_in"),
            )
        }
    }

    /** Step 1 of Twitch's device-code login (DropForge `_oauth_login`). No scopes are needed. */
    suspend fun startDeviceLogin(client: DeviceClient): DeviceCode = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://id.twitch.tv/oauth2/device")
            .tag(String::class.java, "device-code (${client.label})")
            .post(FormBody.Builder().add("client_id", client.clientId).add("scopes", "").build())
            .header("Client-Id", client.clientId)
            .header("X-Device-Id", prefs.deviceId)
            .header("User-Agent", userAgent)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val j = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (resp.code != 200 || !j.has("device_code")) {
                val reason = j.optString("message").ifEmpty { j.optString("error") }.ifEmpty { body.take(200) }
                throw IOException("Twitch device login failed (HTTP ${resp.code}): $reason")
            }
            DeviceCode(
                client = client,
                deviceCode = j.getString("device_code"),
                userCode = j.getString("user_code"),
                verificationUri = j.getString("verification_uri"),
                intervalSec = j.optLong("interval", 5),
                expiresAtMs = System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000,
            )
        }
    }

    /** Step 2: one poll. Returns the access token, or null while the user hasn't entered the code. */
    suspend fun pollDeviceLogin(code: DeviceCode): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://id.twitch.tv/oauth2/token")
            .tag(String::class.java, "device-token poll")
            .post(
                FormBody.Builder()
                    .add("client_id", code.client.clientId)
                    .add("device_code", code.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build(),
            )
            .header("Client-Id", code.client.clientId)
            .header("X-Device-Id", prefs.deviceId)
            .header("User-Agent", userAgent)
            .build()
        http.newCall(req).execute().use { resp ->
            // Twitch answers 400 until the code has been entered.
            if (resp.code != 200) return@withContext null
            JSONObject(resp.body?.string().orEmpty()).getString("access_token")
        }
    }

    /** The site that matches the signed-in client: m.twitch.tv for mobile-web tokens. */
    fun defaultOrigin(): ApiOrigin =
        if (prefs.clientId == DeviceClient.MobileWeb.clientId) ApiOrigin.Mobile else ApiOrigin.Www

    suspend fun dashboard(useIntegrity: Boolean, origin: ApiOrigin = defaultOrigin()): DashboardResult {
        val root = gql(Queries.dashboard(), useIntegrity, origin)
        val errors = root.errorMessages()
        val arr = root.optJSONObject("data")?.optJSONObject("currentUser")?.optJSONArray("dropCampaigns")
            ?: return DashboardResult(null, errors)
        val list = arr.objects().map { c ->
            Campaign(
                id = c.optString("id"),
                name = c.optString("name"),
                game = c.optJSONObject("game")?.let { it.optString("displayName").ifEmpty { it.optString("name") } }.orEmpty(),
                gameId = c.optJSONObject("game")?.optString("id").orEmpty(),
                gameSlug = c.optJSONObject("game")?.optString("slug")?.ifEmpty { null },
                endsAtMs = parseTime(c.optString("endAt")),
                status = c.optString("status"),
                linked = c.optJSONObject("self")?.takeIf { it.has("isAccountConnected") }?.optBoolean("isAccountConnected"),
            )
        }
        return DashboardResult(list, errors)
    }

    /** Inventory is not integrity-gated today, so it works for background polling. */
    suspend fun inventory(): List<DropProgress> = inventoryCampaigns().flatMap { c ->
        c.drops.map { d -> DropProgress(c.name, c.gameName, d.name, d.minutes, d.required, d.claimed) }
    }

    /** In-progress campaigns with everything the miner needs (fields as DropForge's inventory.py). */
    suspend fun inventoryCampaigns(): List<InvCampaign> {
        val root = gql(Queries.inventory(), useIntegrity = false)
        root.errorMessages().takeIf { it.isNotEmpty() }?.let { throw IOException("Inventory: ${it.joinToString()}") }
        val campaigns = root.optJSONObject("data")?.optJSONObject("currentUser")
            ?.optJSONObject("inventory")?.optJSONArray("dropCampaignsInProgress") ?: return emptyList()
        return campaigns.objects().map { c ->
            val game = c.optJSONObject("game") ?: JSONObject()
            val allow = c.optJSONObject("allow")
            val allowEnabled = allow?.optBoolean("isEnabled", true) ?: false
            InvCampaign(
                id = c.optString("id"),
                name = c.optString("name"),
                gameId = game.optString("id"),
                gameName = game.optString("displayName").ifEmpty { game.optString("name") },
                gameSlug = game.optString("slug").ifEmpty { null },
                endsAtMs = parseTime(c.optString("endAt")),
                allowedLogins = if (allowEnabled) {
                    (allow?.optJSONArray("channels") ?: JSONArray()).objects().map { it.optString("name") }.filter { it.isNotEmpty() }
                } else emptyList(),
                drops = (c.optJSONArray("timeBasedDrops") ?: JSONArray()).objects().map { d ->
                    val self = d.optJSONObject("self")
                    InvDrop(
                        id = d.optString("id"),
                        name = d.optString("name"),
                        required = d.optInt("requiredMinutesWatched"),
                        minutes = self?.optInt("currentMinutesWatched") ?: 0,
                        claimed = self?.optBoolean("isClaimed") ?: false,
                        instanceId = self?.optString("dropInstanceID")?.takeIf { it.isNotEmpty() && it != "null" },
                        preconditionsMet = self?.optBoolean("hasPreconditionsMet", true) ?: true,
                        endsAtMs = parseTime(d.optString("endAt")),
                    )
                },
            )
        }
    }

    /** Live, drops-enabled streams for a game (DropForge get_live_streams). */
    suspend fun liveStreams(game: InvCampaign, limit: Int = 20): List<LiveStream> =
        liveStreams(WatchedGame(game.gameId, game.gameName, game.gameSlug ?: slugOf(game.gameName)), limit)

    suspend fun liveStreams(game: WatchedGame, limit: Int = 20): List<LiveStream> {
        val slug = game.slug
        val root = gql(Queries.gameDirectory(slug, limit), useIntegrity = false)
        val edges = root.optJSONObject("data")?.optJSONObject("game")?.optJSONObject("streams")?.optJSONArray("edges")
            ?: return emptyList()
        return edges.objects().mapNotNull { e ->
            val node = e.optJSONObject("node") ?: return@mapNotNull null
            val caster = node.optJSONObject("broadcaster") ?: return@mapNotNull null
            val g = node.optJSONObject("game")
            LiveStream(
                channelId = caster.optString("id"),
                login = caster.optString("login"),
                broadcastId = node.optString("id"),
                gameId = g?.optString("id") ?: game.id,
                gameName = g?.optString("displayName")?.ifEmpty { g.optString("name") } ?: game.name,
                viewers = node.optInt("viewersCount"),
            )
        }
    }

    /** One channel's live stream, or null if offline (GetStreamInfo). */
    suspend fun streamInfo(login: String): LiveStream? {
        val root = gql(Queries.streamInfo(login), useIntegrity = false)
        val user = root.optJSONObject("data")?.optJSONObject("user") ?: return null
        val stream = user.optJSONObject("stream") ?: return null
        val game = user.optJSONObject("broadcastSettings")?.optJSONObject("game")
        return LiveStream(
            channelId = user.optString("id"),
            login = login,
            broadcastId = stream.optString("id"),
            gameId = game?.optString("id").orEmpty(),
            gameName = game?.optString("displayName")?.ifEmpty { game.optString("name") }.orEmpty(),
            viewers = stream.optInt("viewersCount"),
        )
    }

    /**
     * The channel's spade (analytics) URL, scraped from its page as DropForge get_spade_url does:
     * directly from the page, or via the page's settings.<hash>.js.
     */
    suspend fun spadeUrl(login: String): String = withContext(Dispatchers.IO) {
        fun get(url: String, label: String): String = http.newCall(
            Request.Builder().url(url).tag(String::class.java, label).header("User-Agent", userAgent).build(),
        ).execute().use { it.body?.string().orEmpty() }

        val spade = Regex("\"spade_?url\": ?\"(https://[.\\w\\-/]+)\"", RegexOption.IGNORE_CASE)
        val settings = Regex("src=\"(https://[\\w.]+/config/settings\\.[0-9a-f]{32}\\.js)\"", RegexOption.IGNORE_CASE)
        val html = get("https://www.twitch.tv/$login", "channel page $login")
        spade.find(html)?.groupValues?.get(1)?.let { return@withContext it }
        val settingsUrl = settings.find(html)?.groupValues?.get(1) ?: throw IOException("spade URL: no settings script on channel page")
        spade.find(get(settingsUrl, "settings script"))?.groupValues?.get(1) ?: throw IOException("spade URL: not in settings script")
    }

    /** One "minute-watched" heartbeat (DropForge send_watch). Twitch answers 204 when accepted. */
    suspend fun sendMinuteWatched(spadeUrl: String, stream: LiveStream): Int = withContext(Dispatchers.IO) {
        val event = JSONArray().put(
            JSONObject()
                .put("event", "minute-watched")
                .put(
                    "properties",
                    JSONObject()
                        .put("broadcast_id", stream.broadcastId)
                        .put("channel_id", stream.channelId)
                        .put("channel", stream.login)
                        .put("client_time", java.time.Instant.now().toString())
                        .put("game", stream.gameName)
                        .put("game_id", stream.gameId)
                        .put("hidden", false)
                        .put("is_live", true)
                        .put("live", true)
                        .put("logged_in", true)
                        .put("minutes_logged", 1)
                        .put("muted", false)
                        .put("user_id", prefs.userId.orEmpty()),
                ),
        )
        val data = android.util.Base64.encodeToString(event.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val req = Request.Builder()
            .url(spadeUrl)
            .tag(String::class.java, "heartbeat ${stream.login} (broadcast ${stream.broadcastId}, game ${stream.gameName})")
            .post(FormBody.Builder().add("data", data).build())
            .header("User-Agent", userAgent)
            .build()
        http.newCall(req).execute().use { it.code }
    }

    /** Claims a finished drop (DropsPage_ClaimDropRewards, integrity-gated). Returns Twitch's status. */
    suspend fun claimDrop(instanceId: String): String {
        val root = gql(Queries.claimDrop(instanceId), useIntegrity = true)
        root.errorMessages().takeIf { it.isNotEmpty() }?.let { throw IOException("Claim: ${it.joinToString()}") }
        return root.optJSONObject("data")?.optJSONObject("claimDropRewards")?.optString("status") ?: "no result"
    }

    private suspend fun gql(
        body: JSONObject,
        useIntegrity: Boolean,
        origin: ApiOrigin = defaultOrigin(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = prefs.authToken ?: throw IOException("Not signed in")
        val builder = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .tag(String::class.java, "gql ${body.optString("operationName")}${if (useIntegrity) " +integrity" else ""} (${origin.label})")
            .post(body.toString().toRequestBody(JSON))
            .header("Client-Id", prefs.clientId)
            .header("Authorization", "OAuth $token")
            .header("X-Device-Id", prefs.deviceId)
            .header("Client-Session-Id", prefs.sessionId)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US")
            .header("User-Agent", userAgent)
            .header("Origin", origin.url)
            .header("Referer", "${origin.url}/")
        if (useIntegrity) {
            val integrity = prefs.integrityToken ?: throw IOException("No integrity token; mint one first")
            builder.header("Client-Integrity", integrity)
        }
        val request = builder.build()
        // Twitch sometimes answers PersistedQueryNotFound once; DropForge retries after a second.
        var root = JSONObject()
        for (attempt in 0..1) {
            root = http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
                JSONObject(text)
            }
            root.errorMessages().takeIf { it.isNotEmpty() }?.let {
                DiagLog.w("gql ${body.optString("operationName")} errors: ${it.joinToString()}")
            }
            if (attempt == 0 && root.errorMessages().contains("PersistedQueryNotFound")) delay(1_000) else break
        }
        root
    }

    private fun JSONObject.errorMessages(): List<String> =
        (optJSONArray("errors") ?: JSONArray()).objects().map { e ->
            // Integrity failures carry either this message or extensions.code == IntegrityCheckFailed.
            e.optJSONObject("extensions")?.optString("code")?.takeIf { it == "IntegrityCheckFailed" }
                ?: e.optString("message")
        }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** Persisted-query hashes copied from DevilXD/TwitchDropsMiner constants.py. */
internal object Queries {
    private fun persisted(name: String, sha: String, vars: JSONObject) = JSONObject()
        .put("operationName", name)
        .put("variables", vars)
        .put(
            "extensions",
            JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", sha)),
        )

    fun dashboard() = persisted(
        "ViewerDropsDashboard",
        "c16bb890cc8ce7647a96ee69cd313d423a378a3dedadf630a1017cde18975feb",
        JSONObject().put("fetchRewardCampaigns", false),
    )

    fun gameDirectory(slug: String, limit: Int) = persisted(
        "DirectoryPage_Game",
        "86bcceb4e8b1a51256ff8eed8bd8aae4acacf80d737efe904f84f3aeadf8cafd",
        JSONObject()
            .put("limit", limit)
            .put("slug", slug)
            .put("imageWidth", 50)
            .put("includeCostreaming", false)
            .put(
                "options",
                JSONObject()
                    .put("broadcasterLanguages", JSONArray())
                    .put("freeformTags", JSONObject.NULL)
                    .put("includeRestricted", JSONArray().put("SUB_ONLY_LIVE"))
                    .put("recommendationsContext", JSONObject().put("platform", "web"))
                    .put("sort", "RELEVANCE")
                    .put("systemFilters", JSONArray().put("DROPS_ENABLED"))
                    .put("tags", JSONArray())
                    .put("requestID", "JIRA-VXP-2397"),
            )
            .put("sortTypeIsRecency", false),
    )

    fun streamInfo(login: String) = persisted(
        "VideoPlayerStreamInfoOverlayChannel",
        "198492e0857f6aedead9665c81c5a06d67b25b58034649687124083ff288597d",
        JSONObject().put("channel", login),
    )

    fun claimDrop(instanceId: String) = persisted(
        "DropsPage_ClaimDropRewards",
        "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930",
        JSONObject().put("input", JSONObject().put("dropInstanceID", instanceId)),
    )

    fun inventory() = persisted(
        "Inventory",
        "8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404",
        JSONObject().put("fetchRewardCampaigns", false),
    )
}

/** DropForge Game.slug: lowercase, drop apostrophes, non-alphanumerics to single dashes. */
internal fun slugOf(name: String): String =
    name.lowercase().replace("'", "").replace(Regex("\\W+"), "-").replace(Regex("-{2,}"), "-").trim('-')

internal fun parseTime(iso: String): Long =
    runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
