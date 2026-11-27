package com.shaazifi.app.feature.routers

import com.shaazifi.app.core.session.ProbeDiagnostic
import com.shaazifi.app.core.session.ProbeException
import com.shaazifi.app.core.session.SessionManager
import com.shaazifi.app.data.normalizeRouterAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AddRouterUiState(
    val name: String = "",
    val address: String = "",
    val isProbing: Boolean = false,
    val errorMessage: String? = null,
    val diagnostic: ProbeDiagnostic? = null,
    val discoveredFingerprint: String? = null,
    val isHttp: Boolean = false,
    val showApprovalDialog: Boolean = false
)

/**
 * State holder for the Add Router flow.
 * Manages input text, probe execution, error state, and certificate approval.
 */
class AddRouterStateHolder(
    private val sessionManager: SessionManager
) {
    private val _state = MutableStateFlow(AddRouterUiState())
    val state: StateFlow<AddRouterUiState> = _state.asStateFlow()

    fun onNameChanged(newName: String) {
        _state.value = _state.value.copy(
            name = newName,
            errorMessage = null,
            diagnostic = null
        )
    }

    fun onAddressChanged(newAddress: String) {
        _state.value = _state.value.copy(
            address = newAddress,
            errorMessage = null,
            diagnostic = null
        )
    }

    fun openDialog(initialAddress: String = "", initialName: String = "My Router") {
        _state.value = AddRouterUiState(
            name = initialName,
            address = initialAddress
        )
    }

    fun dismissDialog() {
        if (!_state.value.isProbing) {
            _state.value = AddRouterUiState()
        }
    }

    fun dismissApproval() {
        _state.value = _state.value.copy(showApprovalDialog = false)
    }

    suspend fun probe() {
        val currentAddress = _state.value.address.trim()
        if (currentAddress.isBlank()) return

        _state.value = _state.value.copy(
            isProbing = true,
            errorMessage = null,
            diagnostic = null
        )

        val result = sessionManager.probeCertificate(currentAddress)
        if (result.isSuccess) {
            val normalized = normalizeRouterAddress(currentAddress) ?: currentAddress
            applyProbeSuccess(normalized, result.getOrNull() ?: "")
        } else {
            applyProbeFailure(result.exceptionOrNull())
        }
    }

    /** Probes candidate endpoints in order (HTTPS then HTTP) and opens approval on the first reachable one. */
    suspend fun probeCandidates(candidates: List<String>) {
        val ordered = candidates
            .mapNotNull { normalizeRouterAddress(it) }
            .distinct()
            .ifEmpty { listOf("https://192.168.1.1", "http://192.168.1.1") }

        _state.value = _state.value.copy(
            isProbing = true,
            errorMessage = null,
            diagnostic = null
        )

        var lastError: Throwable? = null
        for (candidate in ordered) {
            _state.value = _state.value.copy(address = candidate)
            val result = sessionManager.probeCertificate(candidate)
            if (result.isSuccess) {
                applyProbeSuccess(candidate, result.getOrNull() ?: "")
                return
            }
            lastError = result.exceptionOrNull()
        }
        _state.value = _state.value.copy(address = ordered.first())
        applyProbeFailure(lastError)
    }

    private fun applyProbeSuccess(address: String, fingerprint: String) {
        val isHttp = fingerprint == RouterDisplay.HTTP_NO_PIN || address.startsWith("http://", ignoreCase = true)
        _state.value = _state.value.copy(
            address = address,
            isProbing = false,
            discoveredFingerprint = fingerprint,
            isHttp = isHttp,
            showApprovalDialog = true,
            errorMessage = null,
            diagnostic = null
        )
    }

    private fun applyProbeFailure(error: Throwable?) {
        val diag = (error as? ProbeException)?.diagnostic
            ?: ProbeDiagnostic(
                exceptionClass = error?.javaClass?.name ?: "UnknownException",
                socketConnected = false,
                detail = error?.message
            )
        _state.value = _state.value.copy(
            isProbing = false,
            errorMessage = error?.message ?: "Failed to reach router",
            diagnostic = diag
        )
    }
}
