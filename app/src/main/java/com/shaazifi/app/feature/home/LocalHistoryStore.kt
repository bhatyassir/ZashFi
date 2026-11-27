/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Local-only history kept on the phone. Nothing here contacts the router or leaves the device;
 * values are the compact encodings from our pure logic classes.
 */
class LocalHistoryStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val NET_EVENTS = stringPreferencesKey("local_net_events")
        private val THERMAL_SAMPLES = stringPreferencesKey("local_thermal_samples")
    }

    suspend fun readNetEvents(): List<NetEvent> = decode(dataStore.data.first()[NET_EVENTS])

    suspend fun recordNetEvent(atMs: Long, up: Boolean) {
        val events = readNetEvents().filter { it.up != up }.toMutableList()
        events += NetEvent(atMs, up)
        dataStore.edit { it[NET_EVENTS] = encode(events) }
    }

    suspend fun readThermalSamples(): List<ThermalSample> = decodeThermalHistory(dataStore.data.first()[THERMAL_SAMPLES])

    suspend fun recordThermalSamples(samples: List<ThermalSample>) {
        if (samples.isEmpty()) return
        val all = (readThermalSamples() + samples).takeLast(300)
        dataStore.edit { it[THERMAL_SAMPLES] = encodeThermalHistory(all) }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(NET_EVENTS)
            prefs.remove(THERMAL_SAMPLES)
        }
    }
}
