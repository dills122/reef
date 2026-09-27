package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.PostMatchSettlementObligationWorker
import com.reef.platform.api.PostMatchSettlementTransitionWorker
import com.reef.platform.application.settlement.PostTradeProfileSelection
import com.reef.platform.application.settlement.PostTradeProfileSelectionSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun boundedTransitionPostsDvpAndFailsClosedOnOpeningDrift() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "transition-test-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        try {
            seed(target, stream, generation, run, session)
            target.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO settlement.resource_positions(
                         resource_position_id, scenario_run_id, correlation_id, causation_id,
                         participant_id, account_id, asset_type, asset_id, quantity, occurred_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())"""
                ).use { statement ->
                    listOf(
                        listOf("buyer-0-$stream", "buyer-account-0-$stream", "CASH", "USD", "200"),
                        listOf("seller-0-$stream", "seller-account-0-$stream", "SECURITY", "AAPL", "2")
                    ).forEachIndexed { index, position ->
                        statement.setString(1, "position-$index-$token")
                        statement.setString(2, run)
                        statement.setString(3, "correlation-$token")
                        statement.setString(4, "causation-$token")
                        position.forEachIndexed { offset, value -> statement.setString(offset + 5, value) }
                        statement.addBatch()
                    }
                    assertEquals(2, statement.executeBatch().size)
                }
            }
            val policy = SettlementPolicySnapshot(
                PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                    PostTradeProfileSelectionSource.HardDefault),
                "T+0", "gross-or-microbatch", "near-instant-finality"
            )
            val obligation = SettlementBoundedObligationStore(target)
            val obligationWindow = requireNotNull(obligation.readNextWindow(stream, 0, generation,
                maxSourcePositions = 3))
            assertEquals(PostMatchApplyResult.APPLIED,
                obligation.apply(obligationWindow, mapOf(SettlementPolicyKey(run, session) to policy)))
            val transition = SettlementBoundedTransitionStore(target)
            val window = requireNotNull(transition.readNextWindow(stream, 0, generation,
                maxSourcePositions = 3))
            assertEquals(1, window.obligations.size)
            assertEquals(PostMatchApplyResult.APPLIED, transition.apply(window))
            assertEquals(PostMatchApplyResult.DUPLICATE, transition.apply(window))
            assertEquals(null, transition.readNextWindow(stream, 0, generation))
            target.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE settlement.canonical_account_state SET ledger_delta = 0
                       WHERE event_stream = ? AND participant_id = ? AND asset_type = 'CASH'"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, "buyer-0-$stream")
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> { transition.apply(window) }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE settlement.canonical_account_state SET ledger_delta = -200
                       WHERE event_stream = ? AND participant_id = ? AND asset_type = 'CASH'"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, "buyer-0-$stream")
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """UPDATE settlement.canonical_obligation_coverage SET trade_digest = 'corrupt'
                       WHERE event_stream = ?"""
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            assertFailsWith<IllegalStateException> { transition.apply(window) }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE settlement.canonical_obligation_coverage SET trade_digest = ?
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, obligationWindow.tradeDigest)
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE settlement.canonical_transition_ledger_entries SET quantity = 199
                       WHERE event_stream = ? AND entry_kind = 'BUYER_CASH_DEBIT'"""
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            assertFailsWith<IllegalStateException> { transition.apply(window) }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE settlement.canonical_transition_ledger_entries SET quantity = 200
                       WHERE event_stream = ? AND entry_kind = 'BUYER_CASH_DEBIT'"""
                ).use { statement -> statement.setString(1, stream); assertEquals(1, statement.executeUpdate()) }
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT status FROM settlement.canonical_settlement_obligations
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals("SETTLED", rows.getString(1)) }
                }
                connection.prepareStatement(
                    """SELECT COUNT(*), SUM(CASE WHEN direction = 'DEBIT' AND asset_type = 'CASH'
                         THEN quantity ELSE 0 END), SUM(CASE WHEN direction = 'CREDIT' AND asset_type = 'CASH'
                         THEN quantity ELSE 0 END), SUM(CASE WHEN direction = 'DEBIT' AND asset_type = 'SECURITY'
                         THEN quantity ELSE 0 END), SUM(CASE WHEN direction = 'CREDIT' AND asset_type = 'SECURITY'
                         THEN quantity ELSE 0 END)
                       FROM settlement.canonical_transition_ledger_entries WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(4, rows.getInt(1))
                        assertEquals("200", rows.getBigDecimal(2).toPlainString())
                        assertEquals("200", rows.getBigDecimal(3).toPlainString())
                        assertEquals("2", rows.getBigDecimal(4).toPlainString())
                        assertEquals("2", rows.getBigDecimal(5).toPlainString())
                    }
                }
                connection.prepareStatement(
                    """INSERT INTO settlement.resource_positions(
                         resource_position_id, scenario_run_id, correlation_id, causation_id,
                         participant_id, account_id, asset_type, asset_id, quantity, occurred_at)
                       VALUES (?, ?, ?, ?, ?, ?, 'CASH', 'USD', '1', now())"""
                ).use { statement ->
                    statement.setString(1, "position-drift-$token")
                    statement.setString(2, run)
                    statement.setString(3, "correlation-$token")
                    statement.setString(4, "causation-$token")
                    statement.setString(5, "buyer-0-$stream")
                    statement.setString(6, "buyer-account-0-$stream")
                    statement.executeUpdate()
                }
            }
            assertFailsWith<IllegalStateException> { transition.apply(window) }
            target.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM settlement.resource_positions WHERE resource_position_id = ?"
                ).use { statement ->
                    statement.setString(1, "position-drift-$token")
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertEquals(PostMatchApplyResult.DUPLICATE, transition.apply(window))
            target.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE settlement.resource_positions SET quantity = '100' WHERE resource_position_id = ?"
                ).use { statement ->
                    statement.setString(1, "position-0-$token")
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> { transition.apply(window) }
        } finally {
            cleanTarget(target, stream)
            target.connection.use { connection ->
                connection.prepareStatement("DELETE FROM settlement.resource_positions WHERE scenario_run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM settlement.canonical_resource_openings WHERE run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
            }
        }
    }

    @Test
    fun boundedTransitionOpensBreakWithoutLedgerOnInsufficientCash() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "transition-break-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        try {
            seed(target, stream, generation, run, session)
            target.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO settlement.resource_positions(
                         resource_position_id, scenario_run_id, correlation_id, causation_id,
                         participant_id, account_id, asset_type, asset_id, quantity, occurred_at)
                       VALUES (?, ?, ?, ?, ?, ?, 'CASH', 'USD', '199', now())"""
                ).use { statement ->
                    statement.setString(1, "position-$token")
                    statement.setString(2, run)
                    statement.setString(3, "correlation-$token")
                    statement.setString(4, "causation-$token")
                    statement.setString(5, "buyer-0-$stream")
                    statement.setString(6, "buyer-account-0-$stream")
                    statement.executeUpdate()
                }
            }
            val policy = SettlementPolicySnapshot(
                PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                    PostTradeProfileSelectionSource.HardDefault),
                "T+0", "gross-or-microbatch", "near-instant-finality"
            )
            val obligation = SettlementBoundedObligationStore(target)
            assertEquals(PostMatchApplyResult.APPLIED,
                obligation.apply(requireNotNull(obligation.readNextWindow(stream, 0, generation,
                    maxSourcePositions = 3)), mapOf(SettlementPolicyKey(run, session) to policy)))
            val transition = SettlementBoundedTransitionStore(target)
            val empty = requireNotNull(transition.readNextWindow(stream, 0, generation,
                maxSourcePositions = 1))
            assertEquals(0, empty.obligations.size)
            assertEquals(PostMatchApplyResult.APPLIED, transition.apply(empty))
            val trade = requireNotNull(transition.readNextWindow(stream, 0, generation,
                maxSourcePositions = 1))
            assertEquals(PostMatchApplyResult.APPLIED, transition.apply(trade))
            assertEquals(PostMatchApplyResult.DUPLICATE, transition.apply(trade))
            target.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT o.status, a.outcome, a.break_reason,
                              (SELECT COUNT(*) FROM settlement.canonical_transition_ledger_entries l
                               WHERE l.event_stream = o.event_stream AND l.trade_id = o.trade_id)
                       FROM settlement.canonical_settlement_obligations o
                       JOIN settlement.canonical_transition_attempts a
                         ON a.event_stream = o.event_stream AND a.source_generation = o.source_generation
                        AND a.trade_id = o.trade_id
                       WHERE o.event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals("BREAK", rows.getString(1))
                        assertEquals("BREAK", rows.getString(2))
                        assertEquals("CASH_LEG_FAILED", rows.getString(3))
                        assertEquals(0, rows.getInt(4))
                    }
                }
            }
        } finally {
            cleanTarget(target, stream)
            target.connection.use { connection ->
                connection.prepareStatement("DELETE FROM settlement.resource_positions WHERE scenario_run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM settlement.canonical_resource_openings WHERE run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
            }
        }
    }

    @Test
    fun boundedTransitionSerializesSharedAccountsAcrossPartitions() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "transition-partitions-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        val accountKey = "shared-$token"
        try {
            for (partition in 0..3) seed(target, stream, generation, run, session,
                partitionId = partition, accountKey = accountKey, reverseRoles = partition % 2 == 1)
            target.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO settlement.resource_positions(
                         resource_position_id, scenario_run_id, correlation_id, causation_id,
                         participant_id, account_id, asset_type, asset_id, quantity, occurred_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())"""
                ).use { statement ->
                    listOf(
                        listOf("buyer-0-$accountKey", "buyer-account-0-$accountKey", "CASH", "USD", "400"),
                        listOf("seller-0-$accountKey", "seller-account-0-$accountKey", "CASH", "USD", "400"),
                        listOf("buyer-0-$accountKey", "buyer-account-0-$accountKey", "SECURITY", "AAPL", "4"),
                        listOf("seller-0-$accountKey", "seller-account-0-$accountKey", "SECURITY", "AAPL", "4")
                    ).forEachIndexed { index, position ->
                        statement.setString(1, "position-$index-$token")
                        statement.setString(2, run)
                        statement.setString(3, "correlation-$token")
                        statement.setString(4, "causation-$token")
                        position.forEachIndexed { offset, value -> statement.setString(offset + 5, value) }
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            val policy = SettlementPolicySnapshot(
                PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                    PostTradeProfileSelectionSource.HardDefault),
                "T+0", "gross-or-microbatch", "near-instant-finality"
            )
            val obligation = SettlementBoundedObligationStore(target)
            for (partition in 0..3) {
                val window = requireNotNull(obligation.readNextWindow(stream, partition, generation,
                    maxSourcePositions = 3))
                assertEquals(PostMatchApplyResult.APPLIED,
                    obligation.apply(window, mapOf(SettlementPolicyKey(run, session) to policy)))
            }
            val transition = SettlementBoundedTransitionStore(target)
            val windows = (0..3).map { partition ->
                requireNotNull(transition.readNextWindow(stream, partition, generation,
                    maxSourcePositions = 3))
            }
            val ready = CountDownLatch(4)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val tasks = windows.map { window -> executor.submit<PostMatchApplyResult> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    transition.apply(window)
                } }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                assertEquals(List(4) { PostMatchApplyResult.APPLIED },
                    tasks.map { it.get(20, TimeUnit.SECONDS) })
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT COUNT(*) FROM settlement.canonical_transition_ledger_entries
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(16, rows.getInt(1)) }
                }
                connection.prepareStatement(
                    """SELECT ledger_delta FROM settlement.canonical_account_state
                       WHERE event_stream = ? AND run_id = ? AND participant_id = ?
                         AND account_id = ? AND asset_type = 'CASH' AND asset_id = 'USD'"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, run)
                    statement.setString(3, "buyer-0-$accountKey")
                    statement.setString(4, "buyer-account-0-$accountKey")
                    statement.executeQuery().use { rows ->
                        check(rows.next()); assertEquals("0", rows.getBigDecimal(1).toPlainString())
                    }
                }
            }
            val firstCheckpointPartition = target.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE settlement.canonical_account_checkpoints
                       SET before_delta = before_delta + 1, after_delta = after_delta + 1
                       WHERE event_stream = ? AND run_id = ? AND participant_id = ?
                         AND account_id = ? AND asset_type = 'CASH' AND asset_id = 'USD'
                         AND account_version = 1 RETURNING partition_id"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, run)
                    statement.setString(3, "buyer-0-$accountKey")
                    statement.setString(4, "buyer-account-0-$accountKey")
                    statement.executeQuery().use { rows ->
                        check(rows.next()); rows.getInt(1).also { check(!rows.next()) }
                    }
                }
            }
            assertFailsWith<IllegalStateException> { transition.apply(windows[firstCheckpointPartition]) }
        } finally {
            cleanTarget(target, stream)
            target.connection.use { connection ->
                connection.prepareStatement("DELETE FROM settlement.resource_positions WHERE scenario_run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
                connection.prepareStatement("DELETE FROM settlement.canonical_resource_openings WHERE run_id = ?")
                    .use { it.setString(1, run); it.executeUpdate() }
            }
        }
    }

    @Test
    fun transitionFailureAfterLedgerInsertRollsBackAllFactsAndProgress() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "transition-rollback-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        try {
            seed(target, stream, generation, run, session)
            val policy = SettlementPolicySnapshot(
                PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                    PostTradeProfileSelectionSource.HardDefault),
                "T+0", "gross-or-microbatch", "near-instant-finality"
            )
            val obligations = SettlementBoundedObligationStore(target)
            assertEquals(PostMatchApplyResult.APPLIED, obligations.apply(
                requireNotNull(obligations.readNextWindow(stream, 0, generation, maxSourcePositions = 3)),
                mapOf(SettlementPolicyKey(run, session) to policy)))
            val transition = SettlementBoundedTransitionStore(target) { error("injected after ledger insert") }
            val window = requireNotNull(transition.readNextWindow(stream, 0, generation,
                maxSourcePositions = 3))
            assertFailsWith<IllegalStateException> { transition.apply(window) }
            target.connection.use { connection ->
                listOf("canonical_transition_frontiers", "canonical_transition_coverage",
                    "canonical_transition_attempts", "canonical_transition_ledger_entries",
                    "canonical_account_state", "canonical_account_checkpoints").forEach { table ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM settlement.$table WHERE event_stream = ?"
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows ->
                            check(rows.next()); assertEquals(0, rows.getInt(1), table)
                        }
                    }
                }
                connection.prepareStatement(
                    "SELECT status FROM settlement.canonical_settlement_obligations WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next()); assertEquals("PENDING", rows.getString(1))
                    }
                }
            }
            assertEquals(PostMatchApplyResult.APPLIED, SettlementBoundedTransitionStore(target).apply(window))
        } finally { cleanTarget(target, stream) }
    }

    @Test
    fun documentedShadowRebootstrapClearsCheckpointsBeforeSameGenerationReplay() {
        val (_, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "transition-rebootstrap-$token"
        val generation = "generation-$token"
        val run = "run-$token"
        val session = "session-$token"
        val policy = SettlementPolicySnapshot(
            PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                PostTradeProfileSelectionSource.HardDefault),
            "T+0", "gross-or-microbatch", "near-instant-finality"
        )
        fun runTransition() {
            seed(target, stream, generation, run, session)
            val obligations = SettlementBoundedObligationStore(target)
            assertEquals(PostMatchApplyResult.APPLIED, obligations.apply(
                requireNotNull(obligations.readNextWindow(stream, 0, generation, maxSourcePositions = 3)),
                mapOf(SettlementPolicyKey(run, session) to policy)))
            val transition = SettlementBoundedTransitionStore(target)
            assertEquals(PostMatchApplyResult.APPLIED, transition.apply(
                requireNotNull(transition.readNextWindow(stream, 0, generation, maxSourcePositions = 3))))
        }
        try {
            runTransition()
            val guide = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("docs/LOCAL_CONFIGURATION.md") }
                .first(Files::exists)
            val transaction = Files.readString(guide)
                .substringAfter("Run this transaction on the settlement target:\n\n```sql\n")
                .substringBefore("\n```")
            check(transaction.startsWith("BEGIN;") && transaction.trimEnd().endsWith("COMMIT;"))
            val tables = Regex("settlement\\.[a-z_]+")
                .findAll(transaction).map { it.value }.toList()
            check("settlement.canonical_account_checkpoints" in tables)
            target.connection.use { connection ->
                val oldAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    tables.forEach { table ->
                        connection.prepareStatement("DELETE FROM $table WHERE event_stream = ?").use { statement ->
                            statement.setString(1, stream)
                            statement.executeUpdate()
                        }
                    }
                    connection.commit()
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally { connection.autoCommit = oldAutoCommit }
            }
            runTransition()
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
    fun transitionWorkerShrinksFanoutAndAdvancesEmptyRanges() {
        val (source, target) = databasesOrSkip()
        val token = UUID.randomUUID().toString()
        val stream = "transition-worker-$token"
        val generation = PostMatchSourceCatalog(source).generation()
        val run = "run-$token"
        val session = "session-$token"
        try {
            seed(target, stream, generation, run, session, tradeCount = 2, spreadTrades = true)
            val policy = SettlementPolicySnapshot(
                PostTradeProfileSelection("instant-post-trade-v1", 1, "instant-post-trade",
                    PostTradeProfileSelectionSource.HardDefault),
                "T+0", "gross-or-microbatch", "near-instant-finality"
            )
            val obligation = SettlementBoundedObligationStore(target)
            assertEquals(PostMatchApplyResult.APPLIED,
                obligation.apply(requireNotNull(obligation.readNextWindow(stream, 0, generation,
                    maxSourcePositions = 3)), mapOf(SettlementPolicyKey(run, session) to policy)))
            val worker = PostMatchSettlementTransitionWorker(
                PostMatchSourceCatalog(source), SettlementBoundedTransitionStore(target),
                stream, listOf(0), batchSize = 3, maxObligations = 1, pollMs = 50
            )
            assertEquals(PostMatchSettlementTransitionWorker.Progress(0, true), worker.processOnceProgress())
            assertEquals(PostMatchSettlementTransitionWorker.Progress(1, true), worker.processOnceProgress())
            assertEquals(PostMatchSettlementTransitionWorker.Progress(1, true), worker.processOnceProgress())
            assertEquals(PostMatchSettlementTransitionWorker.Progress(0, false), worker.processOnceProgress())
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
        session: String, tradeCount: Int = 1, spreadTrades: Boolean = false,
        partitionId: Int = 0, accountKey: String = stream, reverseRoles: Boolean = false) {
        val origin = com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(partitionId)
        val tradeKey = if (partitionId == 0) stream else "$stream-p$partitionId"
        fun buyerParticipant(ordinal: Int) = "${if (reverseRoles) "seller" else "buyer"}-$ordinal-$accountKey"
        fun sellerParticipant(ordinal: Int) = "${if (reverseRoles) "buyer" else "seller"}-$ordinal-$accountKey"
        fun buyerAccount(ordinal: Int) = "${if (reverseRoles) "seller" else "buyer"}-account-$ordinal-$accountKey"
        fun sellerAccount(ordinal: Int) = "${if (reverseRoles) "buyer" else "seller"}-account-$ordinal-$accountKey"
        target.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_intake_frontiers(event_stream, partition_id,
                   source_generation, last_stream_sequence) VALUES (?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, stream); statement.setInt(2, partitionId)
                statement.setString(3, generation); statement.setLong(4, origin + 3)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_intake_coverage(event_stream, partition_id,
                   source_generation, from_exclusive_sequence, through_inclusive_sequence,
                   source_member_count, source_digest) VALUES (?, ?, ?, ?, ?, 3, ?)"""
            ).use { statement ->
                statement.setString(1, stream); statement.setInt(2, partitionId)
                statement.setString(3, generation); statement.setLong(4, origin)
                statement.setLong(5, origin + 3); statement.setString(6, "source-$tradeKey")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_trade_intake(event_stream, source_generation, partition_id,
                   stream_sequence, effect_ordinal, event_id, trade_id, execution_id, run_id, venue_session_id,
                   buy_order_id, sell_order_id, buyer_participant_id, seller_participant_id,
                   buyer_account_id, seller_account_id, instrument_id, quantity_units, price, currency, occurred_at_text)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AAPL', '2', '100', 'USD',
                   '2026-09-27T00:00:00Z')"""
            ).use { statement ->
                repeat(tradeCount) { ordinal ->
                    statement.setString(1, stream); statement.setString(2, generation)
                    statement.setInt(3, partitionId)
                    statement.setLong(4, origin + if (spreadTrades) (ordinal + 2).toLong() else 2L)
                    statement.setInt(5, ordinal); statement.setString(6, "event-$ordinal-$tradeKey")
                    statement.setString(7, "trade-$ordinal-$tradeKey"); statement.setString(8, "execution-$ordinal-$tradeKey")
                    statement.setString(9, run); statement.setString(10, session)
                    statement.setString(11, "buy-$ordinal-$tradeKey"); statement.setString(12, "sell-$ordinal-$tradeKey")
                    statement.setString(13, buyerParticipant(ordinal)); statement.setString(14, sellerParticipant(ordinal))
                    statement.setString(15, buyerAccount(ordinal))
                    statement.setString(16, sellerAccount(ordinal))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            val intakeTrades = (0 until tradeCount).map { ordinal -> SettlementIntakeTrade(
                streamSequence = origin + if (spreadTrades) (ordinal + 2).toLong() else 2L,
                effectOrdinal = ordinal,
                tradeId = "trade-$ordinal-$tradeKey", eventId = "event-$ordinal-$tradeKey",
                executionId = "execution-$ordinal-$tradeKey",
                buyOrderId = "buy-$ordinal-$tradeKey", sellOrderId = "sell-$ordinal-$tradeKey",
                runId = run, venueSessionId = session,
                buyerParticipantId = buyerParticipant(ordinal),
                sellerParticipantId = sellerParticipant(ordinal),
                buyerAccountId = buyerAccount(ordinal),
                sellerAccountId = sellerAccount(ordinal),
                instrumentId = "AAPL", quantityUnits = "2", price = "100", currency = "USD",
                occurredAt = "2026-09-27T00:00:00Z"
            ) }
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_intake_receipts(event_stream, source_generation,
                   partition_id, stream_sequence, batch_id, command_id, command_payload_hash,
                   result_digest, effect_count, trade_count, trade_digest)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                (origin + 1..origin + 3).forEach { sequence ->
                    val members = intakeTrades.filter { it.streamSequence == sequence }
                    statement.setString(1, stream); statement.setString(2, generation)
                    statement.setInt(3, partitionId)
                    statement.setLong(4, sequence); statement.setString(5, "batch-$sequence")
                    statement.setString(6, "command-$sequence")
                    statement.setString(7, "payload-$sequence")
                    statement.setString(8, "result-$sequence")
                    statement.setInt(9, members.size); statement.setInt(10, members.size)
                    statement.setString(11, SettlementIntakeManifest.digest(members))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun cleanTarget(target: javax.sql.DataSource, stream: String) {
        target.connection.use { connection ->
            listOf("canonical_account_checkpoints", "canonical_transition_ledger_entries", "canonical_transition_attempts",
                "canonical_account_state", "canonical_transition_coverage", "canonical_transition_frontiers",
                "canonical_settlement_obligations", "canonical_obligation_coverage",
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
