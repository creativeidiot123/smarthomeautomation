package dev.homedj.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Device-local pairing configuration, never backed up or sent in plaintext. */
internal class RemotePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)

    fun serverKey(): String {
        prefs.getString("server_key", null)?.let { if (RemoteChannel.decodeKey(it) != null) return it }
        return RemoteChannel.generateKey().also { prefs.edit().putString("server_key", it).commit() }
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    val savedHost: String get() = prefs.getString("host", "").orEmpty()
    val savedClientKey: String get() = prefs.getString("client_key", "").orEmpty()

    fun saveClient(host: String, key: String) {
        require(validPrivateIpv4(host) && RemoteChannel.decodeKey(key) != null)
        prefs.edit().putString("host", host).putString("client_key", key.trim()).apply()
    }

    companion object {
        fun validPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { char -> !char.isDigit() } }) {
                return false
            }
            val bytes = parts.map { it.toIntOrNull() ?: return false }
            if (bytes.any { it !in 0..255 }) return false
            return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168)
        }

        fun localAddress(context: Context): String =
            runCatching {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                (listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList())
                    .distinct()
                    .asSequence()
                    .filter {
                        manager.getNetworkCapabilities(it)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    .mapNotNull { manager.getLinkProperties(it) }
                    .flatMap { it.linkAddresses.asSequence() }
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress.orEmpty() }
                    .firstOrNull(::validPrivateIpv4)
            }.getOrNull() ?: "Check this phone's Wi-Fi IP address"
    }
}
