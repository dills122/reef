package com.reef.platform.application.postmatch

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CanonicalEffectDecoderTest {
    private val decoder = CanonicalEffectDecoder()

    @Test
    fun decodesTradeAndBothFinalOrderStatesInStableSourceOrder() {
        val source = source(matchedSubmit)
        val first = decoder.decode(source)

        assertEquals(first, decoder.decode(source))
        assertEquals((0..5).toList(), first.map { it.position.effectOrdinal })
        assertEquals(listOf("incoming", "maker"), first.filter { it.effect is CanonicalEffect.OrderStateChanged }
            .map { (it.effect as CanonicalEffect.OrderStateChanged).orderId })
        assertEquals("participant-1", assertIs<CanonicalEffect.Accepted>(first[0].effect).newOrder?.participantId)
        assertEquals("buy-execution", assertIs<CanonicalEffect.Execution>(first[1].effect).eventId)
        assertEquals("sell-execution", assertIs<CanonicalEffect.Execution>(first[2].effect).eventId)
        assertEquals("trade-event", assertIs<CanonicalEffect.Trade>(first[3].effect).eventId)
        assertEquals(source.commandId, first.last().commandId)
    }

    @Test
    fun rejectsMissingMakerStateAndMismatchedExecution() {
        val missingMaker = JsonMapper.builder().build().readTree(matchedSubmit) as ObjectNode
        missingMaker.withArray("orderStates").remove(1)
        assertFailsWith<IllegalArgumentException> { decoder.decode(source(missingMaker.toString())) }

        val wrongOrder = JsonMapper.builder().build().readTree(matchedSubmit) as ObjectNode
        (wrongOrder.withArray("executions")[1] as ObjectNode).put("orderId", "wrong")
        assertFailsWith<IllegalArgumentException> { decoder.decode(source(wrongOrder.toString())) }
    }

    @Test
    fun rejectedAndFailedOutcomesHaveNoBusinessEffects() {
        val rejected = decoder.decode(source("""{"effectVersion":1,"rejected":{"eventId":"reject-1","orderId":"incoming","code":"REJECTED","reason":"invalid","occurredAt":"t"}}""", "rejected"))
        assertEquals(1, rejected.size)
        assertIs<CanonicalEffect.Rejected>(rejected.single().effect)

        val failed = decoder.decode(source("""{"effectVersion":1,"rejected":{"code":"POISON","reason":"bad payload"}}""", "failed"))
        assertEquals(1, failed.size)
        assertIs<CanonicalEffect.Failed>(failed.single().effect)
        assertIs<CanonicalEffect.Failed>(decoder.decode(source("""{"effectVersion":1,"rejected":{"code":"POISON","reason":"bad payload"}}""", "failed").copy(commandId = "")).single().effect)
        assertFailsWith<IllegalArgumentException> {
            decoder.decode(source("""{"rejected":{"eventId":"reject-1","orderId":"incoming","code":"REJECTED","reason":"invalid","occurredAt":"t"}}""", "rejected"))
        }
    }

    @Test
    fun acceptedModifyCanCarryStpCancellationWithoutATrade() {
        val result = """
            {
              "effectVersion":1,
              "accepted":{"eventId":"modify-event","orderId":"incoming","engineOrderId":"engine-incoming","occurredAt":"t"},
              "orderStates":[
                {"orderId":"incoming","instrumentId":"AAPL","side":"BUY","status":"OPEN","originalQuantity":"10","remainingQuantity":"10","limitPrice":"100","currency":"USD","lastUpdatedAt":"t"},
                {"orderId":"maker","instrumentId":"AAPL","side":"SELL","status":"CANCELLED","originalQuantity":"10","remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"t"}
              ]
            }
        """.trimIndent()
        val effects = decoder.decode(source(result).copy(commandType = "ModifyOrder"))
        assertEquals(3, effects.size)
        assertIs<CanonicalEffect.Accepted>(effects[0].effect)
        assertEquals("maker", assertIs<CanonicalEffect.OrderStateChanged>(effects[2].effect).orderId)
    }

    private fun source(result: String, status: String = "accepted") = CanonicalOutcomeSource(
        eventStream = "venue-commands",
        partitionId = 2,
        streamSequence = 42,
        batchId = "batch-1",
        commandId = "command-1",
        commandType = "SubmitOrder",
        payloadHash = "hash-1",
        instrumentId = "AAPL",
        orderId = "incoming",
        resultStatus = status,
        resultPayloadJson = result
    )

    private val matchedSubmit = """
        {
          "effectVersion": 1,
          "accepted": {"eventId":"accept-event","orderId":"incoming","engineOrderId":"engine-incoming","occurredAt":"t"},
          "acceptedOrder": {"orderId":"incoming","engineOrderId":"engine-incoming","clientOrderId":"client-1","runId":"run-1","venueSessionId":"session-1","instrumentId":"AAPL","participantId":"participant-1","accountId":"account-1","side":"BUY","orderType":"LIMIT","quantityUnits":"10","limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"t"},
          "executions": [
            {"eventId":"buy-execution","executionId":"execution-1-buy","orderId":"incoming","instrumentId":"AAPL","quantityUnits":"10","executionPrice":"100","currency":"USD","occurredAt":"t","liquidityRole":"TAKER"},
            {"eventId":"sell-execution","executionId":"execution-1-sell","orderId":"maker","instrumentId":"AAPL","quantityUnits":"10","executionPrice":"100","currency":"USD","occurredAt":"t","liquidityRole":"MAKER"}
          ],
          "trades": [{"eventId":"trade-event","tradeId":"trade-1","executionId":"execution-1","buyOrderId":"incoming","sellOrderId":"maker","instrumentId":"AAPL","quantityUnits":"10","price":"100","currency":"USD","occurredAt":"t"}],
          "orderStates": [
            {"orderId":"incoming","instrumentId":"AAPL","side":"BUY","status":"FILLED","originalQuantity":"10","remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"t"},
            {"orderId":"maker","instrumentId":"AAPL","side":"SELL","status":"FILLED","originalQuantity":"10","remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"t"}
          ]
        }
    """.trimIndent()
}
