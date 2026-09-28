package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectDecoder
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier
import com.reef.platform.application.settlementjournal.ReferenceCoverageEvidence
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceObligation
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreter
import com.reef.platform.application.settlementjournal.ReferenceSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceStep
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

/** A verified snapshot is evidence only. No writer may hydrate from it or skip journal replay. */
data class SettlementJournalSnapshotReceipt(
    val eventStream: String,
    val batchSequence: Long,
    val batchDigest: String,
    val incarnationId: String,
    val stateDigest: String,
    val duplicate: Boolean
)

/**
 * Bounded, default-off snapshot candidate. Genesis replay authenticates retained source and
 * controls before deriving state. `verifyCurrent` repeats that proof and compares exact state
 * bytes. It rejects a later head: reference controlDigest cannot yet resume from its final hash.
 */
class SettlementJournalSnapshotProof(
    private val dataSource: DataSource,
    private val journal: SettlementJournalStore,
    private val schema: String = "settlement",
    private val beforeInsert: () -> Unit = {}
) {
    init { require(schema.matches(Regex("[a-z][a-z0-9_]*"))) }

    fun captureCurrent(
        eventStream: String,
        sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier,
        maxBatches: Int = 256
    ): SettlementJournalSnapshotReceipt {
        val proof = SettlementJournalReplayProof(journal).prove(eventStream, sourceAuthority,
            controlVerifier, maxBatches)
        val head = proof.head
        check(head.nextBatchSequence > 1) { "cannot snapshot an uncommitted journal" }
        val state = derive(eventStream, head, sourceAuthority, controlVerifier)
        check(journal.head(eventStream) == head) { "journal head moved during snapshot build" }
        val digest = stateDigest(eventStream, head, state)
        beforeInsert()
        return dataSource.connection.use { connection ->
            val oldAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                // Keep the final head comparison and one-row insert in the same transaction.
                check(lockedHead(connection, eventStream) == head) {
                    "journal head moved before snapshot commit"
                }
                val sequence = head.nextBatchSequence - 1
                val inserted = connection.prepareStatement(
                    """INSERT INTO $schema.settlement_journal_snapshots
                       (event_stream, batch_sequence, batch_digest, owner_epoch, incarnation_id,
                        control_sequence, control_prefix_digest, state_version, state_bytes, state_digest)
                       VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                       ON CONFLICT (event_stream, batch_sequence) DO NOTHING"""
                ).use { statement ->
                    statement.setString(1, eventStream)
                    statement.setLong(2, sequence)
                    statement.setString(3, head.lastBatchDigest)
                    statement.setLong(4, head.ownerEpoch)
                    statement.setString(5, head.incarnationId)
                    statement.setLong(6, head.lastControlSequence)
                    statement.setString(7, head.lastControlDigest)
                    statement.setBytes(8, state)
                    statement.setString(9, digest)
                    statement.executeUpdate() == 1
                }
                val saved = read(connection, eventStream, sequence)
                check(saved.head == head && saved.state.contentEquals(state) && saved.digest == digest) {
                    "existing snapshot differs from verified state"
                }
                connection.commit()
                SettlementJournalSnapshotReceipt(eventStream, sequence, head.lastBatchDigest,
                    head.incarnationId, digest, !inserted)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = oldAutoCommit
            }
        }
    }

    fun verifyCurrent(
        eventStream: String,
        batchSequence: Long,
        sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier,
        maxBatches: Int = 256
    ): SettlementJournalSnapshotReceipt {
        require(eventStream.isNotBlank() && batchSequence > 0)
        val saved = dataSource.connection.use { read(it, eventStream, batchSequence) }
        val head = journal.head(eventStream)
        check(head == saved.head && batchSequence == head.nextBatchSequence - 1) {
            "snapshot is not bound to current journal head; tail replay is disabled"
        }
        check(saved.digest == stateDigest(eventStream, saved.head, saved.state)) {
            "snapshot state digest changed"
        }
        val proof = SettlementJournalReplayProof(journal).prove(eventStream, sourceAuthority,
            controlVerifier, maxBatches)
        check(proof.head == saved.head) { "journal head changed during snapshot proof" }
        val expected = derive(eventStream, saved.head, sourceAuthority, controlVerifier)
        check(saved.state.contentEquals(expected) && journal.head(eventStream) == saved.head) {
            "snapshot state differs from independent genesis replay"
        }
        return SettlementJournalSnapshotReceipt(eventStream, batchSequence,
            head.lastBatchDigest, head.incarnationId, saved.digest, true)
    }

    private data class Saved(val head: SettlementJournalHead, val state: ByteArray, val digest: String)

    private fun read(connection: Connection, stream: String, sequence: Long): Saved =
        connection.prepareStatement(
            """SELECT batch_digest, owner_epoch, incarnation_id, control_sequence,
                      control_prefix_digest, state_version, state_bytes, state_digest
               FROM $schema.settlement_journal_snapshots
               WHERE event_stream = ? AND batch_sequence = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setLong(2, sequence)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "settlement snapshot is missing" }
                val head = SettlementJournalHead(sequence + 1, rows.getString(1), rows.getLong(2),
                    rows.getString(3), rows.getLong(4), rows.getString(5))
                check(rows.getInt(6) == 1) { "unsupported settlement snapshot state version" }
                val result = Saved(head, rows.getBytes(7), rows.getString(8))
                check(!rows.next()) { "settlement snapshot row repeated" }
                result
            }
        }

    private fun lockedHead(connection: Connection, stream: String): SettlementJournalHead =
        connection.prepareStatement(
            """SELECT next_batch_sequence, last_batch_digest, owner_epoch, incarnation_id,
                      last_control_sequence, last_control_digest
               FROM $schema.settlement_journal_heads WHERE event_stream = ? FOR SHARE"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "journal head is missing" }
                SettlementJournalHead(rows.getLong(1), rows.getString(2), rows.getLong(3),
                    rows.getString(4), rows.getLong(5), rows.getString(6))
            }
        }

    private fun derive(
        stream: String,
        head: SettlementJournalHead,
        sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier
    ): ByteArray {
        val steps = mutableListOf<ReferenceStep>()
        val frontiers = linkedMapOf<ReferenceStreamPartition, Long>()
        val orders = linkedMapOf<String, com.reef.platform.application.postmatch.CanonicalOrderIdentity>()
        val activePolicies = linkedMapOf<Pair<String, String>, ReferencePolicyActivation>()
        val openings = linkedMapOf<ReferenceAccountKey, String>()
        val fundingIds = linkedMapOf<ReferenceAccountKey, MutableList<String>>()
        val seenTradeIds = linkedSetOf<String>()
        val attempts = linkedMapOf<String, Int>()
        val decoder = CanonicalEffectDecoder()
        val sourceVerifier = CanonicalSourceCoverageVerifier()
        var batchDigest = SettlementJournalStore.ORIGIN_DIGEST
        var controlPrefix = SettlementJournalStore.ORIGIN_DIGEST
        var controlSequence = 0L
        for (sequence in 1 until head.nextBatchSequence) {
            val proposal = journal.replayProposalCopy(journal.readVerifiedBatch(stream, sequence))
            check(proposal.expectedPreviousDigest == batchDigest &&
                proposal.incarnationId == head.incarnationId && proposal.eventStream == stream) {
                "snapshot journal chain or incarnation changed"
            }
            batchDigest = journal.preview(proposal).batchDigest
            val ordered = (proposal.sourceWindows.map { it.stepIndex to it } +
                proposal.controls.map { it.stepIndex to it }).sortedBy { it.first }
            check(ordered.map { it.first } == (0 until ordered.size).toList()) {
                "snapshot input step order changed"
            }
            ordered.forEach { (_, member) -> when (member) {
                is SettlementJournalSourceWindow -> {
                    val outcomes = if (member.members.isEmpty()) {
                        check(sourceAuthority.verifyEmpty(stream, member)) {
                            "snapshot empty source range lacks independent proof"
                        }
                        emptyList()
                    } else {
                        val verified = sourceAuthority.readVerified(stream, member)
                        check(verified.eventStream == stream &&
                            verified.sourceGeneration == member.sourceGeneration &&
                            verified.partitionId == member.partitionId &&
                            verified.fromExclusiveSequence == member.fromExclusiveSequence &&
                            verified.throughInclusiveSequence == member.throughInclusiveSequence) {
                            "snapshot retained source identity changed"
                        }
                        val read = verified.outcomes.map { it.source }
                        val rechecked = sourceVerifier.verify(verified.consumerName, stream,
                            member.partitionId, member.sourceGeneration,
                            member.fromExclusiveSequence, member.throughInclusiveSequence, read)
                        check(rechecked.sourceDigest == verified.sourceDigest &&
                            rechecked.outcomes == verified.outcomes &&
                            read.size == member.members.size &&
                            read.map { SettlementJournalSourceMember(it.streamSequence, sourceDigest(it)) } ==
                                member.members) { "snapshot retained source changed" }
                        read
                    }
                    val partition = ReferenceStreamPartition(stream, member.sourceGeneration,
                        member.partitionId)
                    frontiers[partition] = member.throughInclusiveSequence
                    outcomes.forEach { outcome -> decoder.decode(outcome).forEach { effect ->
                        when (val fact = effect.effect) {
                            is CanonicalEffect.Accepted -> fact.newOrder?.let {
                                check(orders.putIfAbsent(it.orderId, it) == null) {
                                    "snapshot order identity repeated"
                                }
                            }
                            is CanonicalEffect.Trade -> seenTradeIds += fact.tradeId
                            else -> Unit
                        }
                    } }
                    steps += ReferenceStep.Source(ReferenceSourceWindow(
                        stream = partition, fromExclusiveSequence = member.fromExclusiveSequence,
                        throughInclusiveSequence = member.throughInclusiveSequence,
                        outcomes = outcomes, evidence = ReferenceCoverageEvidence(
                            proofId = member.coverageProofId, digest = member.coverageDigest)))
                }
                is SettlementJournalControl -> {
                    check(member.sequence == controlSequence + 1 &&
                        member.digest == SettlementJournalStore.controlMemberDigest(member)) {
                        "snapshot control sequence or payload changed"
                    }
                    controlSequence = member.sequence
                    controlPrefix = SettlementJournalStore.controlPrefixDigest(controlPrefix, member)
                    val control = SettlementJournalControlCodec.decode(member.payload)
                    check(control.controlId == member.id && control.controlSequence == member.sequence &&
                        control.version == member.version) { "snapshot decoded control changed" }
                    steps += ReferenceStep.Control(control)
                    when (control) {
                        is ReferencePolicyActivation -> activePolicies[control.runId to control.venueSessionId] = control
                        is ReferenceOpening -> openings[control.account] = control.controlId
                        is ReferenceFunding -> fundingIds.getOrPut(control.account) { mutableListOf() }
                            .add(control.controlId)
                    }
                }
            } }
            proposal.results.forEach { result ->
                attempts[result.tradeId] = result.attemptNumber
            }
        }
        check(batchDigest == head.lastBatchDigest && controlSequence == head.lastControlSequence &&
            controlPrefix == head.lastControlDigest) { "snapshot head or control prefix changed" }
        val evaluation = ReferenceSettlementInterpreter({ _, _ -> true }, controlVerifier).evaluate(steps)
        check(evaluation.results.size == attempts.values.sum() &&
            evaluation.results.groupBy { it.tradeId }.mapValues { it.value.size } == attempts &&
            evaluation.results.map { it.tradeId }.toSet().all { it in seenTradeIds }) {
            "snapshot evaluated attempt state changed"
        }
        check(journal.head(stream) == head) { "journal head moved during snapshot derivation" }
        return encodeState(head, evaluation, frontiers, orders, activePolicies,
            openings, fundingIds, seenTradeIds, attempts)
    }

    private fun encodeState(
        head: SettlementJournalHead,
        evaluation: com.reef.platform.application.settlementjournal.ReferenceEvaluation,
        frontiers: Map<ReferenceStreamPartition, Long>,
        orders: Map<String, com.reef.platform.application.postmatch.CanonicalOrderIdentity>,
        policies: Map<Pair<String, String>, ReferencePolicyActivation>,
        openings: Map<ReferenceAccountKey, String>,
        fundingIds: Map<ReferenceAccountKey, List<String>>,
        seenTrades: Set<String>,
        attempts: Map<String, Int>
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.field("reef.settlement.snapshot.state.v1")
            out.writeLong(head.nextBatchSequence - 1)
            out.field(head.lastBatchDigest)
            out.field(head.incarnationId)
            out.writeLong(head.lastControlSequence)
            out.field(head.lastControlDigest)
            out.writeInt(evaluation.results.size)
            out.writeInt(frontiers.size)
            frontiers.entries.sortedWith(compareBy({ it.key.eventStream },
                { it.key.sourceGeneration }, { it.key.partitionId })).forEach { (key, value) ->
                out.field(key.eventStream); out.field(key.sourceGeneration)
                out.writeInt(key.partitionId); out.writeLong(value)
            }
            out.writeInt(orders.size)
            orders.toSortedMap().values.forEach { order ->
                listOf(order.orderId, order.engineOrderId, order.clientOrderId, order.runId,
                    order.venueSessionId, order.instrumentId, order.participantId, order.accountId,
                    order.side, order.orderType, order.quantityUnits, order.limitPrice,
                    order.currency, order.timeInForce, order.acceptedAt).forEach { out.field(it) }
            }
            out.writeInt(policies.size)
            policies.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).forEach { (_, policy) ->
                out.field(SettlementJournalControlCodec.encode(policy))
            }
            out.writeInt(openings.size)
            openings.entries.sortedBy { accountSortKey(it.key) }.forEach { (key, id) ->
                out.account(key); out.field(id)
            }
            out.writeInt(fundingIds.size)
            fundingIds.entries.sortedBy { accountSortKey(it.key) }.forEach { (key, ids) ->
                out.account(key); out.writeInt(ids.size); ids.forEach { out.field(it) }
            }
            out.writeInt(evaluation.controlDigests.size)
            evaluation.controlDigests.toSortedMap().forEach { (id, digest) ->
                out.field(id); out.field(digest)
            }
            out.writeInt(evaluation.balances.size)
            evaluation.balances.entries.sortedBy { accountSortKey(it.key) }.forEach { (key, amount) ->
                out.account(key); out.field(amount.toPlainString())
            }
            out.writeInt(seenTrades.size)
            seenTrades.sorted().forEach { out.field(it) }
            out.writeInt(attempts.size)
            attempts.toSortedMap().forEach { (id, count) -> out.field(id); out.writeInt(count) }
            out.writeInt(evaluation.outstanding.size)
            evaluation.outstanding.toSortedMap().values.forEach { out.obligation(it) }
        }
        bytes.toByteArray()
    }

    private fun accountSortKey(key: ReferenceAccountKey): String =
        listOf(key.runId, key.participantId, key.accountId, key.assetType, key.assetId)
            .joinToString("\u0000")

    private fun DataOutputStream.account(key: ReferenceAccountKey) {
        listOf(key.runId, key.participantId, key.accountId, key.assetType, key.assetId)
            .forEach { field(it) }
    }

    private fun DataOutputStream.obligation(value: ReferenceObligation) {
        field(value.tradeId); field(value.eventId)
        field(value.position.stream.eventStream); field(value.position.stream.sourceGeneration)
        writeInt(value.position.stream.partitionId); writeLong(value.position.streamSequence)
        writeInt(value.position.effectOrdinal); field(value.sourceMemberDigest)
        field(value.runId); field(value.venueSessionId)
        account(value.buyer); account(value.seller)
        field(value.instrumentId); field(value.currency)
        field(value.quantity.toPlainString()); field(value.cash.toPlainString())
        field(value.occurredAt.toString()); field(value.policyControlId)
        writeInt(value.policyVersion); field(value.mode)
    }

    private fun DataOutputStream.field(value: String) = field(value.toByteArray(StandardCharsets.UTF_8))
    private fun DataOutputStream.field(value: ByteArray) { writeInt(value.size); write(value) }

    private fun stateDigest(stream: String, head: SettlementJournalHead, state: ByteArray): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.field("reef.settlement.snapshot.digest.v1")
                out.field(stream); out.writeLong(head.nextBatchSequence - 1)
                out.field(head.lastBatchDigest); out.writeLong(head.ownerEpoch)
                out.field(head.incarnationId); out.writeLong(head.lastControlSequence)
                out.field(head.lastControlDigest); out.field(state)
            }
            buffer.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
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
}
