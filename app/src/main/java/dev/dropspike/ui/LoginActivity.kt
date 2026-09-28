package dev.dropspike.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs

/**
 * Twitch's normal login page in a WebView. We never see the password: we wait for Twitch
 * to set its `auth-token` cookie and take that.
 *
 * Deliberately plain Android views, not Compose: on a Pixel 10 Pro Fold (Android 17, Vanadium
 * WebView 154) a WebView hosted in Compose loaded the page (DOM present) but never drew.
 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlText: TextView
    private lateinit var progress: ProgressBar
    private var defaultUserAgent = ""
    private var desktop = false
    private var software = false
    private var consoleLines = 0
    private var done = false

    private val cookiePoll = object : Runnable {
        override fun run() {
            if (done) return
            if (!checkForToken()) webView.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.i("login: opened, plain views (WebView ${WebView.getCurrentWebViewPackage()?.versionName})")

        val dp = resources.displayMetrics.density
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        urlText = TextView(this).apply {
            text = "Loading…"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), 0)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("Close") { finish() })
            addView(button("Reload") { webView.reload() })
            addView(button("Desktop site") {
                desktop = !desktop
                setDesktopMode(desktop)
                webView.loadUrl(LOGIN_URL)
            })
            addView(button("Mobile site") { webView.loadUrl(MOBILE_LOGIN_URL) })
            addView(button("Software") {
                software = !software
                webView.setLayerType(if (software) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE, null)
                DiagLog.i("login: software rendering ${if (software) "on" else "off"}")
            })
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        webView = createWebView()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Grey, so a WebView that doesn't draw is distinguishable from the app's dark background.
            setBackgroundColor(Color.rgb(0x9A, 0x9A, 0xA0))
            addView(urlText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(HorizontalScrollView(this@LoginActivity).apply { addView(buttons) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        // Keep content clear of the status and navigation bars on edge-to-edge Android.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setContentView(root)

        webView.loadUrl(LOGIN_URL)
        webView.post(cookiePoll)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        defaultUserAgent = settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val level = message.messageLevel()
                if ((level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) && consoleLines < 25) {
                    consoleLines++
                    DiagLog.i("login console $level: ${message.message().take(200)} (${message.sourceId().substringAfterLast('/').take(40)}:${message.lineNumber()})")
                }
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                DiagLog.i("login: page started $url")
                urlText.text = url
            }

            override fun onPageFinished(view: WebView, url: String?) {
                DiagLog.i("login: page finished $url")
                urlText.text = url
                view.evaluateJavascript(
                    "(function(){var b=document.body;return document.readyState+' | title='+document.title+' | bodyText='+(b?b.innerText.length:-1)+' | elements='+document.getElementsByTagName('*').length})()",
                ) { DiagLog.i("login: page state $it") }
                // Size and attachment of the view itself, for the drawing problem.
                DiagLog.i("login: view ${view.width}x${view.height}, attached=${view.isAttachedToWindow}, hwAccel=${view.isHardwareAccelerated}")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    DiagLog.i("login: load error ${error.errorCode} ${error.description} for ${request.url}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || response.statusCode == 403 || response.statusCode == 429) {
                    DiagLog.i("login: HTTP ${response.statusCode} for ${request.url.host}${request.url.path?.take(60)}")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                DiagLog.i("login: WebView renderer gone (crashed=${detail.didCrash()})")
                done = true
                finish()
                return true
            }
        }
    }

    /** Twitch's login is a single-page app, so poll the cookie jar instead of watching navigation. */
    private fun checkForToken(): Boolean {
        val cookies = readCookies()
        val token = cookies["auth-token"]
        if (token.isNullOrBlank()) return false
        val prefs = DropSpikeApp.instance.prefs
        cookies["unique_id"]?.takeIf { it.isNotBlank() }?.let { prefs.deviceId = it }
        prefs.authToken = token
        prefs.clientId = Prefs.WEB_CLIENT_ID
        DiagLog.i("login: got auth-token cookie (unique_id ${if (cookies.containsKey("unique_id")) "present" else "absent"})")
        done = true
        setResult(RESULT_OK)
        finish()
        return true
    }

    private fun setDesktopMode(on: Boolean) {
        webView.settings.userAgentString = if (on) DESKTOP_UA else defaultUserAgent
        webView.settings.useWideViewPort = on
        webView.settings.loadWithOverviewMode = on
        DiagLog.i("login: desktop mode ${if (on) "on" else "off"}")
    }

    override fun onDestroy() {
        done = true
        webView.destroy()
        super.onDestroy()
    }

    private fun readCookies(): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie("https://www.twitch.tv") ?: return emptyMap()
        return raw.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
    }

    companion object {
        private const val LOGIN_URL = "https://www.twitch.tv/login"
        private const val MOBILE_LOGIN_URL = "https://m.twitch.tv/login"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    }
}
