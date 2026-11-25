package com.shaazifi.app.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

class TrustStoreTest {

    /**
     * Made-up test certificate (CN=ShaaziFi Test Certificate, made with keytool on 2026-11-17) and
     * its SHA-256 fingerprint as keytool prints it. Replaced the real router's certificate so no
     * router details are needed in the tests (public release, T-20261117-C0594015 P6).
     */
    private val testCertificateFingerprint = "8D:0C:D8:A9:AD:30:8F:3E:1C:2B:EF:B3:74:50:17:FC:B4:DA:B8:B5:75:26:92:1B:D8:5F:F1:4A:BE:88:7D:AC"

    @Test
    fun testCertificateFingerprintCalculation() {
        val stream: InputStream = javaClass.classLoader!!.getResourceAsStream("test_cert.der")
            ?: error("test_cert.der not found in test resources")
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(stream) as X509Certificate

        val calculated = TrustStore.fingerprint(cert)
        assertEquals(testCertificateFingerprint, calculated)
    }

    @Test
    fun testMockWebServerTwoCertificatesHardStopOnChangedCert() {
        val (sslFactory1, cert1) = createThrowawayKeyStoreAndCert("cert1", "password")
        val (sslFactory2, cert2) = createThrowawayKeyStoreAndCert("cert2", "password")

        val fp1 = TrustStore.fingerprint(cert1)
        val fp2 = TrustStore.fingerprint(cert2)
        assertTrue("Test certificates must have different fingerprints", fp1 != fp2)

        val server1 = MockWebServer()
        server1.useHttps(sslFactory1, false)
        server1.enqueue(MockResponse().setBody("OK from server 1"))
        server1.start()

        val server2 = MockWebServer()
        server2.useHttps(sslFactory2, false)
        server2.enqueue(MockResponse().setBody("OK from server 2"))
        server2.start()

        try {
            // Pin cert1 for server1 in TrustStore
            val trustStore = TrustStore()
            trustStore.pin("localhost:${server1.port}", fp1)
            trustStore.pin("127.0.0.1:${server1.port}", fp1)
            trustStore.pin("localhost", fp1)
            trustStore.pin("127.0.0.1", fp1)

            val client = OkHttpClient.Builder()
                .sslSocketFactory(trustStore.sslSocketFactory, trustStore.trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()

            // Request 1 to server1: server1 presents pinned cert1 -> SUCCESS
            val server1Url = server1.url("/test").newBuilder().host("127.0.0.1").build()
            val response1 = client.newCall(Request.Builder().url(server1Url).build()).execute()
            assertEquals(200, response1.code)
            assertEquals("OK from server 1", response1.body?.string())

            // Now test server2: pin fp1 for server2, but server2 presents cert2.
            // MUST be a HARD STOP: throw CertificateChangedException, NEVER silent!
            val trustStore2 = TrustStore()
            trustStore2.pin("127.0.0.1:${server2.port}", fp1)
            trustStore2.pin("localhost:${server2.port}", fp1)
            trustStore2.pin("127.0.0.1", fp1)
            trustStore2.pin("localhost", fp1)

            val client2 = OkHttpClient.Builder()
                .sslSocketFactory(trustStore2.sslSocketFactory, trustStore2.trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()

            val server2Url = server2.url("/test").newBuilder().host("127.0.0.1").build()
            try {
                client2.newCall(Request.Builder().url(server2Url).build()).execute()
                fail("Expected CertificateChangedException when certificate fingerprint changed")
            } catch (e: Exception) {
                var foundChanged = false
                fun inspect(t: Throwable?) {
                    if (t == null || foundChanged) return
                    if (t is CertificateChangedException) {
                        foundChanged = true
                        assertEquals(fp1, t.expectedFingerprint)
                        assertEquals(fp2, t.actualFingerprint)
                        return
                    }
                    inspect(t.cause)
                    for (s in t.suppressed) {
                        inspect(s)
                    }
                }
                inspect(e)
                assertTrue("Expected CertificateChangedException in causal chain, got $e", foundChanged)
            }
        } finally {
            server1.shutdown()
            server2.shutdown()
        }
    }

    @Test
    fun testHostnameVerifierAcceptsPinnedCertificateEvenWithMismatchedSan() {
        // Router cert has CN=OpenWrt and NO SAN matching 127.0.0.1 or test IP
        val (sslFactory, cert) = createThrowawayKeyStoreAndCert("OpenWrt", "password", san = null)
        val fp = TrustStore.fingerprint(cert)

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setBody("Untangled Dashboard"))
        server.start()

        try {
            val trustStore = TrustStore()
            trustStore.pin("127.0.0.1:${server.port}", fp)
            trustStore.pin("127.0.0.1", fp)

            // Using HttpFactory which wires TrustStore's hostnameVerifier
            val client = HttpFactory.createClient(trustStore)
            val url = server.url("/untangled/").newBuilder().host("127.0.0.1").build()

            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            assertEquals(200, resp.code)
            assertEquals("Untangled Dashboard", resp.body?.string())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testNoPinnedFingerprintRefusedUntilUserApproves() {
        val (sslFactory, cert) = createThrowawayKeyStoreAndCert("unpinned_router", "password")
        val fp = TrustStore.fingerprint(cert)

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setBody("Confidential Router Page"))
        server.start()

        try {
            // An empty TrustStore with NO pins configured
            val unpinnedTrustStore = TrustStore()
            val client = HttpFactory.createClient(unpinnedTrustStore)
            val url = server.url("/untangled/").newBuilder().host("127.0.0.1").build()

            // (a) Must refuse connection when certificate is not pinned
            try {
                client.newCall(Request.Builder().url(url).build()).execute()
                fail("Expected UntrustedCertificateException when connecting without a pinned fingerprint")
            } catch (e: Exception) {
                var foundUntrusted = false
                var capturedFingerprint: String? = null
                fun inspect(t: Throwable?) {
                    if (t == null || foundUntrusted) return
                    if (t is UntrustedCertificateException) {
                        foundUntrusted = true
                        capturedFingerprint = t.presentedFingerprint
                        return
                    }
                    inspect(t.cause)
                    for (s in t.suppressed) inspect(s)
                }
                inspect(e)
                assertTrue("Expected UntrustedCertificateException in chain, got $e", foundUntrusted)
                assertEquals(fp, capturedFingerprint)
            }

            // Once the user approves the fingerprint, pin it
            unpinnedTrustStore.pin("127.0.0.1:${server.port}", fp)
            unpinnedTrustStore.pin("127.0.0.1", fp)
            server.enqueue(MockResponse().setBody("Confidential Router Page"))

            // Subsequent connection with approved pin must succeed
            val approvedResponse = client.newCall(Request.Builder().url(url).build()).execute()
            assertEquals(200, approvedResponse.code)
            assertEquals("Confidential Router Page", approvedResponse.body?.string())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testTrustManagerAndHostnameVerifierNeverTrustAll() {
        val (sslFactory1, cert1) = createThrowawayKeyStoreAndCert("pinned_host", "password")
        val (sslFactory2, cert2) = createThrowawayKeyStoreAndCert("unknown_host", "password")
        val fp1 = TrustStore.fingerprint(cert1)
        val fp2 = TrustStore.fingerprint(cert2)
        assertTrue(fp1 != fp2)

        val server2 = MockWebServer()
        server2.useHttps(sslFactory2, false)
        server2.enqueue(MockResponse().setBody("Attack Server"))
        server2.start()

        try {
            // TrustStore only has pin for host 192.168.1.1 with fp1
            val trustStore = TrustStore()
            trustStore.pin("192.168.1.1", fp1)

            val client = HttpFactory.createClient(trustStore)
            val url = server2.url("/").newBuilder().host("127.0.0.1").build()

            // (b) Must reject server2 because its fingerprint is not pinned for 127.0.0.1
            try {
                client.newCall(Request.Builder().url(url).build()).execute()
                fail("Expected connection to be rejected because fp2 is not pinned; must never trust-all")
            } catch (e: Exception) {
                var rejected = false
                fun inspect(t: Throwable?) {
                    if (t == null || rejected) return
                    if (t is CertificateChangedException || t is UntrustedCertificateException || t is javax.net.ssl.SSLPeerUnverifiedException) {
                        rejected = true
                        return
                    }
                    inspect(t.cause)
                    for (s in t.suppressed) inspect(s)
                }
                inspect(e)
                assertTrue("Connection to unpinned certificate must be rejected, got $e", rejected)
            }
        } finally {
            server2.shutdown()
        }
    }

    private fun createThrowawayKeyStoreAndCert(
        alias: String,
        pass: String,
        san: String? = "dns:localhost,ip:127.0.0.1"
    ): Pair<SSLSocketFactory, X509Certificate> {
        val tempFile = File.createTempFile("test_keystore_$alias", ".p12")
        tempFile.delete() // keytool will create it
        tempFile.deleteOnExit()

        val cmd = mutableListOf(
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
        if (san != null) {
            cmd.add("-ext")
            cmd.add("san=$san")
        }

        val pb = ProcessBuilder(cmd)
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
