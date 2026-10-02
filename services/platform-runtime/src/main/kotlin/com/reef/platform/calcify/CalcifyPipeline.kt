package com.reef.platform.calcify

import com.reef.platform.infrastructure.config.RuntimeEnv
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException

/** Independent opt-in pipeline. No legacy settlement or balance writes. */
object CalcifyPipeline {
    internal data class VerifierPollPlan(
        val outputs: List<ProducerRecord<ByteArray, ByteArray>>,
        val offsets: Map<TopicPartition, OffsetAndMetadata>,
        val poisoned: List<Triple<TopicPartition, Long, String?>>,
    )

    internal data class ReceiptPollPlan(
        val valid: Map<TopicPartition, List<Pair<Long, CommitmentVerificationPassed>>>,
        val poisoned: List<Triple<TopicPartition, Long, String?>>,
    )

    private data class Config(val stage: String) {
        val bootstrap = RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        val source = RuntimeEnv.string("CALCIFY_SOURCE_TOPIC", "REEF_VENUE_EVENTS")
        val commitments = RuntimeEnv.string("CALCIFY_COMMITMENT_TOPIC", "REEF_MATCH_COMMITMENTS_V1")
        val verified = RuntimeEnv.string("CALCIFY_VERIFIED_TOPIC", "REEF_VERIFIED_COMMITMENTS_V1")
        val generation = RuntimeEnv.int("CALCIFY_SOURCE_GENERATION", 1, min = 1)
        val instance = RuntimeEnv.string("CALCIFY_INSTANCE_ID", System.getenv("HOSTNAME") ?: "local")
        val reset = RuntimeEnv.string("CALCIFY_AUTO_OFFSET_RESET", "earliest").also {
            require(it == "earliest" || it == "latest")
        }
        val jdbc = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/reef?currentSchema=runtime")
        val dbUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
        val dbPassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
        val input: String get() = when (stage) {
            "extractor" -> source
            "verifier" -> commitments
            "receipt" -> verified
            else -> error("Unsupported CALCIFY_STAGE: " + stage)
        }
        val output: String? get() = when (stage) {
            "extractor" -> commitments
            "verifier" -> verified
            else -> null
        }
    }

    fun run(stage: String) {
        if (stage == "resolver") { CalcifyResolverRuntime.run(); return }
        val config = Config(stage)
        val input = config.input
        if (stage == "extractor") verifyGeneration(config)
        ensureTopics(config)
        consumer(config).use { consumer ->
            val blocked = mutableSetOf<TopicPartition>()
            consumer.subscribe(listOf(input), object : ConsumerRebalanceListener {
                override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
                    blocked.removeAll(partitions.toSet())
                }
                override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
                    if (stage == "extractor") verifyGeneration(config)
                    consumer.pause(partitions.filter { it in blocked })
                }
            })
            val producer = config.output?.let { producer(config) }
            val receiptConnection = if (stage == "receipt") connection(config) else null
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val polled = consumer.poll(Duration.ofMillis(200))
                    if (stage == "verifier") {
                        val plan = planVerifierPoll(polled, config.output!!, blocked)
                        if (plan.poisoned.isNotEmpty()) {
                            consumer.pause(plan.poisoned.map { it.first })
                            plan.poisoned.forEach { (partition, offset, message) ->
                                System.err.println("Calcify stopped $partition at $offset: $message")
                            }
                        }
                        if (plan.offsets.isNotEmpty()) {
                            publishAndCheckpoint(requireNotNull(producer), consumer, plan.outputs, plan.offsets)
                        }
                        continue
                    }
                    if (stage == "receipt") {
                        val plan = planReceiptPoll(polled, blocked)
                        if (plan.poisoned.isNotEmpty()) {
                            consumer.pause(plan.poisoned.map { it.first })
                            plan.poisoned.forEach { (partition, offset, message) ->
                                System.err.println("Calcify stopped $partition at $offset: $message")
                            }
                        }
                        for ((partition, entries) in plan.valid) {
                            try {
                                CalcifyReceiptStore.recordBatch(requireNotNull(receiptConnection), entries.map { it.second })
                                consumer.commitSync(mapOf(partition to OffsetAndMetadata(entries.last().first + 1)))
                            } catch (ex: IllegalArgumentException) {
                                // Replay valid prefix one record at a time to isolate a conflicting receipt.
                                for ((offset, passed) in entries) {
                                    try {
                                        CalcifyReceiptStore.record(requireNotNull(receiptConnection), passed)
                                        consumer.commitSync(mapOf(partition to OffsetAndMetadata(offset + 1)))
                                    } catch (conflict: IllegalArgumentException) {
                                        blocked.add(partition)
                                        consumer.pause(listOf(partition))
                                        System.err.println("Calcify stopped $partition at $offset: ${conflict.message}")
                                        break
                                    }
                                }
                            }
                        }
                        continue
                    }
                    for (record in polled) {
                        val partition = TopicPartition(record.topic(), record.partition())
                        if (partition in blocked) continue
                        val links = try {
                            when (stage) {
                                "extractor" -> CalcifySourceBatch.extract(
                                    requireNotNull(record.value()).toString(Charsets.UTF_8),
                                    config.source, config.generation, record.partition(), record.offset()
                                ).map(CalcifyWire::commitment)
                                else -> error("Unsupported CALCIFY_STAGE: $stage")
                            }
                        } catch (ex: IllegalArgumentException) {
                            blocked.add(partition)
                            consumer.pause(listOf(partition))
                            System.err.println("Calcify stopped " + partition + " at " + record.offset() + ": " + ex.message)
                            continue
                        }
                        if (producer != null) publishAndCheckpoint(
                            producer, consumer,
                            links.map { ProducerRecord(config.output!!, record.partition(), null, it) },
                            mapOf(partition to OffsetAndMetadata(record.offset() + 1))
                        )
                    }
                }
            } finally {
                producer?.close()
                receiptConnection?.close()
            }
        }
    }

    internal fun planVerifierPoll(
        records: Iterable<ConsumerRecord<ByteArray, ByteArray>>,
        output: String,
        blocked: MutableSet<TopicPartition>,
    ): VerifierPollPlan {
        val outputs = ArrayList<ProducerRecord<ByteArray, ByteArray>>()
        val offsets = linkedMapOf<TopicPartition, OffsetAndMetadata>()
        val poisoned = ArrayList<Triple<TopicPartition, Long, String?>>()
        for (record in records) {
            val partition = TopicPartition(record.topic(), record.partition())
            if (partition in blocked) continue
            try {
                val passed = CalcifyWire.stubVerify(requireNotNull(record.value()))
                require(passed.commitmentId.sourcePartition == record.partition())
                outputs.add(ProducerRecord(output, record.partition(), null, CalcifyWire.passed(passed)))
                offsets[partition] = OffsetAndMetadata(record.offset() + 1)
            } catch (ex: IllegalArgumentException) {
                blocked.add(partition)
                poisoned.add(Triple(partition, record.offset(), ex.message))
            }
        }
        return VerifierPollPlan(outputs, offsets, poisoned)
    }

    internal fun planReceiptPoll(
        records: Iterable<ConsumerRecord<ByteArray, ByteArray>>,
        blocked: MutableSet<TopicPartition>,
    ): ReceiptPollPlan {
        val valid = linkedMapOf<TopicPartition, MutableList<Pair<Long, CommitmentVerificationPassed>>>()
        val poisoned = ArrayList<Triple<TopicPartition, Long, String?>>()
        for (record in records) {
            val partition = TopicPartition(record.topic(), record.partition())
            if (partition in blocked) continue
            try {
                val passed = CalcifyWire.readPassed(requireNotNull(record.value()))
                require(passed.commitmentId.sourcePartition == record.partition())
                valid.getOrPut(partition) { ArrayList() }.add(record.offset() to passed)
            } catch (ex: IllegalArgumentException) {
                blocked.add(partition)
                poisoned.add(Triple(partition, record.offset(), ex.message))
            }
        }
        return ReceiptPollPlan(valid, poisoned)
    }

    private fun publishAndCheckpoint(
        producer: KafkaProducer<ByteArray, ByteArray>,
        consumer: KafkaConsumer<ByteArray, ByteArray>,
        outputs: List<ProducerRecord<ByteArray, ByteArray>>,
        offsets: Map<TopicPartition, OffsetAndMetadata>,
    ) {
        producer.beginTransaction()
        try {
            val sends = outputs.map { producer.send(it) }
            sends.forEach { it.get() }
            producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata())
            producer.commitTransaction()
        } catch (ex: Exception) {
            producer.abortTransaction()
            throw ex
        }
    }

    private fun verifyGeneration(config: Config) {
        CalcifySourceRegistration.verify(config.bootstrap, config.source, config.generation, config.jdbc, config.dbUser, config.dbPassword)
    }

    private fun connection(config: Config) =
        DriverManager.getConnection(config.jdbc, config.dbUser, config.dbPassword)

    private fun ensureTopics(config: Config) {
        AdminClient.create(Properties().apply { put("bootstrap.servers", config.bootstrap) }).use { admin ->
            require(config.source in admin.listTopics().names().get()) { "Calcify source topic is absent" }
            val count = admin.describeTopics(listOf(config.source)).allTopicNames().get()
                .getValue(config.source).partitions().size
            for (topic in listOf(config.commitments, config.verified)) {
                try {
                    admin.createTopics(listOf(NewTopic(topic, count, 1.toShort()))).all().get()
                } catch (ex: ExecutionException) {
                    if (ex.cause !is TopicExistsException) throw ex
                }
                require(admin.describeTopics(listOf(topic)).allTopicNames().get().getValue(topic).partitions().size == count) {
                    "Calcify topic partition count mismatch: " + topic
                }
            }
        }
    }

    private fun consumer(config: Config): KafkaConsumer<ByteArray, ByteArray> =
        KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrap)
            put(ConsumerConfig.GROUP_ID_CONFIG, "reef-calcify-" + config.stage + "-v1" +
                if (config.stage == "extractor") "-g" + config.generation else "")
            put(ConsumerConfig.CLIENT_ID_CONFIG, "reef-calcify-" + config.stage + "-" + config.instance)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.reset)
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100)
        })

    private fun producer(config: Config): KafkaProducer<ByteArray, ByteArray> =
        KafkaProducer<ByteArray, ByteArray>(Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrap)
            put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "reef-calcify-" + config.stage + "-" + config.instance)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        }).also { it.initTransactions() }
}
