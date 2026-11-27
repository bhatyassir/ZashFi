package com.shaazifi.app.feature.adguard

import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.core.adguard.AdGuardProbeDiagnostic
import com.shaazifi.app.data.AdGuardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AdGuardUiState {
    object Loading : AdGuardUiState()
    data class Loaded(val dashboard: AdGuardDashboard) : AdGuardUiState()
    data class FeatureNotInstalled(
        val message: String = "AdGuard Home is not installed on this router or not running on this port.",
        val diagnostic: AdGuardProbeDiagnostic? = null
    ) : AdGuardUiState()
    data class WrongPassword(val message: String = "AdGuard username or password incorrect.") : AdGuardUiState()
    data class RateLimited(
        val retryAfterSeconds: Int? = null,
        val message: String = if (retryAfterSeconds != null) {
            val mins = (retryAfterSeconds + 59) / 60
            if (mins > 1) {
                "Too many failed login attempts. Please wait $mins minutes."
            } else {
                "Too many failed login attempts. Please wait $retryAfterSeconds seconds."
            }
        } else {
            "Too many failed login attempts. Please wait a while."
        }
    ) : AdGuardUiState()
    data class RouterUnreachable(val message: String = "Could not reach AdGuard Home.") : AdGuardUiState()
    data class DemoData(val dashboard: AdGuardDashboard) : AdGuardUiState()
}

sealed class PendingConfirmation {
    data class ProtectionToggle(
        val enabled: Boolean,
        val durationMs: Long? = null,
        val title: String,
        val message: String
    ) : PendingConfirmation()

    data class SafeBrowsingToggle(
        val enabled: Boolean,
        val title: String,
        val message: String
    ) : PendingConfirmation()

    data class ParentalToggle(
        val enabled: Boolean,
        val title: String,
        val message: String
    ) : PendingConfirmation()
}

class AdGuardStateHolder(
    val repository: AdGuardRepository,
    private val isDemo: Boolean = false
) {
    companion object {
        const val REFRESH_INTERVAL_MS = 5_000L
    }

    private val _state = MutableStateFlow<AdGuardUiState>(AdGuardUiState.Loading)
    val state: StateFlow<AdGuardUiState> = _state.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private var inMemoryPassword: String? = null

    fun setPassword(password: String) {
        inMemoryPassword = password
    }

    fun clearPassword() {
        inMemoryPassword = null
    }

    fun hasPassword(): Boolean = inMemoryPassword != null

    fun getPassword(): String? = inMemoryPassword

    private fun needsUserAction(): Boolean = when (_state.value) {
        is AdGuardUiState.WrongPassword,
        is AdGuardUiState.RateLimited,
        is AdGuardUiState.FeatureNotInstalled -> true
        else -> false
    }

    suspend fun pollWhileActive() {
        refresh()
        if (needsUserAction()) return
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            refresh()
            if (needsUserAction()) return
        }
    }

    suspend fun refresh() {
        if (isDemo) {
            val dashboard = repository.loadDashboard()
            _state.value = AdGuardUiState.DemoData(dashboard)
            return
        }

        try {
            val probeResult = repository.probeDetailed()
            if (!probeResult.isAdGuard) {
                _state.value = AdGuardUiState.FeatureNotInstalled(
                    diagnostic = probeResult.diagnostic
                )
                return
            }

            val dashboard = repository.loadDashboard()
            _state.value = AdGuardUiState.Loaded(dashboard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = mapErrorToState(e)
        }
    }

    private fun mapErrorToState(e: Exception): AdGuardUiState = when (e) {
        is AdGuardError.Unauthorized ->
            AdGuardUiState.WrongPassword(e.message ?: "AdGuard username or password incorrect.")
        is AdGuardError.RateLimited ->
            AdGuardUiState.RateLimited(
                retryAfterSeconds = e.retryAfterSeconds,
                message = e.message ?: "Too many failed login attempts. Please wait a while."
            )
        is AdGuardError.NotAdGuardHome ->
            AdGuardUiState.FeatureNotInstalled(message = e.message ?: "AdGuard Home not installed on this port.")
        else ->
            AdGuardUiState.RouterUnreachable(e.message ?: "Could not reach AdGuard Home.")
    }

    fun requestProtectionToggle(enabled: Boolean, durationMs: Long? = null) {
        val durationLabel = when {
            durationMs == null -> null
            durationMs < 60_000L -> "< 1 minute"
            else -> "${durationMs / 60000L} minutes"
        }
        val title = when {
            enabled -> "Enable Protection?"
            durationMs != null && durationMs < 60_000L -> "Pause Protection for < 1 Minute?"
            durationMs != null -> "Pause Protection for ${durationMs / 60000L} Minutes?"
            else -> "Disable Protection?"
        }
        val message = when {
            enabled -> "AdGuard Home will resume blocking ads, trackers, and malicious domains for all devices."
            durationLabel != null -> "AdGuard Home will stop blocking for $durationLabel, then automatically re-enable."
            else -> "Ads and malicious websites will not be blocked until you turn protection back on."
        }
        _pendingConfirmation.value = PendingConfirmation.ProtectionToggle(enabled, durationMs, title, message)
    }

    fun requestSafeBrowsingToggle(enabled: Boolean) {
        val title = if (enabled) "Enable Safe Browsing?" else "Disable Safe Browsing?"
        val message = if (enabled) {
            "Checks requested domains against AdGuard's malware and phishing blacklist."
        } else {
            "Turns off malware and phishing site checking."
        }
        _pendingConfirmation.value = PendingConfirmation.SafeBrowsingToggle(enabled, title, message)
    }

    fun requestParentalToggle(enabled: Boolean) {
        val title = if (enabled) "Enable Parental Filter?" else "Disable Parental Filter?"
        val message = if (enabled) {
            "Blocks adult websites for all devices on this network."
        } else {
            "Turns off adult website filtering."
        }
        _pendingConfirmation.value = PendingConfirmation.ParentalToggle(enabled, title, message)
    }

    suspend fun confirmPendingAction() {
        val action = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null

        try {
            when (action) {
                is PendingConfirmation.ProtectionToggle -> {
                    repository.setProtection(action.enabled, action.durationMs)
                    // Read back real server state: NO optimistic UI
                    refresh()
                }
                is PendingConfirmation.SafeBrowsingToggle -> {
                    repository.setSafeBrowsing(action.enabled)
                    refresh()
                }
                is PendingConfirmation.ParentalToggle -> {
                    repository.setParental(action.enabled)
                    refresh()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = mapErrorToState(e)
        }
    }

    fun dismissPendingAction() {
        _pendingConfirmation.value = null
    }
}
