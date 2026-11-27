package com.shaazifi.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private class FakePreferencesDataStore(initial: Preferences = preferencesOf()) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(flow.value)
            flow.value = next
            return next
        }
    }

    @Test
    fun testDataStoreRepositoryThrowsOnCorruptedJsonAndPreservesData() = runBlocking {
        val key = stringPreferencesKey("saved_router_profiles")
        val corrupted = "{not valid json"
        val dataStore = FakePreferencesDataStore(preferencesOf(key to corrupted))
        val repo = DataStoreProfileRepository(dataStore)

        try {
            repo.getProfiles().first()
            fail("getProfiles must throw on corrupted JSON")
        } catch (_: Exception) {
        }

        try {
            repo.saveProfile(
                RouterProfile(
                    id = "new-1",
                    name = "New",
                    address = testAddress,
                    certificateFingerprint = testFingerprint
                )
            )
            fail("saveProfile must throw instead of wiping corrupted storage")
        } catch (_: Exception) {
        }

        assertEquals(corrupted, dataStore.data.first()[key])
    }

    @Test
    fun testInMemoryProfileRepositoryHandlesConcurrentSavesSafely() = runBlocking {
        val repo = InMemoryProfileRepository()
        val jobs = (1..25).map { idx ->
            async {
                repo.saveProfile(
                    RouterProfile(
                        id = "r-$idx",
                        name = "Router $idx",
                        address = testAddress,
                        certificateFingerprint = testFingerprint
                    )
                )
            }
        }
        jobs.awaitAll()
        assertEquals(25, repo.getProfiles().first().size)
    }
}

