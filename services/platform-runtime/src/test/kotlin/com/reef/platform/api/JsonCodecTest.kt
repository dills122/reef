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
}
