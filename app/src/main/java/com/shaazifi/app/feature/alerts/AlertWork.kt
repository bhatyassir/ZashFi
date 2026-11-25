package com.shaazifi.app.feature.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shaazifi.app.MainActivity
import com.shaazifi.app.R
import com.shaazifi.app.ShaaziFiApp
import com.shaazifi.app.core.session.SecretKind
import com.shaazifi.app.feature.devices.loadDevices
import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.feature.home.UbusClientCaller
import com.shaazifi.app.feature.router.RouterReader
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Which router has alerts on (one at a time), and what the last check saw. */
class AlertSettings(private val dataStore: DataStore<Preferences>) {
    private val profileKey = stringPreferencesKey("alerts_profile_id")
    private val memoryKey = stringPreferencesKey("alerts_memory")

    suspend fun profileId(): String? = dataStore.data.first()[profileKey]
    suspend fun memory(): AlertMemory? = AlertMemory.decode(dataStore.data.first()[memoryKey])
    suspend fun saveMemory(m: AlertMemory) { dataStore.edit { it[memoryKey] = m.encode() } }

    suspend fun enable(profileId: String) {
        dataStore.edit {
            it[profileKey] = profileId
            it.remove(memoryKey) // the first check learns again
        }
    }

    suspend fun disable() {
        dataStore.edit {
            it.remove(profileKey)
            it.remove(memoryKey)
        }
    }
}

/**
 * Schedules the background check with WorkManager (androidx.work 2.12.0). Every 15 minutes, which
 * is the shortest interval WorkManager allows (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS).
 */
object AlertScheduler {
    private const val WORK_NAME = "shaazifi-alerts"

    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

/**
 * One background check (M10c). Runs only while the phone is on Wi-Fi, and only against the
 * router's home address (never the VPN address), so it polls only on the router's own network.
 * Signs in with the background copy of the password, reads the internet status and the device
 * list (read-only allowlist), notifies on changes, then signs out. Any failure just skips this round.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ShaaziFiApp).container
        val profileId = container.alertSettings.profileId() ?: return Result.success()
        val profile = container.profileRepository.getProfileById(profileId)
        if (profile == null) {
            container.alertSettings.disable()
            AlertScheduler.stop(applicationContext)
            return Result.success()
        }
        if (!onWifi(applicationContext)) return Result.success()
        val password = runCatching { container.backgroundSecrets.load(profileId, SecretKind.ROUTER_BACKGROUND) }.getOrNull()
            ?: return Result.success()

        container.trustStore.pin(profile.address, profile.certificateFingerprint)
        val session = runCatching { container.ubusClient.login(profile.address, "root", password) }.getOrNull()
            ?: return Result.success()
        try {
            val reader = RouterReader(UbusClientCaller(container.ubusClient, profile.address, session.token))
            val previous = container.alertSettings.memory()
            val wan = runCatching { reader.optional("network.interface", "dump") }.getOrNull()?.let { HomeParsers.parseWan(it).up }
            val list = runCatching { loadDevices(reader) }.getOrNull() ?: return Result.success()
            val devices = list.devices.associate { it.mac to it.name }
            val snapshot = AlertSnapshot(internetUp = wan ?: previous?.internetUp ?: true, devices = devices)
            val (alerts, memory) = AlertRules.evaluate(previous, snapshot)
            container.alertSettings.saveMemory(memory)
            alerts.forEach { AlertNotifier.show(applicationContext, AlertRules.text(it, profile.name)) }
            runCatching {
                container.widgetStore.update(applicationContext, profile.id, profile.name, internetUp = wan, devicesOnline = list.online)
            }
        } finally {
            runCatching { container.ubusClient.destroySession(profile.address, session.token) }
        }
        return Result.success()
    }

    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

/** Posts alert notifications. Nothing is shown without the notification permission. */
object AlertNotifier {
    const val CHANNEL_ID = "alerts"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Router alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New devices and internet outages on your router"
            }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, text: Pair<String, String>) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setContentTitle(text.first)
            .setContentText(text.second)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((text.first + text.second).hashCode(), n)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }
}
