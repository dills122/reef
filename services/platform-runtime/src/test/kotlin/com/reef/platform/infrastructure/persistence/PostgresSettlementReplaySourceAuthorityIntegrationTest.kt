package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalStreamPosition
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.apache.kafka.common.Uuid

class PostgresSettlementReplaySourceAuthorityIntegrationTest {
    @Test
    fun retainedZeroTradeOutcomeIsVerifiedAndPayloadChangeRemainsVisible() = withSource { source ->
        val catalog = PostMatchSourceCatalog(source)
        val generation = catalog.generation()
        val reader = PostgresSettlementReplaySourceAuthority(source)
        insertOutcome(source, 1, "first", "bad")
        insertOutcome(source, 2, "second", "bad")
        val window = window(generation, 0, 2)

        val original = reader.readVerified(STREAM, window)
        assertEquals(listOf(1L, 2L), original.outcomes.map { it.source.streamSequence })
        assertEquals(0, original.outcomes.sumOf { outcome ->
            outcome.effects.count { it.effect is CanonicalEffect.Trade }
        })

        source.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE runtime.canonical_command_outcomes SET result_payload = ?::jsonb WHERE stream_sequence = 2"
            ).use { statement ->
                statement.setString(1, rejected("second", "changed"))
                assertEquals(1, statement.executeUpdate())
            }
        }
        val changed = reader.readVerified(STREAM, window)
        assertNotEquals(original.sourceDigest, changed.sourceDigest)
        assertNotEquals(original.outcomes[1].source.resultPayloadJson,
            changed.outcomes[1].source.resultPayloadJson)
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT result_payload::text FROM runtime.canonical_command_outcomes WHERE stream_sequence = 2"
                ).use { rows ->
                    check(rows.next())
                    assertEquals(rows.getString(1), changed.outcomes[1].source.resultPayloadJson)
                }
            }
        }
        assertFalse(reader.verifyEmpty(STREAM, window(generation, 2, 3).copy(members = emptyList())))
    }

    @Test
    fun gapAndMissingRetainedBatchMembershipFailClosed() = withSource { source ->
        insertOutcome(source, 1, "first", "bad")
        insertOutcome(source, 3, "third", "bad")
        val generation = PostMatchSourceCatalog(source).generation()
        val authority = PostgresSettlementReplaySourceAuthority(source)
        assertFailsWith<IllegalArgumentException> {
            authority.readVerified(STREAM, window(generation, 0, 3))
        }

        source.connection.use { connection ->
            connection.createStatement().use { it.execute("DELETE FROM runtime.canonical_command_outcomes WHERE stream_sequence = 3") }
            connection.createStatement().use { it.execute("DELETE FROM runtime.canonical_venue_event_batches WHERE first_sequence = 1") }
        }
        assertFailsWith<IllegalStateException> {
            authority.readVerified(STREAM, window(generation, 0, 1))
        }
    }

    @Test
    fun foreignStreamInsideRequestedPartitionRangeFailsClosed() = withSource { source ->
        insertOutcome(source, 1, "first", "bad")
        insertOutcome(source, 2, "foreign", "bad", "foreign-replay-stream")
        val generation = PostMatchSourceCatalog(source).generation()
        assertFailsWith<IllegalArgumentException> {
            PostgresSettlementReplaySourceAuthority(source).readVerified(
                STREAM, window(generation, 0, 2))
        }
    }

    @Test
    fun nextRetainedSequenceFindsHoleBoundaryAndRejectsForeignStream() = withSource { source ->
        val reader = PostgresCanonicalOutcomeSourceReader(source)
        assertNull(reader.nextRetainedSequence(STREAM, 0, 0))
        insertOutcome(source, 1, "first", "bad")
        insertOutcome(source, 3, "third", "bad")
        assertEquals(1L, reader.nextRetainedSequence(STREAM, 0, 0))
        assertEquals(3L, reader.nextRetainedSequence(STREAM, 0, 1))
        assertNull(reader.nextRetainedSequence(STREAM, 0, 3))
        insertOutcome(source, 2, "foreign", "bad", "other-stream")
        assertFailsWith<IllegalStateException> {
            reader.nextRetainedSequence(STREAM, 0, 1)
        }
    }

    @Test
    fun retainedZeroTradeWindowComposesWithJournalReplayProof() = withSource { source ->
        insertOutcome(source, 1, "first", "bad")
        insertOutcome(source, 2, "second", "bad")
        val generation = PostMatchSourceCatalog(source).generation()
        val authority = PostgresSettlementReplaySourceAuthority(source)
        val verified = authority.readVerified(STREAM, window(generation, 0, 2))
        val initial = SettlementJournalSourceWindow(0, generation, 0, 0, 2,
            "retained-source-test", SettlementJournalStore.ORIGIN_DIGEST,
            verified.outcomes.map { outcome -> SettlementJournalSourceMember(
                outcome.source.streamSequence, sourceMemberDigest(outcome.source)) })
        val journalWindow = initial.copy(coverageDigest =
            SettlementJournalStore.sourceCoverageDigest(STREAM, initial))
        withJournalSchema(source) { schema ->
            val store = SettlementJournalStore(source, schema)
            val head = store.initialize(STREAM, 1, "replay-source-test")
            store.append(SettlementJournalBatchProposal(STREAM, head.nextBatchSequence,
                head.lastBatchDigest, head.ownerEpoch, head.incarnationId,
                listOf(journalWindow), emptyList(), emptyList()))
            val proof = SettlementJournalReplayProof(store).prove(STREAM, authority, { _, _ -> true })
            assertEquals(2L, proof.head.nextBatchSequence)
            assertEquals(0, proof.resultCount)
            assertEquals(emptyMap(), proof.balances)
            assertEquals(emptySet(), proof.outstandingTradeIds)
        }
    }

    @Test
    fun generationChangeBeforeOrDuringReadFailsClosed() = withSource { source ->
        insertOutcome(source, 1, "first", "bad")
        val catalog = PostMatchSourceCatalog(source)
        val generation = catalog.generation()
        val window = window(generation, 0, 1)
        val changingCatalog = object : PostMatchReadSourceCatalog by catalog {
            var reads = 0
            override fun generation(): String {
                reads++
                return if (reads == 1) catalog.generation() else UUID.randomUUID().toString()
            }
        }
        assertFailsWith<IllegalStateException> {
            PostgresSettlementReplaySourceAuthority(source, changingCatalog).readVerified(STREAM, window)
        }
        assertEquals(2, changingCatalog.reads)

        source.connection.use { connection ->
            connection.createStatement().use {
                assertEquals(1, it.executeUpdate(
                    "UPDATE runtime.postmatch_source_generation SET generation = gen_random_uuid() WHERE singleton = TRUE"))
            }
        }
        assertFailsWith<IllegalStateException> {
            PostgresSettlementReplaySourceAuthority(source).readVerified(STREAM, window)
        }
    }

    @Test
    fun emptyRangeNeedsBrokerEvidenceAndNoRetainedOutcome() = withSource { source ->
        val generation = PostMatchSourceCatalog(source).generation()
        val empty = window(generation, 2, 3).copy(members = emptyList())
        var calls = 0
        val authority = PostgresSettlementReplaySourceAuthority(source,
            emptyRangeAttestor = SettlementEmptyRangeAttestor { stream, candidate ->
                calls++
                assertEquals(STREAM, stream)
                assertEquals(empty, candidate)
                true
            })
        assertTrue(authority.verifyEmpty(STREAM, empty))
        assertEquals(1, calls)
        insertOutcome(source, 3, "retained", "bad")
        assertFalse(authority.verifyEmpty(STREAM, empty))
        assertEquals(1, calls)
        assertFalse(authority.verifyEmpty(STREAM, empty.copy(sourceGeneration = UUID.randomUUID().toString())))
        assertFalse(authority.verifyEmpty(STREAM, empty.copy(members = listOf(
            SettlementJournalSourceMember(3, "member")))))
    }

    @Test
    fun emptyRangeRejectsGenerationChangeDuringBrokerProof() = withSource { source ->
        val generation = PostMatchSourceCatalog(source).generation()
        val empty = window(generation, 0, 1).copy(members = emptyList())
        val authority = PostgresSettlementReplaySourceAuthority(source,
            emptyRangeAttestor = SettlementEmptyRangeAttestor { _, _ ->
                source.connection.use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("UPDATE runtime.postmatch_source_generation " +
                            "SET generation = gen_random_uuid() WHERE singleton = TRUE")
                    }
                }
                true
            })
        assertFalse(authority.verifyEmpty(STREAM, empty))
    }

    @Test
    fun recoveredTrailingFrontierNeedsRetainedHeadAndEveryBoundedBrokerGap() = withSource { source ->
        val generation = PostMatchSourceCatalog(source).generation()
        insertOutcome(source, 1, "retained", "bad")
        val proved = mutableListOf<Pair<Long, Long>>()
        val authority = PostgresSettlementReplaySourceAuthority(source,
            emptyRangeAttestor = SettlementEmptyRangeAttestor { _, window ->
                proved += window.fromExclusiveSequence to window.throughInclusiveSequence
                true
            })
        assertTrue(authority.verifyRecoveredFrontier(STREAM, generation, 0, 1, 5002))
        assertEquals(listOf(1L to 5001L, 5001L to 5002L), proved)
        assertFalse(authority.verifyRecoveredFrontier(STREAM, generation, 0, 2, 5002))
        assertFalse(authority.verifyRecoveredFrontier(STREAM, generation, 0, 1, 5_000_002))
        assertFalse(authority.verifyRecoveredFrontier(STREAM, UUID.randomUUID().toString(), 0, 1, 2))
        insertOutcome(source, 3, "uncovered", "bad")
        assertFalse(authority.verifyRecoveredFrontier(STREAM, generation, 0, 1, 4))
    }

    @Test
    fun kafkaEmptyRangeProbeRequiresPinnedTopicAndMapsCommandOffsets() {
        val origin = CanonicalStreamPosition.origin(3)
        val window = SettlementJournalSourceWindow(0, "source-generation", 3,
            origin + 7, origin + 9, "proof", "digest", emptyList())
        val calls = mutableListOf<List<Any>>()
        val broker = object : SettlementKafkaEmptyRangeBroker {
            override fun hasNoCommittedRecords(topic: String, pinnedTopicId: String,
                partitionId: Int, startOffset: Long, endOffsetExclusive: Long): Boolean {
                calls += listOf(topic, pinnedTopicId, partitionId, startOffset, endOffsetExclusive)
                return true
            }
        }
        val unbound = SettlementKafkaEmptyRangeAttestor(STREAM, "REEF_COMMANDS", "unused",
            SettlementCommandTopicIdentity { _, _, _ -> null }, broker = broker)
        assertFalse(unbound.verify(STREAM, window))
        assertEquals(emptyList(), calls)

        val topicId = Uuid.randomUuid().toString()
        val pinned = SettlementKafkaEmptyRangeAttestor(STREAM, "REEF_COMMANDS", "unused",
            SettlementCommandTopicIdentity { stream, generation, topic ->
                assertEquals(listOf(STREAM, "source-generation", "REEF_COMMANDS"),
                    listOf(stream, generation, topic))
                topicId
            }, broker = broker)
        assertTrue(pinned.verify(STREAM, window))
        assertEquals(listOf<Any>("REEF_COMMANDS", topicId, 3, 7L, 9L), calls.single())
        assertFalse(pinned.verify("foreign-stream", window))
        assertFalse(pinned.verify(STREAM, window.copy(throughInclusiveSequence = origin + 5009)))
        assertEquals(1, calls.size)
    }

    @Test
    fun freshSourcePinsBothTopicIdsAndBindingCannotChange() = withSource { source ->
        val generation = PostMatchSourceCatalog(source).generation()
        val commandId = Uuid.randomUuid().toString()
        val eventId = Uuid.randomUuid().toString()
        val registry = PostgresSettlementSourceTopicIdentity(source,
            SettlementColdKafkaTopicProbe { command, events ->
                assertEquals("REEF_COMMANDS", command)
                assertEquals("REEF_VENUE_EVENTS", events)
                SettlementColdKafkaTopics(commandId, eventId)
            })
        assertEquals(null, registry.topicId(STREAM, generation, "REEF_COMMANDS"))
        assertEquals(SettlementColdKafkaTopics(commandId, eventId),
            registry.enrollFresh(STREAM, "REEF_COMMANDS", "REEF_VENUE_EVENTS"))
        assertEquals(commandId, registry.topicId(STREAM, generation, "REEF_COMMANDS"))
        assertEquals(null, registry.topicId(STREAM, generation, "WRONG_COMMANDS"))
        val binding = registry.readBinding(STREAM, generation)!!
        assertEquals(listOf(generation, STREAM, "REEF_COMMANDS", commandId,
            "REEF_VENUE_EVENTS", eventId), listOf(binding.sourceGeneration,
            binding.eventStream, binding.commandTopic, binding.commandTopicId,
            binding.venueEventTopic, binding.venueEventTopicId))
        assertEquals(binding.digest(), registry.bindingDigest(STREAM, generation,
            "REEF_COMMANDS", "REEF_VENUE_EVENTS"))
        assertEquals(64, binding.digest().length)
        assertNull(registry.bindingDigest(STREAM, generation, "WRONG_COMMANDS", "REEF_VENUE_EVENTS"))
        assertEquals(binding, registry.verifiedBinding(STREAM, generation,
            SettlementSourceTopicVerifier { it.commandTopicId == commandId &&
                it.venueEventTopicId == eventId }))
        assertNull(registry.verifiedBinding(STREAM, generation,
            SettlementSourceTopicVerifier { false }))
        val kafka = SettlementKafkaEmptyRangeAttestor(STREAM, "REEF_COMMANDS", "unused",
            registry, broker = object : SettlementKafkaEmptyRangeBroker {
                override fun hasNoCommittedRecords(topic: String, pinnedTopicId: String,
                    partitionId: Int, startOffset: Long, endOffsetExclusive: Long): Boolean {
                    assertEquals(commandId, pinnedTopicId)
                    assertEquals(listOf<Any>("REEF_COMMANDS", 0, 0L, 1L),
                        listOf<Any>(topic, partitionId, startOffset, endOffsetExclusive))
                    return true
                }
            })
        assertTrue(PostgresSettlementReplaySourceAuthority(source, emptyRangeAttestor = kafka)
            .verifyEmpty(STREAM, window(generation, 0, 1).copy(members = emptyList())))
        source.connection.use { connection ->
            assertFailsWith<java.sql.SQLException> {
                connection.createStatement().use { it.executeUpdate(
                    "UPDATE runtime.settlement_source_topic_identity " +
                        "SET command_topic_id = 'changed' WHERE event_stream = '$STREAM'") }
            }
            assertFailsWith<java.sql.SQLException> {
                connection.createStatement().use { it.executeUpdate(
                    "DELETE FROM runtime.settlement_source_topic_identity WHERE event_stream = '$STREAM'") }
            }
        }
        assertFailsWith<java.sql.SQLException> {
            registry.enrollFresh(STREAM, "REEF_COMMANDS", "REEF_VENUE_EVENTS")
        }
    }

    @Test
    fun populatedSourceAndTopicReplacementCannotBeEnrolled() = withSource { source ->
        val generation = PostMatchSourceCatalog(source).generation()
        val first = SettlementColdKafkaTopics(Uuid.randomUuid().toString(), Uuid.randomUuid().toString())
        val changed = first.copy(venueEventTopicId = Uuid.randomUuid().toString())
        var reads = 0
        val racing = PostgresSettlementSourceTopicIdentity(source,
            SettlementColdKafkaTopicProbe { _, _ -> if (++reads == 1) first else changed })
        assertFailsWith<IllegalStateException> {
            racing.enrollFresh(STREAM, "REEF_COMMANDS", "REEF_VENUE_EVENTS")
        }
        assertEquals(null, racing.topicId(STREAM, generation, "REEF_COMMANDS"))

        insertOutcome(source, 1, "first", "bad")
        val registry = PostgresSettlementSourceTopicIdentity(source,
            SettlementColdKafkaTopicProbe { _, _ -> first })
        assertFailsWith<IllegalStateException> {
            registry.enrollFresh(STREAM, "REEF_COMMANDS", "REEF_VENUE_EVENTS")
        }
        assertEquals(null, registry.topicId(STREAM, generation, "REEF_COMMANDS"))
    }

    @Test
    fun restoredGenerationCannotReusePreviousTopicBinding() = withSource { source ->
        val originalGeneration = PostMatchSourceCatalog(source).generation()
        val topics = SettlementColdKafkaTopics(Uuid.randomUuid().toString(), Uuid.randomUuid().toString())
        val registry = PostgresSettlementSourceTopicIdentity(source,
            SettlementColdKafkaTopicProbe { _, _ -> topics })
        registry.enrollFresh(STREAM, "REEF_COMMANDS", "REEF_VENUE_EVENTS")
        source.connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE runtime.postmatch_source_generation " +
                    "SET generation = gen_random_uuid() WHERE singleton = TRUE")
            }
        }
        val restoredGeneration = PostMatchSourceCatalog(source).generation()
        assertNotEquals(originalGeneration, restoredGeneration)
        assertEquals(null, registry.topicId(STREAM, restoredGeneration, "REEF_COMMANDS"))
        assertEquals(topics.commandTopicId,
            registry.topicId(STREAM, originalGeneration, "REEF_COMMANDS"))
    }

    private fun window(generation: String, from: Long, through: Long) = SettlementJournalSourceWindow(
        0, generation, 0, from, through, "test-proof", "test-digest",
        ((from + 1)..through).map { SettlementJournalSourceMember(it, "test-member") }
    )

    private fun insertOutcome(source: DataSource, sequence: Long, order: String, reason: String,
        eventStream: String = STREAM) {
        source.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO runtime.canonical_venue_event_batches " +
                    "(event_stream,batch_id,partition_id,first_sequence,last_sequence) VALUES (?,?,?,?,?)"
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setString(2, "batch-$sequence")
                statement.setInt(3, 0)
                statement.setLong(4, sequence)
                statement.setLong(5, sequence)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO runtime.canonical_command_outcomes
                    (event_stream,partition_id,stream_sequence,batch_id,command_id,
                     command_type,payload_hash,instrument_id,order_id,result_status,result_payload)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setInt(2, 0)
                statement.setLong(3, sequence)
                statement.setString(4, "batch-$sequence")
                statement.setString(5, "command-$sequence")
                statement.setString(6, "SubmitOrder")
                statement.setString(7, "hash-$sequence")
                statement.setString(8, "AAPL")
                statement.setString(9, order)
                statement.setString(10, "rejected")
                statement.setString(11, rejected(order, reason))
                statement.executeUpdate()
            }
        }
    }

    private fun rejected(order: String, reason: String) =
        """{"effectVersion":1,"rejected":{"eventId":"event-$order","orderId":"$order","code":"R","reason":"$reason","occurredAt":"2026-09-28T00:00:00Z"}}"""

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

    private fun withJournalSchema(source: DataSource, test: (String) -> Unit) {
        val schema = "journal_source_${UUID.randomUUID().toString().replace("-", "")}"
        source.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            val migration = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("scripts/dev/db/migrations/settlement/0012_settlement_journal.sql") }
                .first(Files::exists)
            val sql = Files.readString(migration).lineSequence()
                .filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .replace("settlement.", "$schema.")
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    sql.split(';').map(String::trim).filter(String::isNotBlank)
                        .forEach(statement::execute)
                }
            }
            test(schema)
        } finally {
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun withSource(test: (DataSource) -> Unit) {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        val source = RuntimeDataSources.dataSource(url!!, user!!, password!!, "settlement-replay-source-test")
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA runtime")
                statement.execute("""CREATE TABLE runtime.postmatch_source_generation (
                    singleton BOOLEAN PRIMARY KEY, generation UUID NOT NULL)""")
                statement.execute("INSERT INTO runtime.postmatch_source_generation VALUES (TRUE, gen_random_uuid())")
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
        val migration = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("scripts/dev/db/migrations/runtime/0072_settlement_source_topic_identity.sql") }
            .first(Files::exists)
        source.connection.use { connection ->
            connection.createStatement().use { it.execute(Files.readString(migration)) }
        }
        try {
            test(source)
        } finally {
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA runtime CASCADE") }
            }
        }
    }

    companion object {
        private const val STREAM = "settlement-replay-test"
    }
}
