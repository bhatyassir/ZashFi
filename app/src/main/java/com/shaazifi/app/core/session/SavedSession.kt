package com.shaazifi.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owner feedback 2026-11-17: "stay logged in for a week". The router keeps a sign-in for
 * [TIMEOUT_SECONDS] of no use (rpcd session.c: login `timeout`, default 300 s); the phone keeps
 * the session encrypted (Keystore key that opens without fingerprint, like the alerts' copy) so
 * it survives Android closing the app. The app lock, when on, still covers the screens.
 * Sign out, Forget router and the week running out delete it.
 */
@Serializable
data class SavedSession(val profileId: String, val token: String, val endpoint: String, val viaVpn: Boolean, val savedAt: Long) {
    fun isFresh(now: Long) = now - savedAt < TIMEOUT_SECONDS * 1000L
    fun encode() = json.encodeToString(this)
    override fun toString() = "SavedSession(profileId=$profileId, endpoint=$endpoint, viaVpn=$viaVpn, savedAt=$savedAt)"

    companion object {
        const val TIMEOUT_SECONDS = 7 * 24 * 3600
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(s: String?): SavedSession? = s?.let { runCatching { json.decodeFromString<SavedSession>(it) }.getOrNull() }
    }
}

/** Where the saved session lives: the token encrypted in [secrets], the router id in plain app storage. */
class SavedSessionStore(private val dataStore: DataStore<Preferences>, private val secrets: SecretStore) {
    private val lastKey = stringPreferencesKey("last_session_router")
    private val mutex = Mutex()

    suspend fun save(s: SavedSession) {
        mutex.withLock {
            secrets.save(s.profileId, SecretKind.SESSION, s.encode())
            dataStore.edit { it[lastKey] = s.profileId }
        }
    }

    suspend fun last(): SavedSession? = mutex.withLock {
        dataStore.data.first()[lastKey]?.let { read(it) }
    }

    private suspend fun read(id: String): SavedSession? = try {
        SavedSession.decode(secrets.load(id, SecretKind.SESSION))?.takeIf { it.profileId == id }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** A stale resume check must never delete a newer sign-in. */
    suspend fun clear(expected: SavedSession? = null) {
        mutex.withLock {
            val id = dataStore.data.first()[lastKey] ?: return@withLock
            if (expected != null && (id != expected.profileId || read(id) != expected)) return@withLock
            try {
                secrets.delete(id, SecretKind.SESSION)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Remove the pointer even when the encrypted copy is already inaccessible.
            }
            dataStore.edit { it.remove(lastKey) }
        }
    }
}
