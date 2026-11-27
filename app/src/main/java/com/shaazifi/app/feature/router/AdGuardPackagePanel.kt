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
import kotlinx.coroutines.CancellationException

/**
 * Read-only view of the OpenWrt AdGuard Home **package** config (`uci show adguardhome`).
 *
 * This is deliberately separate from the app's AdGuard screens, which talk to the HTTP API:
 * here we only report the settings stored on the router. Nothing here builds a form, a
 * change plan or a write call; a missing or refused config yields explanatory empty sections.
 */
object AdGuardPackagePanel {

    private const val CONFIG = "adguardhome"
    private const val NOTE =
        "These are the router package settings. Blocking rules and query logs are managed in the AdGuard tab."
    private const val MISSING = "Not set"

    suspend fun load(r: RouterReader): FeatureView {
        val config = try {
            r.uci(CONFIG, required = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        // The package normally has one `adguardhome` section. Other sections in the same
        // config are not package settings, so they are intentionally not shown here.
        val sections = config?.ofType(CONFIG).orEmpty()

        return FeatureView(
            summary = "AdGuard Home package settings on the router (read-only).",
            pill = ViewPill("Read-only", PillTone.Off),
            sections = listOf(
                ViewSection(
                    "Package",
                    rowsFor(sections, ::packageRows),
                    emptyText = "No AdGuard Home package settings were reported by this router."
                ),
                ViewSection(
                    "DNS",
                    rowsFor(sections, ::dnsRows),
                    emptyText = "No AdGuard Home DNS settings were reported by this router."
                )
            ),
            note = NOTE
        )
    }

    /** With several sections, the section id keeps each row's origin visible. */
    private fun rowsFor(
        sections: List<UciSection>,
        build: (UciSection) -> List<ViewRow>
    ): List<ViewRow> = sections.flatMap { section ->
        val rows = build(section)
        if (sections.size > 1) rows.map { it.copy(label = "${it.label} (${section.id})") } else rows
    }

    private fun packageRows(s: UciSection): List<ViewRow> = listOf(
        flagRow("Enabled", s, "enabled"),
        flagRow("Started", s, "started"),
        valueRow("Addresses", s, "addresses", list = true, mono = true),
        valueRow("Port", s, "port"),
        valueRow("HTTP port", s, "http_port"),
        valueRow("Configuration path", s, "configuration_path", mono = true),
        valueRow("Work directory", s, "work_dir", mono = true),
        valueRow("Query log memory", s, "querylog_size_memory"),
        valueRow("Statistics memory", s, "statistics_size_memory")
    )

    private fun dnsRows(s: UciSection): List<ViewRow> = listOf(
        valueRow("DNS port", s, "dns_port"),
        valueRow("DNS addresses", s, "dns_addresses", list = true, mono = true),
        valueRow("Local PTR upstreams", s, "local_ptr_upstreams", list = true, mono = true),
        valueRow("Bootstrap DNS", s, "bootstrap_dns", list = true, mono = true)
    )

    /** An absent flag stays "Not set" rather than being reported as disabled. */
    private fun flagRow(label: String, s: UciSection, key: String): ViewRow {
        val raw = s.opt(key)
        val on = s.flag(key)
        return ViewRow(
            label = label,
            value = when (raw) {
                null -> MISSING
                else -> if (on) "Yes" else "No"
            },
            pill = raw?.let { ViewPill(if (on) "On" else "Off", if (on) PillTone.Ok else PillTone.Off) }
        )
    }

    private fun valueRow(
        label: String,
        s: UciSection,
        key: String,
        list: Boolean = false,
        mono: Boolean = false
    ): ViewRow {
        val value = if (list) s.list(key).joinToString(", ") else s.opt(key).orEmpty()
        return ViewRow(label, value.takeIf { it.isNotBlank() } ?: MISSING, mono = mono)
    }
}
