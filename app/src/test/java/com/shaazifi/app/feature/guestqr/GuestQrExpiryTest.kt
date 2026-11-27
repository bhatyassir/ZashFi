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
package com.shaazifi.app.feature.guestqr

import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.RouterWriter
import com.shaazifi.app.feature.router.UciOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time-limited guest access: fixed durations, one narrow disable op, and a password-free token. */
class GuestQrExpiryTest {

    private fun pass(
        sectionId: String = "guest_radio0",
        ssid: String = "Home Guest",
        createdAtMs: Long = 1_000_000L,
        expiresAtMs: Long = createdAtMs + 2L * 60 * 60_000L + 5L * 60_000L
    ) = GuestPass(sectionId, ssid, expiresAtMs, createdAtMs)

    private fun fails(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (_: IllegalArgumentException) {
        true
    }

    // ------------------------------------------------------------- duration parsing

    @Test
    fun onlyTheSevenFixedDurationsAreAccepted() {
        assertEquals(30L * 60_000L, parseDuration("30m"))
        assertEquals(60L * 60_000L, parseDuration("1h"))
        assertEquals(2L * 60 * 60_000L, parseDuration("2h"))
        assertEquals(6L * 60 * 60_000L, parseDuration("6h"))
        assertEquals(12L * 60 * 60_000L, parseDuration("12h"))
        assertEquals(24L * 60 * 60_000L, parseDuration("1d"))
        assertEquals(7L * 24 * 60 * 60_000L, parseDuration("7d"))
    }

    @Test
    fun durationsAreCaseInsensitiveButOtherwiseStrict() {
        assertEquals(parseDuration("30m"), parseDuration("30M"))
        assertEquals(parseDuration("12h"), parseDuration(" 12H "))
        assertEquals(parseDuration("7d"), parseDuration("7D"))
        for (bad in listOf("", "5m", "3h", "0h", "8d", "1w", "1.5h", "1 h", "2h30m", "-1h", "+1h", "999999999d", "30m; reboot", "30m\nreboot")) {
            assertNull("must reject $bad", parseDuration(bad))
        }
    }

    // ------------------------------------------------------------- expiry arithmetic

    @Test
    fun expiryChecksAndRemainingTime() {
        val p = pass(createdAtMs = 1_000L, expiresAtMs = 7_250_000L)
        assertFalse(p.isExpired(7_249_999L))
        assertEquals(7_249_000L, p.remainingMs(1_000L))
        assertTrue(p.isExpired(7_250_000L))
        assertEquals(0L, p.remainingMs(7_250_000L))
        assertEquals(0L, p.remainingMs(9_000_000L))
        assertTrue(p.isExpired(9_000_000L))
    }

    // ------------------------------------------------------------- encoding

    @Test
    fun encodeRoundTripsWithoutAnyPassword() {
        val p = pass(ssid = "Living room | guests")
        val text = encode(p)
        assertEquals("the '|' in the SSID must not break the format", 4, text.split('|').size)
        assertEquals(p, decode(text))
        assertFalse("no password field exists in the token", text.contains("super-secret", ignoreCase = true))
        assertEquals("guest_radio0", decode(text)?.sectionId)
    }

    @Test
    fun decodeRejectsCorruptOrTamperedValues() {
        val good = encode(pass())
        assertNull(decode(null))
        assertNull(decode(""))
        assertNull(decode(good.replace("guest_radio0", "guest;radio0")))
        assertNull(decode("guest_radio0||2000|1000")) // blank SSID
        assertNull(decode("guest_radio0|   |2000|1000"))
        assertNull(decode("guest_radio0|Guest|1000"))
        assertNull(decode("$good|extra"))
        assertNull(decode("guest_radio0|Guest|not-a-number|1000"))
        assertNull(decode("guest_radio0|Guest|-2000|1000"))
        assertNull(decode("guest_radio0|Guest|1000|0"))
        assertNull(decode("guest_radio0|Guest|1000|1000")) // expiry must be after creation
        assertNull(decode("guest_radio0|Guest|999|1000"))
        assertNull(decode("guest_radio0|Guest|+2000|1000"))
    }

    @Test
    fun encodeRefusesInvalidPasses() {
        assertTrue(fails { encode(pass(sectionId = "guest;radio0")) })
        assertTrue(fails { encode(pass(sectionId = "")) })
        assertTrue(fails { encode(pass(sectionId = "x".repeat(33))) })
        assertTrue(fails { encode(pass(ssid = "   ")) })
        assertTrue(fails { encode(pass(createdAtMs = 0L, expiresAtMs = 100L)) })
        assertTrue(fails { encode(pass(createdAtMs = 100L, expiresAtMs = 100L)) })
    }

    // ------------------------------------------------------------- disable plan

    @Test
    fun disablePlanIsOneNarrowWirelessOpWithAClearWarning() {
        val result = buildDisablePlan(" guest_radio0 ", " Home Guest ")
        assertTrue(result is PlanResult.Ready)
        val plan = (result as PlanResult.Ready).plan
        assertEquals(1, plan.ops.size)
        assertEquals(
            UciOp.Set("wireless", "guest_radio0", mapOf("disabled" to "1")),
            plan.ops.single()
        )
        val (method, args) = RouterWriter.argsFor(plan.ops.single())
        assertTrue("the plan must be sendable through the safe-change path", RouterWriter.isAllowedWrite("uci", method, args))
        assertTrue(plan.title.contains("guest", ignoreCase = true))
        assertTrue(plan.lines.isNotEmpty())
        assertTrue(plan.lines.all { it.label.isNotBlank() })
        val note = plan.note.orEmpty()
        assertTrue("note must mention the Wi-Fi restart", note.contains("restart", ignoreCase = true))
        assertTrue("note must say guests are disconnected", note.contains("disconnect", ignoreCase = true))
        assertTrue("no main-network or firewall writes", plan.services.isEmpty())
    }

    @Test
    fun disablePlanRefusesSectionNamesThatCouldEscapeUci() {
        for (bad in listOf("", "guest;radio0", "guest radio0", "../lan", "x".repeat(33))) {
            val result = buildDisablePlan(bad, "Guest")
            assertTrue("must reject $bad", result is PlanResult.Invalid)
        }
    }

    // ------------------------------------------------------------- summary text

    @Test
    fun summaryCoversAllThreeStates() {
        val now = 10_000_000L
        assertEquals("No guest pass is active", summarise(null, now))
        val p = pass(createdAtMs = now, expiresAtMs = now + 2L * 60 * 60_000L + 5L * 60_000L)
        assertEquals("Guest access ends in 2 h 5 min", summarise(p, now))
        assertEquals("Guest access ends in 2 h", summarise(p, now + 5L * 60_000L))
        assertEquals("Guest access ends in 5 min", summarise(p, now + 2L * 60 * 60_000L))
        assertEquals("Guest access has ended", summarise(p, p.expiresAtMs))
        assertEquals("Guest access has ended", summarise(p, p.expiresAtMs + 1L))
    }
}
