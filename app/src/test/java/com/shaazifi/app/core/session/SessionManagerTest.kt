package com.shaazifi.app.core.session

import com.shaazifi.app.core.net.HttpFactory
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.data.RouterProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

class SessionManagerTest {

    private val loginResponse = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "result": [
                0,
                {
                    "ubus_rpc_session": "sess_token_12345",
                    "timeout": 300,
                    "expires": 300,
                    "data": { "username": "root" }
                }
            ]
        }
    """.trimIndent()

    private val destroyResponse = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "result": [0]
        }
    """.trimIndent()

    @Test
    fun testLoginEstablishesSessionAndPinsFingerprint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginResponse))
        server.start()

        try {
            val trustStore = TrustStore()
            val ubusClient = UbusClient(OkHttpClient())
            val sessionManager = SessionManager(ubusClient, trustStore, OkHttpClient())

            val profile = RouterProfile(
                id = "test-p1",
                name = "Test Router",
                address = server.url("/").toString().removeSuffix("/"),
                certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"
            )

            // Initially logged out
            assertTrue(sessionManager.sessionState.value is SessionState.LoggedOut)

            // Login
            val session = sessionManager.login(profile, "secret_password")
            assertNotNull(session)
            assertEquals("sess_token_12345", session.token)
            assertEquals(300, session.timeoutSeconds)

            // Verify fingerprint was pinned in TrustStore
            val pinnedFp = trustStore.getPinned(profile.address)
            assertEquals(profile.certificateFingerprint, pinnedFp)

            // Verify StateFlow has LoggedIn state
            val state = sessionManager.sessionState.first()
            assertTrue(state is SessionState.LoggedIn)
            assertEquals("sess_token_12345", (state as SessionState.LoggedIn).token)

            // Token is in memory only
            assertEquals("sess_token_12345", sessionManager.currentActiveToken)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testLogoutClearsSessionAndDestroysToken() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginResponse))
        server.enqueue(MockResponse().setResponseCode(200).setBody(destroyResponse))
        server.start()

        try {
            val trustStore = TrustStore()
            val ubusClient = UbusClient(OkHttpClient())
            val sessionManager = SessionManager(ubusClient, trustStore, OkHttpClient())

            val profile = RouterProfile(
                id = "test-p2",
                name = "Test Router",
                address = server.url("/").toString().removeSuffix("/"),
                certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"
            )

            sessionManager.login(profile, "password")
            assertEquals("sess_token_12345", sessionManager.currentActiveToken)

            // Logout
            sessionManager.logout()
            assertNull(sessionManager.currentActiveToken)
            assertTrue(sessionManager.sessionState.value is SessionState.LoggedOut)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun keptSessionCanRestoreTheSameTokenAfterAnOutage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(destroyResponse))
            val profile = RouterProfile("kept-router", "Test Router", server.url("/").toString(), "HTTP_NO_PIN")
            val kept = SavedSession(profile.id, "saved-token", profile.address, false, System.currentTimeMillis())
            val client = OkHttpClient()
            val saved = mutableListOf<SessionState.LoggedIn>()
            val manager = SessionManager(UbusClient(client), TrustStore(), client, onSignedIn = { saved.add(it) })

            assertEquals(ResumeResult.Unreachable, manager.resume(profile, kept))
            assertNull(manager.currentActiveToken)
            assertTrue(saved.isEmpty())
            assertEquals(ResumeResult.Restored, manager.resume(profile, kept))
            assertEquals(kept.token, manager.currentActiveToken)
            assertEquals(kept.token, saved.single().token)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/ubus", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("\"saved-token\",\"system\",\"board\""))
            }
        }
    }

    @Test
    fun resumeSeparatesExplicitRejectionFromAnUnavailableBoardRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":[4]}"""))
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"error":{"code":-32002,"message":"Access denied"}}"""))
            val profile = RouterProfile("kept-router", "Test Router", server.url("/").toString(), "HTTP_NO_PIN")
            val kept = SavedSession(profile.id, "saved-token", profile.address, false, System.currentTimeMillis())
            val client = OkHttpClient()
            val manager = SessionManager(UbusClient(client), TrustStore(), client)

            assertEquals(ResumeResult.Unavailable, manager.resume(profile, kept))
            assertEquals(ResumeResult.Rejected, manager.resume(profile, kept))
            assertNull(manager.currentActiveToken)
        }
    }

    @Test
    fun resumeTriesTheApprovedVpnAddressWhenHomeIsUnreachable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(destroyResponse))
            val vpnAddress = server.url("/").toString()
            val homeAddress = "http://192.168.1.1"
            val profile = RouterProfile(
                "kept-router", "Test Router", homeAddress, "HTTP_NO_PIN",
                vpnAddress = vpnAddress, vpnCertificateFingerprint = "HTTP_NO_PIN"
            )
            val kept = SavedSession(profile.id, "saved-token", homeAddress, false, System.currentTimeMillis())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.host == "192.168.1.1") throw ConnectException("Home unavailable")
                chain.proceed(chain.request())
            }.build()
            val manager = SessionManager(UbusClient(client), TrustStore(), client)

            assertEquals(ResumeResult.Unavailable, manager.resume(profile, kept.copy(endpoint = "http://192.168.1.2")))
            assertEquals(0, server.requestCount)
            assertEquals(ResumeResult.Restored, manager.resume(profile, kept))
            val active = manager.sessionState.value as SessionState.LoggedIn
            assertEquals(vpnAddress, active.endpoint)
            assertTrue(active.viaVpn)
        }
    }

    @Test
    fun testProbeCertificateCapturesFingerprintFromUnpinnedServer() = runBlocking {
        val (sslFactory, cert) = createThrowawayKeyStoreAndCert("probe_router", "password")
        val expectedFp = TrustStore.fingerprint(cert)

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("Probe Landing Page"))
        server.start()

        try {
            val emptyTrustStore = TrustStore()
            val client = HttpFactory.createClient(emptyTrustStore)
            val ubusClient = UbusClient(client)
            val sessionManager = SessionManager(ubusClient, emptyTrustStore, client)

            val serverUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()

            // Probe must extract the unpinned certificate fingerprint without sending credentials
            val probeResult = sessionManager.probeCertificate(serverUrl)
            assertTrue(probeResult.isSuccess)
            assertEquals(expectedFp, probeResult.getOrNull())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeCertificateHandlesPlainHttpEndpointWithoutPinning() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>OpenWrt HTTP</html>"))
        server.start()

        try {
            val emptyTrustStore = TrustStore()
            val client = HttpFactory.createClient(emptyTrustStore)
            val ubusClient = UbusClient(client)
            val sessionManager = SessionManager(ubusClient, emptyTrustStore, client)

            val serverUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
            val probeResult = sessionManager.probeCertificate(serverUrl)

            assertTrue("Probe must succeed for plain HTTP", probeResult.isSuccess)
            assertEquals("HTTP_NO_PIN", probeResult.getOrNull())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeCertificateCapturesFingerprintFromHandshakeWhenAlreadyAccepted() = runBlocking {
        val (sslFactory, cert) = createThrowawayKeyStoreAndCert("lan_probe_router", "password")
        val expectedFp = TrustStore.fingerprint(cert)

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("Landing Page"))
        server.start()

        try {
            val trustStore = TrustStore()
            // Pin under another host (simulating Tailscale IP pinned, now probing LAN IP)
            trustStore.pin("10.0.0.1", expectedFp)

            val client = HttpFactory.createClient(trustStore)
            val ubusClient = UbusClient(client)
            val sessionManager = SessionManager(ubusClient, trustStore, client)

            val serverUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()

            val probeResult = sessionManager.probeCertificate(serverUrl)
            assertTrue("Probe must succeed when certificate is already trusted under another host", probeResult.isSuccess)
            assertEquals(expectedFp, probeResult.getOrNull())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeCertificateProvidesDiagnosticOnConnectionFailure() = runBlocking {
        val trustStore = TrustStore()
        val client = HttpFactory.createClient(trustStore)
        val ubusClient = UbusClient(client)
        val sessionManager = SessionManager(ubusClient, trustStore, client)

        val probeResult = sessionManager.probeCertificate("http://127.0.0.1:1")
        assertTrue(probeResult.isFailure)

        val exception = probeResult.exceptionOrNull()
        assertTrue("Exception must be ProbeException", exception is ProbeException)
        val diag = (exception as ProbeException).diagnostic
        assertEquals(false, diag.socketConnected)
        assertNotNull(diag.exceptionClass)
        val cls = diag.exceptionClass ?: ""
        assertTrue(cls.contains("ConnectException") || cls.contains("SocketException"))
    }

    private fun createThrowawayKeyStoreAndCert(
        alias: String,
        pass: String
    ): Pair<SSLSocketFactory, X509Certificate> {
        val tempFile = File.createTempFile("test_keystore_$alias", ".p12")
        tempFile.delete()
        tempFile.deleteOnExit()

        val pb = ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-keystore", tempFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", pass,
            "-dname", "CN=$alias"
        )
        val proc = pb.start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val err = proc.errorStream.bufferedReader().readText()
            error("keytool failed ($exitCode): $err")
        }

        val ks = KeyStore.getInstance("PKCS12")
        tempFile.inputStream().use { ks.load(it, pass.toCharArray()) }
        tempFile.delete()

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass.toCharArray())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)

        val cert = ks.getCertificate(alias) as X509Certificate
        return Pair(sslContext.socketFactory, cert)
    }
}
