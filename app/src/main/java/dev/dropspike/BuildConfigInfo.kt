package dev.dropspike

import android.content.Context
import android.os.Build
import android.webkit.WebView

/** Header for the shareable diagnostics report. */
object BuildConfigInfo {
    fun header(context: Context): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val webView = WebView.getCurrentWebViewPackage()
        return buildString {
            appendLine("DropSpike $app")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("WebView: ${webView?.packageName} ${webView?.versionName}")
            appendLine("---")
        }
    }
}
