package com.shaazifi.app.feature.home

import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.router.RouterReader
import com.shaazifi.app.feature.router.ThermalSensor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Supplies readings for the Home screen. The real router and demo mode both implement it. */
interface HomeRepository {
    /** @throws HomeLoadException when the reading cannot be made. */
    suspend fun load(): HomeReading

    /**
     * Owner feedback (smooth, near real-time chart): only the internet port's byte counters, read
     * every second between full readings. Null when unknown; never throws for a refused call.
     */
    suspend fun loadCounters(): TrafficCounters? = null
}

/** One ubus call. Kept as an interface so tests can answer from saved replies. */
interface UbusCaller {
    suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject
}

/** Sends calls through the app's existing connection core (pinned certificate, address guard). */
class UbusClientCaller(
    private val ubusClient: UbusClient,
    private val endpointUrl: String,
    private val token: String
) : UbusCaller {
    override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
        ubusClient.call(
            endpointUrl = endpointUrl,
            token = token,
            ubusObject = ubusObject,
            method = method,
            args = args
        )
}

/**
 * Reads Home data from the router. READ-ONLY: it makes only the calls listed in
 * [isAllowedCall], the same ones UnTangled's own Network Map makes.
 */
class UbusHomeRepository(private val caller: UbusCaller) : HomeRepository {

    companion object {
        /** The only file Home ever reads on the router. */
        const val PROC_STAT_PATH = "/proc/stat"

        private val FIXED_CALLS = setOf(
            "system" to "board",
            "system" to "info",
            "file" to "read",
            "file" to "list",
            "network.interface" to "dump",
            "network.device" to "status",
            "network.wireless" to "status",
            "luci-rpc" to "getWirelessDevices",
            "luci-rpc" to "getHostHints",
            "luci-rpc" to "getDHCPLeases",
            "iwinfo" to "assoclist"
        )

        /** True for the read-only calls Home may make. Everything else is refused before sending. */
        fun isAllowedCall(ubusObject: String, method: String, args: JsonObject): Boolean {
            if (ubusObject.startsWith("hostapd.")) {
                return method == "get_clients" &&
                    HomeParsers.isSafeIfname(ubusObject.removePrefix("hostapd.")) &&
                    args.isEmpty()
            }
            if ((ubusObject to method) !in FIXED_CALLS) return false
            if (ubusObject == "file" && method == "read") {
                val path = args["path"] as? JsonPrimitive
                return args.size == 1 && path != null && path.isString &&
                    (path.content == PROC_STAT_PATH || RouterReader.isAllowedThermalReadPath(path.content))
            }
            if (ubusObject == "file" && method == "list") {
                val path = args["path"] as? JsonPrimitive
                return args.size == 1 && path != null && path.isString && RouterReader.isAllowedFileList(path.content)
            }
            if (ubusObject == "iwinfo") {
                val device = args["device"] as? JsonPrimitive
                return args.size == 1 && device != null && device.isString && HomeParsers.isSafeIfname(device.content)
            }
            return args.isEmpty()
        }
    }

    private val noArgs = buildJsonObject {}

    /** Cached board info for this repository session (static hardware/firmware identity). */
    @Volatile private var cachedBoard: BoardInfo? = null

    /** The internet port's device name from the last full reading. */
    @Volatile private var wanDevice: String? = null

    override suspend fun loadCounters(): TrafficCounters? {
        val device = wanDevice ?: return null
        return try {
            HomeParsers.parseDeviceCounters(send("network.device", "status"), device)
        } catch (e: IOException) {
            if (isConnectionProblem(e)) throw HomeLoadException.Unreachable()
            null
        }
    }

    private suspend fun send(ubusObject: String, method: String, args: JsonObject = noArgs): JsonObject {
        check(isAllowedCall(ubusObject, method, args)) { "Home may not call $ubusObject $method" }
        return caller.call(ubusObject, method, args)
    }

    private fun isConnectionProblem(e: IOException): Boolean =
        e !is UbusError || e is UbusError.NetworkError || e is UbusError.HttpError || e is UbusError.InvalidJson

    /**
     * A call the screen cannot work without.
     * [sessionGate] marks the first call: any signed-in session may make it, so
     * "access denied" there can only mean the session is no longer valid.
     * When [sessionGate] is false (including post-cache refreshes), `AccessDenied` or
     * `SessionExpired` probes `system.board` so an expired session is never misreported
     * as `NotPermitted` or `FeatureNotInstalled`.
     */
    private suspend fun required(ubusObject: String, method: String, sessionGate: Boolean): JsonObject {
        try {
            return send(ubusObject, method)
        } catch (e: IOException) {
            if (isConnectionProblem(e)) throw HomeLoadException.Unreachable()
            if (sessionGate) {
                throw when (e) {
                    is UbusError.SessionExpired, is UbusError.AccessDenied -> HomeLoadException.SessionExpired()
                    else -> HomeLoadException.FeatureNotInstalled()
                }
            }
            if (e is UbusError.AccessDenied || e is UbusError.SessionExpired) {
                try {
                    send("system", "board")
                } catch (gateErr: IOException) {
                    cachedBoard = null
                    if (isConnectionProblem(gateErr)) throw HomeLoadException.Unreachable()
                    if (gateErr is UbusError.AccessDenied || gateErr is UbusError.SessionExpired) {
                        throw HomeLoadException.SessionExpired()
                    }
                }
                throw if (e is UbusError.AccessDenied) {
                    HomeLoadException.NotPermitted()
                } else {
                    HomeLoadException.FeatureNotInstalled()
                }
            }
            throw HomeLoadException.FeatureNotInstalled()
        }
    }

    /** A call Home can live without. Returns null when the router refuses or lacks it. */
    private suspend fun optional(
        ubusObject: String,
        method: String,
        args: JsonObject = noArgs,
        onRefused: (UbusError) -> Unit = {}
    ): JsonObject? {
        return try {
            send(ubusObject, method, args)
        } catch (e: IOException) {
            if (isConnectionProblem(e)) throw HomeLoadException.Unreachable()
            onRefused(e as UbusError)
            null
        }
    }

    private suspend fun listThermalDir(path: String): List<String>? {
        val reply = optional("file", "list", buildJsonObject { put("path", path) }) ?: return null
        val entries = reply["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { entry ->
            ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    private suspend fun readThermalFile(path: String): String? {
        val reply = optional("file", "read", buildJsonObject { put("path", path) }) ?: return null
        return (reply["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    override suspend fun load(): HomeReading {
        val board = cachedBoard
            ?: HomeParsers.parseBoard(required("system", "board", sessionGate = true)).also { cachedBoard = it }
        val info = required("system", "info", sessionGate = false)
        val dump = required("network.interface", "dump", sessionGate = false)
        val wan = HomeParsers.parseWan(dump)
        wanDevice = wan.device.ifBlank { null }

        val procStat = optional("file", "read", buildJsonObject { put("path", PROC_STAT_PATH) })
        val procStatText = (procStat?.get("data") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cpu = procStatText?.let { HomeParsers.parseCpuSample(it) }

        val thermals: List<ThermalSensor> = RouterReader.readThermalsWith(::listThermalDir, ::readThermalFile)

        val deviceStatus = optional("network.device", "status")
        val counters = deviceStatus?.let { HomeParsers.parseDeviceCounters(it, wan.device) }

        val notices = mutableSetOf<HomeNotice>()
        var clientCallsRefused = 0
        var clientCallsDenied = 0
        val countRefusal: (UbusError) -> Unit = { error ->
            clientCallsRefused++
            if (error is UbusError.AccessDenied) clientCallsDenied++
        }
        val hints = optional("luci-rpc", "getHostHints", onRefused = countRefusal)
        val leases = optional("luci-rpc", "getDHCPLeases", onRefused = countRefusal)

        val clients: ClientSummary?
        var radios: List<RadioSummary> = emptyList()
        if (clientCallsRefused == 2) {
            // Neither list is available, so the device count is unknown, not zero.
            clients = null
            notices.add(
                if (clientCallsDenied > 0) HomeNotice.CLIENTS_NOT_PERMITTED else HomeNotice.CLIENTS_FEATURE_MISSING
            )
        } else {
            val wifi = readWifiClients()
            val wifiMacs = wifi.macs
            val wifiOk = wifi.ok
            radios = wifi.radios
            clients = HomeParsers.summarizeClients(
                hostHints = hints ?: noArgs,
                dhcpLeases = leases ?: noArgs,
                wifiMacs = wifiMacs,
                wifiOk = wifiOk
            )
        }

        return HomeReading(
            board = board,
            uptimeSec = HomeParsers.parseUptimeSec(info),
            memUsedPct = HomeParsers.parseMemUsedPct(info),
            cpu = cpu,
            wan = wan,
            clients = clients,
            wanCounters = counters,
            notices = notices,
            interfaces = HomeParsers.parseInterfaces(dump),
            radios = radios,
            thermals = thermals
        )
    }

    /**
     * Which devices are on Wi-Fi, and whether Wi-Fi answered at all.
     * Same order as UnTangled: `network.wireless status`, else
     * `luci-rpc getWirelessDevices`; then per access point `hostapd.<ifname>
     * get_clients`, else `iwinfo assoclist`.
     */
    private class WifiRead(val macs: Set<String>, val ok: Boolean, val radios: List<RadioSummary>)

    private suspend fun readWifiClients(): WifiRead {
        val status = optional("network.wireless", "status")
            ?: optional("luci-rpc", "getWirelessDevices")
            ?: return WifiRead(emptySet(), false, emptyList())
        val plan = HomeParsers.parseWirelessPlan(status)
        // Radios exist but none is an access point: nothing can be a Wi-Fi client here.
        if (!plan.wifiAbsent && plan.accessPoints == 0) return WifiRead(emptySet(), true, emptyList())
        val perIfname = mutableMapOf<String, Int>()

        val macs = mutableSetOf<String>()
        var answered = 0
        for (ifname in plan.ifnames) {
            val fromHostapd = optional("hostapd.$ifname", "get_clients")?.let { HomeParsers.parseHostapdClients(it) }
            if (fromHostapd != null) {
                macs.addAll(fromHostapd)
                perIfname[ifname] = fromHostapd.size
                answered++
                continue
            }
            val fromIwinfo = optional("iwinfo", "assoclist", buildJsonObject { put("device", ifname) })
            if (fromIwinfo != null) {
                val assoc = HomeParsers.parseIwinfoAssoc(fromIwinfo)
                macs.addAll(assoc)
                perIfname[ifname] = assoc.size
                answered++
            }
        }
        return WifiRead(macs, answered > 0, HomeParsers.parseRadios(status, perIfname))
    }
}
