package com.shaazifi.app.feature.web

import com.shaazifi.app.core.net.AddressGuardDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.security.MessageDigest

class SecureWebViewClientTest {

    private val routerOrigin = "https://100.64.0.7"
    private val pinnedFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"

    @Test
    fun testCertificateMismatchRefused() {
        val dummyCertBytes = "dummy_der_x509_bytes_for_testing".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val correctHash = md.digest(dummyCertBytes).joinToString(":") { "%02X".format(it) }

        assertTrue(SecureWebNavigation.verifyFingerprint(dummyCertBytes, correctHash))
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, pinnedFingerprint))
    }

    @Test
    fun testNoPinRefused() {
        val dummyCertBytes = "dummy_der_x509_bytes_for_testing".toByteArray()
        val correctHash = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"

        assertFalse(SecureWebNavigation.verifyFingerprint(null, correctHash))
        assertFalse(SecureWebNavigation.verifyFingerprint(byteArrayOf(), correctHash))
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, null))
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, ""))
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, "   "))
    }

    @Test
    fun testInjectionOnlyOnProfileOrigin() {
        assertTrue(SecureWebNavigation.isInternalNavigation("https://100.64.0.7/untangled/", routerOrigin))
        assertTrue(SecureWebNavigation.isInternalNavigation("https://100.64.0.7/cgi-bin/luci/", routerOrigin))

        // Different host, port, scheme, or userinfo is NOT internal
        assertFalse(SecureWebNavigation.isInternalNavigation("https://100.82.239.49/untangled/", routerOrigin))
        assertFalse(SecureWebNavigation.isInternalNavigation("http://100.64.0.7/untangled/", routerOrigin))
        assertFalse(SecureWebNavigation.isInternalNavigation("https://100.64.0.7:8443/untangled/", routerOrigin))
        assertFalse(SecureWebNavigation.isInternalNavigation("https://user@100.64.0.7/untangled/", routerOrigin))
    }

    @Test
    fun testIsNavigationalAllowedRestrictsWebViewToRouterOriginAndPrivateGuard() {
        assertTrue(SecureWebNavigation.isNavigationalAllowed("https://100.64.0.7/cgi-bin/luci/admin/network", routerOrigin))
        assertTrue(SecureWebNavigation.isNavigationalAllowed("https://100.64.0.7/untangled/#wireless", routerOrigin))

        // Public internet URLs are NOT loaded inside the router WebView
        assertFalse(SecureWebNavigation.isNavigationalAllowed("https://openwrt.org", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("https://8.8.8.8/", routerOrigin))

        // Other private hosts, obfuscated IPs, userinfo spoofing, and dangerous schemes are NOT allowed
        assertFalse(SecureWebNavigation.isNavigationalAllowed("http://192.168.1.99/admin", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("http://10.0.0.1/", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("http://2130706433/", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("https://100.64.0.7@evil.com/", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("javascript:alert(1)", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("file:///etc/passwd", routerOrigin))
        assertFalse(SecureWebNavigation.isNavigationalAllowed("content://com.example/secret", routerOrigin))
    }

    @Test
    fun testPublicLinksOpenExternallyWhileArbitraryPrivateHostsAreBlocked() {
        val publicDns = AddressGuardDns { listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))) }
        val lanDns = AddressGuardDns { listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))) }

        // Internal router links load in WebView
        assertEquals(
            NavigationDecision.ALLOW_INTERNAL,
            SecureWebNavigation.decideNavigation("https://100.64.0.7/cgi-bin/luci/", routerOrigin)
        )

        // Public non-local links open safely in external browser
        assertEquals(
            NavigationDecision.OPEN_EXTERNAL,
            SecureWebNavigation.decideNavigation("https://openwrt.org", routerOrigin, isRedirect = false, dns = publicDns)
        )
        assertEquals(
            NavigationDecision.OPEN_EXTERNAL,
            SecureWebNavigation.decideNavigation("https://google.com/search", routerOrigin, isRedirect = false, dns = publicDns)
        )
        assertEquals(
            NavigationDecision.OPEN_EXTERNAL,
            SecureWebNavigation.decideNavigation("https://8.8.8.8/", routerOrigin, isRedirect = false)
        )

        // Arbitrary other private/LAN/CGNAT/loopback hosts are BLOCKED from both WebView and external browser
        assertEquals(
            NavigationDecision.BLOCK,
            SecureWebNavigation.decideNavigation("http://192.168.1.99/admin", routerOrigin)
        )
        assertEquals(
            NavigationDecision.BLOCK,
            SecureWebNavigation.decideNavigation("http://10.0.0.5:8080/", routerOrigin)
        )
        assertEquals(
            NavigationDecision.BLOCK,
            SecureWebNavigation.decideNavigation("http://localhost:3000/", routerOrigin)
        )
        assertEquals(
            NavigationDecision.BLOCK,
            SecureWebNavigation.decideNavigation("http://nas.lan/", routerOrigin, isRedirect = false, dns = lanDns)
        )

        // Dangerous schemes, userinfo spoofing, and obfuscated IPs are always BLOCKED
        assertEquals(NavigationDecision.BLOCK, SecureWebNavigation.decideNavigation("javascript:alert(1)", routerOrigin))
        assertEquals(NavigationDecision.BLOCK, SecureWebNavigation.decideNavigation("file:///etc/passwd", routerOrigin))
        assertEquals(NavigationDecision.BLOCK, SecureWebNavigation.decideNavigation("intent://scan/#Intent;end", routerOrigin))
        assertEquals(NavigationDecision.BLOCK, SecureWebNavigation.decideNavigation("http://2130706433/", routerOrigin))
        assertEquals(NavigationDecision.BLOCK, SecureWebNavigation.decideNavigation("https://100.64.0.7@evil.com/", routerOrigin))
    }

    @Test
    fun testRedirectsStayInternalAndBlockExternalOrCrossHostRedirects() {
        val publicDns = AddressGuardDns { listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))) }

        // Internal redirects (e.g. /cgi-bin/luci/ -> /cgi-bin/luci/admin/status) are allowed inside WebView
        assertEquals(
            NavigationDecision.ALLOW_INTERNAL,
            SecureWebNavigation.decideNavigation("https://100.64.0.7/cgi-bin/luci/admin/status", routerOrigin, isRedirect = true)
        )

        // Automatic HTTP redirects to external public URLs or other private hosts must be blocked
        assertEquals(
            NavigationDecision.BLOCK,
            SecureWebNavigation.decideNavigation("https://evil.example/phish", routerOrigin, isRedirect = true, dns = publicDns)
        )
        assertEquals(
            NavigationDecision.BLOCK,
            SecureWebNavigation.decideNavigation("http://192.168.1.99/login", routerOrigin, isRedirect = true)
        )
    }

    @Test
    fun testSubresourceAddressGuardFiltering() {
        val publicDns = AddressGuardDns { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }

        // Allowed: router origin scripts, fetch, images, and inline data/blob URIs
        assertTrue(SecureWebNavigation.isSubresourceAllowed("https://100.64.0.7/luci-static/resources/cbi.js", routerOrigin))
        assertTrue(SecureWebNavigation.isSubresourceAllowed("https://100.64.0.7/ubus", routerOrigin))
        assertTrue(SecureWebNavigation.isSubresourceAllowed("https://100.64.0.7/luci-static/logo.png", routerOrigin))
        assertTrue(SecureWebNavigation.isSubresourceAllowed("data:image/svg+xml;utf8,<svg></svg>", routerOrigin))
        assertTrue(SecureWebNavigation.isSubresourceAllowed("blob:https://100.64.0.7/1234", routerOrigin))

        // Blocked: public internet scripts/images/fetch, obfuscated IPs, DNS-rebound .lan to public IP, file://, userinfo
        assertFalse(SecureWebNavigation.isSubresourceAllowed("https://cdn.example.com/tracker.js", routerOrigin, publicDns))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("https://8.8.8.8/pixel.png", routerOrigin))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("https://router.lan/evil.js", routerOrigin, publicDns))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("http://0x7f.1/payload.js", routerOrigin))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("http://2130706433/payload.js", routerOrigin))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("https://100.64.0.7@evil.com/evil.js", routerOrigin))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("file:///sdcard/secret.txt", routerOrigin))
        assertFalse(SecureWebNavigation.isSubresourceAllowed("content://com.android.contacts/data", routerOrigin))
    }

    @Test
    fun testTokenValidationRejectsNonHexOrMalformedTokens() {
        assertFalse(SecureWebNavigation.isValidToken(""))
        assertFalse(SecureWebNavigation.isValidToken("12345"))
        assertFalse(SecureWebNavigation.isValidToken("e89547d7c6792fca0cb329c2ab87dc7")) // 31 chars
        assertFalse(SecureWebNavigation.isValidToken("e89547d7c6792fca0cb329c2ab87dc79a")) // 33 chars

        assertFalse(SecureWebNavigation.isValidToken("e89547d7c6792fca0cb329c2ab87dc7g"))
        assertFalse(SecureWebNavigation.isValidToken("'; alert('xss'); '01234567890123"))
        assertFalse(SecureWebNavigation.isValidToken("E89547D7C6792FCA0CB329C2AB87DC79"))

        try {
            SecureWebNavigation.buildSessionInjectionScript("invalid_token")
            fail("Expected IllegalArgumentException for malformed token")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testValidTokenAcceptedForInjection() {
        val validToken = "e89547d7c6792fca0cb329c2ab87dc79"
        assertTrue(SecureWebNavigation.isValidToken(validToken))

        val script = SecureWebNavigation.buildSessionInjectionScript(validToken)
        assertTrue(script.contains("sessionStorage.setItem('untangled-session', 'e89547d7c6792fca0cb329c2ab87dc79')"))
    }
}
