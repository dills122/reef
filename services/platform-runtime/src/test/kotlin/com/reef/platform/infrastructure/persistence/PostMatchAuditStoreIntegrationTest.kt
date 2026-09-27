package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Uses an isolated projection database migrated through runtime/0071. */
class PostMatchAuditStoreIntegrationTest {
    @Test
    fun matchedAndPoisonFixturesHaveCompleteOrderedEffects() {
        val source = matchedOutcome("fixture-stream", "buy-order", "sell-order", "fixture")
        val window = CanonicalSourceCoverageVerifier().verify(
            PostMatchAuditStore.CONSUMER, "fixture-stream", 0, "fixture-generation", 2, 3, listOf(source)
        )
        assertEquals(6, window.outcomes.single().effects.size)
        assertEquals((0..5).toList(), window.outcomes.single().effects.map { it.position.effectOrdinal })
        val poison = CanonicalSourceCoverageVerifier().verify(PostMatchAuditStore.CONSUMER,
            "fixture-stream", 0, "fixture-generation", 3, 5,
            listOf(poisonOutcome("fixture-stream", 4), poisonOutcome("fixture-stream", 5)))
        assertEquals(listOf("", ""), poison.outcomes.map { it.source.commandId })
        assertEquals(listOf(1, 1), poison.outcomes.map { it.effects.size })
    }

    @Test
    fun auditEffectsCoverageAndFrontierCommitTogetherAndReplayIsChecked() {
        val url = System.getenv("RUNTIME_PROJECTION_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("RUNTIME_PROJECTION_POSTGRES_USER_TEST")
        val password = System.getenv("RUNTIME_PROJECTION_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "isolated migrated projection PostgreSQL test database is required")
        val dataSource = RuntimeDataSources.dataSource(url!!, user!!, password!!, "audit-integration")
        val store = PostMatchAuditStore(dataSource)
        val verifier = CanonicalSourceCoverageVerifier()
        val token = UUID.randomUUID().toString()
        val stream = "audit-test-$token"
        val generation = "generation-$token"
        val first = outcome(stream, 1, "event-$token", "order-$token", "reason-a")
        val firstWindow = verifier.verify(PostMatchAuditStore.CONSUMER, stream, 0, generation, 0, 1, listOf(first))

        try {
            assertEquals(0L, store.lastCommittedSequence(stream, 0, generation))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(firstWindow))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(firstWindow))
            assertEquals(1L, store.lastCommittedSequence(stream, 0, generation))
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT effect_type, event_id, effect_ordinal FROM runtime.canonical_audit_effects
                       WHERE event_stream = ? AND source_generation = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals("OrderRejected", rows.getString(1))
                        assertEquals("event-$token", rows.getString(2))
                        assertEquals(0, rows.getInt(3))
                        check(!rows.next())
                    }
                }
            }

            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(PostMatchAuditStore.CONSUMER, stream, 0, generation, 0, 1,
                    listOf(first.copy(resultPayloadJson = first.resultPayloadJson.replace("reason-a", "reason-b")))))
            }
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(PostMatchAuditStore.CONSUMER, stream, 0, "other-generation", 1, 2,
                    listOf(outcome(stream, 2, "new-event-$token", "order-$token", "reason-c"))))
            }
            val duplicateEvent = outcome(stream, 2, "event-$token", "other-order-$token", "reason-d")
            assertFailsWith<Exception> {
                store.apply(verifier.verify(PostMatchAuditStore.CONSUMER, stream, 0, generation, 1, 2,
                    listOf(duplicateEvent)))
            }
            assertEquals(1L, store.lastCommittedSequence(stream, 0, generation))
            val second = outcome(stream, 2, "new-event-$token", "other-order-$token", "reason-e")
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(verifier.verify(
                PostMatchAuditStore.CONSUMER, stream, 0, generation, 1, 2, listOf(second)
            )))
            assertEquals(2L, store.lastCommittedSequence(stream, 0, generation))
            val buyOrder = "buy-$token"
            val sellOrder = "sell-$token"
            val tradeWindow = verifier.verify(PostMatchAuditStore.CONSUMER, stream, 0, generation, 2, 3,
                listOf(matchedOutcome(stream, buyOrder, sellOrder, token)))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(tradeWindow))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(tradeWindow))
            assertEquals(3L, store.lastCommittedSequence(stream, 0, generation))
            val poisonWindow = verifier.verify(PostMatchAuditStore.CONSUMER, stream, 0, generation, 3, 5,
                listOf(poisonOutcome(stream, 4), poisonOutcome(stream, 5)))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(poisonWindow))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(poisonWindow))
            assertEquals(5L, store.lastCommittedSequence(stream, 0, generation))
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT COUNT(*) FROM runtime.canonical_audit_effects
                       WHERE event_stream = ? AND source_generation = ? AND effect_type = 'CommandFailed'
                         AND event_id IS NULL AND order_id IS NULL AND occurred_at IS NULL"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(2L, rows.getLong(1))
                    }
                }
                connection.prepareStatement(
                    """SELECT order_id, related_order_id, effect_ordinal FROM runtime.canonical_audit_effects
                       WHERE event_stream = ? AND source_generation = ? AND effect_type = 'Trade'"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(buyOrder, rows.getString(1))
                        assertEquals(sellOrder, rows.getString(2))
                        assertEquals(3, rows.getInt(3))
                        check(!rows.next())
                    }
                }
                connection.prepareStatement(
                    """SELECT COUNT(*) FROM runtime.canonical_audit_outcomes WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(5L, rows.getLong(1))
                    }
                }
                connection.prepareStatement(
                    """SELECT COUNT(*) FROM runtime.canonical_audit_coverage WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(4L, rows.getLong(1))
                    }
                }
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE runtime.canonical_audit_outcomes
                       SET result_payload = jsonb_set(result_payload, '{rejected,reason}', '"tampered"'::jsonb)
                       WHERE event_stream = ? AND source_generation = ? AND partition_id = 0 AND stream_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> { store.apply(firstWindow) }
        } finally {
            dataSource.connection.use { connection ->
                listOf("canonical_audit_effects", "canonical_audit_outcomes", "canonical_audit_coverage",
                    "canonical_audit_frontiers").forEach { table ->
                    connection.prepareStatement("DELETE FROM runtime.$table WHERE event_stream = ?").use { statement ->
                        statement.setString(1, stream)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun outcome(stream: String, sequence: Long, eventId: String, orderId: String, reason: String) =
        CanonicalOutcomeSource(
            eventStream = stream, partitionId = 0, streamSequence = sequence,
            batchId = "batch-$sequence", commandId = "command-$stream-$sequence", commandType = "SubmitOrder",
            payloadHash = "hash-$sequence", instrumentId = "AAPL", orderId = orderId, resultStatus = "rejected",
            resultPayloadJson = """{"effectVersion":1,"rejected":{"eventId":"$eventId","orderId":"$orderId","code":"R","reason":"$reason","occurredAt":"2026-09-26T00:00:00Z"}}"""
        )

    private fun poisonOutcome(stream: String, sequence: Long) = CanonicalOutcomeSource(
        eventStream = stream, partitionId = 0, streamSequence = sequence,
        batchId = "poison-batch-$sequence", commandId = "", commandType = "SubmitOrder",
        payloadHash = "poison-hash-$sequence", instrumentId = "", orderId = "", resultStatus = "failed",
        resultPayloadJson = """{"effectVersion":1,"rejected":{"code":"POISON","reason":"invalid payload $sequence"}}"""
    )

    private fun matchedOutcome(stream: String, buyOrder: String, sellOrder: String, token: String) =
        CanonicalOutcomeSource(
            eventStream = stream, partitionId = 0, streamSequence = 3,
            batchId = "batch-3", commandId = "matched-command-$token", commandType = "SubmitOrder",
            payloadHash = "hash-3", instrumentId = "AAPL", orderId = buyOrder, resultStatus = "accepted",
            resultPayloadJson = """{
              "effectVersion":1,
              "accepted":{"eventId":"accepted-$token","orderId":"$buyOrder","occurredAt":"2026-09-26T00:00:00Z"},
              "acceptedOrder":{"orderId":"$buyOrder","engineOrderId":"engine-$token","clientOrderId":"",
                "runId":"","venueSessionId":"session-1","instrumentId":"AAPL","participantId":"participant-1",
                "accountId":"account-1","side":"BUY","orderType":"LIMIT","quantityUnits":"10",
                "limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-26T00:00:00Z"},
              "executions":[
                {"eventId":"buy-fill-$token","executionId":"execution-$token-buy","orderId":"$buyOrder",
                 "instrumentId":"AAPL","quantityUnits":"5","executionPrice":"100","currency":"USD",
                 "occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"TAKER"},
                {"eventId":"sell-fill-$token","executionId":"execution-$token-sell","orderId":"$sellOrder",
                 "instrumentId":"AAPL","quantityUnits":"5","executionPrice":"100","currency":"USD",
                 "occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"MAKER"}
              ],
              "trades":[{"eventId":"trade-event-$token","tradeId":"trade-$token","executionId":"execution-$token",
                "buyOrderId":"$buyOrder","sellOrderId":"$sellOrder","instrumentId":"AAPL",
                "quantityUnits":"5","price":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z"}],
              "orderStates":[
                {"orderId":"$buyOrder","instrumentId":"AAPL","side":"BUY","status":"PARTIALLY_FILLED",
                 "originalQuantity":"10","remainingQuantity":"5","limitPrice":"100","currency":"USD",
                 "lastUpdatedAt":"2026-09-26T00:00:01Z"},
                {"orderId":"$sellOrder","instrumentId":"AAPL","side":"SELL","status":"FILLED",
                 "originalQuantity":"5","remainingQuantity":"0","limitPrice":"100","currency":"USD",
                 "lastUpdatedAt":"2026-09-26T00:00:01Z"}
              ]
            }""".trimIndent()
        )
}
