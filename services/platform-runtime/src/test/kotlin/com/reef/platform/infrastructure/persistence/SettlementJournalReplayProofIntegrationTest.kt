package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettlementJournalReplayProofIntegrationTest {
    @Test
    fun emptyRangeNeedsIndependentProofAndReplayCoversPinnedHead() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val eventStream = "replay-empty-${UUID.randomUUID()}"
            val expectedStream = eventStream
            val store = SettlementJournalStore(dataSource, schema)
            val head = store.initialize(eventStream, 1, "replay-test")
            val initial = SettlementJournalSourceWindow(0, "generation-1", 0,
                0, 1, "external-absence-proof", SettlementJournalStore.ORIGIN_DIGEST, emptyList())
            val window = initial.copy(coverageDigest =
                SettlementJournalStore.sourceCoverageDigest(eventStream, initial))
            store.append(SettlementJournalBatchProposal(eventStream, head.nextBatchSequence,
                head.lastBatchDigest, head.ownerEpoch, head.incarnationId,
                listOf(window), emptyList(), emptyList()))
            val replay = SettlementJournalReplayProof(store)
            val denied = object : SettlementReplaySourceAuthority {
                override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow):
                    VerifiedCanonicalSourceWindow = error("nonempty source read unexpected")
                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow) = false
            }
            assertFailsWith<IllegalStateException> {
                replay.prove(eventStream, denied, { _, _ -> true })
            }
            val allowed = object : SettlementReplaySourceAuthority {
                override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow):
                    VerifiedCanonicalSourceWindow = error("nonempty source read unexpected")
                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow) =
                    eventStream == expectedStream &&
                        window.coverageProofId.startsWith("external-absence-proof")
            }
            val first = replay.prove(eventStream, allowed, { _, _ -> true })
            assertEquals(2L, first.head.nextBatchSequence)
            assertEquals(emptyMap(), first.balances)
            assertEquals(emptySet(), first.outstandingTradeIds)
            val secondInitial = window.copy(fromExclusiveSequence = 1, throughInclusiveSequence = 2,
                coverageProofId = "external-absence-proof-2")
            val second = secondInitial.copy(coverageDigest =
                SettlementJournalStore.sourceCoverageDigest(eventStream, secondInitial))
            val nextHead = store.head(eventStream)
            store.append(SettlementJournalBatchProposal(eventStream, nextHead.nextBatchSequence,
                nextHead.lastBatchDigest, nextHead.ownerEpoch, nextHead.incarnationId,
                listOf(second), emptyList(), emptyList()))
            assertFailsWith<IllegalStateException> {
                replay.prove(eventStream, allowed, { _, _ -> true }, maxBatches = 1)
            }
            assertEquals(3L, replay.prove(eventStream, allowed, { _, _ -> true }).head.nextBatchSequence)
            var fenced = false
            val movingHead = object : SettlementReplaySourceAuthority by allowed {
                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow): Boolean {
                    if (!fenced) {
                        val current = store.head(eventStream)
                        store.fenceOwner(eventStream, current, current.ownerEpoch + 1)
                        fenced = true
                    }
                    return allowed.verifyEmpty(eventStream, window)
                }
            }
            assertFailsWith<IllegalStateException> {
                replay.prove(eventStream, movingHead, { _, _ -> true })
            }
        }
    }

    private fun withMigratedSchema(dataSource: DataSource, run: (String) -> Unit) {
        val schema = "journal_replay_${UUID.randomUUID().toString().replace("-", "")}"
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
        return RuntimeDataSources.dataSource(url, user, password, "settlement-journal-replay")
    }
}
