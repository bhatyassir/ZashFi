package com.shaazifi.app.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * WifiBinder binds network sockets to the active Wi-Fi interface when mobile data
 * is active alongside a home Wi-Fi that has no upstream internet.
 *
 * CRITICAL RULE:
 * WifiBinder applies ONLY to private LAN addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16,
 * and local IPv6 ULA/link-local). It must NEVER bind 100.64.0.0/10 or tailnet addresses,
 * otherwise Tailscale VPN traffic would be forced out of the local Wi-Fi interface.
 */
object WifiBinder {

    /**
     * Determines whether traffic to the target host/IP must be bound to the local Wi-Fi interface.
     * Returns true ONLY for private LAN ranges (RFC 1918 IPv4 or local ULA/link-local IPv6).
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
            addresses.all { isPrivateLanAddress(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateLanAddress(addr: InetAddress): Boolean {
        return when (addr) {
            is Inet4Address -> isPrivateLanIpv4(addr)
            is Inet6Address -> {
                val bytes = addr.address
                val isIpv4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                    bytes[10] == 0xFF.toByte() &&
                    bytes[11] == 0xFF.toByte()
                if (isIpv4Mapped) {
                    val b0 = bytes[12].toInt() and 0xFF
                    val b1 = bytes[13].toInt() and 0xFF
                    isPrivateLanBytes(b0, b1)
                } else if (addr.isLinkLocalAddress) {
                    true
                } else {
                    val firstByte = bytes[0].toInt() and 0xFF
                    (firstByte and 0xFE) == 0xFC
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
        if (b0 == 10) return true
        if (b0 == 172 && (b1 in 16..31)) return true
        if (b0 == 192 && b1 == 168) return true
        return false
    }

    /**
     * Binds a socket to the active non-VPN Wi-Fi network on Android if the target qualifies
     * as private LAN.
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

    @Suppress("DEPRECATION")
    fun findWifiNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return active
            }
        }
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return net
            }
        }
        return null
    }

    fun socketFactory(context: Context): SocketFactory {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val delegate = SocketFactory.getDefault()
        return object : SocketFactory() {
            override fun createSocket(): Socket = delegate.createSocket()

            override fun createSocket(host: String, port: Int): Socket {
                val s = delegate.createSocket()
                bindSocketIfLan(s, host, cm)
                s.connect(InetSocketAddress(host, port))
                return s
            }

            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                val s = delegate.createSocket()
                bindSocketIfLan(s, host, cm)
                s.bind(InetSocketAddress(localHost, localPort))
                s.connect(InetSocketAddress(host, port))
                return s
            }

            override fun createSocket(host: InetAddress, port: Int): Socket {
                val s = delegate.createSocket()
                bindSocketIfLan(s, host.hostAddress ?: "", cm)
                s.connect(InetSocketAddress(host, port))
                return s
            }

            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                val s = delegate.createSocket()
                bindSocketIfLan(s, address.hostAddress ?: "", cm)
                s.bind(InetSocketAddress(localAddress, localPort))
                s.connect(InetSocketAddress(address, port))
                return s
            }
        }
    }
}
