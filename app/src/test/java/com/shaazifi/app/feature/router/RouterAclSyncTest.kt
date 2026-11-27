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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps docs/router/shaazifi.json in step with the app's own read lists. rpcd resolves
 * symlinks before applying the ACL (see docs/router/README.md), so both the /sys/class
 * and the resolved /sys/devices temperature patterns must be present.
 */
class RouterAclSyncTest {

    /** Tests run with the module dir as cwd; also accept the repo root (see AlertRulesTest). */
    private fun acl(): JsonObject {
        val file = listOf(
            File("docs/router/shaazifi.json"),
            File("../docs/router/shaazifi.json")
        ).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    /** shaazifi.<section>.file maps a path/command pattern to an array of permission strings. */
    private fun fileSection(root: JsonObject, section: String): Map<String, List<String>> {
        val obj = root["shaazifi"]?.jsonObject
            ?.get(section)?.jsonObject
            ?.get("file")?.jsonObject
            ?: error("shaazifi.json has no shaazifi.$section.file object")
        return obj.mapValues { (_, value) ->
            (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content }
                ?: emptyList()
        }
    }

    private fun assertGrants(section: Map<String, List<String>>, pattern: String, permission: String) {
        val granted = section[pattern]
        assertTrue("shaazifi.json is missing ACL entry '$pattern'", granted != null)
        assertTrue("ACL entry '$pattern' must grant '$permission'", granted!!.contains(permission))
    }

    /** Exact pattern, or a trailing-`*` ACL pattern that the command is a prefix of. */
    private fun isCovered(command: String, patterns: Set<String>): Boolean =
        command in patterns || patterns.any { pattern ->
            pattern.endsWith("*") && command.startsWith(pattern.dropLast(1).trimEnd())
        }

    @Test
    fun readFilePathsAreGranted() {
        val read = fileSection(acl(), "read")
        listOf(
            "/proc/stat",
            "/etc/crontabs/root",
            "/sys/class/hwmon/hwmon*/temp*_input",
            "/sys/devices/virtual/thermal/thermal_zone*/hwmon*/temp*_input",
            "/sys/devices/platform/soc/*/ieee80211/phy*/hwmon*/temp*_input"
        ).forEach { assertGrants(read, it, "read") }
    }

    @Test
    fun readExecCommandsAreGranted() {
        val read = fileSection(acl(), "read")
        listOf(
            "/bin/ps -w",
            "/sbin/ip -4 route show",
            "/sbin/ip -6 route show",
            "/usr/sbin/nft list ruleset",
            "/usr/bin/led-nightmode check",
            "/bin/dmesg -r"
        ).forEach { assertGrants(read, it, "exec") }
    }

    @Test
    fun newUciConfigsAndCronWriteAreGranted() {
        val root = acl()
        val readUci = (root["shaazifi"]?.jsonObject?.get("read")?.jsonObject?.get("uci") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val writeUci = (root["shaazifi"]?.jsonObject?.get("write")?.jsonObject?.get("uci") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        listOf("adguardhome", "nlbwmon", "wireless").forEach {
            assertTrue("read.uci missing $it", readUci.contains(it))
        }
        assertTrue("write.uci missing wireless", writeUci.contains("wireless"))
        val writeUbus = root["shaazifi"]?.jsonObject?.get("write")?.jsonObject
            ?.get("ubus")?.jsonObject?.get("file") as? JsonArray
        assertTrue("write.ubus.file missing write", writeUbus?.mapNotNull { (it as? JsonPrimitive)?.content }?.contains("write") == true)
        assertGrants(fileSection(root, "write"), "/etc/crontabs/root", "write")
    }

    @Test
    fun writeExecCommandsAreGranted() {
        val write = fileSection(acl(), "write")
        listOf(
            "/usr/bin/led-nightmode night",
            "/usr/bin/led-nightmode day",
            "/usr/bin/etherwake -i *"
        ).forEach { assertGrants(write, it, "exec") }
    }

    @Test
    fun everyAppReadIsGrantedByTheAcl() {
        val readPatterns = fileSection(acl(), "read").keys

        RouterReader.FILE_READS.forEach { path ->
            assertTrue("FILE_READS '$path' has no read.file ACL entry", isCovered(path, readPatterns))
        }
        RouterReader.EXEC_READS.forEach { (command, params) ->
            val fullCommand = (listOf(command) + params).joinToString(" ")
            assertTrue("EXEC_READS '$fullCommand' has no read.file ACL entry", isCovered(fullCommand, readPatterns))
        }
    }
}
