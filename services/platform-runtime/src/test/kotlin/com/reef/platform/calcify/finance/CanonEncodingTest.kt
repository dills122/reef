package com.reef.platform.calcify.finance

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The canonical encoder is hot (every decision, every entity). It must stay byte-identical to the stream based encoder it replaced, which this test keeps as the reference. */
class CanonEncodingTest {
    private fun referenceUtf8(value: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try { encoder.encode(CharBuffer.wrap(value)) } catch (e: CharacterCodingException) { throw IllegalArgumentException("invalid UTF-16 text", e) }
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun referenceBytes(block: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream(); DataOutputStream(buffer).use(block); return buffer.toByteArray()
    }

    private fun randomText(rnd: Random): String {
        val length = rnd.nextInt(40)
        return buildString {
            repeat(length) {
                when (rnd.nextInt(10)) {
                    0 -> append((0x80 + rnd.nextInt(0x700)).toChar())
                    1 -> append((0x800 + rnd.nextInt(0xD000 - 0x800)).toChar())
                    2 -> appendCodePoint(0x10000 + rnd.nextInt(0x100000))
                    3 -> append(rnd.nextInt(0x20).toChar())
                    else -> append((0x20 + rnd.nextInt(0x5f)).toChar())
                }
            }
        }
    }

    @Test fun theEncoderIsByteIdenticalToTheStreamEncoderOnRandomRecords() {
        val rnd = Random(20261015)
        repeat(20_000) {
            val strings = List(rnd.nextInt(6)) { randomText(rnd) }
            val longs = List(rnd.nextInt(4)) { rnd.nextLong() }; val ints = List(rnd.nextInt(3)) { rnd.nextInt() }
            val expected = referenceBytes { out ->
                strings.forEach { s -> val b = referenceUtf8(s); out.writeInt(b.size); out.write(b) }
                longs.forEach(out::writeLong); ints.forEach(out::writeInt)
            }
            val actual = Canon.bytes { strings.forEach { str(it) }; longs.forEach { long(it) }; ints.forEach { int(it) } }
            assertContentEquals(expected, actual, "strings=$strings longs=$longs ints=$ints")
        }
    }

    @Test fun edgeValuesEncodeIdentically() {
        val values = listOf("", "a", "\u0000", "\u007f", "\u0080", "߿", "ࠀ", "￿", "😀", "a".repeat(5000), "é".repeat(3000))
        for (v in values) {
            assertContentEquals(referenceBytes { out -> val b = referenceUtf8(v); out.writeInt(b.size); out.write(b) }, Canon.bytes { str(v) }, "value of length ${v.length}")
        }
        for (v in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) assertContentEquals(referenceBytes { it.writeLong(v) }, Canon.bytes { long(v) })
        for (v in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) assertContentEquals(referenceBytes { it.writeInt(v) }, Canon.bytes { int(v) })
    }

    @Test fun invalidUtf16IsStillRefusedWhetherOrNotTheRestIsAscii() {
        for (bad in listOf("\uD800", "abc\uD800", "\uDC00abc", "é\uD800", "\uD800a")) {
            assertFailsWith<IllegalArgumentException>(bad) { Canon.bytes { str(bad) } }
        }
    }

    @Test fun shaMatchesAFreshDigest() {
        val rnd = Random(3)
        repeat(2_000) {
            val bytes = ByteArray(rnd.nextInt(300)).also(rnd::nextBytes)
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), Canon.sha(bytes))
        }
    }

    @Test fun shaIsCorrectWhenManyThreadsHashDifferentInputsAtOnce() {
        val threads = 8; val failures = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    val rnd = Random(100L + t)
                    repeat(20_000) {
                        val bytes = ByteArray(1 + rnd.nextInt(200)).also(rnd::nextBytes)
                        if (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) != Canon.sha(bytes)) failures.incrementAndGet()
                    }
                }
            }
            futures.forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertEquals(0, failures.get(), "a digest shared between threads corrupts results")
    }

    @Test fun aLoneSurrogateAnywhereInARandomStringIsRefused() {
        val rnd = Random(11); var refused = 0
        repeat(2_000) {
            val text = StringBuilder(randomText(rnd))
            text.insert(rnd.nextInt(text.length + 1), if (rnd.nextBoolean()) '\uD800' else '\uDC00')
            val s = text.toString()
            // the inserted surrogate may have joined a neighbour into a valid pair; the reference decides which strings are invalid
            val invalid = try { referenceUtf8(s); false } catch (_: IllegalArgumentException) { true }
            if (invalid) { refused++; assertFailsWith<IllegalArgumentException> { Canon.bytes { str(s) } } }
            else assertContentEquals(referenceBytes { out -> val b = referenceUtf8(s); out.writeInt(b.size); out.write(b) }, Canon.bytes { str(s) })
        }
        assert(refused > 500) { "the test must actually exercise invalid text ($refused)" }
    }
}
