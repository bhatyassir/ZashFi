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
package com.shaazifi.app.test

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Test DataStore backed by a [MutableStateFlow] instead of a file.
 *
 * AndroidX DataStore's `AtomicFile` cannot always rename `.preferences_pb.tmp` over an existing
 * file on Windows, which makes file-backed JVM tests flaky. Settings stores only need the
 * [DataStore] contract, so an in-memory instance exercises the same code deterministically.
 */
class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {
    private val current = MutableStateFlow(initial)

    override val data: Flow<Preferences> get() = current

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(current.value)
        current.value = next
        return next
    }
}
