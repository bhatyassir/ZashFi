package com.shaazifi.app.feature.quick

import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.demo.DemoMode
import com.shaazifi.app.feature.web.MainTab
import com.shaazifi.app.feature.web.MorePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** M9 item 3: Quick Settings tiles are shortcuts; they carry no secrets and change nothing by themselves. */
class QuickActionTest {

    @Test
    fun onlyKnownShortcutsAreAccepted() {
        assertEquals(QuickAction.ADGUARD_PAUSE, QuickAction.parse("adguard_pause"))
        assertEquals(QuickAction.GUEST_WIFI, QuickAction.parse("guest_wifi"))
        assertNull(QuickAction.parse(null))
        assertNull(QuickAction.parse("delete_everything"))
        assertNull(QuickAction.parse("ADGUARD_PAUSE"))
    }

    @Test
    fun aShortcutCarriesOnlyItsName() {
        QuickAction.entries.forEach { a ->
            assertEquals(mapOf(QuickAction.EXTRA to a.key), QuickAction.extrasFor(a))
        }
    }

    @Test
    fun shortcutsOpenTheRightScreen() {
        val pause = QuickAction.ADGUARD_PAUSE.startNav()
        assertEquals(MainTab.AdGuard, pause.tab)
        val guest = QuickAction.GUEST_WIFI.startNav()
        assertEquals(MainTab.Wifi, guest.tab)
        assertEquals("guest", guest.wifiPage)
        assertEquals("blueprint: pause AdGuard 10 min", 600_000L, QuickAction.PAUSE_MS)
    }

    @Test
    fun theRouterIsPickedOnlyWhenThereIsOne() {
        val a = RouterProfile(id = "a", name = "Home", address = "https://192.168.1.1", certificateFingerprint = "AA")
        val b = RouterProfile(id = "b", name = "Office", address = "https://192.168.2.1", certificateFingerprint = "BB")
        assertEquals(a, QuickAction.routerFor(listOf(a)))
        assertEquals("the demo router is never used", a, QuickAction.routerFor(listOf(a, DemoMode.profile)))
        assertNull("several routers: the owner picks", QuickAction.routerFor(listOf(a, b)))
        assertNull(QuickAction.routerFor(emptyList()))
    }

    @Test
    fun tilesAreDeclaredSoOnlyTheSystemCanBindThem() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        for (name in listOf(".feature.quick.AdGuardPauseTileService", ".feature.quick.GuestWifiTileService")) {
            val block = manifest.substringAfter("android:name=\"$name\"").substringBefore("</service>")
            assertTrue("$name declared", manifest.contains("android:name=\"$name\""))
            assertTrue(block.contains("android:permission=\"android.permission.BIND_QUICK_SETTINGS_TILE\""))
            assertTrue(block.contains("android.service.quicksettings.action.QS_TILE"))
        }
    }
}
