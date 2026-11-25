package com.shaazifi.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * A saved router profile.
 * Every profile is strictly bound to both its address AND its SHA-256 certificate fingerprint.
 */
@Serializable
data class RouterProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val certificateFingerprint: String,
    val createdAt: Long = System.currentTimeMillis(),
    val adguardEnabled: Boolean = true,
    val adguardHost: String? = null,
    val adguardPort: Int? = null,
    val adguardProtocol: String? = null,
    val adguardUsername: String = "admin",
    /** M9: a second address for the same router over a VPN (for example Tailscale). Used only when the home address cannot be reached. */
    val vpnAddress: String? = null,
    /** Certificate approved by the owner for [vpnAddress]. Without it the VPN address is never used. */
    val vpnCertificateFingerprint: String? = null
)

/** One way to reach a router: an address and the certificate approved for it. */
data class RouterEndpoint(val address: String, val fingerprint: String, val viaVpn: Boolean)

/** Addresses to try, home first. The VPN address is used only once its certificate has been approved. */
fun RouterProfile.endpoints(): List<RouterEndpoint> = buildList {
    add(RouterEndpoint(address, certificateFingerprint, viaVpn = false))
    val vpn = vpnAddress?.trim()?.ifBlank { null }
    val fp = vpnCertificateFingerprint?.trim()?.ifBlank { null }
    if (vpn != null && fp != null && vpn != address) add(RouterEndpoint(vpn, fp, viaVpn = true))
}

/** Tidies a typed router address: adds https:// when no scheme is given and drops a trailing slash. Null when empty. */
fun normalizeRouterAddress(raw: String): String? {
    val t = raw.trim().ifBlank { return null }
    val withScheme = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    return withScheme.trimEnd('/')
}

/**
 * Reduces what a user typed in a host field to a bare host: drops scheme, path and port,
 * and removes IPv6 brackets. Returns null when nothing is left.
 */
fun normalizeAdGuardHost(raw: String): String? {
    val noScheme = raw.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .trim()
    val host = when {
        // Bracketed IPv6, optionally with a port: [fd00::1] or [fd00::1]:90
        noScheme.startsWith("[") -> noScheme.substringAfter("[").substringBefore("]")
        // Bare IPv6 literal (more than one colon): fd00::1
        noScheme.count { it == ':' } > 1 -> noScheme
        // IPv4 or name, optionally with a port: 192.168.1.1:90
        else -> noScheme.substringBefore(":")
    }.trim()
    return host.ifBlank { null }
}

/**
 * Returns the fully qualified base URL for AdGuard Home based on the profile configuration.
 * Normalizes host, port, and protocol to prevent malformed URLs.
 */
fun RouterProfile.getAdGuardBaseUrl(): String {
    val cleanProto = adguardProtocol?.trim()?.lowercase()?.removeSuffix(":")?.ifBlank { null } ?: "http"
    val cleanHost = adguardHost?.let { normalizeAdGuardHost(it) }
        ?: address.toHttpUrlOrNull()?.host
        ?: "192.168.1.1"
    val cleanPort = adguardPort ?: 3000
    val urlHost = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
    return "$cleanProto://$urlHost:$cleanPort"
}

object ProfileJsonAdapter {
    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(profile: RouterProfile): String {
        return json.encodeToString(profile)
    }

    fun fromJson(jsonStr: String): RouterProfile? {
        return try {
            json.decodeFromString<RouterProfile>(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    fun listToJson(profiles: List<RouterProfile>): String {
        return json.encodeToString(profiles)
    }

    fun listFromJson(jsonStr: String): List<RouterProfile> {
        return try {
            json.decodeFromString<List<RouterProfile>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Repository interface for managing saved router profiles.
 */
interface ProfileRepository {
    fun getProfiles(): Flow<List<RouterProfile>>
    suspend fun saveProfile(profile: RouterProfile)
    suspend fun deleteProfile(id: String)
    suspend fun getProfileById(id: String): RouterProfile?
}

/**
 * DataStore-backed implementation of ProfileRepository.
 */
class DataStoreProfileRepository(
    private val dataStore: DataStore<Preferences>
) : ProfileRepository {

    private val profilesKey = stringPreferencesKey("saved_router_profiles")

    override fun getProfiles(): Flow<List<RouterProfile>> {
        return dataStore.data.map { prefs ->
            val jsonStr = prefs[profilesKey] ?: "[]"
            ProfileJsonAdapter.listFromJson(jsonStr)
        }
    }

    override suspend fun saveProfile(profile: RouterProfile) {
        dataStore.edit { prefs ->
            val current = ProfileJsonAdapter.listFromJson(prefs[profilesKey] ?: "[]").toMutableList()
            val existingIndex = current.indexOfFirst { it.id == profile.id }
            if (existingIndex >= 0) {
                current[existingIndex] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesKey] = ProfileJsonAdapter.listToJson(current)
        }
    }

    override suspend fun deleteProfile(id: String) {
        dataStore.edit { prefs ->
            val current = ProfileJsonAdapter.listFromJson(prefs[profilesKey] ?: "[]").toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesKey] = ProfileJsonAdapter.listToJson(current)
        }
    }

    override suspend fun getProfileById(id: String): RouterProfile? {
        val list = getProfiles().first()
        return list.find { it.id == id }
    }
}

/**
 * In-memory implementation for tests and demo mode.
 */
class InMemoryProfileRepository(
    initial: List<RouterProfile> = emptyList()
) : ProfileRepository {

    private val _profiles = MutableStateFlow(initial)

    override fun getProfiles(): Flow<List<RouterProfile>> = _profiles.asStateFlow()

    override suspend fun saveProfile(profile: RouterProfile) {
        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            current.add(profile)
        }
        _profiles.value = current
    }

    override suspend fun deleteProfile(id: String) {
        val current = _profiles.value.toMutableList()
        current.removeAll { it.id == id }
        _profiles.value = current
    }

    override suspend fun getProfileById(id: String): RouterProfile? {
        return _profiles.value.find { it.id == id }
    }
}
