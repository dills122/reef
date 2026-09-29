package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.infrastructure.persistence.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Requires migrated, isolated disposable source, journal and finality databases. */
class PostMatchSettlementJournalCandidateWorkerIntegrationTest {
    @Test
    fun retainedSourceCommitsThroughExternalFinalityAndRestartFailsClosed() {
        val source = database("SETTLEMENT_SOURCE_POSTGRES", "candidate-source")
        val settlement = database("SETTLEMENT_POSTGRES", "candidate-journal")
        val finality = database("SETTLEMENT_FINALITY_POSTGRES", "candidate-finality")
        val id = UUID.randomUUID().toString().replace("-", "")
        val controlSchema = "candidate_control_$id"
        val journalSchema = "candidate_journal_$id"
        val finalitySchema = "candidate_finality_$id"
        val stream = "candidate-$id"
        val incarnation = "incarnation-$id"
        settlement.sql("CREATE SCHEMA $controlSchema")
        settlement.sql("CREATE SCHEMA $journalSchema")
        finality.sql("CREATE SCHEMA $finalitySchema")
        try {
            migration(settlement, "postmatch/0007_settlement_control_log.sql", "postmatch.",
                "$controlSchema.")
            listOf("0012_settlement_journal.sql", "0014_settlement_journal_snapshots.sql",
                "0015_settlement_journal_writer_snapshots.sql")
                .forEach { migration(settlement, "settlement/$it", "settlement.", "$journalSchema.") }
            migration(finality, "finality/0001_settlement_finality_authority.sql", "finality.",
                "$finalitySchema.")
            insertRejectedOutcome(source, stream)
            val catalog = PostMatchSourceCatalog(source)
            val reader = PostgresCanonicalOutcomeSourceReader(source)
            var trailingStableEnd: Long? = null
            val retainedReplay = PostgresSettlementReplaySourceAuthority(source, catalog, reader)
            val replay = object : SettlementReplaySourceAuthority by retainedReplay {
                override fun stableEndSequence(eventStream: String, sourceGeneration: String,
                    partitionId: Int, fromExclusiveSequence: Long): Long? = trailingStableEnd

                override fun verifyEmpty(eventStream: String,
                    window: SettlementJournalSourceWindow): Boolean =
                    trailingStableEnd != null && window.partitionId == 0 &&
                        window.fromExclusiveSequence == 3L &&
                        window.throughInclusiveSequence == trailingStableEnd &&
                        window.members.isEmpty()
            }
            val controls = SettlementControlLogStore(settlement, controlSchema)
            controls.initialize(stream, 1, "owner-$id", incarnation)
            val journal = SettlementJournalStore(settlement, journalSchema)
            val snapshots = SettlementJournalSnapshotProof(settlement, journal, journalSchema)
            val authority = PostgresSettlementJournalFinalityAuthority(finality, finalitySchema)
            val protocol = SettlementJournalFinalityProtocol(journal, authority)
            val binding = SettlementSourceBindingDigestReader { "a".repeat(64) }
            fun open() = PostMatchSettlementJournalCandidateWorker.open(source, catalog, reader,
                replay, controls, incarnation, journal, protocol, authority, binding, stream,
                listOf(0), incarnation, snapshots = snapshots)
            val worker = open()
            val committed = worker.pollOnce() ?: error("retained source was not committed")
            assertEquals(1L, committed.batchSequence)
            assertEquals(1, committed.sourceWindows)
            assertEquals(0, committed.resultCount)
            assertEquals(1L, authority.read(stream)?.acknowledgedBatchSequence)
            assertEquals(1L, authority.read(stream)?.snapshot?.batchSequence)
            assertEquals(null, worker.pollOnce())
            finality.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $finalitySchema.settlement_finality_anchors
                       SET lease_expires_at = clock_timestamp() - interval '1 second'
                       WHERE event_stream = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val originalPayload = source.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT result_payload::text FROM runtime.canonical_command_outcomes
                       WHERE event_stream = ? AND stream_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getString(1)
                    }
                }
            }
            fun writePayload(payload: String) = source.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE runtime.canonical_command_outcomes SET result_payload = ?::jsonb
                       WHERE event_stream = ? AND stream_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, payload)
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val anchoredBeforeTamper = authority.read(stream)
            writePayload("""{"effectVersion":1,"rejected":{"eventId":"event-1",
                "orderId":"order-1","code":"R","reason":"changed",
                "occurredAt":"2026-09-28T00:00:00Z"}}""")
            assertFailsWith<Exception> { open() }
            assertEquals(anchoredBeforeTamper, authority.read(stream))
            writePayload(originalPayload)
            val resumed = open()
            assertEquals(1L, resumed.recoverySnapshot().evaluator.head.batchSequence)
            val partition = ReferenceStreamPartition(stream, catalog.generation(), 0)
            val acceptance = SettlementControlAcceptanceAdapter(controls, stream, incarnation,
                1, "owner-$id", mapOf(partition to 1L))
            acceptance.accept(ReferencePolicyActivation(controlSequence = 0,
                controlId = "policy-$id", runId = "run-1", venueSessionId = "session-1",
                effectiveAfterSourceFrontiers = mapOf(partition to 1L),
                profileId = "instant-post-trade-v1", policyVersion = 1,
                mode = "instant-post-trade", settlementCycle = "T+0", nettingMode = "gross",
                ledgerPostingMode = "gross-dvp", selectionSource = "fixture"))
            acceptance.accept(ReferenceOpening(controlSequence = 0, controlId = "buyer-cash-$id",
                account = ReferenceAccountKey("run-1", "buyer", "buyer-account", "CASH", "USD"),
                amount = BigDecimal("50")))
            acceptance.accept(ReferenceOpening(controlSequence = 0,
                controlId = "seller-security-$id", account = ReferenceAccountKey("run-1", "seller",
                    "seller-account", "SECURITY", "AAPL"), amount = BigDecimal.ONE))
            // Simulate a future policy retained before this acceptance guard existed.
            val controlHead = controls.head(stream)
            controls.appendBatch(SettlementControlLogProposal(stream,
                controlHead.nextControlSequence, controlHead.lastControlDigest, 1,
                "owner-$id", incarnation, listOf(ReferencePolicyActivation(
                    controlSequence = controlHead.nextControlSequence,
                    controlId = "future-policy-$id", runId = "run-1",
                    venueSessionId = "session-1",
                    effectiveAfterSourceFrontiers = mapOf(partition to 2L),
                    profileId = "instant-post-trade-v1", policyVersion = 2,
                    mode = "instant-post-trade", settlementCycle = "T+0",
                    nettingMode = "gross", ledgerPostingMode = "gross-dvp",
                    selectionSource = "fixture"))))
            val fixture = PostMatchSettlementJournalCandidateWorkerTest()
            insertOutcome(source, fixture.source(stream, 2, "seller-order", "seller", "SELL", false))
            insertOutcome(source, fixture.source(stream, 3, "buyer-order", "buyer", "BUY", true))
            val boundaryBatch = resumed.pollOnce() ?: error("future policy source boundary was not committed")
            assertEquals(2L, boundaryBatch.batchSequence)
            assertEquals(0, boundaryBatch.resultCount)
            assertEquals(3, boundaryBatch.controls)
            assertEquals(2L, journal.readVerifiedBatch(stream, 2).sourceWindows.single().throughInclusiveSequence)
            val tradeBatch = resumed.pollOnce() ?: error("trade was not committed")
            assertEquals(3L, tradeBatch.batchSequence)
            assertEquals(1, tradeBatch.resultCount)
            assertEquals(1, tradeBatch.controls)
            assertEquals(3L, authority.read(stream)?.acknowledgedBatchSequence)
            assertEquals("SETTLED", journal.readVerifiedBatch(stream, 3).results.single().outcome)
            val retained = resumed.recoverySnapshot().lastRetainedSourceFrontiers
            assertEquals(3L, retained.getValue(partition))
            trailingStableEnd = 4L
            val trailing = resumed.pollOnce() ?: error("stable Kafka tail was not covered")
            assertEquals(4L, trailing.batchSequence)
            assertEquals(1, trailing.sourceWindows)
            assertEquals(emptyList(), journal.readVerifiedBatch(stream, 4).sourceWindows.single().members)
            assertEquals(3L, resumed.recoverySnapshot().lastRetainedSourceFrontiers.getValue(partition))
            assertEquals(null, resumed.pollOnce())
            assertFailsWith<IllegalStateException> { open() }
        } finally {
            finality.sql("DROP SCHEMA $finalitySchema CASCADE")
            settlement.sql("DROP SCHEMA $journalSchema CASCADE")
            settlement.sql("DROP SCHEMA $controlSchema CASCADE")
            source.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM runtime.canonical_command_outcomes WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM runtime.canonical_venue_event_batches WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun laterRetainedOutcomeWaitsForEarlierCommittedCommandToMaterialize() {
        val source = database("SETTLEMENT_SOURCE_POSTGRES", "candidate-gap-source")
        val settlement = database("SETTLEMENT_POSTGRES", "candidate-gap-journal")
        val finality = database("SETTLEMENT_FINALITY_POSTGRES", "candidate-gap-finality")
        val id = UUID.randomUUID().toString().replace("-", "")
        val controlSchema = "candidate_gap_control_$id"
        val journalSchema = "candidate_gap_journal_$id"
        val finalitySchema = "candidate_gap_finality_$id"
        val stream = "candidate-gap-$id"
        val incarnation = "incarnation-$id"
        settlement.sql("CREATE SCHEMA $controlSchema")
        settlement.sql("CREATE SCHEMA $journalSchema")
        finality.sql("CREATE SCHEMA $finalitySchema")
        try {
            migration(settlement, "postmatch/0007_settlement_control_log.sql", "postmatch.",
                "$controlSchema.")
            listOf("0012_settlement_journal.sql", "0014_settlement_journal_snapshots.sql")
                .forEach { migration(settlement, "settlement/$it", "settlement.", "$journalSchema.") }
            migration(finality, "finality/0001_settlement_finality_authority.sql", "finality.",
                "$finalitySchema.")
            insertOutcome(source, rejectedSource(stream, 1))
            insertOutcome(source, rejectedSource(stream, 3))
            val catalog = PostMatchSourceCatalog(source)
            val reader = PostgresCanonicalOutcomeSourceReader(source)
            var emptyProofCalls = 0
            var brokerStableEnd: Long? = 3L
            val replay = PostgresSettlementReplaySourceAuthority(source, catalog, reader,
                object : SettlementEmptyRangeAttestor {
                    override fun verify(eventStream: String,
                        window: SettlementJournalSourceWindow): Boolean {
                        emptyProofCalls++
                        return false
                    }
                    override fun stableEndSequence(eventStream: String, sourceGeneration: String,
                        partitionId: Int, fromExclusiveSequence: Long): Long? = brokerStableEnd
                })
            val controls = SettlementControlLogStore(settlement, controlSchema)
            controls.initialize(stream, 1, "owner-$id", incarnation)
            val journal = SettlementJournalStore(settlement, journalSchema)
            val authority = PostgresSettlementJournalFinalityAuthority(finality, finalitySchema)
            val protocol = SettlementJournalFinalityProtocol(journal, authority)
            val worker = PostMatchSettlementJournalCandidateWorker.open(source, catalog, reader,
                replay, controls, incarnation, journal, protocol, authority,
                SettlementSourceBindingDigestReader { "a".repeat(64) }, stream,
                listOf(0), incarnation)
            assertEquals(1L, worker.pollOnce()?.batchSequence)
            assertEquals(null, worker.pollOnce())
            assertEquals(1, emptyProofCalls)
            val partition = ReferenceStreamPartition(stream, catalog.generation(), 0)
            assertEquals(1L, worker.recoverySnapshot().evaluator.sourceFrontiers.getValue(partition))
            assertEquals(1L, authority.read(stream)?.acknowledgedBatchSequence)

            insertOutcome(source, rejectedSource(stream, 2))
            assertEquals(2L, worker.pollOnce()?.batchSequence)
            assertEquals(listOf(2L, 3L), journal.readVerifiedBatch(stream, 2)
                .sourceWindows.single().members.map { it.streamSequence })
            assertEquals(3L, worker.recoverySnapshot().evaluator.sourceFrontiers.getValue(partition))
            assertEquals(2L, authority.read(stream)?.acknowledgedBatchSequence)

            insertOutcome(source, rejectedSource(stream, 5))
            brokerStableEnd = null
            assertFailsWith<IllegalStateException> { worker.pollOnce() }
            assertEquals(2L, authority.read(stream)?.acknowledgedBatchSequence)
        } finally {
            finality.sql("DROP SCHEMA $finalitySchema CASCADE")
            settlement.sql("DROP SCHEMA $journalSchema CASCADE")
            settlement.sql("DROP SCHEMA $controlSchema CASCADE")
            source.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM runtime.canonical_command_outcomes WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM runtime.canonical_venue_event_batches WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun rejectedSource(stream: String, sequence: Long): CanonicalOutcomeSource =
        CanonicalOutcomeSource(stream, 0, sequence, "batch-$stream-$sequence",
            "command-$stream-$sequence", "SubmitOrder", "hash-$sequence", "AAPL",
            "order-$sequence", "rejected",
            """{"effectVersion":1,"rejected":{"eventId":"event-$sequence",
                "orderId":"order-$sequence","code":"R","reason":"bad",
                "occurredAt":"2026-09-28T00:00:00Z"}}""")

    private fun insertRejectedOutcome(source: DataSource, stream: String) {
        source.connection.use { connection ->
            connection.prepareStatement("""INSERT INTO runtime.canonical_venue_event_batches
                (batch_id,shard_id,partition_id,command_stream,event_stream,first_sequence,
                 last_sequence,command_count,payload_checksum,payload_json,created_at)
                VALUES (?,'test-shard',0,'REEF_COMMANDS',?,1,1,1,'test-checksum','{}'::jsonb,
                        '2026-09-28T00:00:00Z')""").use { statement ->
                statement.setString(1, "batch-$stream")
                statement.setString(2, stream)
                statement.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO runtime.canonical_command_outcomes
                (command_id,batch_id,shard_id,partition_id,command_stream,event_stream,
                 stream_sequence,delivered_count,command_type,payload_hash,instrument_id,
                 order_id,result_status,reject_code,result_payload)
                VALUES (?,?,'test-shard',0,'REEF_COMMANDS',?,1,1,'SubmitOrder','hash-1',
                        'AAPL','order-1','rejected','R',?::jsonb)""").use { statement ->
                statement.setString(1, "command-$stream")
                statement.setString(2, "batch-$stream")
                statement.setString(3, stream)
                statement.setString(4, """{"effectVersion":1,"rejected":{"eventId":"event-1",
                    "orderId":"order-1","code":"R","reason":"bad",
                    "occurredAt":"2026-09-28T00:00:00Z"}}""")
                statement.executeUpdate()
            }
        }
    }

    private fun insertOutcome(dataSource: DataSource, outcome: CanonicalOutcomeSource) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""INSERT INTO runtime.canonical_venue_event_batches
                (batch_id,shard_id,partition_id,command_stream,event_stream,first_sequence,
                 last_sequence,command_count,payload_checksum,payload_json,created_at)
                VALUES (?,'test-shard',0,'REEF_COMMANDS',?,?,?,1,'test-checksum','{}'::jsonb,
                        '2026-09-28T00:00:00Z')""").use { statement ->
                statement.setString(1, outcome.batchId)
                statement.setString(2, outcome.eventStream)
                statement.setLong(3, outcome.streamSequence)
                statement.setLong(4, outcome.streamSequence)
                statement.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO runtime.canonical_command_outcomes
                (command_id,batch_id,shard_id,partition_id,command_stream,event_stream,
                 stream_sequence,delivered_count,command_type,payload_hash,instrument_id,
                 order_id,result_status,reject_code,result_payload)
                VALUES (?,?,'test-shard',0,'REEF_COMMANDS',?,?,1,?,?,?,?,?,'',?::jsonb)""").use { statement ->
                statement.setString(1, outcome.commandId)
                statement.setString(2, outcome.batchId)
                statement.setString(3, outcome.eventStream)
                statement.setLong(4, outcome.streamSequence)
                statement.setString(5, outcome.commandType)
                statement.setString(6, outcome.payloadHash)
                statement.setString(7, outcome.instrumentId)
                statement.setString(8, outcome.orderId)
                statement.setString(9, outcome.resultStatus)
                statement.setString(10, outcome.resultPayloadJson)
                statement.executeUpdate()
            }
        }
    }

    private fun migration(dataSource: DataSource, file: String, original: String, target: String) {
        val path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("scripts/dev/db/migrations/$file") }.first(Files::exists)
        val sql = Files.readString(path).lineSequence()
            .filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .replace("CREATE SCHEMA IF NOT EXISTS ${original.removeSuffix(".")};", "")
            .replace(original, target)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }
    }

    private fun DataSource.sql(sql: String) = connection.use { connection ->
        connection.createStatement().use { it.execute(sql) }
    }

    private fun database(prefix: String, pool: String): DataSource {
        val url = System.getenv("${prefix}_JDBC_URL_TEST")
        val user = System.getenv("${prefix}_USER_TEST")
        val password = System.getenv("${prefix}_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "three disposable PostgreSQL databases required")
        return RuntimeDataSources.dataSource(url, user, password, pool)
    }
}
