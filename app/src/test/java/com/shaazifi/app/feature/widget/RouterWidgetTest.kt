package com.shaazifi.app.feature.widget

import com.shaazifi.app.feature.alerts.AlertNotifier
import com.shaazifi.app.feature.quick.QuickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** M10e: home-screen widget with no secrets (owner's brief); it shows what the app last saw. */
class RouterWidgetTest {

    private val time: (Long) -> String = { "14:32" }

    @Test
    fun theWidgetHoldsNoAddressesTokensOrPasswords() {
        val fields = WidgetSnapshot::class.java.declaredFields.map { it.name.lowercase() }
        listOf("address", "token", "password", "key", "ip", "url", "secret", "mac").forEach { bad ->
            assertFalse("widget data must not hold '$bad'", fields.any { it.contains(bad) })
        }
    }

    @Test
    fun snapshotRoundTripsAndRejectsGarbage() {
        val s = WidgetSnapshot("r1", "Home", internetUp = true, devicesOnline = 12, adguardOn = false, updatedAt = 1_000L)
        assertEquals(s, WidgetSnapshot.decode(s.encode()))
        assertNull(WidgetSnapshot.decode("not json"))
        assertNull(WidgetSnapshot.decode(null))
    }

    @Test
    fun linesSayWhatIsKnown() {
        val s = WidgetSnapshot("r1", "Home", internetUp = true, devicesOnline = 12, adguardOn = true, updatedAt = 1_000L)
        val placeholder = "Open the app to see your router"
        assertEquals(listOf("Internet: online", "12 devices online", "AdGuard: on", "Updated 14:32"), WidgetText.lines(s, placeholder, time))
        val partial = WidgetSnapshot("r1", "Home", internetUp = false, devicesOnline = 1, adguardOn = null, updatedAt = 1_000L)
        assertEquals(listOf("Internet: down", "1 device online", "Updated 14:32"), WidgetText.lines(partial, placeholder, time))
        assertEquals(listOf(placeholder), WidgetText.lines(null, placeholder, time))
    }

    @Test
    fun updatesKeepOtherValuesButAnotherRouterStartsFresh() {
        val a = WidgetSnapshot.merge(null, "r1", "Home", 1L, internetUp = true, devicesOnline = 5)
        val b = WidgetSnapshot.merge(a, "r1", "Home", 2L, adguardOn = true)
        assertEquals(WidgetSnapshot("r1", "Home", true, 5, true, 2L), b)
        val c = WidgetSnapshot.merge(b, "r2", "Office", 3L, internetUp = false)
        assertEquals(WidgetSnapshot("r2", "Office", false, null, null, 3L), c)
    }

    @Test
    fun theWidgetIsDeclared() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        assertTrue(manifest.contains("android:resource=\"@xml/router_widget_info\""))
        val info = File(main, "res/xml/router_widget_info.xml").readText()
        assertTrue("the app pushes updates; the system never wakes it", info.contains("android:updatePeriodMillis=\"0\""))
    }

    @Test
    fun widgetActionAndRequestCodeAreDistinct() {
        assertNotEquals(RouterWidget.ACTION_WIDGET, AlertNotifier.ACTION_ALERT)
        QuickAction.entries.forEach { action ->
            assertNotEquals(RouterWidget.ACTION_WIDGET, action.intentAction())
            assertNotEquals(RouterWidget.REQUEST_CODE, action.requestCode)
        }
    }
}
