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

import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.feature.home.UbusCaller
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the read-only LuCI Status/System pages in [RouterTools]. */
class RouterToolsTest {

    private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun reader(handler: (String, String, JsonObject) -> JsonObject): RouterReader =
        RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                handler(ubusObject, method, args)
        })

    private fun stringArg(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.content

    private fun execArgs(args: JsonObject): Pair<String, List<String>> {
        val command = stringArg(args, "command") ?: ""
        val params = (args["params"] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        return command to params
    }

    @Test
    fun inspectorShowsProcessesRoutesFirewallAndWifiChannels() {
        runTest {
            val r = reader { obj, method, args ->
                when {
                    obj == "file" && method == "exec" -> {
                        val (command, params) = execArgs(args)
                        j(
                            when {
                                command == "/bin/ps" ->
                                    """{"code":0,"stdout":"PID USER COMMAND\n1 root /sbin/init\n2 root [kthreadd]\n"}"""
                                command == "/sbin/ip" && params == listOf("-4", "route", "show") ->
                                    """{"code":0,"stdout":"default via 192.168.1.1 dev wan\n192.168.1.0/24 dev br-lan scope link\n"}"""
                                command == "/sbin/ip" && params == listOf("-6", "route", "show") ->
                                    """{"code":0,"stdout":"::/0 via fe80::1 dev wan\n"}"""
                                command == "/usr/sbin/nft" && params == listOf("list", "ruleset") ->
                                    """{"code":0,"stdout":"table inet filter {\n chain input { }\n}\n"}"""
                                else -> throw UbusError.ObjectNotFound()
                            }
                        )
                    }
                    obj == "luci-rpc" && method == "getWirelessDevices" ->
                        // "phy0" is not a UCI radio name and must be ignored; only radioN is queried.
                        j("""{"radio0":{},"radio1":{},"phy0":{}}""")
                    obj == "iwinfo" && method == "freqlist" -> j(
                        when (stringArg(args, "device")) {
                            "radio0" ->
                                """{"results":[{"channel":36,"band":5,"frequency":5180,"restricted":false},{"channel":44,"band":5,"frequency":5220}]}"""
                            "radio1" -> """{"results":[{"channel":1,"band":2,"frequency":2412}]}"""
                            else -> throw UbusError.ObjectNotFound()
                        }
                    )
                    else -> throw UbusError.ObjectNotFound()
                }
            }

            val view = RouterTools.inspector(r)
            assertEquals(
                listOf("Processes", "Routing table", "Firewall rules (nftables)", "Wi-Fi channels"),
                view.sections.map { it.heading }
            )

            val processes = view.sections[0]
            assertEquals(listOf("PID 1", "PID 2", "PID 3"), processes.rows.map { it.label })
            assertEquals(
                listOf("PID USER COMMAND", "1 root /sbin/init", "2 root [kthreadd]"),
                processes.rows.map { it.value }
            )

            val routes = view.sections[1]
            assertEquals(
                listOf("default via 192.168.1.1 dev wan", "192.168.1.0/24 dev br-lan scope link", "::/0 via fe80::1 dev wan"),
                routes.rows.map { it.value }
            )

            val firewall = view.sections[2]
            assertEquals(3, firewall.rows.size)
            assertEquals("table inet filter {", firewall.rows.first().value)

            val channels = view.sections[3]
            assertEquals(
                listOf("radio0: channel 36 (5180 MHz)", "radio0: channel 44 (5220 MHz)", "radio1: channel 1 (2412 MHz)"),
                channels.rows.map { it.value }
            )
            assertEquals(listOf("Channel 1", "Channel 2", "Channel 3"), channels.rows.map { it.label })
            assertTrue(channels.rows.all { it.mono })
        }
    }

    @Test
    fun freqlistEntriesWithoutFrequencyStillShowTheChannel() {
        runTest {
            val r = reader { obj, method, args ->
                when {
                    obj == "file" && method == "exec" -> j("""{"code":0,"stdout":""}""")
                    obj == "luci-rpc" && method == "getWirelessDevices" -> j("""{"radio0":{}}""")
                    obj == "iwinfo" && method == "freqlist" ->
                        j("""{"results":[{"channel":13,"band":2},{"channel":11,"band":2,"frequency":2462}]}""")
                    else -> throw UbusError.ObjectNotFound()
                }
            }
            val channels = RouterTools.inspector(r).sections.first { it.heading == "Wi-Fi channels" }
            assertEquals(
                listOf("radio0: channel 13", "radio0: channel 11 (2462 MHz)"),
                channels.rows.map { it.value }
            )
        }
    }

    @Test
    fun configurationShowsCronDnsNlbwAndLedActions() {
        runTest {
            val r = reader { obj, method, args ->
                when {
                    obj == "file" && method == "read" -> {
                        assertEquals("/etc/crontabs/root", stringArg(args, "path"))
                        j("""{"data":"# generated by the app\n0 2 * * * /usr/bin/led-nightmode night\n*/5 * * * * /usr/bin/check.sh\n"}""")
                    }
                    obj == "uci" && method == "get" -> j(
                        when (stringArg(args, "config")) {
                            "dhcp" ->
                                """{"values":{"cfg1":{".type":"dnsmasq",".index":0,"local_use":"1","local_domain":"lan","server":"1.1.1.1"}}}"""
                            "nlbwmon" ->
                                """{"values":{"main":{".type":"nlbwmon",".index":0,"database_directory":"/usr/lib/nlbwmon","commit_interval":"60"}}}"""
                            else -> throw UbusError.ObjectNotFound()
                        }
                    )
                    obj == "file" && method == "exec" -> {
                        assertEquals("/usr/bin/led-nightmode" to listOf("check"), execArgs(args))
                        j("""{"code":0,"stdout":"night\n"}""")
                    }
                    else -> throw UbusError.ObjectNotFound()
                }
            }

            val view = RouterTools.configuration(r)
            assertEquals(
                listOf("Scheduled tasks", "DNS (dnsmasq)", "Bandwidth monitor", "LED night mode"),
                view.sections.map { it.heading }
            )

            val cron = view.sections[0]
            assertEquals(
                listOf("0 2 * * * /usr/bin/led-nightmode night", "*/5 * * * * /usr/bin/check.sh"),
                cron.rows.map { it.value }
            )
            assertEquals("Job 1", cron.rows.first().label)

            val dns = view.sections[1]
            assertEquals(listOf("local_use", "local_domain", "server"), dns.rows.map { it.label })
            assertEquals(listOf("1", "lan", "1.1.1.1"), dns.rows.map { it.value })

            val nlbw = view.sections[2]
            assertEquals(listOf("database_directory", "commit_interval"), nlbw.rows.map { it.label })
            assertEquals(listOf("/usr/lib/nlbwmon", "60"), nlbw.rows.map { it.value })

            val led = view.sections[3]
            assertEquals("night", led.rows[0].value)
            assertEquals("Set night", led.rows[1].label)
            assertEquals("Set day", led.rows[2].label)
            val nightPlan = led.rows[1].extra!!.build()
            assertTrue(nightPlan is PlanResult.Ready)
            assertEquals("/usr/bin/led-nightmode" to listOf("night"), (nightPlan as PlanResult.Ready).plan.command)
            val dayPlan = led.rows[2].extra!!.build()
            assertTrue(dayPlan is PlanResult.Ready)
            assertEquals("/usr/bin/led-nightmode" to listOf("day"), (dayPlan as PlanResult.Ready).plan.command)
        }
    }

    @Test
    fun exportSettingsIncludesConfigSectionsAndEscapesSingleQuotes() {
        runTest {
            val r = reader { obj, method, args ->
                if (obj == "uci" && method == "get") {
                    j(
                        when (stringArg(args, "config")) {
                            "dhcp" ->
                                """{"values":{"cfg":{".type":"dnsmasq",".index":0,"local_domain":"lan","note":"it's fine"}}}"""
                            "system" ->
                                """{"values":{"@system[0]":{".type":"system",".index":0,"hostname":"router"}}}"""
                            else -> throw UbusError.ObjectNotFound()
                        }
                    )
                } else {
                    throw UbusError.ObjectNotFound()
                }
            }

            val text = RouterTools.exportSettings(r)
            assertTrue(text.startsWith("# ZashFi settings snapshot\n"))
            assertTrue(text.contains("# config dhcp\n"))
            assertTrue(text.contains("# config system\n"))
            assertTrue(text.contains("config 'dnsmasq' 'cfg'\n"))
            assertTrue(text.contains("option 'local_domain' 'lan'\n"))
            assertTrue(text.contains("option 'note' 'it'\\''s fine'\n"))
            assertTrue(text.contains("config 'system' '@system[0]'\n"))
            assertTrue(text.contains("option 'hostname' 'router'\n"))
        }
    }
}
