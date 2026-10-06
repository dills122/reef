package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler

/** Real test-only financial capacity diagnostic. No source gateway or live authority. */
object FinancialRateProbe {
    private val json = ObjectMapper()
    private const val PRICE = 10_000_000L
    private const val RAW_CAP = 256L * 1024 * 1024
    private const val DISK_CAP = 10L * 1024 * 1024 * 1024
    private val nullNode get() = json.nullNode()
    private fun node(value: Any?): JsonNode = json.valueToTree(value)
    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun canonical(n: JsonNode): String = when {
        n.isObject -> n.fields().asSequence().sortedBy { it.key }.joinToString(",", "{", "}") { json.writeValueAsString(it.key) + ":" + canonical(it.value) }
        n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
        else -> n.toString()
    }
    private fun digest(n: JsonNode): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun part(s: String) { md.update(s.toByteArray()) }
        fun walk(v: JsonNode) {
            when {
                v.isObject -> { part("{"); v.fields().asSequence().sortedBy { it.key }.forEachIndexed { i, entry -> if (i > 0) part(","); part(json.writeValueAsString(entry.key)); part(":"); walk(entry.value) }; part("}") }
                v.isArray -> { part("["); v.forEachIndexed { i, child -> if (i > 0) part(","); walk(child) }; part("]") }
                else -> part(v.toString())
            }
        }
        walk(n); return HexFormat.of().formatHex(md.digest())
    }
    private fun shaFile(path: Path): String { val md = MessageDigest.getInstance("SHA-256"); Files.newInputStream(path).use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) } }; return HexFormat.of().formatHex(md.digest()) }
    private fun frame(vararg ids: String) = json.writeValueAsString(ids.toList())
    private fun action(id: Long, phase: String, kind: String): JsonNode {
        val execution = "$phase-$id"
        val payload = if (kind == "CAPTURE") mapOf("executionId" to execution, "runId" to "e4", "venueSessionId" to "venue", "instrumentId" to "ACME",
            "buyOrderId" to "buyer-$execution", "sellOrderId" to "seller-$execution", "buyerAccount" to "buyer", "sellerAccount" to "seller",
            "cashAsset" to "USD_NANO", "securityAsset" to "ACME_SHARE", "quantity" to "1", "priceNanos" to PRICE.toString(), "dueTick" to "0")
        else mapOf("executionId" to execution, "runId" to "e4", "venueSessionId" to "venue", "instrumentId" to "ACME", "attempt" to "1")
        return node(mapOf("namespace" to "financial-e4", "domain" to "hot", "actionId" to "$execution-$kind", "kind" to kind, "payload" to payload))
    }
    private fun emptyOwner(): ObjectNode = json.createObjectNode().apply {
        listOf("balances", "executions", "obligations", "workflows", "instructions", "attempts", "exceptions", "reservations", "dedup", "effects", "versions", "dueQueue", "stagedInputs", "policy").forEach { set<JsonNode>(it, json.createObjectNode()) }
        put("logicalTick", "0"); listOf("businessSeq", "historySeq", "deliveryCursor", "nextDelivery").forEach { put(it, 0L) }
        listOf("continuation", "lastDecisionId", "semanticDigest", "activePolicy").forEach { putNull(it) }
    }

    /** Independent fixed gross-DvP reference: BigInteger economics, known-key deltas.
     * No kernel economics, per-input full state scans or snapshots. */
    internal class Reference(private val balances: JsonNode, private val policy: JsonNode) {
        private val owner = FinancialCanonicalLeafOwner(emptyOwner())
        var chain = "GENESIS"
        var historyRecords = 0L
        private fun get(path: List<String>): JsonNode = owner.get(path)
        fun accept(record: JsonNode) {
            val writes = linkedMapOf<List<String>, JsonNode>()
            fun put(root: String, key: String?, value: JsonNode) { writes[if (key == null) listOf(root) else listOf(root, key)] = value }
            fun increment(root: String, key: String? = null) { val p = if (key == null) listOf(root) else listOf(root, key); writes[p] = node(get(p).asLong() + 1) }
            val legs = mutableListOf<Map<String, String>>()
            fun leg(asset: String, account: String, amount: BigInteger) { legs.add(mapOf("asset" to asset, "account" to account, "amount" to amount.toString())) }
            if (historyRecords == 0L) {
                require(record["kind"].asText() == "GENESIS")
                listOf("buyerCash", "sellerCash", "buyerShares", "sellerShares").forEach { put("balances", it, balances[it]) }
                for ((asset, suffix) in listOf("USD_NANO" to "Cash", "ACME_SHARE" to "Shares")) {
                    val opening = -(BigInteger(balances["buyer$suffix"].asText()) + BigInteger(balances["seller$suffix"].asText()))
                    put("balances", "opening$suffix", node(opening.toString())); leg(asset, "opening", opening)
                    listOf("buyer", "seller").forEach { account -> leg(asset, account, BigInteger(balances[account + suffix].asText())) }
                }
                listOf("buyerCash", "sellerCash", "buyerShares", "sellerShares", "openingCash", "openingShares").forEach { put("versions", it, node(0L)) }
                put("policy", null, policy); put("activePolicy", null, policy["policy"]); put("semanticDigest", null, node(sha("GENESIS".toByteArray())))
            } else {
                require(record["kind"].asText() == "BUSINESS")
                val input = record["input"]; val kind = input["kind"].asText(); val payload = input["payload"]
                require(input["namespace"].asText() == "financial-e4" && input["domain"].asText() == "hot")
                require(kind in setOf("CAPTURE", "SETTLE"))
                val execution = frame("e4", "venue", "ACME", payload["executionId"].asText())
                val decision = frame("financial-e4", "hot", input["actionId"].asText())
                require(!owner.contains("dedup", decision)) { "duplicate timed economic action" }
                val disposition: String
                if (kind == "CAPTURE") {
                    require(!owner.contains("executions", execution)); require(payload["quantity"].asText() == "1" && payload["priceNanos"].asText() == PRICE.toString())
                    put("executions", execution, payload); put("obligations", execution, node(mapOf("status" to "PENDING", "cashResidual" to PRICE.toString(), "shareResidual" to "1")))
                    put("workflows", execution, node("PENDING")); put("instructions", execution, node("READY"))
                    put("dueQueue", execution, node(mapOf("tick" to "0", "priority" to 0, "workId" to payload["executionId"].asText())))
                    increment("versions", execution); disposition = "CAPTURED"
                } else {
                    require(get(listOf("obligations", execution))["status"].asText() == "PENDING")
                    for ((field, delta) in listOf("buyerCash" to -PRICE, "sellerCash" to PRICE, "buyerShares" to 1L, "sellerShares" to -1L)) {
                        val next = BigInteger(get(listOf("balances", field)).asText()) + BigInteger.valueOf(delta)
                        require(next.signum() >= 0 && next <= BigInteger.valueOf(Long.MAX_VALUE)); put("balances", field, node(next.toString())); increment("versions", field)
                    }
                    leg("USD_NANO", "buyer", BigInteger.valueOf(-PRICE)); leg("USD_NANO", "seller", BigInteger.valueOf(PRICE)); leg("ACME_SHARE", "seller", -BigInteger.ONE); leg("ACME_SHARE", "buyer", BigInteger.ONE)
                    put("obligations", execution, node(mapOf("status" to "SETTLED", "cashResidual" to "0", "shareResidual" to "0")))
                    put("workflows", execution, node("SETTLED")); put("instructions", execution, node("SETTLED")); put("attempts", frame("e4", "venue", "ACME", payload["executionId"].asText(), "1"), node("SETTLED"))
                    increment("versions", execution); put("dueQueue", execution, nullNode); disposition = "SETTLED"
                }
                increment("businessSeq"); put("lastDecisionId", null, node(decision))
                val pairs = payload.fields().asSequence().sortedBy { it.key }.map { listOf(it.key, it.value.asText()) }.toList()
                val requestDigest = sha(json.writeValueAsBytes(listOf(kind, null, pairs)))
                val context = policy.deepCopy<ObjectNode>().apply { put("logicalTick", "0"); put("domain", "hot") }
                put("dedup", decision, node(mapOf("digest" to requestDigest, "context" to context, "disposition" to disposition)))
                if (legs.isNotEmpty()) put("effects", decision, node(legs))
                val semanticChanges = writes.entries.filter { it.key[0] !in setOf("historySeq", "deliveryCursor", "nextDelivery", "stagedInputs", "policy", "semanticDigest") && canonical(get(it.key)) != canonical(it.value) }
                    .sortedBy { canonical(node(it.key)) }.map { mapOf("path" to it.key, "before" to get(it.key), "after" to it.value) }
                put("semanticDigest", null, node(sha(canonical(node(mapOf("decisionId" to decision, "disposition" to disposition, "input" to requestDigest, "context" to context, "journalLegs" to legs, "semanticChanges" to semanticChanges))).toByteArray())))
            }
            increment("historySeq")
            val expected = writes.map { (p, value) -> mapOf("path" to p, "before" to get(p), "after" to value) }
            val actual = record["changes"].map { it }.sortedBy { canonical(it["path"]) }
            require(canonical(node(expected.sortedBy { canonical(node(it["path"])) })) == canonical(node(actual))) { "independent complete semantic delta mismatch" }
            require(canonical(record["journalGroups"]) == canonical(node(legs.groupBy { it.getValue("asset") }))) { "independent journal mismatch" }
            require(record["sequence"].asLong() == historyRecords + 1 && record["priorSequence"].asLong() == historyRecords && record["priorChecksum"].asText() == chain)
            val unsigned = record.deepCopy<ObjectNode>().apply { remove("checksum") }
            require(record["checksum"].asText() == digest(unsigned)) { "history checksum" }
            writes.forEach { (p, value) -> owner.write(p, value) }
            historyRecords++; chain = record["checksum"].asText()
        }
        fun ownerDigest() = owner.ownerDigest()
        fun clear() = owner.clear()
        fun businessView() = owner.materialize().apply {
            listOf("policy", "historySeq", "deliveryCursor", "nextDelivery", "stagedInputs").forEach { remove(it) }; set<JsonNode>("dueWork", node(emptyList<String>()))
        }
    }

    private fun selfCheck(policy: JsonNode) {
        val balances = node(mapOf("buyerCash" to "1000000000", "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to "100"))
        val kernel = FinancialKernel(balances, policy, retainHistory = false)
        val reference = Reference(balances, policy); reference.accept(kernel.lastRecord()!!)
        val oracle = FinancialOracle(balances, policy)
        val histories = mutableListOf(kernel.lastRecord()!!)
        repeat(20) { id -> listOf("CAPTURE", "SETTLE").forEach { kind ->
            val input = action(id.toLong(), "timed", kind); kernel.execute(input, detailed = false); reference.accept(kernel.lastRecord()!!); histories.add(kernel.lastRecord()!!)
            oracle.execute(input); oracle.assertMatches(reference.businessView(), "rate reference prefix=$id $kind")
        } }
        require(digest(kernel.ownerView()) == reference.ownerDigest())
        val bad = kernel.lastRecord()!!.deepCopy<ObjectNode>(); (bad["journalGroups"] as ObjectNode).remove("ACME_SHARE")
        val beforeLast = Reference(balances, policy); histories.dropLast(1).forEach { beforeLast.accept(it) }
        require(runCatching { beforeLast.accept(bad) }.exceptionOrNull()?.message?.contains("journal mismatch") == true)
        println(json.writeValueAsString(mapOf("selfCheck" to true, "prefixes" to 40, "independentFullOracle" to true, "oneLegMutationRejected" to true)))
    }

    // Build identity covers every compiled Financial*.class, including nested classes,
    // in this test-only code-source directory. Classpath identity covers ordered actual
    // entries: streamed jar/file bytes and every regular directory file sorted by relative
    // path, including resources. Config/fixture identity covers raw file bytes.
    internal fun heapCapability(sourceHead: String, fixturePath: Path, configPath: Path): JsonNode {
        val root = Path.of(FinancialRateProbe::class.java.protectionDomain.codeSource.location.toURI())
        require(Files.isDirectory(root)) { "HEAP_DIRECTORY_BUILD_REQUIRED" }
        val dir = root.resolve("com/reef/platform/calcify/financial")
        val md = MessageDigest.getInstance("SHA-256")
        Files.list(dir).use { paths -> paths.filter { it.fileName.toString().startsWith("Financial") && it.fileName.toString().endsWith(".class") }
            .sorted().forEach { file -> md.update(file.fileName.toString().toByteArray()); md.update(0.toByte()); md.update(Files.readAllBytes(file)) } }
        val classpathDigest = MessageDigest.getInstance("SHA-256")
        fun classpathPart(value: String) { classpathDigest.update(value.toByteArray()); classpathDigest.update(0.toByte()) }
        val classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it).toAbsolutePath().normalize().toString() }
        classpathEntries.forEach { entry ->
            val path = Path.of(entry).toAbsolutePath().normalize(); require(Files.exists(path)) { "HEAP_CLASSPATH_ENTRY_MISSING" }
            classpathPart(path.toString())
            if (Files.isDirectory(path)) Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                classpathPart(path.relativize(file).toString()); classpathPart(shaFile(file))
            } } else classpathPart(shaFile(path))
        }
        return node(mapOf("schema" to "financial-heap-capability-v1", "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "usedHeapBytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            "vmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
            "javaVersion" to System.getProperty("java.version"), "javaVendor" to System.getProperty("java.vendor"),
            "sourceHead" to sourceHead, "fixtureSha256" to shaFile(fixturePath), "configSha256" to shaFile(configPath),
            "buildSha256" to HexFormat.of().formatHex(md.digest()), "classpathSha256" to HexFormat.of().formatHex(classpathDigest.digest()), "classpathEntries" to classpathEntries,
            "classIdentityScope" to "All compiled Financial*.class including nested classes; ordered classpath entries with streamed jar/file bytes and sorted directory resources"))
    }

    internal fun heapGuard(spec: JsonNode, specPath: Path, configPath: Path, timed: Long, aged: Long, pending: Long): FinancialHeapGuard {
        val h = requireNotNull(spec["heap"]) { "HEAP_CONSERVATIVE_EVIDENCE_REQUIRED" }
        val b = requireNotNull(h["bounds"]); val c = requireNotNull(h["capability"])
        require(h["schema"]?.asText() == "financial-heap-admission-v1" && b["schema"]?.asText() == "financial-heap-bounds-v1"
            && c["schema"]?.asText() == "financial-heap-capability-v1") { "HEAP_CONSERVATIVE_EVIDENCE_REQUIRED" }
        require(FinancialHeapBounds.integer(h["authorizedMaxHeapBytes"], "authorizedMaxHeapBytes") == FinancialHeapBounds.AUTHORIZED_MAX)
        require(b["validationOnly"]?.isBoolean == true && !b["validationOnly"].booleanValue()) { "HEAP_VALIDATION_ONLY_EVIDENCE" }
        for (field in listOf("derivation", "scope")) require(b[field]?.isTextual == true && b[field].asText().isNotBlank())
        val review = requireNotNull(h["review"]) { "HEAP_REVIEW_EVIDENCE_REQUIRED" }
        require(review["schema"]?.asText() == "financial-heap-bounds-review-v1"
            && review["verdict"]?.asText() == "READY_CONSERVATIVE_BOUND"
            && review["validationOnly"]?.isBoolean == true && !review["validationOnly"].booleanValue()) { "HEAP_REVIEW_EVIDENCE_REQUIRED" }
        require(review["boundsEvidenceSha256"] == h["boundsEvidenceSha256"]) { "HEAP_REVIEW_SCOPE_MISMATCH" }
        for (field in listOf("sourceHead", "fixtureSha256", "configSha256", "buildSha256", "classpathSha256", "supportedIdentities", "supportedPendingItems", "derivation", "scope"))
            require(review[field] != null && review[field] == b[field]) { "HEAP_REVIEW_SCOPE_MISMATCH field=$field" }
        val root = specPath.toAbsolutePath().normalize().parent.toRealPath()
        for ((kind, expected) in listOf("bounds" to b, "capability" to c, "review" to review)) {
            require(h["${kind}EvidencePath"]?.isTextual == true)
            val file = root.resolve(h["${kind}EvidencePath"].asText()).normalize().toRealPath()
            require(file.startsWith(root) && file != root) { "HEAP_EVIDENCE_MUST_BE_OWNED_BY_POLICY" }
            require(shaFile(file) == h["${kind}EvidenceSha256"]?.asText()) { "HEAP_RAW_EVIDENCE_HASH_MISMATCH" }
            require(json.readTree(Files.readString(file)) == expected) { "HEAP_RAW_EVIDENCE_IDENTITY_MISMATCH" }
        }
        require(h["fixtureEvidencePath"]?.isTextual == true)
        val fixturePath = root.resolve(h["fixtureEvidencePath"].asText()).normalize().toRealPath()
        require(fixturePath.startsWith(root) && fixturePath != root && shaFile(fixturePath) == b["fixtureSha256"].asText()) { "HEAP_FIXTURE_IDENTITY_MISMATCH" }
        val actual = heapCapability(spec["sourceHead"].asText(), fixturePath, configPath)
        require(spec["fixtureSha256"] == actual["fixtureSha256"]) { "HEAP_FIXTURE_IDENTITY_MISMATCH" }
        for (field in listOf("sourceHead", "fixtureSha256", "configSha256", "buildSha256", "classpathSha256")) {
            val pattern = if (field == "sourceHead") "(?:[a-f0-9]{40}|[a-f0-9]{64})" else "[a-f0-9]{64}"
            require(b[field]?.isTextual == true && b[field].asText().matches(Regex(pattern))
                && b[field] == c[field] && b[field] == actual[field]) { "HEAP_IDENTITY_MISMATCH field=$field" }
        }
        val vmArgs = node(listOf("-Xms128m", "-Xmx768m"))
        require(c["vmArguments"] == vmArgs && actual["vmArguments"] == vmArgs) { "HEAP_PINNED_VM_ARGUMENTS_REQUIRED" }
        for (field in listOf("javaVersion", "javaVendor")) require(c[field]?.isTextual == true && c[field].asText().isNotBlank() && c[field] == actual[field])
        val bounds = FinancialHeapBounds.from(b)
        val capabilityMax = FinancialHeapBounds.integer(c["maxHeapBytes"], "maxHeapBytes")
        val capabilityUsed = FinancialHeapBounds.integer(c["usedHeapBytes"], "usedHeapBytes", false)
        val estimated = bounds.estimate(timed, aged, pending)
        require(estimated <= FinancialHeapBounds.MAX_SAFE_INTEGER && estimated < FinancialHeapBounds.limit(capabilityMax)
            && capabilityUsed <= bounds.baselineHeapBytesUpper && capabilityUsed < FinancialHeapBounds.limit(capabilityMax)) { "HEAP_ADMISSION_LIMIT" }
        return FinancialHeapGuard(bounds, estimated, capabilityMax)
    }

    /** Finite empirical resource permission; never an ordinary conservative policy. */
    internal fun diagnosticDiskBudget(spec: JsonNode): Long {
        if (!spec.has("resourceProfile")) return DISK_CAP
        require(spec["resourceProfile"]?.asText() in setOf("financial-empirical-disk16-v1", "financial-empirical-heap8-disk16-v1")) { "EMPIRICAL_RESOURCE_PROFILE_REQUIRED" }
        return 16L * 1024 * 1024 * 1024
    }
    internal fun diagnosticHeapMax(spec: JsonNode): Long =
        if (spec["resourceProfile"]?.asText() == "financial-empirical-heap8-disk16-v1") FinancialHeapGuard.EMPIRICAL_8G_MAX else FinancialHeapGuard.EMPIRICAL_MAX
    internal fun validateDiagnosticPolicy(spec: JsonNode) {
        require(spec["schema"]?.asText() == "financial-empirical-timed-policy-v1"
            && spec["status"]?.asText() == "FROZEN_EMPIRICAL_DIAGNOSTIC"
            && spec["purpose"]?.asText() == "EMPIRICAL_TIMED_DIAGNOSTIC"
            && spec["capacityQualification"]?.isBoolean == true && !spec["capacityQualification"].booleanValue()
            && spec["heapConservativeBound"]?.isBoolean == true && !spec["heapConservativeBound"].booleanValue()
            && spec["estimatedHeapBytes"]?.isNull == true && !spec.has("heap")) { "EMPIRICAL_DIAGNOSTIC_SCOPE_REQUIRED" }
        require(spec["arm"] == node(mapOf("rate" to 2500, "seconds" to 60, "state" to "fresh"))
            && FinancialHeapBounds.integer(spec["expectedTimedTrades"], "expectedTimedTrades") == 150000L
            && FinancialHeapBounds.integer(spec["ackJournalMaxBytes"], "ackJournalMaxBytes") == 2L * 1024 * 1024 * 1024
            && spec["aged"] == node(mapOf("identities" to 0, "pendingItems" to 0))
            && FinancialHeapBounds.integer(spec["diskBudgetBytes"], "diskBudgetBytes") == diagnosticDiskBudget(spec)
            && FinancialHeapBounds.integer(spec["diskHeadroomBytes"], "diskHeadroomBytes") == 20L * 1024 * 1024 * 1024) { "EMPIRICAL_FIXED_COUNTS_BUDGET_REQUIRED" }
        val unsigned = spec.deepCopy<ObjectNode>().apply { remove("policySha256") }
        require(sha(json.writeValueAsBytes(unsigned)) == spec["policySha256"]?.asText()) { "EMPIRICAL_FROZEN_HASH_REQUIRED" }
    }
    private fun diagnosticGuard(spec: JsonNode, specPath: Path, configPath: Path): FinancialHeapGuard {
        validateDiagnosticPolicy(spec)
        val root = specPath.toAbsolutePath().parent.toRealPath()
        fun owned(field: String): Path {
            require(spec[field]?.isTextual == true)
            val file = root.resolve(spec[field].asText()).normalize().toRealPath()
            require(file.startsWith(root) && file != root && Files.isRegularFile(file) && Files.size(file) <= 1024L * 1024)
            return file
        }
        val cap = requireNotNull(spec["capability"]); val correctness = requireNotNull(spec["correctness"])
        val capabilityFile = owned("capabilityEvidencePath"); val correctnessFile = owned("correctnessEvidencePath")
        require(shaFile(capabilityFile) == spec["capabilityEvidenceSha256"].asText()
            && json.readTree(Files.readString(capabilityFile)) == cap) { "EMPIRICAL_RAW_CAPABILITY_REQUIRED" }
        val correctnessRaw = correctness.deepCopy<ObjectNode>().apply { remove("evidenceSha256") }
        require(shaFile(correctnessFile) == correctness["evidenceSha256"].asText()
            && json.readTree(Files.readString(correctnessFile)) == correctnessRaw && correctness["result"].asText() == "PASS") { "EMPIRICAL_RAW_CORRECTNESS_REQUIRED" }
        val fixture = owned("fixtureEvidencePath")
        require(shaFile(fixture) == spec["fixtureSha256"].asText() && shaFile(configPath) == cap["configSha256"].asText()) { "EMPIRICAL_FIXTURE_CONFIG_REQUIRED" }
        val actual = heapCapability(spec["sourceHead"].asText(), fixture, configPath)
        for (field in listOf("schema", "sourceHead", "fixtureSha256", "configSha256", "buildSha256", "classpathSha256", "javaVersion", "javaVendor", "classpathEntries", "maxHeapBytes", "vmArguments"))
            require(cap[field] != null && cap[field] == actual[field]) { "EMPIRICAL_CAPABILITY_DRIFT field=$field" }
        val authorizedHeap = diagnosticHeapMax(spec)
        require(actual["vmArguments"] == node(listOf("-Xms128m", if (authorizedHeap == FinancialHeapGuard.EMPIRICAL_8G_MAX) "-Xmx8g" else "-Xmx4g"))
            && actual["maxHeapBytes"].asLong() == authorizedHeap) { "EMPIRICAL_PINNED_VM_REQUIRED" }
        val sourceRoot = Path.of(spec["sourceRoot"].asText()).toRealPath()
        require(actual["classpathEntries"].any { it.asText() == sourceRoot.resolve("services/platform-runtime/build/classes/kotlin/test").toString() }) { "EMPIRICAL_SOURCE_BUILD_ROOT" }
        for ((name, key) in listOf("FinancialKernel.kt" to "kernelSha256", "FinancialBrokerProbe.kt" to "managedAdapterSha256")) {
            val digest = shaFile(sourceRoot.resolve("services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial/$name"))
            require(digest == spec["runtimeProofHashes"][key].asText() && digest == correctness[key].asText()) { "EMPIRICAL_CORRECTNESS_SOURCE_DRIFT" }
        }
        require(json.readTree(Files.readString(configPath))["policy"] == json.readTree(Files.readString(fixture))["policy"]) { "EMPIRICAL_POLICY_FIXTURE" }
        return FinancialHeapGuard.empiricalTimed(actual["maxHeapBytes"].asLong(), authorizedMax = authorizedHeap)
    }

    internal fun validateBootstrapCounts(spec: JsonNode) {
        require(spec["schema"]?.asText() == "financial-calibration-bootstrap-v1"
            && spec["purpose"]?.asText() == "EMPIRICAL_BOUNDED_DIAGNOSTIC"
            && spec["status"]?.asText() == "FROZEN_DIAGNOSTIC"
            && spec["capacityQualification"]?.isBoolean == true && !spec["capacityQualification"].booleanValue()
            && spec["heapConservativeBound"]?.isBoolean == true && !spec["heapConservativeBound"].booleanValue()
            && spec["estimatedHeapBytes"]?.isNull == true && !spec.has("arm") && !spec.has("heap")) { "BOOTSTRAP_DIAGNOSTIC_SCOPE" }
        for ((field, expected) in mapOf("sampleTrades" to 1000L, "pendingSample" to 100L, "agedIdentities" to 0L,
            "sourceActions" to 2100L, "historyRecords" to 2101L))
            require(FinancialHeapBounds.integer(spec[field], field, false) == expected) { "BOOTSTRAP_FIXED_COUNTS field=$field" }
    }

    private fun bootstrapLimits(): JsonNode = json.readTree("""{
      "sourceRecordMaxBytes":1024,"unsignedHistoryMaxBytes":65536,"historyChangesMax":64,
      "checksummedHistoryMaxBytes":65614,"resultMaxBytes":135324,"sourceActions":2100,"historyRecords":2101,
      "maxPollRecords":16,"maxPartitionFetchBytes":262144,"fetchMaxBytes":524288,
      "producerBufferBytes":4194304,"producerBatchBytes":65536,"maxRequestBytes":262144,
      "bufferedRecordsPerPartition":16,"topicMaxMessageBytes":262144,
      "ackJournalMaxBytes":33554432,"rawEncodedMaxBytes":150994944
    }""")

    internal fun parseBootstrapResult(bytes: ByteArray): JsonNode {
        require(bytes.size <= 135324) { "BOOTSTRAP_RESULT_BYTE_BOUND" }
        return json.readTree(bytes).also { value ->
            require(value["records"]?.isArray == true && value["records"].size() <= 2) { "BOOTSTRAP_RESULT_SHAPE_BOUND" }
            value["records"].forEach { require(json.writeValueAsBytes(it).size <= 65614
                && it["changes"]?.isArray == true && it["changes"].size() <= 64) { "BOOTSTRAP_HISTORY_BOUND" } }
        }
    }

    internal fun validateBootstrapRuntimePaths(config: JsonNode) {
        val owned = Path.of(requireNotNull(config["ownedRoot"]).asText()).toRealPath()
        val resources = requireNotNull(config["localResources"]); require(resources.isArray && resources.size() == 3) { "BOOTSTRAP_LOCAL_RESOURCE_SET" }
        require(resources.map { it["id"]?.asText() }.let { ids -> ids.all { !it.isNullOrBlank() } && ids.distinct().size == 3 }) { "BOOTSTRAP_LOCAL_RESOURCE_IDS" }
        val paths = mutableListOf<Path>()
        for ((field, category) in listOf("stateDir" to "localStore", "ackJournalDir" to "controllerJournal", "proofDir" to "proof")) {
            val value = requireNotNull(config[field]) { "BOOTSTRAP_LOCAL_RESOURCE_FIELD $field" }.asText()
            val path = Path.of(value).toRealPath()
            require(path.toString() == value && path.startsWith(owned) && path != owned && Files.isDirectory(path)) { "BOOTSTRAP_OWNED_LOCAL_PATH" }
            val rows = resources.filter { it["category"]?.asText() == category }; require(rows.size == 1 && rows.single()["path"]?.asText() == value) { "BOOTSTRAP_LOCAL_RESOURCE_BINDING $field" }
            require(paths.none { it.startsWith(path) || path.startsWith(it) }) { "BOOTSTRAP_LOCAL_RESOURCE_OVERLAP" }; paths.add(path)
        }
    }

    internal fun validateBootstrapBrokerScope(config: JsonNode, bootstrapServers: String, observed: JsonNode) {
        val registry = requireNotNull(config["registeredResources"]) { "BOOTSTRAP_BROKER_REGISTRY_REQUIRED" }
        val expected = requireNotNull(config["brokerScope"]) { "BOOTSTRAP_BROKER_SCOPE_REQUIRED" }
        require(registry["bootstrapServers"]?.asText() == bootstrapServers && expected["bootstrapServers"]?.asText() == bootstrapServers
            && expected["schema"]?.asText() == "financial-broker-scope-v1" && expected["metadataSource"]?.asText() == "actual AdminClient describeCluster"
            && expected["clusterId"]?.isTextual == true && expected["clusterId"].asText().isNotBlank() && expected == observed) { "BOOTSTRAP_BROKER_ENDPOINT_OR_CLUSTER_MISMATCH" }
        val containers = requireNotNull(registry["containers"]); require(containers.isArray && containers.size() == 3) { "BOOTSTRAP_BROKER_RESOURCE_SET" }
        val nodes = containers.sortedBy { it["brokerId"].asInt() }.map { c ->
            val endpoint = requireNotNull(c["hostKafkaEndpoint"]); require(endpoint["host"]?.asText() == "127.0.0.1"
                && endpoint["port"]?.isIntegralNumber == true && endpoint["port"].asInt() in 1..65535) { "BOOTSTRAP_BROKER_ENDPOINT_PIN" }
            mapOf("id" to c["brokerId"].asInt(), "host" to endpoint["host"].asText(), "port" to endpoint["port"].asInt())
        }
        require(nodes.map { it["id"] } == listOf(0, 1, 2) && observed["brokers"] == node(nodes)
            && bootstrapServers == nodes.joinToString(",") { "${it["host"]}:${it["port"]}" }) { "BOOTSTRAP_BROKER_NODE_MAPPING" }
    }

    private fun brokerScope(admin: AdminClient, bootstrapServers: String): JsonNode {
        val description = admin.describeCluster(); val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        val clusterId = description.clusterId().get(maxOf(1L, end - System.nanoTime()), TimeUnit.NANOSECONDS)
        val brokers = description.nodes().get(maxOf(1L, end - System.nanoTime()), TimeUnit.NANOSECONDS).sortedBy { it.id() }
        require(clusterId.isNotBlank() && brokers.size == 3) { "BROKER_SCOPE_EXACT_RF3_CLUSTER_REQUIRED" }
        return node(mapOf("schema" to "financial-broker-scope-v1", "bootstrapServers" to bootstrapServers, "clusterId" to clusterId,
            "brokers" to brokers.map { mapOf("id" to it.id(), "host" to it.host(), "port" to it.port()) }, "metadataSource" to "actual AdminClient describeCluster"))
    }

    internal fun validateBootstrapCorrectness(spec: JsonNode, specPath: Path, expectedBootstrapServers: String) {
        val evidence = requireNotNull(spec["correctness"]) { "BOOTSTRAP_E3_RAW_REQUIRED" }
        val root = specPath.toAbsolutePath().parent.toRealPath()
        fun raw(field: String): Path {
            require(spec[field]?.isTextual == true) { "BOOTSTRAP_E3_RAW_REQUIRED" }
            val planned = root.resolve(spec[field].asText()).normalize()
            require(Files.isRegularFile(planned)) { "BOOTSTRAP_E3_RAW_REQUIRED" }
            val file = planned.toRealPath()
            require(file == planned && file.startsWith(root) && file != root && Files.size(file) <= 1024L * 1024) { "BOOTSTRAP_E3_RAW_SCOPE" }
            return file
        }
        val markerPath = raw("correctnessEvidencePath")
        require(shaFile(markerPath) == evidence["evidenceSha256"]?.asText()) { "BOOTSTRAP_E3_RAW_HASH" }
        val marker = json.readTree(Files.readString(markerPath))
        val declared = evidence.deepCopy<ObjectNode>().apply { remove("evidenceSha256") }
        require(marker == declared && marker["schema"]?.asText() == "financial-e3-correctness-binding-v1" && marker["result"]?.asText() == "PASS") { "BOOTSTRAP_E3_RAW_IDENTITY" }
        for (field in listOf("sourceHead", "fixtureSha256", "candidateSha256")) require(marker[field] != null && marker[field] == spec[field]) { "BOOTSTRAP_E3_CANDIDATE_BINDING $field" }
        for (field in listOf("buildSha256", "classpathSha256")) require(marker[field] != null && marker[field] == spec["capability"][field]) { "BOOTSTRAP_E3_BUILD_BINDING $field" }
        require(marker["plannedBootstrapConfigSha256"] == spec["capability"]["configSha256"] && marker["sourceCandidateEvidenceSha256"] == spec["candidateEvidenceSha256"]) { "BOOTSTRAP_E3_PLANNED_CONFIG_BINDING" }
        require(marker["arms"] == node(mapOf("core" to 24, "golden" to 20, "external" to 4, "activation" to 1))
            && marker["scope"]?.asText() == "Actual same-candidate RF3/EOS financial proof: core24 + golden20 + external4 + activation1; E3 configuration and plan identities retained separately from planned bootstrap configuration.") { "BOOTSTRAP_E3_PROOF_SCOPE" }
        val manifestPath = raw("correctnessProofManifestEvidencePath")
        require(shaFile(manifestPath) == marker["proofManifestSha256"]?.asText()) { "BOOTSTRAP_E3_MANIFEST_HASH" }
        val manifest = json.readTree(Files.readString(manifestPath)); require(manifest["schema"]?.asText() == "financial-e3-proof-manifest-v1") { "BOOTSTRAP_E3_MANIFEST_SCHEMA" }
        for (field in listOf("sourceHead", "fixtureSha256", "candidateSha256", "buildSha256", "classpathSha256", "plannedBootstrapConfigSha256", "arms", "e3PlanSha256"))
            require(manifest[field] != null && manifest[field] == marker[field]) { "BOOTSTRAP_E3_MANIFEST_IDENTITY $field" }
        val proofRoot = Path.of(requireNotNull(spec["correctnessProofRoot"]).asText()).toRealPath()
        require(proofRoot.startsWith(root.parent) && proofRoot != root.parent && manifest["proofRoot"]?.asText() == proofRoot.toString()) { "BOOTSTRAP_E3_PROOF_ROOT" }
        val files = requireNotNull(manifest["files"]); require(files.isArray && files.size() in 1..4096) { "BOOTSTRAP_E3_FILE_SET" }
        val names = mutableListOf<String>(); var total = 0L
        for (row in files) {
            val planned = Path.of(row["path"].asText()); val file = planned.toRealPath(); val size = Files.size(file)
            require(file == planned && file.startsWith(proofRoot) && Files.isRegularFile(file) && size in 0..8L * 1024 * 1024
                && row["sizeBytes"]?.isIntegralNumber == true && row["sizeBytes"].asLong() == size && shaFile(file) == row["sha256"]?.asText()) { "BOOTSTRAP_E3_FILE_HASH" }
            total = Math.addExact(total, size); require(total <= 256L * 1024 * 1024) { "BOOTSTRAP_E3_RAW_BOUND" }; names.add(file.toString())
        }
        val actualFiles = mutableListOf<String>()
        Files.walk(proofRoot, 65).use { paths ->
            val iterator = paths.iterator(); var entries = 0
            while (iterator.hasNext()) {
                val file = iterator.next(); entries++
                require(entries <= 65536 && proofRoot.relativize(file).nameCount <= 64 && !Files.isSymbolicLink(file)) { "BOOTSTRAP_E3_TREE_BOUND_OR_SYMLINK" }
                if (!Files.isDirectory(file)) {
                    require(Files.isRegularFile(file) && actualFiles.size < 4096) { "BOOTSTRAP_E3_FILE_SET" }; actualFiles.add(file.toString())
                }
            }
        }
        require(names == names.sorted() && names.distinct().size == names.size && names == actualFiles.sorted()) { "BOOTSTRAP_E3_COMPLETE_FILE_SET" }
        val candidate = json.readTree(Files.readString(raw("candidateEvidencePath")))
        require(shaFile(raw("candidateEvidencePath")) == spec["candidateEvidenceSha256"]?.asText()) { "BOOTSTRAP_E3_SOURCE_RAW_HASH" }
        val sourceHashes = candidate["sourceSha256"]
        val compiled = linkedMapOf<String, String>()
        val classpath = spec["capability"]["classpathEntries"].map { it.asText() }
        for (entry in classpath) {
            val path = Path.of(entry)
            if (Files.isRegularFile(path)) compiled[path.toString()] = shaFile(path)
            else {
                val dir = path.resolve("com/reef/platform/calcify/financial")
                if (Files.isDirectory(dir)) Files.list(dir).use { paths -> paths.filter { it.fileName.toString().startsWith("Financial") && it.fileName.toString().endsWith(".class") }
                    .sorted().forEach { compiled[it.toString()] = shaFile(it) } }
            }
        }
        require(compiled.keys.any { Path.of(it).fileName.toString() == "FinancialKernel.class" }) { "BOOTSTRAP_E3_COMPILED_COVERAGE" }
        for (arm in listOf("core", "golden", "external", "activation")) {
            val planHash = marker["e3PlanSha256"]?.get(arm)?.asText()
            val selected = files.filter { Path.of(it["path"].asText()).fileName.toString() == "plan.json" && it["sha256"].asText() == planHash }
            require(planHash?.matches(Regex("[a-f0-9]{64}")) == true && selected.size == 1) { "BOOTSTRAP_E3_PLAN_BINDING" }
            val planPath = Path.of(selected.single()["path"].asText()); val plan = json.readTree(Files.readString(planPath))
            require(plan["schema"]?.asText() == "financial-e3-plan-v1" && plan["fixtureSha256"] == spec["fixtureSha256"]
                && plan["classpath"]?.asText() == classpath.joinToString(File.pathSeparator) && plan["compiledHashes"] == node(compiled)) { "BOOTSTRAP_E3_ACTUAL_BUILD_BINDING" }
            require(plan["broker"]?.asText() == expectedBootstrapServers) { "BOOTSTRAP_E3_BROKER_ENDPOINT_BINDING" }
            val financial = "services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial/"
            sourceHashes.properties().filter { it.key.startsWith(financial) && it.key.endsWith(".kt") }.forEach { (name, value) ->
                require(plan["sourceHashes"]?.get(name.removePrefix(financial)) == value) { "BOOTSTRAP_E3_ACTUAL_SOURCE_BINDING" }
            }
            for (name in listOf("scripts/dev/calcify-financial/broker-proof.mjs", "scripts/dev/calcify-financial/lib/external-faults.mjs", "scripts/dev/calcify-financial/proof-supervisor.mjs"))
                require(sourceHashes[name] != null && plan["runnerHashes"]?.get(name) == sourceHashes[name]) { "BOOTSTRAP_E3_RUNNER_BINDING" }
            val resultPath = planPath.parent.resolve("results.json"); require(names.contains(resultPath.toString())) { "BOOTSTRAP_E3_RESULTS_REQUIRED" }
            val results = json.readTree(Files.readString(resultPath))["results"]
            val expectedNames = when (arm) {
                "core" -> listOf("happy") + plan["mutationBoundaries"].map { "mutation-${it["index"].asInt()}" } + listOf("forward", "serialization", "local-state-loss", "staged-phase-loss")
                "golden" -> (0..19).map { "golden-$it" }
                "external" -> listOf("producer-failure", "committed-output-before-controller-ack", "stale-owner-resume-after-takeover", "majority-one-broker-unavailable")
                else -> listOf("activation-quartet")
            }
            require(results != null && results.isArray && results.size() == marker["arms"][arm].asInt()
                && results.map { it["name"].asText() }.sorted() == expectedNames.sorted()
                && results.all { it["after"]?.get("pass")?.isBoolean == true && it["after"]["pass"].booleanValue() }) { "BOOTSTRAP_E3_ACTUAL_PASS_SET" }
            if (arm == "activation") require(results.all { it["before"]["pass"].asBoolean() && it["idle"]["pass"].asBoolean() }) { "BOOTSTRAP_E3_ACTIVATION_PREFIX" }
        }
    }

    /** Finite empirical entry point. Never construct a placeholder conservative bound. */
    internal fun validateBootstrapCapability(capability: JsonNode, actual: JsonNode) {
        for (field in listOf("sourceHead", "fixtureSha256", "configSha256", "buildSha256", "classpathSha256", "javaVersion", "javaVendor", "classpathEntries"))
            require(capability[field] != null && capability[field] == actual[field]) { "BOOTSTRAP_CAPABILITY_DRIFT field=$field" }
        val frozenMax = FinancialHeapBounds.integer(capability["maxHeapBytes"], "maxHeapBytes")
        val actualMax = FinancialHeapBounds.integer(actual["maxHeapBytes"], "maxHeapBytes")
        require(frozenMax == actualMax) { "BOOTSTRAP_CAPABILITY_DRIFT field=maxHeapBytes" }
        require(actualMax == FinancialHeapBounds.AUTHORIZED_MAX) { "BOOTSTRAP_PINNED_MAX_REQUIRED" }
    }

    internal fun validateBootstrapActivation(summary: JsonNode, request: JsonNode, ordinal: Long?, offset: Long?, memberOffset: Long) {
        val actualCount = FinancialHeapBounds.integer(summary["historyRecords"], "bootstrapHistoryRecords")
        val expectedCount = FinancialHeapBounds.integer(request["historyRecords"], "bootstrapHistoryRecords")
        require(summary["ownerSha256"] == request["ownerSha256"] && summary["historyChecksum"] == request["historyChecksum"]
            && actualCount == expectedCount && actualCount == 2101L && ordinal == 2099L
            && offset == memberOffset) { "BOOTSTRAP_MANAGED_ACTIVATION_PARITY" }
    }

    internal fun bootstrapGuard(spec: JsonNode, specPath: Path, configPath: Path): FinancialHeapGuard {
        validateBootstrapCounts(spec)
        require(spec["sourceHead"]?.asText()?.matches(Regex("[a-f0-9]{40}")) == true) { "BOOTSTRAP_SOURCE_HEAD" }
        require(spec["limits"] == bootstrapLimits()) { "BOOTSTRAP_LIMITS_DRIFT" }
        val unsigned = spec.deepCopy<ObjectNode>().apply { remove("bootstrapSha256") }
        require(sha(json.writeValueAsBytes(unsigned)) == spec["bootstrapSha256"]?.asText()) { "BOOTSTRAP_FROZEN_HASH" }
        val root = specPath.toAbsolutePath().parent.toRealPath()
        fun raw(name: String, maximum: Long = 1024L * 1024): Path {
            require(spec[name]?.isTextual == true) { "BOOTSTRAP_RAW_EVIDENCE_REQUIRED" }
            val file = root.resolve(spec[name].asText()).normalize().toRealPath()
            require(file.startsWith(root) && file != root && Files.isRegularFile(file) && Files.size(file) <= maximum) { "BOOTSTRAP_RAW_EVIDENCE_SCOPE" }
            return file
        }
        val capability = requireNotNull(spec["capability"]); val review = requireNotNull(spec["review"])
        var candidate: JsonNode? = null
        for (kind in listOf("capability", "review", "candidate")) {
            val file = raw("${kind}EvidencePath")
            require(shaFile(file) == spec["${kind}EvidenceSha256"]?.asText()) { "BOOTSTRAP_RAW_EVIDENCE_HASH" }
            val value = json.readTree(Files.readString(file))
            if (kind == "candidate") candidate = value else require(value == spec[kind]) { "BOOTSTRAP_RAW_EVIDENCE_IDENTITY" }
        }
        val fixture = raw("fixtureEvidencePath")
        require(shaFile(fixture) == spec["fixtureSha256"]?.asText() && Files.size(configPath) <= 1024L * 1024
            && shaFile(configPath) == capability["configSha256"]?.asText()) { "BOOTSTRAP_FIXTURE_CONFIG_HASH" }
        val actual = heapCapability(spec["sourceHead"].asText(), fixture, configPath)
        require(actual["vmArguments"] == node(listOf("-Xms128m", "-Xmx768m"))) { "BOOTSTRAP_PINNED_VM_REQUIRED" }
        validateBootstrapCapability(capability, actual)
        val source = requireNotNull(candidate)
        require(source["schema"]?.asText() == "financial-candidate-source-v1" && source["sourceHead"] == spec["sourceHead"]
            && source["candidateSha256"] == spec["candidateSha256"] && sha(json.writeValueAsBytes(source["sourceSha256"])) == spec["candidateSha256"].asText()) { "BOOTSTRAP_CANDIDATE_HASH" }
        val sourceRoot = Path.of(source["sourceRoot"].asText()).toRealPath()
        val classes = sourceRoot.resolve("services/platform-runtime/build/classes/kotlin/test")
        require(actual["classpathEntries"].any { it.asText() == classes.toString() }) { "BOOTSTRAP_SOURCE_BUILD_ROOT" }
        val financialPath = "services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial"
        val required = Files.list(sourceRoot.resolve(financialPath)).use { paths -> paths.filter {
            it.fileName.toString().startsWith("Financial") && it.fileName.toString().endsWith(".kt")
        }.map { "$financialPath/${it.fileName}" }.toList() }
        require(required.isNotEmpty() && required.all { source["sourceSha256"].has(it) }) { "BOOTSTRAP_SOURCE_COVERAGE" }
        for (name in listOf("broker-proof.mjs", "rate-proof.mjs", "bootstrap-calibration.mjs", "rate-supervision.mjs", "proof-supervisor.mjs",
            "physical-evidence.mjs", "physical-adapter.mjs", "lib/external-faults.mjs"))
            require(source["sourceSha256"].has("scripts/dev/calcify-financial/$name")) { "BOOTSTRAP_SOURCE_COVERAGE" }
        source["sourceSha256"].properties().forEach { (name, expected) ->
            val file = sourceRoot.resolve(name).normalize().toRealPath()
            require(file.startsWith(sourceRoot) && Files.isRegularFile(file) && Files.size(file) <= 1024L * 1024
                && shaFile(file) == expected.asText()) { "BOOTSTRAP_SOURCE_DRIFT" }
        }
        require(review["schema"]?.asText() == "financial-bootstrap-review-v1" && review["verdict"]?.asText() == "READY_BOUNDED_DIAGNOSTIC"
            && review["candidateSha256"] == spec["candidateSha256"] && review["limitsSha256"]?.asText() == sha(json.writeValueAsBytes(spec["limits"]))) { "BOOTSTRAP_REVIEW_REQUIRED" }
        for (field in listOf("fixtureSha256", "configSha256", "buildSha256", "classpathSha256")) require(review[field] == capability[field]) { "BOOTSTRAP_REVIEW_SCOPE" }
        for (field in listOf("sampleTrades", "pendingSample", "agedIdentities")) require(review[field] == spec[field]) { "BOOTSTRAP_REVIEW_COUNTS" }
        val config = json.readTree(Files.readString(configPath))
        validateBootstrapCorrectness(spec, specPath, requireNotNull(config["registeredResources"]?.get("bootstrapServers")).asText())
        require(config["policy"] == json.readTree(Files.readString(fixture))["policy"]) { "BOOTSTRAP_POLICY_FIXTURE" }
        validateBootstrapRuntimePaths(config)
        return FinancialHeapGuard.diagnosticBootstrap(1000, 100, 0, actual["maxHeapBytes"].asLong(),
            spec["preloadOperationalCeilingBytes"]?.let { FinancialHeapBounds.integer(it, "preloadOperationalCeilingBytes") })
    }

    /** Actual caller seam: guard starts before setup and closes on every exceptional path.
     * Body retains guard through worker close, result-only replay and atomic result publication. */
    internal fun <T> withHeapProtection(guard: FinancialHeapGuard, body: (FinancialHeapGuard) -> T): T {
        try { guard.start(); return body(guard).also { if (guard.isClosed) guard.checkpoint("lifecycle-return") else guard.refresh("lifecycle-return") } }
        finally { guard.close() }
    }

    /** Shared actual result-only replay batch boundary, independently injectable without a broker. */
    internal fun <T> heapReplayBatch(guard: FinancialHeapGuard, records: Iterable<T>, applyRecord: (T) -> Unit) {
        guard.checkpoint("result-only-replay-batch")
        records.forEach { record -> guard.checkpoint("result-only-replay-record"); applyRecord(record); guard.checkpoint("result-only-replay-record-applied") }
    }

    /** Main thread polls heap failure during interruptible blocking producer operations. */
    internal fun <T> heapAwait(guard: FinancialHeapGuard, stage: String, timeoutMillis: Long, action: () -> T): T {
        guard.checkpoint("$stage-before-operation")
        val task = FutureTask<T> { action() }
        val thread = Thread(task, "financial-$stage").apply { isDaemon = true; start() }
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (true) {
                guard.checkpoint(stage)
                require(System.nanoTime() < end) { "rate operation timeout stage=$stage" }
                try { return task.get(25, TimeUnit.MILLISECONDS).also { guard.refresh("$stage-complete") } }
                catch (_: TimeoutException) { /* Keep heap checkpoint separate from blocked operation. */ }
            }
        } finally { if (!task.isDone) task.cancel(true); thread.join(500) }
    }

    internal data class HeapArtifactSnapshot(val observation: Map<String, Any?>, val resourcePeakBytes: Long, val peakScope: String)

    /** Reporting cutoff only: guard continues sampling through serialization and staged writes. */
    internal fun heapArtifactSnapshot(guard: FinancialHeapGuard, physicalSamplerPeakBytes: Long): HeapArtifactSnapshot {
        val observation = guard.telemetry()
        val scope = "Sampled through result assembly snapshot; final serialization and staged writes excluded"
        return HeapArtifactSnapshot(observation + ("reportedPeakScope" to scope),
            maxOf(physicalSamplerPeakBytes, observation["heapPeakBytes"] as Long), scope)
    }

    /** Same provider serves hot input and cold activation; historical lookup stays disk-backed. */
    internal fun acknowledgedMember(journal: FinancialAckJournal, index: Long, checkpoint: () -> Unit = {}): JsonNode {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            journal.checkHealthy(); checkpoint(); journal.member(index)?.let { return it }; LockSupport.parkNanos(100_000)
        }
        error("ACK membership wait expired index=$index")
    }

    internal fun streamProperties(base: Properties, prefix: String, stateDir: Path, bootstrap: Boolean) = Properties().apply {
        putAll(base); put(StreamsConfig.APPLICATION_ID_CONFIG, "$prefix-streams"); put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString())
        put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2); put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3)
        put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1); put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 2000)
        put("topic.min.insync.replicas", 2); put("topic.write.caching", false); put("consumer.max.poll.records", if (bootstrap) 16 else 128)
        put("consumer.max.poll.interval.ms", 30000)
        put("default.deserialization.exception.handler", "org.apache.kafka.streams.errors.LogAndFailExceptionHandler")
        put("default.production.exception.handler", "org.apache.kafka.streams.errors.DefaultProductionExceptionHandler")
        put("processing.exception.handler", "org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler")
        if (bootstrap) {
            put("topic.max.message.bytes", 262144); put(StreamsConfig.BUFFERED_RECORDS_PER_PARTITION_CONFIG, 16)
            put("consumer.fetch.max.bytes", 524288); put("consumer.max.partition.fetch.bytes", 262144)
            put("producer.buffer.memory", 4194304L); put("producer.batch.size", 65536); put("producer.max.request.size", 262144)
            put("producer.max.in.flight.requests.per.connection", 1); put("producer.compression.type", "none")
            put("producer.max.block.ms", 10000L); put("producer.request.timeout.ms", 10000); put("producer.delivery.timeout.ms", 30000)
        }
    }

    private fun consumerProperties(base: Properties, bootstrap: Boolean) = Properties().apply {
        putAll(base); put("enable.auto.commit", false); put("auto.offset.reset", "earliest"); put("isolation.level", "read_committed")
        if (bootstrap) { put("max.poll.records", 16); put("fetch.max.bytes", 524288); put("max.partition.fetch.bytes", 262144) }
    }

    internal fun bootstrapClientConfiguration(properties: Properties): JsonNode {
        val streams = StreamsConfig(properties)
        fun finite(values: Map<String, Any>, expected: Map<String, Any>): Map<String, Any> {
            for ((key, wanted) in expected) require(values[key].toString() == wanted.toString()) { "BOOTSTRAP_EFFECTIVE_CONFIG_MISMATCH $key" }
            return expected.keys.associateWith { values.getValue(it) }
        }
        val consumers = mapOf("max.poll.records" to 16, "fetch.max.bytes" to 524288, "max.partition.fetch.bytes" to 262144, "isolation.level" to "read_committed")
        return node(mapOf("schema" to "financial-bootstrap-effective-clients-v1",
            "producer" to finite(streams.getProducerConfigs("finite-producer"), mapOf("buffer.memory" to 4194304L, "batch.size" to 65536,
                "max.request.size" to 262144, "max.in.flight.requests.per.connection" to 1, "compression.type" to "none", "max.block.ms" to 10000L,
                "request.timeout.ms" to 10000, "delivery.timeout.ms" to 30000)),
            "mainConsumer" to finite(streams.getMainConsumerConfigs("finite-group", "finite-main", 0), consumers),
            "restoreConsumer" to finite(streams.getRestoreConsumerConfigs("finite-restore"), consumers),
            "bufferedRecordsPerPartition" to streams.getInt(StreamsConfig.BUFFERED_RECORDS_PER_PARTITION_CONFIG)))
    }

    private fun bootstrapRecovery(args: Array<String>) {
        require(args.size == 7 && args[2].startsWith("financial-s1-")) { "bootstrap-recover broker prefix config spec recovery-request result" }
        val configPath = Path.of(args[3]); val specPath = Path.of(args[4]); val requestPath = Path.of(args[5]); val resultPath = Path.of(args[6])
        require(Files.size(specPath) <= 1024L * 1024 && Files.size(requestPath) <= 1024L * 1024)
        val spec = json.readTree(Files.readString(specPath)); val config = json.readTree(Files.readString(configPath)); val request = json.readTree(Files.readString(requestPath))
        val guard = bootstrapGuard(spec, specPath, configPath)
        withHeapProtection(guard) { heap ->
            val start = System.nanoTime(); val prefix = args[2]; val props = Properties().apply { put("bootstrap.servers", args[1]) }
            val balances = node(mapOf("buyerCash" to "11000000000", "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to "1100"))
            AdminClient.create(props).use { admin ->
                validateBootstrapBrokerScope(config, args[1], brokerScope(admin, args[1]))
                val uuid = admin.describeTopics(listOf("$prefix-input")).allTopicNames().get(3, TimeUnit.SECONDS).getValue("$prefix-input").topicId().toString()
                require(uuid == request["sourceUuid"].asText() && request["bootstrapSha256"] == spec["bootstrapSha256"]) { "BOOTSTRAP_RECOVERY_SCOPE" }
                FinancialAckJournal(Path.of(config["ackJournalDir"].asText()), FinancialAckJournal.Scope(prefix, "$prefix-input", uuid), maxBytes = 33554432).use { journal ->
                    require(journal.recoveredCount == 2100L && journal.publishedCount == 2100L) { "BOOTSTRAP_RECOVERY_JOURNAL_COUNT" }
                    val activation = AtomicReference<JsonNode>(); val failure = AtomicReference<Throwable>(); val running = CountDownLatch(1)
                    val workerConfig = node(mapOf("genesis" to mapOf("hot" to mapOf("balances" to balances)), "policy" to config["policy"], "acceptedManifest" to mapOf("inputTopicId" to uuid)))
                    val topology = FinancialBrokerProbe.topology(workerConfig, "", prefix,
                        { index -> acknowledgedMember(journal, index) { heap.checkpoint("managed-recovery-membership") } }, uuid,
                        onActivation = { cut ->
                            heap.refresh("managed-activation-cut"); val owner = requireNotNull(cut.owners["hot"]) { "BOOTSTRAP_RECOVERY_OWNER_MISSING" }
                            val summary = node(mapOf("ownerSha256" to digest(owner.ownerView()), "historyChecksum" to owner.lastRecord()!!["checksum"].asText(),
                                "historyRecords" to owner.historySequence(), "ordinal" to cut.ordinal, "offset" to cut.offset))
                            Files.writeString(resultPath.parent.resolve("activation-attempt.json"), json.writeValueAsString(summary) + "\n", java.nio.file.StandardOpenOption.CREATE_NEW)
                            validateBootstrapActivation(summary, request, cut.ordinal, cut.offset, journal.member(2099)!!["offset"].asLong())
                            activation.set(summary); heap.refresh("managed-activation-parity")
                        })
                    val effectiveProperties = streamProperties(props, prefix, Path.of(config["stateDir"].asText()), true)
                    bootstrapClientConfiguration(effectiveProperties)
                    val streams = KafkaStreams(topology, effectiveProperties)
                    streams.setStateListener { next, _ -> if (next == KafkaStreams.State.RUNNING) running.countDown() }
                    streams.setUncaughtExceptionHandler { error -> failure.compareAndSet(null, error); StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
                    try {
                        streams.start(); val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                        while (running.count > 0 && System.nanoTime() < end && failure.get() == null) { heap.checkpoint("managed-startup-wait"); running.await(25, TimeUnit.MILLISECONDS) }
                        failure.get()?.let { throw IllegalStateException("managed recovery failed", it) }
                        require(running.count == 0L && activation.get() != null) { "BOOTSTRAP_MANAGED_STARTUP_TIMEOUT" }
                    } finally { require(streams.close(Duration.ofSeconds(15))) { "BOOTSTRAP_MANAGED_CLOSE_TIMEOUT" } }
                    journal.checkHealthy(); heap.refresh("managed-recovery-closed")
                    val result = mapOf("schema" to "financial-bootstrap-process-recovery-v1", "bootstrapSha256" to spec["bootstrapSha256"].asText(),
                        "processId" to ProcessHandle.current().pid(), "sourceUuid" to uuid, "recoveredMembers" to journal.recoveredCount,
                        "controllerAdmissionsReplayed" to 0, "oldControllerAdmissionTimes" to "unknown", "activation" to activation.get(),
                        "elapsedMs" to (System.nanoTime() - start) / 1_000_000, "heapObservation" to heapArtifactSnapshot(heap, 0).observation,
                        "firstFreshResult" to null, "scope" to "actual new JVM and KafkaStreams instance; complete certified activation; no new economic inputs")
                    heap.publish(resultPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n")
                }
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "broker-scope") {
            require(args.size == 2) { "broker-scope exact-bootstrap-endpoints" }
            val admin = AdminClient.create(Properties().apply { put("bootstrap.servers", args[1]); put("default.api.timeout.ms", 3000); put("request.timeout.ms", 3000) })
            try { println(json.writeValueAsString(brokerScope(admin, args[1]))) } finally { admin.close(Duration.ofSeconds(2)) }
            return
        }
        if (args.firstOrNull() == "bootstrap-recover") { bootstrapRecovery(args); return }
        if (args.firstOrNull() == "heap-capability") {
            require(args.size == 4) { "heap-capability source-head fixture-path config-path" }
            println(json.writeValueAsString(heapCapability(args[1], Path.of(args[2]), Path.of(args[3])))); return
        }
        if (args.firstOrNull() == "self-check") { selfCheck(json.readTree(Files.readString(Path.of(args[1])))["policy"]); return }
        require(args.size >= 4) { "calibrate|run bootstrap financial-s1-prefix config-file --financial-rate-* PATH" }
        val mode = args[0]; val broker = args[1]; val prefix = args[2]
        require(mode in setOf("calibrate", "run", "bootstrap-calibrate", "diagnostic-run") && prefix.startsWith("financial-s1-"))
        val bootstrap = mode == "bootstrap-calibrate"; val diagnostic = mode == "diagnostic-run"; val calibrationMode = mode != "run" && !diagnostic
        val configPath = Path.of(args[3])
        fun flag(name: String): Path = Path.of(args[args.indexOf(name).also { require(it >= 0) } + 1])
        val specPath = flag(if (bootstrap) "--financial-calibration-bootstrap-request" else if (calibrationMode) "--financial-rate-calibration-request" else "--financial-rate-policy")
        val spec = json.readTree(Files.readString(specPath))
        val outPath = flag(if (bootstrap) "--financial-calibration-bootstrap" else if (calibrationMode) "--financial-rate-calibration" else "--financial-rate-measurement")
        require(if (calibrationMode) spec["correctness"]["result"].asText() == "PASS" else spec["status"].asText() == if (diagnostic) "FROZEN_EMPIRICAL_DIAGNOSTIC" else "FROZEN") { "E3/frozen load gate" }
        if (mode == "run") {
            val frozen = spec.deepCopy<ObjectNode>().apply { remove("policySha256") }
            require(sha(json.writeValueAsBytes(frozen)) == spec["policySha256"]?.asText()) { "FROZEN_POLICY_HASH_MISMATCH" }
        }
        val count = FinancialHeapBounds.integer(spec[if (calibrationMode) "sampleTrades" else "expectedTimedTrades"], "timedTrades")
        require(count in 1L..3_000_000L)
        val rate = if (calibrationMode) 0L else spec["arm"]["rate"].asLong()
        val seconds = if (calibrationMode) 0L else spec["arm"]["seconds"].asLong()
        val aged = if (mode == "run" && spec["arm"]["state"].asText() == "aged") FinancialHeapBounds.integer(spec["aged"]["identities"], "agedIdentities", false) else 0L
        val pending = if (calibrationMode) FinancialHeapBounds.integer(spec["pendingSample"], "pendingSample", false) else if (spec["arm"]["state"].asText() == "aged") FinancialHeapBounds.integer(spec["aged"]["pendingItems"], "pendingItems", false) else 0L
        val heap = if (diagnostic) diagnosticGuard(spec, specPath, configPath) else if (bootstrap) bootstrapGuard(spec, specPath, configPath) else heapGuard(spec, specPath, configPath, count, aged, pending)
        withHeapProtection(heap) { guard ->
        val diskCap = if (diagnostic) diagnosticDiskBudget(spec) else DISK_CAP
        val config = json.readTree(Files.readString(configPath))
        require(config["registeredResources"]?.get("bootstrapServers")?.asText() == broker) { "RATE_BROKER_ARGUMENT_NOT_REGISTERED" }
        val opening = Math.addExact(Math.addExact(aged, pending), count)
        val balances = node(mapOf("buyerCash" to Math.multiplyExact(opening, PRICE).toString(), "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to opening.toString()))
        if (mode == "run" || diagnostic) require(spec["openingResources"]["cashNanos"].asText() == balances["buyerCash"].asText() && spec["openingResources"]["shares"].asText() == balances["sellerShares"].asText()) { "frozen genesis resource mismatch" }
        val stateDir = Path.of(config.path("stateDir").asText("/tmp/$prefix-state")); Files.createDirectories(stateDir)
        val props = Properties().apply { put("bootstrap.servers", broker) }
        val capacity = Semaphore(16384)
        val stageLock = Any()
        val fatal = AtomicReference<Throwable>(); val done = AtomicReference(false)
        val ordinal = AtomicLong(); val covered = AtomicLong(); val offered = AtomicLong(); val admitted = AtomicLong(); val captured = AtomicLong(); val settled = AtomicLong()
        val deadlineOffered = AtomicLong(); val deadlineAdmitted = AtomicLong(); val deadlineCaptured = AtomicLong(); val deadlineSettled = AtomicLong()
        val callbackSuccess = AtomicLong(); val deadlineCallbackSuccess = AtomicLong()
        val timedStart = AtomicLong()
        fun beforeDeadline(now: Long = System.nanoTime()) = timedStart.get() > 0 && (seconds == 0L || now - timedStart.get() <= seconds * 1_000_000_000L)
        val inputBytes = AtomicLong(); val outputBytes = AtomicLong(); val keys = AtomicLong()
        val latency = mutableListOf<Long>(); val lag = mutableListOf<Map<String, Long>>()
        val rawPath = outPath.parent.resolve("encoded-records.bin"); val raw = if (calibrationMode) Files.newOutputStream(rawPath) else null
        val rawEncodedCap = if (bootstrap) 150994944L else RAW_CAP
        var rawBytes = 0L
        fun retain(bytes: ByteArray) { if (raw != null) synchronized(raw) { require(rawBytes + bytes.size <= rawEncodedCap); raw.write(bytes); rawBytes += bytes.size } }
        val reference = Reference(balances, config["policy"])
        try {
        guard.refresh("before-client-topic-setup")
        AdminClient.create(props).use { admin ->
            validateBootstrapBrokerScope(config, broker, brokerScope(admin, broker))
            admin.createTopics(listOf("input", "results").map { NewTopic("$prefix-$it", 1, 3.toShort()).configs(mapOf("cleanup.policy" to "delete", "write.caching" to "false", "min.insync.replicas" to "2")
                + if (bootstrap || diagnostic) mapOf("max.message.bytes" to "262144") else emptyMap()) }).all().get(30, TimeUnit.SECONDS)
            val uuid = admin.describeTopics(listOf("$prefix-input")).allTopicNames().get().getValue("$prefix-input").topicId().toString()
            val journalDir = Path.of(config.path("ackJournalDir").asText(outPath.parent.resolve("ack-journal").toString()))
            val journalCap = if (bootstrap) spec["limits"]["ackJournalMaxBytes"].asLong() else spec.get("ackJournalMaxBytes")?.let { FinancialHeapBounds.integer(it, "ackJournalMaxBytes") } ?: RAW_CAP
            if (bootstrap) require(journalCap == 33554432L) { "BOOTSTRAP_JOURNAL_CAP" }
            FinancialAckJournal(journalDir, FinancialAckJournal.Scope(prefix, "$prefix-input", uuid), maxBytes = journalCap,
                onPublished = { member, _ -> synchronized(stageLock) {
                    val admissionNano = System.nanoTime()
                    if (member["phase"].asText() == "timed" && member["kind"].asText() == "SETTLE") {
                        admitted.incrementAndGet(); if (beforeDeadline(admissionNano)) deadlineAdmitted.incrementAndGet()
                    }
                } }).use { journal ->
            fun checkWorkers() { journal.checkHealthy(); fatal.get()?.let { throw IllegalStateException("worker/observer failure", it) } }
            val workerConfig = node(mapOf("genesis" to mapOf("hot" to mapOf("balances" to balances)), "policy" to config["policy"], "acceptedManifest" to mapOf("inputTopicId" to uuid)))
            val topology = FinancialBrokerProbe.topology(workerConfig, "", prefix, { index ->
                acknowledgedMember(journal, index) { guard.checkpoint("ACK-membership"); checkWorkers() }
            }, uuid)
            val effectiveProperties = streamProperties(props, prefix, stateDir, bootstrap || diagnostic)
            val effectiveClients = if (bootstrap) bootstrapClientConfiguration(effectiveProperties) else null
            val streams = KafkaStreams(topology, effectiveProperties)
            var physicalManifest: JsonNode? = null; var clockReceipt: JsonNode? = null
            var effectiveTopics: JsonNode? = null
            val physicalCheckpoints = mutableListOf<JsonNode>()
            fun physicalCheckpoint(stage: String) {
                if (bootstrap) {
                    val checkpoint = FinancialPhysicalInventory.collectCheckpoint(guard, admin, requireNotNull(physicalManifest),
                        Path.of(config["nodeExecutable"].asText()), Path.of(config["physicalAdapterScript"].asText()), outPath.parent, stage, requireNotNull(clockReceipt)) as ObjectNode
                    for (kind in listOf("manifest", "inventory", "artifact")) checkpoint.put("${kind}Sha256", shaFile(Path.of(checkpoint["${kind}Path"].asText())))
                    physicalCheckpoints.add(checkpoint)
                }
            }
            val running = CountDownLatch(1)
            streams.setStateListener { next, _ -> if (next == KafkaStreams.State.RUNNING) running.countDown() }
            streams.setUncaughtExceptionHandler { error -> fatal.compareAndSet(null, error); StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
            fun counts() = synchronized(stageLock) { mapOf("offered" to offered.get(), "callbackSuccess" to callbackSuccess.get(), "admitted" to admitted.get(), "decided" to captured.get(), "settled" to settled.get(), "pending" to captured.get() - settled.get()) }
            fun physical(): Pair<Long, Long>? = runCatching {
                val brokers = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).map { it.id() }; var ordinary = 0L; var changelog = 0L
                admin.describeLogDirs(brokers).allDescriptions().get(10, TimeUnit.SECONDS).values.forEach { dirs -> dirs.values.forEach { dir -> dir.replicaInfos().forEach { (tp, info) -> if (tp.topic().startsWith(prefix)) { if (tp.topic().endsWith("-changelog")) changelog += info.size() else ordinary += info.size() } } } }; ordinary to changelog
            }.getOrNull()
            fun localBytes() = Files.walk(stateDir).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
            fun rss(): Long? = runCatching { Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong() * 1024 }.getOrNull() ?: runCatching {
                val process = ProcessBuilder("/bin/ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start()
                require(process.waitFor(2, TimeUnit.SECONDS)); require(process.exitValue() == 0); process.inputStream.bufferedReader().readText().trim().toLong() * 1024
            }.getOrNull()
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val observer = Thread {
                try { KafkaConsumer(consumerProperties(props, bootstrap || diagnostic), StringDeserializer(), StringDeserializer()).use { consumer ->
                    val tp = TopicPartition("$prefix-results", 0); consumer.assign(listOf(tp)); consumer.seekToBeginning(listOf(tp))
                    while (!done.get()) for (record in consumer.poll(Duration.ofMillis(50))) {
                        guard.checkpoint("observer-record"); checkWorkers(); val bytes = record.value().toByteArray()
                        val value = if (bootstrap || diagnostic) parseBootstrapResult(bytes) else json.readTree(bytes); val index = value["inputOrdinal"].asLong(); require(index == covered.get())
                        val member = requireNotNull(journal.member(index)) { "missing durable acknowledged member" }; require(value["inputOffset"].asLong() == member["offset"].asLong())
                        retain(bytes); outputBytes.addAndGet(bytes.size.toLong())
                        val histories = value["records"]
                        require(histories.size() == if (index == 0L) 2 else 1) { "unique EXECUTE missing business history" }
                        if (index == 0L) require(histories[0]["kind"].asText() == "GENESIS" && histories[0]["input"].isNull)
                        val business = histories.last(); require(business["kind"].asText() == "BUSINESS" && canonical(business["input"]) == canonical(member["input"])) { "business input/source mismatch" }
                        histories.forEach { history -> reference.accept(history); keys.addAndGet(history["changes"].size().toLong() + 1) }; keys.addAndGet(2)
                        synchronized(stageLock) { if (member["phase"].asText() == "timed") {
                            val kind = member["kind"].asText(); require(value["disposition"].asText() == if (kind == "CAPTURE") "CAPTURED" else "SETTLED")
                            if (kind == "CAPTURE") { captured.incrementAndGet(); if (beforeDeadline()) deadlineCaptured.incrementAndGet() } else { settled.incrementAndGet(); if (beforeDeadline()) deadlineSettled.incrementAndGet(); if (member["trade"].asLong() % 1000 == 0L) synchronized(latency) { latency.add((System.nanoTime() - member["offeredNano"].asLong()) / 1_000_000) } }
                        } }
                        covered.incrementAndGet(); capacity.release()
                    }
                } } catch (error: Throwable) { fatal.compareAndSet(null, error) }
            }
            fun waitCovered(timeoutMs: Long = 120000) { val end = System.nanoTime() + timeoutMs * 1_000_000; while (covered.get() < ordinal.get() && System.nanoTime() < end && fatal.get() == null) { guard.checkpoint("drain"); checkWorkers(); Thread.sleep(10) }; guard.refresh("drain-complete"); checkWorkers(); require(covered.get() == ordinal.get() && journal.publishedCount == ordinal.get()) { "coverage/durable-membership/drain timeout" } }
            var heapPeak = 0L; var rssPeak: Long? = null; var diskPeak = 0L
            val start = timedStart; val deadline = AtomicReference<Map<String, Long>>()
            val sampler = Thread {
                try { while (!done.get()) {
                    val now = System.nanoTime(); if (start.get() > 0) { val elapsed = (now - start.get()) / 1_000_000; val cap = elapsed
                        synchronized(lag) { if ((seconds == 0L || cap < seconds * 1000) && (lag.isEmpty() || lag.last()["elapsedMs"] != cap)) lag.add(mapOf("elapsedMs" to cap, "lag" to maxOf(0L, admitted.get() - settled.get()))) }
                        // Deadline counts updated at individual event times, never credited from late sample.
                    }
                    heapPeak = maxOf(heapPeak, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used); rss()?.let { rssPeak = maxOf(rssPeak ?: 0, it) }
                    val snapshot = requireNotNull(physical()) { "physical broker budget monitoring unavailable" }; val disk = localBytes() + snapshot.first + snapshot.second; diskPeak = maxOf(diskPeak, disk); require(disk <= diskCap) { if (diagnostic && diskCap > DISK_CAP) "16GiB empirical budget exceeded" else "10GiB budget exceeded" }; Thread.sleep(1000)
                } } catch (error: Throwable) { if (!done.get()) fatal.compareAndSet(null, error) }
            }
            var producerMs = 0L; var observerMs = 0L; var drainMs = 0L; var cpuMs = 0L; var bytesPhysical = 0L; var pendingPhysical = 0L; var identityHeap = 0L
            var physicalEnd: Pair<Long, Long>? = null; var txnMs: Double? = null
            val workload = MessageDigest.getInstance("SHA-256")
            try {
                guard.refresh("startup"); observer.start(); streams.start()
                val startupEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (running.count > 0 && System.nanoTime() < startupEnd) { guard.checkpoint("startup-wait"); running.await(25, TimeUnit.MILLISECONDS) }
                require(running.count == 0L); guard.baseline("warmed-before-aging"); sampler.start()
                if (bootstrap) {
                    clockReceipt = FinancialPhysicalInventory.captureHostClock(Path.of(config["nodeExecutable"].asText()), Path.of(config["physicalAdapterScript"].asText()))
                    val provenance = node(mapOf("sourceCommit" to spec["sourceHead"], "candidateSha256" to spec["candidateSha256"],
                        "configSha256" to spec["capability"]["configSha256"], "fixtureSha256" to spec["fixtureSha256"],
                        "buildSha256" to spec["capability"]["buildSha256"], "classpathSha256" to spec["capability"]["classpathSha256"],
                        "imageDigest" to config["registeredResources"]["containers"][0]["imageId"]))
                    physicalManifest = FinancialPhysicalInventory.freezeManifest(admin, prefix, provenance, config["registeredResources"],
                        Path.of(config["ownedRoot"].asText()), config["localResources"], listOf(
                            FinancialPhysicalInventory.OwnedTopic("$prefix-input", "source"), FinancialPhysicalInventory.OwnedTopic("$prefix-results", "output"),
                            FinancialPhysicalInventory.OwnedTopic("$prefix-streams-financial-state-changelog", "changelog")))
                    val resources = physicalManifest!!["topics"].map { ConfigResource(ConfigResource.Type.TOPIC, it["name"].asText()) }
                    val configs = heapAwait(guard, "effective-topic-configs", 5000) { admin.describeConfigs(resources).all().get(3, TimeUnit.SECONDS) }
                    effectiveTopics = node(resources.map { resource ->
                        val values = configs.getValue(resource).entries().associate { it.name() to it.value() }
                        require(values["max.message.bytes"] == "262144" && values["write.caching"] == "false") { "BOOTSTRAP_EFFECTIVE_TOPIC_CONFIG_MISMATCH" }
                        mapOf("topic" to resource.name(), "maxMessageBytes" to values["max.message.bytes"], "writeCaching" to values["write.caching"],
                            "requestedMinimumISR" to 2, "observedMinimumISR" to values["min.insync.replicas"], "minimumISRScope" to "null means unsupported readback; requested config is not empirical majority-outage proof", "rawConfigs" to values)
                    })
                    physicalCheckpoint("warmed")
                }
                val producer = KafkaProducer(Properties().apply {
                    putAll(props); put("acks", "all"); put("enable.idempotence", true); put("linger.ms", 2); put("batch.size", 65536)
                    put("buffer.memory", if (bootstrap || diagnostic) 4194304L else 16L * 1024 * 1024)
                    if (bootstrap || diagnostic) { put("max.request.size", 262144); put("compression.type", "none"); put("max.in.flight.requests.per.connection", 1)
                        put("max.block.ms", 10000L); put("request.timeout.ms", 10000); put("delivery.timeout.ms", 30000) }
                }, StringSerializer(), StringSerializer())
                try {
                    guard.baseline("producer-warmed-before-aging")
                    fun send(id: Long, phase: String, kind: String) {
                        guard.checkpoint("send"); checkWorkers(); val acquireEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        var acquired = capacity.tryAcquire()
                        while (!acquired && System.nanoTime() < acquireEnd) { guard.checkpoint("send-capacity"); checkWorkers(); acquired = capacity.tryAcquire(25, TimeUnit.MILLISECONDS) }
                        require(acquired) { "bounded source suffix exhausted" }
                        val index = ordinal.getAndIncrement(); val input = action(id, phase, kind); val bytes = json.writeValueAsBytes(node(mapOf("domain" to "hot", "mode" to "EXECUTE", "inputOrdinal" to index, "input" to input)))
                        require(bytes.size <= 1024) { "source record exceeds pinned1KiB bound" }; retain(bytes); inputBytes.addAndGet(bytes.size.toLong()); if (phase == "timed") workload.update(json.writeValueAsBytes(input)); val offeredNano = System.nanoTime()
                        producer.send(ProducerRecord("$prefix-input", 0, "hot", String(bytes, Charsets.UTF_8))) { metadata, error -> if (error != null) fatal.compareAndSet(null, error) else { try {
                            val callbackNano = System.nanoTime()
                            require(metadata.partition() == journal.scope.partition && metadata.topic() == journal.scope.topic) { "ACK callback topic/partition mismatch" }
                            synchronized(stageLock) { if (phase == "timed" && kind == "SETTLE") { callbackSuccess.incrementAndGet(); if (beforeDeadline(callbackNano)) deadlineCallbackSuccess.incrementAndGet() } }
                            journal.callback(index, metadata.offset(), bytes, phase, kind, id, offeredNano, callbackNano)
                        } catch (failure: Throwable) { fatal.compareAndSet(null, failure) }
                        } }
                    }
                    for (id in 0 until aged) { send(id, "aged", "CAPTURE"); send(id, "aged", "SETTLE") }; if (mode == "run") for (id in 0 until pending) send(id, "pending", "CAPTURE")
                    guard.checkpoint("flush-before"); heapAwait(guard, "producer-flush", 120000) { producer.flush() }; guard.refresh("flush-after"); waitCovered(); System.gc(); Thread.sleep(100)
                    guard.refresh("before-timed-load"); val heapBefore = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used; val before = physical(); val localBefore = localBytes(); val cpuBefore = os.processCpuTime
                    physicalCheckpoint("before-settled")
                    start.set(System.nanoTime()); synchronized(lag) { lag.add(mapOf("elapsedMs" to 0L, "lag" to 0L)) }
                    for (id in 0 until count) { if (rate > 0) { val due = start.get() + id * 1_000_000_000L / rate; while (System.nanoTime() < due) { guard.checkpoint("pacing"); checkWorkers(); LockSupport.parkNanos(100_000) } }; synchronized(stageLock) { offered.incrementAndGet(); if (beforeDeadline()) deadlineOffered.incrementAndGet() }; send(id, "timed", "CAPTURE"); send(id, "timed", "SETTLE") }
                    if (seconds > 0) while (System.nanoTime() < start.get() + seconds * 1_000_000_000) { guard.checkpoint("deadline-wait"); LockSupport.parkNanos(100_000) }
                    guard.checkpoint("timed-flush"); heapAwait(guard, "producer-flush", 120000) { producer.flush() }; guard.refresh("timed-flush-complete"); val producerEnd = System.nanoTime(); producerMs = maxOf(1L, (producerEnd - start.get()) / 1_000_000); waitCovered(); val observerEnd = System.nanoTime()
                    observerMs = maxOf(1L, (observerEnd - start.get()) / 1_000_000); drainMs = maxOf(0L, (observerEnd - producerEnd) / 1_000_000); cpuMs = (os.processCpuTime - cpuBefore) / 1_000_000
                    synchronized(stageLock) { deadline.set(mapOf("offered" to deadlineOffered.get(), "callbackSuccess" to deadlineCallbackSuccess.get(), "admitted" to deadlineAdmitted.get(), "decided" to deadlineCaptured.get(), "settled" to deadlineSettled.get(), "pending" to deadlineCaptured.get() - deadlineSettled.get())) }; synchronized(lag) { if (seconds > 0) lag.add(mapOf("elapsedMs" to seconds * 1000, "lag" to maxOf(0L, deadlineAdmitted.get() - deadlineSettled.get()))) }; physicalEnd = physical(); bytesPhysical = if (before != null && physicalEnd != null) maxOf(0L, physicalEnd!!.first + physicalEnd!!.second - before.first - before.second + localBytes() - localBefore) else 0
                    System.gc(); Thread.sleep(100); identityHeap = maxOf(0L, (ManagementFactory.getMemoryMXBean().heapMemoryUsage.used - heapBefore) / count)
                    physicalCheckpoint("after-settled")
                    if (calibrationMode && pending > 0) { val pBefore = physical(); val lBefore = localBytes(); for (id in 0 until pending) send(id, "pending", "CAPTURE"); heapAwait(guard, "producer-flush", 120000) { producer.flush() }; waitCovered(); val pAfter = physical(); pendingPhysical = if (pBefore != null && pAfter != null) maxOf(0L, (pAfter.first + pAfter.second - pBefore.first - pBefore.second + localBytes() - lBefore) / pending) else 0 }
                    physicalCheckpoint("after-pending")
                    txnMs = streams.metrics().filterKeys { it.name() == "txn-commit-time-ns-total" }.values.mapNotNull { (it.metricValue() as? Number)?.toDouble() }.takeIf { it.isNotEmpty() }?.sum()?.div(1_000_000)
                } finally { producer.close(if (guard.failure != null) Duration.ZERO else Duration.ofSeconds(15)) }
            } finally {
                done.set(true); observer.join(5000); sampler.interrupt(); sampler.join(15000); raw?.close()
                val closed = streams.close(Duration.ofSeconds(15)); journal.close()
                if (bootstrap) require(closed && !observer.isAlive && !sampler.isAlive) { "BOOTSTRAP_WORKERS_CLOSE_TIMEOUT" }
            }
            guard.refresh("workers-closed"); checkWorkers()
            physicalCheckpoint("workers-closed")
            guard.refresh("before-owner-digest"); val expectedOwner = reference.ownerDigest(); val expectedChain = reference.chain; val expectedHistory = reference.historyRecords
            guard.refresh("after-owner-digest"); reference.clear(); System.gc(); guard.refresh("before-result-only-replay")
            var managedRecovery: JsonNode? = null
            var managedRecoveryProcess: JsonNode? = null
            if (bootstrap) {
                require(ordinal.get() == 2100L && expectedHistory == 2101L && counts()["settled"] == 1000L) { "BOOTSTRAP_COMPLETE_INPUT_CUT" }
                val recoveryDir = outPath.parent.resolve("managed-recovery"); Files.createDirectory(recoveryDir)
                val recoveryRequest = recoveryDir.resolve("request.json"); val recoveryResult = recoveryDir.resolve("result.json")
                Files.writeString(recoveryRequest, json.writeValueAsString(mapOf("bootstrapSha256" to spec["bootstrapSha256"],
                    "sourceUuid" to uuid, "ownerSha256" to expectedOwner, "historyChecksum" to expectedChain, "historyRecords" to expectedHistory)), java.nio.file.StandardOpenOption.CREATE_NEW)
                val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xms128m", "-Xmx768m", "-cp", System.getProperty("java.class.path"),
                    FinancialRateProbe::class.java.name, "bootstrap-recover", broker, prefix, configPath.toString(), specPath.toString(), recoveryRequest.toString(), recoveryResult.toString())
                Files.writeString(recoveryDir.resolve("command.json"), json.writeValueAsString(command), java.nio.file.StandardOpenOption.CREATE_NEW)
                val stdout = recoveryDir.resolve("stdout.log"); val stderr = recoveryDir.resolve("stderr.log")
                val builder = ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
                listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH").forEach { builder.environment().remove(it) }
                val child = builder.start(); val childStart = System.nanoTime()
                try {
                    while (!child.waitFor(25, TimeUnit.MILLISECONDS)) {
                        guard.checkpoint("managed-restart-wait")
                        require(System.nanoTime() - childStart < TimeUnit.SECONDS.toNanos(120)) { "BOOTSTRAP_MANAGED_PROCESS_TIMEOUT" }
                        require(Files.size(stdout) + Files.size(stderr) <= 64L * 1024 * 1024) { "BOOTSTRAP_MANAGED_LOG_CAP" }
                    }
                    require(child.exitValue() == 0 && Files.isRegularFile(recoveryResult) && Files.size(recoveryResult) <= 1024 * 1024) { "BOOTSTRAP_MANAGED_PROCESS_FAILED" }
                    managedRecovery = json.readTree(Files.readString(recoveryResult))
                    require(managedRecovery["schema"].asText() == "financial-bootstrap-process-recovery-v1" && managedRecovery["processId"].asLong() == child.pid()
                        && child.pid() != ProcessHandle.current().pid() && managedRecovery["bootstrapSha256"] == spec["bootstrapSha256"]
                        && managedRecovery["recoveredMembers"].asLong() == 2100L && managedRecovery["controllerAdmissionsReplayed"].asLong() == 0L
                        && managedRecovery["activation"]["ownerSha256"].asText() == expectedOwner
                        && managedRecovery["activation"]["historyChecksum"].asText() == expectedChain
                        && managedRecovery["activation"]["historyRecords"].asLong() == expectedHistory) { "BOOTSTRAP_MANAGED_PROCESS_PARITY" }
                    managedRecoveryProcess = node(mapOf("processId" to child.pid(), "parentProcessId" to ProcessHandle.current().pid(), "exitCode" to child.exitValue(),
                        "newJvm" to true, "commandEvidencePath" to "managed-recovery/command.json", "commandEvidenceSha256" to shaFile(recoveryDir.resolve("command.json"))))
                } finally { if (child.isAlive) { child.destroyForcibly(); require(child.waitFor(5, TimeUnit.SECONDS)) { "BOOTSTRAP_MANAGED_PROCESS_REAP" } } }
                physicalCheckpoint("after-managed-restart")
            }
            val restored = Reference(balances, config["policy"]); val restoreStart = System.nanoTime(); var restoreBytes = 0L; var restoreInputs = 0L
            fun expectedInput(index: Long): JsonNode {
                if (calibrationMode) return if (index < count * 2) action(index / 2, "timed", if (index % 2 == 0L) "CAPTURE" else "SETTLE") else action(index - count * 2, "pending", "CAPTURE")
                return when {
                    index < aged * 2 -> action(index / 2, "aged", if (index % 2 == 0L) "CAPTURE" else "SETTLE")
                    index < aged * 2 + pending -> action(index - aged * 2, "pending", "CAPTURE")
                    else -> { val timed = index - aged * 2 - pending; action(timed / 2, "timed", if (timed % 2 == 0L) "CAPTURE" else "SETTLE") }
                }
            }
            var resultCut = 0L
            KafkaConsumer(consumerProperties(props, bootstrap || diagnostic), StringDeserializer(), StringDeserializer()).use { consumer ->
                val tp = TopicPartition("$prefix-results", 0); consumer.assign(listOf(tp)); consumer.seekToBeginning(listOf(tp))
                resultCut = consumer.endOffsets(listOf(tp), Duration.ofSeconds(10)).getValue(tp)
                val end = restoreStart + TimeUnit.MINUTES.toNanos(5)
                while (consumer.position(tp) < resultCut && System.nanoTime() < end) { guard.checkpoint("result-only-replay-poll"); heapReplayBatch(guard, consumer.poll(Duration.ofMillis(100))) { record ->
                    val bytes = record.value().toByteArray(); val value = if (bootstrap || diagnostic) parseBootstrapResult(bytes) else json.readTree(bytes)
                    require(record.offset() < resultCut && restoreInputs < ordinal.get() && value["inputOrdinal"].asLong() == restoreInputs)
                    val rows = value["records"]; require(rows.size() == if (restoreInputs == 0L) 2 else 1)
                    require(rows.last()["kind"].asText() == "BUSINESS" && canonical(rows.last()["input"]) == canonical(expectedInput(restoreInputs)))
                    rows.forEach { restored.accept(it) }; restoreBytes += record.value().toByteArray().size; restoreInputs++
                } }
                require(consumer.position(tp) == resultCut) { "COMPLETE_COMMITTED_RESULT_CUT_TIMEOUT" }
            }
            guard.refresh("result-only-replay-complete"); require(restoreInputs == ordinal.get() && expectedHistory == restored.historyRecords && expectedChain == restored.chain && expectedOwner == restored.ownerDigest()) { "complete result-only restore mismatch" }
            val restoreMs = (System.nanoTime() - restoreStart) / 1_000_000
            physicalCheckpoint("after-result-replay")
            val parityPath = outPath.parent.resolve("parity.json")
            val parityContent = json.writeValueAsString(mapOf("ownerSha256" to expectedOwner, "historyChecksum" to expectedChain, "historyRecords" to expectedHistory, "sourceUuid" to uuid, "readCommitted" to true, "independentCompleteDeltas" to true)) + "\n"
            val heapArtifact = heapArtifactSnapshot(guard, heapPeak)
            val result = if (bootstrap) mapOf(
                "schema" to "financial-bootstrap-calibration-v1", "purpose" to "EMPIRICAL_BOUNDED_DIAGNOSTIC", "capacityQualification" to false,
                "heapConservativeBound" to false, "estimatedHeapBytes" to null, "bootstrapSha256" to spec["bootstrapSha256"],
                "processId" to ProcessHandle.current().pid(), "agedIdentities" to 0,
                "sourceHead" to spec["sourceHead"], "fixtureSha256" to spec["fixtureSha256"], "candidateSha256" to spec["candidateSha256"], "capability" to spec["capability"],
                "sampleTrades" to count, "pendingSample" to pending, "sourceActions" to ordinal.get(), "historyRecords" to expectedHistory,
                "final" to counts(), "sourceTopicUuid" to uuid, "ackJournal" to journal.telemetry(), "heapObservation" to heapArtifact.observation,
                "realRecordEvidencePath" to "encoded-records.bin", "realRecordEvidenceSha256" to shaFile(rawPath), "encodedBytes" to rawBytes,
                "encodedInputBytes" to inputBytes.get(), "encodedResultBytes" to outputBytes.get(),
                "managedRestartVerified" to true, "managedRecovery" to managedRecovery, "resultOnlyReplayVerified" to true,
                "managedRecoveryEvidencePath" to "managed-recovery/result.json", "managedRecoveryEvidenceSha256" to shaFile(outPath.parent.resolve("managed-recovery/result.json")),
                "managedRecoveryProcess" to managedRecoveryProcess,
                "resultOnlyReplay" to mapOf("records" to expectedHistory, "inputs" to restoreInputs, "bytes" to restoreBytes, "elapsedMs" to restoreMs, "committedResultOffsetExclusive" to resultCut,
                    "ownerSha256" to expectedOwner, "historyChecksum" to expectedChain, "sourceUuid" to uuid, "readCommitted" to true, "completeCommittedCut" to true),
                "parity" to mapOf("ownerSha256" to expectedOwner, "historyChecksum" to expectedChain, "historyRecords" to expectedHistory, "sourceUuid" to uuid,
                    "readCommitted" to true, "independentCompleteDeltas" to true),
                "physicalCheckpoints" to physicalCheckpoints,
                "effectiveClients" to effectiveClients,
                "effectiveTopics" to effectiveTopics,
                "producerElapsedMs" to producerMs, "observerElapsedMs" to observerMs, "drainMs" to drainMs,
                "gaps" to listOf("Finite empirical sample; no retained-heap upper bound or rate qualification", "Physical allocated category attribution unknown unless raw mapping proves it", "Broker/native/child aggregate CPU unmeasured"))
            else if (mode == "calibrate") mapOf(
                "schema" to "financial-rate-calibration-v1", "sourceHead" to spec["sourceHead"].asText(), "fixtureSha256" to spec["fixtureSha256"].asText(), "realRecordEvidencePath" to "encoded-records.bin", "realRecordEvidenceSha256" to shaFile(rawPath), "sampleTrades" to count, "encodedBytes" to rawBytes,
                "physicalBytes" to bytesPhysical, "physicalReplicationIncluded" to true, "identityPhysicalBytes" to if (bytesPhysical > 0) maxOf(1L, bytesPhysical / count) else 0, "pendingPhysicalBytes" to pendingPhysical, "identityHeapBytes" to identityHeap, "heapObservation" to heapArtifact.observation,
                "producer" to mapOf("completedTrades" to admitted.get(), "callbackSuccessTrades" to callbackSuccess.get(), "elapsedMs" to producerMs), "observer" to mapOf("completedTrades" to settled.get(), "elapsedMs" to observerMs, "exactParity" to true), "sourceTopicUuid" to uuid, "ackJournal" to journal.telemetry())
            else mapOf("schema" to "financial-rate-measurement-v1", "policySha256" to spec["policySha256"].asText(), "sourceHead" to spec["sourceHead"].asText(), "fixtureSha256" to spec["fixtureSha256"].asText(), "workloadSha256" to HexFormat.of().formatHex(workload.digest()), "units" to "unique timed executions; preflight/aged rows excluded", "producerElapsedMs" to producerMs, "drainMs" to drainMs, "deadline" to deadline.get(), "final" to counts(),
                "parity" to mapOf("completeJournal" to true, "completeOwnerState" to true, "independentOracle" to true, "evidenceSha256" to sha(parityContent.toByteArray())), "lagSamples" to lag,
                "resources" to mapOf("processCpuMs" to cpuMs, "heapPeakBytes" to heapArtifact.resourcePeakBytes, "heapPeakScope" to heapArtifact.peakScope, "rssPeakBytes" to rssPeak, "diskPeakBytes" to diskPeak, "encodedInputBytes" to inputBytes.get(), "encodedResultBytes" to outputBytes.get(), "encodedTechnicalBytes" to null, "physicalBrokerBytes" to physicalEnd?.first, "physicalChangelogBytes" to physicalEnd?.second, "touchedKeys" to keys.get(), "technicalRecords" to covered.get(), "transactionWaitMs" to txnMs),
                "heapObservation" to heapArtifact.observation, "stageLatency" to synchronized(latency) { val sorted = latency.sorted(); mapOf("kind" to "sampled-individual", "stage" to "offer-to-read-committed-settlement", "clockDomain" to "same-monotonic", "samples" to sorted.size, "p95Ms" to sorted.getOrNull((sorted.size * .95).toInt()), "p99Ms" to sorted.getOrNull((sorted.size * .99).toInt())) },
                "restore" to mapOf("kind" to "result-only-replay", "records" to expectedHistory, "bytes" to restoreBytes, "elapsedMs" to restoreMs, "verifiedCut" to true), "ackJournal" to journal.telemetry(), "gaps" to listOf("Actual managed restart remains separate from result-only replay and broker-free journal process-crash controls", "Encoded technical store bytes uninstrumented", "CPU scope same-JVM worker+producer+observer; broker CPU unmeasured", "Touched keys = emitted state/history/coverage/cert writes; framework reads excluded"), "capacityQualification" to false) + if (diagnostic) mapOf("purpose" to "EMPIRICAL_TIMED_DIAGNOSTIC", "heapConservativeBound" to false,
                "estimatedHeapBytes" to null, "explicitHeapBudgetBytes" to guard.actualMaxHeapBytes,
                "journalBudgetBytes" to journalCap, "managedRestartVerified" to false, "diskBudgetBytes" to diskCap, "resourceProfile" to spec["resourceProfile"]?.asText()) else emptyMap()
            journal.checkHealthy(); guard.publish(outPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", parityPath to parityContent); println(json.writeValueAsString(mapOf("artifact" to outPath.toString(), "offered" to offered.get(), "admitted" to admitted.get(), "settled" to settled.get())))
            }
        }
        } finally { raw?.close() }
        }
    }
}
