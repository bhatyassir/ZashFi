package com.shaazifi.app.feature.localnet

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Android 17 local network permission (M9 item 5).
 * Source: developer.android.com/privacy-and-security/local-network-permission and
 * Manifest.permission#ACCESS_LOCAL_NETWORK (read 2026-11-17):
 * - Added in API 37, "dangerous" (asked at run time), part of the Nearby devices group.
 * - Needed only when the app targets SDK 37 or higher. At 36 or lower, access comes with INTERNET,
 *   and the app must not declare or request it. ShaaziFi targets 36 today, so all of this is off.
 * - A "local network" excludes mobile data and VPN connections, so the Tailscale address does not
 *   need it.
 * When targetSdk moves to 37: declare the permission in the manifest (LocalNetworkAccessTest fails
 * until then), and the explainer below switches itself on.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val FIRST_SDK = 37

    enum class Decision { NotNeeded, Granted, Explain, OpenSettings }

    fun applies(targetSdk: Int, deviceSdk: Int): Boolean = targetSdk >= FIRST_SDK && deviceSdk >= FIRST_SDK

    /**
     * [showRationale] is Activity.shouldShowRequestPermissionRationale: false before the first ask and
     * also after "Don't ask again", so [askedBefore] tells the two apart.
     */
    fun decide(applies: Boolean, granted: Boolean, askedBefore: Boolean, showRationale: Boolean): Decision = when {
        !applies -> Decision.NotNeeded
        granted -> Decision.Granted
        !askedBefore || showRationale -> Decision.Explain
        else -> Decision.OpenSettings
    }

    fun applies(context: Context): Boolean = applies(context.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT)

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}

/** Remembers whether the app has asked once (needed to tell "not asked yet" from "refused for good"). */
class LocalNetworkPrefs(private val dataStore: DataStore<Preferences>) {
    private val asked = booleanPreferencesKey("local_network_asked")
    suspend fun askedBefore(): Boolean = dataStore.data.first()[asked] ?: false
    suspend fun markAsked() { dataStore.edit { it[asked] = true } }
}
