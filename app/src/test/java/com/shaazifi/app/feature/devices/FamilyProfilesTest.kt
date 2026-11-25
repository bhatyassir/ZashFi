package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.demo.DemoRouterChanges
import com.shaazifi.app.feature.router.ChangeOutcome
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadState
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.UciConfig
import com.shaazifi.app.feature.router.UciOp
import com.shaazifi.app.feature.router.UciSection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyProfilesTest {
    @Test
    fun resumingFamilyPreservesProfilePausesIndividualPausesAndSchedules() = runBlocking {
        val holder = LoadHolder(load = { DemoDevices }, demo = DemoDevices)
        val demo = DemoRouterChanges(holder) { LoadHolder(load = { FeatureView("") }) }
        holder.refresh()
        fun rules() = (holder.state.value as LoadState.DemoData).value.accessRules.orEmpty()
        val profile = familyProfiles(rules(), emptyList()).single()
        assertEquals(ChangeOutcome.Kept, demo.run((FamilyChanges.profilePause(profile) as PlanResult.Ready).plan))
        val before = rules()
        val pause = (FamilyChanges.familyPause(familyProfiles(before, emptyList()), before) as PlanResult.Ready).plan
        val targets = pause.ops.filterIsInstance<UciOp.Add>().map { it.values["src_mac"] }.toSet()
        assertEquals(profile.macs, targets)
        assertEquals(ChangeOutcome.Kept, demo.run(pause))
        assertTrue(FamilyChanges.overrideActive(rules()))

        val resume = (FamilyChanges.familyPause(familyProfiles(rules(), emptyList()), rules()) as PlanResult.Ready).plan
        assertEquals(ChangeOutcome.Kept, demo.run(resume))
        assertFalse(FamilyChanges.overrideActive(rules()))
        assertEquals(before, rules())
        assertTrue(familyProfiles(rules(), emptyList()).single().paused)
        assertTrue(rules().any { it.mac == "02:00:00:00:00:04" && it.family == null && it.enabled })
        assertEquals(2, rules().count { it.scheduled && it.enabled })
    }

    @Test
    fun assignmentInheritsExistingRestrictionsAndUsesTheKnownSourceZone() {
        val rules = DemoDevices.accessRules.orEmpty()
        val existing = familyProfiles(rules, emptyList()).single()
        val paused = existing.copy(rules = existing.rules.map { it.copy(enabled = true) })
        val override = existing.members.first().copy(id = "override", enabled = true, family = FamilyTag(existing.id, "override", existing.label))
        val printer = DemoDevices.devices.first { it.name == "Printer" }
        val result = FamilyChanges.assign(paused, listOf(printer), rules + override) as PlanResult.Ready
        val added = result.plan.ops.filterIsInstance<UciOp.Add>()
        assertEquals(3, added.size)
        assertTrue(added.all { it.values["src"] == "studio" && it.values["src_mac"] == printer.mac && it.values["enabled"] == "1" })
        assertEquals(1, added.count { it.values["start_time"] == "21:00" })
        assertTrue(FamilyChanges.assign(paused, listOf(printer.copy(network = null)), rules) is PlanResult.Invalid)
        assertTrue(FamilyChanges.assign(paused, DemoDevices.devices.filter { it.mac in paused.macs }, rules) is PlanResult.Invalid)
    }

    @Test
    fun familyManagedRulesCannotBeChangedThroughIndividualPause() {
        val target = DemoDevices.devices.first { it.name == "Kid's tablet" }
        val individual = AccessRule("individual", target.mac, true)
        val member = AccessRule("member", target.mac, true, family = FamilyTag("f123456789abcdef", "member", "Kids"))
        val override = member.copy(id = "override", family = member.family!!.copy(kind = "override"))
        val result = changeInternet(target, listOf(individual, member, override)) as PlanResult.Ready
        assertEquals(listOf(UciOp.Set("firewall", "individual", mapOf("enabled" to "0"))), result.plan.ops)
    }

    @Test
    fun deviceGroupsMatchReportedPrefixesAndDoNotGuessAZone() {
        val dump = Json.parseToJsonElement("""
            {"interface":[
                {"interface":"lan","ipv4-address":[{"address":"192.168.1.1","mask":24}]},
                {"interface":"lab","ipv4-address":[{"address":"192.168.1.32","mask":27}]},
                {"interface":"wan","ipv4-address":[{"address":"198.51.100.1","mask":24}]},
                {"interface":"mesh","ipv4-address":[{"address":"192.0.2.1","mask":24}]}
            ]}
        """).jsonObject
        val fw = UciConfig(listOf(
            UciSection("z1", "zone", mapOf("name" to listOf("lan"), "network" to listOf("lan", "lab"))),
            UciSection("z2", "zone", mapOf("name" to listOf("wan"), "network" to listOf("wan")))
        ))
        val list = DemoDevices.withNetworks(dump, fw)
        val tablet = list.devices.first { it.name == "Kid's tablet" }
        assertEquals(DeviceNetwork("lab", "192.168.1.32/27", "lan"), tablet.network)
        val printer = list.devices.first { it.name == "Printer" }
        assertNotNull(printer.network)
        assertNull(printer.network?.sourceZone)

        val unmatched = DemoDevices.copy(devices = listOf(tablet.copy(ip = "198.51.100.30"))).withNetworks(dump, fw)
        assertNull(unmatched.devices.single().network)
        assertTrue(addressSortKey("192.168.1.9") < addressSortKey("192.168.1.12"))
    }
}
