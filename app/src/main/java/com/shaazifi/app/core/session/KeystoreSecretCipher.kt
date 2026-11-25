package com.shaazifi.app.core.session

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key kept in the Android Keystore (it cannot be copied off the phone).
 * The key only works for [VALIDITY_SECONDS] after the owner unlocks with fingerprint or PIN.
 * Source: developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder
 * (setUserAuthenticationParameters from API 30; setUserAuthenticationValidityDurationSeconds below it).
 * Such a key needs a secure screen lock and is invalidated when the screen lock is removed:
 * that surfaces as [SecretUnavailableException.KeyGone] and the key is recreated on the next save.
 * setUnlockedDeviceRequired is not used (the same page warns about it before Android 15).
 */
class KeystoreSecretCipher(
    private val alias: String = ALIAS,
    /** False only for the alerts' background copy (M10c, owner's choice): usable without fingerprint. */
    private val requireUserAuth: Boolean = true
) : SecretCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        val iv = cipher.iv // 12 bytes, chosen by the Keystore for every encryption
        val ct = cipher.doFinal(plain)
        byteArrayOf(iv.size.toByte()) + iv + ct
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = guarded {
        val ivLen = blob.firstOrNull()?.toInt() ?: throw SecretUnavailableException.Tampered()
        if (ivLen != 12 || blob.size < 1 + ivLen + 16) throw SecretUnavailableException.Tampered()
        val k = key(create = false) ?: throw SecretUnavailableException.KeyGone()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 1, ivLen))
        cipher.updateAAD(aad)
        try {
            cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
        } catch (_: javax.crypto.AEADBadTagException) {
            throw SecretUnavailableException.Tampered()
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecretUnavailableException) {
        throw e
    } catch (_: UserNotAuthenticatedException) {
        throw SecretUnavailableException.NeedsUnlock()
    } catch (_: KeyPermanentlyInvalidatedException) {
        deleteKey()
        throw SecretUnavailableException.KeyGone()
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(requireUserAuth)
            .apply {
                if (!requireUserAuth) {
                    // No unlock needed: background checks must be able to read it.
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(VALIDITY_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "shaazifi_secrets_v1"
        const val BACKGROUND_ALIAS = "shaazifi_background_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VALIDITY_SECONDS = 30
    }
}
