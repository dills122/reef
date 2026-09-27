package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs against the migrated settlement schema in the schema-placement CI job. */
class SettlementCanonicalIntakeStoreIntegrationTest {
    @Test
    fun tradeOwnershipReceiptsAndFrontierCommitTogetherAndReplayChecksPayload() {
        val store = storeOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "settlement-test-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        val maker = "maker-$token"
        val taker = "taker-$token"
        val verifier = CanonicalSourceCoverageVerifier()
        val first = verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 0, 1,
            listOf(source(stream, 1, maker, makerSubmit(maker, run, session))))
        val secondOutcome = source(stream, 2, taker, takerSubmit(taker, maker, run, session))
        val second = verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 1, 2,
            listOf(secondOutcome))

        try {
            assertEquals(0L, store.lastCommittedSequence(stream, 0, generation))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(first))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(second))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(second))
            assertEquals(2L, store.lastCommittedSequence(stream, 0, generation))
            assertEquals(1, SettlementBoundedObligationStore(dataSource())
                .readNextWindow(stream, 0, generation, maxSourcePositions = 2)?.trades?.size)
            connection().use { connection ->
                connection.prepareStatement(
                    """SELECT run_id, venue_session_id, buyer_participant_id, seller_participant_id,
                              buyer_account_id, seller_account_id, quantity_units, price
                       FROM settlement.canonical_trade_intake WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(run, rows.getString(1))
                        assertEquals(session, rows.getString(2))
                        assertEquals("participant-$taker", rows.getString(3))
                        assertEquals("participant-$maker", rows.getString(4))
                        assertEquals("account-$taker", rows.getString(5))
                        assertEquals("account-$maker", rows.getString(6))
                        assertEquals("2", rows.getString(7))
                        assertEquals("100", rows.getString(8))
                        check(!rows.next())
                    }
                }
                assertEquals(2L, count(connection, "settlement.canonical_intake_receipts", stream))
                assertEquals(2L, count(connection, "settlement.canonical_intake_coverage", stream))
            }
            val changed = secondOutcome.copy(resultPayloadJson = secondOutcome.resultPayloadJson
                .replace("trade-event-$taker", "different-trade-event-$taker"))
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 1, 2,
                    listOf(changed)))
            }
            val anotherTaker = "another-taker-$token"
            val reusedEvent = source(stream, 3, anotherTaker, takerSubmit(anotherTaker, maker, run, session)
                .replace("trade-event-$anotherTaker", "trade-event-$taker"))
            assertFailsWith<Exception> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 2, 3,
                    listOf(reusedEvent)))
            }
            assertEquals(2L, store.lastCommittedSequence(stream, 0, generation))
            connection().use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement.canonical_trade_intake SET buyer_account_id = 'tampered' WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> { store.apply(second) }
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, "new-generation", 2, 3,
                    listOf(source(stream, 3, "other-$token", makerSubmit("other-$token", run, session)))))
            }
        } finally {
            clean(stream)
        }
    }

    @Test
    fun missingOrFutureMakerOwnershipRollsBackIntakeAndFrontier() {
        val store = storeOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "settlement-test-$token"
        val generation = "generation-$token"
        val maker = "maker-$token"
        val taker = "taker-$token"
        val run = "run-$token"
        val session = "session-$token"
        val verifier = CanonicalSourceCoverageVerifier()
        val takerOutcome = source(stream, 1, taker, takerSubmit(taker, maker, run, session))
        try {
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 0, 1,
                    listOf(takerOutcome)))
            }
            assertEquals(0L, store.lastCommittedSequence(stream, 0, generation))
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 0, 2,
                    listOf(takerOutcome, source(stream, 2, maker, makerSubmit(maker, run, session)))))
            }
            assertEquals(0L, store.lastCommittedSequence(stream, 0, generation))
            connection().use { connection ->
                assertEquals(0L, count(connection, "settlement.canonical_trade_intake", stream))
                assertEquals(0L, count(connection, "settlement.canonical_order_directory", stream))
                assertEquals(0L, count(connection, "settlement.canonical_intake_receipts", stream))
            }
            val makerWindow = verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 0, 1,
                listOf(source(stream, 1, maker, makerSubmit(maker, run, session))))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(makerWindow))
            val negative = source(stream, 2, taker, takerSubmit(taker, maker, run, session)
                .replace("\"quantityUnits\":\"2\"", "\"quantityUnits\":\"-2\""))
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 1, 2,
                    listOf(negative)))
            }
            val malformedPrice = source(stream, 2, taker, takerSubmit(taker, maker, run, session)
                .replace("\"executionPrice\":\"100\"", "\"executionPrice\":\"invalid\"")
                .replace("\"price\":\"100\"", "\"price\":\"invalid\""))
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 1, 2,
                    listOf(malformedPrice)))
            }
            assertFailsWith<IllegalArgumentException> {
                SettlementCanonicalIntakeStore(dataSource(), maxWindowEffects = 5).apply(
                    verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 1, 2,
                        listOf(source(stream, 2, taker, takerSubmit(taker, maker, run, session))))
                )
            }
            assertEquals(1L, store.lastCommittedSequence(stream, 0, generation))
            connection().use { connection ->
                assertEquals(0L, count(connection, "settlement.canonical_trade_intake", stream))
            }
        } finally {
            clean(stream)
        }
    }

    @Test
    fun crossPartitionOwnerRequiresCausalProof() {
        val store = storeOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "settlement-test-$token"
        val generation = "generation-$token"
        val maker = "maker-$token"
        val taker = "taker-$token"
        val run = "run-$token"
        val session = "session-$token"
        val verifier = CanonicalSourceCoverageVerifier()
        val partitionOneOrigin = CanonicalStreamPosition.origin(1)
        try {
            val makerSource = source(stream, partitionOneOrigin + 1, maker, makerSubmit(maker, run, session))
                .copy(partitionId = 1)
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(verifier.verify(
                SettlementCanonicalIntakeStore.CONSUMER, stream, 1, generation,
                partitionOneOrigin, partitionOneOrigin + 1, listOf(makerSource)
            )))
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(SettlementCanonicalIntakeStore.CONSUMER, stream, 0, generation, 0, 1,
                    listOf(source(stream, 1, taker, takerSubmit(taker, maker, run, session)))))
            }
            assertEquals(0L, store.lastCommittedSequence(stream, 0, generation))
        } finally {
            clean(stream)
        }
    }

    private fun storeOrSkip(): SettlementCanonicalIntakeStore {
        assumeTrue(System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST") != null &&
            System.getenv("SETTLEMENT_POSTGRES_USER_TEST") != null &&
            System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST") != null,
            "migrated dedicated settlement PostgreSQL test database is required")
        return SettlementCanonicalIntakeStore(dataSource())
    }

    private fun dataSource() = RuntimeDataSources.dataSource(
        System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST"),
        System.getenv("SETTLEMENT_POSTGRES_USER_TEST"),
        System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST"), "settlement-intake-integration"
    )

    private fun connection() = dataSource().connection

    private fun count(connection: java.sql.Connection, table: String, stream: String): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE event_stream = ?").use { statement ->
            statement.setString(1, stream)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }

    private fun clean(stream: String) {
        connection().use { connection ->
            listOf("canonical_trade_intake", "canonical_order_directory", "canonical_intake_receipts",
                "canonical_intake_coverage", "canonical_intake_frontiers").forEach { table ->
                connection.prepareStatement("DELETE FROM settlement.$table WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun source(stream: String, sequence: Long, orderId: String, result: String) = CanonicalOutcomeSource(
        eventStream = stream, partitionId = 0, streamSequence = sequence, batchId = "batch-$sequence",
        commandId = "command-$sequence", commandType = "SubmitOrder", payloadHash = "hash-$sequence",
        instrumentId = "AAPL", orderId = orderId, resultStatus = "accepted", resultPayloadJson = result
    )

    private fun makerSubmit(maker: String, run: String, session: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accept-$maker","orderId":"$maker","occurredAt":"2026-09-26T00:00:00Z"},
         "acceptedOrder":{"orderId":"$maker","engineOrderId":"engine-$maker","runId":"$run",
         "venueSessionId":"$session","instrumentId":"AAPL","participantId":"participant-$maker",
         "accountId":"account-$maker","side":"SELL","orderType":"LIMIT","quantityUnits":"5",
         "limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-26T00:00:00Z"},
         "orderStates":[{"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"ACCEPTED",
         "originalQuantity":"5","remainingQuantity":"5","limitPrice":"100","currency":"USD",
         "lastUpdatedAt":"2026-09-26T00:00:00Z"}]}
    """.trimIndent()

    private fun takerSubmit(taker: String, maker: String, run: String, session: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accept-$taker","orderId":"$taker","occurredAt":"2026-09-26T00:00:01Z"},
         "acceptedOrder":{"orderId":"$taker","engineOrderId":"engine-$taker","runId":"$run",
         "venueSessionId":"$session","instrumentId":"AAPL","participantId":"participant-$taker",
         "accountId":"account-$taker","side":"BUY","orderType":"LIMIT","quantityUnits":"2",
         "limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-26T00:00:01Z"},
         "executions":[
           {"eventId":"exec-buy-$taker","executionId":"trade-$taker-buy","orderId":"$taker","instrumentId":"AAPL","quantityUnits":"2","executionPrice":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"TAKER"},
           {"eventId":"exec-sell-$taker","executionId":"trade-$taker-sell","orderId":"$maker","instrumentId":"AAPL","quantityUnits":"2","executionPrice":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"MAKER"}],
         "trades":[{"eventId":"trade-event-$taker","tradeId":"trade-$taker","executionId":"trade-$taker",
         "buyOrderId":"$taker","sellOrderId":"$maker","instrumentId":"AAPL","quantityUnits":"2",
         "price":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z"}],
         "orderStates":[
           {"orderId":"$taker","instrumentId":"AAPL","side":"BUY","status":"FILLED","originalQuantity":"2",
           "remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:01Z"},
           {"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"PARTIALLY_FILLED","originalQuantity":"5",
           "remainingQuantity":"3","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:01Z"}]}
    """.trimIndent()
}
