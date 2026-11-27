/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.router

import com.shaazifi.app.ui.theme.PillTone

/**
 * Read-only Wi-Fi extras (WPS/WDS, MAC filtering, client isolation and guest VLANs) and the
 * narrowly scoped change plans for them.
 *
 * Nothing here talks to the router: the plans are pure and go through the normal
 * plan -> confirm -> staged UCI write path like every other native change. Only the options
 * named in the [UciOp.Set] values below are ever written; the writer allow-list is extended
 * separately when this feature is wired into a screen.
 */
object WifiExtras {

    private val SECTION_ID = Regex("^[A-Za-z0-9_]{1,32}$")
    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    private val VLAN = Regex("^[0-9]+$")
    private val MAC_SPLIT = Regex("[\\s,]+")

    private val MODES = setOf("disabled", "allow", "deny")
    private const val MAX_MAC_ENTRIES = 64
    private val FLAG_OPTIONS = listOf("wps_pushbutton", "wds", "wps_label", "wps_pin")

    private val ON = ViewPill("On", PillTone.Ok)
    private val OFF = ViewPill("Off", PillTone.Off)
    private fun onOff(on: Boolean) = if (on) ON else OFF

    private const val WIFI_NOTE =
        "The router applies Wi-Fi settings under a 120-second undo timer. The app confirms only if the router stays reachable."
    private const val FILTER_NOTE =
        "MAC filtering takes effect when the router applies the change. Keep this device's address in the allow list if you use allow mode."
    private const val VLAN_NOTE =
        "The VLAN applies to the guest network when the router commits the change, under the normal undo timer."

    private fun ready(title: String, line: ChangeLine, op: UciOp, note: String): PlanResult =
        PlanResult.Ready(ChangePlan(title = title, lines = listOf(line), ops = listOf(op), note = note))

    private fun invalid(message: String): PlanResult = PlanResult.Invalid(message)

    // ------------------------------------------------------------------ Read-only panel

    /** A view of wireless options the main Wi-Fi screens do not otherwise expose. */
    fun panel(wifi: UciConfig): FeatureView {
        val ifaces = wifi.ofType("wifi-iface")

        // One row per option per interface; the pill carries the UCI boolean state.
        val wpsRows = ifaces.flatMap { s ->
            FLAG_OPTIONS.map { name ->
                ViewRow(
                    label = name,
                    value = s.opt(name) ?: "0",
                    sub = s.id,
                    pill = onOff(s.flag(name))
                )
            }
        }

        // A summary row for an explicitly configured filter, then one mono row per MAC.
        val macRows = ifaces.flatMap { s ->
            val mode = s.opt("macfilter")?.takeIf { it.isNotBlank() }
            val entries = s.list("maclist")
            val modeRows = if (mode != null || entries.isNotEmpty()) {
                listOf(ViewRow("macfilter", mode ?: "disabled", sub = s.id))
            } else {
                emptyList()
            }
            modeRows + entries.map { ViewRow("maclist", it, sub = s.id, mono = true) }
        }

        val apRows = ifaces.filter { s ->
            s.opt("mode").let { it == null || it.equals("ap", ignoreCase = true) }
        }.map { s ->
            ViewRow(
                label = "isolate",
                value = s.opt("isolate") ?: "0",
                sub = s.id,
                pill = onOff(s.flag("isolate"))
            )
        }

        val guestRows = ifaces.filter { it.isGuest() }.flatMap { s ->
            listOf(
                ViewRow("vlan", s.opt("vlan") ?: "Not set", sub = s.id, mono = true),
                ViewRow("network", s.list("network").joinToString(", ").ifBlank { "Not set" }, sub = s.id)
            )
        }

        return FeatureView(
            summary = "Read-only detail from the wireless config: WPS/WDS, MAC filtering, client isolation and guest VLANs.",
            sections = listOf(
                ViewSection("WPS / WDS", wpsRows, emptyText = "No Wi-Fi interface exposes WPS or WDS options."),
                ViewSection("MAC filtering", macRows, emptyText = "No MAC filtering is configured on any Wi-Fi interface."),
                ViewSection("Client isolation", apRows, emptyText = "No access point reports a client-isolation setting."),
                ViewSection("Guest VLAN", guestRows, emptyText = "No guest network has a VLAN or guest network binding.")
            ),
            note = "Read-only here: use LuCI or the Wi-Fi screens to change these settings."
        )
    }

    /** A guest interface is bound to a guest network, or named/labelled as a guest network. */
    private fun UciSection.isGuest(): Boolean =
        id.startsWith("guest", ignoreCase = true) ||
            opt("ssid")?.startsWith("guest", ignoreCase = true) == true ||
            list("network").any { it.contains("guest", ignoreCase = true) }

    // ------------------------------------------------------------------ Change plans

    fun setWps(sectionId: String, on: Boolean): PlanResult =
        flagPlan(sectionId, "wps_pushbutton", "WPS push button", on)

    fun setWds(sectionId: String, on: Boolean): PlanResult =
        flagPlan(sectionId, "wds", "WDS", on)

    fun setIsolate(sectionId: String, on: Boolean): PlanResult =
        flagPlan(sectionId, "isolate", "Client isolation", on)

    private fun flagPlan(sectionId: String, option: String, label: String, on: Boolean): PlanResult {
        if (!SECTION_ID.matches(sectionId)) return invalid("That Wi-Fi section name is not valid.")
        return ready(
            title = "Turn ${if (on) "on" else "off"} $label?",
            line = ChangeLine(label, if (on) "Off" else "On", if (on) "On" else "Off"),
            op = UciOp.Set("wireless", sectionId, mapOf(option to if (on) "1" else "0")),
            note = WIFI_NOTE
        )
    }

    /** Writes exactly `macfilter` and `maclist`; no other wireless option is touched. */
    fun setMacFilter(sectionId: String, mode: String, macs: String): PlanResult {
        if (!SECTION_ID.matches(sectionId)) return invalid("That Wi-Fi section name is not valid.")
        if (mode !in MODES) return invalid("MAC filtering must be disabled, allow or deny.")
        val entries = macs.split(MAC_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.size > MAX_MAC_ENTRIES) return invalid("At most $MAX_MAC_ENTRIES MAC addresses can be listed.")
        if (entries.any { !MAC.matches(it) }) {
            return invalid("Enter MAC addresses like AA:BB:CC:DD:EE:FF, separated by commas or spaces.")
        }
        if (entries.isEmpty() && mode != "disabled") {
            return invalid("Add at least one MAC address, or choose disabled.")
        }
        return ready(
            title = "Set MAC filtering to $mode?",
            line = ChangeLine(
                "MAC filtering",
                "",
                "$mode (${entries.size} ${if (entries.size == 1) "address" else "addresses"})"
            ),
            op = UciOp.Set(
                "wireless",
                sectionId,
                mapOf("macfilter" to mode, "maclist" to entries)
            ),
            note = FILTER_NOTE
        )
    }

    /** VLAN IDs are 1..4094 (0 and 4095 are reserved in 802.1Q). */
    fun setGuestVlan(sectionId: String, vlan: String): PlanResult {
        if (!SECTION_ID.matches(sectionId)) return invalid("That Wi-Fi section name is not valid.")
        if (!VLAN.matches(vlan)) return invalid("The VLAN must be a number from 1 to 4094.")
        val id = vlan.toIntOrNull() ?: return invalid("The VLAN must be a number from 1 to 4094.")
        if (id !in 1..4094) return invalid("The VLAN must be a number from 1 to 4094.")
        return ready(
            title = "Set the guest VLAN to $vlan?",
            line = ChangeLine("Guest VLAN", "", vlan),
            op = UciOp.Set("wireless", sectionId, mapOf("vlan" to vlan)),
            note = VLAN_NOTE
        )
    }
}
