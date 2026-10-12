package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Properties
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import reef.contracts.calcify.v1.*

/**
 * S2-B1: invalid-schedule differential harness. For reordered, skipped (missing-member), corrupted, duplicated and over-budget
 * (closure burst) schedules the keyed reducer must AGREE with the frozen reducer on every step: both succeed with byte-equal
 * envelopes, or both refuse; a refusal leaves both states untouched so the schedule can continue and still agree.
 */
class IncrementalLifecycleDifferentialHarnessTest {
    private val binding = FiniteLifecycleFixtures.binding()
    private val bodies = FiniteLifecycleFixtures.bodies

    private data class Step(val payload: ByteArray, val offset: Long, val resume: Long, val label: String)

    private class Lockstep(val binding: FiniteLifecycleBinding) {
        var frozen = FiniteLifecycleContract.genesis(binding)
        val kv = MemoryLifecycleKv().also { it.apply(IncrementalLifecycleReducer.genesis(binding)) }
        var refused = 0; var accepted = 0
        fun step(s: Step, tag: String) {
            val before = kv.entries.mapValues { it.value.toList() }
            val f = runCatching { FiniteLifecycleReducer.reduce(frozen, s.payload, s.offset, s.resume, binding) }
            val i = runCatching { IncrementalLifecycleReducer.reduce(kv, s.payload, s.offset, s.resume, binding) }
            assertEquals(f.isSuccess, i.isSuccess, "$tag ${s.label}: frozen=${f.exceptionOrNull()?.message} incremental=${i.exceptionOrNull()?.message}")
            if (f.isFailure) {
                assertTrue(f.exceptionOrNull() is IllegalArgumentException || f.exceptionOrNull() is ArithmeticException, "$tag unexpected failure type ${f.exceptionOrNull()}")
                assertEquals(before, kv.entries.mapValues { it.value.toList() }, "$tag ${s.label}: a refusal must not write")
                refused++; return
            }
            frozen = f.getOrThrow().state; kv.apply(i.getOrThrow().writes); accepted++
            assertEquals(f.getOrThrow().envelope?.toByteArray()?.toList(), i.getOrThrow().envelope?.toByteArray()?.toList(), "$tag ${s.label} envelope bytes")
        }
        fun assertSameState(tag: String) {
            val meta = IncrementalLifecycleReducer.Meta.parse(kv.get("meta")!!)
            assertEquals(frozen.resumeOffset, meta.base.resumeOffset, "$tag resume")
            assertEquals(frozen.completedRecordsCount, meta.completed, "$tag completed")
            assertEquals(frozen.ordersCount, meta.orders, "$tag orders")
            assertEquals(frozen.sourceBytes, meta.base.sourceBytes, "$tag source bytes")
        }
    }

    private fun mutate(index: Int, rnd: Random): ByteArray {
        val body = bodies[index]
        return when (rnd.nextInt(7)) {
            0 -> body.copyOf().also { it[rnd.nextInt(it.size)] = (it[rnd.nextInt(it.size)].toInt() xor 0x5a).toByte() }       // byte corruption
            1 -> body.copyOf(body.size - 1 - rnd.nextInt(20))                                                                   // truncation
            2 -> FiniteLifecycleFixtures.change(body) { it.put("shardId", "changed") }                                           // changed batch content
            3 -> FiniteLifecycleFixtures.change(body) { it.put("batchId", "burst-${rnd.nextInt(6)}") }                          // new batch id, same commands
            4 -> FiniteLifecycleFixtures.change(body) { FiniteLifecycleFixtures.outcome(it).put("orderId", "p0-${1 + rnd.nextInt(8)}") }
            5 -> FiniteLifecycleFixtures.change(body) { FiniteLifecycleFixtures.outcome(it).put("status", "rejected") }
            else -> FiniteLifecycleFixtures.change(body) { it.put("runId", "other") }                                            // scope violation
        }
    }

    private fun schedule(seed: Long): List<Step> {
        val rnd = Random(seed); val steps = ArrayList<Step>(); var offset = 0L
        val order = bodies.indices.toMutableList()
        when (rnd.nextInt(4)) {
            0 -> if (order.size > 3) { val a = 1 + rnd.nextInt(order.size - 2); order[a] = order[a + 1].also { order[a + 1] = order[a] } }   // reorder
            1 -> order.removeAt(rnd.nextInt(order.size))                                                                                      // missing member
            else -> {}
        }
        for (b in order) {
            val corrupt = rnd.nextInt(5) == 0
            val payload = if (corrupt) mutate(b, rnd) else bodies[b]
            steps += Step(payload, offset, offset + 1, if (corrupt) "corrupt($b)" else "body($b)")
            if (rnd.nextInt(6) == 0) steps += Step(payload, offset, offset + 1 + rnd.nextInt(2), "duplicate-or-changed-resume($b)")   // identical or changed redelivery
            if (rnd.nextInt(8) == 0 && offset > 0) steps += Step(bodies[b], rnd.nextInt(offset.toInt() + 1).toLong(), offset + 1, "regressed-offset($b)")
            offset += 1 + rnd.nextInt(2)
        }
        return steps
    }

    @Test fun twoThousandInvalidAndMixedSchedulesAgreeStepByStep() {
        var refused = 0; var accepted = 0
        for (seed in 1L..2_000L) {
            val l = Lockstep(binding)
            schedule(seed).forEach { l.step(it, "seed=$seed") }
            l.assertSameState("seed=$seed"); refused += l.refused; accepted += l.accepted
        }
        assertTrue(refused > 1_000 && accepted > 10_000, "the harness must exercise both outcomes: refused=$refused accepted=$accepted")
    }

    @Test fun closureBurstOverTheBudgetIsRefusedIdentically() {
        val l = Lockstep(binding)
        var offset = 0L
        repeat(bodies.size) { l.step(Step(bodies[it], offset, offset + 1, "body($it)"), "burst"); offset++ }
        repeat(8) { l.step(Step(bodies[it % 3], offset, offset + 1, "replay($it)"), "burst"); offset++ }     // 12 + 8 > 16 publications
        assertTrue(l.refused >= 4, "the publication budget must refuse the tail: ${l.refused}")
        l.assertSameState("burst")
    }

    @Test fun invalidRestoredHistoryIsRefusedByTheOncePerRestoreVerification() {
        val l = Lockstep(binding)
        var offset = 0L
        bodies.forEach { l.step(Step(it, offset, offset + 1, "body"), "restore"); offset++ }
        // Honest keyed history verifies and equals the frozen state.
        val assembled = IncrementalLifecycleReducer.verifyHistory(l.kv, binding)
        assertEquals(l.frozen.ordersList, assembled.ordersList); assertEquals(l.frozen.completedRecordsList, assembled.completedRecordsList)
        fun tampered(edit: (MemoryLifecycleKv) -> Unit): MemoryLifecycleKv =
            MemoryLifecycleKv().also { copy -> copy.entries.putAll(l.kv.entries.mapValues { it.value.copyOf() }); edit(copy) }
        val order = l.kv.entries.keys.first { it.startsWith("order/") }
        val exec = l.kv.entries.keys.first { it.startsWith("exec/") }
        val batch = l.kv.entries.keys.first { it.startsWith("batch/") }
        val done = l.kv.entries.keys.first { it.startsWith("done/") }
        val cases: Map<String, MemoryLifecycleKv> = mapOf(
            "order filled changed" to tampered { it.entries[order] = FiniteLifecycleOrderStateV1.parseFrom(it.entries[order]).toBuilder().setFilledUnits("9").build().toByteArray() },
            "execution id dropped" to tampered { it.entries.remove(exec) },
            "batch dropped" to tampered { it.entries.remove(batch) },
            "completed record dropped" to tampered { it.entries.remove(done) },
            "completed record altered" to tampered { it.entries[done] = FiniteLifecycleCaptureV1.parseFrom(it.entries[done]).toBuilder().setSourceEncodedBytes(1).build().toByteArray() },
            "meta counts changed" to tampered { kv -> kv.entries["meta"] = IncrementalLifecycleReducer.Meta.parse(kv.entries["meta"]!!).copy(orders = 1).bytes() },
            "order dropped" to tampered { it.entries.remove(order) },
        )
        for ((name, kv) in cases) assertFailsWith<IllegalArgumentException>(name) { IncrementalLifecycleReducer.verifyHistory(kv, binding) }
    }

    /** The Kafka Streams adapter: the keyed reducer inside a real topology over the managed key-value store. */
    private class KeyedCaptureProcessor(val binding: FiniteLifecycleBinding) : Processor<ByteArray, ByteArray, ByteArray, ByteArray> {
        lateinit var ctx: ProcessorContext<ByteArray, ByteArray>
        lateinit var kv: StreamsLifecycleKv
        override fun init(context: ProcessorContext<ByteArray, ByteArray>) {
            ctx = context
            val store = context.getStateStore<KeyValueStore<String, ByteArray>>("keyed-lifecycle")
            kv = StreamsLifecycleKv(store)
            if (store.get("meta") == null) kv.apply(IncrementalLifecycleReducer.genesis(binding))
        }
        override fun process(record: Record<ByteArray, ByteArray>) {
            val md = ctx.recordMetadata().orElseThrow()
            val result = IncrementalLifecycleReducer.reduce(kv, record.value(), md.offset(), md.offset() + 1, binding)
            kv.apply(result.writes)
            result.envelope?.let { ctx.forward(Record(null, it.toByteArray(), record.timestamp())) }
        }
    }

    @Test fun streamsStoreAdapterReproducesTheFrozenEnvelopesInsideATopology() {
        val topology = Topology().apply {
            addSource("src", ByteArrayDeserializer(), ByteArrayDeserializer(), binding.sourceTopic)
            addProcessor("keyed", { KeyedCaptureProcessor(binding) }, "src")
            addStateStore(Stores.keyValueStoreBuilder(Stores.inMemoryKeyValueStore("keyed-lifecycle"), Serdes.String(), Serdes.ByteArray()).withCachingDisabled(), "keyed")
            addSink("out", binding.captureTopic, ByteArraySerializer(), ByteArraySerializer(), "keyed")
        }
        val props = Properties().apply { put(StreamsConfig.APPLICATION_ID_CONFIG, "keyed-reducer"); put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234") }
        TopologyTestDriver(topology, props).use { driver ->
            val input = driver.createInputTopic(binding.sourceTopic, org.apache.kafka.common.serialization.ByteArraySerializer(), ByteArraySerializer())
            bodies.forEach { input.pipeInput(null, it) }
            val out = driver.createOutputTopic(binding.captureTopic, ByteArrayDeserializer(), ByteArrayDeserializer()).readValuesToList()
            val frozen = FiniteLifecycleFixtures.sequence()
            assertEquals(frozen.completedRecordsList.map { it.toByteArray().toList() }, out.map { it.toList() })
            val store = driver.getKeyValueStore<String, ByteArray>("keyed-lifecycle")
            IncrementalLifecycleReducer.verifyHistory(StreamsLifecycleKv(store), binding).also { assertEquals(frozen.ordersList, it.ordersList) }
        }
    }
}
