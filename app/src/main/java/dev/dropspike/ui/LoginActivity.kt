package dev.dropspike.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import kotlinx.coroutines.delay

/**
 * Twitch's normal login page in a WebView. We never see the password: we wait for Twitch
 * to set its `auth-token` cookie and take that.
 */
class LoginActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DropSpikeTheme {
                var loading by remember { mutableStateOf(true) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Sign in to Twitch") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, "Cancel") }
                            },
                        )
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, url: String?) {
                                            loading = false
                                        }
                                    }
                                    loadUrl("https://www.twitch.tv/login")
                                }
                            },
                        )
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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

    private fun readCookies(): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie("https://www.twitch.tv") ?: return emptyMap()
        return raw.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
    }
}
