package com.shaazifi.app.feature.activity

import com.shaazifi.app.feature.home.UbusCaller
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.ChangeOutcome
import com.shaazifi.app.feature.router.ChangePlan
import com.shaazifi.app.feature.router.Phase4
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.RouterWriter
import com.shaazifi.app.feature.router.SafeChangeRunner
import com.shaazifi.app.ui.theme.ThemeChoice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 (T-20261117-C0594015): activity log, devices across routers, theme choice. */
class Phase5Test {

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan

    @Test
    fun theActivityLogKeepsNewestFirstAndNeverSecrets() {
        var log = emptyList<ActivityEntry>()
        repeat(205) { i -> log = ActivityLog.add(log, ActivityEntry(time = i.toLong(), router = "Home", title = "Change $i", result = "Saved", lines = emptyList())) }
        assertEquals(ActivityLog.MAX, log.size)
        assertEquals("Change 204", log.first().title)
        val pw = plan(Phase4.password("newpass123", "newpass123", "CHANGE"))
        val e = ActivityLog.entry(pw, ChangeOutcome.Kept, router = "Home", time = 5)
        assertFalse(e.toString().contains("newpass123"))
        assertEquals("Saved", e.result)
        assertEquals("Undone by the router (lost contact)", ActivityLog.entry(pw, ChangeOutcome.LostConnection, "Home", 5).result)
        assertEquals(log, ActivityLog.decode(ActivityLog.encode(log)))
        assertEquals(emptyList<ActivityEntry>(), ActivityLog.decode("garbage"))
    }

    @Test
    fun theChangeControllerReportsEveryResult() = runBlocking {
        val caller = object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject) = JsonObject(emptyMap())
        }
        val seen = mutableListOf<Pair<ChangePlan, ChangeOutcome>>()
        val controller = ChangeController(SafeChangeRunner(RouterWriter(caller), probeAttempts = 1, probeDelayMs = 0), onDone = { p, o -> seen += p to o })
        val p = plan(Phase4.serviceAction("dnsmasq", "restart"))
        controller.request(p)
        assertTrue("nothing before confirm", seen.isEmpty())
        controller.confirm()
        assertEquals(listOf(p to ChangeOutcome.Kept), seen)
    }

    @Test
    fun devicesFromAllRoutersAreSearchable() {
        var cache = emptyMap<String, CachedRouterDevices>()
        cache = DeviceCache.put(cache, "r1", "Home", 100, listOf(CachedDevice("Laptop", "192.168.1.5", "AA:BB:CC:00:00:01", true)))
        cache = DeviceCache.put(cache, "r2", "Office", 200, listOf(CachedDevice("Printer", "10.0.0.9", "AA:BB:CC:00:00:02", false)))
        assertEquals(listOf("Printer"), DeviceCache.search(cache, "10.0.0").map { it.second.name })
        assertEquals(listOf("Laptop"), DeviceCache.search(cache, "aa:bb:cc:00:00:01").map { it.second.name })
        assertEquals(2, DeviceCache.search(cache, "").size)
        assertEquals(setOf("r2"), DeviceCache.forget(cache, "r1").keys)
        assertEquals(cache, DeviceCache.decode(DeviceCache.encode(cache)))
        assertTrue(DeviceCache.decode(null).isEmpty())
    }

    @Test
    fun themeChoice() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.parse(null))
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse("DARK"))
        assertTrue(ThemeChoice.DARK.isDark(systemDark = false))
        assertFalse(ThemeChoice.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeChoice.SYSTEM.isDark(systemDark = true))
        assertNull(null)
    }
}
