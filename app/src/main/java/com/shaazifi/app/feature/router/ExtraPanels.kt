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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read-only completeness panels for parts of LuCI's Firewall and VPN menus that the
 * native feature screens only cover in part. Nothing here builds a form, a change
 * plan or a write call: all data comes from [RouterReader]'s allow-listed reads.
 */
object ExtraPanels {

    private const val FIREWALL_URL = "/cgi-bin/luci/admin/network/firewall"
    private const val FIREWALL_CUSTOM_URL = "$FIREWALL_URL/custom"

    /**
     * The remaining firewall detail that is not editable on the port-forwarding screen:
     * non-simple NAT redirects, IP sets and the custom-rules text (view only).
     */
    suspend fun firewallExtras(r: RouterReader): FeatureView {
        val fw = r.uci("firewall")

        val natRules = fw?.ofType("redirect")?.filter { !isSimplePortForward(it) }.orEmpty()
        val natRows = natRules.map { s ->
            val name = s.opt("name")?.takeIf { it.isNotBlank() } ?: s.id
            val toDport = s.opt("to_dport") ?: s.opt("dest_port")
            val value = listOf(
                "src_dport=${s.opt("src_dport") ?: "-"}",
                "to_dport=${toDport ?: "-"}",
                "dest_ip=${s.opt("dest_ip") ?: "-"}"
            ).joinToString("  ")
            ViewRow(name, value, mono = true)
        }

        val ipSetRows = fw?.ofType("ipset").orEmpty().flatMap { set ->
            val name = set.opt("name")?.takeIf { it.isNotBlank() }
                ?: set.opt("comment")?.takeIf { it.isNotBlank() }
                ?: set.id
            val entries = set.list("entry")
            if (entries.isEmpty()) {
                listOf(ViewRow(name, "No entries", mono = true))
            } else {
                entries.map { ViewRow(name, it, mono = true) }
            }
        }

        return FeatureView(
            summary = "Read-only detail from LuCI's Firewall menu. Only simple port forwards are editable in the app.",
            pill = ViewPill("${natRules.size} extra NAT ${if (natRules.size == 1) "rule" else "rules"}", PillTone.Off),
            sections = listOf(
                ViewSection(
                    "NAT Rules",
                    natRows,
                    emptyText = "No extra NAT rules: every redirect is a simple port forward."
                ),
                ViewSection("IP Sets", ipSetRows, emptyText = "No IP sets are configured."),
                ViewSection(
                    "Custom Rules",
                    listOf(
                        ViewRow(
                            "Custom firewall rules",
                            "View-only in this app - edit in LuCI",
                            sub = FIREWALL_CUSTOM_URL
                        )
                    )
                )
            ),
            note = "These settings are read-only here. Open LuCI -> Network -> Firewall ($FIREWALL_URL) to change them."
        )
    }

    /** Mirrors FeatureMappers.portForwarding: those redirects already have an editable switch there. */
    private fun isSimplePortForward(s: UciSection): Boolean {
        val target = s.opt("target") ?: "DNAT"
        val dmz = s.opt("name") == "DMZ" || s.opt("name") == "dmz" || s.id == "dmz" ||
            (s.opt("proto") == "all" && s.opt("src_dport") == null)
        return target.equals("DNAT", ignoreCase = true) && !dmz && s.opt("src_dport") != null
    }

    /**
     * `tailscale status --json`, read-only. Tries /usr/bin then /usr/sbin (OpenWrt 25.x
     * installs the binary in either place). A missing command or malformed JSON gives an
     * explanatory empty section, never an exception.
     */
    suspend fun tailscaleDetails(r: RouterReader): FeatureView {
        val params = listOf("status", "--json")
        val result = r.execFirst(
            "/usr/bin/tailscale" to params,
            "/usr/sbin/tailscale" to params
        )
        val json = result?.stdout
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            ?.let { it as? JsonObject }

        if (json == null) {
            return FeatureView(
                summary = "Tailscale status is not available on this router.",
                sections = listOf(
                    ViewSection(
                        "Tailscale",
                        emptyList(),
                        emptyText = if (result == null) {
                            "Tailscale is not installed on this router (no status command)."
                        } else {
                            "Tailscale did not return a valid status. Check it in LuCI."
                        }
                    )
                ),
                note = "Read-only status from 'tailscale status --json'; no settings are changed here."
            )
        }

        val backendState = json["BackendState"].str() ?: "Unknown"
        val self = json["Self"].obj()
        val dnsName = self?.get("DNSName").str() ?: "Not set"
        val tailscaleIp = self?.get("TailscaleIPs").strings().firstOrNull() ?: "None"
        val peers = json["Peer"].obj()?.values?.mapNotNull { it.obj() }.orEmpty()

        val overview = listOf(
            ViewRow("Backend state", backendState),
            ViewRow("DNS name", dnsName, mono = true),
            ViewRow("Tailscale IP", tailscaleIp, mono = true)
        )
        val peerRows = peers.map { p ->
            val online = p["Online"].bool() == true
            val exitNode = p["ExitNode"].bool() == true || p["ExitNodeOption"].bool() == true
            val addresses = p["TailscaleIPs"].strings().joinToString(", ").ifBlank { "No address" }
            ViewRow(
                p["HostName"].str() ?: p["ID"].str() ?: "Machine",
                addresses,
                sub = "Online: ${if (online) "Yes" else "No"} - Exit node: ${if (exitNode) "Yes" else "No"}",
                mono = true,
                pill = ViewPill(if (online) "Online" else "Offline", if (online) PillTone.Ok else PillTone.Off)
            )
        }
        return FeatureView(
            summary = "Tailscale VPN status on the router (read-only).",
            sections = listOf(
                ViewSection("Tailscale", overview + peerRows, emptyText = "Tailscale reported no details.")
            ),
            note = "Read-only status from 'tailscale status --json'. Manage Tailscale in LuCI."
        )
    }

    // Lenient readers for the status JSON, kept local so this panel stays self-contained.
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let {
        it.booleanOrNull ?: it.content.let { c -> c == "1" || c.equals("true", ignoreCase = true) }
    }
    private fun JsonElement?.strings(): List<String> = when (this) {
        is JsonArray -> mapNotNull { it.str() }
        is JsonPrimitive -> listOf(content)
        else -> emptyList()
    }
}
