package com.shaazifi.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.Base64

/** Which password is saved for a router. */
enum class SecretKind(val tag: String) {
    ROUTER("router"),
    ADGUARD("adguard"),
    /** M10c: copy of the router password for background alert checks; opens without fingerprint. */
    ROUTER_BACKGROUND("router_bg"),
    /** Owner feedback: the signed-in session (token), kept for a week; same key as ROUTER_BACKGROUND. */
    SESSION("session")
}

/**
 * Encrypts and decrypts saved passwords. The real one is [KeystoreSecretCipher] (a key that never
 * leaves the phone's Keystore and only works after fingerprint or PIN). [aad] is "associated data":
 * it is checked on decrypt, so a saved password cannot be moved to another router or kind.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** Why a saved password could not be read. */
sealed class SecretUnavailableException(message: String) : Exception(message) {
    /** Fingerprint or PIN is needed first (or was too long ago). */
    class NeedsUnlock : SecretUnavailableException("Unlock with fingerprint or PIN first.")
    /** The phone's screen lock was removed or changed, so the key is gone. The password must be typed again. */
    class KeyGone : SecretUnavailableException("Saved passwords were cleared because the phone's screen lock changed. Type the password again.")
    /** The stored data does not match its entry. */
    class Tampered : SecretUnavailableException("The saved password could not be read. Type it again.")
}

/** Where encrypted blobs are kept. The app uses [DataStoreSecretBox]. */
interface SecretBox {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

class DataStoreSecretBox(private val dataStore: DataStore<Preferences>) : SecretBox {
    override suspend fun get(key: String): String? = dataStore.data.first()[stringPreferencesKey(key)]
    override suspend fun put(key: String, value: String) { dataStore.edit { it[stringPreferencesKey(key)] = value } }
    override suspend fun remove(key: String) { dataStore.edit { it.remove(stringPreferencesKey(key)) } }
}

/**
 * Saved router and AdGuard passwords, one per router and kind, encrypted with [cipher].
 * Passwords are only saved when the owner ticks "remember". Nothing here is logged.
 */
class SecretStore(private val box: SecretBox, private val cipher: SecretCipher) {

    suspend fun save(profileId: String, kind: SecretKind, secret: String) {
        val plain = secret.toByteArray(Charsets.UTF_8)
        val blob = try {
            cipher.encrypt(plain, aad(profileId, kind))
        } finally {
            plain.fill(0)
        }
        box.put(keyFor(profileId, kind), Base64.getEncoder().encodeToString(blob))
    }

    /** The saved password, or null when none is saved. Throws [SecretUnavailableException] when one is saved but cannot be read. */
    suspend fun load(profileId: String, kind: SecretKind): String? {
        val stored = box.get(keyFor(profileId, kind)) ?: return null
        val blob = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            throw SecretUnavailableException.Tampered()
        }
        val plain = cipher.decrypt(blob, aad(profileId, kind))
        return try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    suspend fun has(profileId: String, kind: SecretKind): Boolean = box.get(keyFor(profileId, kind)) != null

    suspend fun delete(profileId: String, kind: SecretKind) = box.remove(keyFor(profileId, kind))

    /** Removes every saved password of this router. */
    suspend fun forget(profileId: String) {
        SecretKind.entries.forEach { delete(profileId, it) }
    }

    companion object {
        fun keyFor(profileId: String, kind: SecretKind) = "secret_${kind.tag}_$profileId"
        private fun aad(profileId: String, kind: SecretKind) = "shaazifi:${kind.tag}:$profileId".toByteArray(Charsets.UTF_8)
    }
}
