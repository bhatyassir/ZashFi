package com.shaazifi.app.feature.router

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/*
 * N1 safe-change core (D-20261116-B570D322 point 3; PROJECT.md invariant 10).
 *
 * Every change to router settings goes: plan -> shown to the user -> confirmed -> staged
 * (uci set/add/delete) -> `uci apply {rollback: true, timeout: 120}` -> service reload if
 * needed -> the app checks it can still reach the router -> `uci confirm`. If the router
 * cannot be reached, the app does not confirm and the router restores the old settings
 * by itself when the 120 s timer ends.
 *
 * Calls and their meaning are from rpcd's uci object as used by UnTangled
 * (reference/untangled/src/lib/ubus.ts uciSet/uciAdd/uciDelete/uciApplyRollback/uciConfirm,
 * firewallApply; read as facts) and docs/m2-findings.md section 1.4.
 */

/** One staged UCI operation. Values are strings or lists of strings, as UCI stores them. */
sealed interface UciOp {
    val config: String
    data class Set(override val config: String, val section: String, val values: Map<String, Any>) : UciOp
    /** [name] makes a named section (for example network `wwan`); null lets UCI pick one. */
    data class Add(override val config: String, val type: String, val values: Map<String, Any>, val name: String? = null) : UciOp
    data class Delete(override val config: String, val section: String) : UciOp
    /** Removes one option from a section (for example a parental schedule's times). */
    data class DeleteOption(override val config: String, val section: String, val option: String) : UciOp
    /**
     * Deletes every section of [type] whose options equal [match] (rpcd `uci delete` with `type` and
     * `match` and no section; openwrt/rpcd uci.c rpc_uci_delete / rpc_uci_match_section, read
     * 2026-11-17). Used only to remove Wi-Fi client links (mode sta): access points never match.
     */
    data class DeleteWhere(override val config: String, val type: String, val match: Map<String, String>) : UciOp
}

/** One line of the confirmation: what changes, from what, to what. */
data class ChangeLine(val label: String, val before: String, val after: String)

/**
 * A change the user is asked to confirm. [command] is for one-shot actions that are not
 * settings (wake a device): they run once, with no apply and no rollback.
 */
data class ChangePlan(
    val title: String,
    val lines: List<ChangeLine>,
    val ops: List<UciOp>,
    val reloadFirewall: Boolean = false,
    val note: String? = null,
    val command: Pair<String, List<String>>? = null,
    /** Service commands run after the apply (best effort), e.g. restarting miniupnpd. */
    val extraServices: List<Pair<String, List<String>>> = emptyList(),
    /**
     * The phone is expected to drop off (for example a Wi-Fi name or password change), so the
     * app keeps checking for longer before giving up, still inside the router's 120 s undo timer.
     */
    val longWait: Boolean = false,
    /**
     * After the change, the internet (WAN) must come back up with an address before the app keeps
     * it. Otherwise the app does not confirm and the router undoes the change within 120 s.
     */
    val checkInternet: Boolean = false,
    /** Phase 4: a one-shot router action (service, password, reboot): runs once, no apply, no undo. */
    val ubusCall: UbusCall? = null
) {
    /** All service commands to run after the apply, in order. */
    val services: List<Pair<String, List<String>>>
        get() = (if (reloadFirewall) listOf("/etc/init.d/firewall" to listOf("reload")) else emptyList()) + extraServices
}

sealed interface ChangeOutcome {
    /** Applied and confirmed (or the one-shot command ran). */
    data object Kept : ChangeOutcome
    /** The app lost the router after applying; it did not confirm, so the router undoes the change. */
    data object LostConnection : ChangeOutcome
    /** The router answered but the internet did not come back; the app did not confirm, so the router undoes the change. */
    data object InternetNotBack : ChangeOutcome
    data class Failed(val message: String) : ChangeOutcome
}

/**
 * The only path that changes router settings. Like [RouterReader], every call is checked
 * against a fixed list first; anything else is refused before sending.
 */
class RouterWriter(private val caller: UbusCaller) {

    companion object {
        /**
         * What native changes may touch, per config: which section types may be added, which
         * named options may be set, whether whole sections may be deleted, and which options
         * may be removed. Grows one feature at a time. Anything not listed is refused.
         */
        data class ConfigRule(
            val addTypes: Set<String> = emptySet(),
            val setOptions: Set<String>,
            val deleteSections: Boolean = false,
            val deleteOptions: Set<String> = emptySet(),
            /** Options allowed when adding a section; defaults to [setOptions]. */
            val addOptions: Set<String>? = null
        )

        private val FIREWALL_OPTIONS = setOf(
            "name", "src", "dest", "src_mac", "proto", "target", "enabled",
            "src_dport", "dest_ip", "dest_port", "weekdays", "start_time", "stop_time"
        )

        val RULES: Map<String, ConfigRule> = mapOf(
            "dhcp" to ConfigRule(addTypes = setOf("host"), setOptions = setOf("mac", "ip", "name", "start", "limit", "leasetime"), deleteSections = true),
            "firewall" to ConfigRule(
                addTypes = setOf("rule", "redirect"),
                // "network": a zone's network list (joining another Wi-Fi network adds wwan to the wan zone).
                // Zone policies and NAT (phase 4, owner-approved, with automatic undo).
                setOptions = FIREWALL_OPTIONS + setOf("network", "input", "output", "forward", "masq", "mtu_fix"),
                addOptions = FIREWALL_OPTIONS,
                deleteSections = true,
                deleteOptions = setOf("weekdays", "start_time", "stop_time")
            ),
            // Wi-Fi: on/off, name, password; radio channel, width, country and power (phase 2). New sections:
            // client links only (mode sta, checked below), deletable; never a new access point.
            "wireless" to ConfigRule(
                addTypes = setOf("wifi-iface"),
                setOptions = setOf("disabled", "ssid", "key", "channel", "htmode", "country", "txpower"),
                addOptions = setOf("device", "mode", "network", "ssid", "encryption", "key"),
                deleteOptions = setOf("txpower")
            ),
            "system" to ConfigRule(setOptions = setOf("hostname", "zonename", "timezone")),
            "minidlna" to ConfigRule(setOptions = setOf("enabled")),
            "adblock" to ConfigRule(setOptions = setOf("adb_enabled")),
            "banip" to ConfigRule(setOptions = setOf("ban_enabled")),
            "openvpn" to ConfigRule(setOptions = setOf("enabled")),
            "upnpd" to ConfigRule(setOptions = setOf("enabled", "enable_upnp", "enable_natpmp")),
            "ddns" to ConfigRule(setOptions = setOf("enabled")),
            "sqm" to ConfigRule(setOptions = setOf("enabled", "download", "upload"))
        )

        val WRITABLE_CONFIGS: Set<String> get() = RULES.keys

        /** Service commands allowed after an apply. All are in UnTangled's ACL. */
        private val SERVICES: Set<Pair<String, List<String>>> = setOf(
            "/etc/init.d/firewall" to listOf("reload"),
            "/etc/init.d/miniupnpd" to listOf("restart"),
            "/etc/init.d/miniupnpd" to listOf("stop"),
            "/etc/init.d/miniupnpd" to listOf("enable"),
            "/etc/init.d/ddns" to listOf("reload"),
            "/etc/init.d/sqm" to listOf("restart"),
            "/etc/init.d/minidlna" to listOf("restart"),
            "/etc/init.d/minidlna" to listOf("stop"),
            "/etc/init.d/minidlna" to listOf("enable")
        )

        fun isAllowedService(command: String, params: List<String>): Boolean = (command to params) in SERVICES

        /** Internet settings that may be changed on the `wan` interface. Never `disabled`, never the LAN. */
        private val WAN_OPTIONS = setOf("proto", "ipaddr", "netmask", "gateway", "dns", "peerdns", "username", "password")

        /**
         * The network config is limited by section: `wan` internet settings; `wan6` and other
         * tunnels (for example WireGuard) only on/off; `lan` and `loopback` never.
         */
        private fun networkSetAllowed(section: String, keys: Set<String>): Boolean = when (section) {
            "lan", "loopback" -> false
            "wan" -> WAN_OPTIONS.containsAll(keys)
            else -> keys == setOf("disabled")
        }

        const val ROLLBACK_SECONDS = 120

        private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        fun isAllowedWrite(ubusObject: String, method: String, args: JsonObject): Boolean {
            fun str(k: String) = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val rule = str("config")?.let { RULES[it] }
            val valueKeys = (args["values"] as? JsonObject)?.keys
            when (ubusObject to method) {
                "uci" to "set" -> {
                    if (args.keys != setOf("config", "section", "values") || valueKeys.isNullOrEmpty()) return false
                    val section = str("section") ?: return false
                    if (str("config") == "network") return networkSetAllowed(section, valueKeys)
                    return rule != null && rule.setOptions.containsAll(valueKeys)
                }
                "uci" to "add" -> {
                    val values = args["values"] as? JsonObject ?: return false
                    if (str("config") == "network" && str("type") == "route") {
                        // Phase 4 static routes: these options only, interface and target required.
                        return args.keys == setOf("config", "type", "values") && values.keys.containsAll(setOf("interface", "target")) &&
                            setOf("interface", "target", "gateway", "metric").containsAll(values.keys)
                    }
                    if (str("config") == "network") {
                        // Only the client-link network for joining another Wi-Fi network, DHCP only.
                        return args.keys == setOf("config", "type", "name", "values") && str("type") == "interface" &&
                            str("name") == WifiRadio.CLIENT_NETWORK && values.keys == setOf("proto") &&
                            (values["proto"] as? JsonPrimitive)?.content == "dhcp"
                    }
                    if (args.keys != setOf("config", "type", "values") || rule == null || str("type") !in rule.addTypes) return false
                    if (str("config") == "wireless" && (values["mode"] as? JsonPrimitive)?.content != "sta") return false
                    return valueKeys != null && (rule.addOptions ?: rule.setOptions).containsAll(valueKeys)
                }
                "uci" to "delete" -> {
                    if (args.keys == setOf("config", "type", "match") && str("config") == "network") {
                        // Phase 4: one static route, matched by both its interface and its target.
                        val match = args["match"] as? JsonObject ?: return false
                        return str("type") == "route" && match.keys == setOf("interface", "target")
                    }
                    if (str("config") == "network") return args.keys == setOf("config", "section") && str("section") == WifiRadio.CLIENT_NETWORK
                    if (args.keys == setOf("config", "type", "match")) {
                        // Only "remove Wi-Fi client links": wireless wifi-iface sections whose mode is sta.
                        val match = args["match"] as? JsonObject ?: return false
                        return str("config") == "wireless" && str("type") == "wifi-iface" && match.keys == setOf("mode") &&
                            (match["mode"] as? JsonPrimitive)?.content == "sta"
                    }
                    if (rule == null || str("section") == null) return false
                    if (args.keys == setOf("config", "section")) return rule.deleteSections
                    if (args.keys == setOf("config", "section", "option")) return str("option") in rule.deleteOptions
                    return false
                }
                "uci" to "revert" -> return args.keys == setOf("config") && (str("config") in WRITABLE_CONFIGS || str("config") == "network")
                "uci" to "apply" -> return args.keys == setOf("rollback", "timeout") &&
                    (args["rollback"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (args["timeout"] as? JsonPrimitive)?.intOrNull == ROLLBACK_SECONDS
                "uci" to "confirm", "uci" to "rollback" -> return args.isEmpty()
                "system" to "board" -> return args.isEmpty()
                "system" to "reboot" -> return args.isEmpty()
                "rc" to "init" -> return args.keys == setOf("name", "action") &&
                    Phase4.isAllowedServiceAction(str("name") ?: return false, str("action") ?: return false)
                "luci" to "setPassword" -> return args.keys == setOf("username", "password") && str("username") == "root" &&
                    !str("password").isNullOrEmpty()
                "network.interface" to "dump" -> return args.isEmpty()
                "file" to "exec" -> {
                    val command = str("command") ?: return false
                    val params = (args["params"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: return false } ?: return false
                    if (args.keys != setOf("command", "params")) return false
                    if (isAllowedService(command, params)) return true
                    if (command == "/usr/bin/etherwake" || command == "/usr/bin/ether-wake") {
                        return params.size == 3 && params[0] == "-i" && params[1] == "br-lan" && MAC.matches(params[2])
                    }
                    return false
                }
            }
            return false
        }

        private fun toJson(values: Map<String, Any>) = buildJsonObject {
            for ((k, v) in values) {
                when (v) {
                    is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
                    else -> put(k, v.toString())
                }
            }
        }

        fun argsFor(op: UciOp): Pair<String, JsonObject> = when (op) {
            is UciOp.Set -> "set" to buildJsonObject { put("config", op.config); put("section", op.section); put("values", toJson(op.values)) }
            is UciOp.Add -> "add" to buildJsonObject {
                put("config", op.config); put("type", op.type); op.name?.let { put("name", it) }; put("values", toJson(op.values))
            }
            is UciOp.Delete -> "delete" to buildJsonObject { put("config", op.config); put("section", op.section) }
            is UciOp.DeleteOption -> "delete" to buildJsonObject { put("config", op.config); put("section", op.section); put("option", op.option) }
            is UciOp.DeleteWhere -> "delete" to buildJsonObject {
                put("config", op.config); put("type", op.type); put("match", buildJsonObject { op.match.forEach { (k, v) -> put(k, v) } })
            }
        }
    }

    suspend fun send(ubusObject: String, method: String, args: JsonObject): JsonObject {
        check(isAllowedWrite(ubusObject, method, args)) { "Changes may not call $ubusObject $method" }
        return caller.call(ubusObject, method, args)
    }
}

/** Runs a confirmed [ChangePlan] with the router's rollback safety net. */
class SafeChangeRunner(
    private val writer: RouterWriter,
    private val probeAttempts: Int = 10,
    private val probeDelayMs: Long = 3_000L,
    /** About 100 s at 3 s apart: inside the router's 120 s undo timer, with time for the phone to rejoin Wi-Fi. */
    private val longWaitAttempts: Int = 33,
    /** About 60 s at 3 s apart for the internet to come back after a WAN change. */
    private val internetAttempts: Int = 20
) {
    suspend fun run(plan: ChangePlan): ChangeOutcome {
        plan.ubusCall?.let { call ->
            return try {
                val reply = writer.send(call.obj, call.method, buildJsonObject { call.args.forEach { (k, v) -> put(k, v) } })
                // luci setPassword answers {result: true/false}; other actions answer nothing on success.
                if ((reply["result"] as? JsonPrimitive)?.booleanOrNull == false) ChangeOutcome.Failed("The router refused the change.")
                else ChangeOutcome.Kept
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // A reboot may cut the connection before the answer arrives.
                if (call.method == "reboot") ChangeOutcome.Kept else ChangeOutcome.Failed(describe(e))
            }
        }
        plan.command?.let { (command, params) ->
            return try {
                val reply = writer.send("file", "exec", buildJsonObject {
                    put("command", command)
                    put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
                })
                val code = (reply["code"] as? JsonPrimitive)?.intOrNull ?: 0
                if (code == 0) ChangeOutcome.Kept else ChangeOutcome.Failed((reply["stderr"] as? JsonPrimitive)?.content?.ifBlank { null } ?: "The command ended with code $code.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ChangeOutcome.Failed(describe(e))
            }
        }

        val staged = linkedSetOf<String>()
        try {
            for (op in plan.ops) {
                staged += op.config
                val (method, args) = RouterWriter.argsFor(op)
                writer.send("uci", method, args)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            revert(staged)
            return ChangeOutcome.Failed(describe(e))
        }

        try {
            writer.send("uci", "apply", buildJsonObject {
                put("rollback", true)
                put("timeout", RouterWriter.ROLLBACK_SECONDS)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            revert(staged)
            return ChangeOutcome.Failed(describe(e))
        }

        // Services that need a nudge after the apply (fw4 rules, miniupnpd, ddns, sqm), as UnTangled does. Best effort.
        for ((command, params) in plan.services) {
            try {
                writer.send("file", "exec", buildJsonObject {
                    put("command", command)
                    put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
            }
        }

        // Only keep the change once the app can still reach the router.
        repeat(if (plan.longWait) longWaitAttempts else probeAttempts) { attempt ->
            if (attempt > 0) delay(probeDelayMs)
            val reachable = try {
                writer.send("system", "board", buildJsonObject {})
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                false
            }
            if (reachable) {
                if (plan.checkInternet && !internetCameBack()) return ChangeOutcome.InternetNotBack
                return try {
                    writer.send("uci", "confirm", buildJsonObject {})
                    ChangeOutcome.Kept
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    ChangeOutcome.LostConnection
                }
            }
        }
        return ChangeOutcome.LostConnection
    }

    /** True once `network.interface dump` shows the WAN up with an IPv4 address. */
    private suspend fun internetCameBack(): Boolean {
        repeat(internetAttempts) { attempt ->
            if (attempt > 0) delay(probeDelayMs)
            val up = try {
                val wan = com.shaazifi.app.feature.home.HomeParsers.parseWan(writer.send("network.interface", "dump", buildJsonObject {}))
                wan.up && wan.ipv4 != null
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                false
            }
            if (up) return true
        }
        return false
    }

    private suspend fun revert(configs: Set<String>) {
        for (config in configs) {
            try {
                writer.send("uci", "revert", buildJsonObject { put("config", config) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
            }
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is UbusError.AccessDenied -> "The router did not allow this change."
        is UbusError.NetworkError, is UbusError.HttpError -> "Lost the connection to the router. Nothing was changed."
        is UbusError.RpcError -> "The router refused the change (code ${e.code})."
        else -> "The change did not go through: ${e.message ?: e::class.simpleName}"
    }
}

sealed interface ChangeUiState {
    data object Idle : ChangeUiState
    data class Confirming(val plan: ChangePlan) : ChangeUiState
    data class Running(val plan: ChangePlan) : ChangeUiState
    data class Done(val outcome: ChangeOutcome) : ChangeUiState
}

/**
 * Screen-side state for one change at a time. [request] only shows the plan; nothing is
 * sent to the router until [confirm]. Tested in SafeChangeTest.
 */
class ChangeController(
    private val runPlan: suspend (ChangePlan) -> ChangeOutcome,
    private val onKept: suspend () -> Unit = {},
    /** Phase 5: every confirmed change and its result (for the activity log). */
    private val onDone: suspend (ChangePlan, ChangeOutcome) -> Unit = { _, _ -> }
) {
    constructor(
        runner: SafeChangeRunner,
        onKept: suspend () -> Unit = {},
        onDone: suspend (ChangePlan, ChangeOutcome) -> Unit = { _, _ -> }
    ) : this(runner::run, onKept, onDone)

    private val _state = MutableStateFlow<ChangeUiState>(ChangeUiState.Idle)
    val state: StateFlow<ChangeUiState> = _state.asStateFlow()

    fun request(plan: ChangePlan) {
        if (_state.value is ChangeUiState.Running) return
        _state.value = ChangeUiState.Confirming(plan)
    }

    fun cancel() {
        if (_state.value is ChangeUiState.Confirming) _state.value = ChangeUiState.Idle
    }

    suspend fun confirm() {
        val plan = (_state.value as? ChangeUiState.Confirming)?.plan ?: return
        _state.value = ChangeUiState.Running(plan)
        val outcome = runPlan(plan)
        _state.value = ChangeUiState.Done(outcome)
        runCatching { onDone(plan, outcome) }
        if (outcome == ChangeOutcome.Kept) onKept()
    }

    fun dismiss() {
        if (_state.value is ChangeUiState.Done) _state.value = ChangeUiState.Idle
    }
}
