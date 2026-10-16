package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityFilterTest {
    private fun key(prefix: String, i: Int): ByteArray = Canon.bytes { str("run"); str("session"); str("I${i % 8}"); str("$prefix$i") }

    @Test fun neverForgetsAnAddedKeyAcrossGrowth() {
        val f = EntityFilter(initialKeys = 1_000)
        val n = 400_000
        for (i in 0 until n) { f.add('X', key("e", i)); if (i % 10_007 == 0) for (j in 0..i step 997) assertTrue(f.mightContain('X', key("e", j)), "lost key $j after $i adds") }
        for (i in 0 until n) assertTrue(f.mightContain('X', key("e", i)), "lost key $i")
        assertEquals(n.toLong(), f.count)
    }

    @Test fun theTagIsPartOfTheKey() {
        val f = EntityFilter(initialKeys = 1_000)
        for (i in 0 until 50_000) f.add('X', key("e", i))
        var hits = 0
        for (i in 0 until 50_000) if (f.mightContain('K', key("e", i))) hits++
        assertTrue(hits < 50_000 * 0.03, "a key added as X answered as K ${hits} times of 50000")
    }

    @Test fun falsePositiveRateStaysNearOnePercentAsItGrows() {
        val f = EntityFilter(initialKeys = 1 shl 16)
        val n = 3_000_000
        for (i in 0 until n) f.add('X', key("e", i))
        var fp = 0; val probes = 2_000_000
        for (i in 0 until probes) if (f.mightContain('X', key("absent", i))) fp++
        val rate = fp.toDouble() / probes
        println("FILTER layers=${f.layers} bytes/key=${"%.2f".format(f.bytes.toDouble() / n)} false-positive rate=${"%.3f".format(rate * 100)}%")
        assertTrue(rate < 0.015, "false positive rate $rate")
        assertTrue(f.bytes.toDouble() / n < 3.5, "bytes per key ${f.bytes.toDouble() / n}")
    }

    @Test fun anEmptyFilterContainsNothing() {
        val f = EntityFilter()
        for (i in 0 until 10_000) assertFalse(f.mightContain('X', key("e", i)))
    }

    private fun roundTrip(f: EntityFilter): EntityFilter {
        val bytes = java.io.ByteArrayOutputStream().also { f.writeTo(java.io.DataOutputStream(it)) }.toByteArray()
        return EntityFilter.readFrom(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)))
    }

    @Test fun aWrittenFilterReadsBackWithTheSameAnswersAndKeepsGrowing() {
        val f = EntityFilter(initialKeys = 1_000)
        for (i in 0 until 9_000) f.add('X', key("e", i))          // four layers, the last one partly filled
        val g = roundTrip(f)
        assertEquals(f.count, g.count); assertEquals(f.layers, g.layers); assertEquals(f.bytes, g.bytes)
        for (i in 0 until 9_000) assertTrue(g.mightContain('X', key("e", i)), "lost key $i")
        for (i in 0 until 20_000) assertEquals(f.mightContain('K', key("absent", i)), g.mightContain('K', key("absent", i)), "probe $i")
        for (i in 9_000 until 30_000) { f.add('X', key("e", i)); g.add('X', key("e", i)) }
        assertEquals(f.layers, g.layers)
        for (i in 0 until 40_000) assertEquals(f.mightContain('X', key("e", i)), g.mightContain('X', key("e", i)), "after growth $i")
        assertEquals(0L, roundTrip(EntityFilter()).count)
    }

    @Test fun aMalformedFilterIsRefused() {
        val f = EntityFilter(initialKeys = 1_000); for (i in 0 until 2_500) f.add('X', key("e", i))
        val bytes = java.io.ByteArrayOutputStream().also { f.writeTo(java.io.DataOutputStream(it)) }.toByteArray()
        fun read(b: ByteArray) = EntityFilter.readFrom(java.io.DataInputStream(java.io.ByteArrayInputStream(b)))
        // header: layer count, then per layer capacity (long), bits per key (int), count (long), word count (int), words
        for (cut in listOf(0, 3, 10, 30, bytes.size - 1)) kotlin.test.assertFails("truncated at $cut") { read(bytes.copyOf(cut)) }
        fun patched(at: Int, value: Int) = bytes.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(at, value) }
        kotlin.test.assertFails("no layers") { read(patched(0, 0)) }
        kotlin.test.assertFails("first layer capacity") { read(bytes.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(4, 999L) }) }
        kotlin.test.assertFails("bits per key") { read(patched(12, 40)) }
        kotlin.test.assertFails("count above capacity") { read(bytes.copyOf().also { java.nio.ByteBuffer.wrap(it).putLong(16, 1_001L) }) }
        kotlin.test.assertFails("word count") { read(patched(24, 7)) }
    }
}
