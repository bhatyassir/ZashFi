package com.shaazifi.app.feature.settings

import com.shaazifi.app.core.net.AddressGuard
import com.shaazifi.app.core.net.AddressGuardDns
import com.shaazifi.app.core.net.SystemAddressGuardDns
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.data.ProfileRepository
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.data.normalizeAdGuardHost
import com.shaazifi.app.data.normalizeRouterAddress
import com.shaazifi.app.feature.routers.RouterDisplay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class VpnAddressResult {
    data class Checked(val address: String) : VpnAddressResult()
    data class Error(val message: String) : VpnAddressResult()
}

sealed class AdGuardAddressSaveResult {
    data class Saved(val profile: RouterProfile) : AdGuardAddressSaveResult()
    data class Error(val message: String) : AdGuardAddressSaveResult()
}

class RouterSettingsHandler(
    private val profileRepository: ProfileRepository,
    private val trustStore: TrustStore,
    private val clearWebStorage: () -> Unit,
    /** Deletes this router's saved passwords (M9). */
    private val forgetSecrets: suspend (profileId: String) -> Unit = {},
    /** Signs out / destroys remote session before TrustStore pins are cleared. */
    private val onSignOut: suspend () -> Unit = {}
) {
    suspend fun forgetRouter(profileId: String, routerAddress: String): Boolean {
        // 0. Sign out / destroy active session before TrustStore pins are removed.
        runCatching { onSignOut() }

        val vpnAddress = runCatching { profileRepository.getProfileById(profileId)?.vpnAddress }.getOrNull()

        // 1. Delete profile from repository
        profileRepository.deleteProfile(profileId)

        // 2. Remove certificate pins from TrustStore (home and VPN address)
        trustStore.clearPin(routerAddress)
        vpnAddress?.let { trustStore.clearPin(it) }

        // 3. Clear all WebView data (cookies, cache, Web SQL, IndexedDB, localStorage)
        clearWebStorage()

        // 4. Delete saved router and AdGuard passwords
        forgetSecrets(profileId)

        return true
    }

    /**
     * M9: checks a typed VPN address before its certificate is shown to the owner. Returns the
     * tidied address, or an error message. Only private or Tailscale addresses are accepted.
     */
    suspend fun checkVpnAddress(
        profile: RouterProfile,
        raw: String,
        dns: AddressGuardDns = SystemAddressGuardDns
    ): VpnAddressResult = withContext(Dispatchers.IO) {
        val address = normalizeRouterAddress(raw)
            ?: return@withContext VpnAddressResult.Error("Enter the router's VPN address, for example 100.64.0.7.")
        val host = address.toHttpUrlOrNull()?.host
            ?: return@withContext VpnAddressResult.Error("That address is not valid.")
        if (address.trimEnd('/') == profile.address.trimEnd('/')) {
            return@withContext VpnAddressResult.Error("That is already the home address.")
        }
        if (!AddressGuard.isAllowed(host, dns)) {
            return@withContext VpnAddressResult.Error("$host is not a private or Tailscale address. The app only talks to your own network.")
        }
        VpnAddressResult.Checked(address)
    }

    /** Saves the VPN address with the certificate the owner approved, and pins it. */
    suspend fun saveVpnAddress(profileId: String, address: String, fingerprint: String): RouterProfile? {
        val existing = profileRepository.getProfileById(profileId) ?: return null
        existing.vpnAddress?.let { trustStore.clearPin(it) }
        val updated = existing.copy(vpnAddress = address, vpnCertificateFingerprint = fingerprint)
        profileRepository.saveProfile(updated)
        if (fingerprint != RouterDisplay.HTTP_NO_PIN && fingerprint.isNotBlank()) {
            trustStore.pin(address, fingerprint)
        }
        return updated
    }

    /** Removes the VPN address and its approved certificate. */
    suspend fun removeVpnAddress(profileId: String): RouterProfile? {
        val existing = profileRepository.getProfileById(profileId) ?: return null
        existing.vpnAddress?.let { trustStore.clearPin(it) }
        val updated = existing.copy(vpnAddress = null, vpnCertificateFingerprint = null)
        profileRepository.saveProfile(updated)
        return updated
    }

    suspend fun updateAdGuardConfig(
        profileId: String,
        enabled: Boolean,
        host: String?,
        port: Int?,
        protocol: String?,
        username: String
    ): RouterProfile? {
        val existing = profileRepository.getProfileById(profileId) ?: return null
        val cleanHost = host?.trim()?.ifBlank { null }
        val cleanProto = protocol?.trim()?.lowercase()?.ifBlank { null }
        val cleanUser = username.trim().ifBlank { "admin" }
        val updated = existing.copy(
            adguardEnabled = enabled,
            adguardHost = cleanHost,
            adguardPort = port,
            adguardProtocol = cleanProto,
            adguardUsername = cleanUser
        )
        profileRepository.saveProfile(updated)
        return updated
    }

    /**
     * Saves an AdGuard Home address typed by the user (host, port, protocol), after checking it.
     * Keeps the profile's other AdGuard fields (enabled, and username unless [username] is given).
     * When [allowBlank] is true, empty host/port clear those fields to null (router address / 3000);
     * non-blank values are always validated against [AddressGuard] and port range 1..65535.
     */
    suspend fun saveAdGuardAddress(
        profileId: String,
        host: String,
        portText: String,
        protocol: String,
        dns: AddressGuardDns = SystemAddressGuardDns,
        allowBlank: Boolean = false,
        username: String? = null
    ): AdGuardAddressSaveResult = withContext(Dispatchers.IO) {
        val trimmedHost = host.trim()
        val cleanHost = if (allowBlank && trimmedHost.isEmpty()) {
            null
        } else {
            normalizeAdGuardHost(trimmedHost)
                ?: return@withContext AdGuardAddressSaveResult.Error(
                    "Enter the AdGuard Home address, for example 192.168.1.1."
                )
        }
        val trimmedPort = portText.trim()
        val port = if (allowBlank && trimmedPort.isEmpty()) {
            null
        } else {
            trimmedPort.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return@withContext AdGuardAddressSaveResult.Error("Port must be a number from 1 to 65535.")
        }
        val proto = protocol.trim().lowercase()
        if (proto != "http" && proto != "https") {
            return@withContext AdGuardAddressSaveResult.Error("Protocol must be http or https.")
        }
        if (cleanHost != null && !AddressGuard.isAllowed(cleanHost, dns)) {
            return@withContext AdGuardAddressSaveResult.Error(
                "$cleanHost is not a private or Tailscale address. The app only talks to your own network."
            )
        }
        val existing = try {
            profileRepository.getProfileById(profileId)
        } catch (e: Exception) {
            return@withContext AdGuardAddressSaveResult.Error("Could not read saved routers: ${e::class.simpleName}.")
        } ?: return@withContext AdGuardAddressSaveResult.Error(
            "Could not save: this router is not in the saved list."
        )
        val cleanUsername = username?.trim()?.ifBlank { "admin" } ?: existing.adguardUsername
        val updated = existing.copy(
            adguardHost = cleanHost,
            adguardPort = port,
            adguardProtocol = proto,
            adguardUsername = cleanUsername
        )
        try {
            profileRepository.saveProfile(updated)
        } catch (e: Exception) {
            return@withContext AdGuardAddressSaveResult.Error("Could not save: ${e::class.simpleName}.")
        }
        AdGuardAddressSaveResult.Saved(updated)
    }
}
