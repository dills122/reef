package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettlementJournalSnapshotProofIntegrationTest {
    @Test
    fun retainedAcceptedOrderAndPolicyEnterSnapshotState() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "snapshot-source-${UUID.randomUUID()}"
            val journal = SettlementJournalStore(dataSource, schema)
            val head = journal.initialize(stream, 1, "incarnation-1")
            val policy = ReferencePolicyActivation(controlSequence = 1, controlId = "policy-1",
                runId = "run-1", venueSessionId = "session-1",
                effectiveAfterSourceFrontiers = emptyMap(), profileId = "instant-post-trade-v1",
                policyVersion = 1, mode = "instant-post-trade", settlementCycle = "T+0",
                nettingMode = "gross", ledgerPostingMode = "gross-dvp", selectionSource = "fixture")
            val policyPayload = SettlementJournalControlCodec.encode(policy)
            val unsealed = SettlementJournalControl(0, 1, policy.controlId, "POLICY", 1,
                policyPayload, SettlementJournalStore.ORIGIN_DIGEST)
            val control = unsealed.copy(digest = SettlementJournalStore.controlMemberDigest(unsealed))
            val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-1","orderId":"order-1","occurredAt":"2026-09-28T00:00:00Z"},"acceptedOrder":{"orderId":"order-1","engineOrderId":"engine-order-1","clientOrderId":"client-order-1","runId":"run-1","venueSessionId":"session-1","instrumentId":"AAPL","participantId":"participant-1","accountId":"account-1","side":"BUY","orderType":"LIMIT","quantityUnits":"1","limitPrice":"50","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-28T00:00:00Z"},"orderStates":[{"orderId":"order-1","instrumentId":"AAPL","side":"BUY","status":"OPEN","originalQuantity":"1","remainingQuantity":"1","limitPrice":"50","currency":"USD","lastUpdatedAt":"2026-09-28T00:00:00Z"}]}"""
            val source = CanonicalOutcomeSource(stream, 0, 1, "batch-1", "command-1",
                "SubmitOrder", "hash-1", "AAPL", "order-1", "accepted", payload)
            val member = SettlementJournalSourceMember(1, sourceDigest(source))
            val initialWindow = SettlementJournalSourceWindow(1, "generation-1", 0, 0, 1,
                "retained-proof", SettlementJournalStore.ORIGIN_DIGEST, listOf(member))
            val window = initialWindow.copy(coverageDigest =
                SettlementJournalStore.sourceCoverageDigest(stream, initialWindow))
            journal.append(SettlementJournalBatchProposal(stream, 1, head.lastBatchDigest,
                head.ownerEpoch, head.incarnationId, listOf(window), listOf(control), emptyList()))
            val authority = object : SettlementReplaySourceAuthority {
                override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow) =
                    CanonicalSourceCoverageVerifier().verify("snapshot-test", eventStream, 0,
                        "generation-1", 0, 1, listOf(source))
                override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow) = false
            }
            val verifier = com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier { _, _ -> true }
            val snapshots = SettlementJournalSnapshotProof(dataSource, journal, schema)
            snapshots.captureCurrent(stream, authority, verifier)
            assertEquals(1L, snapshots.verifyCurrent(stream, 1, authority, verifier).batchSequence)
            val state = readStatePrefix(snapshotBytes(dataSource, schema, stream))
            assertEquals("reef.settlement.snapshot.state.v1", state.version)
            assertEquals(1L, state.batchSequence)
            assertEquals("incarnation-1", state.incarnationId)
            assertEquals(1L, state.controlSequence)
            assertEquals(0, state.resultCount)
            assertEquals(listOf(SourceFrontier(stream, "generation-1", 0, 1)), state.frontiers)
            assertEquals(1, state.orders.size)
            assertEquals(listOf("order-1", "engine-order-1", "client-order-1", "run-1",
                "session-1", "AAPL", "participant-1", "account-1", "BUY", "LIMIT",
                "1", "50", "USD", "DAY", "2026-09-28T00:00:00Z"), state.orders.single())
            assertEquals(listOf(policy), state.policies)
            assertEquals(emptyMap(), state.balances)
            assertEquals(0, state.outstandingCount)
        }
    }

    @Test
    fun completeDerivedStateIsAtomicIdempotentAndBoundToCurrentHead() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "snapshot-${UUID.randomUUID()}"
            val journal = SettlementJournalStore(dataSource, schema)
            val initial = journal.initialize(stream, 1, "incarnation-1")
            val opening = ReferenceOpening(controlSequence = 1, controlId = "open-cash",
                account = ReferenceAccountKey("run-1", "participant-1", "account-1", "CASH", "USD"),
                amount = BigDecimal("50.00"))
            val payload = SettlementJournalControlCodec.encode(opening)
            val unsealedControl = SettlementJournalControl(0, 1, opening.controlId, "OPENING", 1,
                payload, SettlementJournalStore.ORIGIN_DIGEST)
            val control = unsealedControl.copy(digest =
                SettlementJournalStore.controlMemberDigest(unsealedControl))
            val firstWindow = emptyWindow(stream, 1, 0, 1)
            journal.append(SettlementJournalBatchProposal(stream, 1, initial.lastBatchDigest,
                initial.ownerEpoch, initial.incarnationId, listOf(firstWindow), listOf(control), emptyList()))
            val source = emptyAuthority(stream)
            val controlVerifier = com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier { _, _ -> true }
            val crashed = SettlementJournalSnapshotProof(dataSource, journal, schema) {
                error("injected crash before snapshot insert")
            }
            assertFailsWith<IllegalStateException> {
                crashed.captureCurrent(stream, source, controlVerifier)
            }
            assertEquals(0L, count(dataSource, schema, stream))
            val snapshots = SettlementJournalSnapshotProof(dataSource, journal, schema)
            val receipt = snapshots.captureCurrent(stream, source, controlVerifier)
            assertEquals(1L, receipt.batchSequence)
            assertTrue(!receipt.duplicate)
            assertTrue(snapshots.captureCurrent(stream, source, controlVerifier).duplicate)
            assertEquals(receipt.stateDigest,
                snapshots.verifyCurrent(stream, 1, source, controlVerifier).stateDigest)
            assertEquals(1L, count(dataSource, schema, stream))
            val state = readStatePrefix(snapshotBytes(dataSource, schema, stream))
            assertEquals(mapOf(listOf("run-1", "participant-1", "account-1", "CASH", "USD")
                to "50.00"), state.balances)
            assertEquals(0, state.outstandingCount)

            // Snapshot is intentionally proof-only: no tail may be resumed from it yet.
            val head = journal.head(stream)
            journal.append(SettlementJournalBatchProposal(stream, 2, head.lastBatchDigest,
                head.ownerEpoch, head.incarnationId, listOf(emptyWindow(stream, 0, 1, 2)),
                emptyList(), emptyList()))
            assertFailsWith<IllegalStateException> {
                snapshots.verifyCurrent(stream, 1, source, controlVerifier)
            }
        }
    }

    @Test
    fun tamperedBytesAndIncarnationAreRejected() {
        val dataSource = dataSourceOrSkip()
        withMigratedSchema(dataSource) { schema ->
            val stream = "snapshot-${UUID.randomUUID()}"
            val journal = SettlementJournalStore(dataSource, schema)
            val head = journal.initialize(stream, 1, "incarnation-1")
            journal.append(SettlementJournalBatchProposal(stream, 1, head.lastBatchDigest,
                head.ownerEpoch, head.incarnationId, listOf(emptyWindow(stream, 0, 0, 1)),
                emptyList(), emptyList()))
            val source = emptyAuthority(stream)
            val verifier = com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier { _, _ -> true }
            val snapshots = SettlementJournalSnapshotProof(dataSource, journal, schema)
            snapshots.captureCurrent(stream, source, verifier)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_snapshots SET state_bytes = ?
                       WHERE event_stream = ? AND batch_sequence = 1"""
                ).use { statement ->
                    statement.setBytes(1, byteArrayOf(1, 2, 3))
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                snapshots.verifyCurrent(stream, 1, source, verifier)
            }
            assertFailsWith<IllegalStateException> {
                snapshots.captureCurrent(stream, source, verifier)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_snapshots SET incarnation_id = ?
                       WHERE event_stream = ? AND batch_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, "wrong-incarnation")
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                snapshots.verifyCurrent(stream, 1, source, verifier)
            }
        }
    }

    private fun emptyWindow(stream: String, step: Int, from: Long, through: Long):
        SettlementJournalSourceWindow {
        val unsealed = SettlementJournalSourceWindow(step, "generation-1", 0, from, through,
            "external-absence-$from-$through", SettlementJournalStore.ORIGIN_DIGEST, emptyList())
        return unsealed.copy(coverageDigest = SettlementJournalStore.sourceCoverageDigest(stream, unsealed))
    }

    private fun sourceDigest(source: CanonicalOutcomeSource): String {
        val hash = MessageDigest.getInstance("SHA-256")
        listOf("reef.reference.canonical-source.v1", source.eventStream,
            source.partitionId.toString(), source.streamSequence.toString(), source.batchId,
            source.commandId, source.commandType, source.payloadHash, source.instrumentId,
            source.orderId, source.resultStatus, source.resultPayloadJson).forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SourceFrontier(val eventStream: String, val sourceGeneration: String,
        val partitionId: Int, val sequence: Long)

    private data class StatePrefix(val version: String, val batchSequence: Long,
        val incarnationId: String, val controlSequence: Long, val resultCount: Int,
        val frontiers: List<SourceFrontier>, val orders: List<List<String>>,
        val policies: List<ReferencePolicyActivation>,
        val balances: Map<List<String>, String>, val outstandingCount: Int)

    private fun readStatePrefix(bytes: ByteArray): StatePrefix =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.field()
            val sequence = input.readLong()
            input.field() // journal batch digest
            val incarnation = input.field()
            val controlSequence = input.readLong()
            input.field() // chainable control prefix
            val resultCount = input.readInt()
            val frontiers = List(input.readInt()) {
                SourceFrontier(input.field(), input.field(), input.readInt(), input.readLong())
            }
            val orders = List(input.readInt()) { List(15) { input.field() } }
            val policies = List(input.readInt()) {
                SettlementJournalControlCodec.decode(input.bytes()) as ReferencePolicyActivation
            }
            repeat(input.readInt()) { repeat(6) { input.field() } } // opening account and ID
            repeat(input.readInt()) {
                repeat(5) { input.field() }
                repeat(input.readInt()) { input.field() }
            }
            repeat(input.readInt()) { input.field(); input.field() } // control ID and digest
            val balances = linkedMapOf<List<String>, String>()
            repeat(input.readInt()) { balances[List(5) { input.field() }] = input.field() }
            repeat(input.readInt()) { input.field() } // seen trades
            repeat(input.readInt()) { input.field(); input.readInt() } // attempt counts
            val outstandingCount = input.readInt()
            require(outstandingCount == 0 && input.available() == 0) {
                "fixture snapshot has unexpected obligation or trailing bytes"
            }
            StatePrefix(version, sequence, incarnation, controlSequence,
                resultCount, frontiers, orders, policies, balances, outstandingCount)
        }

    private fun snapshotBytes(dataSource: DataSource, schema: String, stream: String): ByteArray =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT state_bytes FROM $schema.settlement_journal_snapshots WHERE event_stream = ?"
            ).use { statement ->
                statement.setString(1, stream)
                statement.executeQuery().use { rows -> rows.next(); rows.getBytes(1) }
            }
        }

    private fun DataInputStream.field(): String = String(bytes(), StandardCharsets.UTF_8)

    private fun DataInputStream.bytes(): ByteArray {
        val length = readInt()
        require(length in 0..1_048_576) { "snapshot field length is invalid" }
        return ByteArray(length).also { readFully(it) }
    }

    private fun emptyAuthority(expectedStream: String): SettlementReplaySourceAuthority =
        object : SettlementReplaySourceAuthority {
            override fun readVerified(eventStream: String, window: SettlementJournalSourceWindow):
                VerifiedCanonicalSourceWindow = error("unexpected nonempty source read")
            override fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow) =
                eventStream == expectedStream && window.coverageProofId.startsWith("external-absence-")
        }

    private fun count(dataSource: DataSource, schema: String, stream: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM $schema.settlement_journal_snapshots WHERE event_stream = ?"
            ).use { statement ->
                statement.setString(1, stream)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
        }

    private fun withMigratedSchema(dataSource: DataSource, run: (String) -> Unit) {
        val schema = "journal_snapshot_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.exists(it.resolve("scripts/dev/db/migrations/settlement/0012_settlement_journal.sql")) }
            listOf("0012_settlement_journal.sql", "0014_settlement_journal_snapshots.sql")
                .forEach { migration ->
                    val sql = Files.readString(root.resolve("scripts/dev/db/migrations/settlement/$migration"))
                        .lineSequence().filterNot { it.trimStart().startsWith("--") }
                        .joinToString("\n").replace("settlement.", "$schema.")
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            sql.split(';').map(String::trim).filter(String::isNotBlank)
                                .forEach(statement::execute)
                        }
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
        return RuntimeDataSources.dataSource(url, user, password, "settlement-journal-snapshot")
    }
}
