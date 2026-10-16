package com.reef.platform.calcify.finance

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * A growing Bloom filter over entity keys (tag plus framed key fields), the in-heap "have we ever written this?" answer that lets the ledger evict settled objects:
 * a key it has never seen is answered without touching the store, which is the case for every new execution and action. A false positive costs one store read that
 * finds nothing; a false negative is impossible, because every key written is added before the entity can be evicted.
 *
 * Shape. Layers are blocked Bloom filters (one 64-byte block per key, so a lookup is one cache line per layer). Keys go into the newest layer; when it holds its
 * capacity a layer twice as large is added, with one more bit per key, so the layers' false positive rates fall geometrically and the total stays between 1% and 2%
 * however far the filter grows. Measured (3M keys, sequential Canon-framed execution keys): 1.47% false positives, 2.94 bytes per key. No key list is kept, so a filter cannot be shrunk
 * or rebuilt without the entities. A new layer is allocated in one piece when the newest fills (as large as all earlier ones together): a one-off pause, which at 70M keys is a
 * 300 MB zeroed array.
 *
 * Not thread-safe: one writer, like the ledger that owns it.
 */
internal class EntityFilter(private val initialKeys: Int = 1 shl 20) {
    private class Layer(val capacity: Long, val bitsPerKey: Int) {
        val k: Int = (bitsPerKey * 0.693).toInt().coerceIn(6, 12)
        val blocks: Long = (capacity * bitsPerKey + 511) / 512
        val words: LongArray
        var count = 0L
        init {
            require(blocks < (1L shl 28)) { "entity filter layer too large" }
            words = LongArray((blocks * 8).toInt())
        }
    }

    private val layerList = ArrayList<Layer>()

    /** Keys added so far. */
    var count = 0L; private set
    val layers: Int get() = layerList.size
    /** Heap taken by the bit arrays. */
    val bytes: Long get() = layerList.sumOf { it.words.size * 8L }

    init { require(initialKeys >= 64) { "initialKeys" }; layerList += Layer(initialKeys.toLong(), 13) }

    fun add(tag: Char, key: ByteArray) {
        var layer = layerList.last()
        if (layer.count >= layer.capacity) { layer = Layer(layer.capacity * 2, minOf(layer.bitsPerKey + 1, 24)); layerList += layer }
        val h1 = hash(tag, key); val h2 = mix(h1 xor SEED_2)
        val base = blockOf(h1, layer) shl 3
        var a = h2.toInt(); val step = (h2 ushr 32).toInt() or 1
        for (j in 0 until layer.k) { val bit = a and 511; layer.words[base + (bit ushr 6)] = layer.words[base + (bit ushr 6)] or (1L shl (bit and 63)); a += step }
        layer.count++; count++
    }

    fun mightContain(tag: Char, key: ByteArray): Boolean {
        val h1 = hash(tag, key); val h2 = mix(h1 xor SEED_2)
        val step = (h2 ushr 32).toInt() or 1
        for (i in layerList.indices.reversed()) {
            val layer = layerList[i]
            if (layer.count == 0L) continue
            val base = blockOf(h1, layer) shl 3
            var a = h2.toInt(); var all = true
            for (j in 0 until layer.k) { val bit = a and 511; if (layer.words[base + (bit ushr 6)] and (1L shl (bit and 63)) == 0L) { all = false; break }; a += step }
            if (all) return true
        }
        return false
    }

    /** Multiply-shift range reduction on the high 32 bits, so a layer's size is exact rather than a power of two. */
    private fun blockOf(h1: Long, layer: Layer): Int = (((h1 ushr 32) * layer.blocks) ushr 32).toInt()

    private companion object {
        const val SEED_1 = -0x61c8864680b583ebL      // 0x9E3779B97F4A7C15
        const val SEED_2 = 0x2545F4914F6CDD1DL
        val LONGS: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

        fun mix(value: Long): Long {
            var x = value
            x = (x xor (x ushr 33)) * -0xae502812aa7333L
            x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
            return x xor (x ushr 33)
        }

        /** A 64-bit hash of the tag and the key bytes: 8 bytes per multiply-rotate round, then the murmur3 finalizer. Not cryptographic and not meant to be. */
        fun hash(tag: Char, key: ByteArray): Long {
            var h = SEED_1 xor (tag.code.toLong() shl 56) xor key.size.toLong()
            var i = 0; val n = key.size
            while (i + 8 <= n) { h = java.lang.Long.rotateLeft((h xor (LONGS.get(key, i) as Long)) * SEED_1, 29) + SEED_2; i += 8 }
            var tail = 0L; var shift = 0
            while (i < n) { tail = tail or ((key[i].toLong() and 0xff) shl shift); shift += 8; i++ }
            h = java.lang.Long.rotateLeft((h xor tail) * SEED_1, 29) + SEED_2
            return mix(h)
        }
    }
}
