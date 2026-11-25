package com.shaazifi.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons in the style of the approved mock: 24x24 grid, 1.7 stroke, round caps
 * and joins, no fill. Paths marked "mock" are copied from
 * docs/design/router-app-mock.html (the owner's own design); the others are drawn
 * here in the same style. Tint them with Icon(tint = ...).
 */
object SfIcons {

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}" +
            "A$r $r 0 0 1 ${x + w - r} ${y + h}H${x + r}A$r $r 0 0 1 $x ${y + h - r}" +
            "V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        )
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    /** mock: bottom bar Home */
    val Home: ImageVector by lazy { icon("Home", "M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z") }

    /** mock: Internet node; used for the Router tab (the router's own pages) */
    val Globe: ImageVector by lazy {
        icon("Globe", circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18")
    }

    /** Drawn here: shield, for AdGuard Home */
    val Shield: ImageVector by lazy {
        icon("Shield", "M12 3 5 6v5.5c0 4.3 2.9 7.9 7 9.5 4.1-1.6 7-5.2 7-9.5V6z", "M9 12l2 2 4-4")
    }

    /** mock: bottom bar More */
    val More: ImageVector by lazy { icon("More", "M5 12h.01M12 12h.01M19 12h.01") }

    /** Drawn here: vertical dots, for an overflow menu */
    val MoreVertical: ImageVector by lazy { icon("MoreVertical", "M12 5h.01M12 12h.01M12 19h.01") }

    /** mock: router with Wi-Fi waves (router found on this Wi-Fi) */
    val RouterLive: ImageVector by lazy {
        icon("RouterLive", rect(3f, 13f, 18f, 7f, 2f), "M7 16.5h.01M11 16.5h.01M8 9a6 6 0 0 1 8 0M5.5 6.5a10 10 0 0 1 13 0")
    }

    /** mock: router, one wave (Home dashboard router node) */
    val Router: ImageVector by lazy {
        icon("Router", rect(3f, 13f, 18f, 7f, 2f), "M7 16.5h.01M11 16.5h.01M8 9a6 6 0 0 1 8 0")
    }

    /** mock: saved router, no waves */
    val RouterSaved: ImageVector by lazy { icon("RouterSaved", rect(3f, 13f, 18f, 7f, 2f), "M7 16.5h.01M11 16.5h.01") }

    /** mock: phone (devices node) */
    val Phone: ImageVector by lazy { icon("Phone", rect(7f, 3f, 10f, 18f, 2f), "M11 18h2") }

    /** mock: bottom bar Devices (laptop and phone) */
    val Devices: ImageVector by lazy {
        icon("Devices", rect(3f, 5f, 12f, 9f, 1.5f), "M2 17h14", rect(17f, 9f, 5f, 10f, 1.2f))
    }

    /** mock: bottom bar Wi-Fi */
    val Wifi: ImageVector by lazy {
        icon("Wifi", "M5 12a10 10 0 0 1 14 0M8 15a6 6 0 0 1 8 0M2 9a14 14 0 0 1 20 0M12 19h.01")
    }

    /** Drawn here: chevrons */
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", "M9 6l6 6-6 6") }
    val ChevronLeft: ImageVector by lazy { icon("ChevronLeft", "M15 6l-6 6 6 6") }
    val Close: ImageVector by lazy { icon("Close", "M6 6l12 12M18 6 6 18") }
    val Sun: ImageVector by lazy {
        icon("Sun", circle(12f, 12f, 4f), "M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M19 5l-1.5 1.5m-11 11L5 19")
    }
    val Moon: ImageVector by lazy { icon("Moon", "M20 15.5A8.4 8.4 0 0 1 8.5 4 8.5 8.5 0 1 0 20 15.5z") }
    val Users: ImageVector by lazy {
        icon("Users", circle(9f, 7f, 3f), "M3 20v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6M17 13a5 5 0 0 1 4 5v2")
    }

    // ---- Drawn here (owner feedback 2026-11-17: a livelier look): device kinds, actions, groups.
    val Laptop: ImageVector by lazy { icon("Laptop", rect(4f, 5f, 16f, 11f, 1.5f), "M2 19h20") }
    val Tv: ImageVector by lazy { icon("Tv", rect(3f, 5f, 18f, 12f, 2f), "M8 21h8M12 17v4") }
    val Gamepad: ImageVector by lazy {
        icon("Gamepad", "M7 8h10a4 4 0 0 1 4 4v2a3 3 0 0 1-5.4 1.8L14.5 14h-5l-1.1 1.8A3 3 0 0 1 3 14v-2a4 4 0 0 1 4-4z", "M7 11v3M5.5 12.5h3M16 11.5h.01M18 13h.01")
    }
    val Printer: ImageVector by lazy { icon("Printer", "M7 9V3h10v6", rect(3f, 9f, 18f, 8f, 2f), rect(7f, 14f, 10f, 7f, 1f)) }
    val Speaker: ImageVector by lazy { icon("Speaker", rect(6f, 3f, 12f, 18f, 2f), circle(12f, 14f, 3f), "M12 7h.01") }
    val Camera: ImageVector by lazy { icon("Camera", rect(3f, 7f, 18f, 13f, 2f), circle(12f, 13.5f, 3.5f), "M8 7l1.5-3h5L16 7") }
    val Bulb: ImageVector by lazy { icon("Bulb", "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z") }
    val Lock: ImageVector by lazy { icon("Lock", rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4") }
    val Chart: ImageVector by lazy { icon("Chart", "M3 20h18", "M6 16v-4M10 16V8M14 16v-6M18 16V5") }
    val Bolt: ImageVector by lazy { icon("Bolt", "M13 2 4 14h7l-1 8 9-12h-7z") }
    val Ban: ImageVector by lazy { icon("Ban", circle(12f, 12f, 9f), "M5.6 5.6l12.8 12.8") }
    val Check: ImageVector by lazy { icon("Check", circle(12f, 12f, 9f), "M8 12l3 3 5-6") }
    val Pencil: ImageVector by lazy { icon("Pencil", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4") }
    val Pin: ImageVector by lazy { icon("Pin", "M12 21s-7-6.2-7-12a7 7 0 0 1 14 0c0 5.8-7 12-7 12z", circle(12f, 9f, 2.5f)) }
    val Port: ImageVector by lazy { icon("Port", rect(4f, 6f, 16f, 13f, 2f), "M8 19v-4h8v4M9 10h.01M12 10h.01M15 10h.01") }
    val Link: ImageVector by lazy { icon("Link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Tools: ImageVector by lazy { icon("Tools", "M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z") }
    val Chip: ImageVector by lazy { icon("Chip", rect(6f, 6f, 12f, 12f, 2f), "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4") }
    val Arrows: ImageVector by lazy { icon("Arrows", "M7 4v16M7 20l-3-3M7 20l3-3", "M17 20V4M17 4l-3 3M17 4l3 3") }

    /** Drawn here: settings gear (simplified) */
    val Settings: ImageVector by lazy {
        icon(
            "Settings", circle(12f, 12f, 3f),
            "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4"
        )
    }
}
