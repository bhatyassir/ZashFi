package com.shaazifi.app.feature.devices

import java.util.Locale

/**
 * What a device probably is, guessed from its name and maker, so the list can show a fitting
 * picture and colour (owner feedback 2026-11-17: the device page looked monotonous).
 * A guess only: it never changes what the app does with the device.
 */
enum class DeviceKind(val label: String) {
    PHONE("Phone or tablet"),
    COMPUTER("Computer"),
    TV("TV or streamer"),
    CONSOLE("Game console"),
    PRINTER("Printer"),
    SPEAKER("Speaker"),
    CAMERA("Camera"),
    SMART_HOME("Smart home"),
    NETWORK("Network device"),
    OTHER("Device");

    companion object {
        // Checked in this order: the first kind with a matching word wins. "=word" must stand alone.
        private val WORDS: List<Pair<DeviceKind, List<String>>> = listOf(
            PRINTER to listOf("printer", "laserjet", "deskjet", "officejet", "envy", "epson", "canon", "brother", "pixma"),
            CONSOLE to listOf("ps4", "ps5", "playstation", "xbox", "nintendo", "steamdeck", "steam-deck"),
            TV to listOf("=tv", "smarttv", "androidtv", "appletv", "firetv", "googletv", "webos", "bravia", "roku", "chromecast", "firestick", "fire-stick", "aftmm", "mibox", "shield", "tizen"),
            SPEAKER to listOf("echo", "alexa", "homepod", "sonos", "google-home", "googlehome", "nest-audio", "nestaudio", "speaker", "soundbar"),
            CAMERA to listOf("camera", "ipcam", "ezviz", "hikvision", "dahua", "doorbell", "imou", "wyze", "-cam", "cam-"),
            SMART_HOME to listOf("esp-", "esp32", "esp8266", "espressif", "tuya", "shelly", "tasmota", "sonoff", "smartplug", "smart-plug", "bulb", "wiz", "philips-hue", "meross", "broadlink"),
            NETWORK to listOf("openwrt", "router", "repeater", "extender", "access-point", "mesh-node"),
            COMPUTER to listOf("desktop", "laptop", "macbook", "imac", "mac-mini", "macmini", "thinkpad", "notebook", "vivobook", "zenbook", "ideapad", "-pc", "pc-", "workstation", "ubuntu", "windows", "surface"),
            PHONE to listOf("iphone", "ipad", "android", "galaxy", "pixel", "redmi", "oneplus", "oppo", "vivo", "realme", "poco", "phone", "moto", "nokia", "huawei", "honor", "sm-", "tablet")
        )

        fun guess(name: String, maker: String?): DeviceKind {
            val text = (name + " " + (maker ?: "")).lowercase(Locale.ROOT)
            return WORDS.firstOrNull { (_, words) -> words.any { w -> if (w.startsWith("=")) Regex("(^|[^a-z])" + w.drop(1) + "($|[^a-z])").containsMatchIn(text) else text.contains(w) } }?.first ?: OTHER
        }
    }
}
