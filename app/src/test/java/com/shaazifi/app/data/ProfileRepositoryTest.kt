package com.shaazifi.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryTest {

    private val testAddress = "https://100.64.0.7"
    private val testFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"

    @Test
    fun testRouterProfileBindsAddressAndCertificateFingerprint() {
        val profile = RouterProfile(
            id = "test-router-1",
            name = "Home Router",
            address = testAddress,
            certificateFingerprint = testFingerprint
        )

        assertEquals("test-router-1", profile.id)
        assertEquals("Home Router", profile.name)
        assertEquals(testAddress, profile.address)
        assertEquals(testFingerprint, profile.certificateFingerprint)
    }

    @Test
    fun testInMemoryProfileRepositoryLifecycle() = runBlocking {
        val repository: ProfileRepository = InMemoryProfileRepository()

        // 1. Initial state is empty
        val initialProfiles = repository.getProfiles().first()
        assertTrue(initialProfiles.isEmpty())

        // 2. Save profile
        val profile = RouterProfile(
            id = "profile-1",
            name = "My OpenWrt",
            address = testAddress,
            certificateFingerprint = testFingerprint
        )
        repository.saveProfile(profile)

        // 3. Retrieve profiles
        val profiles = repository.getProfiles().first()
        assertEquals(1, profiles.size)
        assertEquals(profile, profiles[0])

        // 4. Retrieve by ID
        val fetched = repository.getProfileById("profile-1")
        assertNotNull(fetched)
        assertEquals(testAddress, fetched?.address)
        assertEquals(testFingerprint, fetched?.certificateFingerprint)

        // 5. Delete profile
        repository.deleteProfile("profile-1")
        val afterDelete = repository.getProfiles().first()
        assertTrue(afterDelete.isEmpty())
        assertNull(repository.getProfileById("profile-1"))
    }

    @Test
    fun testProfileSerializationAndDeserialization() {
        val original = RouterProfile(
            id = "router-123",
            name = "Tailscale Node",
            address = "https://100.64.0.7",
            certificateFingerprint = testFingerprint,
            createdAt = 1700000000000L
        )

        val json = ProfileJsonAdapter.toJson(original)
        val deserialized = ProfileJsonAdapter.fromJson(json)

        assertNotNull(deserialized)
        assertEquals(original.id, deserialized?.id)
        assertEquals(original.name, deserialized?.name)
        assertEquals(original.address, deserialized?.address)
        assertEquals(original.certificateFingerprint, deserialized?.certificateFingerprint)
        assertEquals(original.createdAt, deserialized?.createdAt)
    }

    @Test
    fun testPreM8ProfileWithoutAdGuardFieldsDeserializesCleanly() {
        val legacyJson = """
            {
                "id": "legacy-profile-1",
                "name": "Legacy Router",
                "address": "https://192.168.1.1",
                "certificateFingerprint": "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77",
                "createdAt": 1700000000000
            }
        """.trimIndent()

        val profile = ProfileJsonAdapter.fromJson(legacyJson)
        assertNotNull(profile)
        assertEquals("legacy-profile-1", profile?.id)
        assertEquals("Legacy Router", profile?.name)
        assertEquals("https://192.168.1.1", profile?.address)
        assertTrue(profile?.adguardEnabled == true)
        assertNull(profile?.adguardHost)
        assertNull(profile?.adguardPort)
        assertNull(profile?.adguardProtocol)
        assertEquals("admin", profile?.adguardUsername)
    }
}

