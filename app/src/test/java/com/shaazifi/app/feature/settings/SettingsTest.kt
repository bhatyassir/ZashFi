package com.shaazifi.app.feature.settings

import com.shaazifi.app.core.adguard.AdGuardClient
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.data.HttpAdGuardRepository
import com.shaazifi.app.data.InMemoryProfileRepository
import com.shaazifi.app.data.ProfileRepository
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.data.getAdGuardBaseUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    private class FakeProfileRepository(
        initialProfiles: List<RouterProfile> = emptyList()
    ) : ProfileRepository {
        private val profiles = initialProfiles.toMutableList()

        override fun getProfiles(): Flow<List<RouterProfile>> = flowOf(profiles.toList())
        override suspend fun getProfileById(id: String): RouterProfile? = profiles.find { it.id == id }
        override suspend fun saveProfile(profile: RouterProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles.add(profile)
        }
        override suspend fun deleteProfile(id: String) {
            profiles.removeAll { it.id == id }
        }
    }

    @Test
    fun testForgetRouterRemovesProfileAndPin() = runBlocking {
        val testProfile = RouterProfile(
            id = "router-1",
            name = "Test Router",
            address = "https://192.168.1.1",
            certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"
        )
        val repository = FakeProfileRepository(listOf(testProfile))
        val trustStore = TrustStore().apply {
            pin("192.168.1.1", testProfile.certificateFingerprint)
        }

        assertEquals(testProfile.certificateFingerprint, trustStore.getPinned("192.168.1.1"))

        var forgottenSecrets: String? = null
        var storageCleared = false
        val settingsHandler = RouterSettingsHandler(
            profileRepository = repository,
            trustStore = trustStore,
            clearWebStorage = { storageCleared = true },
            forgetSecrets = { forgottenSecrets = it }
        )

        val result = settingsHandler.forgetRouter(testProfile.id, testProfile.address)
        assertEquals("saved passwords must be deleted on forget router", testProfile.id, forgottenSecrets)

        assertTrue(result)
        assertNull(repository.getProfileById(testProfile.id))
        assertNull(trustStore.getPinned("192.168.1.1"))
        assertTrue("WebView storage must be cleared on forget router", storageCleared)
    }

    @Test
    fun testNoOptionToDisableCertificateChecksExists() {
        val fields = RouterSettingsHandler::class.java.declaredFields.map { it.name.lowercase() }
        val methods = RouterSettingsHandler::class.java.declaredMethods.map { it.name.lowercase() }

        assertFalse("Cannot have disableCertificateVerification field", fields.any { it.contains("disablecert") || it.contains("trustall") })
        assertFalse("Cannot have disableCertificateVerification method", methods.any { it.contains("disablecert") || it.contains("trustall") })
    }

    @Test
    fun testUpdateAdGuardConfigSavesHostPortAndProtocol() = runBlocking {
        val testProfile = RouterProfile(
            id = "router-1",
            name = "Test Router",
            address = "https://192.168.1.1",
            certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"
        )
        val repository = FakeProfileRepository(listOf(testProfile))
        val trustStore = TrustStore()
        val settingsHandler = RouterSettingsHandler(
            profileRepository = repository,
            trustStore = trustStore,
            clearWebStorage = {}
        )

        val updated = settingsHandler.updateAdGuardConfig(
            profileId = "router-1",
            enabled = true,
            host = "192.168.1.50",
            port = 8080,
            protocol = "https",
            username = "admin_custom"
        )

        assertNotNull(updated)
        assertEquals("192.168.1.50", updated?.adguardHost)
        assertEquals(8080, updated?.adguardPort)
        assertEquals("https", updated?.adguardProtocol)
        assertEquals("admin_custom", updated?.adguardUsername)
        assertTrue(updated?.adguardEnabled == true)

        val fromRepo = repository.getProfileById("router-1")
        assertEquals(updated, fromRepo)
    }

    @Test
    fun testUpdateAdGuardConfigNormalizesBlankInputs() = runBlocking {
        val testProfile = RouterProfile(
            id = "router-1",
            name = "Test Router",
            address = "https://192.168.1.1",
            certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F",
            adguardHost = "old-host",
            adguardPort = 3000
        )
        val repository = FakeProfileRepository(listOf(testProfile))
        val trustStore = TrustStore()
        val settingsHandler = RouterSettingsHandler(
            profileRepository = repository,
            trustStore = trustStore,
            clearWebStorage = {}
        )

        val updated = settingsHandler.updateAdGuardConfig(
            profileId = "router-1",
            enabled = false,
            host = "   ",
            port = null,
            protocol = "   ",
            username = "   "
        )

        assertNotNull(updated)
        assertNull("Blank host must normalize to null", updated?.adguardHost)
        assertNull("Null port must remain null", updated?.adguardPort)
        assertNull("Blank protocol must normalize to null", updated?.adguardProtocol)
        assertEquals("Blank username must default to admin", "admin", updated?.adguardUsername)
        assertFalse(updated?.adguardEnabled == true)
    }

    @Test
    fun testAdGuardConfigResolvesExactBaseUrlAndReKeysClient() = runBlocking {
        val testProfile = RouterProfile(
            id = "router-1",
            name = "Home Router",
            address = "https://192.168.1.1/untangled/",
            certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"
        )
        val repository = FakeProfileRepository(listOf(testProfile))
        val settingsHandler = RouterSettingsHandler(
            profileRepository = repository,
            trustStore = TrustStore(),
            clearWebStorage = {}
        )

        // 1. Update settings with Host 192.168.1.1, Port 90, HTTP
        val updated = settingsHandler.updateAdGuardConfig(
            profileId = "router-1",
            enabled = true,
            host = "192.168.1.1",
            port = 90,
            protocol = "http",
            username = "admin"
        )
        assertNotNull(updated)

        // Verify base URL resolution
        val baseUrl = updated!!.getAdGuardBaseUrl()
        assertEquals("http://192.168.1.1:90", baseUrl)

        // 2. Also verify if user typed "http://192.168.1.1:90" into host field
        val profileWithPrefix = updated.copy(adguardHost = "http://192.168.1.1:90")
        assertEquals("http://192.168.1.1:90", profileWithPrefix.getAdGuardBaseUrl())

        // 3. Test that HttpAdGuardRepository constructed with this baseUrl routes to the correct target
        val server = MockWebServer()
        server.start()
        val mockBaseUrl = server.url("").toString().removeSuffix("/")

        val adGuardRepo = HttpAdGuardRepository(
            adGuardClient = AdGuardClient(OkHttpClient()),
            baseUrl = mockBaseUrl,
            username = "admin",
            passwordProvider = { "secret" }
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version": "v0.107.54", "protection_enabled": true}""")
        )

        val probeSuccess = adGuardRepo.probe()
        assertTrue(probeSuccess)

        val recorded = server.takeRequest()
        assertEquals("/control/status", recorded.path)
        server.shutdown()
    }

    @Test
    fun testAdGuardBaseUrlKeepsIpv6HostIntact() {
        val base = RouterProfile(name = "R", address = "http://192.168.1.1/", certificateFingerprint = "",
            adguardHost = "fd00::1", adguardPort = 90, adguardProtocol = "http")
        assertEquals("http://[fd00::1]:90", base.getAdGuardBaseUrl())
        assertEquals("http://[fd00::1]:90", base.copy(adguardHost = "[fd00::1]").getAdGuardBaseUrl())
        assertEquals("http://[fd00::1]:90", base.copy(adguardHost = "http://[fd00::1]:90/").getAdGuardBaseUrl())
    }

    // ---- Address entry on the "Not Detected" card ----

    private fun addressTestSetup(): Pair<InMemoryProfileRepository, RouterSettingsHandler> {
        val profile = RouterProfile(
            id = "p1", name = "Home", address = "https://192.168.1.1/", certificateFingerprint = "",
            adguardEnabled = true, adguardUsername = "owner"
        )
        val repo = InMemoryProfileRepository(listOf(profile))
        val handler = RouterSettingsHandler(profileRepository = repo, trustStore = TrustStore(), clearWebStorage = {})
        return repo to handler
    }

    @Test
    fun testSaveAdGuardAddressStoresHostPortProtocolAndKeepsOtherFields() = runBlocking {
        val (repo, handler) = addressTestSetup()
        val result = handler.saveAdGuardAddress("p1", " 192.168.1.1 ", " 90 ", "HTTP")
        assertTrue("Valid private address must save", result is AdGuardAddressSaveResult.Saved)
        val stored = repo.getProfileById("p1")!!
        assertEquals("192.168.1.1", stored.adguardHost)
        assertEquals(90, stored.adguardPort)
        assertEquals("http", stored.adguardProtocol)
        assertEquals("owner", stored.adguardUsername)
        assertTrue(stored.adguardEnabled)
        assertEquals("http://192.168.1.1:90", stored.getAdGuardBaseUrl())
        assertEquals(stored, (result as AdGuardAddressSaveResult.Saved).profile)
    }

    @Test
    fun testSaveAdGuardAddressAcceptsFullUrlTypedIntoHostField() = runBlocking {
        val (repo, handler) = addressTestSetup()
        val result = handler.saveAdGuardAddress("p1", "http://192.168.1.1:90/", "90", "http")
        assertTrue(result is AdGuardAddressSaveResult.Saved)
        assertEquals("192.168.1.1", repo.getProfileById("p1")!!.adguardHost)
    }

    @Test
    fun testSaveAdGuardAddressRejectsBadInputAndLeavesProfileUnchanged() = runBlocking {
        val (repo, handler) = addressTestSetup()
        val before = repo.getProfileById("p1")
        val bad = listOf(
            Triple("", "90", "http"),             // blank host
            Triple("192.168.1.1", "abc", "http"), // port not a number
            Triple("192.168.1.1", "0", "http"),   // port out of range
            Triple("192.168.1.1", "70000", "http"),
            Triple("192.168.1.1", "90", "ftp"),   // unsupported protocol
            Triple("8.8.8.8", "90", "http")       // public address: AddressGuard refuses
        )
        for ((host, port, proto) in bad) {
            val result = handler.saveAdGuardAddress("p1", host, port, proto)
            assertTrue("Must refuse host='$host' port='$port' proto='$proto'", result is AdGuardAddressSaveResult.Error)
            assertTrue((result as AdGuardAddressSaveResult.Error).message.isNotBlank())
        }
        assertEquals("Refused input must not change the saved profile", before, repo.getProfileById("p1"))
    }

    @Test
    fun testSaveAdGuardAddressReportsMissingProfileInsteadOfFailingSilently() = runBlocking {
        val (_, handler) = addressTestSetup()
        val result = handler.saveAdGuardAddress("no-such-id", "192.168.1.1", "90", "http")
        assertTrue(result is AdGuardAddressSaveResult.Error)
    }
}
