package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import java.security.MessageDigest

/** Isolated test-only RF3/EOS financial adapter. Bounded delta writes; no live venue authority. */
object FinancialBrokerProbe {
    private val json = ObjectMapper()
    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 4) { "mode bootstrap prefix configuration-file [mode arguments]" }
        val mode = args[0]; val broker = args[1]; val prefix = args[2]
        val config = json.readTree(Files.readString(Path.of(args[3]))) as ObjectNode
        val cohortHash = sha(canonical(config))
        if (mode in setOf("worker", "observe", "observe-prefix", "reconstruct", "reconstruct-prefix")) {
            val manifest = json.readTree(Files.readString(Path.of(args[3] + ".accepted.json")))
            val unsigned = manifest.deepCopy<ObjectNode>(); unsigned.remove("checksum")
            require(manifest["checksum"].asText() == sha(canonical(unsigned)) && manifest["cohortSha256"].asText() == cohortHash) { "accepted source manifest checksum" }
            val actualId = AdminClient.create(properties(broker)).use { it.describeTopics(listOf("$prefix-input")).allTopicNames().get().getValue("$prefix-input").topicId().toString() }
            require(actualId == manifest["inputTopicId"].asText()) { "source topic UUID changed" }
            config.set<JsonNode>("acceptedManifest", manifest)
        }
        require(prefix.startsWith("financial-s1-")) { "isolated financial topic prefix required" }
        when (mode) {
            "init" -> AdminClient.create(properties(broker)).use { admin ->
                admin.createTopics(listOf("input", "results").map { suffix -> NewTopic("$prefix-$suffix", 1, 3.toShort()).configs(mapOf("cleanup.policy" to "delete", "write.caching" to "false", "min.insync.replicas" to "2")) }).all().get(30, TimeUnit.SECONDS)
                emit(mapOf("initialized" to prefix, "partitions" to 1, "replication" to 3))
            }
            "seed", "seed-prefix", "append-suffix" -> {
                val accepted = mutableListOf<JsonNode>()
                val prior = if (mode == "append-suffix") json.readTree(Files.readString(Path.of(args[3] + ".accepted.json"))) else null
                if (prior != null) {
                    val unsigned = prior.deepCopy<ObjectNode>(); unsigned.remove("checksum")
                    require(prior["checksum"].asText() == sha(canonical(unsigned)) && prior["cohortSha256"].asText() == cohortHash) { "suffix changed frozen cohort" }
                    prior["membership"].forEach { accepted.add(it) }
                }
                val limit = if (mode == "seed-prefix") args[4].toInt() else config["inputs"].size()
                require(limit in 1..config["inputs"].size())
                val topicId = AdminClient.create(properties(broker)).use { it.describeTopics(listOf("$prefix-input")).allTopicNames().get().getValue("$prefix-input").topicId().toString() }
                KafkaProducer(properties(broker).apply { put("acks", "all"); put("enable.idempotence", true); put("transactional.id", "$prefix-source-driver") }, StringSerializer(), StringSerializer()).use { producer ->
                    producer.initTransactions()
                    if (prior == null) {
                    producer.beginTransaction()
                    val aborted = config["inputs"][0].deepCopy<ObjectNode>(); aborted.put("inputOrdinal", -1)
                    producer.send(ProducerRecord("$prefix-input", 0, aborted["domain"].asText(), aborted.toString())).get(30, TimeUnit.SECONDS)
                    producer.abortTransaction()
                    }
                    producer.beginTransaction()
                    config["inputs"].forEachIndexed { ordinal, envelope ->
                        if (ordinal < accepted.size || ordinal >= limit) return@forEachIndexed
                        require(envelope["inputOrdinal"].asInt() == ordinal)
                        val body = envelope.toString()
                        val metadata = producer.send(ProducerRecord("$prefix-input", 0, envelope["domain"].asText(), body)).get(30, TimeUnit.SECONDS)
                        accepted.add(json.valueToTree(mapOf("ordinal" to ordinal, "offset" to metadata.offset(), "domain" to envelope["domain"].asText(), "payloadSha256" to sha(body), "bytes" to body.toByteArray().size)))
                    }
                    producer.commitTransaction()
                }
                val manifest = json.valueToTree<ObjectNode>(mapOf("schema" to "financial-e3-source-v1", "cohortSha256" to cohortHash, "inputTopicId" to topicId, "membership" to accepted, "abortedPrelude" to true))
                manifest.put("checksum", sha(canonical(manifest)))
                val manifestPath = Path.of(args[3] + ".accepted.json")
                val pendingManifest = Files.createTempFile(manifestPath.parent, "accepted-", ".json")
                Files.writeString(pendingManifest, manifest.toString() + "\n")
                Files.move(pendingManifest, manifestPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                emit(mapOf("offeredInputs" to accepted.size, "inputBytes" to accepted.sumOf { it["bytes"].asInt() }, "acceptedManifest" to args[3] + ".accepted.json", "manifestSha256" to manifest["checksum"].asText(), "physicalOffsets" to accepted.map { it["offset"] }))
            }
            "mutation-plan" -> {
                val models = mutableMapOf<String, FinancialKernel>()
                var mutations: List<Map<String, Any>> = emptyList()
                config["inputs"].forEach { envelope ->
                    val domain = envelope["domain"].asText()
                    val kernel = models.getOrPut(domain) { FinancialKernel(config["genesis"][domain]["balances"], config["policy"], retainHistory = false) }
                    kernel.execute(envelope["input"], detailed = false)
                    if (domain == "B" && envelope["input"]["kind"].asText() == "SETTLE") mutations = kernel.lastRecord()!!["changes"].mapIndexedNotNull { index, change ->
                        if (change["path"][0].asText() in setOf("historySeq", "deliveryCursor", "nextDelivery", "stagedInputs")) null else mapOf("index" to index, "path" to change["path"])
                    }
                }
                emit(mapOf("mutations" to mutations, "domains" to config["genesis"].fieldNames().asSequence().toList(), "frozenInputs" to config["inputs"].size()))
            }
            "worker" -> worker(broker, prefix, config, args[4], args[5], args.getOrNull(6) ?: "", args[3] + ".accepted.json")
            "observe-prefix" -> observe(broker, prefix, config, args.getOrNull(4)?.toLong() ?: 30000, limit = args[5].toInt())
            "observe" -> observe(broker, prefix, config, args.getOrNull(4)?.toLong() ?: 30000)
            "reconstruct" -> observe(broker, prefix, config, args.getOrNull(4)?.toLong() ?: 30000, reconstruct = true)
            "reconstruct-prefix" -> observe(broker, prefix, config, args[4].toLong(), reconstruct = true, limit = args[5].toInt())
            else -> error("unknown mode $mode")
        }
    }

    private fun worker(broker: String, prefix: String, config: JsonNode, app: String, stateDir: String, fault: String, acceptedPath: String) {
        val started = System.nanoTime()
        require(app == "$prefix-app" && stateDir.contains("reef-financial-e3-")) { "unregistered app/state directory" }
        val topology = topology(config, fault, prefix, { ordinal ->
            val deadline = System.nanoTime() + 10_000_000_000L
            while (ordinal >= config["acceptedManifest"]["membership"].size() && System.nanoTime() < deadline) {
                val refreshed = json.readTree(Files.readString(Path.of(acceptedPath)))
                val unsigned = refreshed.deepCopy<ObjectNode>(); unsigned.remove("checksum")
                val old = config["acceptedManifest"]
                require(refreshed["checksum"].asText() == sha(canonical(unsigned)) && refreshed["cohortSha256"] == old["cohortSha256"] && refreshed["inputTopicId"] == old["inputTopicId"]) { "invalid membership extension" }
                old["membership"].forEachIndexed { index, member -> require(member == refreshed["membership"][index]) { "source membership rewritten" } }
                (config as ObjectNode).set<JsonNode>("acceptedManifest", refreshed)
                if (ordinal >= refreshed["membership"].size()) Thread.sleep(50)
            }
            require(ordinal < config["acceptedManifest"]["membership"].size()) { "source acknowledgement not registered before bounded deadline" }
            config["acceptedManifest"]["membership"][ordinal.toInt()]
        })
        val props = workerProperties(broker, app, stateDir, config, fault)
        val failed = CountDownLatch(1)
        val clients = object : DefaultKafkaClientSupplier() {
            override fun getProducer(config: MutableMap<String, Any>): Producer<ByteArray, ByteArray> {
                val delegate = super.getProducer(config)
                return object : Producer<ByteArray, ByteArray> by delegate {
                    override fun send(record: ProducerRecord<ByteArray, ByteArray>, callback: Callback?): Future<RecordMetadata> {
                        try {
                            return delegate.send(record, Callback { metadata, failure ->
                                if (failure != null) producerFailure("send", failure, record.topic())
                                callback?.onCompletion(metadata, failure)
                            })
                        } catch (failure: Exception) { producerFailure("send", failure, record.topic()); throw failure }
                    }
                    override fun sendOffsetsToTransaction(offsets: MutableMap<TopicPartition, OffsetAndMetadata>, group: ConsumerGroupMetadata) {
                        try { delegate.sendOffsetsToTransaction(offsets, group) }
                        catch (failure: Exception) { producerFailure("sendOffsetsToTransaction", failure, group.groupId()); throw failure }
                    }
                    override fun commitTransaction() {
                        try { delegate.commitTransaction() }
                        catch (failure: Exception) { producerFailure("commitTransaction", failure, app); throw failure }
                    }
                }
            }
        }
        val streams = KafkaStreams(topology, props, clients)
        streams.setUncaughtExceptionHandler { failure ->
            emit(mapOf("halted" to failure.toString(), "cacheInvalidated" to true))
            failed.countDown(); StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
        }
        streams.setStateListener { next, _ -> if (next == KafkaStreams.State.RUNNING) emit(mapOf("frameworkRunningMs" to (System.nanoTime() - started) / 1_000_000)) }
        Runtime.getRuntime().addShutdownHook(Thread { streams.close(Duration.ofSeconds(10)) })
        emit(mapOf("processStarted" to true, "guarantee" to "exactly_once_v2", "commitIntervalMs" to props[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG], "transactionTimeoutMs" to props[StreamsConfig.producerPrefix(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG)], "stateDir" to stateDir))
        streams.start()
        if (failed.await(30, TimeUnit.MINUTES)) { streams.close(Duration.ofSeconds(10)); error("financial worker failed; caches discarded") }
    }

    internal fun topology(
        config: JsonNode,
        fault: String,
        prefix: String,
        membership: (Long) -> JsonNode = { ordinal -> config["acceptedManifest"]["membership"][ordinal.toInt()] },
        sourceUuid: String = config["acceptedManifest"]["inputTopicId"].asText(),
        onActivation: (FinancialPartitionCut.ReconstructedCut) -> Unit = {},
    ): Topology {
        return Topology().addSource("financial-input", StringDeserializer(), StringDeserializer(), "$prefix-input")
            .addProcessor("financial", { FinancialProcessor(config, fault, membership, sourceUuid, onActivation) }, "financial-input")
            .addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("financial-state"), Serdes.String(), Serdes.String()).withCachingDisabled(), "financial")
            .addSink("financial-results", "$prefix-results", StringSerializer(), object : Serializer<String> {
                override fun serialize(topic: String?, data: String?): ByteArray? {
                    return outputBytes(fault, data)
                }
            }, "financial")
    }

    /** Cold verifier consumes one member at a time; retain no second JSON prefix. */
    internal fun activationMembership(config: JsonNode, coverageSize: Int, membership: (Long) -> JsonNode): List<JsonNode> {
        require(coverageSize >= 0)
        val declared = config["acceptedManifest"]?.get("membership")
        require(declared == null || declared.isArray) { "invalid accepted membership" }
        return object : AbstractList<JsonNode>() {
            override val size: Int = declared?.size() ?: coverageSize
            override fun get(index: Int): JsonNode {
                if (index !in 0 until size) throw IndexOutOfBoundsException("membership index=$index size=$size")
                return declared?.get(index) ?: membership(index.toLong())
            }
        }
    }

    private class FinancialProcessor(private val config: JsonNode, private val fault: String, private val membership: (Long) -> JsonNode,
        private val sourceUuid: String, private val onActivation: (FinancialPartitionCut.ReconstructedCut) -> Unit) : Processor<String, String, String, String> {
        private lateinit var context: ProcessorContext<String, String>
        private lateinit var store: KeyValueStore<String, String>
        private val kernels = mutableMapOf<String, FinancialKernel>()
        private val authorityHash = sha(canonical(json.valueToTree(mapOf("policy" to config["policy"], "genesis" to config["genesis"]))))
        private var faultTriggered = false
        private val resume = java.util.concurrent.Semaphore(0)
        @Volatile private var pausePending = false
        override fun init(context: ProcessorContext<String, String>) {
            this.context = context; store = context.getStateStore("financial-state")
            if (fault in setOf("pause", "stale-owner")) Thread { System.`in`.bufferedReader().forEachLine { if (it == "resume") resume.release() } }.apply { isDaemon = true; start() }
            // Cold recovery only. Hot processing performs known-key reads/writes from bounded semantic deltas.
            val coverage = mutableListOf<JsonNode>()
            val historyWrappers = mutableMapOf<String, JsonNode>()
            val semanticState = mutableMapOf<String, JsonNode>()
            store.all().use { rows -> while (rows.hasNext()) {
                val row = rows.next()
                when {
                    row.key.startsWith("c/") -> coverage.add(json.readTree(row.value))
                    row.key.startsWith("h/") -> historyWrappers[row.key] = json.readTree(row.value)
                    row.key.startsWith("s/") -> semanticState[row.key] = json.readTree(row.value)
                }
            } }
            val certificate = store.get("cert/0")?.let { json.readTree(it) }
            val acceptedMembers = activationMembership(config, coverage.size, membership)
            val restored = FinancialPartitionCut.validateActivation(config, sourceUuid,
                acceptedMembers, certificate, coverage, historyWrappers, semanticState)
            kernels.putAll(restored.owners)
            onActivation(restored)
            emit(mapOf("certifiedCatchupDomains" to kernels.size, "partitionCut" to store.get("cert/0")))
        }
        override fun process(record: Record<String, String>) {
            if (pausePending) { resume.acquire(); pausePending = false }
            val envelope = json.readTree(record.value())
            val domain = envelope["domain"].asText()
            require(config["genesis"].has(domain) && record.key() == domain) { "undeclared domain or key mismatch" }
            if (envelope["mode"].asText() != "DRAIN") require(envelope["input"]["domain"].asText() == domain) { "cross-domain input" }
            val metadata = context.recordMetadata().orElseThrow()
            require(metadata.partition() == 0)
            val offset = metadata.offset()
            val priorCertificate = store.get("cert/0")?.let { json.readTree(it) }
            val priorOffset = priorCertificate?.get("offset")?.asLong() ?: -1L
            val ordinal = envelope["inputOrdinal"].asLong()
            val priorOrdinal = priorCertificate?.get("ordinal")?.asLong() ?: -1L
            require(ordinal == priorOrdinal + 1 && offset > priorOffset) { "missing logical source membership" }
            val sourceMember = membership(ordinal)
            require(sourceMember["ordinal"].asLong() == ordinal && sourceMember["offset"].asLong() == offset && sourceMember["domain"].asText() == domain && sourceMember["payloadSha256"].asText() == sha(record.value())) { "unregistered source fact" }
            val records = mutableListOf<JsonNode>()
            val priorHead = kernels[domain]?.let { mapOf("sequence" to it.historySequence(), "checksum" to it.lastRecord()!!["checksum"].asText()) } ?: mapOf("sequence" to 0L, "checksum" to "GENESIS")
            val kernel = kernels.getOrPut(domain) {
                val genesis = config["genesis"][domain]
                FinancialKernel(genesis["balances"], config["policy"], retainHistory = false).also { records.add(it.lastRecord()!!) }
            }
            val before = kernel.historySequence()
            val result = when (envelope["mode"].asText()) {
                "EXECUTE" -> kernel.execute(envelope["input"], detailed = false)
                "STAGE" -> kernel.stage(envelope["input"], detailed = false)
                "DRAIN" -> kernel.drainStaged(maxDecisions = 1, detailed = false).singleOrNull()
                else -> error("unknown financial envelope")
            }
            if (kernel.historySequence() != before) records.add(kernel.lastRecord()!!)
            records.forEach { history ->
                history["changes"].forEachIndexed { index, change ->
                    val key = "s/" + json.writeValueAsString(listOf(domain) + change["path"].map { it.asText() })
                    val stored = store.get(key)?.let { json.readTree(it) }
                    val provenGenesis = history["kind"].asText() == "GENESIS" || store.get("h/$domain/00000000000000000001") != null
                    require(provenGenesis) { "semantic state cannot bootstrap without genesis" }
                    val default = defaultValue(change["path"].map { it.asText() })
                    require(canonical(stored ?: default) == canonical(change["before"])) { "persistent semantic predecessor mismatch key=$key" }
                    val after = change["after"]
                    if (after.isNull && change["path"].size() > 1) store.delete(key) else store.put(key, after.toString())
                    if (!faultTriggered && fault == "mutation:$index" && domain == "B" && history["input"]?.get("kind")?.asText() == "SETTLE") {
                        faultTriggered = true; emit(mapOf("injectedMutation" to index, "key" to key)); Runtime.getRuntime().halt(91)
                    }
                }
                val seq = history["sequence"].asLong()
                store.put("h/$domain/${seq.toString().padStart(20, '0')}", json.writeValueAsString(mapOf("domain" to domain, "record" to history, "source" to mapOf("ordinal" to ordinal, "offset" to offset, "uuid" to sourceUuid, "payloadSha256" to sourceMember["payloadSha256"].asText()))))
            }
            val afterHead = mapOf("sequence" to kernel.historySequence(), "checksum" to kernel.lastRecord()!!["checksum"].asText())
            store.put("c/${ordinal.toString().padStart(20, '0')}", json.writeValueAsString(mapOf("ordinal" to ordinal, "offset" to offset, "uuid" to sourceUuid, "authoritySha256" to authorityHash, "domain" to domain, "payloadSha256" to sourceMember["payloadSha256"].asText(), "headBefore" to priorHead, "headAfter" to afterHead, "history" to records.map { mapOf("sequence" to it["sequence"].asLong(), "checksum" to it["checksum"].asText()) })))
            store.put("cert/0", json.writeValueAsString(mapOf("offset" to offset, "ordinal" to ordinal, "sourceUuid" to sourceUuid, "authoritySha256" to authorityHash, "owners" to kernels.mapValues { (_, owner) -> mapOf("sequence" to owner.historySequence(), "checksum" to owner.lastRecord()!!["checksum"].asText()) })))
            val output = json.writeValueAsString(mapOf("domain" to domain, "inputOffset" to offset, "inputOrdinal" to ordinal, "records" to records, "disposition" to result?.get("disposition")?.asText()))
            if (fault == "stale-owner" && ordinal == config["pauseBeforeOrdinal"].asLong()) {
                emit(mapOf("faultBoundary" to "before-forward", "inputOrdinal" to ordinal, "inputOffset" to offset, "storeCertificateCandidate" to store.get("cert/0"), "outputBytes" to output.toByteArray(Charsets.UTF_8).size, "commitRequested" to false))
                resume.acquire()
                emit(mapOf("faultBoundary" to "stale-owner-resumed", "inputOrdinal" to ordinal, "inputOffset" to offset))
            }
            context.forward(Record(domain, output, record.timestamp()))
            if (fault == "forward" && domain == "B" && records.any { it["input"]?.get("kind")?.asText() == "SETTLE" }) Runtime.getRuntime().halt(91)
            if (envelope["commit"]?.asBoolean() == true) context.commit()
            if (fault == "pause" && ordinal == config["pauseAfterOrdinal"].asLong()) {
                context.commit(); pausePending = true
                emit(mapOf("pausePendingAfterOrdinal" to ordinal, "scope" to "controller must verify read_committed marker before stopping"))
            }
        }
        override fun close() { kernels.clear(); resume.release() }
    }

    private fun observe(broker: String, prefix: String, config: JsonNode, timeoutMs: Long, reconstruct: Boolean = false, limit: Int = config["inputs"].size()) {
        val verifier = OutputVerifier(config)
        val owners = verifier.owners
        val histories = verifier.histories
        var covered = -1L; var physical = -1L; var business = 0; var settled = 0
        var initialEnd = 0L; var finalEnd = 0L; val outputOffsets = mutableListOf<Long>()
        val committedOutputs = mutableListOf<JsonNode>()
        KafkaConsumer(properties(broker).apply { put("enable.auto.commit", false); put("auto.offset.reset", "earliest"); put("isolation.level", "read_committed") }, StringDeserializer(), StringDeserializer()).use { consumer ->
            val partition = TopicPartition("$prefix-results", 0)
            consumer.assign(listOf(partition)); consumer.seekToBeginning(listOf(partition))
            initialEnd = consumer.endOffsets(listOf(partition)).getValue(partition)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var quietUntil = Long.MAX_VALUE
            while (System.nanoTime() < deadline && (covered + 1 < limit || System.nanoTime() < quietUntil || consumer.position(partition) < finalEnd)) {
                for (record in consumer.poll(Duration.ofMillis(100))) {
                    require(covered + 1 < limit) { "extra committed output after declared input membership" }
                    outputOffsets.add(record.offset())
                    val output = json.readTree(record.value()); val domain = output["domain"].asText()
                    require(output["inputOrdinal"].asLong() == covered + 1 && output["inputOffset"].asLong() > physical) { "output logical membership gap/duplicate" }; covered++
                    val member = config["acceptedManifest"]["membership"][covered.toInt()]
                    require(member["offset"].asLong() == output["inputOffset"].asLong() && member["domain"].asText() == domain) { "output source membership mismatch" }
                    physical = output["inputOffset"].asLong()
                    verifier.accept(output); committedOutputs.add(output)
                    business = verifier.business; settled = verifier.settled
                    if (covered + 1 == limit.toLong()) quietUntil = System.nanoTime() + 1_500_000_000L
                }
                if (covered + 1 == limit.toLong()) finalEnd = consumer.endOffsets(listOf(partition)).getValue(partition)
            }
            finalEnd = consumer.endOffsets(listOf(partition)).getValue(partition)
        }
        val complete = covered + 1 == limit.toLong()
        val inputPartition = TopicPartition("$prefix-input", 0)
        val groupCheckpoint = AdminClient.create(properties(broker)).use { admin ->
            admin.listConsumerGroupOffsets("$prefix-app").partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)[inputPartition]?.offset() ?: -1L
        }
        val inputEnd = KafkaConsumer(properties(broker).apply { put("isolation.level", "read_committed") }, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.endOffsets(listOf(inputPartition), Duration.ofSeconds(10)).getValue(inputPartition)
        }
        if (complete) {
            verifier.requirePrefix(limit)
            require(groupCheckpoint in (physical + 1)..inputEnd) { "committed results/input checkpoint disagreement" }
        }
        if (reconstruct) {
            require(complete) { "cannot certify incomplete partition coverage" }
            val manifest = config["acceptedManifest"]
            val cut = FinancialCommittedCut.build(config, manifest, committedOutputs)
            val sourceUuid = manifest["inputTopicId"].asText()
            val members = manifest["membership"].toList()
            val rebuilt = FinancialPartitionCut.reconstruct(config, sourceUuid, members, cut.certificate, cut.coverage, cut.histories)
            val quartet = config.path("caseId").asText() == "activation-quartet"
            if (quartet) {
                require(limit == 4 && owners["A"]!!["balances"]["buyerCash"].asText() == "15" && owners["B"]!!["balances"]["buyerCash"].asText() == "27") { "exact quartet not observed" }
                val mixed = cut.snapshots.first().filterKeys { json.readTree(it.removePrefix("s/"))[0].asText() == "A" } +
                    cut.snapshots.last().filterKeys { json.readTree(it.removePrefix("s/"))[0].asText() == "B" }
                require(mixed["s/" + json.writeValueAsString(listOf("A", "balances", "buyerCash"))]!!.asText() == "10") { "mixed A snapshot differs from recorded A+10" }
                require(runCatching { FinancialPartitionCut.validateActivation(config, sourceUuid, members, cut.certificate, cut.coverage, cut.histories, mixed) }.isFailure) { "mixed A10/B27 activation allowed" }
            } else {
                val mixedCertificate = cut.certificate.deepCopy<ObjectNode>()
                val oldest = cut.histories.values.first()
                (mixedCertificate["owners"] as ObjectNode).set<JsonNode>(oldest["domain"].asText(),
                    json.valueToTree(mapOf("sequence" to oldest["record"]["sequence"].asLong(), "checksum" to oldest["record"]["checksum"].asText())))
                require(runCatching { FinancialPartitionCut.reconstruct(config, sourceUuid, members, mixedCertificate, cut.coverage, cut.histories) }.isFailure) { "mixed owner certificate allowed" }
            }
            val missingKey = if (quartet) "h/A/00000000000000000003" else cut.histories.keys.first()
            require(cut.histories.containsKey(missingKey)) { "missing-history control not applicable" }
            require(runCatching { FinancialPartitionCut.reconstruct(config, sourceUuid, members, cut.certificate, cut.coverage, cut.histories.filterKeys { it != missingKey }) }.isFailure) { "incomplete history activation allowed" }
            require(rebuilt.semanticState == cut.snapshots.last()) { "complete history changed semantic state" }
            emit(mapOf("isolatedReconstruction" to mapOf("inputPartitionOrdinal" to covered, "inputPartitionOffset" to physical,
                "capturedInputGroupCheckpoint" to groupCheckpoint, "inputReadCommittedEndOffset" to inputEnd,
                "sourceDerivedCertificate" to cut.certificate, "mixedAgeRefused" to true, "missingHistoryRefused" to true,
                "repairFromCompleteHistory" to true, "exactQuartetA15B27" to quartet, "committedEndBeforeReconstruction" to finalEnd,
                "scope" to "isolated activation request from actual read_committed outputs; same mandatory startup verifier; no injected mixed RocksDB/changelog restore")))
        }
        emit(mapOf("result" to mapOf("initialCommittedEndOffset" to initialEnd, "finalCommittedEndOffset" to finalEnd, "resultOffsets" to outputOffsets, "ownerSnapshots" to owners, "scope" to (if (limit == config["inputs"].size()) "full input/phase completion" else "certified prefix including pending phases"), "consecutiveResultOffsets" to outputOffsets.zipWithNext().all { (left, right) -> right == left + 1 }, "coveredInputs" to covered + 1, "lastInputOffset" to physical, "inputGroupCheckpoint" to groupCheckpoint, "inputReadCommittedEndOffset" to inputEnd, "ownerCuts" to histories.mapValues { (_, rows) -> mapOf("sequence" to rows.last()["sequence"].asLong(), "checksum" to rows.last()["checksum"].asText()) }, "expectedInputs" to limit, "businessDecisions" to business, "settlements" to settled, "domains" to owners.size, "readCommittedOracle" to true, "pass" to complete)))
    }

    /** Reference dispatch derives expected decisions from accepted inputs, including zero-record deliveries. */
    internal class OutputVerifier(private val config: JsonNode) {
        val owners = linkedMapOf<String, ObjectNode>()
        val histories = linkedMapOf<String, MutableList<JsonNode>>()
        private val oracles = mutableMapOf<String, FinancialOracle>()
        private data class Staged(val input: JsonNode, val digest: String, val position: Long)
        private val pending = mutableMapOf<String, LinkedHashMap<String, Staged>>()
        private val nextDelivery = mutableMapOf<String, Long>()
        private val cursor = mutableMapOf<String, Long>()
        var business = 0; private set
        var settled = 0; private set
        private var ordinal = -1L
        fun accept(output: JsonNode) {
            require(output["inputOrdinal"].asLong() == ordinal + 1) { "output logical prefix gap" }; ordinal++
            val expectedEnvelope = config["inputs"][ordinal.toInt()]
            val domain = expectedEnvelope["domain"].asText()
            require(output["domain"].asText() == domain) { "cross-domain output" }
            val oracle = oracles.getOrPut(domain) { FinancialOracle(config["genesis"][domain]["balances"], config["policy"]) }
            val queue = pending.getOrPut(domain) { linkedMapOf() }
            val beforeSeq = oracle.businessView()["businessSeq"].asLong()
            var disposition: String? = null
            fun enqueue(input: JsonNode) {
                val digest = requestDigest(input)
                val key = json.writeValueAsString(listOf(actionKey(input), digest))
                if (!queue.containsKey(key)) {
                    val position = nextDelivery[domain] ?: 0L
                    queue[key] = Staged(input, digest, position); nextDelivery[domain] = position + 1
                }
            }
            when (expectedEnvelope["mode"].asText()) {
                "STAGE" -> { enqueue(expectedEnvelope["input"]); disposition = "STAGED" }
                "EXECUTE" -> {
                    disposition = oracle.execute(expectedEnvelope["input"])["disposition"].asText()
                    if (disposition == "STAGED") enqueue(expectedEnvelope["input"])
                }
                "DRAIN" -> {
                    val view = oracle.businessView()
                    val item = if (view["continuation"].isNull) queue.entries.minByOrNull { it.value.position } else queue.entries.filter {
                        it.value.input.path("kind").asText().uppercase() == "CONTINUE" && it.value.input.path("payload").path("workId").asText() == view["dueWork"][0].asText()
                    }.minByOrNull { it.value.position }
                    if (item != null) {
                        queue.remove(item.key); cursor[domain] = (cursor[domain] ?: 0L) + 1
                        disposition = oracle.execute(item.value.input)["disposition"].asText()
                    }
                }
                else -> error("unsupported source dispatch")
            }
            val owner = owners.getOrPut(domain) { emptyOwner() }
            val domainHistory = histories.getOrPut(domain) { mutableListOf() }
            var emittedBusiness = 0
            output["records"].forEach { history ->
                verifyRecord(history, domainHistory.lastOrNull()); domainHistory.add(history)
                history["changes"].forEach { change -> apply(owner, change) }
                if (history["kind"].asText() == "BUSINESS") emittedBusiness++
            }
            require(domainHistory.isNotEmpty() && domainHistory.first()["kind"].asText() == "GENESIS") { "missing genesis authority" }
            require(emittedBusiness.toLong() == oracle.businessView()["businessSeq"].asLong() - beforeSeq) { "missing or extra business authority" }
            require(output["disposition"]?.takeUnless { it.isNull }?.asText() == disposition) { "source disposition mismatch" }
            oracle.assertMatches(businessView(owner), "broker domain=$domain input=$ordinal")
            val expectedStaging = json.valueToTree<JsonNode>(queue.mapValues { (_, value) -> mapOf("input" to value.input, "digest" to value.digest, "position" to value.position) })
            require(canonical(owner["stagedInputs"]) == canonical(expectedStaging)) { "missing or extra staged authority" }
            require(owner["nextDelivery"].asLong() == (nextDelivery[domain] ?: 0L) && owner["deliveryCursor"].asLong() == (cursor[domain] ?: 0L)) { "delivery cursor disagreement" }
            business += emittedBusiness; if (disposition == "SETTLED") settled++
        }
        fun requirePrefix(count: Int) {
            require(ordinal + 1 == count.toLong()) { "incomplete input prefix" }
            if (count == config["inputs"].size()) require(pending.values.all { it.isEmpty() } && owners.values.all { it["continuation"].isNull }) { "financial phase remains unfinished" }
        }
        private fun actionKey(input: JsonNode) = json.writeValueAsString(listOf("namespace", "domain", "actionId").map { input.path(it) })
        private fun requestDigest(input: JsonNode): String {
            // Independent source normalization: expected malformed input retains canonical identity while staged.
            val normalized = runCatching {
                val payload = input.path("payload")
                require(payload.isObject)
                val numeric = setOf("quantity", "priceNanos", "dueTick", "attempt", "amount", "tick")
                val pairs = json.createArrayNode()
                payload.fieldNames().asSequence().sorted().forEach { name ->
                    val value = payload[name]
                    val normalizedValue = if (name in numeric) {
                        require(value.isTextual && value.asText().matches(Regex("-?[0-9]+")))
                        json.nodeFactory.textNode(value.asText().toBigInteger().toString())
                    } else value
                    pairs.add(json.createArrayNode().add(name).add(normalizedValue))
                }
                json.writeValueAsString(listOf(input.path("kind").asText().uppercase(), input.get("requestedPolicy"), pairs))
            }
            return sha(normalized.getOrElse { canonical(input) })
        }
    }

    private fun emptyOwner(): ObjectNode = json.createObjectNode().apply {
        listOf("balances", "executions", "obligations", "workflows", "instructions", "attempts", "exceptions", "reservations", "dedup", "effects", "versions", "dueQueue", "stagedInputs", "policy").forEach { set<JsonNode>(it, json.createObjectNode()) }
        put("logicalTick", "0"); listOf("businessSeq", "historySeq", "deliveryCursor", "nextDelivery").forEach { put(it, 0L) }
        listOf("continuation", "lastDecisionId", "semanticDigest", "activePolicy").forEach { putNull(it) }
    }
    private fun defaultValue(path: List<String>): JsonNode {
        var node: JsonNode = emptyOwner()
        path.forEach { key -> node = node.get(key) ?: json.nullNode() }
        return node
    }
    private fun apply(owner: ObjectNode, change: JsonNode) {
        val path = change["path"].map { it.asText() }
        var parent = owner
        path.dropLast(1).forEach { key -> parent = parent[key] as ObjectNode }
        val key = path.last(); val previous = parent.get(key) ?: json.nullNode()
        require(canonical(previous) == canonical(change["before"])) { "observer delta before mismatch path=$path" }
        if (change["after"].isNull && path.size > 1) parent.remove(key) else parent.set<JsonNode>(key, change["after"].deepCopy())
    }
    private fun businessView(owner: ObjectNode): JsonNode = owner.deepCopy().apply {
        listOf("policy", "historySeq", "deliveryCursor", "nextDelivery", "stagedInputs").forEach { remove(it) }
        val queue = this["dueQueue"]
        val due = if (this["continuation"].isNull) emptyList() else queue.fields().asSequence().filter { it.value["tick"].asText().toBigInteger() <= this["logicalTick"].asText().toBigInteger() }.sortedWith(compareBy<Map.Entry<String, JsonNode>> { it.value["tick"].asText().toBigInteger() }.thenBy { it.value["workId"].asText() }.thenBy { it.key }).map { it.value["workId"].asText() }.toList()
        set<JsonNode>("dueWork", json.valueToTree(due))
    }
    private fun verifyHistory(records: List<JsonNode>) {
        records.forEachIndexed { index, record -> verifyRecord(record, records.getOrNull(index - 1)) }
    }
    private fun verifyRecord(record: JsonNode, previous: JsonNode?) {
        require(if (previous == null) record["kind"].asText() == "GENESIS" && record["sequence"].asLong() == 1L && record["priorSequence"].asLong() == 0L && record["priorChecksum"].asText() == "GENESIS" else record["sequence"].asLong() == previous["sequence"].asLong() + 1 && record["priorSequence"].asLong() == previous["sequence"].asLong() && record["priorChecksum"].asText() == previous["checksum"].asText()) { "uncertified history prefix" }
        val unsigned = record.deepCopy<ObjectNode>(); unsigned.remove("checksum")
        require(record["checksum"].asText() == sha(canonical(unsigned))) { "history checksum mismatch" }
    }
    internal fun workerProperties(broker: String, app: String, stateDir: String, config: JsonNode, fault: String = ""): Properties = properties(broker).apply {
        put(StreamsConfig.APPLICATION_ID_CONFIG, app); put(StreamsConfig.STATE_DIR_CONFIG, stateDir)
        put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
        // Preserve sixty-second grouping; EOS default ten-second timeout cannot cover this interval.
        put(StreamsConfig.producerPrefix(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG), 120000)
        put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3); put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1)
        put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, config["commitIntervalMs"]?.asInt() ?: 60000); put("topic.min.insync.replicas", 2); put("topic.write.caching", false)
        put("default.deserialization.exception.handler", "org.apache.kafka.streams.errors.LogAndFailExceptionHandler")
        put("default.production.exception.handler", "org.apache.kafka.streams.errors.DefaultProductionExceptionHandler")
        put("processing.exception.handler", "org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler")
        put("consumer.max.poll.records", config["maxPollRecords"]?.asInt() ?: 128); put("consumer.max.poll.interval.ms", 10000); put("consumer.session.timeout.ms", 6000); put("consumer.heartbeat.interval.ms", 1000)
        if (fault == "production") put("producer.max.request.size", 131072)
        if (fault == "stale-owner") {
            // Bound paused producer transaction lifetime so replacement changelog restoration cannot wait two minutes.
            put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000)
            put(StreamsConfig.producerPrefix(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG), 30000)
        }
    }

    internal fun outputBytes(fault: String, data: String?): ByteArray? {
        if (data == null) return null
        if (fault == "serialization" && data.contains("\"SETTLED\"")) throw IllegalStateException("injected financial serializer failure")
        val target = fault == "production" && json.readTree(data).let { it["domain"]?.asText() == "B" && it["disposition"]?.asText() == "SETTLED" }
        return (if (target) data + " ".repeat(131072) else data).toByteArray(Charsets.UTF_8)
    }

    private fun producerFailure(boundary: String, failure: Exception, resource: String) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(12).map { it.javaClass.name }.toList()
        emit(mapOf("producerBoundary" to boundary, "failureClass" to failure.javaClass.name, "causeClasses" to causes, "resource" to resource, "failure" to failure.toString()))
    }

    private fun properties(broker: String) = Properties().apply { put("bootstrap.servers", broker) }
    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(node[it]) }
        node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
        else -> node.toString()
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun emit(value: Any) { println(json.writeValueAsString(value)); System.out.flush() }
}
