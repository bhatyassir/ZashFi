package com.shaazifi.app.feature.web

import com.shaazifi.app.core.net.AddressGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SecureWebViewClientTest {

    private val routerOrigin = "https://100.64.0.7"
    private val pinnedFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"

    @Test
    fun testCertificateMismatchRefused() {
        val dummyCertBytes = "dummy_der_x509_bytes_for_testing".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val correctHash = md.digest(dummyCertBytes).joinToString(":") { "%02X".format(it) }

        // Correct hash matches
        assertTrue(SecureWebNavigation.verifyFingerprint(dummyCertBytes, correctHash))
        // Mismatched hash is refused (hard stop)
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, pinnedFingerprint))
    }

    @Test
    fun testNoPinRefused() {
        val dummyCertBytes = "dummy_der_x509_bytes_for_testing".toByteArray()
        val correctHash = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"

        // Null or empty cert bytes refused
        assertFalse(SecureWebNavigation.verifyFingerprint(null, correctHash))
        assertFalse(SecureWebNavigation.verifyFingerprint(byteArrayOf(), correctHash))
        // Null or blank pinned fingerprint refused
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, null))
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, ""))
        assertFalse(SecureWebNavigation.verifyFingerprint(dummyCertBytes, "   "))
    }

    @Test
    fun testInjectionOnlyOnProfileOrigin() {
        // Enforces that internal navigation and script injection apply strictly to router origin
        assertTrue(SecureWebNavigation.isInternalNavigation("https://100.64.0.7/untangled/", routerOrigin))
        assertTrue(SecureWebNavigation.isInternalNavigation("https://100.64.0.7/cgi-bin/luci/", routerOrigin))

        // Different host or scheme is NOT internal
        assertFalse(SecureWebNavigation.isInternalNavigation("https://100.82.239.49/untangled/", routerOrigin))
        assertFalse(SecureWebNavigation.isInternalNavigation("http://100.64.0.7/untangled/", routerOrigin))
    }

    @Test
    fun testExternalLinksRoutedToBrowser() {
        // External links are recognized as not internal, causing WebViewClient to route to system browser
        assertFalse(SecureWebNavigation.isInternalNavigation("https://openwrt.org", routerOrigin))
        assertFalse(SecureWebNavigation.isInternalNavigation("https://google.com/search", routerOrigin))
    }

    @Test
    fun testTokenValidationRejectsNonHexOrMalformedTokens() {
        // Empty / length != 32
        assertFalse(SecureWebNavigation.isValidToken(""))
        assertFalse(SecureWebNavigation.isValidToken("12345"))
        assertFalse(SecureWebNavigation.isValidToken("e89547d7c6792fca0cb329c2ab87dc7")) // 31 chars
        assertFalse(SecureWebNavigation.isValidToken("e89547d7c6792fca0cb329c2ab87dc79a")) // 33 chars

        // Invalid characters (JS injection attempts)
        assertFalse(SecureWebNavigation.isValidToken("e89547d7c6792fca0cb329c2ab87dc7g"))
        assertFalse(SecureWebNavigation.isValidToken("'; alert('xss'); '01234567890123"))
        assertFalse(SecureWebNavigation.isValidToken("E89547D7C6792FCA0CB329C2AB87DC79")) // uppercase hex rejected

        // Must throw IllegalArgumentException if passed to buildSessionInjectionScript
        try {
            SecureWebNavigation.buildSessionInjectionScript("invalid_token")
            org.junit.Assert.fail("Expected IllegalArgumentException for malformed token")
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

