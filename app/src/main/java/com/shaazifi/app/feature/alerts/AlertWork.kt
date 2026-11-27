package com.shaazifi.app.feature.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
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
import com.shaazifi.app.core.net.WifiBinder
import com.shaazifi.app.core.session.SecretKind
import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.feature.devices.loadDevices
import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.feature.home.UbusClientCaller
import com.shaazifi.app.feature.router.RouterReader
import kotlinx.coroutines.flow.first
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Which router has alerts on (one at a time), and what the last check saw. */
class AlertSettings(private val dataStore: DataStore<Preferences>) {
    private val profileKey = stringPreferencesKey("alerts_profile_id")
    private val memoryKey = stringPreferencesKey("alerts_memory")
    private val highTempEnabledKey = booleanPreferencesKey("alerts_high_temp_enabled")
    private val tempThresholdKey = doublePreferencesKey("alerts_temp_threshold_c")

    suspend fun profileId(): String? = dataStore.data.first()[profileKey]
    suspend fun memory(): AlertMemory? = AlertMemory.decode(dataStore.data.first()[memoryKey])
    suspend fun saveMemory(m: AlertMemory) { dataStore.edit { it[memoryKey] = m.encode() } }
    suspend fun highTempEnabled(): Boolean = dataStore.data.first()[highTempEnabledKey] ?: false
    suspend fun setHighTempEnabled(enabled: Boolean) { dataStore.edit { it[highTempEnabledKey] = enabled } }
    suspend fun tempThresholdC(): Double =
        (dataStore.data.first()[tempThresholdKey] ?: AlertRules.DEFAULT_TEMP_THRESHOLD_C)
            .coerceIn(AlertRules.MIN_TEMP_THRESHOLD_C, AlertRules.MAX_TEMP_THRESHOLD_C)

    suspend fun setTempThresholdC(thresholdC: Double) {
        val clamped = thresholdC.coerceIn(AlertRules.MIN_TEMP_THRESHOLD_C, AlertRules.MAX_TEMP_THRESHOLD_C)
        dataStore.edit { it[tempThresholdKey] = clamped }
    }

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
        // Never send background credentials over plain http:// without a pinned HTTPS certificate.
        if (!AlertSecurity.canPollInBackground(profile)) return Result.success()
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return Result.success()
        if (findWifiNetwork(cm) == null) return Result.success()
        val password = runCatching { container.backgroundSecrets.load(profileId, SecretKind.ROUTER_BACKGROUND) }.getOrNull()
            ?: return Result.success()

        container.trustStore.pin(profile.address, profile.certificateFingerprint)
        // Bind LAN sockets to Wi-Fi even when Android drops activeNetwork to cellular during an outage.
        val boundClient = UbusClient(
            container.okHttpClient.newBuilder()
                .socketFactory(LanWifiSocketFactory(profile.address, cm))
                .build()
        )
        val session = runCatching { boundClient.login(profile.address, "root", password) }.getOrNull()
            ?: return Result.success()
        try {
            val reader = RouterReader(UbusClientCaller(boundClient, profile.address, session.token))
            val previous = container.alertSettings.memory()
            val wan = runCatching { reader.optional("network.interface", "dump") }.getOrNull()?.let { HomeParsers.parseWan(it).up }
            val list = runCatching { loadDevices(reader) }.getOrNull() ?: return Result.success()
            val highTempEnabled = container.alertSettings.highTempEnabled()
            val tempThreshold = container.alertSettings.tempThresholdC()
            val hottest = if (highTempEnabled) {
                runCatching { reader.readThermals() }.getOrNull()?.maxByOrNull { it.tempCelsius }
            } else null
            val devices = list.devices.associate { it.mac to it.name }
            val snapshot = AlertSnapshot(
                internetUp = wan ?: previous?.internetUp ?: true,
                devices = devices,
                maxTempCelsius = hottest?.tempCelsius,
                hottestSensorLabel = hottest?.label
            )
            val (alerts, memory) = AlertRules.evaluate(
                previous = previous,
                now = snapshot,
                highTempEnabled = highTempEnabled,
                tempThresholdC = tempThreshold
            )
            container.alertSettings.saveMemory(memory)
            alerts.forEach {
                AlertNotifier.show(
                    applicationContext,
                    AlertRules.text(it, profile.name),
                    AlertNotifier.notificationId(it, profile.name)
                )
            }
            runCatching {
                container.widgetStore.update(applicationContext, profile.id, profile.name, internetUp = wan, devicesOnline = list.online)
            }
        } finally {
            runCatching { boundClient.destroySession(profile.address, session.token) }
        }
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun findWifiNetwork(cm: ConnectivityManager): Network? =
        cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
}

private class LanWifiSocketFactory(
    private val targetAddress: String,
    private val cm: ConnectivityManager,
    private val delegate: SocketFactory = getDefault()
) : SocketFactory() {
    override fun createSocket(): Socket =
        delegate.createSocket().also { WifiBinder.bindSocketIfLan(it, targetAddress, cm) }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/** Posts alert notifications. Nothing is shown without the notification permission. */
object AlertNotifier {
    const val CHANNEL_ID = "alerts"
    const val ACTION_ALERT = "com.shaazifi.app.ACTION_ALERT"

    fun notificationId(alert: Alert, routerName: String): Int = when (alert) {
        is Alert.NewDevice -> "new_device:$routerName:${alert.mac.uppercase(Locale.ROOT)}".hashCode()
        is Alert.NewDevices -> "new_devices:$routerName".hashCode()
        Alert.InternetDown, Alert.InternetBack -> "internet:$routerName".hashCode()
        is Alert.HighTemperature -> "high_temp:$routerName".hashCode()
    }

    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_ALERT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Router alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New devices, internet outages, and router temperature alerts"
            }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, text: Pair<String, String>, notificationId: Int = (text.first + text.second).hashCode()) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setContentTitle(text.first)
            .setContentText(text.second)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, n)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post.
        }
    }
}
