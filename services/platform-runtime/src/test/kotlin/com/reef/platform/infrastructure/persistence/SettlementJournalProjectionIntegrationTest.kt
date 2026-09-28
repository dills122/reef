package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
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
import org.junit.jupiter.api.Assumptions.assumeTrue

class SettlementJournalProjectionIntegrationTest {
    private val zero = SettlementJournalStore.ORIGIN_DIGEST
    private val buyerCash = account("buyer", "CASH", "USD")
    private val sellerCash = account("seller", "CASH", "USD")
    private val sellerSecurity = account("seller", "SECURITY", "AAPL")
    private val buyerSecurity = account("buyer", "SECURITY", "AAPL")

    @Test
    fun breakThenFundingRetryProjectsFourEffectsAndAsOfWithoutErasingTrade() {
        withJournal { dataSource, schema, stream, journal ->
            val projection = SettlementJournalProjectionStore(dataSource, schema)
            val policy = control(0, ReferencePolicyActivation(controlSequence = 1,
                controlId = "policy", runId = "run", venueSessionId = "session",
                effectiveAfterSourceFrontiers = emptyMap(), profileId = "instant",
                policyVersion = 1, mode = "instant-post-trade", settlementCycle = "T+0",
                nettingMode = "gross", ledgerPostingMode = "gross", selectionSource = "test"))
            val buyerOpening = control(1, ReferenceOpening(controlSequence = 2,
                controlId = "buyer-cash", account = buyerCash, amount = BigDecimal("100")))
            val sellerOpening = control(2, ReferenceOpening(controlSequence = 3,
                controlId = "seller-security", account = sellerSecurity, amount = BigDecimal.ZERO))
            val controls = listOf(policy, buyerOpening, sellerOpening)
            val prefix = controls.fold(zero, SettlementJournalStore::controlPrefixDigest)
            val window = window(stream, 3)
            val firstResult = result(3, window.members.single().digest, prefix,
                "BREAK", "SECURITY_LEG_FAILED", 1)
            val first = journal.append(SettlementJournalBatchProposal(stream, 1, zero, 1,
                "incarnation-1", listOf(window), controls, listOf(firstResult)))
            projection.initialize(stream, "generation-a")
            val lagging = projection.readAccount(stream, "generation-a", buyerCash)
            assertEquals(0, lagging.frontier.batchSequence)
            assertEquals(1, lagging.frontier.lagBatches)
            assertFailsWith<IllegalStateException> {
                projection.readAccount(stream, "generation-a", buyerCash, requireCurrent = true)
            }
            val verifiedFirst = journal.readVerifiedBatch(stream, 1)
            assertFalse(projection.apply("generation-a", verifiedFirst).duplicate)
            assertTrue(projection.apply("generation-a", verifiedFirst).duplicate)
            assertEquals(BigDecimal("100"), projection.readAccount(
                stream, "generation-a", buyerCash, requireCurrent = true).balance)
            assertEquals("BREAK", projection.readTrade(stream, "generation-a", "run",
                "trade-1", requireCurrent = true).status?.outcome)
            assertEquals(null, projection.readAccount(stream, "generation-a", sellerCash).balance)

            val funding = control(0, ReferenceFunding(controlSequence = 4,
                controlId = "seller-funding", account = sellerSecurity,
                amount = BigDecimal.ONE, retryTradeIds = listOf("trade-1")))
            val secondPrefix = SettlementJournalStore.controlPrefixDigest(prefix, funding)
            val retry = result(0, window.members.single().digest, secondPrefix,
                "SETTLED", null, 2).copy(fundingControlIds = listOf("seller-funding"))
            val second = journal.append(SettlementJournalBatchProposal(stream, 2,
                first.batchDigest, 1, "incarnation-1", emptyList(), listOf(funding), listOf(retry)))
            assertFailsWith<IllegalStateException> {
                projection.readTrade(stream, "generation-a", "run", "trade-1", requireCurrent = true)
            }
            assertFailsWith<IllegalStateException> {
                projection.apply("generation-b", journal.readVerifiedBatch(stream, 2))
            }
            assertFalse(projection.apply("generation-a", journal.readVerifiedBatch(stream, 2)).duplicate)
            assertTrue(projection.apply("generation-a", verifiedFirst).duplicate)
            val buyer = projection.readAccount(stream, "generation-a", buyerCash, requireCurrent = true)
            assertEquals(BigDecimal.ZERO, buyer.balance)
            assertEquals(2, buyer.frontier.batchSequence)
            assertEquals(second.batchDigest, buyer.frontier.batchDigest)
            assertEquals(0, buyer.frontier.lagBatches)
            assertEquals(BigDecimal("100"), projection.readAccount(
                stream, "generation-a", sellerCash).balance)
            assertEquals(BigDecimal.ZERO, projection.readAccount(
                stream, "generation-a", sellerSecurity).balance)
            assertEquals(BigDecimal.ONE, projection.readAccount(
                stream, "generation-a", buyerSecurity).balance)
            val status = projection.readTrade(stream, "generation-a", "run", "trade-1")
            assertEquals(2, status.status?.attemptNumber)
            assertEquals("SETTLED", status.status?.outcome)
            assertEquals(2, status.status?.batchSequence)
            assertEquals(0, status.status?.resultIndex)

            projection.apply("generation-b", verifiedFirst)
            val rebuilt = projection.readTrade(stream, "generation-b", "run", "trade-1")
            assertEquals("BREAK", rebuilt.status?.outcome)
            assertEquals(1, rebuilt.frontier.lagBatches)
            projection.apply("generation-b", journal.readVerifiedBatch(stream, 2))
            assertEquals(BigDecimal.ZERO, projection.readAccount(
                stream, "generation-b", buyerCash, requireCurrent = true).balance)
        }
    }

    @Test
    fun rollbackAndDigestOrIncarnationMismatchLeaveProjectionUnchanged() {
        withJournal { dataSource, schema, stream, journal ->
            val opening = control(0, ReferenceOpening(controlSequence = 1,
                controlId = "opening", account = buyerCash, amount = BigDecimal("7")))
            journal.append(SettlementJournalBatchProposal(stream, 1, zero, 1,
                "incarnation-1", emptyList(), listOf(opening), emptyList()))
            val verified = journal.readVerifiedBatch(stream, 1)
            val failing = SettlementJournalProjectionStore(dataSource, schema,
                beforeCommit = { error("injected projector crash") })
            assertFailsWith<IllegalStateException> { failing.apply("generation-a", verified) }
            val projection = SettlementJournalProjectionStore(dataSource, schema)
            projection.initialize(stream, "generation-a")
            assertEquals(0, projection.readAccount(stream, "generation-a", buyerCash)
                .frontier.batchSequence)
            assertEquals(null, projection.readAccount(stream, "generation-a", buyerCash).balance)
            val changedPayload = opening.copy(payload = SettlementJournalControlCodec.encode(
                ReferenceOpening(controlSequence = 1, controlId = "opening", account = buyerCash,
                    amount = BigDecimal("700"))))
            val alteredControl = changedPayload.copy(
                digest = SettlementJournalStore.controlMemberDigest(changedPayload))
            val alteredProposal = journal.replayProposalCopy(verified).copy(
                controls = listOf(alteredControl))
            assertFailsWith<IllegalStateException> {
                projection.apply("generation-a", SettlementJournalVerifiedBatch(
                    alteredProposal, verified.proposalDigest, verified.batchDigest))
            }
            assertEquals(0, projection.readAccount(stream, "generation-a", buyerCash)
                .frontier.batchSequence)
            assertFailsWith<IllegalStateException> {
                projection.apply("generation-a", SettlementJournalVerifiedBatch(
                    journal.replayProposalCopy(verified), verified.proposalDigest, "f".repeat(64)))
            }
            projection.apply("generation-a", verified)
            assertEquals(BigDecimal("7"), projection.readAccount(
                stream, "generation-a", buyerCash, requireCurrent = true).balance)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_heads SET incarnation_id = ?
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, "changed-incarnation")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                projection.readAccount(stream, "generation-a", buyerCash)
            }
            assertFailsWith<IllegalStateException> { projection.apply("generation-a", verified) }
        }
    }

    private fun account(owner: String, type: String, asset: String) =
        ReferenceAccountKey("run", owner, "$owner-account", type, asset)

    private fun control(step: Int,
        fact: com.reef.platform.application.settlementjournal.ReferenceControl): SettlementJournalControl {
        val kind = when (fact) {
            is ReferencePolicyActivation -> "POLICY"
            is ReferenceOpening -> "OPENING"
            is ReferenceFunding -> "FUNDING"
        }
        val member = SettlementJournalControl(step, fact.controlSequence, fact.controlId, kind,
            fact.version, SettlementJournalControlCodec.encode(fact), zero)
        return member.copy(digest = SettlementJournalStore.controlMemberDigest(member))
    }

    private fun window(stream: String, step: Int): SettlementJournalSourceWindow {
        val initial = SettlementJournalSourceWindow(step, "source-generation", 0, 0, 1,
            "coverage-proof", zero, listOf(SettlementJournalSourceMember(1, "a".repeat(64))))
        return initial.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(stream, initial))
    }

    private fun result(step: Int, memberDigest: String, controlDigest: String,
        outcome: String, reason: String?, attempt: Int): SettlementJournalResult =
        SettlementJournalResult(step, "trade-1", attempt, "source-generation", 0, 1, 0,
            memberDigest, "event-1", "run", "session", "buyer", "buyer-account",
            "seller", "seller-account", "USD", "AAPL", BigDecimal("100"),
            BigDecimal.ONE, Instant.parse("2026-09-28T00:00:00Z"), "policy",
            listOf("buyer-cash", "seller-security"), emptyList(), controlDigest,
            outcome, reason, "exact-workflow-$attempt")

    private fun withJournal(run: (DataSource, String, String, SettlementJournalStore) -> Unit) {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        val dataSource = RuntimeDataSources.dataSource(url, user, password,
            "settlement-journal-projection")
        val schema = "journal_projection_test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            listOf("0012_settlement_journal.sql", "0013_settlement_journal_projection.sql")
                .forEach { filename ->
                    val migration = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                        .map { it.resolve("scripts/dev/db/migrations/settlement/$filename") }
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
                }
            val stream = "journal-${UUID.randomUUID()}"
            val journal = SettlementJournalStore(dataSource, schema)
            journal.initialize(stream, 1, "incarnation-1")
            run(dataSource, schema, stream, journal)
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
