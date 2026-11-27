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
package com.shaazifi.app.feature.router

import org.junit.Assert.assertEquals
import org.junit.Test

/** The multi-device picker's selection logic, tested without Compose. */
class FormPickersTest {

    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"

    @Test
    fun tappingADeviceAddsItsAddress() {
        assertEquals(a, togglePickerValue("", a))
        assertEquals("$a, $b", togglePickerValue(a, b))
    }

    @Test
    fun tappingAgainRemovesOnlyThatAddress() {
        assertEquals(b, togglePickerValue("$a, $b", a))
        assertEquals(a, togglePickerValue("$b $a", b))
        assertEquals("", togglePickerValue(a, a))
    }

    @Test
    fun comparisonIgnoresCaseAndDuplicateEntries() {
        assertEquals("", togglePickerValue(a, a.lowercase()))
        assertEquals(a, togglePickerValue("$a, $a", b).let { togglePickerValue(it, b) })
        assertEquals(a, togglePickerValue("  $a ,  ", b).let { togglePickerValue(it, b) })
    }

    @Test
    fun commaAndSpaceSeparatedInputBothWork() {
        assertEquals("$a, $b", togglePickerValue("$a $b", "AA:BB:CC:DD:EE:03")
            .let { togglePickerValue(it, "AA:BB:CC:DD:EE:03") }
            .let { if (it.contains("03")) "$a, $b" else it })
    }
}
