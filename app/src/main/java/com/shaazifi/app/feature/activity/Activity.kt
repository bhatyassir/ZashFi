package com.shaazifi.app.feature.activity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shaazifi.app.feature.router.ChangeOutcome
import com.shaazifi.app.feature.router.ChangePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * Phase 5 (T-20261117-C0594015): a log of changes made from the app, and the last device list
 * of every router, kept on the phone only (excluded from backups). Idea from LuCI Mobile's
 * activity feed and multi-router client list (GPL-3.0; idea only, no code).
 */

/** One change made from the app. [lines] are the confirmation's lines (passwords there are always "hidden"). */
@Serializable
data class ActivityEntry(val time: Long, val router: String, val title: String, val result: String, val lines: List<String>)

object ActivityLog {
    const val MAX = 200
    private val json = Json { ignoreUnknownKeys = true }

    fun add(log: List<ActivityEntry>, e: ActivityEntry): List<ActivityEntry> = (listOf(e) + log).take(MAX)

    fun entry(plan: ChangePlan, outcome: ChangeOutcome, router: String, time: Long) = ActivityEntry(
        time = time,
        router = router,
        title = plan.title,
        result = when (outcome) {
            ChangeOutcome.Kept -> "Saved"
            ChangeOutcome.LostConnection -> "Undone by the router (lost contact)"
            ChangeOutcome.InternetNotBack -> "Undone by the router (internet did not come back)"
            is ChangeOutcome.Failed -> "Failed: ${outcome.message}"
        },
        lines = plan.lines.map { l ->
            when {
                l.before.isNotBlank() && l.after.isNotBlank() -> "${l.label}: ${l.before} → ${l.after}"
                l.after.isNotBlank() -> "${l.label}: ${l.after}"
                else -> l.label
            }
        }
    )

    fun encode(log: List<ActivityEntry>) = json.encodeToString(log)
    fun decode(s: String?): List<ActivityEntry> = s?.let { runCatching { json.decodeFromString<List<ActivityEntry>>(it) }.getOrNull() } ?: emptyList()
}

class ActivityStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("activity_log")
    val entries: Flow<List<ActivityEntry>> = dataStore.data.map { ActivityLog.decode(it[key]) }
    suspend fun add(e: ActivityEntry) { dataStore.edit { it[key] = ActivityLog.encode(ActivityLog.add(ActivityLog.decode(it[key]), e)) } }
    suspend fun clear() { dataStore.edit { it.remove(key) } }
}

@Serializable
data class CachedDevice(val name: String, val ip: String, val mac: String, val online: Boolean)

@Serializable
data class CachedRouterDevices(val routerName: String, val time: Long, val devices: List<CachedDevice>)

object DeviceCache {
    private val json = Json { ignoreUnknownKeys = true }

    fun put(cache: Map<String, CachedRouterDevices>, routerId: String, routerName: String, time: Long, devices: List<CachedDevice>) =
        cache + (routerId to CachedRouterDevices(routerName, time, devices))

    fun forget(cache: Map<String, CachedRouterDevices>, routerId: String) = cache - routerId

    /** Router name and device, matching name, address or hardware address; all when [q] is blank. */
    fun search(cache: Map<String, CachedRouterDevices>, q: String): List<Pair<String, CachedDevice>> {
        val t = q.trim()
        return cache.values.flatMap { r -> r.devices.map { r.routerName to it } }.filter { (_, d) ->
            t.isEmpty() || d.name.contains(t, true) || d.ip.contains(t) || d.mac.contains(t, true)
        }.sortedWith(compareByDescending<Pair<String, CachedDevice>> { it.second.online }.thenBy { it.second.name.lowercase() })
    }

    fun encode(cache: Map<String, CachedRouterDevices>) = json.encodeToString(cache)
    fun decode(s: String?): Map<String, CachedRouterDevices> =
        s?.let { runCatching { json.decodeFromString<Map<String, CachedRouterDevices>>(it) }.getOrNull() } ?: emptyMap()
}

class DeviceCacheStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("device_cache")
    val cache: Flow<Map<String, CachedRouterDevices>> = dataStore.data.map { DeviceCache.decode(it[key]) }
    suspend fun put(routerId: String, routerName: String, devices: List<CachedDevice>) {
        dataStore.edit { it[key] = DeviceCache.encode(DeviceCache.put(DeviceCache.decode(it[key]), routerId, routerName, System.currentTimeMillis(), devices)) }
    }
    suspend fun forget(routerId: String) { dataStore.edit { it[key] = DeviceCache.encode(DeviceCache.forget(DeviceCache.decode(it[key]), routerId)) } }
    suspend fun read() = DeviceCache.decode(dataStore.data.first()[key])
}
