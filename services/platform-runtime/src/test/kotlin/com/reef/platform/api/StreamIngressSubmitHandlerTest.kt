package com.reef.platform.api

import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamIngressSubmitHandlerTest {
    private val validSubmitBody = JsonCodec.writeObject(
        "commandId" to "cmd-1",
        "traceId" to "trace-1",
        "correlationId" to "corr-1",
        "actorId" to "actor-1",
        "occurredAt" to "2026-07-07T00:00:00Z",
        "orderId" to "order-1",
        "instrumentId" to "instrument-1",
        "participantId" to "participant-1",
        "accountId" to "account-1",
        "side" to "BUY",
        "orderType" to "LIMIT",
        "quantityUnits" to "10",
        "limitPrice" to "100",
        "currency" to "USD",
        "timeInForce" to "DAY"
    )

    private fun handler(
        maxRequestBodyBytes: Int = 1_000_000,
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.StreamAck,
        defaultClientId: String = "stream-ingress",
        submit: (String, String, String, String, String) -> CompletableFuture<PlatformHotPathResponse> =
            { _, _, _, _, _ -> CompletableFuture.completedFuture(PlatformHotPathResponse(200, "{}")) }
    ) = StreamIngressSubmitHandler(maxRequestBodyBytes, commandProcessingMode, defaultClientId, submit)

    @Test
    fun rejectsOversizedBodyWith413() {
        val response = handler(maxRequestBodyBytes = 4).handle(validSubmitBody).get()
        assertEquals(413, response.status)
        assertTrue(response.body.contains("request body too large"))
    }

    @Test
    fun rejectsWhenCommandProcessingModeIsNotStreamAckWith503() {
        val response = handler(commandProcessingMode = CommandProcessingMode.SyncResult).handle(validSubmitBody).get()
        assertEquals(503, response.status)
        assertTrue(response.body.contains("stream command intake unavailable"))
    }

    @Test
    fun rejectsInvalidJsonWith400() {
        val response = handler().handle("not json").get()
        assertEquals(400, response.status)
        assertTrue(response.body.contains("INVALID_JSON"))
    }

    @Test
    fun rejectsMissingClientIdAndIdempotencyKeyWith400() {
        val body = JsonCodec.writeObject("commandId" to "")
        val response = handler(defaultClientId = "").handle(body).get()
        assertEquals(400, response.status)
        assertTrue(response.body.contains("STREAM_INGRESS_METADATA_REQUIRED"))
    }

    @Test
    fun fallsBackToDefaultClientIdAndCommandIdAsIdempotencyKeyWhenBlank() {
        var capturedClientId: String? = null
        var capturedIdempotencyKey: String? = null
        val body = JsonCodec.writeObject(
            "commandId" to "cmd-1",
            "traceId" to "trace-1",
            "correlationId" to "corr-1",
            "actorId" to "actor-1",
            "occurredAt" to "2026-07-07T00:00:00Z",
            "orderId" to "order-1",
            "instrumentId" to "instrument-1",
            "participantId" to "participant-1",
            "accountId" to "account-1",
            "side" to "BUY",
            "orderType" to "LIMIT",
            "quantityUnits" to "10",
            "limitPrice" to "100",
            "currency" to "USD",
            "timeInForce" to "DAY"
        )
        val response = handler(
            defaultClientId = "fallback-client",
            submit = { _, clientId, idempotencyKey, _, _ ->
                capturedClientId = clientId
                capturedIdempotencyKey = idempotencyKey
                CompletableFuture.completedFuture(PlatformHotPathResponse(200, "{}"))
            }
        ).handle(body).get()
        assertEquals(200, response.status)
        assertEquals("fallback-client", capturedClientId)
        assertEquals("cmd-1", capturedIdempotencyKey)
    }

    @Test
    fun rejectsSchemaValidationFailureWith400() {
        val body = JsonCodec.writeObject(
            "commandId" to "cmd-1",
            "traceId" to "trace-1",
            "correlationId" to "corr-1",
            "actorId" to "actor-1",
            "occurredAt" to "2026-07-07T00:00:00Z",
            "orderId" to "order-1",
            "instrumentId" to "instrument-1",
            "participantId" to "participant-1",
            "accountId" to "account-1",
            "side" to "SIDEWAYS",
            "orderType" to "LIMIT",
            "quantityUnits" to "10",
            "limitPrice" to "100",
            "currency" to "USD",
            "timeInForce" to "DAY"
        )
        val response = handler().handle(body).get()
        assertEquals(400, response.status)
        assertTrue(response.body.contains("VALIDATION_ERROR"))
        assertTrue(response.body.contains("invalid side"))
    }

    @Test
    fun delegatesToSubmitOnValidRequest() {
        var invoked = false
        val response = handler(
            defaultClientId = "client-1",
            submit = { route, clientId, idempotencyKey, correlationId, body ->
                invoked = true
                assertEquals("/api/v1/orders/submit", route)
                assertEquals("client-1", clientId)
                assertEquals("cmd-1", idempotencyKey)
                assertEquals("corr-1", correlationId)
                assertEquals(validSubmitBody, body)
                CompletableFuture.completedFuture(PlatformHotPathResponse(202, "{\"ok\":true}"))
            }
        ).handle(validSubmitBody).get()
        assertTrue(invoked)
        assertEquals(202, response.status)
    }
}
