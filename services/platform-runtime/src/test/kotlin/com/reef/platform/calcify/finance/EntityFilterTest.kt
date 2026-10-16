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
}
