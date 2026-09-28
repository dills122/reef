package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceControl
import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import java.util.Collections
import javax.sql.DataSource

data class SettlementControlLogHead(
    val nextControlSequence: Long,
    val lastControlDigest: String,
    val ownerEpoch: Long,
    val ownerNonce: String,
    val incarnationId: String
)

data class SettlementControlLogProposal(
    val eventStream: String,
    val expectedFirstSequence: Long,
    val expectedPreviousDigest: String,
    val ownerEpoch: Long,
    val ownerNonce: String,
    val incarnationId: String,
    val controls: List<ReferenceControl>
)

data class SettlementControlLogReceipt(
    val firstSequence: Long,
    val lastSequence: Long,
    val batchDigest: String,
    val lastControlDigest: String,
    val duplicate: Boolean
)

/** Byte payload is exposed only as Base64, so a caller cannot mutate a verified row. */
data class SettlementControlLogVerifiedMember(
    val sequence: Long,
    val id: String,
    val kind: String,
    val version: Int,
    val payloadBase64: String,
    val memberDigest: String,
    val prefixDigest: String
) {
    fun decode(): ReferenceControl = SettlementJournalControlCodec.decode(Base64.getDecoder().decode(payloadBase64))
}

data class SettlementControlLogVerifiedBatch(
    val firstSequence: Long,
    val lastSequence: Long,
    val previousDigest: String,
    val batchDigest: String,
    val ownerEpoch: Long,
    val ownerNonce: String,
    val incarnationId: String,
    val members: List<SettlementControlLogVerifiedMember>
)

/** Locally verified prefix. External finality anchoring is a separate recovery requirement. */
class SettlementControlLogVerifiedPrefix internal constructor(
    val eventStream: String,
    val head: SettlementControlLogHead,
    batches: List<SettlementControlLogVerifiedBatch>
) {
    val batches: List<SettlementControlLogVerifiedBatch> = Collections.unmodifiableList(
        batches.map { it.copy(members = Collections.unmodifiableList(ArrayList(it.members))) })

    /** Reference interpreter receives only controls accepted by this independent authority. */
    fun referenceVerifier(): ReferenceControlProofVerifier {
        val accepted = batches.flatMap { it.members }.associateBy { it.id }
        return ReferenceControlProofVerifier { control, computedDigest ->
            val stored = accepted[control.controlId]
            stored != null && stored.sequence == control.controlSequence &&
                stored.decode() == control &&
                stored.memberDigest == controlMemberDigest(control) &&
                computedDigest == referenceDigest(control)
        }
    }
}

/**
 * Default-off primary acceptance candidate for future POLICY/OPENING/FUNDING facts. This log
 * does not authenticate legacy mutable policy or resource rows and does not start a journal writer.
 */
class SettlementControlLogStore(
    private val dataSource: DataSource,
    private val schema: String = "postmatch",
    private val beforeCommit: () -> Unit = {},
    private val maxStreamControls: Int = 100_000
) {
    init {
        require(schema.matches(Regex("[a-z][a-z0-9_]*")) && maxStreamControls in 1..100_000)
    }

    fun initialize(eventStream: String, ownerEpoch: Long, ownerNonce: String,
        incarnationId: String): SettlementControlLogHead {
        require(eventStream.isNotBlank() && ownerEpoch > 0 && ownerNonce.isNotBlank() &&
            incarnationId.isNotBlank())
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO $schema.settlement_control_log_heads
                   (event_stream, last_control_digest, owner_epoch, owner_nonce, incarnation_id)
                   VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_stream) DO NOTHING"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setString(2, SettlementJournalStore.ORIGIN_DIGEST)
                statement.setLong(3, ownerEpoch)
                statement.setString(4, ownerNonce)
                statement.setString(5, incarnationId)
                statement.executeUpdate()
            }
            readHead(connection, eventStream, false).also { head ->
                check(head.ownerEpoch == ownerEpoch && head.ownerNonce == ownerNonce &&
                    head.incarnationId == incarnationId) {
                    "control log owner or incarnation differs from initialization"
                }
            }
        }
    }

    fun head(eventStream: String): SettlementControlLogHead = dataSource.connection.use {
        readHead(it, eventStream, false)
    }

    fun fenceOwner(eventStream: String, expected: SettlementControlLogHead,
        newEpoch: Long, newNonce: String): SettlementControlLogHead {
        require(newEpoch > expected.ownerEpoch && newNonce.isNotBlank() &&
            newNonce != expected.ownerNonce)
        return dataSource.connection.use { connection ->
            val priorAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """UPDATE $schema.settlement_control_log_heads
                       SET owner_epoch = ?, owner_nonce = ?
                       WHERE event_stream = ? AND next_control_sequence = ? AND last_control_digest = ?
                         AND owner_epoch = ? AND owner_nonce = ? AND incarnation_id = ?"""
                ).use { statement ->
                    statement.setLong(1, newEpoch)
                    statement.setString(2, newNonce)
                    statement.setString(3, eventStream)
                    statement.setLong(4, expected.nextControlSequence)
                    statement.setString(5, expected.lastControlDigest)
                    statement.setLong(6, expected.ownerEpoch)
                    statement.setString(7, expected.ownerNonce)
                    statement.setString(8, expected.incarnationId)
                    check(statement.executeUpdate() == 1) {
                        "control log head changed before owner fence"
                    }
                }
                readHead(connection, eventStream, false).also { connection.commit() }
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = priorAutoCommit
            }
        }
    }

    fun appendBatch(proposal: SettlementControlLogProposal): SettlementControlLogReceipt {
        val encoded = encode(proposal)
        return dataSource.connection.use { connection ->
            val priorAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val head = readHead(connection, proposal.eventStream, true)
                check(head.ownerEpoch == proposal.ownerEpoch && head.ownerNonce == proposal.ownerNonce &&
                    head.incarnationId == proposal.incarnationId) {
                    "control log writer is fenced or incarnation changed"
                }
                val receipt = if (head.nextControlSequence > encoded.firstSequence) {
                    check(encoded.lastSequence < head.nextControlSequence) {
                        "control log retry overlaps current frontier"
                    }
                    val saved = readBatch(connection, proposal.eventStream, encoded.firstSequence)
                        ?: error("control log retry has no committed batch")
                    check(saved.lastSequence == encoded.lastSequence &&
                        saved.previousDigest == proposal.expectedPreviousDigest &&
                        saved.batchDigest == encoded.batchDigest &&
                        saved.memberCount == encoded.members.size &&
                        saved.ownerEpoch == proposal.ownerEpoch &&
                        saved.ownerNonce == proposal.ownerNonce &&
                        saved.incarnationId == proposal.incarnationId) {
                        "control log retry differs from committed batch"
                    }
                    val persisted = readMembers(connection, proposal.eventStream,
                        encoded.firstSequence, encoded.members.size + 1)
                    check(persisted.size == encoded.members.size &&
                        persisted.zip(encoded.members).all { (actual, expected) -> actual == expected }) {
                        "control log retry differs from committed members"
                    }
                    val predecessor = if (encoded.firstSequence == 1L)
                        SettlementJournalStore.ORIGIN_DIGEST else
                        prefixAt(connection, proposal.eventStream, encoded.firstSequence - 1)
                            ?: error("control log retry predecessor is missing")
                    check(saved.previousDigest == predecessor) {
                        "control log retry predecessor changed"
                    }
                    if (head.nextControlSequence == encoded.lastSequence + 1) {
                        check(head.lastControlDigest == encoded.lastPrefix) {
                            "control log retry differs from current head"
                        }
                    } else {
                        val successor = readBatch(connection, proposal.eventStream,
                            encoded.lastSequence + 1)
                        check(successor?.previousDigest == encoded.lastPrefix) {
                            "control log retry successor changed"
                        }
                    }
                    SettlementControlLogReceipt(encoded.firstSequence, encoded.lastSequence,
                        encoded.batchDigest, encoded.lastPrefix, true)
                } else {
                    check(head.nextControlSequence == encoded.firstSequence &&
                        head.lastControlDigest == proposal.expectedPreviousDigest) {
                        "control log sequence gap or prior digest changed"
                    }
                    insertBatch(connection, proposal, encoded)
                    insertMembers(connection, proposal.eventStream, encoded)
                    connection.prepareStatement(
                        """UPDATE $schema.settlement_control_log_heads
                           SET next_control_sequence = ?, last_control_digest = ?
                           WHERE event_stream = ? AND next_control_sequence = ?
                             AND last_control_digest = ? AND owner_epoch = ?
                             AND owner_nonce = ? AND incarnation_id = ?"""
                    ).use { statement ->
                        statement.setLong(1, encoded.lastSequence + 1)
                        statement.setString(2, encoded.lastPrefix)
                        statement.setString(3, proposal.eventStream)
                        statement.setLong(4, head.nextControlSequence)
                        statement.setString(5, head.lastControlDigest)
                        statement.setLong(6, head.ownerEpoch)
                        statement.setString(7, head.ownerNonce)
                        statement.setString(8, head.incarnationId)
                        check(statement.executeUpdate() == 1) { "control log head compare-and-swap failed" }
                    }
                    beforeCommit()
                    SettlementControlLogReceipt(encoded.firstSequence, encoded.lastSequence,
                        encoded.batchDigest, encoded.lastPrefix, false)
                }
                connection.commit()
                receipt
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = priorAutoCommit
            }
        }
    }

    /** Full bounded read verifies every durable row, exact payload, batch and prefix link. */
    fun readVerifiedPrefix(eventStream: String, expectedIncarnation: String,
        maxControls: Int = maxStreamControls): SettlementControlLogVerifiedPrefix {
        require(eventStream.isNotBlank() && expectedIncarnation.isNotBlank() &&
            maxControls in 1..maxStreamControls)
        return dataSource.connection.use { connection ->
            val priorAutoCommit = connection.autoCommit
            val priorIsolation = connection.transactionIsolation
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val head = readHead(connection, eventStream, false)
                check(head.incarnationId == expectedIncarnation) {
                    "control log incarnation changed or restored from another incarnation"
                }
                val prefix = verifyPrefix(connection, eventStream, head, maxControls)
                connection.commit()
                prefix
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = priorAutoCommit
                connection.transactionIsolation = priorIsolation
            }
        }
    }

    private data class Encoded(val firstSequence: Long, val lastSequence: Long,
        val batchDigest: String, val lastPrefix: String,
        val members: List<SettlementControlLogVerifiedMember>)

    private fun encode(proposal: SettlementControlLogProposal): Encoded {
        require(proposal.eventStream.isNotBlank() && proposal.expectedFirstSequence > 0 &&
            proposal.expectedPreviousDigest.matches(Regex("[0-9a-f]{64}")) &&
            proposal.ownerEpoch > 0 && proposal.ownerNonce.isNotBlank() &&
            proposal.incarnationId.isNotBlank() && proposal.controls.isNotEmpty() &&
            proposal.controls.size <= 1000 &&
            proposal.expectedFirstSequence <= maxStreamControls &&
            proposal.expectedFirstSequence + proposal.controls.size - 1 <= maxStreamControls) {
            "control log stream exceeds bounded replay capacity"
        }
        require(proposal.controls.map { it.controlSequence } ==
            (proposal.expectedFirstSequence until proposal.expectedFirstSequence + proposal.controls.size).toList()) {
            "control log batch has a sequence gap"
        }
        require(proposal.controls.map { it.controlId }.distinct().size == proposal.controls.size) {
            "control log batch repeats a control ID"
        }
        var prefix = proposal.expectedPreviousDigest
        val members = proposal.controls.map { control ->
            validateControl(control)
            val kind = when (control) {
                is ReferencePolicyActivation -> "POLICY"
                is ReferenceOpening -> "OPENING"
                is ReferenceFunding -> "FUNDING"
            }
            val payload = SettlementJournalControlCodec.encode(control)
            require(payload.size <= 1_048_576) { "control log payload exceeds replay byte bound" }
            val journalMember = SettlementJournalControl(0, control.controlSequence,
                control.controlId, kind, control.version, payload, SettlementJournalStore.ORIGIN_DIGEST)
            val digest = SettlementJournalStore.controlMemberDigest(journalMember)
            prefix = SettlementJournalStore.controlPrefixDigest(prefix,
                journalMember.copy(digest = digest))
            SettlementControlLogVerifiedMember(control.controlSequence, control.controlId, kind,
                control.version, Base64.getEncoder().encodeToString(payload), digest, prefix)
        }
        val last = members.last().sequence
        return Encoded(proposal.expectedFirstSequence, last,
            batchDigest(proposal.eventStream, proposal.expectedPreviousDigest,
                proposal.expectedFirstSequence, last, proposal.ownerEpoch, proposal.ownerNonce,
                proposal.incarnationId, members),
            prefix, members)
    }

    private fun validateControl(control: ReferenceControl) {
        require(control.version == 1 && control.controlSequence > 0 && control.controlId.isNotBlank())
        when (control) {
            is ReferencePolicyActivation -> require(control.runId.isNotBlank() &&
                control.venueSessionId.isNotBlank() && control.profileId.isNotBlank() &&
                control.policyVersion > 0 && control.mode in setOf("instant-post-trade", "ops-realistic") &&
                control.effectiveAfterSourceFrontiers.all { (stream, sequence) ->
                    stream.eventStream.isNotBlank() && stream.sourceGeneration.isNotBlank() &&
                        stream.partitionId in 0..32767 &&
                        sequence >= CanonicalStreamPosition.origin(stream.partitionId) &&
                        sequence <= CanonicalStreamPosition.origin(stream.partitionId) +
                            ((1L shl 48) - 1)
                }) { "control log policy is incomplete" }
            is ReferenceOpening -> require(control.amount >= java.math.BigDecimal.ZERO &&
                validAccount(control.account)) { "control log opening is invalid" }
            is ReferenceFunding -> require(control.amount > java.math.BigDecimal.ZERO &&
                validAccount(control.account) &&
                control.retryTradeIds.distinct() == control.retryTradeIds &&
                control.retryTradeIds.all(String::isNotBlank)) { "control log funding is invalid" }
        }
    }

    private fun validAccount(key: ReferenceAccountKey) = listOf(key.runId,
        key.participantId, key.accountId, key.assetType, key.assetId).all(String::isNotBlank)

    private data class BatchRow(val firstSequence: Long, val lastSequence: Long,
        val previousDigest: String, val batchDigest: String, val ownerEpoch: Long,
        val ownerNonce: String, val incarnationId: String, val memberCount: Int)

    private fun readBatch(connection: Connection, stream: String, first: Long): BatchRow? =
        connection.prepareStatement(
            """SELECT first_control_sequence, last_control_sequence, previous_digest, batch_digest,
                      owner_epoch, owner_nonce, incarnation_id, member_count
               FROM $schema.settlement_control_log_batches
               WHERE event_stream = ? AND first_control_sequence = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setLong(2, first)
            statement.executeQuery().use { rows ->
                if (rows.next()) BatchRow(rows.getLong(1), rows.getLong(2), rows.getString(3),
                    rows.getString(4), rows.getLong(5), rows.getString(6), rows.getString(7),
                    rows.getInt(8)) else null
            }
        }

    private fun prefixAt(connection: Connection, stream: String, sequence: Long): String? =
        connection.prepareStatement(
            """SELECT prefix_digest FROM $schema.settlement_control_log_members
               WHERE event_stream = ? AND control_sequence = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setLong(2, sequence)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun readBatches(connection: Connection, stream: String, maxRows: Int): List<BatchRow> =
        connection.prepareStatement(
            """SELECT first_control_sequence, last_control_sequence, previous_digest, batch_digest,
                      owner_epoch, owner_nonce, incarnation_id, member_count
               FROM $schema.settlement_control_log_batches WHERE event_stream = ?
               ORDER BY first_control_sequence LIMIT ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, maxRows)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(BatchRow(rows.getLong(1), rows.getLong(2),
                        rows.getString(3), rows.getString(4), rows.getLong(5),
                        rows.getString(6), rows.getString(7), rows.getInt(8)))
                }
            }
        }

    private data class MemberRow(val batchFirst: Long, val member: SettlementControlLogVerifiedMember)

    private fun readMembers(connection: Connection, stream: String, batchFirst: Long,
        maxRows: Int):
        List<SettlementControlLogVerifiedMember> =
        connection.prepareStatement(
            """SELECT control_sequence, control_id, control_kind, control_version,
                      payload, member_digest, prefix_digest
               FROM $schema.settlement_control_log_members
               WHERE event_stream = ? AND batch_first_control_sequence = ?
               ORDER BY control_sequence LIMIT ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setLong(2, batchFirst)
            statement.setInt(3, maxRows)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(SettlementControlLogVerifiedMember(rows.getLong(1),
                        rows.getString(2), rows.getString(3), rows.getInt(4),
                        Base64.getEncoder().encodeToString(rows.getBytes(5)), rows.getString(6),
                        rows.getString(7)))
                }
            }
        }

    private fun readAllMembers(connection: Connection, stream: String, maxRows: Int): List<MemberRow> =
        connection.prepareStatement(
            """SELECT batch_first_control_sequence, control_sequence, control_id, control_kind,
                      control_version, payload, member_digest, prefix_digest
               FROM $schema.settlement_control_log_members WHERE event_stream = ?
               ORDER BY control_sequence LIMIT ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, maxRows)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(MemberRow(rows.getLong(1),
                        SettlementControlLogVerifiedMember(rows.getLong(2), rows.getString(3),
                            rows.getString(4), rows.getInt(5),
                            Base64.getEncoder().encodeToString(rows.getBytes(6)), rows.getString(7),
                            rows.getString(8))))
                }
            }
        }

    private fun verifyPrefix(connection: Connection, stream: String,
        head: SettlementControlLogHead, maxControls: Int): SettlementControlLogVerifiedPrefix {
        check(head.nextControlSequence - 1 <= maxControls) { "control log replay exceeds bound" }
        val batches = readBatches(connection, stream, maxControls + 1)
        val rows = readAllMembers(connection, stream, maxControls + 1)
        check(batches.size <= maxControls && rows.size <= maxControls &&
            rows.size.toLong() == head.nextControlSequence - 1) {
            "control log replay exceeds bound or member count differs from head"
        }
        var next = 1L
        var prefix = SettlementJournalStore.ORIGIN_DIGEST
        var cursor = 0
        var priorBatchEpoch = 0L
        var priorBatchNonce: String? = null
        val verified = batches.map { batch ->
            check(batch.firstSequence == next && batch.previousDigest == prefix &&
                batch.incarnationId == head.incarnationId && batch.ownerEpoch <= head.ownerEpoch &&
                batch.ownerNonce.isNotBlank() && batch.memberCount in 1..1000 &&
                batch.lastSequence == batch.firstSequence + batch.memberCount - 1) {
                "control log batch chain, owner or count changed"
            }
            check(batch.ownerEpoch >= priorBatchEpoch &&
                (batch.ownerEpoch != priorBatchEpoch || batch.ownerNonce == priorBatchNonce)) {
                "control log owner epoch or nonce regressed"
            }
            priorBatchEpoch = batch.ownerEpoch
            priorBatchNonce = batch.ownerNonce
            val members = mutableListOf<SettlementControlLogVerifiedMember>()
            repeat(batch.memberCount) {
                check(cursor < rows.size) { "control log batch has missing member" }
                val row = rows[cursor++]
                val member = row.member
                check(row.batchFirst == batch.firstSequence && member.sequence == next) {
                    "control log member sequence or batch identity changed"
                }
                val control = member.decode()
                check(control.controlSequence == member.sequence && control.controlId == member.id &&
                    control.version == member.version && kind(control) == member.kind) {
                    "control log typed payload identity changed"
                }
                validateControl(control)
                check(member.memberDigest == controlMemberDigest(control)) {
                    "control log member digest changed"
                }
                val journalMember = SettlementJournalControl(0, member.sequence, member.id,
                    member.kind, member.version, Base64.getDecoder().decode(member.payloadBase64),
                    member.memberDigest)
                prefix = SettlementJournalStore.controlPrefixDigest(prefix, journalMember)
                check(member.prefixDigest == prefix) { "control log prefix digest changed" }
                members += member
                next++
            }
            check(batch.batchDigest == batchDigest(stream, batch.previousDigest,
                batch.firstSequence, batch.lastSequence, batch.ownerEpoch, batch.ownerNonce,
                batch.incarnationId, members)) { "control log batch digest changed" }
            SettlementControlLogVerifiedBatch(batch.firstSequence, batch.lastSequence,
                batch.previousDigest, batch.batchDigest, batch.ownerEpoch, batch.ownerNonce,
                batch.incarnationId, members)
        }
        check(cursor == rows.size && next == head.nextControlSequence &&
            prefix == head.lastControlDigest &&
            (priorBatchEpoch != head.ownerEpoch || priorBatchNonce == head.ownerNonce)) {
            "control log head or trailing rows changed"
        }
        return SettlementControlLogVerifiedPrefix(stream, head, verified)
    }

    private fun insertBatch(connection: Connection, proposal: SettlementControlLogProposal,
        encoded: Encoded) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_control_log_batches
               (event_stream, first_control_sequence, last_control_sequence, member_count,
                previous_digest, batch_digest, owner_epoch, owner_nonce, incarnation_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setString(1, proposal.eventStream)
            statement.setLong(2, encoded.firstSequence)
            statement.setLong(3, encoded.lastSequence)
            statement.setInt(4, encoded.members.size)
            statement.setString(5, proposal.expectedPreviousDigest)
            statement.setString(6, encoded.batchDigest)
            statement.setLong(7, proposal.ownerEpoch)
            statement.setString(8, proposal.ownerNonce)
            statement.setString(9, proposal.incarnationId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun insertMembers(connection: Connection, stream: String, encoded: Encoded) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_control_log_members
               (event_stream, control_sequence, batch_first_control_sequence, control_id,
                control_kind, control_version, payload, member_digest, prefix_digest)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            encoded.members.forEach { member ->
                statement.setString(1, stream)
                statement.setLong(2, member.sequence)
                statement.setLong(3, encoded.firstSequence)
                statement.setString(4, member.id)
                statement.setString(5, member.kind)
                statement.setInt(6, member.version)
                statement.setBytes(7, Base64.getDecoder().decode(member.payloadBase64))
                statement.setString(8, member.memberDigest)
                statement.setString(9, member.prefixDigest)
                statement.addBatch()
            }
            check(statement.executeBatch().all { it == 1 || it == java.sql.Statement.SUCCESS_NO_INFO }) {
                "control log member insert failed"
            }
        }
    }

    private fun readHead(connection: Connection, stream: String, lock: Boolean): SettlementControlLogHead =
        connection.prepareStatement(
            """SELECT next_control_sequence, last_control_digest, owner_epoch, owner_nonce,
                      incarnation_id FROM $schema.settlement_control_log_heads
               WHERE event_stream = ? ${if (lock) "FOR UPDATE" else ""}"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "control log head is missing" }
                SettlementControlLogHead(rows.getLong(1), rows.getString(2), rows.getLong(3),
                    rows.getString(4), rows.getString(5))
            }
        }

    private companion object {
        fun kind(control: ReferenceControl) = when (control) {
            is ReferencePolicyActivation -> "POLICY"
            is ReferenceOpening -> "OPENING"
            is ReferenceFunding -> "FUNDING"
        }
    }
}

private fun controlMemberDigest(control: ReferenceControl): String {
    val payload = SettlementJournalControlCodec.encode(control)
    return SettlementJournalStore.controlMemberDigest(SettlementJournalControl(0,
        control.controlSequence, control.controlId, when (control) {
            is ReferencePolicyActivation -> "POLICY"
            is ReferenceOpening -> "OPENING"
            is ReferenceFunding -> "FUNDING"
        }, control.version, payload, SettlementJournalStore.ORIGIN_DIGEST))
}

private fun batchDigest(stream: String, previous: String, first: Long, last: Long,
    ownerEpoch: Long, ownerNonce: String, incarnationId: String,
    members: List<SettlementControlLogVerifiedMember>): String = digest(listOf(
        "reef.postmatch.settlement-control-batch.v1", stream, previous, first.toString(),
        last.toString(), ownerEpoch.toString(), ownerNonce, incarnationId,
        members.size.toString()) + members.flatMap { listOf(it.id, it.memberDigest, it.prefixDigest) })

/** Mirrors the reference interpreter's v1 control digest to bind its proof callback. */
private fun referenceDigest(control: ReferenceControl): String = digest(when (control) {
    is ReferencePolicyActivation -> listOf("reef.reference.policy.v1",
        control.controlSequence.toString(), control.controlId, control.runId, control.venueSessionId,
        control.effectiveAfterSourceFrontiers.entries.sortedWith(compareBy(
            { it.key.eventStream }, { it.key.sourceGeneration }, { it.key.partitionId }
        )).flatMap { (stream, frontier) -> listOf(stream.eventStream, stream.sourceGeneration,
            stream.partitionId.toString(), frontier.toString()) }.let(::digest),
        control.profileId, control.policyVersion.toString(), control.mode,
        control.settlementCycle, control.nettingMode, control.ledgerPostingMode, control.selectionSource)
    is ReferenceOpening -> listOf("reef.reference.opening.v1",
        control.controlSequence.toString(), control.controlId) + accountFields(control.account) +
        control.amount.toPlainString()
    is ReferenceFunding -> listOf("reef.reference.funding.v1",
        control.controlSequence.toString(), control.controlId) + accountFields(control.account) +
        listOf(control.amount.toPlainString()) + control.retryTradeIds
})

private fun accountFields(key: ReferenceAccountKey) = listOf(key.runId, key.participantId,
    key.accountId, key.assetType, key.assetId)

private fun digest(fields: List<String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        hash.update(bytes)
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
