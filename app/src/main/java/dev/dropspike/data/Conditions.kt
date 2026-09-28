package dev.dropspike.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** The optional "only while charging" / "only on Wi-Fi" rules. */
object Conditions {
    /** Plugged in (also true when full, or when adaptive charging is holding at 80%). */
    fun pluggedIn(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** On Wi-Fi or Ethernet, including through a VPN (which is then unmetered). */
    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    /** Why mining has to wait right now, or null when it can go ahead. */
    fun blocked(context: Context, prefs: Prefs): String? = when {
        prefs.onlyCharging && !pluggedIn(context) -> "waiting for a charger"
        prefs.onlyWifi && !onWifi(context) -> "waiting for Wi-Fi"
        else -> null
    }
}
