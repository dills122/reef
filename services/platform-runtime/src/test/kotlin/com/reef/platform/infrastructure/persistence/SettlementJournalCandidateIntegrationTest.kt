package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceBreakReason
import com.reef.platform.application.settlementjournal.ReferenceControl
import com.reef.platform.application.settlementjournal.ReferenceCoverageEvidence
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceResult
import com.reef.platform.application.settlementjournal.ReferenceResultKind
import com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreter
import com.reef.platform.application.settlementjournal.ReferenceSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceSourceWindowKey
import com.reef.platform.application.settlementjournal.ReferenceStep
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementAppendReceipt
import com.reef.platform.application.settlementjournal.SettlementDecisionProposal
import com.reef.platform.application.settlementjournal.SettlementEvaluatorHead
import com.reef.platform.application.settlementjournal.SettlementJournalEvaluator
import com.reef.platform.application.settlementjournal.SettlementPreparedInput
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Default-off local vertical proof from existing outcome encoding to journal commit. */
class SettlementJournalCandidateIntegrationTest {
    @Test
    fun canonicalOutcomesSettleAndExplicitFundingRetriesThroughFencedJournal() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val streamId = "journal-candidate-${UUID.randomUUID()}"
            val partition = ReferenceStreamPartition(streamId, "generation-1", 0)
            val sources = listOf(
                maker(streamId, 1, "seller-order-1"),
                taker(streamId, 2, "buyer-1", "seller-order-1"),
                maker(streamId, 3, "seller-order-2"),
                taker(streamId, 4, "buyer-2", "seller-order-2")
            )
            val (verified, sourceReadNanos) = readRetainedSources(dataSource, streamId, sources)
            val retainedSources = verified.outcomes.map { it.source }
            assertEquals(4, retainedSources.size)
            val members = retainedSources.map { source -> SettlementJournalSourceMember(
                source.streamSequence, sourceDigest(source)) }
            val initialWindow = SettlementJournalSourceWindow(4, partition.sourceGeneration,
                partition.partitionId, 0, 4, "fixture-proof", "0".repeat(64), members)
            val window = initialWindow.copy(coverageDigest =
                SettlementJournalStore.sourceCoverageDigest(streamId, initialWindow))
            val evidence = ReferenceCoverageEvidence(proofId = window.coverageProofId,
                digest = window.coverageDigest)
            val referenceWindow = ReferenceSourceWindow(stream = partition,
                fromExclusiveSequence = 0, throughInclusiveSequence = 4,
                outcomes = retainedSources, evidence = evidence)
            val controls = listOf<ReferenceControl>(
                ReferencePolicyActivation(controlSequence = 1, controlId = "policy",
                    runId = "run", venueSessionId = "session", effectiveAfterSourceFrontiers = emptyMap(),
                    profileId = "instant-post-trade-v1", policyVersion = 1,
                    mode = "instant-post-trade", settlementCycle = "T+0", nettingMode = "gross",
                    ledgerPostingMode = "gross-dvp", selectionSource = "fixture"),
                ReferenceOpening(controlSequence = 2, controlId = "buyer-1-opening",
                    account = account("buyer-1", "CASH", "USD"), amount = BigDecimal("50")),
                ReferenceOpening(controlSequence = 3, controlId = "buyer-2-opening",
                    account = account("buyer-2", "CASH", "USD"), amount = BigDecimal("50")),
                ReferenceOpening(controlSequence = 4, controlId = "seller-opening",
                    account = account("seller", "SECURITY", "AAPL"), amount = BigDecimal.ONE)
            )
            val oracle = ReferenceSettlementInterpreter(
                coverageVerifier = { _, _ -> true }, controlVerifier = { _, _ -> true })
            val firstReference = oracle.evaluate(controls.map(ReferenceStep::Control) +
                ReferenceStep.Source(referenceWindow))
            assertEquals(listOf(ReferenceResultKind.SETTLED, ReferenceResultKind.BREAK),
                firstReference.results.map { it.kind })

            val store = SettlementJournalStore(dataSource, schema)
            val initialHead = store.initialize(streamId, 1, "proof-incarnation")
            val mapper = SettlementJournalProposalMapper(store)
            var mapped: SettlementJournalProposalMapper.Mapped? = null
            var durableReceipt: SettlementJournalCommitReceipt? = null
            val evaluator = SettlementJournalEvaluator(SettlementEvaluatorHead(0,
                initialHead.lastBatchDigest, 1, "proof-incarnation"),
                { coverage, _ -> coverage.key == referenceWindow.let {
                    ReferenceSourceWindowKey(it.stream, it.fromExclusiveSequence,
                        it.throughInclusiveSequence)
                } && coverage.evidence == evidence &&
                    coverage.expectedTrades == firstReference.results.map { it.trade } }) { proposal, receipt ->
                val mappedProposal = mapped
                val durable = durableReceipt
                mappedProposal != null && durable != null &&
                    mappedProposal.verifies(proposal, receipt, durable, store)
            }
            val prepared = controls.mapIndexed { index, control ->
                SettlementPreparedInput.Control(index.toLong(), control,
                    firstReference.controlDigests.getValue(control.controlId))
            } + SettlementPreparedInput.SourceCoverage(4,
                ReferenceSourceWindowKey(partition, 0, 4), evidence,
                firstReference.results.map { it.trade }) +
                firstReference.results.map { SettlementPreparedInput.Trade(4, it.trade) }
            val evaluationStart = System.nanoTime()
            val firstDecision = evaluator.prepare(prepared)
            val evaluationNanos = System.nanoTime() - evaluationStart
            val mappingStart = System.nanoTime()
            val firstMapped = mapper.map(streamId, initialHead, firstDecision, prepared, listOf(window))
            val mappingNanos = System.nanoTime() - mappingStart
            val contradictoryDecision = firstDecision.copy(results = firstDecision.results.mapIndexed { index, result ->
                if (index == 0) result.copy(kind = ReferenceResultKind.BREAK,
                    breakReason = ReferenceBreakReason.CASH_LEG_FAILED) else result
            })
            assertFailsWith<IllegalArgumentException> {
                mapper.map(streamId, initialHead, contradictoryDecision, prepared, listOf(window))
            }
            mapped = firstMapped
            val bench = System.getenv("SETTLEMENT_JOURNAL_PROOF_BENCH") == "1"
            val beforeSize = if (bench) journalSize(dataSource, schema) else null
            durableReceipt = store.append(firstMapped.batch)
            val afterSize = if (bench) journalSize(dataSource, schema) else null
            assertFalse(durableReceipt.duplicate)
            assertFailsWith<IllegalStateException> {
                store.append(firstMapped.batch.copy(results = firstMapped.batch.results.mapIndexed { index, result ->
                    if (index == 0) result.copy(outcome = "BREAK",
                        breakReason = "CASH_LEG_FAILED") else result
                }))
            }
            if (bench) {
                println("journal-candidate source_read_ns=$sourceReadNanos " +
                    "evaluation_ns=$evaluationNanos mapping_ns=$mappingNanos " +
                    "serialization_ns=${durableReceipt.timings.serializationNanos} " +
                    "head_wait_ns=${durableReceipt.timings.headWaitNanos} " +
                    "append_commit_ns=${durableReceipt.timings.appendAndCommitNanos} " +
                    "wal_bytes_inclusive=${afterSize!!.walBytes - beforeSize!!.walBytes} " +
                    "index_bytes_delta=${afterSize.indexBytes - beforeSize.indexBytes}")
            }
            confirm(evaluator, firstDecision, durableReceipt)
            assertEquals(firstReference.balances, evaluator.snapshot().balances)
            assertEquals(setOf("trade-4"), evaluator.snapshot().outstanding.keys)
            assertEquals(2L, store.head(streamId).nextBatchSequence)

            val funding = ReferenceFunding(controlSequence = 5, controlId = "seller-funding",
                account = account("seller", "SECURITY", "AAPL"), amount = BigDecimal.ONE,
                retryTradeIds = listOf("trade-4"))
            val finalReference = oracle.evaluate(controls.map(ReferenceStep::Control) +
                ReferenceStep.Source(referenceWindow) + ReferenceStep.Control(funding))
            val fundingInput = SettlementPreparedInput.Control(0,
                funding, finalReference.controlDigests.getValue(funding.controlId))
            val secondDecision = evaluator.prepare(listOf(fundingInput))
            assertEquals(2, secondDecision.results.single().attemptNumber)
            assertEquals(ReferenceResultKind.SETTLED, secondDecision.results.single().kind)
            val changedFunding = funding.copy(amount = BigDecimal.TEN)
            assertFailsWith<IllegalArgumentException> {
                mapper.map(streamId, store.head(streamId), secondDecision,
                    listOf(SettlementPreparedInput.Control(0, changedFunding,
                        evaluator.controlMemberDigest(changedFunding))), emptyList())
            }
            val secondMapped = mapper.map(streamId, store.head(streamId), secondDecision,
                listOf(fundingInput), emptyList())
            mapped = secondMapped
            durableReceipt = store.append(secondMapped.batch)
            confirm(evaluator, secondDecision, durableReceipt)
            assertEquals(finalReference.balances, evaluator.snapshot().balances)
            assertTrue(evaluator.snapshot().outstanding.isEmpty())
            assertEquals(3, resultCount(dataSource, schema, streamId))
            assertEquals(3L, store.head(streamId).nextBatchSequence)

            val sourceAuthority = object : SettlementReplaySourceAuthority {
                override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow):
                    VerifiedCanonicalSourceWindow {
                    assertEquals(streamId, eventStream)
                    assertEquals(0L, window.fromExclusiveSequence)
                    assertEquals(4L, window.throughInclusiveSequence)
                    return readRetainedSources(dataSource, streamId, sources).first
                }

                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow): Boolean =
                    false
            }
            val replay = SettlementJournalReplayProof(store).prove(streamId, sourceAuthority,
                { control, memberDigest ->
                    finalReference.controlDigests[control.controlId] == memberDigest
                })
            assertEquals(finalReference.balances, replay.balances)
            assertTrue(replay.outstandingTradeIds.isEmpty())
            assertEquals(3, replay.resultCount)

            val changedSources = sources.map { source ->
                if (source.streamSequence == 2L) source.copy(resultPayloadJson =
                    source.resultPayloadJson.replace("event-trade-2", "event-trade-2-changed"))
                else source
            }
            val changedAuthority = object : SettlementReplaySourceAuthority {
                override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow):
                    VerifiedCanonicalSourceWindow =
                    readRetainedSources(dataSource, eventStream, changedSources).first

                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow): Boolean =
                    false
            }
            assertFailsWith<IllegalStateException> {
                SettlementJournalReplayProof(store).prove(streamId, changedAuthority,
                    { control, memberDigest ->
                        finalReference.controlDigests[control.controlId] == memberDigest
                    })
            }
        }
    }

    private fun confirm(evaluator: SettlementJournalEvaluator, proposal: SettlementDecisionProposal,
        receipt: SettlementJournalCommitReceipt) {
        evaluator.confirm(proposal, SettlementAppendReceipt(proposal.expectedHead,
            proposal.proposalDigest, receipt.proposalDigest,
            proposal.expectedHead.copy(batchSequence = receipt.batchSequence,
                batchDigest = receipt.batchDigest)))
    }

    private fun account(owner: String, type: String, asset: String) =
        ReferenceAccountKey("run", owner, "$owner-account", type, asset)

    private fun maker(stream: String, sequence: Long, order: String): CanonicalOutcomeSource {
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"2026-09-28T00:00:00Z"},"acceptedOrder":${identity(order, "seller", "SELL")},"orderStates":[${state(order, "SELL", "OPEN", "1") }]}"""
        return source(stream, sequence, order, payload)
    }

    private fun taker(stream: String, sequence: Long, buyer: String, sellerOrder: String):
        CanonicalOutcomeSource {
        val order = "$buyer-order"
        val trade = "trade-$sequence"
        val at = "2026-09-28T00:00:01Z"
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"$at"},"acceptedOrder":${identity(order, buyer, "BUY")},"executions":[{"eventId":"buy-$trade","executionId":"$trade-buy","orderId":"$order","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"TAKER"},{"eventId":"sell-$trade","executionId":"$trade-sell","orderId":"$sellerOrder","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"MAKER"}],"trades":[{"eventId":"event-$trade","tradeId":"$trade","executionId":"$trade","buyOrderId":"$order","sellOrderId":"$sellerOrder","instrumentId":"AAPL","quantityUnits":"1","price":"50","currency":"USD","occurredAt":"$at"}],"orderStates":[${state(order, "BUY", "FILLED", "0")},${state(sellerOrder, "SELL", "FILLED", "0") }]}"""
        return source(stream, sequence, order, payload)
    }

    private fun identity(order: String, owner: String, side: String) =
        """{"orderId":"$order","engineOrderId":"engine-$order","clientOrderId":"client-$order","runId":"run","venueSessionId":"session","instrumentId":"AAPL","participantId":"$owner","accountId":"$owner-account","side":"$side","orderType":"LIMIT","quantityUnits":"1","limitPrice":"50","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-28T00:00:00Z"}"""

    private fun state(order: String, side: String, status: String, remaining: String) =
        """{"orderId":"$order","instrumentId":"AAPL","side":"$side","status":"$status","originalQuantity":"1","remainingQuantity":"$remaining","limitPrice":"50","currency":"USD","lastUpdatedAt":"2026-09-28T00:00:01Z"}"""

    private fun source(stream: String, sequence: Long, order: String, payload: String) =
        CanonicalOutcomeSource(stream, 0, sequence, "batch-$sequence", "command-$sequence",
            "SubmitOrder", "hash-$sequence", "AAPL", order, "accepted", payload)

    private fun sourceDigest(source: CanonicalOutcomeSource) = digest(listOf(
        "reef.reference.canonical-source.v1", source.eventStream, source.partitionId.toString(),
        source.streamSequence.toString(), source.batchId, source.commandId, source.commandType,
        source.payloadHash, source.instrumentId, source.orderId, source.resultStatus,
        source.resultPayloadJson))

    private fun readRetainedSources(dataSource: DataSource, stream: String,
        sources: List<CanonicalOutcomeSource>): Pair<VerifiedCanonicalSourceWindow, Long> {
        // Reader uses fixed runtime schema. Only create it in disposable test DB, then remove it.
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA runtime")
                statement.execute("""CREATE TABLE runtime.canonical_venue_event_batches (
                    event_stream TEXT NOT NULL, batch_id TEXT NOT NULL, partition_id INTEGER NOT NULL,
                    first_sequence BIGINT NOT NULL, last_sequence BIGINT NOT NULL)""")
                statement.execute("""CREATE TABLE runtime.canonical_command_outcomes (
                    event_stream TEXT NOT NULL, partition_id INTEGER NOT NULL,
                    stream_sequence BIGINT NOT NULL, batch_id TEXT NOT NULL,
                    command_id TEXT NOT NULL, command_type TEXT NOT NULL,
                    payload_hash TEXT NOT NULL, instrument_id TEXT NOT NULL,
                    order_id TEXT NOT NULL, result_status TEXT NOT NULL,
                    result_payload JSONB NOT NULL)""")
            }
        }
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""INSERT INTO runtime.canonical_venue_event_batches
                    (event_stream,batch_id,partition_id,first_sequence,last_sequence)
                    VALUES (?,?,?,?,?)""").use { batches ->
                    connection.prepareStatement("""INSERT INTO runtime.canonical_command_outcomes
                        (event_stream,partition_id,stream_sequence,batch_id,command_id,
                         command_type,payload_hash,instrument_id,order_id,result_status,result_payload)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)""").use { outcomes ->
                        sources.forEach { source ->
                            batches.setString(1, source.eventStream)
                            batches.setString(2, source.batchId)
                            batches.setInt(3, source.partitionId)
                            batches.setLong(4, source.streamSequence)
                            batches.setLong(5, source.streamSequence)
                            batches.addBatch()
                            outcomes.setString(1, source.eventStream)
                            outcomes.setInt(2, source.partitionId)
                            outcomes.setLong(3, source.streamSequence)
                            outcomes.setString(4, source.batchId)
                            outcomes.setString(5, source.commandId)
                            outcomes.setString(6, source.commandType)
                            outcomes.setString(7, source.payloadHash)
                            outcomes.setString(8, source.instrumentId)
                            outcomes.setString(9, source.orderId)
                            outcomes.setString(10, source.resultStatus)
                            outcomes.setString(11, source.resultPayloadJson)
                            outcomes.addBatch()
                        }
                        assertEquals(sources.size, batches.executeBatch().size)
                        assertEquals(sources.size, outcomes.executeBatch().size)
                    }
                }
            }
            val start = System.nanoTime()
            val window = PostgresCanonicalOutcomeSourceReader(dataSource).readVerifiedWindow(
                "settlement-journal-candidate", stream, 0, "generation-1", 0, sources.size.toLong())
            return window to (System.nanoTime() - start)
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA runtime CASCADE") }
            }
        }
    }

    private fun digest(fields: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resultCount(dataSource: DataSource, schema: String, stream: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $schema.settlement_journal_results " +
                "WHERE event_stream = ?").use { statement ->
                statement.setString(1, stream)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    private data class JournalSize(val walBytes: Long, val indexBytes: Long)

    private fun journalSize(dataSource: DataSource, schema: String): JournalSize =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT pg_current_wal_lsn() - '0/0'::pg_lsn AS wal_bytes,
                    pg_indexes_size('$schema.settlement_journal_heads') +
                    pg_indexes_size('$schema.settlement_journal_batches') +
                    pg_indexes_size('$schema.settlement_journal_source_windows') +
                    pg_indexes_size('$schema.settlement_journal_controls') +
                    pg_indexes_size('$schema.settlement_journal_results') AS index_bytes""").use { rows ->
                    check(rows.next())
                    JournalSize(rows.getLong(1), rows.getLong(2))
                }
            }
        }

    private fun withMigratedSchema(dataSource: DataSource, run: (String) -> Unit) {
        val schema = "journal_candidate_${UUID.randomUUID().toString().replace("-", "")}"
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
        return RuntimeDataSources.dataSource(url, user, password, "settlement-journal-candidate")
    }
}
