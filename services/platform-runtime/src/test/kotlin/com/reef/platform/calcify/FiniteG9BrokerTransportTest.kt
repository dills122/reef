package com.reef.platform.calcify

import java.nio.file.Files
import java.time.Duration
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * G9 transport qualification against a real broker. Opt-in: set FINITE_G9_BOOTSTRAP=host:port of a disposable,
 * unauthenticated broker. Exercises the production managed store and [FiniteLifecycleCaptureRuntime.liveProperties]
 * with a state value of exactly the declared cap (synthetic bytes, never typed authority): producer -> changelog
 * -> fetch -> local-state loss -> restore. Typed-state semantics are covered by the model tests.
 */
class FiniteG9BrokerTransportTest {
    private val binding = FiniteLifecycleFixtures.binding().let { base ->
        val id = "g9-" + System.nanoTime()
        base.copy(sourceTopic = "$id-source", captureTopic = "$id-capture", applicationId = "$id-app")
    }
    private val stateBytes = ByteArray(Math.toIntExact(binding.budget.stateBytes)) { (it % 251).toByte() }

    private fun topology(restored: AtomicReference<ByteArray>, written: AtomicReference<ByteArray>): Topology = Topology().apply {
        addSource("finite-source", ByteArrayDeserializer(), ByteArrayDeserializer(), binding.sourceTopic)
        addProcessor("capture", {
            object : Processor<ByteArray, ByteArray, ByteArray, ByteArray> {
                private lateinit var store: KeyValueStore<String, ByteArray>
                override fun init(context: ProcessorContext<ByteArray, ByteArray>) {
                    store = context.getStateStore(FiniteLifecycleCaptureProcessor.STORE)
                    store.get("state")?.let(restored::set)
                }
                override fun process(record: Record<ByteArray, ByteArray>) {
                    store.put("state", stateBytes); written.set(stateBytes)
                }
            }
        }, "finite-source")
        addStateStore(FiniteLifecycleCaptureProcessor.managedStoreBuilder(), "capture")
        addSink("finite-capture", binding.captureTopic, ByteArraySerializer(), ByteArraySerializer(),
            org.apache.kafka.streams.processor.StreamPartitioner<ByteArray, ByteArray> { _, _, _, _ -> java.util.Optional.of(setOf(binding.partition)) }, "capture")
    }

    private fun streams(bootstrap: String, restored: AtomicReference<ByteArray>, written: AtomicReference<ByteArray>): KafkaStreams {
        val authenticated = Properties().apply { put("bootstrap.servers", bootstrap) }
        val stateDir = Files.createTempDirectory("g9-state-").toString()
        return KafkaStreams(topology(restored, written), FiniteLifecycleCaptureRuntime.liveProperties(binding, authenticated, stateDir))
    }

    private fun await(what: String, seconds: Long = 90, done: () -> Boolean) {
        val end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!done()) { check(System.nanoTime() < end) { "timed out: $what" }; Thread.sleep(50) }
    }

    @Test fun fullDeclaredStateTransportsThroughChangelogAndRestoresAfterLocalStateLoss() {
        val bootstrap = System.getenv("FINITE_G9_BOOTSTRAP"); assumeTrue(bootstrap != null, "FINITE_G9_BOOTSTRAP not set")
        assertEquals(6307840, stateBytes.size)
        Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
            admin.createTopics(listOf(NewTopic(binding.sourceTopic, 1, 1.toShort()), NewTopic(binding.captureTopic, 1, 1.toShort()))).all().get()
            // Production consumers use auto.offset.reset=none: seed the group at the registered genesis offset.
            admin.alterConsumerGroupOffsets(binding.applicationId, mapOf(TopicPartition(binding.sourceTopic, 0) to OffsetAndMetadata(0))).all().get()
        }
        val written = AtomicReference<ByteArray>()
        streams(bootstrap, AtomicReference(), written).use { first ->
            first.start(); await("RUNNING") { first.state() == KafkaStreams.State.RUNNING }
            KafkaProducer<ByteArray, ByteArray>(mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java.name,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java.name)).use { it.send(ProducerRecord(binding.sourceTopic, 0, null, "g9".toByteArray())).get() }
            await("full state written to managed store") { written.get() != null }
            assertEquals(KafkaStreams.State.RUNNING, first.state())
            first.close(Duration.ofSeconds(30))
        }
        // The committed changelog must carry the whole value as one record, readable with the production fetch ceiling.
        val changelog = "${binding.applicationId}-${FiniteLifecycleCaptureProcessor.STORE}-changelog"
        val transport = Math.toIntExact(binding.budget.stateBytes + 1024)
        KafkaConsumer<ByteArray, ByteArray>(mapOf(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to "g9-verify", ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest", ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to transport,
            ConsumerConfig.FETCH_MAX_BYTES_CONFIG to transport,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java.name)).use { consumer ->
            consumer.assign(listOf(TopicPartition(changelog, 0)))
            var best = 0
            val end = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (best < stateBytes.size && System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach { best = maxOf(best, it.value()?.size ?: 0) }
            }
            assertEquals(stateBytes.size, best, "changelog must hold one record of exactly the declared state cap")
        }
        // Fresh local state directory: the only source of the value is changelog restore.
        val restored = AtomicReference<ByteArray>()
        streams(bootstrap, restored, AtomicReference()).use { second ->
            second.start(); await("restore RUNNING") { second.state() == KafkaStreams.State.RUNNING }
            await("restored bytes visible to processor init") { restored.get() != null }
            assertContentEquals(stateBytes, restored.get())
            assertTrue(second.state() == KafkaStreams.State.RUNNING)
            second.close(Duration.ofSeconds(30))
        }
    }
}
