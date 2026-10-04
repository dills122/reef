package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
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
    private class Reference(private val balances: JsonNode, private val policy: JsonNode) {
        val owner = emptyOwner()
        var chain = "GENESIS"
        var historyRecords = 0L
        private fun get(path: List<String>): JsonNode = path.fold(owner as JsonNode?) { v, key -> v?.get(key) } ?: nullNode
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
                require(!owner["dedup"].has(decision)) { "duplicate timed economic action" }
                val disposition: String
                if (kind == "CAPTURE") {
                    require(!owner["executions"].has(execution)); require(payload["quantity"].asText() == "1" && payload["priceNanos"].asText() == PRICE.toString())
                    put("executions", execution, payload); put("obligations", execution, node(mapOf("status" to "PENDING", "cashResidual" to PRICE.toString(), "shareResidual" to "1")))
                    put("workflows", execution, node("PENDING")); put("instructions", execution, node("READY"))
                    put("dueQueue", execution, node(mapOf("tick" to "0", "priority" to 0, "workId" to payload["executionId"].asText())))
                    increment("versions", execution); disposition = "CAPTURED"
                } else {
                    require(owner["obligations"][execution]["status"].asText() == "PENDING")
                    for ((field, delta) in listOf("buyerCash" to -PRICE, "sellerCash" to PRICE, "buyerShares" to 1L, "sellerShares" to -1L)) {
                        val next = BigInteger(owner["balances"][field].asText()) + BigInteger.valueOf(delta)
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
            writes.forEach { (p, value) ->
                val parent = if (p.size == 1) owner else owner[p[0]] as ObjectNode
                if (value.isNull && p.size > 1) parent.remove(p.last()) else parent.set<JsonNode>(p.last(), value.deepCopy())
            }
            historyRecords++; chain = record["checksum"].asText()
        }
        fun businessView() = owner.deepCopy().apply {
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
        require(digest(kernel.ownerView()) == digest(reference.owner))
        val bad = kernel.lastRecord()!!.deepCopy<ObjectNode>(); (bad["journalGroups"] as ObjectNode).remove("ACME_SHARE")
        val beforeLast = Reference(balances, policy); histories.dropLast(1).forEach { beforeLast.accept(it) }
        require(runCatching { beforeLast.accept(bad) }.exceptionOrNull()?.message?.contains("journal mismatch") == true)
        println(json.writeValueAsString(mapOf("selfCheck" to true, "prefixes" to 40, "independentFullOracle" to true, "oneLegMutationRejected" to true)))
    }

    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "self-check") { selfCheck(json.readTree(Files.readString(Path.of(args[1])))["policy"]); return }
        require(args.size >= 4) { "calibrate|run bootstrap financial-s1-prefix config-file --financial-rate-* PATH" }
        val mode = args[0]; val broker = args[1]; val prefix = args[2]
        require(mode in setOf("calibrate", "run") && prefix.startsWith("financial-s1-"))
        val config = json.readTree(Files.readString(Path.of(args[3])))
        fun flag(name: String): Path = Path.of(args[args.indexOf(name).also { require(it >= 0) } + 1])
        val spec = json.readTree(Files.readString(flag(if (mode == "calibrate") "--financial-rate-calibration-request" else "--financial-rate-policy")))
        val outPath = flag(if (mode == "calibrate") "--financial-rate-calibration" else "--financial-rate-measurement")
        require(if (mode == "calibrate") spec["correctness"]["result"].asText() == "PASS" else spec["status"].asText() == "FROZEN") { "E3/frozen load gate" }
        val count = if (mode == "calibrate") spec.path("sampleTrades").asLong(1000) else spec["expectedTimedTrades"].asLong()
        require(count in 1L..3_000_000L)
        val rate = if (mode == "calibrate") 0L else spec["arm"]["rate"].asLong()
        val seconds = if (mode == "calibrate") 0L else spec["arm"]["seconds"].asLong()
        val aged = if (mode == "run" && spec["arm"]["state"].asText() == "aged") spec["aged"]["identities"].asLong() else 0L
        val pending = if (mode == "calibrate") spec.path("pendingSample").asLong(100) else if (spec["arm"]["state"].asText() == "aged") spec["aged"]["pendingItems"].asLong() else 0L
        val opening = Math.addExact(Math.addExact(aged, pending), count)
        val balances = node(mapOf("buyerCash" to Math.multiplyExact(opening, PRICE).toString(), "sellerCash" to "0", "buyerShares" to "0", "sellerShares" to opening.toString()))
        if (mode == "run") require(spec["openingResources"]["cashNanos"].asText() == balances["buyerCash"].asText() && spec["openingResources"]["shares"].asText() == balances["sellerShares"].asText()) { "frozen genesis resource mismatch" }
        val stateDir = Path.of(config.path("stateDir").asText("/tmp/$prefix-state")); Files.createDirectories(stateDir)
        val props = Properties().apply { put("bootstrap.servers", broker) }
        val members = ConcurrentHashMap<Long, JsonNode>(); val capacity = Semaphore(16384)
        val lastMember = AtomicReference<Pair<Long, JsonNode>>()
        val fatal = AtomicReference<Throwable>(); val done = AtomicReference(false)
        val ordinal = AtomicLong(); val covered = AtomicLong(); val offered = AtomicLong(); val admitted = AtomicLong(); val captured = AtomicLong(); val settled = AtomicLong()
        val deadlineOffered = AtomicLong(); val deadlineAdmitted = AtomicLong(); val deadlineCaptured = AtomicLong(); val deadlineSettled = AtomicLong()
        val timedStart = AtomicLong()
        fun beforeDeadline() = timedStart.get() > 0 && (seconds == 0L || System.nanoTime() - timedStart.get() <= seconds * 1_000_000_000L)
        val inputBytes = AtomicLong(); val outputBytes = AtomicLong(); val keys = AtomicLong()
        val latency = mutableListOf<Long>(); val lag = mutableListOf<Map<String, Long>>()
        val rawPath = outPath.parent.resolve("encoded-records.bin"); val raw = if (mode == "calibrate") Files.newOutputStream(rawPath) else null
        var rawBytes = 0L
        fun retain(bytes: ByteArray) { if (raw != null) synchronized(raw) { require(rawBytes + bytes.size <= RAW_CAP); raw.write(bytes); rawBytes += bytes.size } }
        val reference = Reference(balances, config["policy"])
        AdminClient.create(props).use { admin ->
            admin.createTopics(listOf("input", "results").map { NewTopic("$prefix-$it", 1, 3.toShort()).configs(mapOf("cleanup.policy" to "delete", "write.caching" to "false", "min.insync.replicas" to "2")) }).all().get(30, TimeUnit.SECONDS)
            val uuid = admin.describeTopics(listOf("$prefix-input")).allTopicNames().get().getValue("$prefix-input").topicId().toString()
            val workerConfig = node(mapOf("genesis" to mapOf("hot" to mapOf("balances" to balances)), "policy" to config["policy"], "acceptedManifest" to mapOf("inputTopicId" to uuid)))
            val topology = FinancialBrokerProbe.topology(workerConfig, "", prefix, { index ->
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var member = members[index] ?: lastMember.get()?.takeIf { it.first == index }?.second
                while (member == null && System.nanoTime() < end && fatal.get() == null) { LockSupport.parkNanos(100_000); member = members[index] }
                requireNotNull(member) { "ACK membership wait expired index=$index" }
            }, uuid)
            val streams = KafkaStreams(topology, Properties().apply {
                putAll(props); put(StreamsConfig.APPLICATION_ID_CONFIG, "$prefix-streams"); put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString())
                put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2); put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3)
                put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1); put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 2000)
                put("topic.min.insync.replicas", 2); put("topic.write.caching", false); put("consumer.max.poll.records", 128); put("consumer.max.poll.interval.ms", 30000)
                put("default.deserialization.exception.handler", "org.apache.kafka.streams.errors.LogAndFailExceptionHandler"); put("default.production.exception.handler", "org.apache.kafka.streams.errors.DefaultProductionExceptionHandler"); put("processing.exception.handler", "org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler")
            })
            val running = CountDownLatch(1)
            streams.setStateListener { next, _ -> if (next == KafkaStreams.State.RUNNING) running.countDown() }
            streams.setUncaughtExceptionHandler { error -> fatal.compareAndSet(null, error); StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT }
            fun counts() = mapOf("offered" to offered.get(), "admitted" to admitted.get(), "decided" to captured.get(), "settled" to settled.get(), "pending" to captured.get() - settled.get())
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
                try { KafkaConsumer(Properties().apply { putAll(props); put("enable.auto.commit", false); put("auto.offset.reset", "earliest"); put("isolation.level", "read_committed") }, StringDeserializer(), StringDeserializer()).use { consumer ->
                    val tp = TopicPartition("$prefix-results", 0); consumer.assign(listOf(tp)); consumer.seekToBeginning(listOf(tp))
                    while (!done.get()) for (record in consumer.poll(Duration.ofMillis(50))) {
                        val value = json.readTree(record.value()); val index = value["inputOrdinal"].asLong(); require(index == covered.get())
                        val member = members[index] ?: error("missing acknowledged member"); require(value["inputOffset"].asLong() == member["offset"].asLong())
                        val bytes = record.value().toByteArray(); retain(bytes); outputBytes.addAndGet(bytes.size.toLong())
                        val histories = value["records"]
                        require(histories.size() == if (index == 0L) 2 else 1) { "unique EXECUTE missing business history" }
                        if (index == 0L) require(histories[0]["kind"].asText() == "GENESIS" && histories[0]["input"].isNull)
                        val business = histories.last(); require(business["kind"].asText() == "BUSINESS" && canonical(business["input"]) == canonical(member["input"])) { "business input/source mismatch" }
                        histories.forEach { history -> reference.accept(history); keys.addAndGet(history["changes"].size().toLong() + 1) }; keys.addAndGet(2)
                        if (member["phase"].asText() == "timed") {
                            val kind = member["kind"].asText(); require(value["disposition"].asText() == if (kind == "CAPTURE") "CAPTURED" else "SETTLED")
                            if (kind == "CAPTURE") { captured.incrementAndGet(); if (beforeDeadline()) deadlineCaptured.incrementAndGet() } else { settled.incrementAndGet(); if (beforeDeadline()) deadlineSettled.incrementAndGet(); if (member["trade"].asLong() % 1000 == 0L) synchronized(latency) { latency.add((System.nanoTime() - member["offeredNano"].asLong()) / 1_000_000) } }
                        }
                        covered.incrementAndGet(); lastMember.set(index to member); members.remove(index); capacity.release()
                    }
                } } catch (error: Throwable) { fatal.compareAndSet(null, error) }
            }
            fun waitCovered(timeoutMs: Long = 120000) { val end = System.nanoTime() + timeoutMs * 1_000_000; while (covered.get() < ordinal.get() && System.nanoTime() < end && fatal.get() == null) Thread.sleep(10); fatal.get()?.let { throw IllegalStateException("rate probe failure", it) }; require(covered.get() == ordinal.get()) { "coverage/drain timeout" } }
            var heapPeak = 0L; var rssPeak: Long? = null; var diskPeak = 0L
            val start = timedStart; val deadline = AtomicReference<Map<String, Long>>()
            val sampler = Thread {
                try { while (!done.get()) {
                    val now = System.nanoTime(); if (start.get() > 0) { val elapsed = (now - start.get()) / 1_000_000; val cap = elapsed
                        synchronized(lag) { if ((seconds == 0L || cap < seconds * 1000) && (lag.isEmpty() || lag.last()["elapsedMs"] != cap)) lag.add(mapOf("elapsedMs" to cap, "lag" to maxOf(0L, admitted.get() - settled.get()))) }
                        // Deadline counts updated at individual event times, never credited from late sample.
                    }
                    heapPeak = maxOf(heapPeak, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used); rss()?.let { rssPeak = maxOf(rssPeak ?: 0, it) }
                    val snapshot = requireNotNull(physical()) { "physical broker budget monitoring unavailable" }; val disk = localBytes() + snapshot.first + snapshot.second; diskPeak = maxOf(diskPeak, disk); require(disk <= DISK_CAP) { "10GiB budget exceeded" }; Thread.sleep(1000)
                } } catch (error: Throwable) { if (!done.get()) fatal.compareAndSet(null, error) }
            }
            var producerMs = 0L; var observerMs = 0L; var drainMs = 0L; var cpuMs = 0L; var bytesPhysical = 0L; var pendingPhysical = 0L; var identityHeap = 0L
            var physicalEnd: Pair<Long, Long>? = null; var txnMs: Double? = null
            val workload = MessageDigest.getInstance("SHA-256")
            try {
                observer.start(); streams.start(); require(running.await(60, TimeUnit.SECONDS)); sampler.start()
                KafkaProducer(Properties().apply { putAll(props); put("acks", "all"); put("enable.idempotence", true); put("linger.ms", 2); put("batch.size", 65536); put("buffer.memory", 16 * 1024 * 1024) }, StringSerializer(), StringSerializer()).use { producer ->
                    fun send(id: Long, phase: String, kind: String) {
                        fatal.get()?.let { throw IllegalStateException("worker/observer failure", it) }; require(capacity.tryAcquire(10, TimeUnit.SECONDS)) { "bounded source suffix exhausted" }
                        val index = ordinal.getAndIncrement(); val input = action(id, phase, kind); val bytes = json.writeValueAsBytes(node(mapOf("domain" to "hot", "mode" to "EXECUTE", "inputOrdinal" to index, "input" to input)))
                        require(bytes.size <= 1024) { "source record exceeds pinned1KiB bound" }; retain(bytes); inputBytes.addAndGet(bytes.size.toLong()); if (phase == "timed") workload.update(json.writeValueAsBytes(input)); val offeredNano = System.nanoTime()
                        producer.send(ProducerRecord("$prefix-input", 0, "hot", String(bytes, Charsets.UTF_8))) { metadata, error -> if (error != null) fatal.compareAndSet(null, error) else {
                            members[index] = node(mapOf("ordinal" to index, "offset" to metadata.offset(), "domain" to "hot", "payloadSha256" to sha(bytes), "bytes" to bytes.size, "input" to input, "phase" to phase, "kind" to kind, "trade" to id, "offeredNano" to offeredNano)); if (phase == "timed" && kind == "SETTLE") { admitted.incrementAndGet(); if (beforeDeadline()) deadlineAdmitted.incrementAndGet() }
                        } }
                    }
                    for (id in 0 until aged) { send(id, "aged", "CAPTURE"); send(id, "aged", "SETTLE") }; if (mode == "run") for (id in 0 until pending) send(id, "pending", "CAPTURE")
                    producer.flush(); waitCovered(); System.gc(); Thread.sleep(100)
                    val heapBefore = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used; val before = physical(); val localBefore = localBytes(); val cpuBefore = os.processCpuTime
                    start.set(System.nanoTime()); synchronized(lag) { lag.add(mapOf("elapsedMs" to 0L, "lag" to 0L)) }
                    for (id in 0 until count) { if (rate > 0) { val due = start.get() + id * 1_000_000_000L / rate; while (System.nanoTime() < due) LockSupport.parkNanos(100_000) }; offered.incrementAndGet(); if (beforeDeadline()) deadlineOffered.incrementAndGet(); send(id, "timed", "CAPTURE"); send(id, "timed", "SETTLE") }
                    if (seconds > 0) while (System.nanoTime() < start.get() + seconds * 1_000_000_000) LockSupport.parkNanos(100_000)
                    producer.flush(); val producerEnd = System.nanoTime(); producerMs = maxOf(1L, (producerEnd - start.get()) / 1_000_000); waitCovered(); val observerEnd = System.nanoTime()
                    observerMs = maxOf(1L, (observerEnd - start.get()) / 1_000_000); drainMs = maxOf(0L, (observerEnd - producerEnd) / 1_000_000); cpuMs = (os.processCpuTime - cpuBefore) / 1_000_000
                    deadline.set(mapOf("offered" to deadlineOffered.get(), "admitted" to deadlineAdmitted.get(), "decided" to deadlineCaptured.get(), "settled" to deadlineSettled.get(), "pending" to deadlineCaptured.get() - deadlineSettled.get())); synchronized(lag) { if (seconds > 0) lag.add(mapOf("elapsedMs" to seconds * 1000, "lag" to maxOf(0L, deadlineAdmitted.get() - deadlineSettled.get()))) }; physicalEnd = physical(); bytesPhysical = if (before != null && physicalEnd != null) maxOf(0L, physicalEnd!!.first + physicalEnd!!.second - before.first - before.second + localBytes() - localBefore) else 0
                    System.gc(); Thread.sleep(100); identityHeap = maxOf(0L, (ManagementFactory.getMemoryMXBean().heapMemoryUsage.used - heapBefore) / count)
                    if (mode == "calibrate" && pending > 0) { val pBefore = physical(); val lBefore = localBytes(); for (id in 0 until pending) send(id, "pending", "CAPTURE"); producer.flush(); waitCovered(); val pAfter = physical(); pendingPhysical = if (pBefore != null && pAfter != null) maxOf(0L, (pAfter.first + pAfter.second - pBefore.first - pBefore.second + localBytes() - lBefore) / pending) else 0 }
                    txnMs = streams.metrics().filterKeys { it.name() == "txn-commit-time-ns-total" }.values.mapNotNull { (it.metricValue() as? Number)?.toDouble() }.takeIf { it.isNotEmpty() }?.sum()?.div(1_000_000)
                }
            } finally { done.set(true); observer.join(5000); sampler.interrupt(); sampler.join(15000); raw?.close(); streams.close(Duration.ofSeconds(15)) }
            fatal.get()?.let { throw IllegalStateException("rate aborted", it) }
            val expectedOwner = digest(reference.owner); val expectedChain = reference.chain; val expectedHistory = reference.historyRecords
            reference.owner.removeAll(); System.gc()
            val restored = Reference(balances, config["policy"]); val restoreStart = System.nanoTime(); var restoreBytes = 0L; var restoreInputs = 0L
            fun expectedInput(index: Long): JsonNode {
                if (mode == "calibrate") return if (index < count * 2) action(index / 2, "timed", if (index % 2 == 0L) "CAPTURE" else "SETTLE") else action(index - count * 2, "pending", "CAPTURE")
                return when {
                    index < aged * 2 -> action(index / 2, "aged", if (index % 2 == 0L) "CAPTURE" else "SETTLE")
                    index < aged * 2 + pending -> action(index - aged * 2, "pending", "CAPTURE")
                    else -> { val timed = index - aged * 2 - pending; action(timed / 2, "timed", if (timed % 2 == 0L) "CAPTURE" else "SETTLE") }
                }
            }
            KafkaConsumer(Properties().apply { putAll(props); put("enable.auto.commit", false); put("isolation.level", "read_committed") }, StringDeserializer(), StringDeserializer()).use { consumer ->
                val tp = TopicPartition("$prefix-results", 0); consumer.assign(listOf(tp)); consumer.seekToBeginning(listOf(tp))
                val end = restoreStart + TimeUnit.MINUTES.toNanos(5)
                while (restoreInputs < ordinal.get() && System.nanoTime() < end) for (record in consumer.poll(Duration.ofMillis(100))) {
                    val value = json.readTree(record.value()); require(value["inputOrdinal"].asLong() == restoreInputs)
                    val rows = value["records"]; require(rows.size() == if (restoreInputs == 0L) 2 else 1)
                    require(rows.last()["kind"].asText() == "BUSINESS" && canonical(rows.last()["input"]) == canonical(expectedInput(restoreInputs)))
                    rows.forEach { restored.accept(it) }; restoreBytes += record.value().toByteArray().size; restoreInputs++
                }
            }
            require(restoreInputs == ordinal.get() && expectedHistory == restored.historyRecords && expectedChain == restored.chain && expectedOwner == digest(restored.owner)) { "complete result-only restore mismatch" }
            val restoreMs = (System.nanoTime() - restoreStart) / 1_000_000
            val parityPath = outPath.parent.resolve("parity.json"); Files.writeString(parityPath, json.writeValueAsString(mapOf("ownerSha256" to expectedOwner, "historyChecksum" to expectedChain, "historyRecords" to expectedHistory, "sourceUuid" to uuid, "readCommitted" to true, "independentCompleteDeltas" to true)) + "\n")
            val result = if (mode == "calibrate") mapOf(
                "schema" to "financial-rate-calibration-v1", "sourceHead" to spec["sourceHead"].asText(), "fixtureSha256" to spec["fixtureSha256"].asText(), "realRecordEvidencePath" to "encoded-records.bin", "realRecordEvidenceSha256" to shaFile(rawPath), "sampleTrades" to count, "encodedBytes" to rawBytes,
                "physicalBytes" to bytesPhysical, "physicalReplicationIncluded" to true, "identityPhysicalBytes" to if (bytesPhysical > 0) maxOf(1L, bytesPhysical / count) else 0, "pendingPhysicalBytes" to pendingPhysical, "identityHeapBytes" to identityHeap,
                "producer" to mapOf("completedTrades" to admitted.get(), "elapsedMs" to producerMs), "observer" to mapOf("completedTrades" to settled.get(), "elapsedMs" to observerMs, "exactParity" to true), "sourceTopicUuid" to uuid)
            else mapOf("schema" to "financial-rate-measurement-v1", "policySha256" to spec["policySha256"].asText(), "sourceHead" to spec["sourceHead"].asText(), "fixtureSha256" to spec["fixtureSha256"].asText(), "workloadSha256" to HexFormat.of().formatHex(workload.digest()), "units" to "unique timed executions; preflight/aged rows excluded", "producerElapsedMs" to producerMs, "drainMs" to drainMs, "deadline" to deadline.get(), "final" to counts(),
                "parity" to mapOf("completeJournal" to true, "completeOwnerState" to true, "independentOracle" to true, "evidenceSha256" to sha(Files.readAllBytes(parityPath))), "lagSamples" to lag,
                "resources" to mapOf("processCpuMs" to cpuMs, "heapPeakBytes" to heapPeak, "rssPeakBytes" to rssPeak, "diskPeakBytes" to diskPeak, "encodedInputBytes" to inputBytes.get(), "encodedResultBytes" to outputBytes.get(), "encodedTechnicalBytes" to null, "physicalBrokerBytes" to physicalEnd?.first, "physicalChangelogBytes" to physicalEnd?.second, "touchedKeys" to keys.get(), "technicalRecords" to covered.get(), "transactionWaitMs" to txnMs),
                "stageLatency" to synchronized(latency) { val sorted = latency.sorted(); mapOf("kind" to "sampled-individual", "stage" to "offer-to-read-committed-settlement", "clockDomain" to "same-monotonic", "samples" to sorted.size, "p95Ms" to sorted.getOrNull((sorted.size * .95).toInt()), "p99Ms" to sorted.getOrNull((sorted.size * .99).toInt())) },
                "restore" to mapOf("records" to expectedHistory, "bytes" to restoreBytes, "elapsedMs" to restoreMs, "verifiedCut" to true), "gaps" to listOf("Managed restart requires recovered durable ACK membership; rate callback RAM registry not certified", "Encoded technical store bytes uninstrumented", "CPU scope same-JVM worker+producer+observer; broker CPU unmeasured", "Touched keys = emitted state/history/coverage/cert writes; framework reads excluded"), "capacityQualification" to false)
            Files.writeString(outPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n"); println(json.writeValueAsString(mapOf("artifact" to outPath.toString(), "offered" to offered.get(), "admitted" to admitted.get(), "settled" to settled.get())))
        }
    }
}
