package com.shaazifi.app.core.session

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Result of asking for fingerprint or PIN. */
sealed class UnlockResult {
    data object Success : UnlockResult()
    data object Cancelled : UnlockResult()
    data class Failed(val message: String) : UnlockResult()
}

/** Shows the system fingerprint / face / PIN prompt (androidx.biometric 1.1.0). */
object BiometricGate {

    /** True when the phone has a PIN, pattern or password. Saved passwords and the lock need one. */
    fun deviceIsSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** True when the prompt can run with the methods [UnlockRules] allows on this Android version. */
    fun canUnlock(context: Context): Boolean =
        deviceIsSecure(context) &&
            BiometricManager.from(context).canAuthenticate(UnlockRules.allowedAuthenticators(Build.VERSION.SDK_INT)) ==
            BiometricManager.BIOMETRIC_SUCCESS

    suspend fun unlock(activity: FragmentActivity, title: String, subtitle: String? = null): UnlockResult =
        suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(UnlockResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    cont.resume(if (cancelled) UnlockResult.Cancelled else UnlockResult.Failed(errString.toString()))
                }
                // onAuthenticationFailed (a finger not recognised) keeps the prompt open; nothing to do.
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            // No negative button: it must not be set when DEVICE_CREDENTIAL is allowed (setAllowedAuthenticators docs).
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(UnlockRules.allowedAuthenticators(Build.VERSION.SDK_INT))
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
