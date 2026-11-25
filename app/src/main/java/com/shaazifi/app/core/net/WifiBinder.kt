package com.shaazifi.app.core.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket

/**
 * WifiBinder binds network sockets to the active Wi-Fi interface when mobile data
 * is active alongside a home Wi-Fi that has no upstream internet.
 *
 * CRITICAL RULE:
 * WifiBinder applies ONLY to private LAN addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).
 * It must NEVER bind 100.64.0.0/10 or tailnet addresses, otherwise Tailscale VPN traffic
 * would be forced out of the local Wi-Fi interface and bypass the tailnet tunnel.
 */
object WifiBinder {

    /**
     * Determines whether traffic to the target host/IP must be bound to the local Wi-Fi interface.
     * Returns true ONLY for RFC 1918 private LAN ranges.
     * Returns false for Tailscale (100.64.0.0/10), public IPs, loopback, and obfuscated formats.
     */
    fun shouldBindToWifi(hostOrIp: String): Boolean {
        val cleanHost = AddressGuard.extractHost(hostOrIp).trim().removeSurrounding("[", "]")

        if (cleanHost.isEmpty() || AddressGuard.isNonCanonicalIp(cleanHost) || cleanHost.equals("localhost", ignoreCase = true)) {
            return false
        }

        return try {
            val addresses = InetAddress.getAllByName(cleanHost)
            if (addresses.isEmpty()) return false
            // Every address must be strictly RFC 1918 private IPv4
            addresses.all { isPrivateLanAddress(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateLanAddress(addr: InetAddress): Boolean {
        return when (addr) {
            is Inet4Address -> isPrivateLanIpv4(addr)
            is Inet6Address -> {
                // If IPv4-mapped, check if the underlying IPv4 is RFC 1918
                val bytes = addr.address
                val isIpv4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                                   bytes[10] == 0xFF.toByte() &&
                                   bytes[11] == 0xFF.toByte()
                if (isIpv4Mapped) {
                    val b0 = bytes[12].toInt() and 0xFF
                    val b1 = bytes[13].toInt() and 0xFF
                    isPrivateLanBytes(b0, b1)
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun isPrivateLanIpv4(addr: Inet4Address): Boolean {
        val bytes = addr.address
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return isPrivateLanBytes(b0, b1)
    }

    private fun isPrivateLanBytes(b0: Int, b1: Int): Boolean {
        // 10.0.0.0/8
        if (b0 == 10) return true

        // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        if (b0 == 172 && (b1 in 16..31)) return true

        // 192.168.0.0/16 (192.168.0.0 - 192.168.255.255)
        if (b0 == 192 && b1 == 168) return true

        // Tailscale (100.64.0.0/10) and Public are EXCLUDED explicitly
        return false
    }

    /**
     * Binds a socket to the active Wi-Fi network on Android if the target qualifies
     * as private LAN.
     * (Live execution is a HOME test when connected to physical Wi-Fi).
     */
    fun bindSocketIfLan(socket: Socket, hostOrIp: String, connectivityManager: ConnectivityManager?) {
        if (connectivityManager == null) return
        if (!shouldBindToWifi(hostOrIp)) return

        try {
            val wifiNetwork = findWifiNetwork(connectivityManager) ?: return
            wifiNetwork.bindSocket(socket)
        } catch (_: Exception) {
            // Best effort binding
        }
    }

    private fun findWifiNetwork(cm: ConnectivityManager): Network? {
        val networks = cm.allNetworks
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net
            }
        }
        return null
    }
}
