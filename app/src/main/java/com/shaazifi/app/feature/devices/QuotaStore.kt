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
package com.shaazifi.app.feature.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Phone-local daily data quotas; no router call and no address is stored. */
class QuotaStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val KEY = stringPreferencesKey("device_quotas")
    }

    suspend fun all(): List<Quota> = decode(dataStore.data.first()[KEY])

    suspend fun save(quota: Quota) {
        val all = all().filterNot { it.mac.equals(quota.mac, ignoreCase = true) } + quota
        dataStore.edit { it[KEY] = encode(all) }
    }

    suspend fun remove(mac: String) {
        dataStore.edit { it[KEY] = encode(all().filterNot { it.mac.equals(mac, ignoreCase = true) }) }
    }
}
