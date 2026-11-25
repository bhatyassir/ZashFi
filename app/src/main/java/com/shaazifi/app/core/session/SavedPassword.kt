package com.shaazifi.app.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.first

/** What happened when the app tried to read a saved password. */
sealed class SavedPasswordResult {
    data class Value(val secret: String) : SavedPasswordResult()
    data object Cancelled : SavedPasswordResult()
    data class Message(val text: String) : SavedPasswordResult()
}

/**
 * One saved password (router or AdGuard) for one router, behind the fingerprint / PIN prompt.
 * [usable] is false when the phone has no screen lock or no usable prompt: the screens then hide
 * the "remember" option.
 */
class SavedPassword(
    private val store: SecretStore,
    private val lock: AppLock,
    private val activity: FragmentActivity?,
    private val profileId: String,
    private val kind: SecretKind,
    val usable: Boolean
) {
    suspend fun has(): Boolean = store.has(profileId, kind)

    /** Asks for fingerprint or PIN, then saves. Returns null when saved, or a message for the screen. */
    suspend fun save(secret: String): String? {
        when (val r = prompt("Save the password", "Unlock to keep it on this phone, encrypted")) {
            is UnlockResult.Success -> Unit
            is UnlockResult.Cancelled -> return "Password not saved."
            is UnlockResult.Failed -> return "Password not saved: ${r.message}"
        }
        return try {
            store.save(profileId, kind, secret)
            null
        } catch (e: SecretUnavailableException) {
            "Password not saved. ${e.message}"
        } catch (e: Exception) {
            "Password not saved (${e::class.simpleName})."
        }
    }

    /** Asks for fingerprint or PIN, then reads the saved password. */
    suspend fun load(): SavedPasswordResult {
        when (val r = prompt("Sign in with fingerprint", "Or use your phone's PIN")) {
            is UnlockResult.Success -> Unit
            is UnlockResult.Cancelled -> return SavedPasswordResult.Cancelled
            is UnlockResult.Failed -> return SavedPasswordResult.Message(r.message)
        }
        return try {
            store.load(profileId, kind)?.let { SavedPasswordResult.Value(it) }
                ?: SavedPasswordResult.Message("No saved password. Type it instead.")
        } catch (e: SecretUnavailableException.NeedsUnlock) {
            SavedPasswordResult.Message("The phone did not open saved passwords. Try again with a fingerprint or your PIN.")
        } catch (e: SecretUnavailableException) {
            store.delete(profileId, kind)
            SavedPasswordResult.Message(e.message ?: "Type the password again.")
        } catch (e: Exception) {
            SavedPasswordResult.Message("Could not read the saved password (${e::class.simpleName}). Type it instead.")
        }
    }

    suspend fun forget() = store.delete(profileId, kind)

    private suspend fun prompt(title: String, subtitle: String): UnlockResult {
        val a = activity ?: return UnlockResult.Failed("Fingerprint is not available here.")
        lock.authenticationStarted()
        return try {
            BiometricGate.unlock(a, title, subtitle)
        } finally {
            lock.authenticationEnded()
        }
    }
}

/** The owner's app-lock choices, kept in DataStore. */
class LockSettingsStore(private val dataStore: DataStore<Preferences>) {
    suspend fun load(): Pair<Boolean, LockTimeout> {
        val p = dataStore.data.first()
        return (p[ENABLED] ?: false) to LockTimeout.fromName(p[TIMEOUT])
    }

    suspend fun save(enabled: Boolean, timeout: LockTimeout) {
        dataStore.edit {
            it[ENABLED] = enabled
            it[TIMEOUT] = timeout.name
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("app_lock_enabled")
        val TIMEOUT = stringPreferencesKey("app_lock_timeout")
    }
}
