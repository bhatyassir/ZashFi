package com.shaazifi.app.data

import com.shaazifi.app.core.adguard.AdGuardClient
import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.core.adguard.AdGuardProbeResult
import com.shaazifi.app.feature.adguard.AdGuardDashboard
import com.shaazifi.app.feature.adguard.AdGuardLogDevice
import com.shaazifi.app.feature.adguard.AdGuardQueryLog
import com.shaazifi.app.feature.adguard.AdGuardQueryLogSettings

/**
 * Interface for AdGuard repository (v2).
 */
interface AdGuardRepository {
    suspend fun probe(): Boolean
    /**
     * Default for repositories with no network detail (demo data, test fakes):
     * one probe call and no diagnostic, so the UI never shows invented values.
     */
    suspend fun probeDetailed(): AdGuardProbeResult = AdGuardProbeResult(
        isAdGuard = probe(),
        diagnostic = null
    )
    suspend fun loadDashboard(): AdGuardDashboard
    suspend fun loadQueryLog(limit: Int = 50, offset: Int = 0, search: String? = null): AdGuardQueryLog

    /** Query log filtered by AdGuard Home's `response_status` (all, filtered, blocked, ...). */
    suspend fun loadQueryLogFiltered(
        limit: Int, search: String? = null, responseStatus: String? = null, olderThan: String? = null
    ): AdGuardQueryLog = loadQueryLog(limit, search = search)
    suspend fun loadQueryLogSettings(): AdGuardQueryLogSettings = AdGuardQueryLogSettings()
    suspend fun loadQueryLogClients(): List<AdGuardLogDevice> = emptyList()
    suspend fun setProtection(enabled: Boolean, durationMs: Long? = null)
    suspend fun setSafeBrowsing(enabled: Boolean)
}

/**
 * Live HTTP/HTTPS implementation of AdGuardRepository.
 * Enforces unauthenticated discovery check before sending Basic Auth credentials.
 * Credentials are provided dynamically (held in memory only) and never persisted in M8.
 */
class HttpAdGuardRepository(
    private val adGuardClient: AdGuardClient,
    val baseUrl: String,
    val username: String,
    private val passwordProvider: () -> String?
) : AdGuardRepository {

    override suspend fun probe(): Boolean {
        return adGuardClient.probeEndpoint(baseUrl)
    }

    override suspend fun probeDetailed(): AdGuardProbeResult {
        return adGuardClient.probeEndpointDetailed(baseUrl)
    }

    override suspend fun loadDashboard(): AdGuardDashboard {
        val password = passwordProvider() ?: throw AdGuardError.Unauthorized("Password required")
        return adGuardClient.loadDashboard(baseUrl, username, password)
    }

    override suspend fun loadQueryLog(limit: Int, offset: Int, search: String?): AdGuardQueryLog {
        val password = passwordProvider() ?: throw AdGuardError.Unauthorized("Password required")
        return adGuardClient.loadQueryLog(baseUrl, username, password, limit, offset, search)
    }

    override suspend fun loadQueryLogFiltered(
        limit: Int, search: String?, responseStatus: String?, olderThan: String?
    ): AdGuardQueryLog = adGuardClient.loadQueryLog(
        baseUrl, username, requirePassword(), limit, search = search, olderThan = olderThan, responseStatus = responseStatus
    )

    override suspend fun loadQueryLogSettings(): AdGuardQueryLogSettings =
        adGuardClient.loadQueryLogSettings(baseUrl, username, requirePassword())

    override suspend fun loadQueryLogClients(): List<AdGuardLogDevice> =
        adGuardClient.loadQueryLogClients(baseUrl, username, requirePassword())

    private fun requirePassword(): String {
        return passwordProvider() ?: throw AdGuardError.Unauthorized("Password required")
    }

    override suspend fun setProtection(enabled: Boolean, durationMs: Long?) {
        val password = passwordProvider() ?: throw AdGuardError.Unauthorized("Password required")
        adGuardClient.setProtection(baseUrl, username, password, enabled, durationMs)
    }

    override suspend fun setSafeBrowsing(enabled: Boolean) {
        val password = passwordProvider() ?: throw AdGuardError.Unauthorized("Password required")
        adGuardClient.setSafeBrowsing(baseUrl, username, password, enabled)
    }
}
