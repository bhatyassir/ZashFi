package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.router.RouterWriter
import com.shaazifi.app.feature.router.UciOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceChangesTest {

    private val tablet = Device("AA:BB:CC:DD:EE:01", "Kid's tablet", "192.168.1.57", true, Connection.WIFI_24, null, false)

    private fun plan(r: DeviceChangeResult) = (r as DeviceChangeResult.Ready).plan
    private fun error(r: DeviceChangeResult) = (r as DeviceChangeResult.Invalid).message

    @Test
    fun blockAddsARejectRuleForThisMacAndReloadsTheFirewall() {
        val p = plan(DeviceChanges.block(tablet))
        val op = p.ops.single() as UciOp.Add
        assertEquals("firewall", op.config)
        assertEquals("rule", op.type)
        assertEquals("AA:BB:CC:DD:EE:01", op.values["src_mac"])
        assertEquals("REJECT", op.values["target"])
        assertEquals("lan", op.values["src"])
        assertEquals("wan", op.values["dest"])
        assertTrue((op.values["name"] as String).startsWith("Block:"))
        assertTrue(p.reloadFirewall)
        assertEquals("Blocked", p.lines.single().after)

        val guestDevice = tablet.copy(network = DeviceNetwork("guest", "192.168.10.0/24", "guest"))
        val guestOp = plan(DeviceChanges.block(guestDevice)).ops.single() as UciOp.Add
        assertEquals("guest", guestOp.values["src"])
    }

    @Test
    fun unblockDeletesExactlyTheBlockRule() {
        val blocked = tablet.copy(blocked = true, blockId = "cfg0a92bd")
        val p = plan(DeviceChanges.unblock(blocked))
        assertEquals(UciOp.Delete("firewall", "cfg0a92bd"), p.ops.single())
        assertTrue(p.reloadFirewall)
        assertTrue(DeviceChanges.unblock(tablet) is DeviceChangeResult.Invalid)
    }

    @Test
    fun fixedAddressIsCheckedBeforeAnythingIsPlanned() {
        val p = plan(DeviceChanges.setFixedIp(tablet, " 192.168.1.80 ", routerIp = "192.168.1.1"))
        val op = p.ops.single() as UciOp.Add
        assertEquals("dhcp", op.config)
        assertEquals("host", op.type)
        assertEquals("192.168.1.80", op.values["ip"])
        assertEquals("AA:BB:CC:DD:EE:01", op.values["mac"])

        val existing = tablet.copy(fixedIp = "192.168.1.57", reserveId = "cfg11")
        assertEquals(UciOp.Set("dhcp", "cfg11", mapOf("ip" to "192.168.1.90", "mac" to "AA:BB:CC:DD:EE:01")),
            plan(DeviceChanges.setFixedIp(existing, "192.168.1.90", "192.168.1.1")).ops.single())

        assertTrue(error(DeviceChanges.setFixedIp(tablet, "8.8.8.8", "192.168.1.1")).contains("home network"))
        assertTrue(error(DeviceChanges.setFixedIp(tablet, "192.168.1.1", "192.168.1.1")).contains("router"))
        assertTrue(error(DeviceChanges.setFixedIp(tablet, "192.168.1.300", "192.168.1.1")).isNotBlank())
        assertTrue(error(DeviceChanges.setFixedIp(tablet, "192.168.1.255", "192.168.1.1")).isNotBlank())
        assertTrue(error(DeviceChanges.setFixedIp(tablet, "10.0.0.5", "192.168.1.1")).contains("192.168.1."))
    }

    @Test
    fun removingTheFixedAddressDeletesItsEntryOrOnlyIpWhenCustomNameExists() {
        val existing = tablet.copy(fixedIp = "192.168.1.57", reserveId = "cfg11")
        assertEquals(UciOp.Delete("dhcp", "cfg11"), plan(DeviceChanges.removeFixedIp(existing)).ops.single())

        val renamedAndFixed = existing.copy(customName = "Kids-Tablet")
        assertEquals(UciOp.DeleteOption("dhcp", "cfg11", "ip"), plan(DeviceChanges.removeFixedIp(renamedAndFixed)).ops.single())

        val renamedOnly = tablet.copy(reserveId = "cfg11", fixedIp = null, customName = "Kids-Tablet")
        assertTrue(DeviceChanges.removeFixedIp(renamedOnly) is DeviceChangeResult.Invalid)
        assertTrue(DeviceChanges.removeFixedIp(tablet) is DeviceChangeResult.Invalid)
    }

    @Test
    fun renameUsesAHostnameSafeName() {
        val op = plan(DeviceChanges.rename(tablet, "Kid's tablet 2")).ops.single() as UciOp.Add
        assertEquals("Kid-s-tablet-2", op.values["name"])
        val existing = tablet.copy(reserveId = "cfg11")
        assertEquals(UciOp.Set("dhcp", "cfg11", mapOf("name" to "Lounge-TV")), plan(DeviceChanges.rename(existing, "Lounge TV")).ops.single())
        assertTrue(DeviceChanges.rename(tablet, "  !!! ") is DeviceChangeResult.Invalid)
    }

    @Test
    fun wakeIsOneCommandTheWriterAllows() {
        val p = plan(DeviceChanges.wake(tablet))
        assertTrue(p.ops.isEmpty())
        val (command, params) = p.command!!
        assertEquals("/usr/bin/etherwake", command)
        assertEquals(listOf("-i", "br-lan", "AA:BB:CC:DD:EE:01"), params)
    }

    @Test
    fun everyPlannedOperationPassesTheWriterList() {
        val withIds = tablet.copy(blocked = true, blockId = "b1", fixedIp = "192.168.1.57", reserveId = "r1")
        val plans = listOf(
            DeviceChanges.block(tablet), DeviceChanges.unblock(withIds), DeviceChanges.setFixedIp(tablet, "192.168.1.80", "192.168.1.1"),
            DeviceChanges.setFixedIp(withIds, "192.168.1.81", "192.168.1.1"), DeviceChanges.removeFixedIp(withIds),
            DeviceChanges.rename(tablet, "TV"), DeviceChanges.rename(withIds, "TV")
        ).map { plan(it) }
        for (p in plans) for (op in p.ops) {
            val (method, args) = RouterWriter.argsFor(op)
            assertTrue("$op must be allowed", RouterWriter.isAllowedWrite("uci", method, args))
        }
    }
}
