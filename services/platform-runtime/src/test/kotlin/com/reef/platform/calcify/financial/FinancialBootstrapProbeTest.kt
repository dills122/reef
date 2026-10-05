package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Path
import java.util.Properties
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

class FinancialBootstrapProbeTest {
    private val json = ObjectMapper()
    private val brokerEndpoints = "127.0.0.1:39492,127.0.0.1:39592,127.0.0.1:39692"
    @Test fun `raw capability integer round trip retains exact pinned heap identity`() {
        val actual = json.createObjectNode().put("maxHeapBytes", FinancialHeapBounds.AUTHORIZED_MAX)
        for (field in listOf("sourceHead", "fixtureSha256", "configSha256", "buildSha256", "classpathSha256", "javaVersion", "javaVendor")) actual.put(field, "exact-$field")
        actual.putArray("classpathEntries").add("/exact/classes")
        val frozen = json.readTree(json.writeValueAsBytes(actual)) as com.fasterxml.jackson.databind.node.ObjectNode
        assertTrue(actual["maxHeapBytes"].isLong && frozen["maxHeapBytes"].isInt)
        FinancialRateProbe.validateBootstrapCapability(frozen, actual)
        for (bad in listOf(json.nodeFactory.numberNode(FinancialHeapBounds.AUTHORIZED_MAX + 1), json.nodeFactory.textNode(FinancialHeapBounds.AUTHORIZED_MAX.toString()), json.nodeFactory.numberNode(FinancialHeapBounds.AUTHORIZED_MAX.toDouble()), json.nodeFactory.nullNode(), json.nodeFactory.numberNode(0), json.nodeFactory.numberNode(-1), json.nodeFactory.numberNode(java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE)), json.nodeFactory.numberNode(FinancialHeapBounds.MAX_SAFE_INTEGER + 1))) {
            val changed = frozen.deepCopy(); changed.set<com.fasterxml.jackson.databind.JsonNode>("maxHeapBytes", bad)
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCapability(changed, actual) }
        }
        val missing = frozen.deepCopy().apply { remove("maxHeapBytes") }
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCapability(missing, actual) }
        val changedIdentity = frozen.deepCopy().put("buildSha256", "changed")
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCapability(changedIdentity, actual) }
        val smallerActual = actual.deepCopy().put("maxHeapBytes", FinancialHeapBounds.AUTHORIZED_MAX - 1)
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCapability(json.readTree(json.writeValueAsBytes(smallerActual)), smallerActual) }
    }

    @Test fun `managed activation count survives actual Long to raw JSON round trip`() {
        val summary = json.createObjectNode().put("ownerSha256", "owner-exact").put("historyChecksum", "chain-exact").put("historyRecords", 2101L)
        val request = json.readTree(json.writeValueAsBytes(summary)) as com.fasterxml.jackson.databind.node.ObjectNode
        assertTrue(summary["historyRecords"].isLong && request["historyRecords"].isInt)
        FinancialRateProbe.validateBootstrapActivation(summary, request, 2099L, 2200L, 2200L)
        for (bad in listOf(json.nodeFactory.numberNode(2100), json.nodeFactory.numberNode(2102), json.nodeFactory.textNode("2101"), json.nodeFactory.numberNode(2101.0), json.nodeFactory.nullNode(), json.nodeFactory.numberNode(0), json.nodeFactory.numberNode(-1), json.nodeFactory.numberNode(java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE)), json.nodeFactory.numberNode(FinancialHeapBounds.MAX_SAFE_INTEGER + 1))) {
            val changed = request.deepCopy().apply { set<com.fasterxml.jackson.databind.JsonNode>("historyRecords", bad) }
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(summary, changed, 2099L, 2200L, 2200L) }
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(changed, changed, 2099L, 2200L, 2200L) }
        }
        val missing = request.deepCopy().apply { remove("historyRecords") }
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(summary, missing, 2099L, 2200L, 2200L) }
        for (field in listOf("ownerSha256", "historyChecksum")) {
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(summary, request.deepCopy().put(field, "changed"), 2099L, 2200L, 2200L) }
        }
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(summary, request, 2098L, 2200L, 2200L) }
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(summary, request, 2099L, 2201L, 2200L) }
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapActivation(summary, request, 2099L, null, 2200L) }
    }

    @Test fun `rate reference does not retain a full decoded JSON owner`() {
        val policy = json.readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").toFile())["policy"]
        val balances = json.readTree("""{"buyerCash":"100000000000","sellerCash":"0","buyerShares":"0","sellerShares":"2000"}""")
        val type = FinancialRateProbe::class.java.declaredClasses.single { it.simpleName == "Reference" }
        val reference = type.declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(balances, policy)
        val retained = type.getDeclaredField("owner").apply { isAccessible = true }.get(reference)
        assertTrue(retained !is com.fasterxml.jackson.databind.JsonNode, "rate reference must retain compact facts instead of full decoded owner tree")
    }

    private fun counts() = json.createObjectNode().put("schema", "financial-calibration-bootstrap-v1")
        .put("purpose", "EMPIRICAL_BOUNDED_DIAGNOSTIC").put("status", "FROZEN_DIAGNOSTIC")
        .put("capacityQualification", false).put("heapConservativeBound", false).putNull("estimatedHeapBytes")
        .put("sampleTrades", 1000).put("pendingSample", 100).put("agedIdentities", 0)
        .put("sourceActions", 2100).put("historyRecords", 2101)

    @Test fun `bootstrap counts cannot expand or enter conventional rate scope`() {
        FinancialRateProbe.validateBootstrapCounts(counts())
        for (field in listOf("sampleTrades", "pendingSample", "agedIdentities", "sourceActions", "historyRecords")) {
            val spec = counts().put(field, counts()[field].asLong() + 1)
            assertFailsWith<IllegalArgumentException>(field) { FinancialRateProbe.validateBootstrapCounts(spec) }
        }
        for (field in listOf("capacityQualification", "heapConservativeBound")) {
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCounts(counts().put(field, true)) }
        }
        val withArm = counts(); withArm.putObject("arm").put("rate", 2500)
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCounts(withArm) }
    }

    @Test fun `result byte bound precedes decoded JSON and checksum expansion is covered`() {
        val value = FinancialRateProbe.parseBootstrapResult("{\"records\":[]}".toByteArray())
        assertEquals(0, value["records"].size())
        val failure = assertFailsWith<IllegalArgumentException> { FinancialRateProbe.parseBootstrapResult(ByteArray(135325) { 32 }) }
        assertTrue(failure.message!!.contains("BOOTSTRAP_RESULT_BYTE_BOUND"))
    }

    @Test fun `bootstrap freezes effective Kafka client buffers including restore consumers`() {
        val properties = FinancialRateProbe.streamProperties(Properties().apply { put("bootstrap.servers", "localhost:9092") }, "financial-s1-unit", Path.of("/tmp/finite-bootstrap-unit"), true)
        val receipt = FinancialRateProbe.bootstrapClientConfiguration(properties)
        assertEquals(4194304L, receipt["producer"]["buffer.memory"].asLong())
        for (consumer in listOf("mainConsumer", "restoreConsumer")) {
            assertEquals(16, receipt[consumer]["max.poll.records"].asInt())
            assertEquals(524288, receipt[consumer]["fetch.max.bytes"].asInt())
            assertEquals(262144, receipt[consumer]["max.partition.fetch.bytes"].asInt())
        }
        properties["producer.buffer.memory"] = 4194305L
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.bootstrapClientConfiguration(properties) }
    }

    @Test fun `actual runtime paths must match every registered allocation category`() {
        val root = Files.createTempDirectory("bootstrap-registration-control-").toRealPath()
        try {
            val config = json.createObjectNode().put("ownedRoot", root.toString())
            val resources = config.putArray("localResources")
            for ((field, category) in listOf("stateDir" to "localStore", "ackJournalDir" to "controllerJournal", "proofDir" to "proof")) {
                val path = Files.createDirectory(root.resolve(field)); config.put(field, path.toString())
                resources.addObject().put("id", field).put("category", category).put("path", path.toString())
            }
            FinancialRateProbe.validateBootstrapRuntimePaths(config)
            config.put("stateDir", Files.createDirectory(root.resolve("unwatched")).toString())
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapRuntimePaths(config) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `broker endpoint and observed cluster must match registered live resource scope`() {
        val endpoints = "127.0.0.1:39492,127.0.0.1:39592,127.0.0.1:39692"
        val config = json.createObjectNode(); val registry = config.putObject("registeredResources").put("bootstrapServers", endpoints)
        val containers = registry.putArray("containers")
        for (id in 0..2) containers.addObject().put("brokerId", id).putObject("hostKafkaEndpoint").put("host", "127.0.0.1").put("port", 39492 + 100 * id).put("containerPort", "${39492 + 100 * id}/tcp")
        val scope = config.putObject("brokerScope").put("schema", "financial-broker-scope-v1").put("bootstrapServers", endpoints)
            .put("clusterId", "synthetic-owned-cluster").put("metadataSource", "actual AdminClient describeCluster")
        val brokers = scope.putArray("brokers"); for (id in 0..2) brokers.addObject().put("id", id).put("host", "127.0.0.1").put("port", 39492 + 100 * id)
        FinancialRateProbe.validateBootstrapBrokerScope(config, endpoints, scope)
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapBrokerScope(config, "foreign-cluster.example:9092", scope) }
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapBrokerScope(config, endpoints, scope.deepCopy().put("clusterId", "foreign-cluster")) }
        val moved = scope.deepCopy(); (moved["brokers"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("port", 9092)
        assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapBrokerScope(config, endpoints, moved) }
    }

    @Test fun `E3 admission reads raw proof and rejects stale candidate even when declaration hashes match`() {
        val root = Files.createTempDirectory("bootstrap-e3-binding-control-").toRealPath()
        fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        try {
            val spec = json.createObjectNode().put("sourceHead", "a".repeat(40)).put("fixtureSha256", "b".repeat(64)).put("candidateSha256", "c".repeat(64))
                .put("candidateEvidenceSha256", "d".repeat(64)).put("correctnessEvidencePath", "correctness.json").put("correctnessProofManifestEvidencePath", "manifest.json")
            spec.putObject("capability").put("buildSha256", "e".repeat(64)).put("classpathSha256", "f".repeat(64)).put("configSha256", "1".repeat(64))
            val failure = assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCorrectness(spec, root.resolve("request.json"), brokerEndpoints) }
            assertTrue(failure.message!!.contains("BOOTSTRAP_E3"))
            val marker = json.createObjectNode().put("schema", "financial-e3-correctness-binding-v1").put("result", "PASS")
                .put("sourceHead", "a".repeat(40)).put("fixtureSha256", "b".repeat(64)).put("candidateSha256", "0".repeat(64))
            val raw = json.writeValueAsBytes(marker); Files.write(root.resolve("correctness.json"), raw)
            spec.set<com.fasterxml.jackson.databind.JsonNode>("correctness", marker.deepCopy().put("evidenceSha256", sha(raw)))
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCorrectness(spec, root.resolve("request.json"), brokerEndpoints) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `complete synthetic proof binds actual compiled files and detects post proof class mutation`() {
        val root = Files.createTempDirectory("bootstrap-e3-complete-control-").toRealPath()
        fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun write(path: java.nio.file.Path, value: Any): String { Files.writeString(path, json.writeValueAsString(value)); return sha(Files.readAllBytes(path)) }
        try {
            val proofRoot = Files.createDirectory(root.resolve("e3")); val classes = Files.createDirectories(root.resolve("classes/com/reef/platform/calcify/financial"))
            val classFile = Files.writeString(classes.resolve("FinancialKernel.class"), "Synthetic class bytes; validation control only")
            val financial = "services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial/FinancialKernel.kt"
            val sources = linkedMapOf(financial to "2".repeat(64)); val runners = listOf("broker-proof.mjs", "lib/external-faults.mjs", "proof-supervisor.mjs").associate { "scripts/dev/calcify-financial/$it" to "3".repeat(64) }
            sources.putAll(runners); val candidateHash = write(root.resolve("candidate.json"), mapOf("sourceSha256" to sources))
            val cp = root.resolve("classes").toString(); val compiled = mapOf(classFile.toString() to sha(Files.readAllBytes(classFile)))
            val planHashes = linkedMapOf<String, String>(); val arms = mapOf("core" to 24, "golden" to 20, "external" to 4, "activation" to 1)
            for (arm in arms.keys) {
                val dir = Files.createDirectory(proofRoot.resolve(arm))
                val plan = mapOf("schema" to "financial-e3-plan-v1", "broker" to brokerEndpoints, "fixtureSha256" to "b".repeat(64), "classpath" to cp, "compiledHashes" to compiled,
                    "sourceHashes" to mapOf("FinancialKernel.kt" to sources[financial]), "runnerHashes" to runners,
                    "mutationBoundaries" to (0..18).map { mapOf("index" to it) }, "validationFixtureArm" to arm)
                planHashes[arm] = write(dir.resolve("plan.json"), plan)
                val names = when (arm) {
                    "core" -> listOf("happy") + (0..18).map { "mutation-$it" } + listOf("forward", "serialization", "local-state-loss", "staged-phase-loss")
                    "golden" -> (0..19).map { "golden-$it" }
                    "external" -> listOf("producer-failure", "committed-output-before-controller-ack", "stale-owner-resume-after-takeover", "majority-one-broker-unavailable")
                    else -> listOf("activation-quartet")
                }
                write(dir.resolve("results.json"), mapOf("results" to names.map { mapOf("name" to it, "after" to mapOf("pass" to true), "before" to mapOf("pass" to true), "idle" to mapOf("pass" to true)) }))
            }
            val binding = linkedMapOf<String, Any>("sourceHead" to "a".repeat(40), "fixtureSha256" to "b".repeat(64), "candidateSha256" to "c".repeat(64),
                "buildSha256" to "e".repeat(64), "classpathSha256" to "f".repeat(64), "plannedBootstrapConfigSha256" to "1".repeat(64), "arms" to arms, "e3PlanSha256" to planHashes)
            val rows = Files.walk(proofRoot).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().map { mapOf("path" to it.toString(), "sizeBytes" to Files.size(it), "sha256" to sha(Files.readAllBytes(it))) }.toList() }
            val manifestHash = write(root.resolve("manifest.json"), binding + mapOf("schema" to "financial-e3-proof-manifest-v1", "proofRoot" to proofRoot.toString(), "files" to rows))
            val marker = binding + mapOf("schema" to "financial-e3-correctness-binding-v1", "result" to "PASS", "proofManifestSha256" to manifestHash,
                "sourceCandidateEvidenceSha256" to candidateHash, "scope" to "Actual same-candidate RF3/EOS financial proof: core24 + golden20 + external4 + activation1; E3 configuration and plan identities retained separately from planned bootstrap configuration.")
            val markerHash = write(root.resolve("correctness.json"), marker)
            val spec = json.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(binding).apply {
                put("candidateEvidencePath", "candidate.json"); put("candidateEvidenceSha256", candidateHash)
                put("correctnessEvidencePath", "correctness.json"); put("correctnessProofManifestEvidencePath", "manifest.json"); put("correctnessProofRoot", proofRoot.toString())
                set<com.fasterxml.jackson.databind.JsonNode>("correctness", json.valueToTree(marker + mapOf("evidenceSha256" to markerHash)))
                putObject("capability").put("buildSha256", "e".repeat(64)).put("classpathSha256", "f".repeat(64)).put("configSha256", "1".repeat(64)).putArray("classpathEntries").add(cp)
            }
            FinancialRateProbe.validateBootstrapCorrectness(spec, root.resolve("request.json"), brokerEndpoints)
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCorrectness(spec, root.resolve("request.json"), "foreign-cluster.example:9092") }
            val unlistedLink = Files.createSymbolicLink(proofRoot.resolve("unlisted-directory-link"), root)
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCorrectness(spec, root.resolve("request.json"), brokerEndpoints) }
            Files.delete(unlistedLink)
            Files.writeString(classFile, "Changed synthetic class bytes")
            assertFailsWith<IllegalArgumentException> { FinancialRateProbe.validateBootstrapCorrectness(spec, root.resolve("request.json"), brokerEndpoints) }
        } finally { root.toFile().deleteRecursively() }
    }
}
