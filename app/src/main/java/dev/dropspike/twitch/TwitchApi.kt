package dev.dropspike.twitch

import dev.dropspike.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TokenInfo(val login: String, val userId: String, val clientId: String, val expiresInSec: Long)

data class Campaign(val id: String, val name: String, val game: String, val status: String, val linked: Boolean?)

/** `campaigns == null` means Twitch returned `dropCampaigns: null`, i.e. the integrity gate. */
data class DashboardResult(val campaigns: List<Campaign>?, val errors: List<String>)

/** Clients whose device-code login Twitch still accepted on 2026-09-23 (rangermix/TwitchDropsMiner#118). */
enum class DeviceClient(val label: String, val clientId: String) {
    MobileWeb("Mobile web", "r8s4dac0uhzifbpu9sjdiwzctle17ff"),
    SmartTv("Smart TV", "ue6666qo983tsx6so1t0vnawi233wa"),
}

data class DeviceCode(
    val client: DeviceClient,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSec: Long,
    val expiresAtMs: Long,
)

data class DropProgress(val campaign: String, val game: String, val drop: String, val minutes: Int, val required: Int, val claimed: Boolean)

class TwitchApi(private val prefs: Prefs, private val userAgent: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun validate(token: String): TokenInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://id.twitch.tv/oauth2/validate")
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

    suspend fun dashboard(useIntegrity: Boolean): DashboardResult {
        val root = gql(Queries.dashboard(), useIntegrity)
        val errors = root.errorMessages()
        val arr = root.optJSONObject("data")?.optJSONObject("currentUser")?.optJSONArray("dropCampaigns")
            ?: return DashboardResult(null, errors)
        val list = arr.objects().map { c ->
            Campaign(
                id = c.optString("id"),
                name = c.optString("name"),
                game = c.optJSONObject("game")?.let { it.optString("displayName").ifEmpty { it.optString("name") } }.orEmpty(),
                status = c.optString("status"),
                linked = c.optJSONObject("self")?.takeIf { it.has("isAccountConnected") }?.optBoolean("isAccountConnected"),
            )
        }
        return DashboardResult(list, errors)
    }

    /** Inventory is not integrity-gated today, so it works for background polling. */
    suspend fun inventory(): List<DropProgress> {
        val root = gql(Queries.inventory(), useIntegrity = false)
        root.errorMessages().takeIf { it.isNotEmpty() }?.let { throw IOException("Inventory: ${it.joinToString()}") }
        val campaigns = root.optJSONObject("data")?.optJSONObject("currentUser")
            ?.optJSONObject("inventory")?.optJSONArray("dropCampaignsInProgress") ?: return emptyList()
        return campaigns.objects().flatMap { c ->
            val game = c.optJSONObject("game")?.let { it.optString("displayName").ifEmpty { it.optString("name") } }.orEmpty()
            (c.optJSONArray("timeBasedDrops") ?: JSONArray()).objects().map { d ->
                val self = d.optJSONObject("self")
                DropProgress(
                    campaign = c.optString("name"),
                    game = game,
                    drop = d.optString("name"),
                    minutes = self?.optInt("currentMinutesWatched") ?: 0,
                    required = d.optInt("requiredMinutesWatched"),
                    claimed = self?.optBoolean("isClaimed") ?: false,
                )
            }
        }
    }

    private suspend fun gql(body: JSONObject, useIntegrity: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val token = prefs.authToken ?: throw IOException("Not signed in")
        val builder = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .post(body.toString().toRequestBody(JSON))
            .header("Client-Id", prefs.clientId)
            .header("Authorization", "OAuth $token")
            .header("X-Device-Id", prefs.deviceId)
            .header("Client-Session-Id", prefs.sessionId)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US")
            .header("User-Agent", userAgent)
            .header("Origin", "https://www.twitch.tv")
            .header("Referer", "https://www.twitch.tv/")
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
                if (!resp.isSuccessful) throw IOException("GQL HTTP ${resp.code}: ${text.take(200)}")
                JSONObject(text)
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

    fun inventory() = persisted(
        "Inventory",
        "8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404",
        JSONObject().put("fetchRewardCampaigns", false),
    )
}
