package com.shaazifi.app.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiBinderTest {

    @Test
    fun testAppliesOnlyToPrivateLanAddresses() {
        // RFC 1918 Private LAN ranges MUST bind to Wi-Fi
        assertTrue(WifiBinder.shouldBindToWifi("192.168.1.1"))
        assertTrue(WifiBinder.shouldBindToWifi("192.168.0.1"))
        assertTrue(WifiBinder.shouldBindToWifi("192.168.100.254"))
        assertTrue(WifiBinder.shouldBindToWifi("10.0.0.1"))
        assertTrue(WifiBinder.shouldBindToWifi("10.10.10.10"))
        assertTrue(WifiBinder.shouldBindToWifi("172.16.0.1"))
        assertTrue(WifiBinder.shouldBindToWifi("172.31.255.254"))
    }

    @Test
    fun testNeverBindsTailnetOrPublicTraffic() {
        // CRITICAL REQUIREMENT:
        // WifiBinder must NEVER bind 100.64.0.0/10 or tailnet traffic to Wi-Fi,
        // otherwise Tailscale traffic would bypass the VPN tunnel.
        assertFalse(WifiBinder.shouldBindToWifi("100.64.0.1"))
        assertFalse(WifiBinder.shouldBindToWifi("100.64.0.7")) // test router over Tailscale
        assertFalse(WifiBinder.shouldBindToWifi("100.100.100.100")) // MagicDNS
        assertFalse(WifiBinder.shouldBindToWifi("100.127.255.254"))

        // Public IPs must not bind to Wi-Fi either
        assertFalse(WifiBinder.shouldBindToWifi("8.8.8.8"))
        assertFalse(WifiBinder.shouldBindToWifi("1.1.1.1"))

        // Loopback must not bind to Wi-Fi
        assertFalse(WifiBinder.shouldBindToWifi("127.0.0.1"))
        assertFalse(WifiBinder.shouldBindToWifi("localhost"))
    }

    @Test
    fun testNeverBindsOutsideRangeOrAlternativeFormats() {
        // Just outside ranges
        assertFalse(WifiBinder.shouldBindToWifi("172.15.255.255"))
        assertFalse(WifiBinder.shouldBindToWifi("172.32.0.1"))
        assertFalse(WifiBinder.shouldBindToWifi("100.63.255.255"))
        assertFalse(WifiBinder.shouldBindToWifi("100.128.0.1"))
        assertFalse(WifiBinder.shouldBindToWifi("192.169.0.1"))
        assertFalse(WifiBinder.shouldBindToWifi("192.169.1.1"))

        // IPv4-mapped IPv6 public and Tailscale addresses
        assertFalse(WifiBinder.shouldBindToWifi("::ffff:8.8.8.8"))
        assertFalse(WifiBinder.shouldBindToWifi("::ffff:100.64.0.1"))

        // IPv6 loopback and public addresses
        assertFalse(WifiBinder.shouldBindToWifi("::1"))
        assertFalse(WifiBinder.shouldBindToWifi("2001:4860:4860::8888"))

        // Alternative representations
        assertFalse(WifiBinder.shouldBindToWifi("2130706433"))
        assertFalse(WifiBinder.shouldBindToWifi("0x7f.1"))
    }

    @Test
    fun testBindsLocalIpv6UlaAndLinkLocalAddresses() {
        assertTrue(WifiBinder.shouldBindToWifi("fd00::1"))
        assertTrue(WifiBinder.shouldBindToWifi("fd12:3456:789a::1"))
        assertTrue(WifiBinder.shouldBindToWifi("fe80::1"))
        assertTrue(WifiBinder.shouldBindToWifi("[fe80::1%wlan0]:8080"))
        assertTrue(WifiBinder.shouldBindToWifi("::ffff:192.168.1.1"))
    }
}
