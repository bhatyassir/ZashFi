package com.shaazifi.app.feature.routers

import com.shaazifi.app.core.session.ProbeDiagnostic
import com.shaazifi.app.core.session.ProbeException
import com.shaazifi.app.core.session.SessionManager
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
            val fp = result.getOrNull() ?: ""
            val isHttp = fp == "HTTP_NO_PIN" || currentAddress.startsWith("http://")
            _state.value = _state.value.copy(
                isProbing = false,
                discoveredFingerprint = fp,
                isHttp = isHttp,
                showApprovalDialog = true,
                errorMessage = null,
                diagnostic = null
            )
        } else {
            val error = result.exceptionOrNull()
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
}
