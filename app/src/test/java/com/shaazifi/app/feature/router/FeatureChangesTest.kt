package com.shaazifi.app.feature.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureChangesTest {

    private fun plan(r: PlanResult) = (r as PlanResult.Ready).plan
    private fun error(r: PlanResult) = (r as PlanResult.Invalid).message

    private fun allowed(p: ChangePlan) = p.ops.all { op ->
        val (method, args) = RouterWriter.argsFor(op)
        RouterWriter.isAllowedWrite("uci", method, args)
    } && p.services.all { (cmd, params) -> RouterWriter.isAllowedService(cmd, params) }

    @Test
    fun writerOnlyChangesNamedSettings() {
        fun set(config: String, values: Map<String, Any>) = RouterWriter.argsFor(UciOp.Set(config, "s1", values)).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) }
        assertTrue(set("wireless", mapOf("disabled" to "1")))
        // Batch 2 deliberately allows the Wi-Fi name and password (typed by the owner, never read back);
        // everything else about Wi-Fi stays refused (see FeatureChangesBatch2Test.writerKeepsWifiChangesNarrow).
        assertFalse(set("wireless", mapOf("encryption" to "none")))
        assertFalse(set("wireless", mapOf("network" to "wan")))
        assertTrue(set("upnpd", mapOf("enabled" to "1", "enable_upnp" to "1", "enable_natpmp" to "1")))
        assertFalse(set("upnpd", mapOf("external_iface" to "x")))
        assertTrue(set("sqm", mapOf("enabled" to "1", "download" to "90000", "upload" to "18000")))
        assertFalse(set("sqm", mapOf("interface" to "eth0")))
        assertTrue(set("ddns", mapOf("enabled" to "0")))
        assertFalse(set("ddns", mapOf("password" to "x")))
        assertFalse(set("network", mapOf("proto" to "dhcp")))
        assertFalse(RouterWriter.argsFor(UciOp.Delete("wireless", "guest")).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) })
        assertFalse(RouterWriter.argsFor(UciOp.Add("firewall", "zone", mapOf("name" to "x"))).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) })
        assertTrue(RouterWriter.argsFor(UciOp.DeleteOption("firewall", "r1", "weekdays")).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) })
        assertFalse(RouterWriter.argsFor(UciOp.DeleteOption("firewall", "r1", "target")).let { (m, a) -> RouterWriter.isAllowedWrite("uci", m, a) })
        assertTrue(RouterWriter.isAllowedService("/etc/init.d/sqm", listOf("restart")))
        assertFalse(RouterWriter.isAllowedService("/etc/init.d/network", listOf("restart")))
    }

    @Test
    fun guestToggleChangesOnlyThatNetwork() {
        val p = plan(FeatureChanges.guestEnabled("guest_radio1", "Home-Guest", on = false))
        assertEquals(UciOp.Set("wireless", "guest_radio1", mapOf("disabled" to "1")), p.ops.single())
        assertTrue(p.note!!.contains("Wi-Fi"))
        assertTrue(allowed(p))
    }

    @Test
    fun portForwardIsValidatedAndOpensOnlyWhatWasAsked() {
        val p = plan(FeatureChanges.addPortForward("Camera", "8443", "192.168.1.70", "", "tcp", routerIp = "192.168.1.1"))
        val op = p.ops.single() as UciOp.Add
        assertEquals("redirect", op.type)
        assertEquals("8443", op.values["src_dport"])
        assertEquals("8443", op.values["dest_port"])
        assertEquals("192.168.1.70", op.values["dest_ip"])
        assertEquals("DNAT", op.values["target"])
        assertEquals("wan", op.values["src"])
        assertTrue(p.reloadFirewall)
        assertTrue(allowed(p))
        assertEquals(listOf("tcp", "udp"), (plan(FeatureChanges.addPortForward("Game", "25565", "192.168.1.12", "", "both", "192.168.1.1")).ops.single() as UciOp.Add).values["proto"])

        assertTrue(error(FeatureChanges.addPortForward("", "80", "192.168.1.5", "", "tcp", "192.168.1.1")).contains("name"))
        assertTrue(error(FeatureChanges.addPortForward("X", "0", "192.168.1.5", "", "tcp", "192.168.1.1")).contains("port"))
        assertTrue(error(FeatureChanges.addPortForward("X", "70000", "192.168.1.5", "", "tcp", "192.168.1.1")).contains("port"))
        assertTrue(error(FeatureChanges.addPortForward("X", "80", "8.8.8.8", "", "tcp", "192.168.1.1")).contains("home network"))
        assertTrue(error(FeatureChanges.addPortForward("X", "80", "192.168.1.1", "", "tcp", "192.168.1.1")).contains("router"))
        assertTrue(error(FeatureChanges.addPortForward("X", "80", "192.168.1.5", "", "icmp", "192.168.1.1")).isNotBlank())
        assertTrue(plan(FeatureChanges.addPortForward("Range", "6000-6010", "192.168.1.5", "", "udp", "192.168.1.1")).ops.isNotEmpty())
        assertTrue(error(FeatureChanges.addPortForward("Range", "6010-6000", "192.168.1.5", "", "udp", "192.168.1.1")).contains("port"))

        assertEquals(UciOp.Set("firewall", "r9", mapOf("enabled" to "0")), plan(FeatureChanges.redirectEnabled("r9", "Camera", false)).ops.single())
        assertEquals(UciOp.Delete("firewall", "r9"), plan(FeatureChanges.removeRedirect("r9", "Camera")).ops.single())
    }

    @Test
    fun dmzSetAndOff() {
        val p = plan(FeatureChanges.setDmz(null, "192.168.1.9", "192.168.1.1"))
        val op = p.ops.single() as UciOp.Add
        assertEquals("DMZ", op.values["name"])
        assertEquals("all", op.values["proto"])
        assertTrue(p.title.contains("internet", ignoreCase = true))
        assertEquals(UciOp.Set("firewall", "dmz", mapOf("dest_ip" to "192.168.1.10", "enabled" to "1")), plan(FeatureChanges.setDmz("dmz", "192.168.1.10", "192.168.1.1")).ops.single())
        assertEquals(UciOp.Delete("firewall", "dmz"), plan(FeatureChanges.dmzOff("dmz")).ops.single())
        assertTrue(FeatureChanges.dmzOff(null) is PlanResult.Invalid)
    }

    @Test
    fun parentalPauseAndSchedule() {
        val names = mapOf("AA:BB:CC:DD:EE:01" to "Tablet")
        val pause = plan(FeatureChanges.parentalPause("tablet", names))
        val op = pause.ops.single() as UciOp.Add
        assertEquals("AA:BB:CC:DD:EE:01", op.values["src_mac"])
        assertEquals("Parental: Tablet", op.values["name"])
        assertFalse(op.values.containsKey("start_time"))
        assertTrue(allowed(pause))

        val sched = plan(FeatureChanges.parentalSchedule("aa:bb:cc:dd:ee:01", "mon tue wed", "21:30", "06:30", names))
        val sop = sched.ops.single() as UciOp.Add
        assertEquals("Mon Tue Wed", sop.values["weekdays"])
        assertEquals("21:30", sop.values["start_time"])
        assertEquals("06:30", sop.values["stop_time"])
        assertFalse("every day means no weekdays option", (plan(FeatureChanges.parentalSchedule("Tablet", "", "21:30", "06:30", names)).ops.single() as UciOp.Add).values.containsKey("weekdays"))
        assertTrue(error(FeatureChanges.parentalSchedule("Tablet", "Funday", "21:30", "06:30", names)).contains("day"))
        assertTrue(error(FeatureChanges.parentalSchedule("Tablet", "", "25:00", "06:30", names)).contains("time"))
        assertTrue(error(FeatureChanges.parentalPause("nobody", names)).contains("device"))
        assertEquals(UciOp.Delete("firewall", "p1"), plan(FeatureChanges.removeParental("p1", "Tablet")).ops.single())
    }

    @Test
    fun upnpDdnsAndQos() {
        val up = plan(FeatureChanges.upnpEnabled("config", true))
        assertEquals(UciOp.Set("upnpd", "config", mapOf("enabled" to "1", "enable_upnp" to "1", "enable_natpmp" to "1")), up.ops.single())
        assertEquals(listOf("/etc/init.d/miniupnpd" to listOf("restart"), "/etc/init.d/miniupnpd" to listOf("enable")), up.services)
        assertEquals(listOf("/etc/init.d/miniupnpd" to listOf("stop")), plan(FeatureChanges.upnpEnabled("config", false)).services)
        assertTrue(allowed(up))

        val dd = plan(FeatureChanges.ddnsEnabled("myddns", "home.example.net", false))
        assertEquals(UciOp.Set("ddns", "myddns", mapOf("enabled" to "0")), dd.ops.single())
        assertTrue(allowed(dd))

        val q = plan(FeatureChanges.qos("q1", true, "95", "19.5"))
        assertEquals(UciOp.Set("sqm", "q1", mapOf("enabled" to "1", "download" to "95000", "upload" to "19500")), q.ops.single())
        assertTrue(allowed(q))
        assertTrue(error(FeatureChanges.qos("q1", true, "0", "10")).contains("speed"))
        assertTrue(error(FeatureChanges.qos("q1", true, "abc", "10")).contains("speed"))
        assertEquals(UciOp.Set("sqm", "q1", mapOf("enabled" to "0")), plan(FeatureChanges.qosEnabled("q1", false)).ops.single())
    }

    @Test
    fun mappersAttachActionsToTheRightSettings() {
        val fw = FeatureMappersTest.uci("""{"r1":{".type":"redirect","name":"Camera","src":"wan","src_dport":"8443","dest_ip":"192.168.1.70","proto":"tcp"}}""")
        val row = FeatureMappers.portForwarding(fw).sections[0].rows[0]
        assertEquals(true, row.toggle)
        assertEquals(UciOp.Set("firewall", "r1", mapOf("enabled" to "0")), plan(row.onToggle!!.invoke()).ops.single())
        assertEquals(UciOp.Delete("firewall", "r1"), plan(row.extra!!.build()).ops.single())
        assertTrue(FeatureMappers.portForwarding(fw).forms.isNotEmpty())

        val wifi = FeatureMappersTest.fixture("m7_luci_rpc_wirelessdevices.json")
        val guestView = FeatureMappers.guest(wifi)
        assertTrue("no guest network on the fixture router, so no toggle", guestView.sections.flatMap { it.rows }.none { it.onToggle != null })
    }
}
