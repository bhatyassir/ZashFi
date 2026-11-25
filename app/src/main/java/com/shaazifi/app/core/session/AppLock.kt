package com.shaazifi.app.core.session

import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long the app may stay in the background before it asks for fingerprint or PIN again. No "Never" (owner's plan approval). */
enum class LockTimeout(val millis: Long, val label: String) {
    IMMEDIATELY(0, "Immediately"),
    ONE_MINUTE(60_000, "After 1 minute"),
    FIVE_MINUTES(5 * 60_000, "After 5 minutes"),
    FIFTEEN_MINUTES(15 * 60_000, "After 15 minutes");

    companion object {
        val DEFAULT = ONE_MINUTE
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * App lock. Off until the owner turns it on. When on, the app locks at start, when the screen
 * turns off, and when it comes back after [LockTimeout]. Time is measured with [now]
 * (SystemClock.elapsedRealtime in the app), so changing the phone's clock does not unlock it.
 */
class AppLock(private val now: () -> Long) {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    var enabled = false
        private set
    var timeout = LockTimeout.DEFAULT
        private set

    private var stoppedAt: Long? = null
    private var authenticating = false

    fun configure(enabled: Boolean, timeout: LockTimeout) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        this.timeout = timeout
        if (!enabled) _locked.value = false else if (!wasEnabled) _locked.value = true
    }

    fun onAppStopped() {
        if (!authenticating) stoppedAt = now()
    }

    fun onAppStarted() {
        val since = stoppedAt ?: return
        stoppedAt = null
        if (enabled && now() - since >= timeout.millis) _locked.value = true
    }

    fun onScreenOff() {
        if (enabled) _locked.value = true
    }

    /** The PIN screen on older Android is a separate screen; leaving for it must not count as "away". */
    fun authenticationStarted() { authenticating = true }

    fun authenticationEnded() { authenticating = false }

    fun unlocked() {
        authenticating = false
        stoppedAt = null
        _locked.value = false
    }
}

/**
 * Which unlock methods the fingerprint/PIN prompt may offer. androidx.biometric
 * PromptInfo.Builder#setAllowedAuthenticators (developer.android.com, read 2026-11-17):
 * BIOMETRIC_STRONG | DEVICE_CREDENTIAL is unsupported on API 28-29, and DEVICE_CREDENTIAL alone
 * is unsupported before API 30. On 28-29 the prompt offers BIOMETRIC_WEAK | DEVICE_CREDENTIAL.
 */
object UnlockRules {
    const val BIOMETRIC_STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG
    const val BIOMETRIC_WEAK = BiometricManager.Authenticators.BIOMETRIC_WEAK
    const val DEVICE_CREDENTIAL = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun allowedAuthenticators(sdk: Int): Int =
        if (sdk in 28..29) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_STRONG or DEVICE_CREDENTIAL
}
