package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A lease token is minted by the independent authority, never by a restored journal database. */
data class SettlementJournalExternalLease(
    val eventStream: String,
    val journalIncarnationId: String,
    val epoch: Long,
    val nonce: String,
    val expiresAt: Instant
)

data class SettlementJournalAcknowledgedCommit(
    val journalReceipt: SettlementJournalCommitReceipt,
    val finalityAnchor: SettlementJournalFinalityAnchor
)

/** Source row and both live Kafka topic incarnations must agree on every recovery read. */
class PostgresSettlementSourceBindingDigestReader(
    private val sourceCatalog: PostMatchReadSourceCatalog,
    private val sourceIdentity: PostgresSettlementSourceTopicIdentity,
    private val topicVerifier: SettlementSourceTopicVerifier
) : SettlementSourceBindingDigestReader {
    override fun readDigest(eventStream: String): String? {
        val generation = sourceCatalog.generation()
        val binding = sourceIdentity.verifiedBinding(eventStream, generation, topicVerifier)
            ?: return null
        if (sourceCatalog.generation() != generation ||
            sourceIdentity.readBinding(eventStream, generation) != binding) return null
        return binding.digest()
    }
}

/**
 * Monotonic, durable CAS store. Its DataSource must point to a separately restored PostgreSQL
 * service, not the source/control or settlement databases. This class cannot prove deployment
 * independence; wiring and backup policy must enforce it.
 */
class PostgresSettlementJournalFinalityAuthority(
    private val dataSource: DataSource,
    private val schema: String = "finality"
) : SettlementJournalFinalityAnchorReader {
    init { require(schema.matches(Regex("[a-z][a-z0-9_]*"))) }

    override fun read(eventStream: String): SettlementJournalFinalityAnchor? =
        dataSource.connection.use { connection -> row(connection, eventStream, false)?.anchor }

    /** Bootstrap is permitted only while the journal is still at its empty origin. */
    fun initializeAtOrigin(eventStream: String, head: SettlementJournalHead,
        sourceBindingDigest: String): SettlementJournalFinalityAnchor {
        require(eventStream.isNotBlank())
        require(sourceBindingDigest.matches(Regex("[0-9a-f]{64}"))) {
            "immutable source topic binding is required before finality bootstrap"
        }
        check(head.nextBatchSequence == 1L && head.lastBatchDigest == SettlementJournalStore.ORIGIN_DIGEST &&
            head.lastControlSequence == 0L && head.lastControlDigest == SettlementJournalStore.ORIGIN_DIGEST &&
            head.incarnationId.isNotBlank()) { "external finality may only bootstrap at journal origin" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO $schema.settlement_finality_anchors
                   (event_stream, journal_incarnation_id, source_binding_digest,
                    acknowledged_batch_digest, control_digest)
                   VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_stream) DO NOTHING"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setString(2, head.incarnationId)
                statement.setString(3, sourceBindingDigest)
                statement.setString(4, SettlementJournalStore.ORIGIN_DIGEST)
                statement.setString(5, SettlementJournalStore.ORIGIN_DIGEST)
                statement.executeUpdate()
            }
            val saved = row(connection, eventStream, false)?.anchor ?: error("external finality origin missing")
            check(saved == SettlementJournalFinalityAnchor(eventStream, head.incarnationId, 0,
                SettlementJournalStore.ORIGIN_DIGEST, 0, SettlementJournalStore.ORIGIN_DIGEST,
                sourceBindingDigest)) {
                "external finality origin differs from journal"
            }
            saved
        }
    }

    /** Locking CAS and PostgreSQL clock prevent two live owners sharing one lease epoch. */
    fun acquireLease(expected: SettlementJournalFinalityAnchor, journalOwnerEpoch: Long,
        leaseSeconds: Int = 30): SettlementJournalExternalLease {
        require(leaseSeconds in 1..300 && journalOwnerEpoch > 0)
        check(expected.sourceBindingDigest?.matches(Regex("[0-9a-f]{64}")) == true) {
            "external finality lacks immutable source topic binding"
        }
        return transaction { connection ->
            val current = row(connection, expected.eventStream, true)
                ?: error("external finality anchor is missing")
            check(current.anchor == expected) { "external finality changed before lease acquisition" }
            check(current.expiresAt == null || !current.expiresAt.isAfter(databaseNow(connection))) {
                "external settlement writer lease is still active"
            }
            val nextEpoch = maxOf(current.leaseEpoch, journalOwnerEpoch) + 1
            val nonce = UUID.randomUUID().toString()
            connection.prepareStatement(
                """UPDATE $schema.settlement_finality_anchors
                   SET lease_epoch = ?, lease_nonce = ?,
                       lease_expires_at = clock_timestamp() + (? * interval '1 second')
                   WHERE event_stream = ?"""
            ).use { statement ->
                statement.setLong(1, nextEpoch)
                statement.setString(2, nonce)
                statement.setInt(3, leaseSeconds)
                statement.setString(4, expected.eventStream)
                check(statement.executeUpdate() == 1) { "external lease acquisition lost" }
            }
            val saved = row(connection, expected.eventStream, false)!!
            SettlementJournalExternalLease(expected.eventStream, expected.incarnationId,
                nextEpoch, nonce, saved.expiresAt ?: error("external lease expiry missing"))
        }
    }

    fun renewLease(lease: SettlementJournalExternalLease, leaseSeconds: Int = 30): SettlementJournalExternalLease {
        require(leaseSeconds in 1..300)
        return transaction { connection ->
            val current = requireLiveLease(connection, lease)
            check(current.anchor.incarnationId == lease.journalIncarnationId)
            connection.prepareStatement(
                """UPDATE $schema.settlement_finality_anchors
                   SET lease_expires_at = clock_timestamp() + (? * interval '1 second')
                   WHERE event_stream = ? AND lease_epoch = ? AND lease_nonce = ?
                     AND lease_expires_at > clock_timestamp()"""
            ).use { statement ->
                statement.setInt(1, leaseSeconds)
                statement.setString(2, lease.eventStream)
                statement.setLong(3, lease.epoch)
                statement.setString(4, lease.nonce)
                check(statement.executeUpdate() == 1) { "external lease expired during renewal" }
            }
            lease.copy(expiresAt = row(connection, lease.eventStream, false)!!.expiresAt!!)
        }
    }

    fun requireLiveLease(lease: SettlementJournalExternalLease) {
        transaction { connection -> requireLiveLease(connection, lease) }
    }

    /** Exact next frontier only. Duplicate same-frontier acknowledgment handles lost replies. */
    fun acknowledge(lease: SettlementJournalExternalLease, next: SettlementJournalFinalityAnchor):
        SettlementJournalFinalityAnchor = transaction { connection ->
        val current = requireLiveLease(connection, lease)
        val prior = current.anchor
        check(next.eventStream == lease.eventStream && next.incarnationId == lease.journalIncarnationId &&
            next.acknowledgedBatchSequence >= 0 && next.controlSequence >= prior.controlSequence) {
            "external finality acknowledgment identity or control frontier changed"
        }
        val preservingSnapshot = next.copy(sourceBindingDigest = prior.sourceBindingDigest,
            snapshot = prior.snapshot)
        if (next.acknowledgedBatchSequence == prior.acknowledgedBatchSequence) {
            check(preservingSnapshot == prior) { "conflicting duplicate external finality acknowledgment" }
            return@transaction prior
        }
        check(next.acknowledgedBatchSequence == prior.acknowledgedBatchSequence + 1) {
            "external finality acknowledgment skipped a journal batch"
        }
        connection.prepareStatement(
            """UPDATE $schema.settlement_finality_anchors
               SET acknowledged_batch_sequence = ?, acknowledged_batch_digest = ?,
                   control_sequence = ?, control_digest = ? WHERE event_stream = ?"""
        ).use { statement ->
            statement.setLong(1, next.acknowledgedBatchSequence)
            statement.setString(2, next.acknowledgedBatchDigest)
            statement.setLong(3, next.controlSequence)
            statement.setString(4, next.controlDigest)
            statement.setString(5, next.eventStream)
            check(statement.executeUpdate() == 1) { "external finality acknowledgment lost" }
        }
        preservingSnapshot
    }

    /** Anchor only a snapshot at the current acknowledged frontier under a live external lease. */
    fun publishSnapshot(lease: SettlementJournalExternalLease,
        snapshot: SettlementJournalSnapshotAnchor): SettlementJournalFinalityAnchor =
        transaction { connection ->
            val current = requireLiveLease(connection, lease)
            val prior = current.anchor
            check(snapshot.batchSequence == prior.acknowledgedBatchSequence &&
                snapshot.batchDigest == prior.acknowledgedBatchDigest &&
                snapshot.stateVersion > 0 &&
                snapshot.stateDigest.matches(Regex("[0-9a-f]{64}"))) {
                "snapshot does not match externally acknowledged journal frontier"
            }
            val previous = prior.snapshot
            if (previous != null && previous.batchSequence == snapshot.batchSequence) {
                check(previous == snapshot) { "conflicting external snapshot acknowledgment" }
                return@transaction prior
            }
            check(previous == null || snapshot.batchSequence > previous.batchSequence) {
                "external snapshot frontier cannot rewind"
            }
            connection.prepareStatement(
                """UPDATE $schema.settlement_finality_anchors
                   SET snapshot_batch_sequence = ?, snapshot_batch_digest = ?,
                       snapshot_state_version = ?, snapshot_state_digest = ?
                   WHERE event_stream = ?"""
            ).use { statement ->
                statement.setLong(1, snapshot.batchSequence)
                statement.setString(2, snapshot.batchDigest)
                statement.setInt(3, snapshot.stateVersion)
                statement.setString(4, snapshot.stateDigest)
                statement.setString(5, lease.eventStream)
                check(statement.executeUpdate() == 1) { "external snapshot acknowledgment lost" }
            }
            prior.copy(snapshot = snapshot)
        }

    private data class Row(val anchor: SettlementJournalFinalityAnchor, val leaseEpoch: Long,
        val leaseNonce: String?, val expiresAt: Instant?)

    private fun row(connection: Connection, stream: String, lock: Boolean): Row? =
        connection.prepareStatement(
            """SELECT journal_incarnation_id, acknowledged_batch_sequence,
                      acknowledged_batch_digest, control_sequence, control_digest,
                      source_binding_digest, lease_epoch, lease_nonce, lease_expires_at,
                      snapshot_batch_sequence, snapshot_batch_digest,
                      snapshot_state_version, snapshot_state_digest
               FROM $schema.settlement_finality_anchors WHERE event_stream = ?
               ${if (lock) "FOR UPDATE" else ""}"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else Row(
                    SettlementJournalFinalityAnchor(stream, rows.getString(1), rows.getLong(2),
                        rows.getString(3), rows.getLong(4), rows.getString(5), rows.getString(6),
                        rows.getLong(10).takeUnless { rows.wasNull() }?.let { sequence ->
                            SettlementJournalSnapshotAnchor(sequence, rows.getString(11),
                                rows.getInt(12), rows.getString(13))
                        }),
                    rows.getLong(7), rows.getString(8), rows.getTimestamp(9)?.toInstant())
            }
        }

    private fun requireLiveLease(connection: Connection, lease: SettlementJournalExternalLease): Row {
        val current = row(connection, lease.eventStream, true)
            ?: error("external finality anchor disappeared")
        check(current.anchor.incarnationId == lease.journalIncarnationId &&
            current.leaseEpoch == lease.epoch && current.leaseNonce == lease.nonce &&
            current.expiresAt?.isAfter(databaseNow(connection)) == true) {
            "external settlement writer lease is stale or expired"
        }
        return current
    }

    private fun databaseNow(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT clock_timestamp()").use { rows ->
            check(rows.next())
            rows.getTimestamp(1).toInstant()
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }
}

/** No write starts unless verified journal head exactly equals independent acknowledgment. */
class SettlementJournalFinalityProtocol(
    private val journal: SettlementJournalStore,
    private val authority: PostgresSettlementJournalFinalityAuthority
) {
    /** Old call site remains compilable but cannot start without retained source identity. */
    fun bootstrapAtOrigin(eventStream: String): SettlementJournalFinalityAnchor =
        error("retained source topic binding reader is required before finality bootstrap")

    fun bootstrapAtOrigin(eventStream: String,
        bindingReader: SettlementSourceBindingDigestReader): SettlementJournalFinalityAnchor {
        val head = journal.head(eventStream)
        val binding = bindingReader.readDigest(eventStream)
            ?: error("retained source topic binding is missing")
        val anchor = authority.initializeAtOrigin(eventStream, head, binding)
        check(bindingReader.readDigest(eventStream) == binding) {
            "retained source topic binding changed during finality bootstrap"
        }
        check(journal.head(eventStream) == head) { "journal moved during finality bootstrap" }
        return anchor
    }

    /** Old call site remains compilable but cannot grant a writer lease. */
    fun acquireAndFence(eventStream: String, sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier, maxBatches: Int = 256,
        leaseSeconds: Int = 30): SettlementJournalExternalLease =
        error("retained source topic binding reader is required before writer lease")

    fun acquireAndFence(eventStream: String, sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier,
        bindingReader: SettlementSourceBindingDigestReader, maxBatches: Int = 256,
        leaseSeconds: Int = 30): SettlementJournalExternalLease {
        val sourceBinding = bindingReader.readDigest(eventStream)
            ?: error("retained source topic binding is missing")
        val state = SettlementJournalExternalAnchorGate(
            SettlementJournalReplayProof(journal), authority).prove(eventStream, sourceAuthority,
            controlVerifier, maxBatches)
        val head = state.head
        val anchor = authority.read(eventStream) ?: error("external finality anchor disappeared")
        SettlementJournalExternalAnchorGate.verify(eventStream, state, anchor)
        check(anchor.sourceBindingDigest == sourceBinding &&
            bindingReader.readDigest(eventStream) == sourceBinding) {
            "retained source topic binding differs from external finality"
        }
        val lease = authority.acquireLease(anchor, head.ownerEpoch, leaseSeconds)
        journal.fenceOwner(eventStream, head, lease.epoch)
        check(journal.head(eventStream).ownerEpoch == lease.epoch &&
            authority.read(eventStream) == anchor &&
            bindingReader.readDigest(eventStream) == sourceBinding) {
            "journal, source binding or external finality moved during owner fence"
        }
        return lease
    }

    /** Financial commit plus external acknowledgment; caller exposes no result on failure. */
    fun appendAndAcknowledge(lease: SettlementJournalExternalLease,
        proposal: SettlementJournalBatchProposal): SettlementJournalAcknowledgedCommit {
        check(proposal.eventStream == lease.eventStream &&
            proposal.incarnationId == lease.journalIncarnationId && proposal.ownerEpoch == lease.epoch) {
            "journal proposal does not belong to external writer lease"
        }
        authority.requireLiveLease(lease)
        val receipt = journal.append(proposal)
        val anchor = acknowledgeCommitted(lease, receipt)
        return SettlementJournalAcknowledgedCommit(receipt, anchor)
    }

    /** Call only after a durable append receipt; never publish its result before this returns. */
    fun acknowledgeCommitted(lease: SettlementJournalExternalLease,
        receipt: SettlementJournalCommitReceipt): SettlementJournalFinalityAnchor {
        val head = journal.head(lease.eventStream)
        check(head.incarnationId == lease.journalIncarnationId && head.ownerEpoch == lease.epoch &&
            head.nextBatchSequence - 1 == receipt.batchSequence &&
            head.lastBatchDigest == receipt.batchDigest) {
            "journal head does not match committed receipt for external acknowledgment"
        }
        val next = SettlementJournalFinalityAnchor(lease.eventStream, head.incarnationId,
            receipt.batchSequence, receipt.batchDigest, head.lastControlSequence, head.lastControlDigest)
        val acknowledged = authority.acknowledge(lease, next)
        check(journal.head(lease.eventStream) == head) {
            "journal advanced during external finality acknowledgment"
        }
        return acknowledged
    }

    /** V1 snapshots gain independent tamper evidence, but remain proof-only until V2 hydration. */
    fun publishVerifiedCurrentSnapshot(lease: SettlementJournalExternalLease,
        snapshots: SettlementJournalSnapshotProof,
        sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier,
        bindingReader: SettlementSourceBindingDigestReader,
        maxBatches: Int = 256): SettlementJournalFinalityAnchor {
        authority.requireLiveLease(lease)
        val before = authority.read(lease.eventStream)
            ?: error("external finality anchor disappeared before snapshot")
        check(before.sourceBindingDigest == bindingReader.readDigest(lease.eventStream)) {
            "source topic binding changed before snapshot"
        }
        val captured = snapshots.captureCurrent(lease.eventStream, sourceAuthority,
            controlVerifier, maxBatches)
        val verified = snapshots.verifyCurrent(lease.eventStream, captured.batchSequence,
            sourceAuthority, controlVerifier, maxBatches)
        check(captured.batchSequence == verified.batchSequence &&
            captured.batchDigest == verified.batchDigest &&
            captured.stateDigest == verified.stateDigest &&
            before == authority.read(lease.eventStream) &&
            before.sourceBindingDigest == bindingReader.readDigest(lease.eventStream)) {
            "snapshot, source binding or external finality changed before acknowledgment"
        }
        val head = journal.head(lease.eventStream)
        check(head.ownerEpoch == lease.epoch && head.incarnationId == lease.journalIncarnationId &&
            head.nextBatchSequence - 1 == captured.batchSequence &&
            head.lastBatchDigest == captured.batchDigest) {
            "journal moved before external snapshot acknowledgment"
        }
        val published = authority.publishSnapshot(lease, SettlementJournalSnapshotAnchor(
            captured.batchSequence, captured.batchDigest, 1, captured.stateDigest))
        check(journal.head(lease.eventStream) == head &&
            published.sourceBindingDigest == bindingReader.readDigest(lease.eventStream)) {
            "journal or source binding moved during external snapshot acknowledgment"
        }
        return published
    }

    /** Pin a resumable V2 writer checkpoint outside the settlement restore domain. */
    fun publishWriterCurrentSnapshot(lease: SettlementJournalExternalLease,
        snapshots: SettlementJournalSnapshotProof,
        state: SettlementJournalWriterRecoveryState,
        bindingReader: SettlementSourceBindingDigestReader): SettlementJournalFinalityAnchor {
        authority.requireLiveLease(lease)
        val before = authority.read(lease.eventStream)
            ?: error("external finality anchor disappeared before writer checkpoint")
        val binding = bindingReader.readDigest(lease.eventStream)
            ?: error("retained source topic binding disappeared before writer checkpoint")
        check(before.sourceBindingDigest == binding && state.sourceBindingDigest == binding &&
            state.evaluator.head.ownerEpoch == lease.epoch &&
            state.evaluator.head.ownerIncarnation == lease.journalIncarnationId) {
            "writer checkpoint source identity or owner changed"
        }
        val captured = snapshots.captureWriterCurrent(lease.eventStream, state, before)
        check(before == authority.read(lease.eventStream) &&
            bindingReader.readDigest(lease.eventStream) == binding) {
            "external finality or source binding changed before writer checkpoint acknowledgment"
        }
        val head = journal.head(lease.eventStream)
        check(head.ownerEpoch == lease.epoch && head.incarnationId == lease.journalIncarnationId &&
            head.nextBatchSequence - 1 == captured.batchSequence &&
            head.lastBatchDigest == captured.batchDigest) {
            "journal moved before external writer checkpoint acknowledgment"
        }
        val published = authority.publishSnapshot(lease, SettlementJournalSnapshotAnchor(
            captured.batchSequence, captured.batchDigest,
            SettlementJournalWriterRecoveryCodec.VERSION, captured.stateDigest))
        check(journal.head(lease.eventStream) == head &&
            published.sourceBindingDigest == bindingReader.readDigest(lease.eventStream)) {
            "journal or source binding moved during writer checkpoint acknowledgment"
        }
        return published
    }
}
