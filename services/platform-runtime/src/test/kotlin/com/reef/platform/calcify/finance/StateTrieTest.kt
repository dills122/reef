package com.reef.platform.calcify.finance

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The incremental [StateTrie] against the from-scratch reference [StateCommitment.trieRoot]: randomized schedules over uniform, prefix-crowded and
 * caterpillar key sets, the crafted goldens of the independent encoder, a large state, and the cost of one update (design sections 4.1, 9.3 and 9.4).
 */
class StateTrieTest {
    private val hex = HexFormat.of()
    private fun leafFor(rnd: Random): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
    private fun sha(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest()

    /** Keys that are uniform, share a long prefix with a base key (crowding), or differ from the base in one bit (a caterpillar). */
    private fun key(rnd: Random, base: ByteArray): ByteArray = when (rnd.nextInt(3)) {
        0 -> ByteArray(32).also { rnd.nextBytes(it) }
        1 -> base.copyOf().also { k -> val from = rnd.nextInt(24, 250); for (i in from until 256) if (rnd.nextBoolean()) k[i ushr 3] = (k[i ushr 3].toInt() xor (0x80 ushr (i and 7))).toByte() }
        else -> base.copyOf().also { k -> val b = rnd.nextInt(0, 256); k[b ushr 3] = (k[b ushr 3].toInt() xor (0x80 ushr (b and 7))).toByte() }
    }

    private fun reference(map: Map<String, Pair<ByteArray, ByteArray>>) = StateCommitment.trieRoot(map.values.toList())

    @Test fun anEmptyTrieAndASingleLeafMatchTheReference() {
        val t = StateTrie()
        assertEquals(hex.formatHex(StateCommitment.emptyTrie), hex.formatHex(t.root()))
        val k = sha("k".toByteArray()); val l = sha("l".toByteArray())
        t.put(k, l)
        assertEquals(hex.formatHex(l), hex.formatHex(t.root()), "a single leaf is its own digest")
        assertEquals(1, t.size); assertEquals(hex.formatHex(l), hex.formatHex(t.get(k)!!))
        assertNull(t.get(sha("other".toByteArray())))
    }

    @Test fun tenThousandRandomSchedulesAgreeWithTheReferenceAfterEveryOperation() {
        var checks = 0
        for (seed in 1..10_000) {
            val rnd = Random(seed.toLong()); val base = ByteArray(32).also { rnd.nextBytes(it) }
            val trie = StateTrie(); val map = LinkedHashMap<String, Pair<ByteArray, ByteArray>>()
            repeat(rnd.nextInt(1, 40)) {
                val k = if (map.isNotEmpty() && rnd.nextInt(4) == 0) map.values.elementAt(rnd.nextInt(map.size)).first else key(rnd, base)
                val l = leafFor(rnd)
                trie.put(k, l); map[hex.formatHex(k)] = k to l
                assertEquals(hex.formatHex(reference(map)), hex.formatHex(trie.root()), "seed $seed")
                assertEquals(map.size, trie.size); checks++
            }
        }
        assertTrue(checks > 100_000)
    }

    @Test fun insertionOrderDoesNotChangeTheDigestAndTheGoldensMatch() {
        val goldens = javaClass.getResourceAsStream("/calcify/state-commitment-goldens.json").use { ObjectMapper().readTree(it) }
        for (case in goldens["trieCases"]) {
            val pairs = case["pairs"].map { hex.parseHex(it["key"].asText()) to hex.parseHex(it["leaf"].asText()) }
            for (order in listOf(pairs, pairs.reversed(), pairs.shuffled(Random(1)), pairs.shuffled(Random(2)))) {
                val t = StateTrie(); order.forEach { t.put(it.first, it.second) }
                assertEquals(case["root"].asText(), hex.formatHex(t.root()), case["name"].asText())
            }
        }
    }

    @Test fun aLargeStateWithInterleavedUpdatesAgreesWithTheReference() {
        val rnd = Random(77); val trie = StateTrie(); val map = HashMap<String, Pair<ByteArray, ByteArray>>()
        repeat(100_000) { val k = ByteArray(32).also { rnd.nextBytes(it) }; val l = leafFor(rnd); trie.put(k, l); map[hex.formatHex(k)] = k to l }
        assertEquals(hex.formatHex(reference(map)), hex.formatHex(trie.root()))
        val keys = map.values.map { it.first }
        repeat(2_000) { val k = keys[rnd.nextInt(keys.size)]; val l = leafFor(rnd); trie.put(k, l); map[hex.formatHex(k)] = k to l }
        repeat(500) { val k = ByteArray(32).also { rnd.nextBytes(it) }; val l = leafFor(rnd); trie.put(k, l); map[hex.formatHex(k)] = k to l }
        assertEquals(hex.formatHex(reference(map)), hex.formatHex(trie.root()))
        assertEquals(map.size, trie.size)
    }

    @Test fun anUpdateRehashesOnlyItsPathAndAnUnchangedRootRehashesNothing() {
        val rnd = Random(5); val trie = StateTrie(); val keys = ArrayList<ByteArray>()
        repeat(50_000) { val k = ByteArray(32).also { rnd.nextBytes(it) }; keys += k; trie.put(k, leafFor(rnd)) }
        trie.root()
        val settled = trie.branchHashes
        trie.root(); assertEquals(settled, trie.branchHashes, "a second root() with nothing stale hashes nothing")
        var worst = 0L; var total = 0L
        repeat(1_000) {
            val before = trie.branchHashes
            trie.put(keys[rnd.nextInt(keys.size)], leafFor(rnd)); trie.root()
            val spent = trie.branchHashes - before; worst = maxOf(worst, spent); total += spent
        }
        assertTrue(total / 1_000.0 < 30.0, "mean branches re-hashed per update is near log2(n): ${total / 1_000.0}")
        assertTrue(worst <= 256, "never more than one path")
    }

    @Test fun aCaterpillarCostsOneHashPerLevelOfItsPath() {
        for (levels in listOf(20, 40, 80, 160)) {
            val trie = StateTrie(); val base = ByteArray(32)
            trie.put(base, sha("base".toByteArray()))
            for (b in 0 until levels) { val k = ByteArray(32); k[b ushr 3] = (0x80 ushr (b and 7)).toByte(); trie.put(k, sha("c$b".toByteArray())) }
            trie.root()
            val deepest = base                                   // the all-zero key sits below every branch of the caterpillar
            val depth = trie.depthOf(deepest)
            assertEquals(levels, depth, "one branch per diverging bit")
            val before = trie.branchHashes
            trie.put(deepest, sha("changed".toByteArray())); trie.root()
            assertEquals(depth.toLong(), trie.branchHashes - before, "updating the deepest leaf re-hashes exactly its path ($levels levels)")
        }
    }

    @Test fun replacingAKeyKeepsTheSizeAndReturnsTheNewLeaf() {
        val t = StateTrie(); val k = sha("k".toByteArray())
        t.put(k, sha("1".toByteArray())); t.put(sha("k2".toByteArray()), sha("2".toByteArray()))
        t.put(k, sha("3".toByteArray()))
        assertEquals(2, t.size); assertEquals(hex.formatHex(sha("3".toByteArray())), hex.formatHex(assertNotNull(t.get(k))))
    }

    @Test fun malformedKeysAndLeavesAreRefusedAndTheInputIsCopied() {
        val t = StateTrie()
        assertFailsWith<IllegalArgumentException> { t.put(ByteArray(31), ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { t.put(ByteArray(32), ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { t.get(ByteArray(5)) }
        val k = ByteArray(32).also { it[0] = 1 }; val l = ByteArray(32).also { it[1] = 2 }
        t.put(k, l); val before = hex.formatHex(t.root())
        k[0] = 0; l[1] = 0
        assertEquals(before, hex.formatHex(t.root()), "later changes to the caller's arrays do not reach the trie")
    }
}
