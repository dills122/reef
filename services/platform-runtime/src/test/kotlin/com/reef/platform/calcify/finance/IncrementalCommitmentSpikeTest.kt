package com.reef.platform.calcify.finance

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Research spike (not product code; excluded from the default run, enable with -PresearchTests): cost of an incremental state commitment versus the current whole-state digest.
 * Two candidates update only the touched entities: (b) AdHash-style multiset accumulator over 3072 bits, (c) bucketed Merkle tree
 * (2^16 buckets, level-by-level dirty-node recompute). Measures per-decision cost (5 touched entities) at several state sizes.
 */
@org.junit.jupiter.api.Tag("research")
class IncrementalCommitmentSpikeTest {
    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach(d::update) }.digest()

    // (b) multiset accumulator
    private class AdHash {
        private val mod = BigInteger.ONE.shiftLeft(3072)
        var acc = BigInteger.ZERO; var count = 0L
        private fun expand(entity: ByteArray): BigInteger {
            val md = MessageDigest.getInstance("SHA-256"); val out = ByteArray(384)
            for (i in 0 until 12) { md.update(entity); md.update(byteArrayOf(i.toByte())); System.arraycopy(md.digest(), 0, out, i * 32, 32) }
            return BigInteger(1, out)
        }
        fun add(entity: ByteArray) { acc = acc.add(expand(entity)).mod(mod); count++ }
        fun remove(entity: ByteArray) { acc = acc.subtract(expand(entity)).mod(mod); count-- }
        fun replace(old: ByteArray?, new: ByteArray) { if (old != null) remove(old); add(new) }
        fun commitment(): ByteArray = MessageDigest.getInstance("SHA-256").digest(acc.toByteArray() + count.toString().toByteArray())
    }

    // (c) bucketed Merkle tree
    private class Merkle(val depth: Int = 16) {
        private val leaves = 1 shl depth
        private val levels = Array(depth + 1) { l -> arrayOfNulls<ByteArray>(1 shl (depth - l)) }   // level 0 = buckets
        private val buckets = HashMap<Int, java.util.TreeMap<String, ByteArray>>()
        private val empty = Array(depth + 1) { ByteArray(32) }.also { for (l in 1..depth) it[l] = MessageDigest.getInstance("SHA-256").digest(it[l - 1] + it[l - 1]) }
        private fun node(level: Int, i: Int) = levels[level][i] ?: empty[level]
        private fun bucketOf(key: String): Int { val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()); return (((h[0].toInt() and 0xff) shl 8) or (h[1].toInt() and 0xff)) and (leaves - 1) }
        fun apply(changes: Map<String, ByteArray>) {
            val dirty = HashSet<Int>()
            for ((k, v) in changes) {
                val b = bucketOf(k); buckets.getOrPut(b) { java.util.TreeMap() }[k] = MessageDigest.getInstance("SHA-256").digest(k.toByteArray() + v); dirty += b
            }
            var current = dirty
            for (b in dirty) {
                val md = MessageDigest.getInstance("SHA-256"); buckets[b]!!.forEach { (k, h) -> md.update(k.toByteArray()); md.update(h) }
                levels[0][b] = md.digest()
            }
            for (l in 1..depth) {
                val next = HashSet<Int>()
                for (i in current) next += i shr 1
                for (p in next) levels[l][p] = MessageDigest.getInstance("SHA-256").also { d -> d.update(node(l - 1, p * 2)); d.update(node(l - 1, p * 2 + 1)) }.digest()
                current = next
            }
        }
        fun root(): ByteArray = node(depth, 0)
    }

    private fun entity(i: Int, version: Int) = "entity/$i".toByteArray() + ":v$version-${"x".repeat(24)}".toByteArray()

    @Test fun commitmentsAreOrderIndependentAndDetectAnyChange() {
        val a = AdHash(); val b = AdHash()
        for (i in 0 until 50) a.add(entity(i, 0)); for (i in 49 downTo 0) b.add(entity(i, 0))
        assertEquals(a.commitment().toList(), b.commitment().toList())
        b.replace(entity(7, 0), entity(7, 1)); assertNotEquals(a.commitment().toList(), b.commitment().toList())
        b.replace(entity(7, 1), entity(7, 0)); assertEquals(a.commitment().toList(), b.commitment().toList())
        val m1 = Merkle(); val m2 = Merkle()
        m1.apply((0 until 50).associate { "k$it" to entity(it, 0) }); m2.apply((49 downTo 0).associate { "k$it" to entity(it, 0) })
        assertEquals(m1.root().toList(), m2.root().toList())
        m2.apply(mapOf("k7" to entity(7, 1))); assertNotEquals(m1.root().toList(), m2.root().toList())
    }

    @Test fun measure() {
        val rnd = Random(1)
        for (size in listOf(700, 10_000, 100_000)) {
            val ad = AdHash(); val mk = Merkle()
            val versions = IntArray(size)
            for (i in 0 until size) ad.add(entity(i, 0))
            mk.apply((0 until size).associate { "k$it" to entity(it, 0) })
            fun adStep() { repeat(5) { val i = rnd.nextInt(size); ad.replace(entity(i, versions[i]), entity(i, ++versions[i])) } }
            fun mkStep() { mk.apply((0 until 5).associate { val i = rnd.nextInt(size); "k$i" to entity(i, ++versions[i]) }) }
            repeat(300) { adStep(); mkStep() }
            val n = if (size >= 100_000) 3000 else 6000
            var t = System.nanoTime(); repeat(n) { adStep() }; val adNs = (System.nanoTime() - t) / n
            t = System.nanoTime(); repeat(n) { mkStep() }; val mkNs = (System.nanoTime() - t) / n
            // Baseline: today's behaviour is a SHA-256 over every entity each decision (modelled as hashing all entity bytes).
            val blob = ByteArray(size * 56); val md = MessageDigest.getInstance("SHA-256")
            val fullN = if (size >= 100_000) 100 else 2000
            repeat(50) { md.digest(blob) }
            t = System.nanoTime(); repeat(fullN) { md.digest(blob) }; val fullNs = (System.nanoTime() - t) / fullN
            println("COMMIT-SPIKE entities=$size perDecision(5 touched): wholeStateHash=${fullNs}ns adHash3072=${adNs}ns merkle16=${mkNs}ns")
        }
    }
}
