package com.reef.platform.calcify

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import reef.contracts.calcify.v1.*

/** S1-D2 differential: the keyed reducer against the frozen whole-state reducer on seeded valid schedules. */
class IncrementalLifecycleReducerTest {
    private val binding = FiniteLifecycleFixtures.binding()
    private val bodies = FiniteLifecycleFixtures.bodies

    private sealed interface Step { val offset: Long; val body: Int }
    private data class Fresh(override val offset: Long, override val body: Int) : Step
    private data class Replay(override val offset: Long, override val body: Int) : Step      // an earlier batch re-delivered at a new offset
    private data class Duplicate(override val offset: Long, override val body: Int) : Step   // the same physical record delivered again

    private fun schedule(seed: Long): List<Step> {
        val rnd = Random(seed)
        val steps = ArrayList<Step>(); var offset = rnd.nextInt(3).toLong(); var replays = 0
        for (i in bodies.indices) {
            steps += Fresh(offset, i)
            if (rnd.nextInt(4) == 0) steps += Duplicate(offset, i)                          // redelivery of the very same record
            offset += 1 + rnd.nextInt(3)
            if (replays < 4 && i >= 1 && rnd.nextInt(3) == 0) { steps += Replay(offset, rnd.nextInt(i + 1)); replays++; offset += 1 + rnd.nextInt(3) }
        }
        return steps
    }

    private fun run(seed: Long) {
        var frozen = FiniteLifecycleContract.genesis(binding)
        val kv = MemoryLifecycleKv(); kv.apply(IncrementalLifecycleReducer.genesis(binding))
        for ((n, step) in schedule(seed).withIndex()) {
            val payload = bodies[step.body]; val resume = step.offset + 1
            val f = FiniteLifecycleReducer.reduce(frozen, payload, step.offset, resume, binding)
            val i = IncrementalLifecycleReducer.reduce(kv, payload, step.offset, resume, binding)
            frozen = f.state; kv.apply(i.writes)
            assertEquals(f.envelope?.toByteArray()?.toList(), i.envelope?.toByteArray()?.toList(), "seed=$seed step=$n $step envelope bytes")
        }
        // Entity content equals the frozen state.
        val meta = IncrementalLifecycleReducer.Meta.parse(kv.get("meta")!!)
        assertEquals(frozen.ordersCount, meta.orders); assertEquals(frozen.executionIdsCount, meta.executions)
        assertEquals(frozen.batchesCount, meta.batches); assertEquals(frozen.completedRecordsCount, meta.completed)
        assertEquals(frozen.resumeOffset, meta.base.resumeOffset); assertEquals(frozen.completedFrontier, meta.base.completedFrontier)
        assertEquals(frozen.sourceBytes, meta.base.sourceBytes); assertEquals(frozen.captureBytes, meta.base.captureBytes)
        for (o in frozen.ordersList) assertEquals(o, FiniteLifecycleOrderStateV1.parseFrom(kv.get("order/" + o.acceptance.fact.orderId)), "seed=$seed order")
        for (e in frozen.executionIdsList) assertTrue(kv.get("exec/$e") != null, "seed=$seed execution $e")
        for (b in frozen.batchesList) assertEquals(b, FiniteLifecycleBatchStateV1.parseFrom(kv.get("batch/" + b.batchId)), "seed=$seed batch")
        for (r in frozen.completedRecordsList) assertEquals(r, FiniteLifecycleCaptureV1.parseFrom(kv.get(IncrementalLifecycleReducer.doneKey(r.source.sourceOffset))), "seed=$seed completed")
        val expectedKeys = buildSet {
            add("meta")
            frozen.ordersList.forEach { add("order/" + it.acceptance.fact.orderId) }
            frozen.executionIdsList.forEach { add("exec/$it") }
            frozen.batchesList.forEach { add("batch/" + it.batchId) }
            frozen.completedRecordsList.forEach { add(IncrementalLifecycleReducer.doneKey(it.source.sourceOffset)) }
        }
        assertEquals(expectedKeys, kv.entries.keys, "seed=$seed unexpected or missing KV keys")
    }

    @Test fun twoThousandSeededValidSchedulesMatchTheFrozenReducer() { for (seed in 1L..2_000L) run(seed) }

    @Test fun theGeneratorExercisesReplaysDuplicatesAndOffsetGaps() {
        val all = (1L..2_000L).flatMap { schedule(it) }
        assertTrue(all.count { it is Replay } > 1_000 && all.count { it is Duplicate } > 1_000, "replays and duplicates occur")
        val gaps = (1L..2_000L).map { seed -> schedule(seed).filterIsInstance<Fresh>().zipWithNext { a, b -> b.offset - a.offset }.toSet() }.flatten().toSet()
        assertTrue(gaps.size >= 3, "offset gaps vary: $gaps")
        assertTrue((1L..2_000L).map { schedule(it) }.toSet().size > 500, "schedules are diverse")
    }

    @Test fun failedReductionReturnsNoWritesAndLeavesTheKvUntouched() {
        val kv = MemoryLifecycleKv(); kv.apply(IncrementalLifecycleReducer.genesis(binding))
        val first = IncrementalLifecycleReducer.reduce(kv, bodies[0], 0, 1, binding); kv.apply(first.writes)
        val before = kv.entries.mapValues { it.value.toList() }
        assertFailsWith<IllegalArgumentException> { IncrementalLifecycleReducer.reduce(kv, bodies[1], 0, 1, binding) }          // regressed/changed position
        assertFailsWith<IllegalArgumentException> { IncrementalLifecycleReducer.reduce(kv, bodies[0], 0, 5, binding) }          // changed resume for a done record
        assertFailsWith<IllegalArgumentException> { IncrementalLifecycleReducer.reduce(kv, bodies[2].copyOf().also { it[10] = (it[10] + 1).toByte() }, 1, 2, binding) }
        assertEquals(before, kv.entries.mapValues { it.value.toList() })
        assertNull(IncrementalLifecycleReducer.reduce(kv, bodies[0], 0, 1, binding).envelope)                                    // identical physical redelivery
    }

    @Test fun workPerRecordIsIndependentOfHistoryLength() {
        // The keyed reducer reads only touched entities; count the reads for the last record against the first.
        class Counting(val inner: MemoryLifecycleKv) : LifecycleKv { var reads = 0; override fun get(key: String): ByteArray? { reads++; return inner.get(key) }; override fun scan(prefix: String) = inner.scan(prefix) }
        val kv = MemoryLifecycleKv(); kv.apply(IncrementalLifecycleReducer.genesis(binding))
        var firstReads = 0; var lastReads = 0
        bodies.forEachIndexed { i, body ->
            val counting = Counting(kv)
            val r = IncrementalLifecycleReducer.reduce(counting, body, i.toLong(), i + 1L, binding); kv.apply(r.writes)
            if (i == 1) firstReads = counting.reads; if (i == bodies.lastIndex) lastReads = counting.reads
        }
        assertTrue(lastReads <= firstReads + 8, "reads should not grow with history: first=$firstReads last=$lastReads")
    }
}
