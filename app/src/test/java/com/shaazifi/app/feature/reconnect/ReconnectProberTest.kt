package com.shaazifi.app.feature.reconnect

import com.shaazifi.app.core.net.TrustStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

class ReconnectProberTest {

    private fun createThrowawayKeyStoreAndCert(
        alias: String,
        pass: String = "testpass",
        san: String? = "dns:localhost,ip:127.0.0.1"
    ): Pair<SSLSocketFactory, X509Certificate> {
        val tempFile = File.createTempFile("test_keystore_$alias", ".p12")
        tempFile.delete()
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

    @Test
    fun testProbeRecognizesChangesKeptAtNewAddress() = runBlocking {
        val (sslFactory, cert) = createThrowawayKeyStoreAndCert("keptRouter")
        val fingerprint = TrustStore.fingerprint(cert)

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.start()

        try {
            val candidateUrl = server.url("/").toString()
            val oldUrl = "https://192.168.1.1:9999" // unreachable

            val prober = ReconnectProber(
                totalDurationSeconds = 130,
                probeIntervalMs = 50L // fast for test
            )

            val states = prober.probe(
                currentAddress = oldUrl,
                candidateAddress = candidateUrl,
                pinnedFingerprint = fingerprint
            ).toList()

            val lastState = states.last()
            assertTrue("Expected ReconnectedKept but got $lastState", lastState is ReconnectState.ReconnectedKept)
            assertEquals(candidateUrl, (lastState as ReconnectState.ReconnectedKept).newAddress)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeRecognizesChangesRolledBackAtOldAddress() = runBlocking {
        val (sslFactory, cert) = createThrowawayKeyStoreAndCert("rollbackRouter")
        val fingerprint = TrustStore.fingerprint(cert)

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.start()

        try {
            val oldUrl = server.url("/").toString()
            val candidateUrl = "https://192.168.2.1:9999" // unreachable candidate

            val prober = ReconnectProber(
                totalDurationSeconds = 130,
                probeIntervalMs = 50L
            )

            val states = prober.probe(
                currentAddress = oldUrl,
                candidateAddress = candidateUrl,
                pinnedFingerprint = fingerprint
            ).toList()

            val lastState = states.last()
            assertTrue("Expected ReconnectedRolledBack but got $lastState", lastState is ReconnectState.ReconnectedRolledBack)
            assertEquals(oldUrl, (lastState as ReconnectState.ReconnectedRolledBack).oldAddress)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeHaltsOnCertificateChangedNeverAutoTrusts() = runBlocking {
        val (sslFactory, _) = createThrowawayKeyStoreAndCert("changedCertRouter")

        val server = MockWebServer()
        server.useHttps(sslFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.start()

        try {
            val url = server.url("/").toString()
            val differentFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"

            val prober = ReconnectProber(
                totalDurationSeconds = 2,
                probeIntervalMs = 50L
            )

            val states = prober.probe(
                currentAddress = url,
                candidateAddress = null,
                pinnedFingerprint = differentFingerprint
            ).toList()

            val lastState = states.last()
            assertTrue("Expected CertificateChanged but got $lastState", lastState is ReconnectState.CertificateChanged)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeTimesOutAfterCountdownExceeding120Seconds() = runBlocking {
        val prober = ReconnectProber(
            totalDurationSeconds = 2, // 2s for test, but default must be > 120s
            probeIntervalMs = 50L
        )

        assertEquals(130, ReconnectProber.DEFAULT_DURATION_SECONDS)
        assertTrue("Default duration must exceed 120s for OpenWrt rollback timer", ReconnectProber.DEFAULT_DURATION_SECONDS > 120)

        val states = prober.probe(
            currentAddress = "https://192.168.1.1:9999",
            candidateAddress = "https://192.168.2.1:9999",
            pinnedFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        ).toList()

        val lastState = states.last()
        assertTrue("Expected TimeoutUnreachable but got $lastState", lastState is ReconnectState.TimeoutUnreachable)
    }

    @Test
    fun testProbeRefusesPublicCandidateAddress() = runBlocking {
        val prober = ReconnectProber(
            totalDurationSeconds = 2,
            probeIntervalMs = 50L
        )

        val states = prober.probe(
            currentAddress = "https://192.168.1.1:9999",
            candidateAddress = "https://8.8.8.8", // public IP rejected by AddressGuard
            pinnedFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        ).toList()

        // Candidate 8.8.8.8 was skipped, only 192.168.1.1 was probed (which timed out)
        val lastState = states.last()
        assertTrue(lastState is ReconnectState.TimeoutUnreachable)
    }
}
