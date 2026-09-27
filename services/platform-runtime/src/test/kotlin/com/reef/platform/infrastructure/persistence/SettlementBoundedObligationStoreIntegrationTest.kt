package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.PostMatchSettlementObligationWorker
import com.reef.platform.application.settlement.PostTradeProfileSelection
import com.reef.platform.application.settlement.PostTradeProfileSelectionSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Exercises migrated runtime authority and dedicated settlement target. */
class SettlementBoundedObligationStoreIntegrationTest {
    @Test
    fun missingIntakeCoverageCannotAdvanceObligationFrontier() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "obligation-coverage-$token"
        val generation = "generation-$token"
        try {
            seed(target, stream, generation, "run-$token", "session-$token")
            target.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM settlement.canonical_intake_coverage WHERE event_stream = ?"
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            assertFailsWith<IllegalStateException> {
                SettlementBoundedObligationStore(target).readNextWindow(stream, 0, generation)
            }
        } finally { cleanTarget(target, stream) }
    }

    @Test
    fun workerShrinksTradeFanoutAndKeepsAdvancingEmptyRanges() {
        val (source, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "obligation-worker-$token"
        val generation = PostMatchSourceCatalog(source).generation()
        try {
            seed(target, stream, generation, "run-$token", "session-$token",
                tradeCount = 2, spreadTrades = true)
            val worker = PostMatchSettlementObligationWorker(
                PostMatchSourceCatalog(source), SettlementObligationPolicySource(source),
                SettlementBoundedObligationStore(target), stream, listOf(0),
                batchSize = 3, maxTrades = 1, pollMs = 50
            )
            assertEquals(PostMatchSettlementObligationWorker.Progress(0, true), worker.processOnceProgress())
            assertEquals(PostMatchSettlementObligationWorker.Progress(1, true), worker.processOnceProgress())
            assertEquals(PostMatchSettlementObligationWorker.Progress(1, true), worker.processOnceProgress())
            assertEquals(PostMatchSettlementObligationWorker.Progress(0, false), worker.processOnceProgress())
        } finally { cleanTarget(target, stream) }
    }

    @Test
    fun emptyRangesTradeObligationsReplayAndDriftAreAtomic() {
        val (source, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "obligation-test-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        val profile = "profile-$token"
        val store = SettlementBoundedObligationStore(target)
        val policies = SettlementObligationPolicySource(source)
        val key = SettlementPolicyKey(run, session)
        try {
            source.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO admin.post_trade_profiles(profile_id, mode, settlement_cycle,
                       netting_mode, ledger_posting_mode, policy_version) VALUES (?, 'instant-post-trade',
                       'T+0', 'gross-or-microbatch', 'near-instant-finality', 7)"""
                ).use { statement -> statement.setString(1, profile); statement.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO runtime.reference_scenario_runs(scenario_run_id, post_trade_profile_id) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, run); statement.setString(2, profile); statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO runtime.reference_venue_sessions(venue_session_id, post_trade_profile_id) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, session); statement.setString(2, profile); statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO runtime.reference_scenario_runs(scenario_run_id, post_trade_profile_id) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, "unknown-$token")
                    statement.setString(2, "missing-$token")
                    statement.executeUpdate()
                }
            }
            seed(target, stream, generation, run, session)
            assertEquals(SettlementPolicySnapshot(PostTradeProfileSelection(profile, 7, "instant-post-trade",
                PostTradeProfileSelectionSource.ScenarioRun), "T+0", "gross-or-microbatch",
                "near-instant-finality"), policies.resolve(setOf(key)).getValue(key))
            assertEquals(PostTradeProfileSelectionSource.VenueSession,
                policies.resolve(setOf(SettlementPolicyKey("other-$token", session)))
                    .getValue(SettlementPolicyKey("other-$token", session)).selection.source)
            assertFailsWith<IllegalArgumentException> {
                policies.resolve(setOf(SettlementPolicyKey("unknown-$token", session)))
            }

            val empty = requireNotNull(store.readNextWindow(stream, 0, generation, maxSourcePositions = 1))
            assertEquals(1L, empty.throughInclusiveSequence)
            assertEquals(0, empty.trades.size)
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(empty, emptyMap()))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(empty, emptyMap()))

            val trade = requireNotNull(store.readNextWindow(stream, 0, generation, maxSourcePositions = 1))
            assertEquals(2L, trade.throughInclusiveSequence)
            assertEquals(1, trade.trades.size)
            val selections = policies.resolve(setOf(key))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(trade, selections))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(trade, selections))
            target.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement.canonical_settlement_obligations SET buyer_account_id = 'tampered' WHERE event_stream = ?"
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            assertFailsWith<IllegalStateException> { store.apply(trade, selections) }
            target.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement.canonical_settlement_obligations SET buyer_account_id = ? WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, "buyer-account-0-$stream")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT quantity_units, cash_amount, post_trade_profile_id, post_trade_policy_version,
                              post_trade_mode, status FROM settlement.canonical_settlement_obligations
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals("2", rows.getBigDecimal(1).toPlainString())
                        assertEquals("200", rows.getBigDecimal(2).toPlainString())
                        assertEquals(profile, rows.getString(3))
                        assertEquals(7, rows.getInt(4))
                        assertEquals("instant-post-trade", rows.getString(5))
                        assertEquals("PENDING", rows.getString(6))
                        check(!rows.next())
                    }
                }
            }
            assertFailsWith<IllegalStateException> {
                store.apply(trade, selections.mapValues { (_, value) ->
                    value.copy(selection = value.selection.copy(policyVersion = 8))
                })
            }
            source.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE admin.post_trade_profiles SET ledger_posting_mode = 'changed' WHERE profile_id = ?"
                ).use { statement -> statement.setString(1, profile); statement.executeUpdate() }
            }
            assertFailsWith<IllegalStateException> { store.apply(trade, policies.resolve(setOf(key))) }
            assertEquals(PostMatchApplyResult.APPLIED,
                store.apply(requireNotNull(store.readNextWindow(stream, 0, generation, maxSourcePositions = 1)), emptyMap()))
            assertEquals(null, store.readNextWindow(stream, 0, generation))
            target.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_stream_sequence FROM settlement.canonical_obligation_frontiers WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(3L, rows.getLong(1)) }
                }
            }
        } finally {
            cleanTarget(target, stream)
            source.connection.use { connection ->
                connection.prepareStatement("DELETE FROM runtime.reference_scenario_runs WHERE scenario_run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM runtime.reference_scenario_runs WHERE scenario_run_id = ?")
                    .use { it.setString(1, "unknown-$token"); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM runtime.reference_venue_sessions WHERE venue_session_id = ?")
                    .use { it.setString(1, session); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM admin.post_trade_profiles WHERE profile_id = ?")
                    .use { it.setString(1, profile); it.executeUpdate() }
            }
        }
    }

    @Test
    fun tradeCapAndGenerationMismatchStopProgress() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "obligation-test-$token"
        val generation = "generation-$token"
        val store = SettlementBoundedObligationStore(target)
        try {
            seed(target, stream, generation, "run-$token", "session-$token", tradeCount = 2)
            assertFailsWith<SettlementObligationWindowTooLarge> {
                store.readNextWindow(stream, 0, generation, maxSourcePositions = 3, maxTrades = 1)
            }
            val first = requireNotNull(store.readNextWindow(stream, 0, generation, maxSourcePositions = 1))
            assertEquals(0, first.trades.size)
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(first, emptyMap()))
            assertFailsWith<IllegalStateException> {
                store.readNextWindow(stream, 0, "other-generation", maxSourcePositions = 1)
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement.canonical_trade_intake SET execution_id = 'tampered' WHERE event_stream = ? AND effect_ordinal = 0"
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            assertFailsWith<IllegalStateException> {
                store.readNextWindow(stream, 0, generation, maxSourcePositions = 1)
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement.canonical_trade_intake SET execution_id = ? WHERE event_stream = ? AND effect_ordinal = 0"
                ).use { statement ->
                    statement.setString(1, "execution-0-$stream")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM settlement.canonical_trade_intake WHERE event_stream = ? AND effect_ordinal = 0"
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            assertFailsWith<IllegalStateException> {
                store.readNextWindow(stream, 0, generation, maxSourcePositions = 1)
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_stream_sequence FROM settlement.canonical_obligation_frontiers WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(1L, rows.getLong(1)) }
                }
            }
        } finally { cleanTarget(target, stream) }
    }

    private fun databasesOrSkip(): Pair<javax.sql.DataSource, javax.sql.DataSource> {
        val sourceUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")
        val targetUrl = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        assumeTrue(sourceUrl != null && targetUrl != null && sourceUrl != targetUrl &&
            System.getenv("RUNTIME_POSTGRES_USER_TEST") != null &&
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") != null &&
            System.getenv("SETTLEMENT_POSTGRES_USER_TEST") != null &&
            System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST") != null,
            "migrated runtime and dedicated settlement PostgreSQL test databases are required")
        return RuntimeDataSources.dataSource(sourceUrl, System.getenv("RUNTIME_POSTGRES_USER_TEST"),
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST"), "obligation-source-test") to
            RuntimeDataSources.dataSource(targetUrl, System.getenv("SETTLEMENT_POSTGRES_USER_TEST"),
                System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST"), "obligation-target-test")
    }

    private fun seed(target: javax.sql.DataSource, stream: String, generation: String, run: String,
        session: String, tradeCount: Int = 1, spreadTrades: Boolean = false) {
        target.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_intake_frontiers(event_stream, partition_id,
                   source_generation, last_stream_sequence) VALUES (?, 0, ?, 3)"""
            ).use { statement -> statement.setString(1, stream); statement.setString(2, generation); statement.executeUpdate() }
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_intake_coverage(event_stream, partition_id,
                   source_generation, from_exclusive_sequence, through_inclusive_sequence,
                   source_member_count, source_digest) VALUES (?, 0, ?, 0, 3, 3, ?)"""
            ).use { statement ->
                statement.setString(1, stream); statement.setString(2, generation)
                statement.setString(3, "source-$stream")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_trade_intake(event_stream, source_generation, partition_id,
                   stream_sequence, effect_ordinal, event_id, trade_id, execution_id, run_id, venue_session_id,
                   buy_order_id, sell_order_id, buyer_participant_id, seller_participant_id,
                   buyer_account_id, seller_account_id, instrument_id, quantity_units, price, currency, occurred_at_text)
                   VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AAPL', '2', '100', 'USD',
                   '2026-09-27T00:00:00Z')"""
            ).use { statement ->
                repeat(tradeCount) { ordinal ->
                    statement.setString(1, stream); statement.setString(2, generation)
                    statement.setLong(3, if (spreadTrades) (ordinal + 2).toLong() else 2L)
                    statement.setInt(4, ordinal); statement.setString(5, "event-$ordinal-$stream")
                    statement.setString(6, "trade-$ordinal-$stream"); statement.setString(7, "execution-$ordinal-$stream")
                    statement.setString(8, run); statement.setString(9, session)
                    statement.setString(10, "buy-$ordinal-$stream"); statement.setString(11, "sell-$ordinal-$stream")
                    statement.setString(12, "buyer-$ordinal-$stream"); statement.setString(13, "seller-$ordinal-$stream")
                    statement.setString(14, "buyer-account-$ordinal-$stream")
                    statement.setString(15, "seller-account-$ordinal-$stream")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            val intakeTrades = (0 until tradeCount).map { ordinal -> SettlementIntakeTrade(
                streamSequence = if (spreadTrades) (ordinal + 2).toLong() else 2L,
                effectOrdinal = ordinal,
                tradeId = "trade-$ordinal-$stream", eventId = "event-$ordinal-$stream",
                executionId = "execution-$ordinal-$stream",
                buyOrderId = "buy-$ordinal-$stream", sellOrderId = "sell-$ordinal-$stream",
                runId = run, venueSessionId = session,
                buyerParticipantId = "buyer-$ordinal-$stream",
                sellerParticipantId = "seller-$ordinal-$stream",
                buyerAccountId = "buyer-account-$ordinal-$stream",
                sellerAccountId = "seller-account-$ordinal-$stream",
                instrumentId = "AAPL", quantityUnits = "2", price = "100", currency = "USD",
                occurredAt = "2026-09-27T00:00:00Z"
            ) }
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_intake_receipts(event_stream, source_generation,
                   partition_id, stream_sequence, batch_id, command_id, command_payload_hash,
                   result_digest, effect_count, trade_count, trade_digest)
                   VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                (1L..3L).forEach { sequence ->
                    val members = intakeTrades.filter { it.streamSequence == sequence }
                    statement.setString(1, stream); statement.setString(2, generation)
                    statement.setLong(3, sequence); statement.setString(4, "batch-$sequence")
                    statement.setString(5, "command-$sequence")
                    statement.setString(6, "payload-$sequence")
                    statement.setString(7, "result-$sequence")
                    statement.setInt(8, members.size); statement.setInt(9, members.size)
                    statement.setString(10, SettlementIntakeManifest.digest(members))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun cleanTarget(target: javax.sql.DataSource, stream: String) {
        target.connection.use { connection ->
            listOf("canonical_settlement_obligations", "canonical_obligation_coverage",
                "canonical_policy_bindings", "canonical_obligation_frontiers", "canonical_trade_intake",
                "canonical_intake_receipts", "canonical_intake_coverage",
                "canonical_intake_frontiers").forEach { table ->
                connection.prepareStatement("DELETE FROM settlement.$table WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream); statement.executeUpdate()
                }
            }
        }
    }
}
