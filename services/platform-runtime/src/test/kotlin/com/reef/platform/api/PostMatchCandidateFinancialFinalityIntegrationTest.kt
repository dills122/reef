package com.reef.platform.api

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementJournalBatchProposal
import com.reef.platform.infrastructure.persistence.SettlementJournalControl
import com.reef.platform.infrastructure.persistence.SettlementJournalProjectionStore
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Committed journal tail stays invisible until a separate finality database acknowledges it. */
class PostMatchCandidateFinancialFinalityIntegrationTest {
    @Test
    fun commitSuccessAndAnchorFailureLeaveFinancialProjectionAtOrigin() {
        val journalDataSource = testDataSource("SETTLEMENT_POSTGRES", "candidate-financial-journal")
        val finalityDataSource = testDataSource("SETTLEMENT_FINALITY_POSTGRES", "candidate-financial-finality")
        assumeTrue(System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST") !=
            System.getenv("SETTLEMENT_FINALITY_POSTGRES_JDBC_URL_TEST"),
            "distinct disposable settlement and finality PostgreSQL databases required")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val journalSchema = "candidate_financial_$suffix"
        val finalitySchema = "candidate_finality_$suffix"
        createSchema(journalDataSource, journalSchema)
        createSchema(finalityDataSource, finalitySchema)
        try {
            applyMigration(journalDataSource, journalSchema,
                "scripts/dev/db/migrations/settlement/0012_settlement_journal.sql", "settlement")
            applyMigration(journalDataSource, journalSchema,
                "scripts/dev/db/migrations/settlement/0013_settlement_journal_projection.sql", "settlement")
            applyMigration(finalityDataSource, finalitySchema,
                "scripts/dev/db/migrations/finality/0001_settlement_finality_authority.sql", "finality")
            val stream = "candidate-financial-${UUID.randomUUID()}"
            val journal = SettlementJournalStore(journalDataSource, journalSchema)
            val projection = SettlementJournalProjectionStore(journalDataSource, journalSchema)
            val finality = PostgresSettlementJournalFinalityAuthority(finalityDataSource, finalitySchema)
            journal.initialize(stream, 1, "incarnation-1")
            val anchor = finality.initializeAtOrigin(stream, journal.head(stream), "a".repeat(64))
            val lease = finality.acquireLease(anchor, journal.head(stream).ownerEpoch)
            journal.fenceOwner(stream, journal.head(stream), lease.epoch)
            val account = ReferenceAccountKey("run", "buyer", "account", "CASH", "USD")
            val opening = ReferenceOpening(controlSequence = 1, controlId = "opening",
                account = account, amount = BigDecimal("7"))
            val initial = SettlementJournalControl(0, 1, "opening", "OPENING", opening.version,
                SettlementJournalControlCodec.encode(opening), SettlementJournalStore.ORIGIN_DIGEST)
            val control = initial.copy(digest = SettlementJournalStore.controlMemberDigest(initial))
            val head = journal.head(stream)
            val receipt = journal.append(SettlementJournalBatchProposal(stream, 1, head.lastBatchDigest,
                head.ownerEpoch, head.incarnationId, emptyList(), listOf(control), emptyList()))
            finalityDataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $finalitySchema.settlement_finality_anchors
                       SET lease_expires_at = clock_timestamp() - interval '1 second'
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                finality.acknowledge(lease, anchor.copy(acknowledgedBatchSequence = 1,
                    acknowledgedBatchDigest = receipt.batchDigest, controlSequence = 1,
                    controlDigest = journal.head(stream).lastControlDigest))
            }
            assertEquals(1L, journal.head(stream).nextBatchSequence - 1)
            assertEquals(0L, finality.read(stream)?.acknowledgedBatchSequence)

            val worker = PostMatchCandidateProjectionWorkers(null, null, journal, projection,
                finality, stream, "generation-a", emptyList(), 1, 10)
            assertEquals(0, worker.processFinancialOnce())
            assertEquals(0L, projection.frontier(stream, "generation-a").batchSequence)
            assertFailsWith<IllegalStateException> {
                CandidateFinancialFinalityGuard.verify(
                    projection.frontier(stream, "generation-a").copy(batchSequence = 1), anchor)
            }
        } finally {
            dropSchema(finalityDataSource, finalitySchema)
            dropSchema(journalDataSource, journalSchema)
        }
    }

    private fun testDataSource(prefix: String, pool: String): DataSource {
        val url = System.getenv("${prefix}_JDBC_URL_TEST")
        val user = System.getenv("${prefix}_USER_TEST")
        val password = System.getenv("${prefix}_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable $prefix PostgreSQL database required")
        return RuntimeDataSources.dataSource(requireNotNull(url), requireNotNull(user),
            requireNotNull(password), pool)
    }

    private fun createSchema(dataSource: DataSource, schema: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
    }

    private fun dropSchema(dataSource: DataSource, schema: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    private fun applyMigration(dataSource: DataSource, schema: String, path: String,
        originalSchema: String) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve(path)) }
        val sql = Files.readString(root.resolve(path))
            .lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .replace("CREATE SCHEMA IF NOT EXISTS $originalSchema;", "")
            .replace("$originalSchema.", "$schema.")
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                if (originalSchema == "finality") statement.execute(sql)
                else sql.split(';').map(String::trim).filter(String::isNotBlank)
                    .forEach(statement::execute)
            }
        }
    }
}
