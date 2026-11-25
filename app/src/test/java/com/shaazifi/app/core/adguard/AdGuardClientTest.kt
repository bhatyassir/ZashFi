package com.shaazifi.app.core.adguard

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AdGuardClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AdGuardClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().removeSuffix("/")
        client = AdGuardClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun cursorHistorySendsAnExactSearchAndNoOffset() = runBlocking {
        val cursor = "2026-11-16T15:45:30.123456789+05:30"
        server.enqueue(MockResponse().setBody("""{"data":[],"oldest":""}"""))

        val log = client.loadQueryLog(baseUrl, "test", "test", limit = 50,
            search = "\"192.0.2.20\"", olderThan = cursor, responseStatus = "blocked")

        assertTrue(log.items.isEmpty())
        val url = server.takeRequest().requestUrl!!
        assertEquals("/control/querylog", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("\"192.0.2.20\"", url.queryParameter("search"))
        assertEquals("blocked", url.queryParameter("response_status"))
        assertEquals(cursor, url.queryParameter("older_than"))
        assertNull(url.queryParameter("offset"))
    }

    @Test
    fun testProbeEndpointReturnsTrueForAdGuard200() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version": "v0.107.54", "protection_enabled": true}""")
        )

        val result = client.probeEndpointDetailed(baseUrl)
        assertTrue(result.isAdGuard)
        assertEquals("HTTP 200", result.diagnostic!!.httpCodeOrException)
        assertTrue(result.diagnostic!!.socketConnected)
        assertEquals("${server.hostName}:${server.port}", result.diagnostic!!.targetHostPort)

        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        assertNull("Unauthenticated probe must NOT send Authorization header", recorded.headers["Authorization"])
    }

    @Test
    fun testProbeEndpointReturnsTrueForAdGuard401WithWwwAuthenticate() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", "Basic realm=\"AdGuard Home\"")
                .setBody("""{"message": "Unauthorized"}""")
        )

        val result = client.probeEndpointDetailed(baseUrl)
        assertTrue("AdGuard 401 with WWW-Authenticate must be accepted as AdGuard present", result.isAdGuard)
        assertEquals("HTTP 401", result.diagnostic!!.httpCodeOrException)
        assertTrue(result.diagnostic!!.socketConnected)
        assertEquals("${server.hostName}:${server.port}", result.diagnostic!!.targetHostPort)

        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        assertNull("Unauthenticated probe must NOT send Authorization header", recorded.headers["Authorization"])
    }

    @Test
    fun testProbeEndpointReturnsTrueForAdGuard401WithoutWwwAuthenticate() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message": "Unauthorized"}""")
        )

        val result = client.probeEndpointDetailed(baseUrl)
        assertTrue("AdGuard 401 without WWW-Authenticate must be accepted as AdGuard present", result.isAdGuard)
        assertEquals("HTTP 401", result.diagnostic!!.httpCodeOrException)
        assertTrue(result.diagnostic!!.socketConnected)
        assertEquals("${server.hostName}:${server.port}", result.diagnostic!!.targetHostPort)

        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        assertNull("Unauthenticated probe must NOT send Authorization header", recorded.headers["Authorization"])
    }

    @Test
    fun testProbeEndpointReturnsFalseForRouterPort80Html200() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>LuCI - Lua Configuration Interface</body></html>")
        )

        val result = client.probeEndpointDetailed(baseUrl)
        assertFalse("Router HTML 200 response must be refused", result.isAdGuard)
        assertEquals("HTTP 200 (Non-AdGuard content)", result.diagnostic!!.httpCodeOrException)
        assertTrue(result.diagnostic!!.socketConnected)

        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun testProbeEndpointReturnsFalseForRouterPort80Html404() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>404 Not Found</body></html>")
        )

        val result = client.probeEndpointDetailed(baseUrl)
        assertFalse("404 must be refused", result.isAdGuard)
        assertEquals("HTTP 404", result.diagnostic!!.httpCodeOrException)
        assertTrue(result.diagnostic!!.socketConnected)

        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun testProbeEndpointReturnsFalseOnUnreachableSocket() = runBlocking {
        // Shutdown server to trigger ConnectException
        server.shutdown()

        val result = client.probeEndpointDetailed(baseUrl)
        assertFalse(result.isAdGuard)
        assertFalse("socketConnected must be false on connection failure", result.diagnostic!!.socketConnected)
        assertTrue("Exception class must be captured", result.diagnostic!!.httpCodeOrException.contains("Exception"))
    }

    @Test
    fun testLoadDashboardRefusesToSendCredentialsIfProbeFails() = runBlocking {
        // First call is probe: returns 404 HTML (not AdGuard)
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        try {
            client.loadDashboard(baseUrl, "admin", "secret123")
            fail("Expected AdGuardError.NotAdGuardHome")
        } catch (e: AdGuardError.NotAdGuardHome) {
            // Expected
        }

        // Only 1 request was sent (the probe) and it had NO credentials
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertNull("Password must NEVER be sent to an unverified endpoint", recorded.headers["Authorization"])
    }

    @Test
    fun testHttp401ThrowsUnauthorized() = runBlocking {
        // 1. Probe succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"version": "v0.107.54", "protection_enabled": true}""")
        )
        // 2. Status call with credentials returns 401
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message": "wrong credentials"}""")
        )

        try {
            client.loadDashboard(baseUrl, "admin", "wrong_pass")
            fail("Expected AdGuardError.Unauthorized")
        } catch (e: AdGuardError.Unauthorized) {
            assertEquals("AdGuard username or password incorrect", e.message)
        }
    }

    @Test
    fun testHttp429WithRetryAfterHeaderThrowsRateLimitedWithParsedSeconds() = runBlocking {
        // 1. Probe succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"version": "v0.107.54", "protection_enabled": true}""")
        )
        // 2. Status call returns 429 with Retry-After: 900 (15 minutes)
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "900")
                .setBody("""{"message": "Too Many Requests"}""")
        )

        try {
            client.loadDashboard(baseUrl, "admin", "wrong_pass")
            fail("Expected AdGuardError.RateLimited")
        } catch (e: AdGuardError.RateLimited) {
            assertEquals(900, e.retryAfterSeconds)
            assertTrue("Message must mention 15 minutes when Retry-After is 900", e.message?.contains("15 minutes") == true)
        }
    }

    @Test
    fun testHttp429WithoutRetryAfterHeaderShowsWaitAWhile() = runBlocking {
        // 1. Probe succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"version": "v0.107.54", "protection_enabled": true}""")
        )
        // 2. Status call returns 429 without Retry-After header
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"message": "Too Many Requests"}""")
        )

        try {
            client.loadDashboard(baseUrl, "admin", "wrong_pass")
            fail("Expected AdGuardError.RateLimited")
        } catch (e: AdGuardError.RateLimited) {
            assertNull("retryAfterSeconds must be null when header absent", e.retryAfterSeconds)
            assertTrue("Message must say 'wait a while' when Retry-After header is absent", e.message?.contains("wait a while") == true)
            assertFalse("Message must NOT claim a fixed 15 minutes when header absent", e.message?.contains("15 minutes") == true)
        }
    }

    @Test
    fun testSetProtectionSendsPostJsonWithSetProtectionRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        client.setProtection(baseUrl, "admin", "pass", enabled = false, durationMs = 600000L)

        val recorded = server.takeRequest()
        assertEquals("/control/protection", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("application/json; charset=utf-8", recorded.headers["Content-Type"])
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"enabled\":false"))
        assertTrue(body.contains("\"duration\":600000"))
    }

    @Test
    fun testSetSafeBrowsingSendsPostWithoutContentTypeHeader() = runBlocking {
        // In AdGuard Home CSRF protection, empty-body POSTs like safebrowsing/enable
        // must NOT carry Content-Type: application/json
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        client.setSafeBrowsing(baseUrl, "admin", "pass", enabled = true)

        val recorded = server.takeRequest()
        assertEquals("/control/safebrowsing/enable", recorded.path)
        assertEquals("POST", recorded.method)
        assertNull("Empty-body POST must NOT have Content-Type header in AdGuard", recorded.headers["Content-Type"])
    }

    /**
     * Matches the real AdGuard Home reply to an unauthenticated /control/status:
     * a bare 401 with no WWW-Authenticate header and no body.
     * Source: AdguardTeam/AdGuardHome master 5a6053ab47f1, internal/home/authhttp.go,
     * authMiddlewareDefault.Wrap -> w.WriteHeader(http.StatusUnauthorized).
     */
    @Test
    fun testProbeAcceptsBare401LikeRealAdGuardHome() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.probeEndpointDetailed(server.url("").toString().removeSuffix("/"))
        assertTrue("A bare 401 is what AdGuard Home sends before login", result.isAdGuard)
        assertEquals("HTTP 401", result.diagnostic!!.httpCodeOrException)
        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        assertNull("Probe must never send credentials", recorded.getHeader("Authorization"))
    }

    /** Fake AdGuard: unauthenticated probe gets 401; signed-in calls get the bodies below; anything else 404. */
    private fun fakeAdGuard(safeBrowsing: String?, parental: String?) = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
            val signedIn = request.getHeader("Authorization") != null
            val body = when (request.path) {
                "/control/status" -> if (!signedIn) return MockResponse().setResponseCode(401)
                    else """{"version":"v0.107.54","protection_enabled":true,"dns_addresses":["192.168.1.1"]}"""
                "/control/stats" -> """{"num_dns_queries":100,"num_blocked_filtering":5,"avg_processing_time":0.011}"""
                "/control/safebrowsing/status" -> safeBrowsing
                "/control/parental/status" -> parental
                else -> null
            } ?: return MockResponse().setResponseCode(404)
            return MockResponse().setResponseCode(200).setBody(body)
        }
    }

    @Test
    fun dashboardShowsRealSafeBrowsingAndParentalStatus() = runBlocking {
        server.dispatcher = fakeAdGuard("""{"enabled": false}""", """{"enabled": true, "sensitivity": 13}""")
        val dashboard = client.loadDashboard(baseUrl, "admin", "test-only")
        assertEquals(false, dashboard.safeBrowsingEnabled)
        assertEquals(true, dashboard.parentalEnabled)
    }

    @Test
    fun dashboardMarksSafeBrowsingAndParentalUnknownWhenNotReadable() = runBlocking {
        server.dispatcher = fakeAdGuard(null, null)
        val dashboard = client.loadDashboard(baseUrl, "admin", "test-only")
        assertNull("Unreadable status must be unknown, not guessed", dashboard.safeBrowsingEnabled)
        assertNull(dashboard.parentalEnabled)
        assertEquals(100L, dashboard.stats.numDnsQueries)
    }
}
