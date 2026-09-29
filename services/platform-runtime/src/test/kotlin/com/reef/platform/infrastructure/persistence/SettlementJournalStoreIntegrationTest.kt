package com.reef.platform.infrastructure.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Disposable migrated schema; no current settlement authority tables are changed. */
class SettlementJournalStoreIntegrationTest {
    private val zero = SettlementJournalStore.ORIGIN_DIGEST

    @Test
    fun laterSourceCannotReuseSettledTradeIdOrCommitSecondTransfer() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "journal-${UUID.randomUUID()}"
            val store = SettlementJournalStore(dataSource, schema)
            store.initialize(stream, 1, "incarnation-1")
            val controls = listOf(control(0, 1, "policy", "POLICY"),
                control(1, 2, "buyer-opening", "OPENING"),
                control(2, 3, "seller-opening", "OPENING"))
            val prefix = controls.fold(zero) { digest, member ->
                SettlementJournalStore.controlPrefixDigest(digest, member)
            }
            val firstWindow = window(stream, 3, 0, 1, listOf(1L))
            val firstResult = result(3, 1, 1, firstWindow.members.single().digest,
                "SETTLED", null, prefix)
            val first = store.append(SettlementJournalBatchProposal(stream, 1, zero, 1,
                "incarnation-1", listOf(firstWindow), controls, listOf(firstResult)))
            val nextWindow = window(stream, 0, 1, 2, listOf(2L))
            val reused = result(0, 2, 1, nextWindow.members.single().digest,
                "SETTLED", null, prefix).copy(tradeId = firstResult.tradeId)
            assertFailsWith<Exception> {
                store.append(SettlementJournalBatchProposal(stream, 2, first.batchDigest, 1,
                    "incarnation-1", listOf(nextWindow), emptyList(), listOf(reused)))
            }
            assertEquals(2L, store.head(stream).nextBatchSequence)
            assertEquals(1L, count(dataSource, schema, "settlement_journal_results", stream))
            assertEquals(0L, count(dataSource, schema, "settlement_journal_batches", stream, 2))
        }
    }

    @Test
    fun appendIsAtomicFencedRetrySafeAndCarriesEmptyCoverage() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "journal-${UUID.randomUUID()}"
            val store = SettlementJournalStore(dataSource, schema)
            val start = store.initialize(stream, 1, "incarnation-1")
            assertEquals(1L, start.nextBatchSequence)
            val policy = control(0, 1, "policy", "POLICY")
            val buyerOpening = control(1, 2, "buyer-opening", "OPENING")
            val sellerOpening = control(2, 3, "seller-opening", "OPENING")
            val controls = listOf(policy, buyerOpening, sellerOpening)
            val firstPrefix = controls.fold(zero) { prefix, control ->
                SettlementJournalStore.controlPrefixDigest(prefix, control)
            }
            val source = window(stream, 3, 0, 2, listOf(1L, 2L))
            val first = result(3, 1, 1, source.members[0].digest, "SETTLED", null, firstPrefix)
            val second = result(3, 2, 1, source.members[1].digest, "BREAK",
                "SECURITY_LEG_FAILED", firstPrefix)
            val proposal = SettlementJournalBatchProposal(stream, 1, zero, 1, "incarnation-1",
                listOf(source), controls, listOf(first, second))

            val receipt = store.append(proposal)
            assertEquals(1L, receipt.batchSequence)
            assertEquals(2, receipt.resultCount)
            assertTrue(!receipt.duplicate)
            assertTrue(store.append(proposal).duplicate)
            assertEquals(2L, store.head(stream).nextBatchSequence)
            assertEquals(firstPrefix, store.head(stream).lastControlDigest)
            assertEquals(2L, count(dataSource, schema, "settlement_journal_results", stream))
            assertEquals(listOf("SETTLED", "BREAK"), outcomes(dataSource, schema, stream))
            assertFailsWith<IllegalStateException> {
                store.append(proposal.copy(results = listOf(first.copy(workflowFacts = "changed"), second)))
            }

            val funding = control(0, 4, "seller-funding", "FUNDING")
            val secondPrefix = SettlementJournalStore.controlPrefixDigest(firstPrefix, funding)
            val retry = second.copy(decisionStepIndex = 0, attemptNumber = 2, outcome = "SETTLED",
                breakReason = null, boundControlDigest = secondPrefix, fundingControlIds = listOf(funding.id),
                workflowFacts = "retry-workflow")
            val retryProposal = SettlementJournalBatchProposal(stream, 2, receipt.batchDigest, 1,
                "incarnation-1", emptyList(), listOf(funding), listOf(retry))
            val injected = SettlementJournalStore(dataSource, schema) { error("injected pre-commit crash") }
            assertFailsWith<IllegalStateException> { injected.append(retryProposal) }
            assertEquals(2L, store.head(stream).nextBatchSequence)
            assertEquals(0L, count(dataSource, schema, "settlement_journal_batches", stream, 2))
            assertEquals(0L, count(dataSource, schema, "settlement_journal_controls", stream, 2))
            assertEquals(0L, count(dataSource, schema, "settlement_journal_results", stream, 2))
            val retryReceipt = store.append(retryProposal)
            assertEquals(3L, store.head(stream).nextBatchSequence)
            assertEquals(3L, count(dataSource, schema, "settlement_journal_results", stream))
            assertTrue(store.append(retryProposal).duplicate)

            val fenced = store.fenceOwner(stream, store.head(stream), 2)
            assertEquals(2L, fenced.ownerEpoch)
            val emptyWindow = window(stream, 0, 2, 4, emptyList())
            val emptyProposal = SettlementJournalBatchProposal(stream, 3, retryReceipt.batchDigest,
                1, "incarnation-1", listOf(emptyWindow), emptyList(), emptyList())
            assertFailsWith<IllegalStateException> { store.append(emptyProposal) }
            val committedEmpty = store.append(emptyProposal.copy(ownerEpoch = 2))
            assertEquals(4L, store.head(stream).nextBatchSequence)
            assertEquals(1L, count(dataSource, schema, "settlement_journal_source_windows", stream, 3))
            assertEquals(0L, count(dataSource, schema, "settlement_journal_results", stream, 3))
            assertTrue(store.append(emptyProposal.copy(ownerEpoch = 2)).duplicate)
            assertFailsWith<IllegalStateException> {
                store.append(SettlementJournalBatchProposal(stream, 4, committedEmpty.batchDigest,
                    2, "wrong-incarnation", listOf(window(stream, 0, 4, 5, emptyList())),
                    emptyList(), emptyList()))
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_results SET workflow_facts = ?
                       WHERE event_stream = ? AND batch_sequence = 1 AND result_index = 0"""
                ).use { statement ->
                    statement.setString(1, "tampered-workflow")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> { store.append(proposal) }
        }
    }

    @Test
    fun badManifestAndChangedControlPayloadCannotCommit() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "journal-${UUID.randomUUID()}"
            val store = SettlementJournalStore(dataSource, schema)
            store.initialize(stream, 1, "incarnation-1")
            val control = control(0, 1, "policy", "POLICY")
            val source = window(stream, 1, 0, 1, listOf(1L))
            val valid = SettlementJournalBatchProposal(stream, 1, zero, 1, "incarnation-1",
                listOf(source), listOf(control), emptyList())
            val outOfOrder = listOf(control.copy(stepIndex = 0, sequence = 2),
                control(1, 1, "next-policy", "POLICY"))
                .map { it.copy(digest = SettlementJournalStore.controlMemberDigest(it)) }
            assertFailsWith<IllegalArgumentException> {
                store.append(valid.copy(sourceWindows = listOf(source.copy(stepIndex = 2)),
                    controls = outOfOrder))
            }
            assertFailsWith<IllegalStateException> {
                store.append(valid.copy(sourceWindows = listOf(source.copy(coverageDigest = "a".repeat(64)))))
            }
            assertFailsWith<IllegalArgumentException> {
                store.append(valid.copy(controls = listOf(control.copy(payload = "changed".toByteArray()))))
            }
            assertEquals(1L, store.head(stream).nextBatchSequence)
            assertEquals(0L, count(dataSource, schema, "settlement_journal_batches", stream))
            store.append(valid)
            assertFailsWith<IllegalStateException> {
                store.append(valid.copy(sourceWindows = listOf(source.copy(
                    members = listOf(source.members[0].copy(digest = "b".repeat(64)))))))
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_source_windows SET coverage_proof_id = ?
                       WHERE event_stream = ? AND batch_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, "changed-proof")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                    statement.setString(1, source.coverageProofId)
                    assertFailsWith<IllegalStateException> { store.append(valid) }
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_controls SET control_kind = ?
                       WHERE event_stream = ? AND control_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, "OPENING")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                    statement.setString(1, control.kind)
                    assertFailsWith<IllegalStateException> { store.append(valid) }
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertTrue(store.append(valid).duplicate)
        }
    }

    @Test
    fun delayedFirstAttemptBindsEarlierOpsRealisticSourceMember() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "journal-${UUID.randomUUID()}"
            val store = SettlementJournalStore(dataSource, schema)
            store.initialize(stream, 1, "incarnation-1")
            val policy = control(0, 1, "policy", "POLICY")
            val buyerOpening = control(1, 2, "buyer-opening", "OPENING")
            val sellerOpening = control(2, 3, "seller-opening", "OPENING")
            val initialControls = listOf(policy, buyerOpening, sellerOpening)
            val source = window(stream, 3, 0, 1, listOf(1L))
            val sourceOnly = SettlementJournalBatchProposal(stream, 1, zero, 1, "incarnation-1",
                listOf(source), initialControls, emptyList())
            val firstReceipt = store.append(sourceOnly)
            assertEquals(0L, count(dataSource, schema, "settlement_journal_results", stream))

            val funding = control(0, 4, "buyer-funding", "FUNDING")
            val prefix = (initialControls + funding).fold(zero) { digest, member ->
                SettlementJournalStore.controlPrefixDigest(digest, member)
            }
            val firstAttempt = result(0, 1, 1, source.members.single().digest,
                "SETTLED", null, prefix).copy(fundingControlIds = listOf(funding.id))
            val proposal = SettlementJournalBatchProposal(stream, 2, firstReceipt.batchDigest, 1,
                "incarnation-1", emptyList(), listOf(funding), listOf(firstAttempt))
            assertFailsWith<IllegalStateException> {
                store.append(proposal.copy(results = listOf(firstAttempt.copy(
                    sourceMemberDigest = "f".repeat(64)))))
            }
            assertEquals(2L, store.head(stream).nextBatchSequence)
            assertEquals(1, store.append(proposal).resultCount)
            assertTrue(store.append(proposal).duplicate)
            assertEquals(1L, count(dataSource, schema, "settlement_journal_results", stream))
        }
    }

    @Test
    fun multipleCrossBatchRetriesUseOnePriorAttemptLookup() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "journal-${UUID.randomUUID()}"
            val store = SettlementJournalStore(dataSource, schema)
            store.initialize(stream, 1, "incarnation-1")
            val controls = listOf(control(0, 1, "policy", "POLICY"),
                control(1, 2, "buyer-opening", "OPENING"),
                control(2, 3, "seller-opening", "OPENING"))
            val firstPrefix = controls.fold(zero) { digest, control ->
                SettlementJournalStore.controlPrefixDigest(digest, control)
            }
            val source = window(stream, 3, 0, 2, listOf(1L, 2L))
            val breaks = source.members.map { member ->
                result(3, member.streamSequence, 1, member.digest, "BREAK", "CASH_LEG_FAILED",
                    firstPrefix)
            }
            val first = store.append(SettlementJournalBatchProposal(stream, 1, zero, 1,
                "incarnation-1", listOf(source), controls, breaks))
            val funding = control(0, 4, "buyer-funding", "FUNDING")
            val retryPrefix = SettlementJournalStore.controlPrefixDigest(firstPrefix, funding)
            val retries = breaks.map { it.copy(decisionStepIndex = 0, attemptNumber = 2,
                outcome = "SETTLED", breakReason = null, boundControlDigest = retryPrefix,
                fundingControlIds = listOf(funding.id), workflowFacts = "retry-${it.tradeId}") }
            val retryProposal = SettlementJournalBatchProposal(stream, 2, first.batchDigest, 1,
                "incarnation-1", emptyList(), listOf(funding), retries)
            assertEquals(2, store.append(retryProposal).resultCount)
            assertTrue(store.append(retryProposal).duplicate)
            assertEquals(4L, count(dataSource, schema, "settlement_journal_results", stream))
        }
    }

    private fun control(step: Int, sequence: Long, id: String, kind: String): SettlementJournalControl {
        val initial = SettlementJournalControl(step, sequence, id, kind, 1,
            "{\"id\":\"$id\"}".toByteArray(), zero)
        return initial.copy(digest = SettlementJournalStore.controlMemberDigest(initial))
    }

    private fun window(stream: String, step: Int, from: Long, through: Long,
        positions: List<Long>): SettlementJournalSourceWindow {
        val initial = SettlementJournalSourceWindow(step, "generation-1", 0, from, through,
            "source-proof-$from-$through", zero,
            positions.map { SettlementJournalSourceMember(it, "%064x".format(it)) })
        return initial.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(stream, initial))
    }

    private fun result(step: Int, sequence: Long, attempt: Int, memberDigest: String,
        outcome: String, reason: String?, boundDigest: String) = SettlementJournalResult(
        step, "trade-$sequence", attempt, "generation-1", 0, sequence, 0, memberDigest,
        "event-$sequence", "run", "session", "buyer", "buyer-account", "seller",
        "seller-account", "USD", "AAPL", BigDecimal("100"), BigDecimal.ONE,
        Instant.parse("2026-09-28T00:00:00.123456789Z"), "policy",
        listOf("buyer-opening", "seller-opening"), emptyList(), boundDigest,
        outcome, reason, "exact-workflow-$sequence-$attempt"
    )

    private fun count(dataSource: DataSource, schema: String, table: String, stream: String,
        batch: Long? = null): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $schema.$table WHERE event_stream = ?" +
            if (batch == null) "" else " AND batch_sequence = ?").use { statement ->
            statement.setString(1, stream)
            if (batch != null) statement.setLong(2, batch)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun outcomes(dataSource: DataSource, schema: String, stream: String): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT outcome FROM $schema.settlement_journal_results " +
                "WHERE event_stream = ? ORDER BY batch_sequence, result_index").use { statement ->
                statement.setString(1, stream)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    private fun withMigratedSchema(dataSource: DataSource, run: (String) -> Unit) {
        val schema = "journal_test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            val migration = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("scripts/dev/db/migrations/settlement/0012_settlement_journal.sql") }
                .first(Files::exists)
            val sql = Files.readString(migration).lineSequence()
                .filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .replace("settlement.", "$schema.")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    sql.split(';').map(String::trim).filter(String::isNotBlank)
                        .forEach(statement::execute)
                }
            }
            run(schema)
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun dataSourceOrSkip(): DataSource {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        return RuntimeDataSources.dataSource(url, user, password, "settlement-journal-store")
    }
}
