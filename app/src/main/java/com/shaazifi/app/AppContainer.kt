package com.shaazifi.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.shaazifi.app.core.adguard.AdGuardClient
import com.shaazifi.app.core.net.DataStorePinStore
import com.shaazifi.app.core.net.HttpFactory
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.net.WifiBinder
import android.os.SystemClock
import com.shaazifi.app.core.session.AppLock
import com.shaazifi.app.core.session.DataStoreSecretBox
import com.shaazifi.app.core.session.GatewayDiscovery
import com.shaazifi.app.core.session.KeystoreSecretCipher
import com.shaazifi.app.core.session.LockSettingsStore
import com.shaazifi.app.core.session.SecretStore
import com.shaazifi.app.core.session.SessionManager
import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.data.DataStoreProfileRepository
import com.shaazifi.app.data.ProfileRepository
import com.shaazifi.app.debug.DebugRouterConfig
import com.shaazifi.app.feature.settings.RouterSettingsHandler
import com.shaazifi.app.feature.web.WebStorageManager
import okhttp3.OkHttpClient

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shaazifi_preferences")

/**
 * Hand-written dependency container for ShaaziFi.
 * No DI framework (Hilt, Dagger, Koin) is used, per architectural decision D-20261116-DCF009C3.
 */
class AppContainer(private val context: Context) {

    val trustStore: TrustStore by lazy {
        TrustStore(DataStorePinStore(dataStore)).apply {
            DebugRouterConfig.seedDebugPins(this)
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        HttpFactory.createClient(
            trustStore = trustStore,
            socketFactory = WifiBinder.socketFactory(context)
        )
    }

    val dataStore: DataStore<Preferences> by lazy {
        context.dataStore
    }

    val profileRepository: ProfileRepository by lazy {
        DataStoreProfileRepository(dataStore)
    }

    val ubusClient: UbusClient by lazy {
        UbusClient(okHttpClient)
    }

    val adGuardClient: AdGuardClient by lazy {
        AdGuardClient(okHttpClient)
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(
            ubusClient, trustStore, okHttpClient,
            loginTimeoutSeconds = com.shaazifi.app.core.session.SavedSession.TIMEOUT_SECONDS,
            onSignedIn = { s ->
                savedSessions.save(com.shaazifi.app.core.session.SavedSession(s.profile.id, s.token, s.endpoint, s.viaVpn, System.currentTimeMillis()))
            },
            onSignedOut = { savedSessions.clear() }
        )
    }

    /** Owner feedback: the signed-in session, kept a week so the app does not ask again each time. */
    val savedSessions: com.shaazifi.app.core.session.SavedSessionStore by lazy {
        com.shaazifi.app.core.session.SavedSessionStore(dataStore, backgroundSecrets)
    }

    val gatewayDiscovery: GatewayDiscovery by lazy {
        GatewayDiscovery(context)
    }


    val routerSettingsHandler: RouterSettingsHandler by lazy {
        RouterSettingsHandler(
            profileRepository = profileRepository,
            trustStore = trustStore,
            clearWebStorage = { WebStorageManager.clearAll() },
            forgetSecrets = { profileId ->
                secretStore.forget(profileId)
                backgroundSecrets.forget(profileId)
                val saved = savedSessions.last()
                if (saved?.profileId == profileId) {
                    savedSessions.clear(saved)
                }
                if (alertSettings.profileId() == profileId) {
                    runCatching { com.shaazifi.app.feature.alerts.AlertScheduler.stop(context) }
                    alertSettings.disable()
                }
                widgetStore.clearIf(context, profileId)
                deviceCache.forget(profileId)
            }
        )
    }

    /** Saved router and AdGuard passwords (encrypted, fingerprint or PIN to open). M9. */
    val secretStore: SecretStore by lazy {
        SecretStore(DataStoreSecretBox(dataStore), KeystoreSecretCipher())
    }

    /** App lock (off until the owner turns it on). M9. */
    val appLock: AppLock by lazy { AppLock(now = { SystemClock.elapsedRealtime() }) }

    val lockSettings: LockSettingsStore by lazy { LockSettingsStore(dataStore) }

    /**
     * M10c: the background copy of the router password for alerts (owner's choice). Same storage as
     * [secretStore], but a separate Keystore key that opens without fingerprint.
     */
    val backgroundSecrets: SecretStore by lazy {
        SecretStore(DataStoreSecretBox(dataStore), KeystoreSecretCipher(KeystoreSecretCipher.BACKGROUND_ALIAS, requireUserAuth = false))
    }

    /** Phase 5: activity log, last device lists, theme choice (on this phone only). */
    val activityStore: com.shaazifi.app.feature.activity.ActivityStore by lazy { com.shaazifi.app.feature.activity.ActivityStore(dataStore) }
    val deviceCache: com.shaazifi.app.feature.activity.DeviceCacheStore by lazy { com.shaazifi.app.feature.activity.DeviceCacheStore(dataStore) }
    val themeStore: com.shaazifi.app.feature.activity.ThemeStore by lazy { com.shaazifi.app.feature.activity.ThemeStore(dataStore) }

    /** M10e: what the home-screen widget shows (no secrets). */
    val widgetStore: com.shaazifi.app.feature.widget.WidgetStore by lazy { com.shaazifi.app.feature.widget.WidgetStore(dataStore) }

    /** Owner feedback 2026-11-18: how often each feature is opened, for "Frequently used". */
    val featureUsage: com.shaazifi.app.feature.router.FeatureUsageStore by lazy { com.shaazifi.app.feature.router.FeatureUsageStore(dataStore) }

    val alertSettings: com.shaazifi.app.feature.alerts.AlertSettings by lazy { com.shaazifi.app.feature.alerts.AlertSettings(dataStore) }
}
