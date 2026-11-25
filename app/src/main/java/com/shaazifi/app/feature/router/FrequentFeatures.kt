package com.shaazifi.app.feature.router

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Owner feedback 2026-11-18: a "Frequently used" group at the top of More. The app counts how often
 * each feature is opened, on this phone only (nothing is sent anywhere), and shows the top few.
 */
object FrequentFeatures {
    /** Shown before anything has been opened. */
    val STARTERS = listOf("bandwidth", "services", "live", "parental")

    fun top(counts: Map<String, Int>, isKnown: (String) -> Boolean, n: Int): List<String> {
        val used = counts.filter { (id, count) -> count > 0 && isKnown(id) }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        return (used + STARTERS.filter(isKnown)).distinct().take(n)
    }
}

/** How many times each feature was opened (kept in the app's own storage). */
class FeatureUsageStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("feature_opens")

    val counts: Flow<Map<String, Int>> = dataStore.data.map { decode(it[key]) }

    suspend fun record(id: String) {
        dataStore.edit { prefs ->
            val now = decode(prefs[key]).toMutableMap()
            now[id] = (now[id] ?: 0) + 1
            prefs[key] = JsonObject(now.mapValues { JsonPrimitive(it.value) }).toString()
        }
    }

    private fun decode(s: String?): Map<String, Int> = s?.let {
        runCatching {
            (Json.parseToJsonElement(it) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { n -> k to n } }.toMap()
        }.getOrNull()
    } ?: emptyMap()
}
