package com.reef.platform.calcify

import com.reef.platform.infrastructure.config.RuntimeEnv
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException

/** Independent opt-in pipeline. No legacy settlement or balance writes. */
object CalcifyPipeline {
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
        val config = Config(stage)
        val input = config.input
        if (stage == "extractor") verifyGeneration(config)
        config.output?.let { ensureOutput(config.bootstrap, input, it) }
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
                    for (record in consumer.poll(Duration.ofMillis(200))) {
                        val partition = TopicPartition(record.topic(), record.partition())
                        if (partition in blocked) continue
                        val links = try {
                            when (stage) {
                                "extractor" -> CalcifySourceBatch.extract(
                                    requireNotNull(record.value()).toString(Charsets.UTF_8),
                                    config.source, config.generation, record.partition(), record.offset()
                                ).map(CalcifyWire::commitment)
                                "verifier" -> {
                                    val passed = CalcifyWire.stubVerify(requireNotNull(record.value()))
                                    require(passed.commitmentId.sourcePartition == record.partition())
                                    listOf(CalcifyWire.passed(passed))
                                }
                                else -> {
                                    val passed = CalcifyWire.readPassed(requireNotNull(record.value()))
                                    require(passed.commitmentId.sourcePartition == record.partition())
                                    CalcifyReceiptStore.record(requireNotNull(receiptConnection), passed)
                                    consumer.commitSync(mapOf(partition to OffsetAndMetadata(record.offset() + 1)))
                                    emptyList()
                                }
                            }
                        } catch (ex: IllegalArgumentException) {
                            blocked.add(partition)
                            consumer.pause(listOf(partition))
                            System.err.println("Calcify stopped " + partition + " at " + record.offset() + ": " + ex.message)
                            continue
                        }
                        if (producer != null) publishAndCheckpoint(producer, consumer, record, config.output!!, links)
                    }
                }
            } finally {
                producer?.close()
                receiptConnection?.close()
            }
        }
    }

    private fun publishAndCheckpoint(
        producer: KafkaProducer<ByteArray, ByteArray>,
        consumer: KafkaConsumer<ByteArray, ByteArray>,
        record: ConsumerRecord<ByteArray, ByteArray>,
        output: String,
        links: List<ByteArray>
    ) {
        producer.beginTransaction()
        try {
            val sends = links.map { producer.send(ProducerRecord(output, record.partition(), null, it)) }
            sends.forEach { it.get() }
            producer.sendOffsetsToTransaction(
                mapOf(TopicPartition(record.topic(), record.partition()) to OffsetAndMetadata(record.offset() + 1)),
                consumer.groupMetadata()
            )
            producer.commitTransaction()
        } catch (ex: Exception) {
            producer.abortTransaction()
            throw ex
        }
    }

    private fun verifyGeneration(config: Config) {
        val topicId = AdminClient.create(Properties().apply {
            put("bootstrap.servers", config.bootstrap)
        }).use { admin ->
            admin.describeTopics(listOf(config.source)).allTopicNames().get()
                .getValue(config.source).topicId().toString()
        }
        require(topicId != Uuid.ZERO_UUID.toString()) {
            "broker did not provide source topic ID"
        }
        connection(config).use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement(
                    "SELECT source_topic, source_topic_id FROM runtime.calcify_source_generations " +
                        "WHERE source_generation = ? FOR UPDATE"
                ).use {
                    it.setInt(1, config.generation)
                    it.executeQuery().use { rows ->
                        require(rows.next() && rows.getString(1) == config.source) {
                            "unregistered Calcify source generation"
                        }
                        val registeredId = rows.getString(2)
                        if (registeredId == null) {
                            db.prepareStatement(
                                "UPDATE runtime.calcify_source_generations SET source_topic_id = ? " +
                                    "WHERE source_generation = ?"
                            ).use { update ->
                                update.setString(1, topicId)
                                update.setInt(2, config.generation)
                                update.executeUpdate()
                            }
                        } else {
                            require(registeredId == topicId) {
                                "source topic recreated: register next Calcify source generation"
                            }
                        }
                    }
                }
                db.commit()
            } catch (ex: Exception) {
                db.rollback()
                throw ex
            }
        }
    }

    private fun connection(config: Config) =
        DriverManager.getConnection(config.jdbc, config.dbUser, config.dbPassword)

    private fun ensureOutput(bootstrap: String, input: String, output: String) {
        AdminClient.create(Properties().apply { put("bootstrap.servers", bootstrap) }).use { admin ->
            val count = admin.describeTopics(listOf(input)).allTopicNames().get().getValue(input).partitions().size
            try {
                admin.createTopics(listOf(NewTopic(output, count, 1.toShort()))).all().get()
            } catch (ex: ExecutionException) {
                if (ex.cause !is TopicExistsException) throw ex
            }
            require(admin.describeTopics(listOf(output)).allTopicNames().get().getValue(output).partitions().size == count) {
                "Calcify topic partition count mismatch"
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
