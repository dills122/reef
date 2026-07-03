package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamCommandIntakeTest {
    @Test
    fun subjectBuilderUsesDeterministicPartitionAndRoutingTokens() {
        val config = StreamCommandConfig(
            streamName = "REEF_COMMANDS",
            subjectPrefix = "reef.cmd.v1",
            partitionCount = 16
        )
        val first = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            body = validStreamSubmitBody(),
            config = config
        )
        val second = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-2",
            body = validStreamSubmitBody().replace("\"commandId\":\"cmd-1\"", "\"commandId\":\"cmd-2\""),
            config = config
        )

        val firstEnvelope = assertIs<EitherBoundaryError.Envelope>(first).envelope
        val secondEnvelope = assertIs<EitherBoundaryError.Envelope>(second).envelope
        assertEquals(firstEnvelope.partition, secondEnvelope.partition)
        assertTrue(firstEnvelope.subject.matches(Regex("""reef\.cmd\.v1\.p\d{2}\.session-1\.AAPL\.SubmitOrder""")))
    }

    @Test
    fun envelopeBuilderRejectsMissingRoutingMetadata() {
        val result = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/cancel",
            idempotencyKey = "idem-1",
            body = """
                {
                  "commandId":"cmd-1",
                  "traceId":"trace-1",
                  "correlationId":"corr-1",
                  "actorId":"bot-1",
                  "occurredAt":"2026-05-22T00:00:00Z",
                  "orderId":"ord-1",
                  "reason":"test"
                }
            """.trimIndent()
        )

        val error = assertIs<EitherBoundaryError.Error>(result).error
        assertEquals("STREAM_ROUTING_METADATA_REQUIRED", error.code)
        assertEquals(400, error.status)
    }

    @Test
    fun intakeStoreReplaysSamePayloadAndConflictsDifferentPayload() {
        val store = InMemoryStreamCommandIntakeStore()
        val envelope = assertIs<EitherBoundaryError.Envelope>(
            StreamCommandEnvelopeBuilder.fromRequest(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-1",
                body = validStreamSubmitBody()
            )
        ).envelope
        val first = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        val published = store.markPublished(envelope.scope, envelope.idempotencyKey, 42L)
        val replay = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        val changedEnvelope = envelope.copy(payloadHash = "different-hash")
        val conflict = store.reserve(changedEnvelope, changedEnvelope.reference("REEF_COMMANDS"))

        assertIs<StreamCommandReservation.Reserved>(first)
        assertEquals(42L, published?.streamSequence)
        assertEquals(42L, assertIs<StreamCommandReservation.Replay>(replay).reference.streamSequence)
        assertIs<StreamCommandReservation.Conflict>(conflict)
    }

    private fun validStreamSubmitBody(): String {
        return """
            {
              "commandId":"cmd-1",
              "traceId":"trace-1",
              "causationId":"",
              "correlationId":"corr-1",
              "actorId":"bot-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "runId":"run-1",
              "venueSessionId":"session-1",
              "clientOrderId":"clord-1",
              "orderId":"ord-1",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()
    }
}
