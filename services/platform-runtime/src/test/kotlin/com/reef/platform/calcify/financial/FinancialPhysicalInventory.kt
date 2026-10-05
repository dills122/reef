package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.DescribeLogDirsOptions
import org.apache.kafka.clients.admin.DescribeTopicsOptions
import org.apache.kafka.clients.admin.LogDirDescription
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.Uuid
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Test-only actual Admin replies, finite owned identities, and guarded physical collector. */
object FinancialPhysicalInventory {
    data class OwnedTopic(val name: String, val category: String)
    class Failure(val evidence: JsonNode, cause: Throwable) : IllegalStateException("PHYSICAL_ADMIN_REFUSED", cause)
    private val json = ObjectMapper()
    private const val MAX_SAFE = 9007199254740991L
    private const val MAX_RAW = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 8192
    private fun now() = System.nanoTime() / 1_000_000L
    private fun node(value: Any): ObjectNode = json.valueToTree(value)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safe(value: Long) { require(value in 0..MAX_SAFE) { "PHYSICAL_UNSAFE_INTEGER" } }
    private fun brokers(manifest: JsonNode) = manifest["registeredResources"]["containers"].map {
        require(it["brokerId"].isIntegralNumber && it["brokerId"].canConvertToInt()) { "PHYSICAL_BROKER_ID_INVALID" }
        it["brokerId"].intValue()
    }.also {
        require(it.size == 3 && it.toSet().size == 3 && it.all { id -> id >= 0 }) { "PHYSICAL_EXACT_THREE_BROKERS_REQUIRED" }
    }
    private fun topics(names: List<String>, replies: Map<String, TopicDescription>): List<Map<String, Any?>> {
        require(replies.keys == names.toSet()) { "PHYSICAL_TOPIC_RESPONSE_SET_DRIFT" }
        return names.map { name ->
            val t = replies.getValue(name)
            require(t.name() == name && t.topicId() != Uuid.ZERO_UUID) { "PHYSICAL_TOPIC_IDENTITY_INVALID" }
            mapOf("name" to t.name(), "topicId" to t.topicId().toString(), "partitions" to t.partitions().sortedBy { it.partition() }.map {
                mapOf("partition" to it.partition(), "replicas" to it.replicas().map { broker -> broker.id() })
            })
        }
    }
    fun freezeManifest(admin: Admin, runId: String, provenance: JsonNode, registration: JsonNode,
        ownedRoot: Path, localResources: JsonNode, ownedTopics: List<OwnedTopic>): ObjectNode {
        require(ownedTopics.size == 3 && ownedTopics.map { it.name }.toSet().size == 3 &&
            ownedTopics.map { it.category }.toSet() == setOf("source", "output", "changelog")) { "PHYSICAL_EXACT_FINANCIAL_TOPICS_REQUIRED" }
        require(ownedTopics.all { it.name.isNotBlank() && '\u0000' !in it.name })
        val names = ownedTopics.map { it.name }
        val reply = admin.describeTopics(names, DescribeTopicsOptions().timeoutMs(3000)).allTopicNames().get(3, TimeUnit.SECONDS)
        val rows = topics(names, reply).mapIndexed { index, row -> row + ("category" to ownedTopics[index].category) }
        val m = node(mapOf("schema" to "calcify-physical-manifest-v1", "runId" to runId, "provenance" to provenance,
            "registeredResources" to registration, "ownedRoot" to ownedRoot.toRealPath().toString(), "localResources" to localResources,
            "topics" to rows, "categoryMappings" to emptyList<Any>()))
        validateTopics(m, m["topics"])
        return m
    }
    private fun validateTopics(m: JsonNode, rows: JsonNode) {
        val ids = brokers(m).toSet()
        require(rows.size() == 3 && rows.map { it["name"].asText() }.toSet().size == 3 && rows.map { it["topicId"].asText() }.toSet().size == 3)
        rows.forEach { t ->
            require(t["partitions"].size() in 1..MAX_ENTRIES)
            t["partitions"].forEachIndexed { index, p ->
                require(p["partition"].intValue() == index && p["replicas"].size() == 3 && p["replicas"].map { it.intValue() }.toSet() == ids) { "PHYSICAL_EXACT_RF3_ASSIGNMENT_REQUIRED" }
            }
        }
    }
    /** Raw rows come directly from API value objects; normalized rows are derived from those rows. */
    fun inventoryFromReplies(manifest: JsonNode, topicReplies: Map<String, TopicDescription>,
        logDirReplies: Map<Int, Map<String, LogDirDescription>>, startedAtMs: Long, completedAtMs: Long): ObjectNode {
        safe(startedAtMs); safe(completedAtMs)
        require(completedAtMs >= startedAtMs && completedAtMs - startedAtMs <= 5000) { "PHYSICAL_API_WINDOW" }
        val names = manifest["topics"].map { it["name"].asText() }; val ids = brokers(manifest)
        require(logDirReplies.keys == ids.toSet()) { "PHYSICAL_BROKER_RESPONSE_SET_DRIFT" }
        var count = topicReplies.values.fold(0) { sum, topic -> sum + topic.partitions().size * 4 }
        require(count <= MAX_ENTRIES) { "PHYSICAL_METADATA_BOUND" }
        val topicRows = topics(names, topicReplies)
        val rawTopics = node(mapOf("schema" to "calcify-admin-topics-raw-v1", "request" to mapOf("topics" to names),
            "response" to mapOf("topics" to topicRows.map { it + ("error" to null) })))
        val rawDirs = node(mapOf("schema" to "calcify-admin-log-dirs-raw-v1", "request" to mapOf("brokerIds" to ids),
            "response" to mapOf("brokers" to ids.map { id -> mapOf("brokerId" to id, "logDirs" to logDirReplies.getValue(id).toSortedMap().map { (path, d) ->
                require(++count <= MAX_ENTRIES) { "PHYSICAL_METADATA_BOUND" }
                mapOf("logDir" to path, "error" to d.error()?.let { mapOf("type" to it.javaClass.name, "message" to it.message) },
                    "replicas" to d.replicaInfos().entries.sortedWith(compareBy({ it.key.topic() }, { it.key.partition() })).map { (tp, r) ->
                        require(++count <= MAX_ENTRIES) { "PHYSICAL_METADATA_BOUND" }
                        mapOf("topic" to tp.topic(), "partition" to tp.partition(), "sizeBytes" to r.size(), "offsetLag" to r.offsetLag(), "isFuture" to r.isFuture())
                    })
            }) })))
        val inventory = node(mapOf("schema" to "calcify-replica-inventory-v1", "runId" to manifest["runId"], "provenance" to manifest["provenance"],
            "window" to mapOf("startedAtMs" to startedAtMs, "completedAtMs" to completedAtMs, "clockScope" to "host-monotonic-ms", "kind" to "actual-host-covering-bracket"),
            "topics" to topicRows, "replicas" to emptyList<Any>(), "rawReceipts" to listOf("describeTopics" to rawTopics, "describeLogDirs" to rawDirs).map { (operation, raw) ->
                val bytes = json.writeValueAsBytes(raw); require(bytes.size <= MAX_RAW) { "PHYSICAL_RAW_BOUND" }
                mapOf("operation" to operation, "startedAtMs" to startedAtMs, "completedAtMs" to completedAtMs, "rawJSON" to bytes.toString(Charsets.UTF_8), "bytesSha256" to sha(bytes))
            }))
        try {
            validateTopics(manifest, inventory["topics"])
            val expectedTopics = manifest["topics"].map { t -> node(mapOf("name" to t["name"], "topicId" to t["topicId"], "partitions" to t["partitions"])) }
            require(inventory["topics"] == json.valueToTree<JsonNode>(expectedTopics)) { "PHYSICAL_TOPIC_ASSIGNMENT_DRIFT" }
            val replicas = json.createArrayNode(); val seen = mutableSetOf<String>()
            rawDirs["response"]["brokers"].forEach { b ->
                require(b["logDirs"].size() > 0) { "PHYSICAL_MISSING_LOG_DIRS" }
                b["logDirs"].forEach { d ->
                    val path = d["logDir"].asText(); val normalized = Path.of(path).normalize().toString()
                    require(d["error"].isNull && path == normalized && (path == "/var/lib/redpanda/data" || path.startsWith("/var/lib/redpanda/data/"))) { "PHYSICAL_LOG_DIR_API_OR_SCOPE_ERROR" }
                    d["replicas"].forEach { r ->
                        if (r["topic"].asText() in names) {
                            safe(r["sizeBytes"].longValue()); require(!r["isFuture"].booleanValue()) { "PHYSICAL_FUTURE_REPLICA" }
                            val topic = manifest["topics"].first { it["name"] == r["topic"] }
                            require(topic["partitions"].any { it["partition"] == r["partition"] }) { "PHYSICAL_PARTITION_DRIFT" }
                            require(seen.add("${b["brokerId"]}/${r["topic"]}/${r["partition"]}")) { "PHYSICAL_DUPLICATE_REPLICA" }
                            replicas.add(node(mapOf("brokerId" to b["brokerId"], "topic" to r["topic"], "topicId" to topic["topicId"], "partition" to r["partition"],
                                "logDir" to path, "sizeBytes" to r["sizeBytes"], "unit" to "bytes", "error" to null)))
                        }
                    }
                }
            }
            require(replicas.size() == manifest["topics"].sumOf { it["partitions"].size() * 3 }) { "PHYSICAL_MISSING_REPLICA" }
            inventory.set<JsonNode>("replicas", replicas)
            require(json.writeValueAsBytes(inventory).size <= MAX_RAW) { "PHYSICAL_SERIALIZED_RAW_BOUND" }
            return inventory
        } catch (e: Throwable) { throw Failure(inventory, e) }
    }
    fun snapshot(admin: Admin, manifest: JsonNode, clockReceipt: JsonNode): ObjectNode {
        require(clockReceipt["clockScope"]?.asText() == "host-monotonic-ms" && clockReceipt["verified"]?.asBoolean() == true) { "PHYSICAL_CLOCK_NOT_VERIFIED" }
        val nodeExecutable = Path.of(clockReceipt["nodeExecutable"].asText())
        val adapterScript = Path.of(clockReceipt["adapterScript"].asText())
        val before = captureHostClock(nodeExecutable, adapterScript)
        val start = now(); val deadline = System.nanoTime() + 3_000_000_000L
        val names = manifest["topics"].map { it["name"].asText() }; val ids = brokers(manifest)
        try {
            val topicStart = now()
            val topics = admin.describeTopics(names, DescribeTopicsOptions().timeoutMs(3000)).allTopicNames()
            val dirStart = now()
            val dirs = admin.describeLogDirs(ids, DescribeLogDirsOptions().timeoutMs(3000)).allDescriptions()
            fun remaining() = (deadline - System.nanoTime()).also { require(it > 0) { "PHYSICAL_ADMIN_TIMEOUT" } }
            val t = topics.get(remaining(), TimeUnit.NANOSECONDS); val topicEnd = now()
            val d = dirs.get(remaining(), TimeUnit.NANOSECONDS); val dirEnd = now()
            val after = captureHostClock(nodeExecutable, adapterScript)
            val inventory = inventoryFromReplies(manifest, t, d, before["sample"]["sampledAtMs"].longValue(), after["sample"]["sampledAtMs"].longValue())
            inventory.set<JsonNode>("hostClockReceipts", json.valueToTree(listOf(before, after)))
            inventory["rawReceipts"].forEachIndexed { index, raw ->
                (raw as ObjectNode).put("timestampMeaning", "conservative actual host covering bracket; not individual API latency")
                raw.set<JsonNode>("jvmMonotonicWindow", node(mapOf("clockScope" to "jvm-system-nano",
                    "startedAtMs" to if (index == 0) topicStart else dirStart, "completedAtMs" to if (index == 0) topicEnd else dirEnd)))
            }
            require(json.writeValueAsBytes(inventory).size <= MAX_RAW) { "PHYSICAL_SERIALIZED_RAW_BOUND" }
            return inventory
        } catch (e: Failure) { throw e }
        catch (e: Throwable) {
            throw Failure(node(mapOf("schema" to "calcify-admin-failure-v1", "startedAtMs" to start,
                "completedAtMs" to now(), "topicRequest" to names, "brokerRequest" to ids,
                "error" to mapOf("type" to e.javaClass.name, "message" to e.message))), e)
        }
    }
    private fun process(command: List<String>, deadlineMs: Long, checkpoint: () -> Unit): ObjectNode {
        val p = ProcessBuilder(command).start(); val failure = AtomicReference<Throwable>()
        fun drain(stream: java.io.InputStream): Pair<Thread, java.io.ByteArrayOutputStream> {
            val out = java.io.ByteArrayOutputStream()
            val reader = thread(isDaemon = true, name = "physical-adapter-output") {
                try { stream.use { input -> val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        synchronized(out) { require(out.size() + n <= 32768) { "PHYSICAL_PROCESS_OUTPUT_BOUND" }; out.write(buffer, 0, n) }
                    }
                } } catch (e: Throwable) { failure.compareAndSet(null, e); p.destroyForcibly() }
            }; return reader to out
        }
        val (stdoutThread, stdout) = drain(p.inputStream); val (stderrThread, stderr) = drain(p.errorStream)
        val started = now(); var error: Throwable? = null
        try {
            while (p.isAlive) { checkpoint(); failure.get()?.let { throw it }
                require(now() < deadlineMs) { "PHYSICAL_PROCESS_TIMEOUT" }; p.waitFor(25, TimeUnit.MILLISECONDS) }
            checkpoint(); failure.get()?.let { throw it }
        } catch (e: Throwable) { error = e; p.destroyForcibly() }
        finally { p.waitFor(250, TimeUnit.MILLISECONDS); stdoutThread.join(250); stderrThread.join(250) }
        error = error ?: failure.get()
        if (stdoutThread.isAlive || stderrThread.isAlive) error = error ?: IllegalStateException("PHYSICAL_PROCESS_DRAIN_TIMEOUT")
        val receipt = node(mapOf("argv" to command, "startedAtMs" to started, "completedAtMs" to now(),
            "exitCode" to if (p.isAlive) null else p.exitValue(), "stdout" to synchronized(stdout) { stdout.toString(Charsets.UTF_8) },
            "stderr" to synchronized(stderr) { stderr.toString(Charsets.UTF_8) }, "error" to error?.toString()))
        if (error != null || p.isAlive || p.exitValue() != 0) throw Failure(receipt, error ?: IllegalStateException("PHYSICAL_PROCESS_FAILED"))
        return receipt
    }
    fun captureHostClock(nodeExecutable: Path, adapterScript: Path): ObjectNode {
        require(nodeExecutable.isAbsolute && adapterScript.isAbsolute)
        val start = now(); val receipt = process(listOf(nodeExecutable.toRealPath().toString(), adapterScript.toRealPath().toString(), "--clock"), start + 1000) {}
        val end = now(); val sample = json.readTree(receipt["stdout"].asText()); val sampled = sample["sampledAtMs"]
        if (!(sample["clockScope"].asText() == "host-monotonic-ms" && sampled.isIntegralNumber && sampled.longValue() in 0..MAX_SAFE))
            throw Failure(node(mapOf("jvmStartedAtMs" to start, "jvmCompletedAtMs" to end, "sample" to sample, "process" to receipt)), IllegalStateException("PHYSICAL_CLOCK_SAMPLE_INVALID"))
        return node(mapOf("clockScope" to "host-monotonic-ms", "verified" to true, "timestampMeaning" to "actual Node host sample; JVM bracket uses separate origin",
            "jvmClockScope" to "jvm-system-nano", "sameOriginObserved" to (sampled.longValue() in start..end), "jvmStartedAtMs" to start, "jvmCompletedAtMs" to end,
            "nodeExecutable" to nodeExecutable.toRealPath().toString(), "adapterScript" to adapterScript.toRealPath().toString(), "sample" to sample, "process" to receipt))
    }
    fun collectCheckpoint(guard: FinancialHeapGuard, admin: Admin, manifest: JsonNode, nodeExecutable: Path,
        adapterScript: Path, ownedProof: Path, stage: String, clockReceipt: JsonNode): ObjectNode {
        require(stage.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "PHYSICAL_STAGE_INVALID" }
        require(clockReceipt["nodeExecutable"].asText() == nodeExecutable.toRealPath().toString() &&
            clockReceipt["adapterScript"].asText() == adapterScript.toRealPath().toString()) { "PHYSICAL_CLOCK_EXECUTABLE_DRIFT" }
        val root = Path.of(manifest["ownedRoot"].asText()).toRealPath(); val proof = ownedProof.toRealPath()
        require(proof.startsWith(root) && proof != root && proof.toString() == ownedProof.toString() &&
            manifest["localResources"].any { it["category"].asText() == "proof" && it["path"].asText() == proof.toString() }) { "PHYSICAL_OWNED_PROOF_REQUIRED" }
        guard.refresh("physical-$stage-start"); val started = now(); val deadline = started + 5000
        val unique = "$stage-${java.util.UUID.randomUUID()}"
        val manifestPath = proof.resolve("$unique-manifest.json"); val inventoryPath = proof.resolve("$unique-inventory.json"); val artifactPath = proof.resolve("$unique-physical.json")
        fun write(path: Path, value: JsonNode) { val bytes = json.writeValueAsBytes(value); require(bytes.size <= MAX_RAW)
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
        write(manifestPath, manifest)
        try {
            val inventory = snapshot(admin, manifest, clockReceipt); write(inventoryPath, inventory)
            val receipt = process(listOf(nodeExecutable.toRealPath().toString(), adapterScript.toRealPath().toString(), "--collect",
                manifestPath.toString(), inventoryPath.toString(), artifactPath.toString()), deadline) { guard.checkpoint("physical-$stage-collect") }
            require(now() - started <= 5000) { "PHYSICAL_COMBINED_WINDOW_STALE" }
            require(Files.size(artifactPath) <= 16L * 1024 * 1024) { "PHYSICAL_ARTIFACT_BOUND" }
            val artifact = Files.newInputStream(artifactPath).use { json.readTree(it) }
            require(artifact["schema"].asText() == "calcify-physical-evidence-v1" && artifact["runId"] == manifest["runId"]) { "PHYSICAL_ARTIFACT_IDENTITY_DRIFT" }
            guard.refresh("physical-$stage-complete")
            require(now() - started <= 5000) { "PHYSICAL_COMBINED_WINDOW_STALE" }
            return node(mapOf("stage" to stage, "manifestPath" to manifestPath.toString(), "inventoryPath" to inventoryPath.toString(),
                "artifactPath" to artifactPath.toString(), "process" to receipt, "clockReceipt" to clockReceipt, "artifact" to artifact))
        } catch (e: Throwable) {
            write(proof.resolve("$unique-admin-failed.json"), node(mapOf("stage" to stage, "error" to e.toString(), "evidence" to (e as? Failure)?.evidence)))
            throw e
        }
    }
}
