package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresRuntimeJsonTest {
    private val order = PersistedOrder(
        orderId = "ord-1",
        engineOrderId = "eng-1",
        instrumentId = "AAPL",
        participantId = "participant-1",
        accountId = "account-1",
        side = "BUY",
        orderType = "LIMIT",
        quantityUnits = "100",
        limitPrice = "150250000000",
        currency = "USD",
        timeInForce = "DAY",
        acceptedAt = "2026-07-07T00:00:00Z",
        clientOrderId = "client-1",
        runId = "run-1",
        venueSessionId = "session-1"
    )

    private val execution = ExecutionCreated(
        eventId = "evt-exec-1",
        executionId = "exec-1",
        orderId = "ord-1",
        instrumentId = "AAPL",
        quantityUnits = "100",
        executionPrice = "150250000000",
        currency = "USD",
        occurredAt = "2026-07-07T00:00:00Z"
    )

    private val trade = TradeCreated(
        eventId = "evt-trade-1",
        tradeId = "trade-1",
        executionId = "exec-1",
        buyOrderId = "ord-1",
        sellOrderId = "ord-2",
        instrumentId = "AAPL",
        quantityUnits = "100",
        price = "150250000000",
        currency = "USD",
        occurredAt = "2026-07-07T00:00:00Z"
    )

    private val event = RuntimeEvent(
        eventId = "evt-1",
        eventType = "OrderAccepted",
        orderId = "ord-1",
        traceId = "trace-1",
        causationId = "cmd-1",
        correlationId = "corr-1",
        actorId = "actor-1",
        producer = "platform-runtime",
        schemaVersion = "v1",
        payloadJson = """{"key":"value"}""",
        occurredAt = "2026-07-07T00:00:00Z"
    )

    @Test
    fun persistedOrderSerializesAllFields() {
        val json = order.toJsonObject()
        assertEquals(
            "{\"orderId\":\"ord-1\", \"engineOrderId\":\"eng-1\", \"instrumentId\":\"AAPL\", " +
                "\"participantId\":\"participant-1\", \"accountId\":\"account-1\", \"side\":\"BUY\", " +
                "\"orderType\":\"LIMIT\", \"quantityUnits\":\"100\", \"limitPrice\":\"150250000000\", " +
                "\"currency\":\"USD\", \"timeInForce\":\"DAY\", \"acceptedAt\":\"2026-07-07T00:00:00Z\", " +
                "\"clientOrderId\":\"client-1\", \"runId\":\"run-1\", \"venueSessionId\":\"session-1\"}",
            json
        )
    }

    @Test
    fun executionCreatedSerializesAllFields() {
        val json = execution.toJsonObject()
        assertEquals(
            "{\"eventId\":\"evt-exec-1\", \"executionId\":\"exec-1\", \"orderId\":\"ord-1\", " +
                "\"instrumentId\":\"AAPL\", \"quantityUnits\":\"100\", \"executionPrice\":\"150250000000\", " +
                "\"currency\":\"USD\", \"occurredAt\":\"2026-07-07T00:00:00Z\"}",
            json
        )
    }

    @Test
    fun tradeCreatedSerializesAllFields() {
        val json = trade.toJsonObject()
        assertEquals(
            "{\"eventId\":\"evt-trade-1\", \"tradeId\":\"trade-1\", \"executionId\":\"exec-1\", " +
                "\"buyOrderId\":\"ord-1\", \"sellOrderId\":\"ord-2\", \"instrumentId\":\"AAPL\", " +
                "\"quantityUnits\":\"100\", \"price\":\"150250000000\", \"currency\":\"USD\", " +
                "\"occurredAt\":\"2026-07-07T00:00:00Z\"}",
            json
        )
    }

    @Test
    fun runtimeEventSerializesWithPayload() {
        val json = event.toJsonObject()
        assertEquals(
            "{\"eventId\":\"evt-1\",\"eventType\":\"OrderAccepted\",\"orderId\":\"ord-1\"," +
                "\"traceId\":\"trace-1\",\"causationId\":\"cmd-1\",\"correlationId\":\"corr-1\"," +
                "\"actorId\":\"actor-1\",\"producer\":\"platform-runtime\",\"schemaVersion\":\"v1\"," +
                "\"occurredAt\":\"2026-07-07T00:00:00Z\",\"payloadJson\":{\"key\":\"value\"}}",
            json
        )
    }

    @Test
    fun runtimeEventFallsBackToEmptyObjectWhenPayloadBlank() {
        val blankPayload = event.copy(payloadJson = "  ")
        val json = blankPayload.toJsonObject()
        assertEquals(true, json.endsWith("\"payloadJson\":{}}"))
    }

    @Test
    fun persistableSubmitOutcomeSerializesAcceptedResult() {
        val outcome = PersistableSubmitOutcome(
            commandId = "cmd-1",
            result = SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = "evt-accept-1",
                    orderId = "ord-1",
                    engineOrderId = "eng-1",
                    occurredAt = "2026-07-07T00:00:00Z"
                ),
                executions = listOf(execution),
                trades = listOf(trade)
            ),
            acceptedOrder = order,
            lifecycleEvents = listOf(event)
        )

        val json = outcome.toJsonObject()
        assertEquals(true, json.contains("\"resultType\":\"accepted\""))
        assertEquals(true, json.contains("\"eventId\":\"evt-accept-1\""))
        assertEquals(true, json.contains("\"engineOrderId\":\"eng-1\""))
        assertEquals(true, json.contains("\"acceptedOrder\":{\"orderId\":\"ord-1\""))
        assertEquals(true, json.contains("\"executions\":[{"))
        assertEquals(true, json.contains("\"trades\":[{"))
        assertEquals(true, json.contains("\"events\":[{"))
    }

    @Test
    fun persistableSubmitOutcomeSerializesRejectedResultWithNullAcceptedOrder() {
        val outcome = PersistableSubmitOutcome(
            commandId = "cmd-2",
            result = SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-1",
                    orderId = "ord-2",
                    code = "REFERENCE_DATA_ERROR",
                    reason = "unknown instrument",
                    occurredAt = "2026-07-07T00:00:00Z"
                )
            ),
            acceptedOrder = null,
            lifecycleEvents = emptyList()
        )

        val json = outcome.toJsonObject()
        assertEquals(true, json.contains("\"resultType\":\"rejected\""))
        assertEquals(true, json.contains("\"code\":\"REFERENCE_DATA_ERROR\""))
        assertEquals(true, json.contains("\"reason\":\"unknown instrument\""))
        assertEquals(true, json.contains("\"acceptedOrder\":null"))
        assertEquals(true, json.contains("\"executions\":[]"))
        assertEquals(true, json.contains("\"events\":[]"))
    }

    @Test
    fun canonicalSubmitOutcomeSerializesEnvelopeFields() {
        val outcome = CanonicalSubmitOutcome(
            runId = "run-1",
            venueSessionId = "session-1",
            partitionId = 3,
            partitionSequence = 42L,
            streamName = "reef.cmd.v1",
            streamSequence = 7L,
            commandId = "cmd-1",
            idempotencyKey = "idem-1",
            payloadHash = "hash-1",
            instrumentId = "AAPL",
            commandType = "SubmitOrder",
            resultStatus = "ACCEPTED",
            rejectCode = "",
            acceptedAt = "2026-07-07T00:00:00Z",
            completedAt = "2026-07-07T00:00:01Z",
            engineShardId = "shard-1",
            outcome = PersistableSubmitOutcome(
                commandId = "cmd-1",
                result = SubmitOrderResult(),
                acceptedOrder = null,
                lifecycleEvents = emptyList()
            )
        )

        val json = outcome.toJsonObject()
        assertEquals(true, json.contains("\"partitionId\":\"3\""))
        assertEquals(true, json.contains("\"partitionSequence\":\"42\""))
        assertEquals(true, json.contains("\"streamSequence\":\"7\""))
        assertEquals(true, json.contains("\"resultPayload\":{"))
        assertEquals(true, json.contains("\"events\":[]"))
    }

    @Test
    fun venueEventBatchFactSerializesOutcomes() {
        val outcomeFact = VenueCommandOutcomeFact(
            commandId = "cmd-1",
            commandType = "SubmitOrder",
            streamSequence = 5L,
            deliveredCount = 1L,
            payloadHash = "hash-1",
            instrumentId = "AAPL",
            orderId = "ord-1",
            resultStatus = "ACCEPTED",
            rejectCode = "",
            resultPayloadJson = """{"accepted":true}"""
        )
        val batch = VenueEventBatchFact(
            batchId = "batch-1",
            shardId = "shard-1",
            partition = 0,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 1L,
            lastSequence = 5L,
            commandCount = 5,
            createdAt = "2026-07-07T00:00:00Z",
            payloadChecksum = "checksum-1",
            outcomes = listOf(outcomeFact)
        )

        val json = batch.toJsonObject()
        assertEquals(true, json.contains("\"batchId\":\"batch-1\""))
        assertEquals(true, json.contains("\"payloadFormat\":\"venue-event-batch-json\""))
        assertEquals(true, json.contains("\"payloadVersion\":\"v1\""))
        assertEquals(true, json.contains("\"outcomes\":[{"))
        assertEquals(true, json.contains("\"result\":{\"accepted\":true}"))
    }

    @Test
    fun venueCommandOutcomeFactFallsBackToEmptyObjectWhenResultPayloadBlank() {
        val outcomeFact = VenueCommandOutcomeFact(
            commandId = "cmd-2",
            commandType = "CancelOrder",
            streamSequence = 6L,
            deliveredCount = 2L,
            payloadHash = "hash-2",
            instrumentId = "AAPL",
            orderId = "ord-2",
            resultStatus = "REJECTED",
            rejectCode = "NOT_FOUND",
            resultPayloadJson = "  "
        )

        val json = outcomeFact.toJsonObject()
        assertEquals(true, json.endsWith("\"result\":{}}"))
    }

    @Test
    fun toJsonArrayReturnsEmptyArrayForEmptyList() {
        assertEquals("[]", emptyList<ExecutionCreated>().toJsonArray { it.toJsonObject() })
    }

    @Test
    fun toJsonArraySerializesMultipleElements() {
        val json = listOf(execution, execution).toJsonArray { it.toJsonObject() }
        assertEquals(true, json.startsWith("[{"))
        assertEquals(true, json.endsWith("}]"))
        assertEquals(2, json.split("\"eventId\"").size - 1)
    }

    @Test
    fun jsonObjectBuildsFromVarargPairs() {
        assertEquals(
            "{\"a\":\"1\", \"b\":\"2\"}",
            jsonObject("a" to "1", "b" to "2")
        )
    }

    @Test
    fun escapeJsonEscapesControlCharactersAndQuotes() {
        assertEquals("a\\\\b\\\"c\\bd\\fe\\nf\\rg\\th", escapeJson("a\\b\"c\bde\nf\rg\th"))
    }

    @Test
    fun escapeJsonLeavesPlainTextUnchanged() {
        assertEquals("plain text 123", escapeJson("plain text 123"))
    }
}
