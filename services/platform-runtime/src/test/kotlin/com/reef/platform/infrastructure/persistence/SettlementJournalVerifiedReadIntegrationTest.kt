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

/** Recovery read checks use a disposable schema, never the live settlement tables. */
class SettlementJournalVerifiedReadIntegrationTest {
    private val zero = SettlementJournalStore.ORIGIN_DIGEST

    @Test
    fun verifiesHistoricalAndHeadBatchesWithAllMemberKinds() = withStore { store, _, _, stream ->
        val (first, second) = appendTwoBatches(store, stream)
        val historical = store.readVerifiedBatch(stream, 1)
        assertEquals(first.proposalDigest, historical.proposalDigest)
        assertEquals(first.batchDigest, historical.batchDigest)
        assertEquals(3, historical.controls.size)
        assertEquals(1, historical.sourceWindows.size)
        assertEquals(1, historical.results.size)
        assertEquals("SETTLED", historical.results.single().outcome)
        assertEquals("cG9saWN5", historical.controls.first().payloadBase64)
        val latest = store.readVerifiedBatch(stream, 2)
        assertEquals(second.batchDigest, latest.batchDigest)
        assertTrue(latest.sourceWindows.single().members.isEmpty())
        assertTrue(latest.results.isEmpty())
    }

    @Test
    fun mutationOfReplayCopyCannotChangeVerifiedEnvelope() = withStore { store, _, _, stream ->
        appendTwoBatches(store, stream)
        val verified = store.readVerifiedBatch(stream, 1)
        val copy = store.replayProposalCopy(verified)
        copy.controls.first().payload[0] = 'X'.code.toByte()
        assertEquals("cG9saWN5", verified.controls.first().payloadBase64)
        assertTrue(store.replayProposalCopy(verified).controls.first().payload
            .contentEquals("policy".toByteArray()))
        assertFailsWith<IllegalArgumentException> { store.preview(copy) }
        assertFailsWith<UnsupportedOperationException> {
            (verified.controls as MutableList<SettlementJournalVerifiedControl>).clear()
        }
    }

    @Test
    fun rejectsChangedResultAndMissingSourceManifest() = withStore { store, dataSource, schema, stream ->
        appendTwoBatches(store, stream)
        mutate(dataSource, """UPDATE $schema.settlement_journal_results SET workflow_facts = 'changed'
            WHERE event_stream = ? AND batch_sequence = 1""", stream)
        assertFailsWith<IllegalStateException> { store.readVerifiedBatch(stream, 1) }
        mutate(dataSource, """UPDATE $schema.settlement_journal_results SET workflow_facts = 'exact-workflow'
            WHERE event_stream = ? AND batch_sequence = 1""", stream)
        mutate(dataSource, """UPDATE $schema.settlement_journal_controls SET payload = 'changed'::bytea
            WHERE event_stream = ? AND batch_sequence = 1 AND control_index = 0""", stream)
        assertFailsWith<IllegalArgumentException> { store.readVerifiedBatch(stream, 1) }
        mutate(dataSource, """DELETE FROM $schema.settlement_journal_source_windows
            WHERE event_stream = ? AND batch_sequence = 2""", stream)
        assertFailsWith<IllegalStateException> { store.readVerifiedBatch(stream, 2) }
    }

    @Test
    fun rejectsChangedChainAndHead() = withStore { store, dataSource, schema, stream ->
        val (_, second) = appendTwoBatches(store, stream)
        mutate(dataSource, """UPDATE $schema.settlement_journal_heads SET last_batch_digest = '${"f".repeat(64)}'
            WHERE event_stream = ?""", stream)
        assertFailsWith<IllegalStateException> { store.readVerifiedBatch(stream, 2) }
        mutate(dataSource, """UPDATE $schema.settlement_journal_heads SET last_batch_digest = ?
            WHERE event_stream = ?""", second.batchDigest, stream)
        mutate(dataSource, """UPDATE $schema.settlement_journal_batches
            SET previous_digest = '${"f".repeat(64)}' WHERE event_stream = ? AND batch_sequence = 2""", stream)
        assertFailsWith<IllegalStateException> { store.readVerifiedBatch(stream, 1) }
        assertFailsWith<IllegalStateException> { store.readVerifiedBatch(stream, 2) }
    }

    private fun appendTwoBatches(store: SettlementJournalStore, stream: String):
        Pair<SettlementJournalCommitReceipt, SettlementJournalCommitReceipt> {
        store.initialize(stream, 1, "incarnation-1")
        val controls = listOf(control(0, 1, "policy", "POLICY"),
            control(1, 2, "buyer-opening", "OPENING"),
            control(2, 3, "seller-opening", "OPENING"))
        val prefix = controls.fold(zero, SettlementJournalStore::controlPrefixDigest)
        val window = window(stream, 3, 0, 1, listOf(1L))
        val result = SettlementJournalResult(3, "trade-1", 1, "generation-1", 0, 1, 0,
            window.members.single().digest, "event-1", "run", "session", "buyer", "buyer-account",
            "seller", "seller-account", "USD", "AAPL", BigDecimal("100"), BigDecimal.ONE,
            Instant.parse("2026-09-28T00:00:00.123456789Z"), "policy",
            listOf("buyer-opening", "seller-opening"), emptyList(), prefix,
            "SETTLED", null, "exact-workflow")
        val first = store.append(SettlementJournalBatchProposal(stream, 1, zero, 1,
            "incarnation-1", listOf(window), controls, listOf(result)))
        val second = store.append(SettlementJournalBatchProposal(stream, 2, first.batchDigest, 1,
            "incarnation-1", listOf(window(stream, 0, 1, 2, emptyList())),
            emptyList(), emptyList()))
        return first to second
    }

    private fun control(step: Int, sequence: Long, id: String, kind: String): SettlementJournalControl {
        val initial = SettlementJournalControl(step, sequence, id, kind, 1,
            id.toByteArray(), zero)
        return initial.copy(digest = SettlementJournalStore.controlMemberDigest(initial))
    }

    private fun window(stream: String, step: Int, from: Long, through: Long,
        positions: List<Long>): SettlementJournalSourceWindow {
        val initial = SettlementJournalSourceWindow(step, "generation-1", 0, from, through,
            "source-proof-$from-$through", zero,
            positions.map { SettlementJournalSourceMember(it, "%064x".format(it)) })
        return initial.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(stream, initial))
    }

    private fun mutate(dataSource: DataSource, sql: String, vararg values: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun withStore(run: (SettlementJournalStore, DataSource, String, String) -> Unit) {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        val dataSource = RuntimeDataSources.dataSource(url, user, password, "journal-verified-read")
        val schema = "journal_read_${UUID.randomUUID().toString().replace("-", "")}"
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
            run(SettlementJournalStore(dataSource, schema), dataSource, schema,
                "journal-${UUID.randomUUID()}")
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
