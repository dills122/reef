package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
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
import org.junit.jupiter.api.Assumptions.assumeTrue

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
