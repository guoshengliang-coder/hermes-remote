package com.hermes.client.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Process-wide snapshot of which transports the active network uses (wifi / cellular / vpn / …).
 *
 * The 2026-09-26 mobile incident review could not settle whether "VPN rule set to DIRECT" meant
 * the failing traffic had actually gone direct: the app recorded failures but never the network
 * they failed on. This reader exists so every failure record can carry a `net=` note. It is a
 * process singleton like [com.hermes.client.data.diagnostics.DebugLog] — [HermesApp] installs the
 * Android-backed reader at startup, and tests install their own. Reading connectivity is cheap
 * and permission-free; TRANSPORT_VPN is exactly the "was the VPN in the path" answer.
 */
object NetworkTransports {
    @Volatile private var reader: () -> String = { "unknown" }

    fun install(reader: () -> String) {
        this.reader = reader
    }

    fun current(): String = reader()
}

/** The Android-backed reader: "cellular+vpn" style, or "none"/"unknown" when it cannot tell. */
class AndroidNetworkTransports(private val context: Context) : () -> String {
    override fun invoke(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val net = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(net) ?: return "none"
        return buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }.joinToString("+").ifEmpty { "other" }
    }
}
