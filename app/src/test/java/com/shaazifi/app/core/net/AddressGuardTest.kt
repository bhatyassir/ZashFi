package com.shaazifi.app.core.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class AddressGuardTest {

    @Test
    fun testAllowedPrivateAndTailnetIps() {
        // Private IPv4 (RFC 1918)
        assertTrue(AddressGuard.isAllowed("192.168.1.1"))
        assertTrue(AddressGuard.isAllowed("192.168.0.254"))
        assertTrue(AddressGuard.isAllowed("10.0.0.1"))
        assertTrue(AddressGuard.isAllowed("10.255.255.254"))
        assertTrue(AddressGuard.isAllowed("172.16.0.1"))
        assertTrue(AddressGuard.isAllowed("172.31.255.254"))

        // Tailscale (RFC 6598 100.64.0.0/10)
        assertTrue(AddressGuard.isAllowed("100.64.0.1"))
        assertTrue(AddressGuard.isAllowed("100.64.0.7"))
        assertTrue(AddressGuard.isAllowed("100.127.255.254"))

        // Localhost & Loopback
        assertTrue(AddressGuard.isAllowed("127.0.0.1"))
        assertTrue(AddressGuard.isAllowed("127.0.0.2"))
        assertTrue(AddressGuard.isAllowed("localhost"))
        assertTrue(AddressGuard.isAllowed("::1"))

        // IPv6 Local (ULA fc00::/7 & Link-local fe80::/10)
        assertTrue(AddressGuard.isAllowed("fd00::1"))
        assertTrue(AddressGuard.isAllowed("fe80::1"))
    }

    @Test
    fun testRefusedPublicIps() {
        assertFalse(AddressGuard.isAllowed("8.8.8.8"))
        assertFalse(AddressGuard.isAllowed("1.1.1.1"))
        assertFalse(AddressGuard.isAllowed("142.250.190.46"))
        assertFalse(AddressGuard.isAllowed("208.67.222.222"))
    }

    @Test
    fun testOutsideRangeBoundaries() {
        // Just outside 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        assertFalse(AddressGuard.isAllowed("172.15.255.255"))
        assertFalse(AddressGuard.isAllowed("172.15.0.1"))
        assertFalse(AddressGuard.isAllowed("172.32.0.1"))
        assertFalse(AddressGuard.isAllowed("172.32.255.255"))

        // Just outside 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
        assertFalse(AddressGuard.isAllowed("100.63.255.255"))
        assertFalse(AddressGuard.isAllowed("100.63.0.1"))
        assertFalse(AddressGuard.isAllowed("100.128.0.1"))
        assertFalse(AddressGuard.isAllowed("100.128.255.255"))

        // Just outside 192.168.0.0/16 (192.168.0.0 - 192.168.255.255)
        assertFalse(AddressGuard.isAllowed("192.167.255.255"))
        assertFalse(AddressGuard.isAllowed("192.169.0.1"))
        assertFalse(AddressGuard.isAllowed("192.169.1.1"))
    }

    @Test
    fun testIpv4MappedIpv6() {
        // IPv4-mapped IPv6 pointing to public address 8.8.8.8 must be REFUSED
        assertFalse(AddressGuard.isAllowed("::ffff:8.8.8.8"))
        assertFalse(AddressGuard.isAllowed("[::ffff:8.8.8.8]"))

        // IPv4-mapped IPv6 pointing to private address 192.168.1.1 may be allowed
        assertTrue(AddressGuard.isAllowed("::ffff:192.168.1.1"))
        assertTrue(AddressGuard.isAllowed("[::ffff:192.168.1.1]"))
    }

    @Test
    fun testAlternativeIpFormatsDwordAndHex() {
        // Dword notation for 127.0.0.1 (2130706433) and hex notation (0x7f.1)
        // Obfuscated / non-canonical IP formats must be refused
        assertFalse(AddressGuard.isAllowed("2130706433"))
        assertFalse(AddressGuard.isAllowed("0x7f.1"))
        assertFalse(AddressGuard.isAllowed("0177.0.0.1"))
        assertFalse(AddressGuard.isAllowed("127.0.0.01"))
        assertFalse(AddressGuard.isAllowed("127.1"))
        assertFalse(AddressGuard.isAllowed("0x7f.0.0.1"))

        try {
            AddressGuard.requireAllowed("http://2130706433/")
            fail("Expected AddressNotAllowedException for DWORD IP http://2130706433/")
        } catch (_: AddressNotAllowedException) {}

        try {
            AddressGuard.requireAllowed("http://0x7f.1/")
            fail("Expected AddressNotAllowedException for hex IP http://0x7f.1/")
        } catch (_: AddressNotAllowedException) {}
    }

    @Test
    fun testUserInfoUrlBypass() {
        // http://192.168.1.1@evil.com has destination host evil.com, not 192.168.1.1
        try {
            AddressGuard.requireAllowed("http://192.168.1.1@evil.com")
            fail("Expected AddressNotAllowedException for http://192.168.1.1@evil.com")
        } catch (e: AddressNotAllowedException) {
            assertEquals("evil.com", e.host)
        }

        assertFalse(AddressGuard.isAllowed("192.168.1.1@evil.com"))
    }

    @Test
    fun testLanNameResolvingToPublicAddressIsRefused() {
        // A .lan name that resolves to a public address (8.8.8.8) must be refused
        val customDns = object : AddressGuardDns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname.equals("router.lan", ignoreCase = true)) {
                    listOf(InetAddress.getByName("8.8.8.8"))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        assertFalse(AddressGuard.isAllowed("router.lan", customDns))

        try {
            AddressGuard.requireAllowed("http://router.lan/", customDns)
            fail("Expected AddressNotAllowedException for router.lan resolving to public 8.8.8.8")
        } catch (e: AddressNotAllowedException) {
            assertEquals("router.lan", e.host)
        }
    }

    @Test
    fun testHttpRedirectToPublicAddressIsRefused() {
        // When a router/server redirects to a public address, OkHttp must re-check on redirect and abort
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://8.8.8.8/evil")
        )
        server.start()

        try {
            val client = HttpFactory.createClient()
            val request = Request.Builder()
                .url(server.url("/start").newBuilder().host("127.0.0.1").build())
                .build()

            try {
                client.newCall(request).execute().use { _ ->
                    fail("Expected AddressNotAllowedException when redirected to public IP 8.8.8.8")
                }
            } catch (e: Exception) {
                var found = false
                fun inspect(t: Throwable?) {
                    if (t == null || found) return
                    if (t is AddressNotAllowedException) {
                        found = true
                        assertEquals("8.8.8.8", t.host)
                        return
                    }
                    inspect(t.cause)
                    for (s in t.suppressed) inspect(s)
                }
                inspect(e)
                assertTrue("Expected AddressNotAllowedException on redirect to 8.8.8.8, but got: $e", found)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testUrlValidation() {
        // Allowed URLs
        AddressGuard.requireAllowed("https://100.64.0.7/untangled/")
        AddressGuard.requireAllowed("http://192.168.1.1:80/ubus")
        AddressGuard.requireAllowed("https://10.0.0.1/")

        // Refused Public URLs should throw AddressNotAllowedException
        try {
            AddressGuard.requireAllowed("https://google.com")
            fail("Expected AddressNotAllowedException for google.com")
        } catch (e: AddressNotAllowedException) {
            assertEquals("google.com", e.host)
        }

        try {
            AddressGuard.requireAllowed("https://8.8.8.8/test")
            fail("Expected AddressNotAllowedException for 8.8.8.8")
        } catch (e: AddressNotAllowedException) {
            assertEquals("8.8.8.8", e.host)
        }
    }

    @Test
    fun testRefusesPublicAddressesBothHttpAndHttpsAndAcceptsRfc1918AndTailscale() {
        // Public addresses must be refused for BOTH http and https
        val publicUrls = listOf(
            "http://google.com/",
            "https://google.com/",
            "http://8.8.8.8/",
            "https://8.8.8.8/",
            "http://1.1.1.1:8080/path",
            "https://1.1.1.1:8443/path",
            "http://208.67.222.222/",
            "https://208.67.222.222/"
        )

        for (url in publicUrls) {
            assertFalse("AddressGuard.isAllowed must return false for public URL: $url", AddressGuard.isAllowed(url))
            try {
                AddressGuard.requireAllowed(url)
                fail("AddressGuard.requireAllowed must throw AddressNotAllowedException for public URL: $url")
            } catch (e: AddressNotAllowedException) {
                // Expected
                assertTrue(e.message!!.contains("refused"))
            }
        }

        // RFC 1918 private addresses (including arbitrary subnets like 192.168.29.1) must be accepted for BOTH http and https
        val rfc1918Urls = listOf(
            "http://192.168.1.1/",
            "https://192.168.1.1/",
            "http://192.168.29.1:3000/control/status",
            "https://192.168.29.1:3000/control/status",
            "http://192.168.0.254/",
            "https://192.168.0.254/",
            "http://10.0.0.1/",
            "https://10.0.0.1/",
            "http://10.255.255.254:8080/",
            "https://10.255.255.254:8080/",
            "http://172.16.0.1/",
            "https://172.16.0.1/",
            "http://172.31.255.254/",
            "https://172.31.255.254/"
        )

        for (url in rfc1918Urls) {
            assertTrue("AddressGuard.isAllowed must return true for RFC 1918 URL: $url", AddressGuard.isAllowed(url))
            AddressGuard.requireAllowed(url) // Must not throw
        }

        // Tailscale CGNAT addresses (100.64.0.0/10) must be accepted for BOTH http and https
        val tailscaleUrls = listOf(
            "http://100.64.0.7/",
            "https://100.64.0.7/",
            "http://100.64.0.1:3000/",
            "https://100.64.0.1:3000/",
            "http://100.127.255.254/",
            "https://100.127.255.254/"
        )

        for (url in tailscaleUrls) {
            assertTrue("AddressGuard.isAllowed must return true for Tailscale URL: $url", AddressGuard.isAllowed(url))
            AddressGuard.requireAllowed(url) // Must not throw
        }
    }
}
