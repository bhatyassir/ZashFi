package com.shaazifi.app.core.session

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * Discovers the local OpenWrt router default gateway on Wi-Fi.
 * Real-world unaided discovery is documented as an OPEN HOME test.
 */
class GatewayDiscovery(private val context: Context) {

    /**
     * Resolves the current default Wi-Fi/LAN gateway IPv4 address.
     * Returns e.g. "192.168.1.1" or null if unresolvable.
     */
    fun findDefaultGateway(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return null
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return null

            for (route in linkProps.routes) {
                if (route.isDefaultRoute && route.gateway is Inet4Address) {
                    val gw = route.gateway?.hostAddress
                    if (!gw.isNullOrBlank() && gw != "0.0.0.0") {
                        return gw
                    }
                }
            }
        } catch (_: Exception) {
            // Ignored
        }
        return null
    }

    /**
     * Candidate gateway endpoints to probe for OpenWrt routers.
     */
    val candidateEndpoints: List<String>
        get() {
            val list = mutableListOf<String>()
            val gw = findDefaultGateway()
            if (gw != null) {
                list.add("https://$gw")
                list.add("http://$gw")
            }
            if (!list.contains("https://192.168.1.1")) {
                list.add("https://192.168.1.1")
            }
            return list
        }
}

