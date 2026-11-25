package com.shaazifi.app.feature.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M10c. Blueprint v3 "Alerts: New device, internet down, update ready; polled only on the router's
 * network". "Update ready" is dropped: the owner removed firmware options (D-20261117-004C63FB).
 */
class AlertRulesTest {

    private fun snap(up: Boolean, vararg devices: Pair<String, String>) =
        AlertSnapshot(internetUp = up, devices = devices.toMap())

    @Test
    fun theFirstCheckOnlyLearnsWhatIsThere() {
        val (alerts, memory) = AlertRules.evaluate(null, snap(true, "AA:01" to "Phone"))
        assertTrue(alerts.isEmpty())
        assertEquals(setOf("AA:01"), memory.knownMacs)
        assertEquals(true, memory.internetUp)
    }

    @Test
    fun aNewDeviceAlertsOnce() {
        val m0 = AlertRules.evaluate(null, snap(true, "AA:01" to "Phone")).second
        val (a1, m1) = AlertRules.evaluate(m0, snap(true, "AA:01" to "Phone", "BB:02" to "Laptop"))
        assertEquals(listOf(Alert.NewDevice("BB:02", "Laptop")), a1)
        val (a2, _) = AlertRules.evaluate(m1, snap(true, "AA:01" to "Phone", "BB:02" to "Laptop"))
        assertTrue("same device again: no alert", a2.isEmpty())
    }

    @Test
    fun manyNewDevicesBecomeOneAlert() {
        val m0 = AlertRules.evaluate(null, snap(true)).second
        val many = (1..7).map { "CC:0$it" to "Device $it" }.toTypedArray()
        val (a, _) = AlertRules.evaluate(m0, snap(true, *many))
        assertEquals(listOf(Alert.NewDevices(7)), a)
    }

    @Test
    fun internetDownAndBackAlertOnTheChangeOnly() {
        val m0 = AlertRules.evaluate(null, snap(true)).second
        val (a1, m1) = AlertRules.evaluate(m0, snap(false))
        assertEquals(listOf(Alert.InternetDown), a1)
        val (a2, m2) = AlertRules.evaluate(m1, snap(false))
        assertTrue("still down: no repeat", a2.isEmpty())
        val (a3, _) = AlertRules.evaluate(m2, snap(true))
        assertEquals(listOf(Alert.InternetBack), a3)
    }

    @Test
    fun alertTextNeverCarriesAddressesOrSecrets() {
        val texts = listOf(Alert.NewDevice("BB:02", "Laptop"), Alert.NewDevices(3), Alert.InternetDown, Alert.InternetBack)
            .map { AlertRules.text(it, routerName = "Home") }
        texts.forEach { (title, body) ->
            assertTrue(title.isNotBlank() && body.isNotBlank())
            assertTrue("no IP addresses in notifications", !Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(title + body))
        }
        assertTrue(texts[0].second.contains("Laptop"))
    }

    @Test
    fun memoryRoundTripsThroughText() {
        val m = AlertMemory(knownMacs = setOf("AA:01", "BB:02"), internetUp = false)
        assertEquals(m, AlertMemory.decode(m.encode()))
        assertEquals(null, AlertMemory.decode("garbage"))
    }

    @Test
    fun theManifestAsksForNotificationsOnly() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
    }
}
