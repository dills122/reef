package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapacityRuntimeTest {
    private val digest = "a".repeat(64)
    private fun plan(edit: (String) -> String = { it }) = edit("""{"schema":"calcify-capacity-plan-v1","planId":"arena-1","run":"run-1","session":"session-1","cashAsset":"USD","openingAccount":"opening","laneRouting":"explicit",
        "laneInstruments":[["INS0","INS1"],["INS2"]],"maxObligations":"1000000","maxCaptures":"1000000","maxSettleAttempts":"2000000","maxControls":"50000","sequenceBound":"8000000","autoSettle":true,
        "captureSource":{"kind":"finite-lifecycle-capture-v1","bindingDigest":"$digest","verifyContentDigest":true},"applicationId":"capacity-app","topics":{"capture":"CAP","control":"CTL","decisions":"DEC"}}""".replace(Regex("\\s*\n\\s*"), "")).toByteArray()

    @Test fun aValidPlanFileBecomesAPlanWithLanesTopicsAndBindings() {
        val d = CapacityRuntime.parsePlan(plan())
        assertEquals(2, d.plan.lanes); assertEquals(setOf("INS0", "INS1"), d.plan.laneInstruments[0]); assertEquals(1, d.plan.laneOf("INS2"))
        assertEquals(8_000_000L, d.plan.sequenceBound); assertEquals(CapacityTopics("CAP", "CTL", "DEC"), d.topics); assertEquals("capacity-app", d.applicationId); assertTrue(d.decoder is CapacityInputDecoders)
        assertTrue(d.plan.autoSettle); assertTrue(d.plan.bindingDigest(0) != d.plan.bindingDigest(1))
        assertEquals(d.plan.bindingDigest(0), CapacityRuntime.parsePlan(plan()).plan.bindingDigest(0), "the same file gives the same lane bindings")
    }

    @Test fun anEngineAlignedPlanWithResolvedContextsIsAccepted() {
        val instruments = (0 until 12).map { "INS$it" }; val lanes = CapacityRouting.engineLanes(instruments, 3)
        val json = plan { it.replace("\"explicit\"", "\"partition-lane-hash-v1\"").replace(Regex("\"captureSource\":\\{[^}]*\\}"), "\"captureSource\":{\"kind\":\"resolved-context-v1\",\"policyVersion\":\"1\"}")
            .replace(Regex("\"laneInstruments\":\\[.*?\\]\\],"), "\"laneInstruments\":[" + lanes.joinToString(",") { l -> l.sorted().joinToString(",", "[", "]") { "\"$it\"" } } + "],") }
        val d = CapacityRuntime.parsePlan(json)
        assertEquals(3, d.plan.lanes); assertTrue(d.decoder is CapacityResolvedContextDecoder); d.plan.requireEngineAligned()
        assertEquals(lanes.map { it.toSet() }, d.plan.laneInstruments)
    }

    @Test fun everyMalformedPlanIsRefused() {
        val bad: Map<String, (String) -> String> = mapOf(
            "unknown schema" to { it.replace("capacity-plan-v1", "capacity-plan-v9") },
            "unknown field" to { it.replace("\"run\":", "\"extra\":\"x\",\"run\":") },
            "missing field" to { it.replace("\"session\":\"session-1\",", "") },
            "digest shape" to { it.replace(digest, "A".repeat(64)) },
            "unknown source kind" to { it.replace("finite-lifecycle-capture-v1", "other-v1") },
            "resolved source needs engine routing" to { it.replace(Regex("\"captureSource\":\\{[^}]*\\}"), "\"captureSource\":{\"kind\":\"resolved-context-v1\",\"policyVersion\":\"1\"}") },
            "unknown routing" to { it.replace("\"explicit\"", "\"hash\"") },
            "not the engine's partitioning" to { it.replace("\"explicit\"", "\"partition-lane-hash-v1\"") },
            "verify flag as text" to { it.replace("\"verifyContentDigest\":true", "\"verifyContentDigest\":\"true\"") },
            "number as number" to { it.replace("\"maxControls\":\"50000\"", "\"maxControls\":50000") },
            "negative number" to { it.replace("\"maxControls\":\"50000\"", "\"maxControls\":\"-1\"") },
            "autoSettle as text" to { it.replace("\"autoSettle\":true", "\"autoSettle\":\"true\"") },
            "instrument in two lanes" to { it.replace("[\"INS2\"]", "[\"INS1\"]") },
            "instrument twice in a lane" to { it.replace("[\"INS0\",\"INS1\"]", "[\"INS0\",\"INS0\"]") },
            "empty lane" to { it.replace("[\"INS2\"]", "[]") },
            "no lanes" to { it.replace(Regex("\"laneInstruments\":\\[.*?\\]\\],"), "\"laneInstruments\":[],") },
            "instrument is the cash asset" to { it.replace("\"INS2\"", "\"USD\"") },
            "topics repeat" to { it.replace("\"control\":\"CTL\"", "\"control\":\"CAP\"") },
            "bound out of range" to { it.replace("\"sequenceBound\":\"8000000\"", "\"sequenceBound\":\"5\"") },
            "captures above obligations" to { it.replace("\"maxCaptures\":\"1000000\"", "\"maxCaptures\":\"2000000\"") },
            "duplicate key" to { it.replace("\"run\":\"run-1\"", "\"run\":\"run-1\",\"run\":\"run-2\"") },
        )
        for ((name, edit) in bad) assertFailsWith<IllegalArgumentException>(name) { CapacityRuntime.parsePlan(plan(edit)) }
        assertFailsWith<IllegalArgumentException> { CapacityRuntime.parsePlan("[]".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { CapacityRuntime.parsePlan(ByteArray(70_000)) }
    }

    private fun env(dir: Path, extra: Map<String, String> = emptyMap(), without: Set<String> = emptySet()): (String) -> String? {
        fun file(name: String, content: ByteArray, mode: String = "rw-------") = dir.resolve(name).also { Files.write(it, content); Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(mode)) }.toString()
        val state = dir.resolve("state").also { if (!Files.exists(it)) Files.createDirectory(it, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
        val m = mutableMapOf(
            "CALCIFY_CAPACITY_PLAN_FILE" to file("plan.json", plan()), "CALCIFY_CAPACITY_KAFKA_BOOTSTRAP_SERVERS" to "broker:9092",
            "CALCIFY_CAPACITY_KAFKA_USERNAME" to "finance", "CALCIFY_CAPACITY_KAFKA_PASSWORD_FILE" to file("kp", "secret".toByteArray()),
            "CALCIFY_CAPACITY_OBSERVER_KAFKA_USERNAME" to "observer", "CALCIFY_CAPACITY_OBSERVER_KAFKA_PASSWORD_FILE" to file("op", "secret2".toByteArray()),
            "CALCIFY_CAPACITY_STATE_DIRECTORY" to state.toString(), "CALCIFY_CAPACITY_POSTGRES_JDBC_URL" to "jdbc:postgresql://localhost:5432/reef",
            "CALCIFY_CAPACITY_POSTGRES_USER" to "projector", "CALCIFY_CAPACITY_POSTGRES_PASSWORD_FILE" to file("pp", "pw".toByteArray()),
        ) + extra
        return { k -> if (k in without) null else m[k] }
    }

    @Test fun theRoleBuildsALauncherFromTheEnvironmentWithoutConnectingAnywhere() {
        val dir = Files.createTempDirectory("capacity-runtime")
        CapacityRuntime.create(env(dir))      // would throw on any missing, loose or inline input; opens no connection
        CapacityRuntime.create(env(dir, mapOf("CALCIFY_CAPACITY_STREAM_THREADS" to "1", "CALCIFY_CAPACITY_PROJECTION_CHUNK" to "5000", "CALCIFY_CAPACITY_SESSION_TIMEOUT_MS" to "10000")))
    }

    @Test fun looseSecretsInlineCredentialsAndBadSettingsAreRefused() {
        val dir = Files.createTempDirectory("capacity-runtime")
        val ok = env(dir)
        fun refused(name: String, lookup: (String) -> String?) = assertFailsWith<RuntimeException>(name) { CapacityRuntime.create(lookup) }
        refused("same principal") { k -> if (k == "CALCIFY_CAPACITY_OBSERVER_KAFKA_USERNAME") "finance" else ok(k) }
        refused("inline password") { k -> if (k == "CALCIFY_CAPACITY_KAFKA_PASSWORD") "x" else ok(k) }
        refused("password in the url") { k -> if (k == "CALCIFY_CAPACITY_POSTGRES_JDBC_URL") "jdbc:postgresql://h/reef?user=a&password=b" else ok(k) }
        refused("missing plan") { k -> if (k == "CALCIFY_CAPACITY_PLAN_FILE") null else ok(k) }
        refused("threads above lanes") { k -> if (k == "CALCIFY_CAPACITY_STREAM_THREADS") "3" else ok(k) }
        refused("chunk out of range") { k -> if (k == "CALCIFY_CAPACITY_PROJECTION_CHUNK") "5" else ok(k) }
        refused("session timeout below the pinned floor") { k -> if (k == "CALCIFY_CAPACITY_SESSION_TIMEOUT_MS") "1000" else ok(k) }
        val loose = dir.resolve("kp"); Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rw-r-----"))
        refused("group-readable credential") { k -> ok(k) }
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rw-------"))
        Files.setPosixFilePermissions(dir.resolve("state"), PosixFilePermissions.fromString("rwxr-x---"))
        refused("group-readable state directory") { k -> ok(k) }
    }

    @Test fun theRoleRefusesToShareAProcessWithAnotherCalcifyRole() {
        CapacityRuntime.requireExclusive { null }
        for (other in listOf("CALCIFY_STAGE", "CALCIFY_FINITE_BINDING_DIGEST", "CALCIFY_FINANCE_BINDING_DIGEST")) assertFailsWith<IllegalArgumentException>(other) { CapacityRuntime.requireExclusive { k -> if (k == other) "x" else null } }
    }
}
