package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FiniteControlCliTest {
    @Test fun parsesFundAndSettleWithoutAnyAuthorityArgument() {
        val (_, fund) = FiniteControlCli.parse(listOf("fund", "o.json", "f1", "buyer-account", "USD", "600"))
        assertEquals(ControlCommand.Fund(ActionKey("operator", "fund", "f1"), AccountId("buyer-account"), AssetId("USD"), 600), fund)
        val (_, settle) = FiniteControlCli.parse(listOf("settle", "o.json", "r1", "exec-1", "2"))
        assertEquals(ControlCommand.Settle(ActionKey("operator", "repair", "r1"), "exec-1", 2), settle)
    }

    @Test fun refusesMalformedCommandsBeforeAnyNetworkWork() {
        for (bad in listOf(emptyList(), listOf("capture", "o.json"), listOf("fund", "o.json", "f", "a", "USD"), listOf("fund", "o.json", "f", "a", "USD", "x"),
            listOf("settle", "o.json", "r", "e", "one"), listOf("settle", "o.json", "r", "e"), listOf("fund"))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { FiniteControlCli.parse(bad) }
        }
    }

    @Test fun credentialsComeFromTheEnvironmentOnlyAndAreEscaped() {
        val p = FiniteControlCli.clientProperties(mapOf("CALCIFY_CONTROL_BOOTSTRAP" to "b:9092", "CALCIFY_CONTROL_USERNAME" to "control", "CALCIFY_CONTROL_PASSWORD" to "p\"w\\x"))
        assertEquals("SASL_PLAINTEXT", p["security.protocol"]); assertEquals("SCRAM-SHA-256", p["sasl.mechanism"])
        assertTrue((p["sasl.jaas.config"] as String).contains("password=\"p\\\"w\\\\x\""))
        for (missing in listOf("CALCIFY_CONTROL_BOOTSTRAP", "CALCIFY_CONTROL_USERNAME", "CALCIFY_CONTROL_PASSWORD")) {
            val env = mapOf("CALCIFY_CONTROL_BOOTSTRAP" to "b", "CALCIFY_CONTROL_USERNAME" to "u", "CALCIFY_CONTROL_PASSWORD" to "p") - missing
            assertFailsWith<IllegalArgumentException>(missing) { FiniteControlCli.clientProperties(env) }
        }
        assertFailsWith<IllegalArgumentException> { FiniteControlCli.clientProperties(mapOf("CALCIFY_CONTROL_BOOTSTRAP" to "b", "CALCIFY_CONTROL_USERNAME" to "u", "CALCIFY_CONTROL_PASSWORD" to "p", "CALCIFY_CONTROL_PROTOCOL" to "PLAINTEXT")) }
    }
}
