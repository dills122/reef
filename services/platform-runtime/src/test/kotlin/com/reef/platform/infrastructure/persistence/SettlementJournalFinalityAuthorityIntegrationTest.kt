package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.PostMatchSettlementJournalRecovery
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementEvaluatorHead
import com.reef.platform.application.settlementjournal.SettlementEvaluatorRecoveryState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Separate disposable PostgreSQL instances exercise journal and finality restore domains. */
class SettlementJournalFinalityAuthorityIntegrationTest {
    private val fixedBinding = SettlementSourceBindingDigestReader { "a".repeat(64) }

    @Test
    fun v2CheckpointReplaysBoundedEmptyTailBeforeOwnerTakeover() {
        val (journalDataSource, finalityDataSource) = dataSourcesOrSkip()
        val sourceDataSource = sourceDataSourceOrSkip()
        withSchemas(journalDataSource, finalityDataSource) { journalSchema, finalitySchema ->
            val controlSchema = "control_recovery_${UUID.randomUUID().toString().replace("-", "")}"
            sourceDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA $controlSchema")
                    val sql = Files.readString(repoRoot().resolve(
                        "scripts/dev/db/migrations/postmatch/0007_settlement_control_log.sql"))
                        .replace("postmatch.", "$controlSchema.")
                    statement.execute(sql)
                }
            }
            try {
                val stream = "writer-recovery-${UUID.randomUUID()}"
                val journal = SettlementJournalStore(journalDataSource, journalSchema)
                journal.initialize(stream, 1, "journal-incarnation")
                val controls = SettlementControlLogStore(sourceDataSource, controlSchema)
                controls.initialize(stream, 1, "control-owner", "control-incarnation")
                val authority = PostgresSettlementJournalFinalityAuthority(finalityDataSource,
                    finalitySchema)
                val protocol = SettlementJournalFinalityProtocol(journal, authority)
                protocol.bootstrapAtOrigin(stream, fixedBinding)
                val source = object : SettlementReplaySourceAuthority {
                    override fun readVerified(eventStream: String,
                        window: SettlementJournalSourceWindow) =
                        error("fixture contains only independently certified empty ranges")
                    override fun verifyEmpty(eventStream: String,
                        window: SettlementJournalSourceWindow) = true
                    override fun verifyRecoveredFrontier(eventStream: String,
                        sourceGeneration: String, partitionId: Int,
                        lastRetainedFrontier: Long, coveredFrontier: Long) =
                        sourceGeneration == "generation-1" && partitionId == 0 &&
                            lastRetainedFrontier == 0L && coveredFrontier in 1L..2L
                }
                val catalog = object : PostMatchReadSourceCatalog {
                    override fun generation() = "generation-1"
                    override fun partitionHeads(eventStream: String, partitionCount: Int) =
                        mapOf(0 to 0L)
                }
                val lease = protocol.acquireAndFence(stream, source,
                    ReferenceControlProofVerifier { _, _ -> true }, fixedBinding)
                fun window(from: Long, through: Long): SettlementJournalSourceWindow {
                    val initial = SettlementJournalSourceWindow(0, "generation-1", 0,
                        from, through, "fixture-absence-$from-$through",
                        SettlementJournalStore.ORIGIN_DIGEST, emptyList())
                    return initial.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(
                        stream, initial))
                }
                val firstHead = journal.head(stream)
                protocol.appendAndAcknowledge(lease, SettlementJournalBatchProposal(stream, 1,
                    firstHead.lastBatchDigest, firstHead.ownerEpoch, firstHead.incarnationId,
                    listOf(window(0, 1)), emptyList(), emptyList()))
                val checkpointHead = journal.head(stream)
                val partition = ReferenceStreamPartition(stream, "generation-1", 0)
                val writerState = SettlementJournalWriterRecoveryState(
                    SettlementEvaluatorRecoveryState(SettlementEvaluatorHead(1,
                        checkpointHead.lastBatchDigest, checkpointHead.ownerEpoch,
                        checkpointHead.incarnationId), emptyList(), emptyMap(), emptyMap(),
                        emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf(partition to 1L)),
                    emptyMap(), emptySet(), "a".repeat(64), "control-incarnation",
                    mapOf(partition to 0L))
                protocol.publishWriterCurrentSnapshot(lease,
                    SettlementJournalSnapshotProof(journalDataSource, journal, journalSchema),
                    writerState, fixedBinding)
                val tailHead = journal.head(stream)
                protocol.appendAndAcknowledge(lease, SettlementJournalBatchProposal(stream, 2,
                    tailHead.lastBatchDigest, tailHead.ownerEpoch, tailHead.incarnationId,
                    listOf(window(1, 2)), emptyList(), emptyList()))
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
                val recovery = PostMatchSettlementJournalRecovery(catalog, source, controls,
                    journal, SettlementJournalSnapshotProof(journalDataSource, journal,
                        journalSchema), authority, fixedBinding)
                val resumed = recovery.acquire(stream, listOf(0), "control-incarnation")
                assertEquals(2L, resumed.state.evaluator.head.batchSequence)
                assertEquals(2L, resumed.state.evaluator.sourceFrontiers[partition])
                assertEquals(0L, resumed.state.lastRetainedSourceFrontiers[partition])
                assertEquals(resumed.lease.epoch, journal.head(stream).ownerEpoch)
                assertTrue(resumed.lease.epoch > lease.epoch)

                // First append can be acknowledged before first checkpoint publication.
                val originStream = "origin-crash-${UUID.randomUUID()}"
                journal.initialize(originStream, 1, "origin-incarnation")
                controls.initialize(originStream, 1, "control-owner", "control-incarnation")
                protocol.bootstrapAtOrigin(originStream, fixedBinding)
                val originLease = protocol.acquireAndFence(originStream, source,
                    ReferenceControlProofVerifier { _, _ -> true }, fixedBinding)
                val originWindow = SettlementJournalSourceWindow(0, "generation-1", 0,
                    0, 1, "fixture-origin-absence", SettlementJournalStore.ORIGIN_DIGEST,
                    emptyList()).let { it.copy(coverageDigest =
                    SettlementJournalStore.sourceCoverageDigest(originStream, it)) }
                val originHead = journal.head(originStream)
                protocol.appendAndAcknowledge(originLease, SettlementJournalBatchProposal(
                    originStream, 1, originHead.lastBatchDigest, originHead.ownerEpoch,
                    originHead.incarnationId, listOf(originWindow), emptyList(), emptyList()))
                finalityDataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """UPDATE $finalitySchema.settlement_finality_anchors
                           SET lease_expires_at = clock_timestamp() - interval '1 second'
                           WHERE event_stream = ?"""
                    ).use { statement ->
                        statement.setString(1, originStream)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                val originRecovery = recovery.acquire(originStream, listOf(0),
                    "control-incarnation")
                assertEquals(1L, originRecovery.state.evaluator.head.batchSequence)
                assertEquals(1L, originRecovery.state.evaluator.sourceFrontiers[
                    ReferenceStreamPartition(originStream, "generation-1", 0)])
                assertTrue(originRecovery.lease.epoch > originLease.epoch)
            } finally {
                sourceDataSource.connection.use { connection ->
                    connection.createStatement().use { it.execute("DROP SCHEMA $controlSchema CASCADE") }
                }
            }
        }
    }

    @Test
    fun monotonicAcknowledgmentLeaseTakeoverAndDatabaseRewindGuard() {
        val (journalDataSource, finalityDataSource) = dataSourcesOrSkip()
        withSchemas(journalDataSource, finalityDataSource) { _, finalitySchema ->
            val stream = "finality-${UUID.randomUUID()}"
            val authority = PostgresSettlementJournalFinalityAuthority(finalityDataSource, finalitySchema)
            val origin = authority.initializeAtOrigin(stream, originHead(), "a".repeat(64))
            assertEquals(origin, authority.initializeAtOrigin(stream, originHead(), "a".repeat(64)))
            assertFailsWith<IllegalStateException> {
                authority.initializeAtOrigin(stream, originHead().copy(incarnationId = "changed"),
                    "a".repeat(64))
            }
            val first = authority.acquireLease(origin, 1)
            assertTrue(first.epoch > 1)
            assertFailsWith<IllegalStateException> { authority.acquireLease(origin, 1) }
            val next = origin.copy(acknowledgedBatchSequence = 1,
                acknowledgedBatchDigest = "1".repeat(64), controlSequence = 1,
                controlDigest = "2".repeat(64))
            assertEquals(next, authority.acknowledge(first, next))
            assertEquals(next, authority.acknowledge(first, next))
            assertFailsWith<IllegalStateException> {
                authority.acknowledge(first, next.copy(acknowledgedBatchDigest = "3".repeat(64)))
            }
            assertFailsWith<IllegalStateException> {
                authority.acknowledge(first, next.copy(acknowledgedBatchSequence = 3))
            }
            assertFailsWith<Exception> {
                finalityDataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("""UPDATE $finalitySchema.settlement_finality_anchors
                            SET acknowledged_batch_sequence = 0 WHERE event_stream = '$stream'""")
                    }
                }
            }
            assertEquals(next, authority.read(stream))
            finalityDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""UPDATE $finalitySchema.settlement_finality_anchors
                        SET lease_expires_at = clock_timestamp() - interval '1 second'
                        WHERE event_stream = '$stream'""")
                }
            }
            assertFailsWith<IllegalStateException> { authority.renewLease(first) }
            assertFailsWith<IllegalStateException> { authority.acknowledge(first, next) }
            val second = authority.acquireLease(next, first.epoch)
            assertTrue(second.epoch > first.epoch && second.nonce != first.nonce)
            assertFailsWith<IllegalStateException> { authority.renewLease(first) }
            assertEquals(second.epoch, authority.renewLease(second).epoch)
        }
    }

    @Test
    fun integratedGateRejectsBothRestoreDirectionsAndUnacknowledgedTail() {
        val (journalDataSource, finalityDataSource) = dataSourcesOrSkip()
        withSchemas(journalDataSource, finalityDataSource) { journalSchema, finalitySchema ->
            val stream = "finality-protocol-${UUID.randomUUID()}"
            val journal = SettlementJournalStore(journalDataSource, journalSchema)
            journal.initialize(stream, 1, "journal-incarnation-${UUID.randomUUID()}")
            val authority = PostgresSettlementJournalFinalityAuthority(finalityDataSource, finalitySchema)
            val protocol = SettlementJournalFinalityProtocol(journal, authority)
            val origin = protocol.bootstrapAtOrigin(stream, fixedBinding)
            val source = object : SettlementReplaySourceAuthority {
                override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow) =
                    error("this fixture contains only a broker-attested empty window")
                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow) = true
            }
            val controls = ReferenceControlProofVerifier { _, _ -> true }
            val lease = protocol.acquireAndFence(stream, source, controls, fixedBinding)
            assertTrue(lease.epoch > 1)
            val window0 = SettlementJournalSourceWindow(0, "generation-1", 0, 0, 1,
                "fixture-empty", SettlementJournalStore.ORIGIN_DIGEST, emptyList())
            val window = window0.copy(coverageDigest =
                SettlementJournalStore.sourceCoverageDigest(stream, window0))
            val head = journal.head(stream)
            val receipt = journal.append(SettlementJournalBatchProposal(stream, 1,
                head.lastBatchDigest, head.ownerEpoch, head.incarnationId,
                listOf(window), emptyList(), emptyList()))
            // Journal commit is final, but a missing external acknowledgment forbids restart.
            assertFailsWith<IllegalStateException> {
                protocol.acquireAndFence(stream, source, controls, fixedBinding)
            }
            val acknowledged = protocol.acknowledgeCommitted(lease, receipt)
            assertEquals(1L, acknowledged.acknowledgedBatchSequence)
            assertEquals(acknowledged, protocol.acknowledgeCommitted(lease, receipt))
            val snapshot = protocol.publishVerifiedCurrentSnapshot(lease,
                SettlementJournalSnapshotProof(journalDataSource, journal, journalSchema),
                source, controls, fixedBinding)
            assertEquals(1L, snapshot.snapshot?.batchSequence)
            assertEquals(snapshot, protocol.publishVerifiedCurrentSnapshot(lease,
                SettlementJournalSnapshotProof(journalDataSource, journal, journalSchema),
                source, controls, fixedBinding))
            val second0 = window0.copy(fromExclusiveSequence = 1, throughInclusiveSequence = 2)
            val second = second0.copy(coverageDigest =
                SettlementJournalStore.sourceCoverageDigest(stream, second0))
            val afterFirst = journal.head(stream)
            val published = protocol.appendAndAcknowledge(lease, SettlementJournalBatchProposal(
                stream, 2, afterFirst.lastBatchDigest, afterFirst.ownerEpoch,
                afterFirst.incarnationId, listOf(second), emptyList(), emptyList()))
            assertEquals(2L, published.finalityAnchor.acknowledgedBatchSequence)
            assertEquals(snapshot.snapshot, published.finalityAnchor.snapshot)
            val writerHead = journal.head(stream)
            val writerState = SettlementJournalWriterRecoveryState(
                SettlementEvaluatorRecoveryState(SettlementEvaluatorHead(2,
                    writerHead.lastBatchDigest, writerHead.ownerEpoch, writerHead.incarnationId),
                    emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                    emptyMap(), emptyMap(),
                    mapOf(ReferenceStreamPartition(stream, "generation-1", 0) to 2L)),
                emptyMap(), emptySet(), "a".repeat(64), "control-incarnation",
                mapOf(ReferenceStreamPartition(stream, "generation-1", 0) to 0L))
            val writerSnapshots = SettlementJournalSnapshotProof(journalDataSource, journal,
                journalSchema)
            val writerPointer = protocol.publishWriterCurrentSnapshot(lease, writerSnapshots,
                writerState, fixedBinding)
            assertEquals(2, writerPointer.snapshot?.stateVersion)
            assertEquals(writerState, writerSnapshots.readAnchoredWriterState(stream, writerPointer))
            assertFailsWith<IllegalStateException> {
                protocol.publishWriterCurrentSnapshot(lease, writerSnapshots,
                    writerState.copy(sourceBindingDigest = "f".repeat(64)), fixedBinding)
            }
            journalDataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $journalSchema.settlement_journal_snapshots
                       SET state_bytes = set_byte(state_bytes, 5, 0)
                       WHERE event_stream = ? AND batch_sequence = 2"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                writerSnapshots.readAnchoredWriterState(stream, writerPointer)
            }
            SettlementJournalExternalAnchorGate(SettlementJournalReplayProof(journal), authority)
                .prove(stream, source, controls)
            assertFailsWith<IllegalStateException> {
                SettlementJournalExternalAnchorGate.verify(stream,
                    SettlementJournalReplayState(head, emptyMap(), emptySet(), 0), acknowledged)
            } // Settlement-only restore behind acknowledged finality.
            assertFailsWith<IllegalStateException> {
                SettlementJournalExternalAnchorGate.verify(stream,
                    SettlementJournalReplayState(journal.head(stream), emptyMap(), emptySet(), 0), origin)
            } // Source/finality restore behind committed journal.
        }
    }

    @Test
    fun separatelyRestoredSourceOrJournalCannotResumePastExternalFinality() {
        val (journalDataSource, finalityDataSource) = dataSourcesOrSkip()
        val sourceDataSource = sourceDataSourceOrSkip()
        withSchemas(journalDataSource, finalityDataSource) { journalSchema, finalitySchema ->
            withSourceSchema(sourceDataSource) {
                val stream = "independent-restore-${UUID.randomUUID()}"
                insertRejectedOutcome(sourceDataSource, stream)
                val sourceAuthority = PostgresSettlementReplaySourceAuthority(sourceDataSource)
                val generation = PostMatchSourceCatalog(sourceDataSource).generation()
                var liveCommandTopicId = "command-topic-original"
                var liveEventTopicId = "venue-topic-original"
                insertSourceBinding(sourceDataSource, stream, generation,
                    liveCommandTopicId, liveEventTopicId)
                val identity = PostgresSettlementSourceTopicIdentity(sourceDataSource,
                    SettlementColdKafkaTopicProbe { _, _ -> null })
                val bindingReader = PostgresSettlementSourceBindingDigestReader(
                    PostMatchSourceCatalog(sourceDataSource), identity,
                    SettlementSourceTopicVerifier { binding ->
                        binding.commandTopicId == liveCommandTopicId &&
                            binding.venueEventTopicId == liveEventTopicId
                    })
                val seed = SettlementJournalSourceWindow(0, generation, 0, 0, 1,
                    "retained-source", SettlementJournalStore.ORIGIN_DIGEST, emptyList())
                val verified = sourceAuthority.readVerified(stream, seed.copy(members =
                    listOf(SettlementJournalSourceMember(1, SettlementJournalStore.ORIGIN_DIGEST))))
                val sourceWindow0 = seed.copy(members = verified.outcomes.map { outcome ->
                    SettlementJournalSourceMember(outcome.source.streamSequence,
                        sourceMemberDigest(outcome.source))
                })
                val sourceWindow = sourceWindow0.copy(coverageDigest =
                    SettlementJournalStore.sourceCoverageDigest(stream, sourceWindow0))
                val journal = SettlementJournalStore(journalDataSource, journalSchema)
                val initial = journal.initialize(stream, 1, "incarnation-${UUID.randomUUID()}")
                val authority = PostgresSettlementJournalFinalityAuthority(finalityDataSource,
                    finalitySchema)
                val protocol = SettlementJournalFinalityProtocol(journal, authority)
                val pinned = protocol.bootstrapAtOrigin(stream, bindingReader)
                assertEquals(bindingReader.readDigest(stream), pinned.sourceBindingDigest)
                val controls = ReferenceControlProofVerifier { _, _ -> true }
                val lease = protocol.acquireAndFence(stream, sourceAuthority, controls,
                    bindingReader)
                val fenced = journal.head(stream)
                val published = protocol.appendAndAcknowledge(lease, SettlementJournalBatchProposal(
                    stream, 1, fenced.lastBatchDigest, fenced.ownerEpoch, fenced.incarnationId,
                    listOf(sourceWindow), emptyList(), emptyList()))
                assertEquals(1L, published.finalityAnchor.acknowledgedBatchSequence)
                val gate = SettlementJournalExternalAnchorGate(SettlementJournalReplayProof(journal),
                    authority)
                gate.prove(stream, sourceAuthority, controls)

                // Source-only rollback loses a retained matching outcome; journal and anchor survive.
                sourceDataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM runtime.canonical_command_outcomes " +
                            "WHERE event_stream = '$stream'")
                    }
                }
                assertFailsWith<Exception> { gate.prove(stream, sourceAuthority, controls) }
                assertEquals(published.finalityAnchor, authority.read(stream))
                insertRejectedOutcome(sourceDataSource, stream, batchAlreadyPresent = true)
                gate.prove(stream, sourceAuthority, controls)

                // Source restore predating binding can re-enroll the same generation on new topics.
                sourceDataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM runtime.settlement_source_topic_identity " +
                            "WHERE event_stream = '$stream'")
                    }
                }
                assertEquals(null, bindingReader.readDigest(stream))
                assertFailsWith<IllegalStateException> {
                    protocol.acquireAndFence(stream, sourceAuthority, controls, bindingReader)
                }
                liveCommandTopicId = "command-topic-replaced"
                liveEventTopicId = "venue-topic-replaced"
                insertSourceBinding(sourceDataSource, stream, generation,
                    liveCommandTopicId, liveEventTopicId)
                assertTrue(bindingReader.readDigest(stream) != pinned.sourceBindingDigest)
                assertFailsWith<IllegalStateException> {
                    protocol.acquireAndFence(stream, sourceAuthority, controls, bindingReader)
                }
                assertEquals(published.finalityAnchor, authority.read(stream))
                sourceDataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM runtime.settlement_source_topic_identity " +
                            "WHERE event_stream = '$stream'")
                    }
                }
                liveCommandTopicId = "command-topic-original"
                liveEventTopicId = "venue-topic-original"
                insertSourceBinding(sourceDataSource, stream, generation,
                    liveCommandTopicId, liveEventTopicId)
                assertEquals(pinned.sourceBindingDigest, bindingReader.readDigest(stream))

                // Target-only rollback restores an empty journal while external acknowledgment survives.
                journalDataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DROP SCHEMA $journalSchema CASCADE")
                        statement.execute("CREATE SCHEMA $journalSchema")
                    }
                }
                applyJournalMigration(journalDataSource, journalSchema)
                journal.initialize(stream, initial.ownerEpoch, initial.incarnationId)
                assertFailsWith<IllegalStateException> {
                    gate.prove(stream, sourceAuthority, controls)
                }
                assertEquals(published.finalityAnchor, authority.read(stream))
            }
        }
    }

    private fun originHead() = SettlementJournalHead(1, SettlementJournalStore.ORIGIN_DIGEST,
        1, "journal-incarnation-1", 0, SettlementJournalStore.ORIGIN_DIGEST)

    private fun withSchemas(journalDataSource: DataSource, finalityDataSource: DataSource,
        run: (String, String) -> Unit) {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val journalSchema = "journal_finality_$suffix"
        val finalitySchema = "external_finality_$suffix"
        journalDataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $journalSchema") }
        }
        finalityDataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $finalitySchema") }
        }
        try {
            val finalitySql = Files.readString(repoRoot().resolve(
                "scripts/dev/db/migrations/finality/0001_settlement_finality_authority.sql"))
                .replace("CREATE SCHEMA IF NOT EXISTS finality;", "")
                .replace("finality.", "$finalitySchema.")
            applyJournalMigration(journalDataSource, journalSchema)
            finalityDataSource.connection.use { connection ->
                connection.createStatement().use { it.execute(finalitySql) }
            }
            run(journalSchema, finalitySchema)
        } finally {
            finalityDataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $finalitySchema CASCADE") }
            }
            journalDataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $journalSchema CASCADE") }
            }
        }
    }

    private fun applyJournalMigration(dataSource: DataSource, schema: String) {
        listOf("0012_settlement_journal.sql", "0014_settlement_journal_snapshots.sql",
            "0015_settlement_journal_writer_snapshots.sql")
            .forEach { migration ->
                val sql = Files.readString(repoRoot().resolve(
                    "scripts/dev/db/migrations/settlement/$migration"))
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n").replace("settlement.", "$schema.")
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        sql.split(';').map(String::trim).filter(String::isNotBlank)
                            .forEach(statement::execute)
                    }
                }
            }
    }

    private fun withSourceSchema(dataSource: DataSource, run: () -> Unit) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA runtime")
                statement.execute("""CREATE TABLE runtime.postmatch_source_generation (
                    singleton BOOLEAN PRIMARY KEY, generation UUID NOT NULL)""")
                statement.execute("INSERT INTO runtime.postmatch_source_generation " +
                    "VALUES (TRUE, gen_random_uuid())")
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
                statement.execute("""CREATE TABLE runtime.settlement_source_topic_identity (
                    source_generation UUID NOT NULL, event_stream TEXT NOT NULL,
                    command_topic TEXT NOT NULL, command_topic_id TEXT NOT NULL,
                    venue_event_topic TEXT NOT NULL, venue_event_topic_id TEXT NOT NULL,
                    PRIMARY KEY (source_generation,event_stream))""")
            }
        }
        try { run() } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA runtime CASCADE") }
            }
        }
    }

    private fun insertSourceBinding(dataSource: DataSource, stream: String, generation: String,
        commandTopicId: String, venueEventTopicId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO runtime.settlement_source_topic_identity
                   (source_generation,event_stream,command_topic,command_topic_id,
                    venue_event_topic,venue_event_topic_id)
                   VALUES (?::uuid,?,'REEF_COMMANDS',?,'REEF_VENUE_EVENTS',?)"""
            ).use { statement ->
                statement.setString(1, generation)
                statement.setString(2, stream)
                statement.setString(3, commandTopicId)
                statement.setString(4, venueEventTopicId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertRejectedOutcome(dataSource: DataSource, stream: String,
        batchAlreadyPresent: Boolean = false) {
        dataSource.connection.use { connection ->
            if (!batchAlreadyPresent) connection.prepareStatement(
                """INSERT INTO runtime.canonical_venue_event_batches
                   (event_stream,batch_id,partition_id,first_sequence,last_sequence)
                   VALUES (?,?,0,1,1)""").use { statement ->
                statement.setString(1, stream)
                statement.setString(2, "batch-1")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO runtime.canonical_command_outcomes
                   (event_stream,partition_id,stream_sequence,batch_id,command_id,
                    command_type,payload_hash,instrument_id,order_id,result_status,result_payload)
                   VALUES (?,0,1,'batch-1','command-1','SubmitOrder','hash-1','AAPL',
                           'order-1','rejected',?::jsonb)""").use { statement ->
                statement.setString(1, stream)
                statement.setString(2,
                    """{"effectVersion":1,"rejected":{"eventId":"event-1","orderId":"order-1","code":"R","reason":"bad","occurredAt":"2026-09-28T00:00:00Z"}}""")
                statement.executeUpdate()
            }
        }
    }

    private fun sourceMemberDigest(source: CanonicalOutcomeSource): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("reef.reference.canonical-source.v1", source.eventStream,
            source.partitionId.toString(), source.streamSequence.toString(), source.batchId,
            source.commandId, source.commandType, source.payloadHash, source.instrumentId,
            source.orderId, source.resultStatus, source.resultPayloadJson).forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun repoRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve(
            "scripts/dev/db/migrations/settlement/0012_settlement_journal.sql")) }

    private fun sourceDataSourceOrSkip(): DataSource {
        val url = System.getenv("SETTLEMENT_SOURCE_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_SOURCE_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_SOURCE_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "separate disposable retained-source PostgreSQL database required")
        return RuntimeDataSources.dataSource(url, user, password, "settlement-source-restore-test")
    }

    private fun dataSourcesOrSkip(): Pair<DataSource, DataSource> {
        val journalUrl = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val journalUser = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val journalPassword = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        val finalityUrl = System.getenv("SETTLEMENT_FINALITY_POSTGRES_JDBC_URL_TEST")
        val finalityUser = System.getenv("SETTLEMENT_FINALITY_POSTGRES_USER_TEST")
        val finalityPassword = System.getenv("SETTLEMENT_FINALITY_POSTGRES_PASSWORD_TEST")
        assumeTrue(journalUrl != null && journalUser != null && journalPassword != null &&
            finalityUrl != null && finalityUser != null && finalityPassword != null &&
            journalUrl != finalityUrl, "distinct disposable journal and finality databases required")
        return RuntimeDataSources.dataSource(journalUrl, journalUser, journalPassword,
            "settlement-journal-finality-test") to RuntimeDataSources.dataSource(finalityUrl,
            finalityUser, finalityPassword, "external-settlement-finality-test")
    }
}
