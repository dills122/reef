package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonCodecTest {
    @Test
    fun extractsStringFieldsWithWhitespaceAndEscapes() {
        val body = """
            {
              "orderId" : "ord-\"quoted\"-1",
              "quantityUnits": 100,
              "missingText": null
            }
        """.trimIndent()
        val json = JsonCodec.parseObject(body)

        assertEquals("ord-\"quoted\"-1", json.string("orderId"))
        assertEquals("100", json.string("quantityUnits"))
        assertEquals("", json.string("missingText"))
        assertEquals("", json.string("nope"))
    }

    @Test
    fun extractsObjectArraysWithoutRegexAssumptions() {
        val body = """
            {
              "executions": [
                {"eventId": "evt-1", "reason": "brace } and bracket ] inside string"},
                {"eventId": "evt-2", "nested": {"ignored": true}},
                "not-an-object"
              ]
            }
        """.trimIndent()

        val document = JsonCodec.parseObject(body)
        val objects = document.objectArray("executions")
        val docs = document.objectDocuments("executions")

        assertEquals(2, objects.size)
        assertEquals(2, docs.size)
        assertEquals("evt-1", docs[0].string("eventId"))
        assertEquals("evt-2", docs[1].string("eventId"))
    }

    @Test
    fun writesObjectsAndEscapesStrings() {
        val payload = JsonCodec.writeObject(
            "orderId" to "ord-1",
            "ok" to true,
            "count" to 2,
            "tags" to listOf("a", "b")
        )

        assertContains(payload, """"orderId":"ord-1"""")
        assertContains(payload, """"ok":true""")
        assertContains(payload, """"count":2""")
        assertContains(payload, """"tags":["a","b"]""")
        assertEquals("""quote\"slash\\newline\n""", JsonCodec.escapeString("quote\"slash\\newline\n"))
    }

    @Test
    fun jsonFieldsFacadePreservesEmptyFallbackForMalformedPayloads() {
        assertEquals("", JsonFields.extract("""{"orderId":""", "orderId"))
        assertTrue(JsonFields.extractObjects("""{"executions":""", "executions").isEmpty())
    }

    @Test
    fun commandParsersRejectMalformedJsonPayloads() {
        assertFailsWith<IllegalArgumentException> {
            PlatformCommandParsers.submitOrder("""{"commandId":""")
        }
    }
    @Test
    fun canonicalChecksumPreservesByteLengthsAtCacheBoundary() {
        for (value in listOf("", "x", "x".repeat(255), "x".repeat(256), "é".repeat(128), "x".repeat(1024))) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val tokens = "o1:1s1:xs${bytes.size}:".toByteArray(Charsets.UTF_8) + bytes
            val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tokens))
            assertEquals(expected, JsonCodec.parseObject(JsonCodec.writeObject("x" to value)).semanticSha256())
        }
    }

    @Test
    fun canonicalChecksumSortsFieldsAndExcludesOnlyRoot() {
        val tokens = "o1:1s1:xo1:2s1:as2:αs1:zs2:β".toByteArray(Charsets.UTF_8)
        val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tokens))
        assertEquals(expected, JsonCodec.parseObject("""{"z":"excluded","x":{"z":"β","a":"α"}}""").semanticSha256(setOf("z")))
    }
    @Test
    fun canonicalChecksumIncludesFieldsBeyondMemoBudgetAndLongUtf8Keys() {
        val fields = (0 until 130).map { "k" + it.toString().padStart(3, '0') to true } + ("é".repeat(100) to true)
        val tokens = "o3:131" + fields.joinToString("") { (name, _) -> "s${name.toByteArray(Charsets.UTF_8).size}:" + name + "b1:1" }
        val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tokens.toByteArray(Charsets.UTF_8)))
        assertEquals(expected, JsonCodec.parseObject(JsonCodec.writeObject(*fields.toTypedArray())).semanticSha256())
    }
    @Test
    fun canonicalChecksumRetainsEveryJsonType() {
        val tokens = "o1:1s1:xa1:8n0:b1:1b1:0d1:2d2:-1d3:0.5s2:éo1:1s1:zd1:0".toByteArray(Charsets.UTF_8)
        val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tokens))
        assertEquals(expected, JsonCodec.parseObject("""{"x":[null,true,false,2,-1,0.5,"é",{"z":0}]}""").semanticSha256())
    }

    @Test
    fun byteParserPreservesStrictUtf8AcrossValidationWindows() {
        val body = JsonCodec.writeObject("x" to ("a".repeat(8191) + "é😀" + "b".repeat(8192)))
        assertEquals(JsonCodec.parseObject(body).semanticSha256(), JsonCodec.parseObject(body.toByteArray()).semanticSha256())
        for (invalid in listOf(byteArrayOf(0xc0.toByte(),0xaf.toByte()), byteArrayOf(0xed.toByte(),0xa0.toByte(),0x80.toByte()), byteArrayOf(0x80.toByte()), byteArrayOf(0xf0.toByte(),0x9f.toByte()))) {
            assertFailsWith<IllegalArgumentException> { JsonCodec.parseObject("{\"x\":\"".toByteArray() + invalid + "\"}".toByteArray()) }
        }
        assertFailsWith<IllegalArgumentException> { JsonCodec.parseObject("[]".toByteArray()) }
    }

    @Test
    fun byteParserRejectsEncodingAutodetectionAndBom() {
        val body = "{\"x\":1}"
        for (encoding in listOf("UTF-16BE","UTF-16LE","UTF-32BE","UTF-32LE")) {
            assertFailsWith<IllegalArgumentException> { JsonCodec.parseObject(body.toByteArray(java.nio.charset.Charset.forName(encoding))) }
        }
        assertFailsWith<IllegalArgumentException> { JsonCodec.parseObject(("\uFEFF" + body).toByteArray()) }
    }

    @Test
    fun canonicalChecksumIncludesShortValuesBeyondMemoBudget() {
        val values = (0 until 130).map(Int::toString).let { it + it }
        val tokens = "o1:1s1:xa3:260" + values.joinToString("") { "s${it.length}:$it" }
        val expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tokens.toByteArray()))
        assertEquals(expected, JsonCodec.parseObject(JsonCodec.writeObject("x" to values)).semanticSha256())
    }

}
