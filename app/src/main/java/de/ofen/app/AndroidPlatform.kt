package de.ofen.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Android-Handy: Einstellungen in SharedPreferences, Meldungen als Benachrichtigung. */
class AndroidPlatform private constructor(private val ctx: Context) : Platform {
    private val prefs get() = ctx.getSharedPreferences("ofen", Context.MODE_PRIVATE)

    override val kind = "phone"
    override val deviceName: String get() = Build.MODEL ?: "Handy"
    override val osType = "Android"
    override val osVersion: String get() = Build.VERSION.RELEASE ?: ""

    override fun loadConfig(): String = prefs.getString("config", "{}") ?: "{}"
    override fun saveConfig(json: String) { prefs.edit().putString("config", json).apply() }

    override fun broadcastAddrs(): List<String> {
        val out = mutableListOf<String>()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            for (la in cm.getLinkProperties(n)?.linkAddresses ?: emptyList()) {
                val b = la.address.address
                if (b.size != 4) continue
                val pl = la.prefixLength.coerceIn(8, 30)
                val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
                val bc = ip or ((1 shl (32 - pl)) - 1)
                out += "${(bc ushr 24) and 255}.${(bc ushr 16) and 255}.${(bc ushr 8) and 255}.${bc and 255}"
            }
        }
        return out
    }

    override fun vpnActive(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    override fun onHomeNet(stoveIp: String): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val pre = stoveIp.split(".").take(3)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (cm.getLinkProperties(n)?.linkAddresses?.any { it.address.hostAddress?.split(".")?.take(3) == pre } == true) return true
        }
        return false
    }

    override fun notifyState(title: String, text: String) = Notifier.stateChange(ctx, title, text)
    override fun alert(id: Int, title: String, text: String) = Notifier.alert(ctx, id, title, text)
    override fun clearAlert(id: Int) = Notifier.clear(ctx, id)

    companion object {
        fun of(c: Context) = AndroidPlatform(c.applicationContext)
    }
}
