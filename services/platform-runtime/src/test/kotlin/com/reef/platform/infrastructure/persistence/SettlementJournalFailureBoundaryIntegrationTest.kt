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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PostgreSQL failure boundaries in an isolated schema; never touches live settlement tables. */
class SettlementJournalFailureBoundaryIntegrationTest {
    @Test
    fun stagedRowsRollbackBeforeCommitAndFreshOwnerCanRetry() = withJournal { dataSource, schema ->
        val stream = "journal-${UUID.randomUUID()}"
        val store = SettlementJournalStore(dataSource, schema)
        val genesis = store.initialize(stream, 1, "incarnation-1")
        val first = firstBatch(stream)

        val crashingStore = SettlementJournalStore(dataSource, schema) {
            error("simulated crash after inserts and head update, before commit")
        }
        assertFailsWith<IllegalStateException> { crashingStore.append(first) }

        assertEquals(genesis, store.head(stream))
        listOf("settlement_journal_batches", "settlement_journal_source_windows",
            "settlement_journal_controls", "settlement_journal_results").forEach { table ->
            assertEquals(0L, rowCount(dataSource, schema, table, stream), table)
        }

        val receipt = SettlementJournalStore(dataSource, schema).append(first)
        assertFalse(receipt.duplicate)
        assertEquals(1L, rowCount(dataSource, schema, "settlement_journal_batches", stream))
        assertEquals(1L, rowCount(dataSource, schema, "settlement_journal_results", stream))
        assertEquals(2L, store.head(stream).nextBatchSequence)
    }

    @Test
    fun lostReplyOnlyAllowsExactDuplicateAndFenceRejectsStaleFutureAppend() =
        withJournal { dataSource, schema ->
            val stream = "journal-${UUID.randomUUID()}"
            val oldOwner = SettlementJournalStore(dataSource, schema)
            oldOwner.initialize(stream, 1, "incarnation-1")
            val first = firstBatch(stream)

            // Commit succeeds, but caller loses the reply before recording it.
            oldOwner.append(first)
            val recoveredOwner = SettlementJournalStore(dataSource, schema)
            val duplicate = recoveredOwner.append(first)
            assertTrue(duplicate.duplicate)
            assertEquals(1L, duplicate.batchSequence)
            assertEquals(recoveredOwner.preview(first).proposalDigest, duplicate.proposalDigest)
            assertEquals(recoveredOwner.head(stream).lastBatchDigest, duplicate.batchDigest)
            assertEquals(1L, rowCount(dataSource, schema, "settlement_journal_batches", stream))
            assertEquals(1L, rowCount(dataSource, schema, "settlement_journal_results", stream))

            val changed = first.copy(results = listOf(first.results.single().copy(
                workflowFacts = "different-recovered-decision")))
            assertFailsWith<IllegalStateException> { recoveredOwner.append(changed) }

            val oldHead = oldOwner.head(stream)
            val fenced = recoveredOwner.fenceOwner(stream, oldHead, 2)
            assertEquals(2L, fenced.ownerEpoch)
            assertEquals(oldHead.nextBatchSequence, fenced.nextBatchSequence)
            assertEquals(oldHead.lastBatchDigest, fenced.lastBatchDigest)
            assertFailsWith<IllegalStateException> {
                oldOwner.fenceOwner(stream, oldHead, 3)
            }

            val next = SettlementJournalBatchProposal(stream, 2, duplicate.batchDigest, 1,
                "incarnation-1", listOf(emptyWindow(stream)), emptyList(), emptyList())
            assertFailsWith<IllegalStateException> { oldOwner.append(next) }
            assertEquals(2L, recoveredOwner.head(stream).nextBatchSequence)
            assertEquals(1L, rowCount(dataSource, schema, "settlement_journal_batches", stream))

            // A late identical reply remains reconcilable; it cannot create a second result.
            assertTrue(recoveredOwner.append(first).duplicate)
            val newOwnerReceipt = recoveredOwner.append(next.copy(ownerEpoch = 2))
            assertFalse(newOwnerReceipt.duplicate)
            assertEquals(3L, recoveredOwner.head(stream).nextBatchSequence)
            assertEquals(2L, rowCount(dataSource, schema, "settlement_journal_batches", stream))
            assertEquals(1L, rowCount(dataSource, schema, "settlement_journal_results", stream))
        }

    private fun firstBatch(stream: String): SettlementJournalBatchProposal {
        val controls = listOf("policy" to "POLICY", "buyer-opening" to "OPENING",
            "seller-opening" to "OPENING").mapIndexed { index, (id, kind) ->
            val control = SettlementJournalControl(index, index.toLong() + 1, id, kind, 1,
                "{\"id\":\"$id\"}".toByteArray(), SettlementJournalStore.ORIGIN_DIGEST)
            control.copy(digest = SettlementJournalStore.controlMemberDigest(control))
        }
        val prefix = controls.fold(SettlementJournalStore.ORIGIN_DIGEST) { digest, control ->
            SettlementJournalStore.controlPrefixDigest(digest, control)
        }
        val member = SettlementJournalSourceMember(1, "1".padStart(64, '0'))
        val window = SettlementJournalSourceWindow(3, "generation-1", 0, 0, 1,
            "source-proof-0-1", SettlementJournalStore.ORIGIN_DIGEST, listOf(member))
        val source = window.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(stream, window))
        val result = SettlementJournalResult(3, "trade-1", 1, "generation-1", 0, 1, 0,
            member.digest, "event-1", "run-1", "session-1", "buyer", "buyer-account", "seller",
            "seller-account", "USD", "AAPL", BigDecimal("100"), BigDecimal.ONE,
            Instant.parse("2026-09-28T00:00:00Z"), "policy",
            listOf("buyer-opening", "seller-opening"), emptyList(), prefix,
            "SETTLED", null, "exact-workflow-1")
        return SettlementJournalBatchProposal(stream, 1, SettlementJournalStore.ORIGIN_DIGEST,
            1, "incarnation-1", listOf(source), controls, listOf(result))
    }

    private fun emptyWindow(stream: String): SettlementJournalSourceWindow {
        val window = SettlementJournalSourceWindow(0, "generation-1", 0, 1, 2,
            "source-proof-1-2", SettlementJournalStore.ORIGIN_DIGEST, emptyList())
        return window.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(stream, window))
    }

    private fun rowCount(dataSource: DataSource, schema: String, table: String, stream: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $schema.$table WHERE event_stream = ?")
                .use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
                }
        }

    private fun withJournal(run: (DataSource, String) -> Unit) {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        val dataSource = RuntimeDataSources.dataSource(url, user, password, "journal-failure-boundary")
        val schema = "journal_failure_${UUID.randomUUID().toString().replace("-", "")}"
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
            run(dataSource, schema)
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
