package dev.dropspike.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import kotlinx.coroutines.delay

/**
 * Twitch's normal login page in a WebView. We never see the password: we wait for Twitch
 * to set its `auth-token` cookie and take that. Everything the page does is logged to
 * Diagnostics, because a blank page here tells us nothing on its own.
 */
class LoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var defaultUserAgent: String = ""
    private var consoleLines = 0

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DiagLog.i("login: opened (WebView ${WebView.getCurrentWebViewPackage()?.versionName})")
        setContent {
            DropSpikeTheme {
                var progress by remember { mutableIntStateOf(0) }
                var url by remember { mutableStateOf("") }
                var desktop by remember { mutableStateOf(false) }
                var software by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Sign in to Twitch")
                                    Text(
                                        url.ifEmpty { "Loading…" },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Cancel") }
                            },
                            actions = {
                                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                            },
                        )
                    },
                    bottomBar = {
                        BottomAppBar {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = desktop,
                                    onClick = {
                                        desktop = !desktop
                                        setDesktopMode(desktop)
                                        webView?.loadUrl(LOGIN_URL)
                                    },
                                    label = { Text("Desktop site") },
                                )
                                FilterChip(
                                    selected = url.startsWith("https://m.twitch.tv"),
                                    onClick = { webView?.loadUrl(MOBILE_LOGIN_URL) },
                                    label = { Text("Mobile site") },
                                )
                                FilterChip(
                                    selected = software,
                                    onClick = {
                                        software = !software
                                        // Tells a GPU/compositing problem apart from a page that never renders.
                                        webView?.setLayerType(if (software) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE, null)
                                        DiagLog.i("login: software rendering ${if (software) "on" else "off"}")
                                    },
                                    label = { Text("Software") },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                createWebView(ctx, onProgress = { progress = it }, onUrl = { url = it })
                                    .also { it.loadUrl(LOGIN_URL) }
                            },
                        )
                        if (progress in 1..99) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // Twitch's login is a single-page app, so poll the cookie jar rather than
                // relying on navigation callbacks.
                LaunchedEffect(Unit) {
                    while (true) {
                        val cookies = readCookies()
                        val token = cookies["auth-token"]
                        if (!token.isNullOrBlank()) {
                            val prefs = DropSpikeApp.instance.prefs
                            cookies["unique_id"]?.takeIf { it.isNotBlank() }?.let { prefs.deviceId = it }
                            prefs.authToken = token
                            DiagLog.i("login: got auth-token cookie (unique_id ${if (cookies.containsKey("unique_id")) "present" else "absent"})")
                            setResult(RESULT_OK)
                            finish()
                            break
                        }
                        delay(1_000)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        ctx: android.content.Context,
        onProgress: (Int) -> Unit,
        onUrl: (String) -> Unit,
    ): WebView = WebView(ctx).apply {
        webView = this
        // White, so "nothing rendered" can be told apart from Twitch's dark theme.
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        defaultUserAgent = settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Errors and warnings only, capped, so the log stays readable.
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
                url?.let(onUrl)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                DiagLog.i("login: page finished $url")
                url?.let(onUrl)
                // What actually rendered: an empty body means the page never drew anything.
                view.evaluateJavascript(
                    "(function(){var b=document.body;return document.readyState+' | title='+document.title+' | bodyText='+(b?b.innerText.length:-1)+' | elements='+document.getElementsByTagName('*').length})()",
                ) { DiagLog.i("login: page state $it") }
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
                // Without this the whole app would crash with the renderer.
                DiagLog.i("login: WebView renderer gone (crashed=${detail.didCrash()})")
                webView = null
                finish()
                return true
            }
        }
    }

    private fun setDesktopMode(on: Boolean) {
        val view = webView ?: return
        view.settings.userAgentString = if (on) DESKTOP_UA else defaultUserAgent
        view.settings.useWideViewPort = on
        view.settings.loadWithOverviewMode = on
        DiagLog.i("login: desktop mode ${if (on) "on" else "off"}")
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
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
