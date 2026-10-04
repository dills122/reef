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
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
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
        if (mode in setOf("worker", "observe", "observe-prefix", "reconstruct")) {
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
        val props = properties(broker).apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, app); put(StreamsConfig.STATE_DIR_CONFIG, stateDir)
            put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
            put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3); put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1)
            put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, config["commitIntervalMs"]?.asInt() ?: 60000); put("topic.min.insync.replicas", 2); put("topic.write.caching", false)
            put("default.deserialization.exception.handler", "org.apache.kafka.streams.errors.LogAndFailExceptionHandler")
            put("default.production.exception.handler", "org.apache.kafka.streams.errors.DefaultProductionExceptionHandler")
            put("processing.exception.handler", "org.apache.kafka.streams.errors.LogAndFailProcessingExceptionHandler")
            put("consumer.max.poll.records", config["maxPollRecords"]?.asInt() ?: 128); put("consumer.max.poll.interval.ms", 10000); put("consumer.session.timeout.ms", 6000); put("consumer.heartbeat.interval.ms", 1000)
            if (fault == "production") put("producer.max.request.size", 512)
        }
        val failed = CountDownLatch(1)
        val streams = KafkaStreams(topology, props)
        streams.setUncaughtExceptionHandler { failure ->
            emit(mapOf("halted" to failure.toString(), "cacheInvalidated" to true))
            failed.countDown(); StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
        }
        streams.setStateListener { next, _ -> if (next == KafkaStreams.State.RUNNING) emit(mapOf("frameworkRunningMs" to (System.nanoTime() - started) / 1_000_000)) }
        Runtime.getRuntime().addShutdownHook(Thread { streams.close(Duration.ofSeconds(10)) })
        emit(mapOf("processStarted" to true, "guarantee" to "exactly_once_v2", "commitIntervalMs" to (config["commitIntervalMs"]?.asInt() ?: 60000), "stateDir" to stateDir))
        streams.start()
        if (failed.await(30, TimeUnit.MINUTES)) { streams.close(Duration.ofSeconds(10)); error("financial worker failed; caches discarded") }
    }

    internal fun topology(
        config: JsonNode,
        fault: String,
        prefix: String,
        membership: (Long) -> JsonNode = { ordinal -> config["acceptedManifest"]["membership"][ordinal.toInt()] },
        sourceUuid: String = config["acceptedManifest"]["inputTopicId"].asText(),
    ): Topology {
        return Topology().addSource("financial-input", StringDeserializer(), StringDeserializer(), "$prefix-input")
            .addProcessor("financial", { FinancialProcessor(config, fault, membership, sourceUuid) }, "financial-input")
            .addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("financial-state"), Serdes.String(), Serdes.String()).withCachingDisabled(), "financial")
            .addSink("financial-results", "$prefix-results", StringSerializer(), object : Serializer<String> {
                override fun serialize(topic: String?, data: String?): ByteArray? {
                    if (fault == "serialization" && data?.contains("\"SETTLED\"") == true) throw IllegalStateException("injected financial serializer failure")
                    return data?.toByteArray(Charsets.UTF_8)
                }
            }, "financial")
    }

    private class FinancialProcessor(private val config: JsonNode, private val fault: String, private val membership: (Long) -> JsonNode, private val sourceUuid: String) : Processor<String, String, String, String> {
        private lateinit var context: ProcessorContext<String, String>
        private lateinit var store: KeyValueStore<String, String>
        private val kernels = mutableMapOf<String, FinancialKernel>()
        private val authorityHash = sha(canonical(json.valueToTree(mapOf("policy" to config["policy"], "genesis" to config["genesis"]))))
        private var faultTriggered = false
        private val resume = java.util.concurrent.Semaphore(0)
        @Volatile private var pausePending = false
        override fun init(context: ProcessorContext<String, String>) {
            this.context = context; store = context.getStateStore("financial-state")
            if (fault == "pause") Thread { System.`in`.bufferedReader().forEachLine { if (it == "resume") resume.release() } }.apply { isDaemon = true; start() }
            // Cold recovery only. Hot processing performs known-key reads/writes from bounded semantic deltas.
            val histories = mutableMapOf<String, MutableList<JsonNode>>()
            store.all().use { rows -> while (rows.hasNext()) {
                val row = rows.next()
                if (row.key.startsWith("h/")) {
                    val wrapper = json.readTree(row.value)
                    histories.getOrPut(wrapper["domain"].asText()) { mutableListOf() }.add(wrapper["record"])
                }
            } }
            histories.forEach { (domain, records) ->
                val ordered = records.sortedBy { it["sequence"].asLong() }
                verifyHistory(ordered)
                kernels[domain] = FinancialKernel.replay(ordered, retainHistory = false)
            }
            val certificate = store.get("cert/0")?.let { json.readTree(it) }
            if (certificate != null) {
                require(certificate["sourceUuid"].asText() == sourceUuid) { "certificate source generation mismatch" }
                val member = membership(certificate["ordinal"].asLong())
                require(member["offset"].asLong() == certificate["offset"].asLong()) { "certificate lost explicit input membership" }
                certificate["owners"].fields().forEachRemaining { (domain, cut) ->
                    val restored = kernels[domain] ?: error("certificate domain history missing")
                    require(restored.historySequence() == cut["sequence"].asLong() && restored.lastRecord()!!["checksum"].asText() == cut["checksum"].asText()) { "mixed-age owner certificate" }
                }
                require(certificate["owners"].size() == kernels.size) { "undeclared restored owner" }
            } else require(kernels.isEmpty()) { "history without partition certificate" }
            val coverage = mutableListOf<JsonNode>()
            val historyWrappers = mutableMapOf<String, JsonNode>()
            store.all().use { rows -> while (rows.hasNext()) {
                val row = rows.next()
                if (row.key.startsWith("c/")) coverage.add(json.readTree(row.value))
                if (row.key.startsWith("h/")) historyWrappers[row.key] = json.readTree(row.value)
            } }
            val heads = mutableMapOf<String, JsonNode>()
            var priorPhysical = -1L; val referencedHistory = mutableSetOf<String>()
            coverage.sortedBy { it["ordinal"].asLong() }.forEachIndexed { index, row ->
                val domain = row["domain"].asText(); val member = membership(index.toLong())
                require(row["ordinal"].asLong() == index.toLong() && row["offset"].asLong() > priorPhysical && row["offset"] == member["offset"] && row["payloadSha256"] == member["payloadSha256"] && row["uuid"].asText() == sourceUuid && row["authoritySha256"].asText() == authorityHash && member["domain"].asText() == domain) { "coverage source membership mismatch" }
                val before = heads[domain] ?: json.valueToTree(mapOf("sequence" to 0L, "checksum" to "GENESIS"))
                require(canonical(before) == canonical(row["headBefore"])) { "coverage owner prefix gap" }
                row["history"].forEach { reference ->
                    val key = "h/$domain/${reference["sequence"].asLong().toString().padStart(20, '0')}"
                    val wrapper = historyWrappers[key] ?: error("coverage history missing")
                    require(wrapper["record"]["checksum"] == reference["checksum"] && wrapper["source"]["ordinal"].asLong() == index.toLong() && wrapper["source"]["offset"] == row["offset"] && wrapper["source"]["uuid"] == row["uuid"] && wrapper["source"]["payloadSha256"] == row["payloadSha256"] && referencedHistory.add(key)) { "history source causation mismatch" }
                }
                heads[domain] = row["headAfter"]; priorPhysical = row["offset"].asLong()
            }
            if (certificate != null) require(coverage.size.toLong() == certificate["ordinal"].asLong() + 1 && priorPhysical == certificate["offset"].asLong() && canonical(json.valueToTree(heads)) == canonical(certificate["owners"]) && certificate["authoritySha256"].asText() == authorityHash && referencedHistory.size == historyWrappers.size) { "uncertified partition/owner coverage" }
            else require(coverage.isEmpty() && historyWrappers.isEmpty()) { "coverage without certified cut" }
            val expectedKeys = mutableMapOf<String, String>()
            histories.forEach { (domain, records) -> records.sortedBy { it["sequence"].asLong() }.forEach { history -> history["changes"].forEach { change ->
                val key = "s/" + json.writeValueAsString(listOf(domain) + change["path"].map { it.asText() })
                if (change["after"].isNull && change["path"].size() > 1) expectedKeys.remove(key) else expectedKeys[key] = canonical(change["after"])
            } } }
            val actualKeys = mutableMapOf<String, String>()
            store.all().use { rows -> while (rows.hasNext()) { val row = rows.next(); if (row.key.startsWith("s/")) actualKeys[row.key] = canonical(json.readTree(row.value)) } }
            require(actualKeys == expectedKeys) { "persistent semantic state/history mismatch" }
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
                    verifier.accept(output)
                    business = verifier.business; settled = verifier.settled
                    if (covered + 1 == limit.toLong()) quietUntil = System.nanoTime() + 1_500_000_000L
                }
                if (covered + 1 == limit.toLong()) finalEnd = consumer.endOffsets(listOf(partition)).getValue(partition)
            }
            finalEnd = consumer.endOffsets(listOf(partition)).getValue(partition)
        }
        val complete = covered + 1 == limit.toLong()
        if (complete) verifier.requirePrefix(limit)
        if (reconstruct) {
            require(complete) { "cannot certify incomplete partition coverage" }
            val cuts = histories.mapValues { (_, rows) -> rows.last()["sequence"].asLong() }
            fun certify(snapshots: Map<String, Long>) {
                require(snapshots.keys == cuts.keys) { "missing domain snapshot" }
                snapshots.forEach { (domain, seq) -> require(seq == cuts.getValue(domain)) { "mixed-age snapshot at declared partition cut domain=$domain snapshot=$seq required=${cuts[domain]}" } }
            }
            certify(cuts)
            val mixed = cuts.toMutableMap()
            val oldest = histories.entries.firstOrNull { it.value.size > 1 } ?: error("reconstruction cohort needs history")
            mixed[oldest.key] = oldest.value[if (oldest.value.size > 2) 1 else 0]["sequence"].asLong()
            require(runCatching { certify(mixed) }.isFailure) { "mixed-age activation was allowed" }
            val domainRecords = oldest.value
            val missing = domainRecords.filterIndexed { index, _ -> index != 1 }
            require(runCatching { verifyHistory(missing) }.isFailure || missing.last()["sequence"].asLong() != cuts.getValue(oldest.key)) { "missing history activation was allowed" }
            emit(mapOf("isolatedReconstruction" to mapOf("inputPartitionOrdinal" to covered, "inputPartitionOffset" to physical, "ownerHistoryCuts" to cuts, "mixedAgeRefused" to true, "missingHistoryRefused" to true, "repairFromCompleteHistory" to true, "committedEndBeforeReconstruction" to finalEnd, "scope" to "read_committed result-only reconstruction; no local or changelog reads")))
        }
        emit(mapOf("result" to mapOf("initialCommittedEndOffset" to initialEnd, "finalCommittedEndOffset" to finalEnd, "resultOffsets" to outputOffsets, "ownerSnapshots" to owners, "scope" to (if (limit == config["inputs"].size()) "full input/phase completion" else "certified prefix including pending phases"), "consecutiveResultOffsets" to outputOffsets.zipWithNext().all { (left, right) -> right == left + 1 }, "coveredInputs" to covered + 1, "expectedInputs" to limit, "businessDecisions" to business, "settlements" to settled, "domains" to owners.size, "readCommittedOracle" to true, "pass" to complete)))
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
                        it.value.input["kind"].asText().uppercase() == "CONTINUE" && it.value.input["payload"]["workId"].asText() == view["dueWork"][0].asText()
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
        private fun actionKey(input: JsonNode) = json.writeValueAsString(listOf(input["namespace"].asText(), input["domain"].asText(), input["actionId"].asText()))
        private fun requestDigest(input: JsonNode): String {
            val numeric = setOf("quantity", "priceNanos", "dueTick", "attempt", "amount", "tick")
            val pairs = input["payload"].fieldNames().asSequence().sorted().map { name ->
                val value = input["payload"][name].asText()
                listOf(name, if (name in numeric) value.toBigInteger().toString() else value)
            }.toList()
            return sha(json.writeValueAsString(listOf(input["kind"].asText().uppercase(), input.get("requestedPolicy")?.takeUnless { it.isNull }?.asText(), pairs)))
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
    private fun properties(broker: String) = Properties().apply { put("bootstrap.servers", broker) }
    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().sorted().joinToString(",", "{", "}") { json.writeValueAsString(it) + ":" + canonical(node[it]) }
        node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
        else -> node.toString()
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun emit(value: Any) { println(json.writeValueAsString(value)); System.out.flush() }
}
