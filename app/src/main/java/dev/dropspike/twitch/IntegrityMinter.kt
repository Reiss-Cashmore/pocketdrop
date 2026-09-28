package dev.dropspike.twitch

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.dropspike.data.DiagLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** How the page hosting Kasada is built. */
enum class MintPage(val label: String) {
    /** Streamlink's approach: blank document on the twitch.tv origin + Kasada's p.js only. */
    Light("Light"),

    /** DropForge's approach: load the real site signed in, wait, then add Kasada's p.js. */
    Full("Full site"),
}

/** Where the WebView lives while minting. */
enum class MintSurface(val label: String) {
    /** Attached to the window, on screen. Closest to a real browser. */
    Visible("On screen"),

    /** Never attached to a window: what a background renewal would have to use. */
    Offscreen("Off screen"),
}

/**
 * Result of the campaign query sent from *inside* the WebView with the fresh token.
 * If this passes but the app's own request fails, Twitch is rejecting our HTTP client's
 * fingerprint rather than the token (DropForge's diagnosis).
 */
data class BrowserProbe(val status: Int, val campaigns: Int?, val errors: List<String>)

data class IntegrityToken(val token: String, val expiresAtMs: Long, val claims: JSONObject?, val probe: BrowserProbe?) {
    /** Twitch puts its bot verdict in the token itself (a PASETO v4.public payload). */
    val isBadBot: String? get() = claims?.opt("is_bad_bot")?.toString()
}

/**
 * Mints a Twitch Client-Integrity token inside [webView] by letting Kasada's SDK patch
 * `window.fetch` and then calling `POST gql.twitch.tv/integrity`. Ported from DropForge's
 * network/integrity.py (Chrome over CDP there, an Android WebView here). Main thread only.
 */
class IntegrityMinter(private val webView: WebView) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun mint(
        page: MintPage,
        siteUrl: String,
        authToken: String,
        setSessionCookie: Boolean,
        headers: Map<String, String>,
        deviceId: String,
        scriptUrl: String,
        probeQuery: JSONObject?,
        timeoutMs: Long = 60_000,
    ): IntegrityToken = withTimeout(timeoutMs) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        // DropForge signs the page in (Network.setCookie) with a web-client token. Other clients'
        // tokens are not site sessions, and the site may sign them out, so leave those off.
        if (setSessionCookie) {
            cookies.setCookie(siteUrl, "auth-token=$authToken; Domain=.twitch.tv; Path=/; Secure")
        } else {
            cookies.setCookie(siteUrl, "auth-token=; Domain=.twitch.tv; Path=/; Secure; Max-Age=0")
        }
        cookies.flush()

        val loaded = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                DiagLog.i("mint: page started $url")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                DiagLog.i("mint: page finished $url")
                loaded.complete(Unit)
            }
        }

        when (page) {
            MintPage.Light -> webView.loadDataWithBaseURL(
                "$siteUrl/",
                "<!doctype html><html><head><meta name=viewport content='width=device-width'></head><body></body></html>",
                "text/html", "utf-8", null,
            )
            MintPage.Full -> webView.loadUrl("$siteUrl/")
        }
        loaded.await()
        // DropForge waits 5s after navigating so the site's own scripts settle.
        if (page == MintPage.Full) delay(5_000)

        val allHeaders = JSONObject(headers).put("x-device-id", deviceId)
        webView.evaluateJavascript(script(allHeaders, scriptUrl, probeQuery), null)

        var minted: IntegrityToken? = null
        while (minted == null) {
            delay(300)
            val state = evalJson(webView, "JSON.stringify(window.__ds || null)") ?: continue
            when (state.optString("state")) {
                "ok" -> minted = parse(state.getJSONObject("proof"))
                "error" -> throw IllegalStateException(state.optString("error"))
            }
        }
        checkNotNull(minted)
    }

    private suspend fun evalJson(view: WebView, js: String): JSONObject? {
        val result = CompletableDeferred<String?>()
        view.evaluateJavascript(js) { result.complete(it) }
        // evaluateJavascript returns the JSON encoding of the value; our value is itself a JSON string.
        val raw = result.await() ?: return null
        val inner = JSONTokener(raw).nextValue() as? String ?: return null
        return JSONTokener(inner).nextValue() as? JSONObject
    }

    private fun parse(proof: JSONObject): IntegrityToken {
        val token = proof.getString("token")
        // Twitch has sent both seconds and milliseconds here; DropForge handles both.
        val exp = proof.optDouble("expiration", 0.0)
        val expiresAtMs = if (exp > 1e11) exp.toLong() else (exp * 1000).toLong()
        val probe = proof.optJSONObject("probe")?.let { p ->
            BrowserProbe(
                status = p.optInt("status"),
                campaigns = if (p.isNull("campaigns")) null else p.optInt("campaigns"),
                errors = (p.optJSONArray("errors") ?: JSONArray()).objects().map {
                    it.optString("code").ifEmpty { it.optString("message") }
                },
            )
        }
        return IntegrityToken(token, expiresAtMs, decodeClaims(token), probe)
    }

    private fun script(headers: JSONObject, scriptUrl: String, probe: JSONObject?) = """
        (function () {
          window.__ds = { state: "pending" };
          const headers = $headers;
          const probe = ${probe ?: "null"};
          function fail(e) { window.__ds = { state: "error", error: String((e && e.message) || e) }; }
          async function fetchIntegrity() {
            // window.fetch is patched by Kasada; it adds its x-kpsdk-* headers itself.
            const resp = await window.fetch("https://gql.twitch.tv/integrity", {
              headers: headers, body: null, method: "POST", mode: "cors", credentials: "omit"
            });
            const text = await resp.text();
            if (resp.status !== 200) throw new Error("Twitch integrity HTTP " + resp.status + ": " + text.slice(0, 300));
            const proof = JSON.parse(text);
            if (probe !== null) {
              const gql = await window.fetch("https://gql.twitch.tv/gql", {
                headers: Object.assign({}, headers, { "Client-Integrity": proof.token }),
                body: JSON.stringify(probe), method: "POST", mode: "cors", credentials: "omit"
              });
              const body = await gql.json();
              const user = (body.data || {}).currentUser;
              proof.probe = {
                status: gql.status,
                campaigns: user && Array.isArray(user.dropCampaigns) ? user.dropCampaigns.length : null,
                errors: (body.errors || []).map(e => ({ message: e.message || "", code: (e.extensions || {}).code || "" }))
              };
            }
            return proof;
          }
          document.addEventListener("kpsdk-load", () => {
            window.KPSDK.configure([{ protocol: "https:", method: "POST", domain: "gql.twitch.tv", path: "/integrity" }]);
          }, { once: true });
          document.addEventListener("kpsdk-ready", () => {
            fetchIntegrity().then(proof => { window.__ds = { state: "ok", proof: proof }; }, fail);
          }, { once: true });
          const s = document.createElement("script");
          s.addEventListener("error", () => fail("Kasada script failed to load"));
          s.src = ${JSONObject.quote(scriptUrl)};
          document.body.appendChild(s);
        })();
    """.trimIndent()

    companion object {
        /** `v4.public.<base64url(message || 64-byte signature)>[.footer]` → message JSON. */
        fun decodeClaims(token: String): JSONObject? = runCatching {
            val parts = token.split('.')
            if (parts.size < 3 || parts[1] != "public") return null
            val bytes = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(bytes, 0, bytes.size - 64, Charsets.UTF_8))
        }.getOrNull()
    }
}
