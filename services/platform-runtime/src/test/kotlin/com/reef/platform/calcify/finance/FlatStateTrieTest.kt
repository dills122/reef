package com.reef.platform.calcify.finance

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.HexFormat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The flat-array [FlatStateTrie] must produce exactly the digests of the linked [StateTrie] and of the from-scratch reference
 * [StateCommitment.trieRoot], with the same number of branch hashes, on random operation sequences (uniform, prefix-crowded and caterpillar keys,
 * replacements, and roots taken at random points so stale and clean paths interleave), plus the committed goldens.
 */
class FlatStateTrieTest {
    private val hex = HexFormat.of()
    private fun leafFor(rnd: Random): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }

    private fun key(rnd: Random, base: ByteArray): ByteArray = when (rnd.nextInt(3)) {
        0 -> ByteArray(32).also { rnd.nextBytes(it) }
        1 -> base.copyOf().also { k -> val from = rnd.nextInt(24, 250); for (i in from until 256) if (rnd.nextBoolean()) k[i ushr 3] = (k[i ushr 3].toInt() xor (0x80 ushr (i and 7))).toByte() }
        else -> base.copyOf().also { k -> val b = rnd.nextInt(0, 256); k[b ushr 3] = (k[b ushr 3].toInt() xor (0x80 ushr (b and 7))).toByte() }
    }

    @Test fun emptyAndSingleLeaf() {
        val t = FlatStateTrie()
        assertEquals(hex.formatHex(StateCommitment.emptyTrie), hex.formatHex(t.root()))
        val k = ByteArray(32) { 7 }; val l = ByteArray(32) { 9 }
        t.put(k, l)
        assertEquals(hex.formatHex(l), hex.formatHex(t.root())); assertEquals(1, t.size)
        assertEquals(hex.formatHex(l), hex.formatHex(t.get(k)!!)); assertNull(t.get(ByteArray(32)))
        assertFailsWith<IllegalArgumentException> { t.put(ByteArray(31), ByteArray(32)) }
    }

    @Test fun tenThousandRandomSchedulesAgreeWithTheLinkedTrieAndTheReference() {
        var checks = 0
        for (seed in 1..10_000) {
            val rnd = Random(seed.toLong()); val base = ByteArray(32).also { rnd.nextBytes(it) }
            val flat = FlatStateTrie(); val linked = StateTrie(); val map = LinkedHashMap<String, Pair<ByteArray, ByteArray>>()
            repeat(rnd.nextInt(1, 40)) {
                val k = if (map.isNotEmpty() && rnd.nextInt(4) == 0) map.values.elementAt(rnd.nextInt(map.size)).first else key(rnd, base)
                val l = leafFor(rnd)
                flat.put(k, l); linked.put(k, l); map[hex.formatHex(k)] = k to l
                if (rnd.nextInt(3) != 0) {   // roots at random points: stale paths accumulate across several puts
                    val expected = hex.formatHex(StateCommitment.trieRoot(map.values.toList()))
                    assertEquals(expected, hex.formatHex(flat.root()), "seed $seed"); assertEquals(expected, hex.formatHex(linked.root()))
                    assertEquals(linked.branchHashes, flat.branchHashes, "same stale set, same hashes (seed $seed)"); checks++
                }
                assertEquals(map.size, flat.size)
            }
            for ((_, p) in map) assertEquals(hex.formatHex(p.second), hex.formatHex(flat.get(p.first)!!))
        }
        assertTrue(checks > 50_000)
    }

    @Test fun goldensMatchInAnyInsertionOrder() {
        val goldens = javaClass.getResourceAsStream("/calcify/state-commitment-goldens.json").use { ObjectMapper().readTree(it) }
        for (case in goldens["trieCases"]) {
            val pairs = case["pairs"].map { hex.parseHex(it["key"].asText()) to hex.parseHex(it["leaf"].asText()) }
            for (order in listOf(pairs, pairs.reversed(), pairs.shuffled(java.util.Random(1)))) {
                val t = FlatStateTrie(); order.forEach { t.put(it.first, it.second) }
                assertEquals(case["root"].asText(), hex.formatHex(t.root()), case["name"].asText())
            }
        }
    }

    @Test fun aLargeStateAcrossChunkBoundariesAgrees() {
        val rnd = Random(77); val flat = FlatStateTrie(); val linked = StateTrie()
        val keys = ArrayList<ByteArray>()
        repeat(200_000) { val k = ByteArray(32).also { rnd.nextBytes(it) }; val l = leafFor(rnd); flat.put(k, l); linked.put(k, l); keys += k }   // > 3 chunks of 65,536 leaves
        assertEquals(hex.formatHex(linked.root()), hex.formatHex(flat.root()))
        repeat(5_000) { val k = keys[rnd.nextInt(keys.size)]; val l = leafFor(rnd); flat.put(k, l); linked.put(k, l); if (it % 7 == 0) assertEquals(hex.formatHex(linked.root()), hex.formatHex(flat.root())) }
        repeat(2_000) { val k = ByteArray(32).also { rnd.nextBytes(it) }; val l = leafFor(rnd); flat.put(k, l); linked.put(k, l) }
        assertEquals(hex.formatHex(linked.root()), hex.formatHex(flat.root()))
        assertEquals(linked.size, flat.size); assertEquals(linked.branchHashes, flat.branchHashes)
        val someKeys = keys.take(100)
        for (k in someKeys) assertEquals(linked.depthOf(k), flat.depthOf(k))
    }

    @Test fun aCaterpillarCostsOneHashPerLevel() {
        for (levels in listOf(20, 80, 160, 255)) {
            val t = FlatStateTrie(); val base = ByteArray(32)
            t.put(base, ByteArray(32) { 1 })
            for (b in 0 until levels) { val k = ByteArray(32); k[b ushr 3] = (0x80 ushr (b and 7)).toByte(); t.put(k, ByteArray(32) { (b + 2).toByte() }) }
            t.root()
            assertEquals(levels, t.depthOf(base))
            val before = t.branchHashes
            t.put(base, ByteArray(32) { 99 }); t.root()
            assertEquals(levels.toLong(), t.branchHashes - before)
        }
    }

    @Test fun inputsAreCopied() {
        val t = FlatStateTrie(); val k = ByteArray(32).also { it[0] = 1 }; val l = ByteArray(32).also { it[1] = 2 }
        t.put(k, l); t.put(ByteArray(32), ByteArray(32)); val before = hex.formatHex(t.root())
        k[0] = 0; l[1] = 0
        assertEquals(before, hex.formatHex(t.root()))
    }
}
