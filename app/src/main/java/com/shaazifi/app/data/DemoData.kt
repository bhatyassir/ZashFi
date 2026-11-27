package com.shaazifi.app.data

import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.ThermalSensor
import com.shaazifi.app.feature.router.ViewPill
import com.shaazifi.app.feature.router.ViewRow
import com.shaazifi.app.feature.router.ViewSection
import com.shaazifi.app.ui.theme.PillTone
import java.util.Locale

/**
 * Demo fixtures for Play Store review and offline testing.
 */
object DemoData : RouterRepository {

    val thermals: List<ThermalSensor> = listOf(
        ThermalSensor(id = "hwmon0", rawName = "cpu_thermal", label = "CPU / SoC", tempCelsius = 49.4),
        ThermalSensor(
            id = "hwmon1",
            rawName = "mt7915_phy0",
            label = "2.4 GHz Wi-Fi",
            tempCelsius = 51.0,
            maxCelsius = 120.0,
            critCelsius = 110.0
        ),
        ThermalSensor(
            id = "hwmon2",
            rawName = "mt7915_phy1",
            label = "5 GHz Wi-Fi",
            tempCelsius = 53.0,
            maxCelsius = 120.0,
            critCelsius = 110.0
        )
    )

    val healthView: FeatureView by lazy {
        FeatureView(
            summary = "How hard the router is working.",
            pill = ViewPill("Healthy", PillTone.Ok),
            sections = listOf(
                ViewSection(
                    "Now",
                    listOf(
                        ViewRow("Processor", "8%"),
                        ViewRow("Memory", "42%", sub = "182 MB of 432 MB in use"),
                        ViewRow("Storage", "34%", sub = "61 MB of 178 MB in use"),
                        ViewRow("Running for", "3 d 4 h"),
                        ViewRow("Firmware", "OpenWrt 25.12 (demo)", detail = true)
                    )
                ),
                ViewSection(
                    "Thermals",
                    thermals.map { sensor ->
                        val thresholds = listOfNotNull(
                            sensor.critCelsius?.let { "Crit ${String.format(Locale.US, "%.0f°C", it)}" },
                            sensor.maxCelsius?.let { "Max ${String.format(Locale.US, "%.0f°C", it)}" }
                        ).joinToString(" · ").ifEmpty { null }
                        ViewRow(
                            label = sensor.label,
                            value = String.format(Locale.US, "%.1f°C", sensor.tempCelsius),
                            sub = thresholds,
                            pill = ViewPill("Healthy", PillTone.Ok)
                        )
                    }
                )
            )
        )
    }
}

