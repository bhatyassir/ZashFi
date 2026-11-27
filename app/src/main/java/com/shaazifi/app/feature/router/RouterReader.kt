package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale

/** Why a feature screen could not be read. Maps onto the six screen states. */
sealed class FeatureLoadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class SessionExpired : FeatureLoadException("Session expired")
    class NotPermitted : FeatureLoadException("Not permitted")
    class NotInstalled : FeatureLoadException("Not installed")
    class Unreachable(cause: Throwable? = null) : FeatureLoadException("Router unreachable", cause)
}

/** One temperature reading from `/sys/class/hwmon` or `/sys/class/thermal`. */
data class ThermalSensor(
    val id: String,
    val rawName: String,
    val label: String,
    val tempCelsius: Double,
    val maxCelsius: Double? = null,
    val critCelsius: Double? = null
)

/** Hardware/software flow offloading flags from UCI `firewall` `@defaults`. */
data class FlowOffloadingStatus(val software: Boolean, val hardware: Boolean)

/** One section of a UCI config: `.type`, `.name` and options (single values become one-item lists). */
data class UciSection(val id: String, val type: String, val options: Map<String, List<String>>) {
    fun opt(name: String): String? = options[name]?.firstOrNull()
    fun list(name: String): List<String> = options[name] ?: emptyList()
    /** UCI booleans: "1", "true", "on", "yes" (OpenWrt UCI convention). */
    fun flag(name: String, default: Boolean = false): Boolean =
        opt(name)?.lowercase()?.let { it == "1" || it == "true" || it == "on" || it == "yes" || it == "enabled" } ?: default
}

data class UciConfig(val sections: List<UciSection>) {
    fun ofType(type: String): List<UciSection> = sections.filter { it.type == type }
    fun byId(id: String): UciSection? = sections.firstOrNull { it.id == id }

    companion object {
        /**
         * Parses the reply of `uci get {"config": <name>}`: `{"values": {<id>: {".type": ..., <option>: string | [strings]}}}`.
         * Shape as used by UnTangled (src/lib/ubus.ts uciGet; src/lib/devices.ts) and LuCI.
         */
        fun parse(reply: JsonObject): UciConfig {
            val values = reply["values"] as? JsonObject ?: return UciConfig(emptyList())
            val sections = values.mapNotNull { (id, element) ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val type = (obj[".type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val options = obj.filterKeys { !it.startsWith(".") }.mapValues { (_, v) -> v.asStringList() }
                UciSection(id, type, options)
            }
            val ordered = sections.sortedBy { s ->
                ((values[s.id] as? JsonObject)?.get(".index") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
            }
            return UciConfig(ordered)
        }

        private fun JsonElement.asStringList(): List<String> = when (this) {
            is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOf(content)
            else -> emptyList()
        }
    }
}

/** Reply of `file exec`: exit code and output (rpcd-mod-file). */
data class ExecResult(val code: Int, val stdout: String, val stderr: String)

/**
 * The only way the native feature screens talk to the router. READ-ONLY:
 * every call is checked against [isAllowedRead] before it is sent, and anything
 * else is refused in the app. No call here changes router settings.
 */
class RouterReader(private val caller: UbusCaller) {

    companion object {
        /** UCI configs the feature screens may read. `uci get` only, never set/add/delete/apply. */
        val UCI_CONFIGS = setOf(
            "dhcp", "firewall", "network", "ddns", "mwan3", "upnpd", "sqm", "openvpn",
            "system", "uhttpd", "minidlna", "ksmbd", "samba4", "adblock", "banip", "nlbwmon",
            "adguardhome", "wireless"
        )

        private val FIXED_READS = setOf(
            "system" to "board",
            "system" to "info",
            "network.interface" to "dump",
            "network.device" to "status",
            "network.wireless" to "status",
            "luci-rpc" to "getWirelessDevices",
            "luci-rpc" to "getHostHints",
            "luci-rpc" to "getDHCPLeases",
            "untangled.mesh" to "info",
            "untangled.mesh" to "status",
            "untangled.mesh" to "nodes",
            // M10b services list (rpcd rc.c; LuCI startup page). "rc init" (start/stop) is a write and is not here.
            "rc" to "list",
            // Phase 3: interfaces, WireGuard peers, the login's own permissions.
            "luci-rpc" to "getNetworkDevices",
            "luci.wireguard" to "getWgInstances",
            "session" to "access",
            // Phase 4: time zone list (LuCI system page).
            "luci" to "getTimezones",
            // Owner feedback: per-device bandwidth. Open connections with byte counts
            // (luci-base ucode luci getConntrackList -> sys.uc conntrack_list; LuCI's Realtime > Connections page).
            "luci" to "getConntrackList"
        )

        /** Files that may be read. */
        val FILE_READS = setOf(
            "/proc/mounts", "/proc/stat",
            // Owner feedback 2026-11-18: live upload/download per device (the file LuCI's connection list reads).
            "/proc/net/nf_conntrack",
            // LuCI System > Scheduled Tasks. Read-only: the editor is not implemented here.
            "/etc/crontabs/root"
        )

        /** Sysfs directories that may be listed for temperature sensors. */
        val FILE_LISTS = setOf("/sys/class/hwmon", "/sys/class/thermal")

        private val HWMON_READ_REGEX =
            Regex("^/sys/class/hwmon/hwmon[0-9]{1,2}/(name|temp[1-8]_(input|label|max|crit))$")
        private val THERMAL_ZONE_READ_REGEX =
            Regex("^/sys/class/thermal/thermal_zone[0-9]{1,2}/(type|temp)$")
        private val HWMON_DIR_REGEX = Regex("^hwmon[0-9]{1,2}$")
        private val THERMAL_ZONE_DIR_REGEX = Regex("^thermal_zone[0-9]{1,2}$")

        fun isAllowedThermalReadPath(path: String): Boolean =
            HWMON_READ_REGEX.matches(path) || THERMAL_ZONE_READ_REGEX.matches(path)

        fun isAllowedFileRead(path: String): Boolean =
            path in FILE_READS || isAllowedThermalReadPath(path)

        fun isAllowedFileList(path: String): Boolean = path in FILE_LISTS

        /**
         * Commands that only print information. Exactly these, with exactly these arguments,
         * as granted to UnTangled by its ACL (openwrt/files/usr/share/rpcd/acl.d/untangled.json).
         */
        val EXEC_READS: Set<Pair<String, List<String>>> = setOf(
            "/usr/bin/vnstat" to listOf("--json"),
            "/usr/sbin/nlbw" to listOf("-c", "json", "-g", "ip,mac", "-o", "rx_bytes"),
            "/usr/sbin/tailscale" to listOf("status", "--json"),
            "/usr/bin/tailscale" to listOf("status", "--json"),
            "/usr/bin/apk" to listOf("info"),
            "/usr/bin/apk" to listOf("list", "-I"),
            "/bin/opkg" to listOf("list-installed"),
            // Kernel log as LuCI reads it (status/dmesg.js).
            "/bin/dmesg" to listOf("-r"),
            // Read-only status pages from LuCI's Status menu.
            "/bin/ps" to listOf("-w"),
            "/sbin/ip" to listOf("-4", "route", "show"),
            "/sbin/ip" to listOf("-6", "route", "show"),
            "/usr/sbin/nft" to listOf("list", "ruleset"),
            // LED night-mode status (the write actions are in RouterWriter.SERVICES).
            "/usr/bin/led-nightmode" to listOf("check")
        )

        const val LOG_LINES = 200

        fun isAllowedRead(ubusObject: String, method: String, args: JsonObject): Boolean {
            if (ubusObject.startsWith("hostapd.")) {
                return method == "get_clients" && HomeParsers.isSafeIfname(ubusObject.removePrefix("hostapd.")) && args.isEmpty()
            }
            when (ubusObject to method) {
                "uci" to "get" -> {
                    val config = (args["config"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return args.size == 1 && config != null && config in UCI_CONFIGS
                }
                "file" to "read" -> {
                    val path = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return args.size == 1 && path != null && isAllowedFileRead(path)
                }
                "file" to "list" -> {
                    val path = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return args.size == 1 && path != null && isAllowedFileList(path)
                }
                "file" to "exec" -> {
                    val command = (args["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                    val params = (args["params"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: return false } ?: return false
                    return args.size == 2 && ((command to params) in EXEC_READS || Diagnostics.isAllowed(command, params))
                }
                "log" to "read" -> {
                    val lines = (args["lines"] as? JsonPrimitive)?.intOrNull
                    val stream = (args["stream"] as? JsonPrimitive)?.content
                    return args.size == 2 && lines == LOG_LINES && stream == "false"
                }
                "luci" to "getRealtimeStats" -> {
                    // Live traffic per device only (luci-bwc -i); never conntrack or other modes.
                    val mode = (args["mode"] as? JsonPrimitive)?.content
                    val device = (args["device"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return args.size == 2 && mode == "interface" && device != null && HomeParsers.isSafeIfname(device)
                }
                "iwinfo" to "scan", "iwinfo" to "freqlist", "iwinfo" to "txpowerlist", "iwinfo" to "countrylist" -> {
                    // As LuCI: the radio's UCI name (radio0) as `device`.
                    val device = (args["device"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return args.size == 1 && device != null && WifiRadio.isRadioName(device)
                }
                "iwinfo" to "assoclist" -> {
                    val device = (args["device"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return args.size == 1 && device != null && HomeParsers.isSafeIfname(device)
                }
            }
            return (ubusObject to method) in FIXED_READS && args.isEmpty()
        }

        private fun isConnectionProblem(e: IOException): Boolean =
            e !is UbusError || e is UbusError.NetworkError || e is UbusError.HttpError || e is UbusError.InvalidJson

        /**
         * Maps a failed call to a screen state. ubus status codes (openwrt/ubus ubusmsg.h,
         * master db20eff5535f): 3 METHOD_NOT_FOUND, 4 NOT_FOUND, 5 NO_DATA, 6 PERMISSION_DENIED.
         * The app's UbusError names code 4 "SessionExpired" and 5 "ObjectNotFound"; for a
         * feature read both mean "this package or setting is not on the router".
         * An expired session answers "access denied" (see UbusClient), so [sessionGate]
         * marks the first call of a screen, where access denied means the session ended.
         */
        fun classify(e: IOException, sessionGate: Boolean): FeatureLoadException = when {
            isConnectionProblem(e) -> FeatureLoadException.Unreachable(e)
            e is UbusError.AccessDenied -> if (sessionGate) FeatureLoadException.SessionExpired() else FeatureLoadException.NotPermitted()
            else -> FeatureLoadException.NotInstalled()
        }

        internal fun friendlyThermalLabel(rawName: String, channelLabel: String? = null): String {
            val norm = rawName.trim().lowercase(Locale.ROOT)
            val base = when {
                norm == "cpu_thermal" || norm == "cpu-thermal" || norm == "soc_thermal" || norm == "soc-thermal" ||
                    norm == "x86_pkg_temp" || norm == "coretemp" || norm == "k10temp" || norm == "acpitz" ||
                    norm.startsWith("cpu") -> "CPU / SoC"
                norm == "mt7915_phy0" || norm == "mt7986_phy0" || norm == "phy0" || norm.endsWith("_phy0") -> "2.4 GHz Wi-Fi"
                norm == "mt7915_phy1" || norm == "mt7986_phy1" || norm == "phy1" || norm.endsWith("_phy1") -> "5 GHz Wi-Fi"
                norm == "mt7915_phy2" || norm == "mt7986_phy2" || norm == "phy2" || norm.endsWith("_phy2") -> "6 GHz Wi-Fi"
                norm == "iwlwifi_1" -> "Wi-Fi"
                norm == "nvme" -> "NVMe SSD"
                norm == "drivetemp" -> "Drive"
                else -> null
            }
            val cleanChan = channelLabel?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                base != null && cleanChan != null && !cleanChan.equals(rawName, ignoreCase = true) -> "$base ($cleanChan)"
                base != null -> base
                cleanChan != null -> cleanChan
                else -> rawName.trim().ifEmpty { "Sensor" }
            }
        }

        private fun parseMillidegreesCelsius(text: String?): Double? {
            val raw = text?.trim()?.toDoubleOrNull() ?: return null
            if (!raw.isFinite()) return null
            val celsius = raw / 1000.0
            return celsius.takeIf { it in -50.0..200.0 }
        }

        private fun isCpuLabel(label: String): Boolean = label == "CPU / SoC"

        internal suspend fun readThermalsWith(
            listDir: suspend (String) -> List<String>?,
            readFile: suspend (String) -> String?
        ): List<ThermalSensor> {
            val hwmonDir = listDir("/sys/class/hwmon")
            val hwmonNames = if (hwmonDir != null) {
                hwmonDir.filter { HWMON_DIR_REGEX.matches(it) }
                    .sortedBy { it.removePrefix("hwmon").toIntOrNull() ?: Int.MAX_VALUE }
            } else {
                listOf("hwmon0", "hwmon1", "hwmon2", "hwmon3")
            }

            val hwmonSensors = mutableListOf<ThermalSensor>()
            for (hwmon in hwmonNames) {
                val base = "/sys/class/hwmon/$hwmon"
                val firstTemp = parseMillidegreesCelsius(readFile("$base/temp1_input")) ?: continue
                val rawName = readFile("$base/name")?.trim()?.takeIf { it.isNotEmpty() } ?: hwmon
                for (ch in 1..8) {
                    val tempC = if (ch == 1) firstTemp else parseMillidegreesCelsius(readFile("$base/temp${ch}_input")) ?: break
                    val chanLabel = readFile("$base/temp${ch}_label")?.trim()?.takeIf { it.isNotEmpty() }
                    val maxC = parseMillidegreesCelsius(readFile("$base/temp${ch}_max"))?.takeIf { it > 0.0 }
                    val critC = parseMillidegreesCelsius(readFile("$base/temp${ch}_crit"))?.takeIf { it > 0.0 }
                    hwmonSensors += ThermalSensor(
                        id = if (ch == 1) hwmon else "$hwmon:temp$ch",
                        rawName = rawName,
                        label = friendlyThermalLabel(rawName, chanLabel),
                        tempCelsius = tempC,
                        maxCelsius = maxC,
                        critCelsius = critC
                    )
                }
            }

            if (hwmonSensors.isNotEmpty() && hwmonSensors.any { isCpuLabel(it.label) }) {
                return hwmonSensors
            }

            val tzDir = listDir("/sys/class/thermal")
            val tzNames = if (tzDir != null) {
                tzDir.filter { THERMAL_ZONE_DIR_REGEX.matches(it) }
                    .sortedBy { it.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            } else {
                listOf("thermal_zone0", "thermal_zone1", "thermal_zone2", "thermal_zone3")
            }

            val tzSensors = mutableListOf<ThermalSensor>()
            for (zone in tzNames) {
                val base = "/sys/class/thermal/$zone"
                val tempC = parseMillidegreesCelsius(readFile("$base/temp")) ?: continue
                val rawType = readFile("$base/type")?.trim()?.takeIf { it.isNotEmpty() } ?: zone
                tzSensors += ThermalSensor(
                    id = zone,
                    rawName = rawType,
                    label = friendlyThermalLabel(rawType),
                    tempCelsius = tempC
                )
            }

            if (hwmonSensors.isEmpty()) return tzSensors

            // Deduplicate CPU sensor if both hwmon and thermal_zone report one; prefer hwmon.
            val seenCpu = hwmonSensors.any { isCpuLabel(it.label) }
            val extra = if (seenCpu) tzSensors.filterNot { isCpuLabel(it.label) } else {
                var keptCpu = false
                tzSensors.filter { s ->
                    if (!isCpuLabel(s.label)) true
                    else if (!keptCpu) { keptCpu = true; true }
                    else false
                }
            }
            return hwmonSensors + extra
        }
    }

    private suspend fun send(ubusObject: String, method: String, args: JsonObject): JsonObject {
        check(isAllowedRead(ubusObject, method, args)) { "Read-only screens may not call $ubusObject $method" }
        return caller.call(ubusObject, method, args)
    }

    /** A call the screen needs. Failures become [FeatureLoadException]. */
    suspend fun required(ubusObject: String, method: String, args: JsonObject = buildJsonObject {}, sessionGate: Boolean = false): JsonObject =
        try {
            send(ubusObject, method, args)
        } catch (e: IOException) {
            if (!sessionGate && (e is UbusError.AccessDenied || e is UbusError.SessionExpired)) {
                try {
                    send("system", "board", buildJsonObject {})
                } catch (gateErr: IOException) {
                    if (isConnectionProblem(gateErr)) throw FeatureLoadException.Unreachable(gateErr)
                    if (gateErr is UbusError.AccessDenied || gateErr is UbusError.SessionExpired) {
                        throw FeatureLoadException.SessionExpired()
                    }
                }
            }
            throw classify(e, sessionGate)
        }

    /** A call the screen can live without: null when the router lacks or refuses it. Connection loss still fails. */
    suspend fun optional(ubusObject: String, method: String, args: JsonObject = buildJsonObject {}): JsonObject? =
        try {
            send(ubusObject, method, args)
        } catch (e: IOException) {
            val failure = classify(e, sessionGate = false)
            if (failure is FeatureLoadException.Unreachable) throw failure
            null
        }

    suspend fun uci(config: String, required: Boolean = true, sessionGate: Boolean = false): UciConfig? {
        val args = buildJsonObject { put("config", config) }
        val reply = if (required) required("uci", "get", args, sessionGate) else optional("uci", "get", args) ?: return null
        return UciConfig.parse(reply)
    }

    suspend fun readFile(path: String): String? {
        val reply = optional("file", "read", buildJsonObject { put("path", path) }) ?: return null
        return (reply["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    suspend fun listDir(path: String): List<String>? {
        val reply = optional("file", "list", buildJsonObject { put("path", path) }) ?: return null
        val entries = reply["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { entry ->
            ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    /** Reads hardware/thermal temperature sensors from sysfs, or empty list if unavailable. */
    suspend fun readThermals(): List<ThermalSensor> = readThermalsWith(::listDir, ::readFile)

    /** Reads software/hardware flow offloading flags from UCI `firewall` `@defaults`. */
    suspend fun readFlowOffloading(): FlowOffloadingStatus? {
        val firewall = uci("firewall", required = false) ?: return null
        val defaults = firewall.ofType("defaults").firstOrNull() ?: return null
        return FlowOffloadingStatus(
            software = defaults.flag("flow_offloading"),
            hardware = defaults.flag("flow_offloading_hw")
        )
    }

    /** Runs one of [EXEC_READS]. Null when the command is missing or not permitted on the router. */
    suspend fun exec(command: String, params: List<String>): ExecResult? {
        val args = buildJsonObject {
            put("command", command)
            put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
        }
        val reply = optional("file", "exec", args) ?: return null
        return ExecResult(
            code = (reply["code"] as? JsonPrimitive)?.intOrNull ?: 0,
            stdout = (reply["stdout"] as? JsonPrimitive)?.content ?: "",
            stderr = (reply["stderr"] as? JsonPrimitive)?.content ?: ""
        )
    }

    /** Tries each command in turn; returns the first that printed something (or exited 0). */
    suspend fun execFirst(vararg commands: Pair<String, List<String>>): ExecResult? {
        for ((command, params) in commands) {
            val result = exec(command, params) ?: continue
            if (result.code == 0 || result.stdout.isNotBlank()) return result
        }
        return null
    }

    /** Any signed-in session may read the board; "access denied" here means the session ended. */
    suspend fun gate() {
        required("system", "board", sessionGate = true)
    }

    suspend fun readLog(): JsonObject? = optional("log", "read", buildJsonObject {
        put("lines", LOG_LINES)
        put("stream", false)
    })
}
