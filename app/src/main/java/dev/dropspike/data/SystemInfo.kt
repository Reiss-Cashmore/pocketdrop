package dev.dropspike.data

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Facts about the device and how Android is treating the app, for logs and reports. */
object SystemInfo {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    fun time(ms: Long): String = if (ms <= 0) "never" else fmt.format(Date(ms))

    fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }
            .getOrDefault("?")

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}"

    fun webView(): String = WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"

    /** Everything that decides whether Android lets us run in the background. */
    fun power(context: Context): List<Pair<String, String>> {
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val bucket = when (usm.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            else -> usm.appStandbyBucket.toString()
        }
        val notifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return listOf(
            "Battery optimisation exempt (Unrestricted)" to pm.isIgnoringBatteryOptimizations(context.packageName).toString(),
            "Background restricted by user" to am.isBackgroundRestricted.toString(),
            "App standby bucket" to bucket,
            "Power saver on" to pm.isPowerSaveMode.toString(),
            "Device idle (Doze) now" to pm.isDeviceIdleMode.toString(),
            "Screen interactive now" to pm.isInteractive.toString(),
            "Notifications allowed" to notifications.toString(),
            "Network" to network(context),
            "Uptime since boot" to "${SystemClock.elapsedRealtime() / 3_600_000}h ${(SystemClock.elapsedRealtime() / 60_000) % 60}m",
        )
    }

    fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return "$kind${if (validated) "" else " (not validated)"}${if (metered) ", metered" else ""}"
    }

    /** Why previous processes of this app ended (killed for memory, by the user, crashed, …). */
    fun exitReasons(context: Context, max: Int = 15): List<String> {
        val am = context.getSystemService(ActivityManager::class.java)
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, max).map { e ->
                "${time(e.timestamp)} pid ${e.pid}: ${reason(e.reason)}" +
                    (e.description?.let { " — $it" } ?: "") +
                    ", importance ${e.importance}, pss ${e.pss / 1024}MB"
            }
        }.getOrElse { listOf("unavailable: ${it.message}") }
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force stop / swipe)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "reason $r"
    }
}
