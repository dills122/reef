package com.reef.platform.calcify.finance

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapacityControlCliTest {
    private val planJson = """{"schema":"calcify-capacity-plan-v1","planId":"p","run":"run-1","session":"session-1","cashAsset":"USD","openingAccount":"opening","laneRouting":"explicit",
        "laneInstruments":[["A"],["B"]],"maxObligations":"1000","maxCaptures":"1000","maxSettleAttempts":"2000","maxControls":"100","sequenceBound":"8000000","autoSettle":true,
        "captureSource":{"kind":"resolved-context-v1","policyVersion":"1"},"applicationId":"app","topics":{"capture":"CAP","control":"CTL","decisions":"DEC"}}""".replace(Regex("\\s*\n\\s*"), "")
    private val plan = Files.createTempFile("plan", ".json").also { Files.writeString(it, planJson) }.toString()
    private val dev = mapOf("CALCIFY_CAPACITY_CONTROL_BOOTSTRAP" to "broker:9092", "CALCIFY_CAPACITY_CONTROL_PROTOCOL" to "PLAINTEXT", "CALCIFY_CAPACITY_DEV_PLAINTEXT" to "true")

    @Test fun commandsAreCheckedBeforeAnythingIsWritten() {
        // The usage and value checks happen after the plan is read and before any broker connection is made for the failing cases below.
        for (bad in listOf(listOf("fund-cash", plan, "p", "alice", "0"), listOf("fund-cash", plan, "p", "alice", "x"), listOf("fund-cash-lane", plan, "p", "alice", "2", "5"),
            listOf("fund-security", plan, "p", "alice", "A"), listOf("repair", plan, "r", "A", "e1", "1"), listOf("repair", plan, "r", "A", "e1", "x"), listOf("nope", plan), listOf("fund-cash"))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { CapacityControlCli.run(bad, dev) }
        }
    }

    @Test fun clientPropertiesFollowTheSameRulesAsTheServiceRole() {
        assertFailsWith<IllegalArgumentException>("plaintext without the flag") { CapacityControlCli.clientProperties(dev - "CALCIFY_CAPACITY_DEV_PLAINTEXT") }
        assertEquals("broker:9092", CapacityControlCli.clientProperties(dev)["bootstrap.servers"])
        assertFailsWith<IllegalArgumentException>("secured protocol without credentials") { CapacityControlCli.clientProperties(mapOf("CALCIFY_CAPACITY_CONTROL_BOOTSTRAP" to "b:1")) }
        val sasl = CapacityControlCli.clientProperties(mapOf("CALCIFY_CAPACITY_CONTROL_BOOTSTRAP" to "b:1", "CALCIFY_CAPACITY_CONTROL_USERNAME" to "ops", "CALCIFY_CAPACITY_CONTROL_PASSWORD" to "pw"))
        assertEquals("SASL_PLAINTEXT", sasl["security.protocol"]); assertTrue(sasl["sasl.jaas.config"].toString().contains("username=\"ops\""))
    }
}
