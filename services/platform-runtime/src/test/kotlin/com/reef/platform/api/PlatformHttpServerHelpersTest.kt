package com.reef.platform.api

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PlatformHttpServerHelpersTest {
    @Test
    fun rootCauseUnwrapsNestedCompletionAndExecutionExceptions() {
        val root = IllegalStateException("boom")
        val wrapped = CompletionException(ExecutionException(root))

        assertSame(root, rootCause(wrapped))
    }

    @Test
    fun rootCauseReturnsInputWhenNotWrapped() {
        val plain = IllegalStateException("boom")
        assertSame(plain, rootCause(plain))
    }

    @Test
    fun rootCauseStopsAtWrapperWithNoCause() {
        val wrapper = CompletionException("no cause", null)
        assertSame(wrapper, rootCause(wrapper))
    }

    @Test
    fun rootMessageReturnsRootCauseMessage() {
        val wrapped = CompletionException(ExecutionException(IllegalStateException("root failure")))
        assertEquals("root failure", rootMessage(wrapped))
    }

    @Test
    fun rootMessageFallsBackToUnknownWhenMessageIsNull() {
        val wrapped = CompletionException(ExecutionException(IllegalStateException()))
        assertEquals("unknown", rootMessage(wrapped))
    }

    @Test
    fun requiredLongParsesValidInteger() {
        val doc = JsonCodec.parseObject("""{"quantity":"42"}""")
        assertEquals(42L, doc.requiredLong("quantity"))
    }

    @Test
    fun requiredLongThrowsOnMissingOrInvalidValue() {
        val doc = JsonCodec.parseObject("""{"quantity":"not-a-number"}""")
        assertFailsWith<IllegalArgumentException> { doc.requiredLong("quantity") }
        assertFailsWith<IllegalArgumentException> { doc.requiredLong("missing") }
    }

    @Test
    fun requiredIntParsesValidInteger() {
        val doc = JsonCodec.parseObject("""{"count":"7"}""")
        assertEquals(7, doc.requiredInt("count"))
    }

    @Test
    fun requiredIntThrowsOnMissingOrInvalidValue() {
        val doc = JsonCodec.parseObject("""{"count":"nope"}""")
        assertFailsWith<IllegalArgumentException> { doc.requiredInt("count") }
        assertFailsWith<IllegalArgumentException> { doc.requiredInt("missing") }
    }

    @Test
    fun booleanParsesCaseInsensitiveTrueAndFalse() {
        val doc = JsonCodec.parseObject("""{"a":"true","b":"TRUE","c":"false","d":"anything"}""")
        assertEquals(true, doc.boolean("a"))
        assertEquals(true, doc.boolean("b"))
        assertEquals(false, doc.boolean("c"))
        assertEquals(false, doc.boolean("d"))
        assertEquals(false, doc.boolean("missing"))
    }
}
