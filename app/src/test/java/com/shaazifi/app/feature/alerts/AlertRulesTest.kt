package com.shaazifi.app.feature.alerts

import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.test.InMemoryPreferencesDataStore
import com.shaazifi.app.feature.routers.RouterDisplay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M10c. Blueprint v3 "Alerts: New device, internet down, update ready; polled only on the router's
 * network". "Update ready" is dropped: the owner removed firmware options (D-20261117-004C63FB).
 */
class AlertRulesTest {

    private fun snap(
        up: Boolean,
        vararg devices: Pair<String, String>,
        maxTempCelsius: Double? = null,
        sensorLabel: String? = null
    ) = AlertSnapshot(
        internetUp = up,
        devices = devices.toMap(),
        maxTempCelsius = maxTempCelsius,
        hottestSensorLabel = sensorLabel
    )

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
        val hot = AlertMemory(
            knownMacs = setOf("AA:01", "BB:02"),
            internetUp = true,
            highTempActive = true,
            missedCounts = mapOf("BB:02" to 4)
        )
        assertEquals(hot, AlertMemory.decode(hot.encode()))
        assertEquals(
            AlertMemory(knownMacs = setOf("AA:01", "BB:02"), internetUp = false),
            AlertMemory.decode("0|AA:01,BB:02")
        )
        assertNull(AlertMemory.decode("garbage"))
    }

    @Test
    fun highTemperatureAlertsOncePerOverheatEpisodeWithHysteresis() {
        val (a0, m0) = AlertRules.evaluate(
            null,
            snap(true, "AA:01" to "Phone", maxTempCelsius = 82.0, sensorLabel = "CPU"),
            highTempEnabled = true,
            tempThresholdC = 80.0
        )
        assertEquals(listOf(Alert.HighTemperature(82.0, 80.0, "CPU")), a0)
        assertTrue(m0.highTempActive)

        // Still hot (and small dips within hysteresis 77..80): no repeat alert.
        val (a1, m1) = AlertRules.evaluate(
            m0,
            snap(true, "AA:01" to "Phone", maxTempCelsius = 78.5, sensorLabel = "CPU"),
            highTempEnabled = true,
            tempThresholdC = 80.0
        )
        assertTrue(a1.isEmpty())
        assertTrue(m1.highTempActive)

        // Cooled below hysteresis (80 - 3 = 77): episode ends.
        val (a2, m2) = AlertRules.evaluate(
            m1,
            snap(true, "AA:01" to "Phone", maxTempCelsius = 74.0, sensorLabel = "CPU"),
            highTempEnabled = true,
            tempThresholdC = 80.0
        )
        assertTrue(a2.isEmpty())
        assertFalse(m2.highTempActive)

        // Overheats again: new episode fires once.
        val (a3, m3) = AlertRules.evaluate(
            m2,
            snap(true, "AA:01" to "Phone", maxTempCelsius = 81.0, sensorLabel = "CPU"),
            highTempEnabled = true,
            tempThresholdC = 80.0
        )
        assertEquals(listOf(Alert.HighTemperature(81.0, 80.0, "CPU")), a3)
        assertTrue(m3.highTempActive)

        // Disabled: never fires.
        val (a4, _) = AlertRules.evaluate(
            null,
            snap(true, maxTempCelsius = 95.0),
            highTempEnabled = false,
            tempThresholdC = 80.0
        )
        assertTrue(a4.isEmpty())

        val (title, body) = AlertRules.text(Alert.HighTemperature(82.0, 80.0, "CPU"), "Home")
        assertTrue(title.contains("hot"))
        assertTrue(body.contains("CPU") && body.contains("82") && body.contains("80"))
        assertFalse(Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(title + body))
    }

    @Test
    fun staleKnownMacsArePrunedAfterRepeatedAbsences() {
        var mem = AlertRules.evaluate(null, snap(true, "AA:01" to "Phone", "BB:02" to "Guest")).second
        repeat(2) {
            val res = AlertRules.evaluate(mem, snap(true, "AA:01" to "Phone"), staleAfterMisses = 3)
            assertTrue(res.first.isEmpty())
            assertTrue("BB:02" in res.second.knownMacs)
            mem = res.second
        }
        // Third consecutive miss prunes BB:02.
        val pruned = AlertRules.evaluate(mem, snap(true, "AA:01" to "Phone"), staleAfterMisses = 3).second
        assertFalse("BB:02" in pruned.knownMacs)
        assertEquals(setOf("AA:01"), pruned.knownMacs)

        // If BB:02 returns later, it alerts as a new device.
        val (alerts, _) = AlertRules.evaluate(pruned, snap(true, "AA:01" to "Phone", "BB:02" to "Guest"), staleAfterMisses = 3)
        assertEquals(listOf(Alert.NewDevice("BB:02", "Guest")), alerts)
    }

    @Test
    fun unnamedDevicesGetDistinctNotificationIds() {
        val id1 = AlertNotifier.notificationId(Alert.NewDevice("AA:BB:CC:00:00:01", ""), "Home")
        val id2 = AlertNotifier.notificationId(Alert.NewDevice("AA:BB:CC:00:00:02", ""), "Home")
        assertNotEquals(id1, id2)
    }

    @Test
    fun plainHttpProfilesAreBlockedFromBackgroundPolling() {
        val httpsPinned = RouterProfile(name = "Home", address = "https://192.168.1.1", certificateFingerprint = "AA:BB:CC")
        val plainHttp = RouterProfile(name = "Home", address = "http://192.168.1.1", certificateFingerprint = RouterDisplay.HTTP_NO_PIN)
        val httpsNoPin = RouterProfile(name = "Home", address = "https://192.168.1.1", certificateFingerprint = "")
        val httpsHttpFlag = RouterProfile(name = "Home", address = "https://192.168.1.1", certificateFingerprint = RouterDisplay.HTTP_NO_PIN)

        assertTrue(AlertSecurity.canPollInBackground(httpsPinned))
        assertFalse(AlertSecurity.canPollInBackground(plainHttp))
        assertFalse(AlertSecurity.canPollInBackground(httpsNoPin))
        assertFalse(AlertSecurity.canPollInBackground(httpsHttpFlag))
    }

    @Test
    fun alertSettingsPersistTemperaturePreferences() = runTest {
        val ds = InMemoryPreferencesDataStore()
        val settings = AlertSettings(ds)
        assertFalse(settings.highTempEnabled())
        assertEquals(AlertRules.DEFAULT_TEMP_THRESHOLD_C, settings.tempThresholdC(), 0.01)

        settings.setHighTempEnabled(true)
        settings.setTempThresholdC(85.0)
        assertTrue(settings.highTempEnabled())
        assertEquals(85.0, settings.tempThresholdC(), 0.01)

        settings.setTempThresholdC(200.0)
        assertEquals(AlertRules.MAX_TEMP_THRESHOLD_C, settings.tempThresholdC(), 0.01)
    }

    @Test
    fun theManifestAsksForNotificationsOnly() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
    }
}
