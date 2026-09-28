package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** Manifest members are retained JSONB::text outcome digests, one per source sequence. */
data class SettlementJournalSourceMember(val streamSequence: Long, val digest: String)

data class SettlementJournalSourceWindow(
    val stepIndex: Int,
    val sourceGeneration: String,
    val partitionId: Int,
    val fromExclusiveSequence: Long,
    val throughInclusiveSequence: Long,
    val coverageProofId: String,
    val coverageDigest: String,
    val members: List<SettlementJournalSourceMember>
)

data class SettlementJournalControl(
    val stepIndex: Int,
    val sequence: Long,
    val id: String,
    val kind: String,
    val version: Int,
    val payload: ByteArray,
    val digest: String
)

/** Account IDs and asset IDs reconstruct four exact gross DvP effects for SETTLED. */
data class SettlementJournalResult(
    val decisionStepIndex: Int,
    val tradeId: String,
    val attemptNumber: Int,
    val sourceGeneration: String,
    val partitionId: Int,
    val streamSequence: Long,
    val effectOrdinal: Int,
    val sourceMemberDigest: String,
    val eventId: String,
    val runId: String,
    val venueSessionId: String,
    val buyerParticipantId: String,
    val buyerAccountId: String,
    val sellerParticipantId: String,
    val sellerAccountId: String,
    val currency: String,
    val instrumentId: String,
    val cashAmount: BigDecimal,
    val quantityUnits: BigDecimal,
    val occurredAt: Instant,
    val policyControlId: String,
    val openingControlIds: List<String>,
    val fundingControlIds: List<String>,
    val boundControlDigest: String,
    val outcome: String,
    val breakReason: String?,
    val workflowFacts: String
)

data class SettlementJournalBatchProposal(
    val eventStream: String,
    val expectedBatchSequence: Long,
    val expectedPreviousDigest: String,
    val ownerEpoch: Long,
    val incarnationId: String,
    val sourceWindows: List<SettlementJournalSourceWindow>,
    val controls: List<SettlementJournalControl>,
    val results: List<SettlementJournalResult>
)

data class SettlementJournalHead(
    val nextBatchSequence: Long,
    val lastBatchDigest: String,
    val ownerEpoch: Long,
    val incarnationId: String,
    val lastControlSequence: Long,
    val lastControlDigest: String
)

data class SettlementJournalAppendTimings(
    val serializationNanos: Long,
    val headWaitNanos: Long,
    val appendAndCommitNanos: Long
)

data class SettlementJournalCommitReceipt(
    val batchSequence: Long,
    val proposalDigest: String,
    val batchDigest: String,
    val resultCount: Int,
    val duplicate: Boolean,
    val timings: SettlementJournalAppendTimings
)

data class SettlementJournalProposalPreview(
    val proposalDigest: String,
    val batchDigest: String,
    val resultCount: Int
)

/**
 * Default-off append authority. Caller proves source/control inputs and evaluates tentative state;
 * only this durable receipt permits making tentative account state visible. Restore proof is PMJ-03.
 */
class SettlementJournalStore(
    private val dataSource: DataSource,
    private val schema: String = "settlement",
    private val beforeCommit: () -> Unit = {}
) {
    init { require(schema.matches(Regex("[a-z][a-z0-9_]*"))) }

    fun initialize(eventStream: String, ownerEpoch: Long, incarnationId: String): SettlementJournalHead {
        require(eventStream.isNotBlank() && ownerEpoch > 0 && incarnationId.isNotBlank())
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO $schema.settlement_journal_heads
                   (event_stream, last_batch_digest, owner_epoch, incarnation_id, last_control_digest)
                   VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_stream) DO NOTHING"""
            ).use { statement ->
                statement.setString(1, eventStream)
                statement.setString(2, ORIGIN_DIGEST)
                statement.setLong(3, ownerEpoch)
                statement.setString(4, incarnationId)
                statement.setString(5, ORIGIN_DIGEST)
                statement.executeUpdate()
            }
            readHead(connection, eventStream, false)
        }
    }

    fun head(eventStream: String): SettlementJournalHead = dataSource.connection.use { connection ->
        readHead(connection, eventStream, false)
    }

    /** Same-incarnation owner takeover. A changed incarnation requires PMJ-03 restore proof. */
    fun fenceOwner(eventStream: String, expected: SettlementJournalHead, newOwnerEpoch: Long): SettlementJournalHead {
        require(newOwnerEpoch > expected.ownerEpoch)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE $schema.settlement_journal_heads SET owner_epoch = ?
                   WHERE event_stream = ? AND next_batch_sequence = ? AND last_batch_digest = ?
                     AND owner_epoch = ? AND incarnation_id = ? AND last_control_sequence = ?
                     AND last_control_digest = ?"""
            ).use { statement ->
                statement.setLong(1, newOwnerEpoch)
                statement.setString(2, eventStream)
                statement.setLong(3, expected.nextBatchSequence)
                statement.setString(4, expected.lastBatchDigest)
                statement.setLong(5, expected.ownerEpoch)
                statement.setString(6, expected.incarnationId)
                statement.setLong(7, expected.lastControlSequence)
                statement.setString(8, expected.lastControlDigest)
                check(statement.executeUpdate() == 1) { "journal head changed during owner fence" }
            }
            readHead(connection, eventStream, false)
        }
    }

    private fun freeze(input: SettlementJournalBatchProposal): SettlementJournalBatchProposal =
        input.copy(
            sourceWindows = input.sourceWindows.map { it.copy(members = it.members.toList()) },
            controls = input.controls.map { it.copy(payload = it.payload.copyOf()) },
            results = input.results.map { it.copy(
                openingControlIds = it.openingControlIds.toList(),
                fundingControlIds = it.fundingControlIds.toList()) }
        )

    /** Pure hash preview for coordinator verification; no journal state is read or changed. */
    fun preview(input: SettlementJournalBatchProposal): SettlementJournalProposalPreview {
        val proposal = freeze(input)
        val proposalDigest = encode(proposal).proposalDigest
        return SettlementJournalProposalPreview(proposalDigest,
            digest(listOf("reef.settlement.journal.batch.v1", proposal.expectedPreviousDigest,
                proposal.expectedBatchSequence.toString(), proposalDigest)), proposal.results.size)
    }

    fun append(input: SettlementJournalBatchProposal): SettlementJournalCommitReceipt {
        val proposal = freeze(input)
        val serializationStart = System.nanoTime()
        val encoded = encode(proposal)
        val serializationNanos = System.nanoTime() - serializationStart
        return dataSource.connection.use { connection ->
            val oldAutoCommit = connection.autoCommit
            connection.autoCommit = false
            val waitStart = System.nanoTime()
            try {
                val head = readHead(connection, proposal.eventStream, true)
                val headWaitNanos = System.nanoTime() - waitStart
                val appendStart = System.nanoTime()
                if (head.nextBatchSequence > proposal.expectedBatchSequence) {
                    val saved = committedBatch(connection, proposal.eventStream, proposal.expectedBatchSequence)
                    check(saved != null && saved.proposalDigest == encoded.proposalDigest &&
                        saved.previousDigest == proposal.expectedPreviousDigest &&
                        saved.ownerEpoch == proposal.ownerEpoch &&
                        saved.incarnationId == proposal.incarnationId &&
                        saved.sourceWindowCount == proposal.sourceWindows.size &&
                        saved.controlCount == proposal.controls.size &&
                        saved.resultCount == proposal.results.size &&
                        saved.batchDigest == digest(listOf("reef.settlement.journal.batch.v1",
                            proposal.expectedPreviousDigest, proposal.expectedBatchSequence.toString(),
                            encoded.proposalDigest))) { "committed journal batch differs from retry" }
                    verifyStoredMembers(connection, proposal, encoded)
                    connection.commit()
                    return@use SettlementJournalCommitReceipt(proposal.expectedBatchSequence,
                        encoded.proposalDigest, saved.batchDigest,
                        proposal.results.size, true,
                        SettlementJournalAppendTimings(serializationNanos, headWaitNanos,
                            System.nanoTime() - appendStart))
                }
                check(head.nextBatchSequence == proposal.expectedBatchSequence &&
                    head.lastBatchDigest == proposal.expectedPreviousDigest &&
                    head.ownerEpoch == proposal.ownerEpoch && head.incarnationId == proposal.incarnationId) {
                    "journal head, owner epoch or incarnation changed"
                }
                validateSourceFrontiers(connection, proposal)
                check(proposal.controls.firstOrNull()?.sequence == null ||
                    proposal.controls.first().sequence == head.lastControlSequence + 1) {
                    "journal control frontier gap"
                }
                val prefixByStep = mutableMapOf<Int, String>()
                var controlPrefix = head.lastControlDigest
                proposal.controls.sortedBy { it.stepIndex }.forEach { control ->
                    controlPrefix = controlPrefixDigest(controlPrefix, control)
                    prefixByStep[control.stepIndex] = controlPrefix
                }
                val boundPrefixAtStep = mutableMapOf<Int, String>()
                var prefix = head.lastControlDigest
                (0 until proposal.sourceWindows.size + proposal.controls.size).forEach { step ->
                    prefix = prefixByStep[step] ?: prefix
                    boundPrefixAtStep[step] = prefix
                }
                proposal.results.forEach { result ->
                    check(result.boundControlDigest == boundPrefixAtStep[result.decisionStepIndex]) {
                        "result control prefix does not match ordered input"
                    }
                }
                val batchDigest = digest(listOf("reef.settlement.journal.batch.v1",
                    head.lastBatchDigest, head.nextBatchSequence.toString(), encoded.proposalDigest))
                insertBatch(connection, proposal, encoded.proposalDigest, batchDigest)
                insertWindows(connection, proposal, encoded)
                insertControls(connection, proposal, encoded, prefixByStep)
                validateResultBindings(connection, proposal)
                validateDelayedFirstAttempts(connection, proposal)
                validateRetries(connection, proposal)
                insertResults(connection, proposal, encoded)
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_heads
                       SET next_batch_sequence = next_batch_sequence + 1, last_batch_digest = ?,
                           last_control_sequence = ?, last_control_digest = ?
                       WHERE event_stream = ? AND next_batch_sequence = ? AND last_batch_digest = ?
                         AND owner_epoch = ? AND incarnation_id = ?"""
                ).use { statement ->
                    statement.setString(1, batchDigest)
                    statement.setLong(2, proposal.controls.lastOrNull()?.sequence ?: head.lastControlSequence)
                    statement.setString(3, controlPrefix)
                    statement.setString(4, proposal.eventStream)
                    statement.setLong(5, head.nextBatchSequence)
                    statement.setString(6, head.lastBatchDigest)
                    statement.setLong(7, head.ownerEpoch)
                    statement.setString(8, head.incarnationId)
                    check(statement.executeUpdate() == 1) { "journal head compare-and-swap failed" }
                }
                beforeCommit()
                connection.commit()
                SettlementJournalCommitReceipt(proposal.expectedBatchSequence,
                    encoded.proposalDigest, batchDigest,
                    proposal.results.size, false,
                    SettlementJournalAppendTimings(serializationNanos, headWaitNanos,
                        System.nanoTime() - appendStart))
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = oldAutoCommit
            }
        }
    }

    private data class Encoded(
        val proposalDigest: String,
        val windowBytes: List<ByteArray>,
        val windowDigests: List<String>,
        val controlPayloads: List<ByteArray>,
        val resultDigests: List<String>
    )

    private fun encode(proposal: SettlementJournalBatchProposal): Encoded {
        require(proposal.eventStream.isNotBlank() && proposal.expectedBatchSequence > 0 &&
            proposal.ownerEpoch > 0 && proposal.incarnationId.isNotBlank() &&
            hexDigest(proposal.expectedPreviousDigest))
        require(proposal.sourceWindows.isNotEmpty() || proposal.controls.isNotEmpty() || proposal.results.isNotEmpty())
        val steps = proposal.sourceWindows.map { it.stepIndex } + proposal.controls.map { it.stepIndex }
        require(steps.sorted() == (0 until steps.size).toList()) { "journal step order has gap or duplicate" }
        require(proposal.results.map { it.decisionStepIndex }.zipWithNext().all { (a, b) -> a <= b }) {
            "journal result order differs from decision steps"
        }
        val memberKeys = mutableMapOf<Triple<Int, Long, String>, String>()
        val windowBytes = proposal.sourceWindows.map { window ->
            require(window.sourceGeneration.isNotBlank() && window.partitionId in 0..32767 &&
                window.fromExclusiveSequence >= CanonicalStreamPosition.origin(window.partitionId) &&
                window.throughInclusiveSequence > window.fromExclusiveSequence &&
                window.throughInclusiveSequence - window.fromExclusiveSequence <= 5000 &&
                window.coverageProofId.isNotBlank() && hexDigest(window.coverageDigest) &&
                (window.members.isEmpty() || window.members.size.toLong() ==
                    window.throughInclusiveSequence - window.fromExclusiveSequence))
            window.members.forEachIndexed { index, member ->
                require(member.streamSequence == window.fromExclusiveSequence + index + 1 &&
                    hexDigest(member.digest)) { "source member gap or digest invalid" }
                check(memberKeys.putIfAbsent(Triple(window.partitionId, member.streamSequence,
                    window.sourceGeneration), member.digest) == null) { "source member repeated" }
            }
            check(window.coverageDigest == sourceCoverageDigest(proposal.eventStream, window)) {
                "source coverage digest does not match retained member manifest"
            }
            bytes { out ->
                out.field("reef.settlement.journal.source-window.v1")
                out.writeInt(window.stepIndex)
                out.field(window.sourceGeneration)
                out.writeInt(window.partitionId)
                out.writeLong(window.fromExclusiveSequence)
                out.writeLong(window.throughInclusiveSequence)
                out.field(window.coverageProofId)
                out.field(window.coverageDigest)
                out.writeInt(window.members.size)
                window.members.forEach { out.writeLong(it.streamSequence); out.field(it.digest) }
            }
        }
        require(proposal.sourceWindows.map { it.stepIndex }.zipWithNext().all { (a, b) -> a < b })
        require(proposal.controls.map { it.stepIndex }.zipWithNext().all { (a, b) -> a < b })
        require(proposal.controls.map { it.sequence }.zipWithNext().all { (a, b) -> b == a + 1 })
        require(proposal.controls.map { it.id }.distinct().size == proposal.controls.size)
        val controlPayloads = proposal.controls.map { it.payload.copyOf() }
        proposal.controls.forEachIndexed { i, control ->
            require(control.sequence > 0 && control.id.isNotBlank() && control.version > 0 &&
                control.kind in CONTROL_KINDS && hexDigest(control.digest) &&
                controlPayloads[i].isNotEmpty() &&
                control.digest == controlMemberDigest(control.copy(payload = controlPayloads[i]))) {
                "control identity, payload or member digest invalid"
            }
        }
        val windowByStep = proposal.sourceWindows.associateBy { it.stepIndex }
        val controlByStep = proposal.controls.associateBy { it.stepIndex }
        val firstPositions = mutableSetOf<Triple<Int, Long, Int>>()
        val attemptKeys = mutableSetOf<Pair<String, Int>>()
        var previousSourcePosition: Pair<Long, Int>? = null
        var previousResultStep = -1
        val resultDigests = proposal.results.map { result ->
            require(result.tradeId.isNotBlank() && result.attemptNumber > 0 &&
                result.sourceGeneration.isNotBlank() && result.partitionId in 0..32767 &&
                result.streamSequence > CanonicalStreamPosition.origin(result.partitionId) &&
                result.effectOrdinal >= 0 && hexDigest(result.sourceMemberDigest) &&
                result.eventId.isNotBlank() && result.runId.isNotBlank() &&
                result.venueSessionId.isNotBlank() && result.buyerParticipantId.isNotBlank() &&
                result.buyerAccountId.isNotBlank() && result.sellerParticipantId.isNotBlank() &&
                result.sellerAccountId.isNotBlank() && result.currency.isNotBlank() &&
                result.instrumentId.isNotBlank() && result.cashAmount > BigDecimal.ZERO &&
                result.quantityUnits > BigDecimal.ZERO && result.policyControlId.isNotBlank() &&
                hexDigest(result.boundControlDigest) && result.outcome in setOf("SETTLED", "BREAK") &&
                ((result.outcome == "SETTLED" && result.breakReason == null) ||
                    (result.outcome == "BREAK" && result.breakReason in BREAK_REASONS)) &&
                result.workflowFacts.isNotBlank())
            check(attemptKeys.add(result.tradeId to result.attemptNumber)) { "attempt repeated in batch" }
            check(result.decisionStepIndex in 0 until steps.size) { "result decision step missing" }
            if (result.decisionStepIndex != previousResultStep) previousSourcePosition = null
            previousResultStep = result.decisionStepIndex
            if (result.attemptNumber == 1) {
                val window = windowByStep[result.decisionStepIndex]
                if (window != null) {
                    check(window.partitionId == result.partitionId &&
                        window.sourceGeneration == result.sourceGeneration &&
                        result.streamSequence > window.fromExclusiveSequence &&
                        result.streamSequence <= window.throughInclusiveSequence) {
                        "first attempt has wrong source decision step"
                    }
                    val sourcePosition = result.streamSequence to result.effectOrdinal
                    check(previousSourcePosition == null ||
                        previousSourcePosition.first < sourcePosition.first ||
                        (previousSourcePosition.first == sourcePosition.first &&
                            previousSourcePosition.second < sourcePosition.second)) {
                        "first attempts within source step are out of source order"
                    }
                    previousSourcePosition = sourcePosition
                    check(memberKeys[Triple(result.partitionId, result.streamSequence,
                        result.sourceGeneration)] == result.sourceMemberDigest) {
                        "first attempt lacks matching source manifest member"
                    }
                } else {
                    val activatingControl = controlByStep[result.decisionStepIndex]
                    check(activatingControl?.kind in setOf("FUNDING", "REPAIR") &&
                        activatingControl?.id in result.fundingControlIds) {
                        "delayed first attempt must follow explicit funding or repair"
                    }
                }
                check(firstPositions.add(Triple(result.partitionId, result.streamSequence,
                    result.effectOrdinal))) { "first-attempt source effect repeated" }
            } else {
                val retryControl = controlByStep[result.decisionStepIndex]
                check(retryControl?.kind in setOf("FUNDING", "REPAIR") &&
                    retryControl?.id in result.fundingControlIds) {
                    "retry must follow explicit funding or repair control"
                }
            }
            digestBytes(resultBytes(result))
        }
        val proposalDigest = digestBytes(bytes { out ->
            out.field("reef.settlement.journal.proposal.v1")
            out.field(proposal.eventStream)
            out.writeLong(proposal.expectedBatchSequence)
            out.field(proposal.expectedPreviousDigest)
            out.writeLong(proposal.ownerEpoch)
            out.field(proposal.incarnationId)
            out.writeInt(windowBytes.size)
            windowBytes.forEach { out.field(it) }
            out.writeInt(proposal.controls.size)
            proposal.controls.forEachIndexed { index, control ->
                out.writeLong(control.sequence)
                out.writeInt(control.stepIndex)
                out.field(control.id)
                out.field(control.kind)
                out.writeInt(control.version)
                out.field(controlPayloads[index])
                out.field(control.digest)
            }
            out.writeInt(resultDigests.size)
            resultDigests.forEach { out.field(it) }
        })
        return Encoded(proposalDigest, windowBytes, windowBytes.map(::digestBytes),
            controlPayloads, resultDigests)
    }

    private fun resultBytes(result: SettlementJournalResult): ByteArray = bytes { out ->
        out.field("reef.settlement.journal.result.v1")
        out.writeInt(result.decisionStepIndex)
        out.field(result.tradeId); out.writeInt(result.attemptNumber)
        out.field(result.sourceGeneration); out.writeInt(result.partitionId)
        out.writeLong(result.streamSequence); out.writeInt(result.effectOrdinal)
        out.field(result.sourceMemberDigest); out.field(result.eventId)
        out.field(result.runId); out.field(result.venueSessionId)
        out.field(result.buyerParticipantId); out.field(result.buyerAccountId)
        out.field(result.sellerParticipantId); out.field(result.sellerAccountId)
        out.field(result.currency); out.field(result.instrumentId)
        out.field(result.cashAmount.toPlainString()); out.field(result.quantityUnits.toPlainString())
        out.field(result.occurredAt.toString()); out.field(result.policyControlId)
        out.writeInt(result.openingControlIds.size); result.openingControlIds.forEach { out.field(it) }
        out.writeInt(result.fundingControlIds.size); result.fundingControlIds.forEach { out.field(it) }
        out.field(result.boundControlDigest); out.field(result.outcome)
        out.field(result.breakReason ?: ""); out.field(result.workflowFacts)
    }

    private fun readHead(connection: Connection, stream: String, lock: Boolean): SettlementJournalHead =
        connection.prepareStatement(
            """SELECT next_batch_sequence, last_batch_digest, owner_epoch, incarnation_id,
                      last_control_sequence, last_control_digest FROM $schema.settlement_journal_heads
               WHERE event_stream = ? ${if (lock) "FOR UPDATE" else ""}"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "journal head missing" }
                SettlementJournalHead(rows.getLong(1), rows.getString(2), rows.getLong(3),
                    rows.getString(4), rows.getLong(5), rows.getString(6)).also { check(!rows.next()) }
            }
        }

    private data class CommittedBatch(
        val previousDigest: String, val proposalDigest: String, val batchDigest: String,
        val ownerEpoch: Long, val incarnationId: String,
        val sourceWindowCount: Int, val controlCount: Int, val resultCount: Int
    )

    private fun committedBatch(connection: Connection, stream: String, sequence: Long): CommittedBatch? =
        connection.prepareStatement(
            """SELECT previous_digest, proposal_digest, batch_digest, owner_epoch, incarnation_id,
                      source_window_count, control_count, result_count
               FROM $schema.settlement_journal_batches
               WHERE event_stream = ? AND batch_sequence = ?"""
        ).use { statement ->
            statement.setString(1, stream); statement.setLong(2, sequence)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else CommittedBatch(rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getLong(4), rows.getString(5), rows.getInt(6),
                    rows.getInt(7), rows.getInt(8)).also { check(!rows.next()) }
            }
        }

    private fun validateSourceFrontiers(connection: Connection, proposal: SettlementJournalBatchProposal) {
        val seen = mutableMapOf<Int, Pair<String, Long>>()
        proposal.sourceWindows.forEach { window ->
            val previous = seen[window.partitionId] ?: connection.prepareStatement(
                """SELECT source_generation, through_inclusive_sequence
                   FROM $schema.settlement_journal_source_windows
                   WHERE event_stream = ? AND partition_id = ?
                   ORDER BY batch_sequence DESC, window_index DESC LIMIT 1"""
            ).use { statement ->
                statement.setString(1, proposal.eventStream)
                statement.setInt(2, window.partitionId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) to rows.getLong(2)
                    else window.sourceGeneration to CanonicalStreamPosition.origin(window.partitionId)
                }
            }
            check(previous.first == window.sourceGeneration &&
                previous.second == window.fromExclusiveSequence) {
                "journal source generation, gap or overlap changed"
            }
            seen[window.partitionId] = window.sourceGeneration to window.throughInclusiveSequence
        }
    }

    private fun validateResultBindings(connection: Connection, proposal: SettlementJournalBatchProposal) {
        val needed = proposal.results.flatMap { result ->
            listOf(result.policyControlId) + result.openingControlIds + result.fundingControlIds
        }.toSet()
        if (needed.isEmpty()) return
        data class ControlLocation(val kind: String, val batch: Long, val step: Int)
        val known = mutableMapOf<String, ControlLocation>()
        connection.prepareStatement(
            """SELECT control_id, control_kind, batch_sequence, step_index
               FROM $schema.settlement_journal_controls
               WHERE event_stream = ? AND control_id = ANY (?)"""
        ).use { statement ->
            statement.setString(1, proposal.eventStream)
            statement.setArray(2, connection.createArrayOf("text", needed.toTypedArray()))
            statement.executeQuery().use { rows -> while (rows.next()) {
                known[rows.getString(1)] = ControlLocation(rows.getString(2), rows.getLong(3),
                    rows.getInt(4))
            } }
        }
        proposal.results.forEach { result ->
            fun available(id: String, kind: Set<String>): Boolean = known[id]?.let {
                it.kind in kind && (it.batch < proposal.expectedBatchSequence ||
                    it.batch == proposal.expectedBatchSequence && it.step <= result.decisionStepIndex)
            } == true
            check(available(result.policyControlId, setOf("POLICY")) &&
                result.openingControlIds.all { available(it, setOf("OPENING")) } &&
                result.fundingControlIds.all { available(it, setOf("FUNDING", "REPAIR")) }) {
                "journal result lacks ordered policy/opening/funding controls"
            }
        }
    }

    /** Locate delayed ops-realistic first attempts in prior durable source manifests, batched by partition. */
    private fun validateDelayedFirstAttempts(connection: Connection, proposal: SettlementJournalBatchProposal) {
        val sourceSteps = proposal.sourceWindows.map { it.stepIndex }.toSet()
        val delayed = proposal.results.filter {
            it.attemptNumber == 1 && it.decisionStepIndex !in sourceSteps
        }
        delayed.groupBy { it.partitionId to it.sourceGeneration }.forEach { (partition, results) ->
            val remaining = results.toMutableSet()
            connection.prepareStatement(
                """SELECT batch_sequence, step_index, from_exclusive_sequence,
                          through_inclusive_sequence, member_manifest, window_digest
                   FROM $schema.settlement_journal_source_windows
                   WHERE event_stream = ? AND partition_id = ? AND source_generation = ?
                     AND through_inclusive_sequence >= ? AND from_exclusive_sequence < ?
                   ORDER BY batch_sequence, window_index"""
            ).use { statement ->
                statement.setString(1, proposal.eventStream)
                statement.setInt(2, partition.first)
                statement.setString(3, partition.second)
                statement.setLong(4, results.minOf { it.streamSequence })
                statement.setLong(5, results.maxOf { it.streamSequence })
                statement.executeQuery().use { rows -> while (rows.next()) {
                    val batch = rows.getLong(1)
                    val step = rows.getInt(2)
                    val from = rows.getLong(3)
                    val through = rows.getLong(4)
                    val manifest = rows.getBytes(5)
                    check(digestBytes(manifest) == rows.getString(6)) {
                        "retained source manifest bytes changed"
                    }
                    val members = decodeWindowMembers(manifest, partition.second, partition.first,
                        from, through)
                    val matched = remaining.filter { it.streamSequence > from &&
                        it.streamSequence <= through }
                    matched.forEach { result ->
                        check((batch < proposal.expectedBatchSequence ||
                            batch == proposal.expectedBatchSequence && step < result.decisionStepIndex) &&
                            members[result.streamSequence] == result.sourceMemberDigest) {
                            "delayed first attempt lacks earlier retained source member"
                        }
                    }
                    remaining.removeAll(matched.toSet())
                } }
            }
            check(remaining.isEmpty()) { "delayed first attempt has no retained source coverage" }
        }
    }

    private fun decodeWindowMembers(manifest: ByteArray, generation: String, partition: Int,
        from: Long, through: Long): Map<Long, String> = DataInputStream(ByteArrayInputStream(manifest)).use { input ->
        fun field(): String {
            val size = input.readInt()
            check(size in 0..16_000_000) { "source manifest field length invalid" }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
        check(field() == "reef.settlement.journal.source-window.v1")
        input.readInt() // step index; query checks its committed placement.
        check(field() == generation && input.readInt() == partition &&
            input.readLong() == from && input.readLong() == through) {
            "source manifest identity changed"
        }
        field() // proof ID is included in manifest digest.
        check(hexDigest(field())) { "source manifest coverage digest invalid" }
        val count = input.readInt()
        check(count in 0..5000)
        val members = linkedMapOf<Long, String>()
        repeat(count) {
            val sequence = input.readLong()
            val memberDigest = field()
            check(sequence > from && sequence <= through && hexDigest(memberDigest) &&
                members.putIfAbsent(sequence, memberDigest) == null) { "source manifest member invalid" }
        }
        check(input.available() == 0) { "source manifest trailing bytes" }
        members
    }

    private fun validateRetries(connection: Connection, proposal: SettlementJournalBatchProposal) {
        val priorFromDatabase = linkedMapOf<Pair<String, Int>, SettlementJournalResult>()
        proposal.results.forEachIndexed { index, result ->
            if (result.attemptNumber == 1) return@forEachIndexed
            val local = proposal.results.take(index).lastOrNull {
                it.tradeId == result.tradeId && it.attemptNumber == result.attemptNumber - 1
            }
            if (local != null) {
                check(local.outcome == "BREAK" && sameObligation(local, result)) {
                    "retry changed source obligation or followed settlement"
                }
            } else priorFromDatabase[result.tradeId to (result.attemptNumber - 1)] = result
        }
        if (priorFromDatabase.isEmpty()) return
        connection.prepareStatement(
            """SELECT prior.trade_id, prior.attempt_number, prior.source_generation,
                      prior.partition_id, prior.stream_sequence, prior.effect_ordinal,
                      prior.source_member_digest, prior.event_id, prior.run_id,
                      prior.venue_session_id, prior.buyer_participant_id, prior.buyer_account_id,
                      prior.seller_participant_id, prior.seller_account_id, prior.currency,
                      prior.instrument_id, prior.cash_amount, prior.quantity_units,
                      prior.occurred_at_text, prior.policy_control_id, prior.outcome
               FROM $schema.settlement_journal_results prior
               JOIN unnest(?::text[], ?::integer[]) AS wanted(trade_id, attempt_number)
                 ON wanted.trade_id = prior.trade_id AND wanted.attempt_number = prior.attempt_number
               WHERE prior.event_stream = ?"""
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("text",
                priorFromDatabase.keys.map { it.first }.toTypedArray()))
            statement.setArray(2, connection.createArrayOf("integer",
                priorFromDatabase.keys.map { it.second }.toTypedArray()))
            statement.setString(3, proposal.eventStream)
            statement.executeQuery().use { rows -> while (rows.next()) {
                val expected = priorFromDatabase.remove(rows.getString(1) to rows.getInt(2))
                    ?: error("unexpected prior attempt returned")
                check(rows.getString(21) == "BREAK" &&
                    rows.getString(3) == expected.sourceGeneration &&
                    rows.getInt(4) == expected.partitionId &&
                    rows.getLong(5) == expected.streamSequence &&
                    rows.getInt(6) == expected.effectOrdinal &&
                    rows.getString(7) == expected.sourceMemberDigest &&
                    rows.getString(8) == expected.eventId &&
                    rows.getString(9) == expected.runId &&
                    rows.getString(10) == expected.venueSessionId &&
                    rows.getString(11) == expected.buyerParticipantId &&
                    rows.getString(12) == expected.buyerAccountId &&
                    rows.getString(13) == expected.sellerParticipantId &&
                    rows.getString(14) == expected.sellerAccountId &&
                    rows.getString(15) == expected.currency &&
                    rows.getString(16) == expected.instrumentId &&
                    rows.getBigDecimal(17).compareTo(expected.cashAmount) == 0 &&
                    rows.getBigDecimal(18).compareTo(expected.quantityUnits) == 0 &&
                    rows.getString(19) == expected.occurredAt.toString() &&
                    rows.getString(20) == expected.policyControlId) {
                    "retry changed source obligation or followed settlement"
                }
            } }
        }
        check(priorFromDatabase.isEmpty()) { "retry lacks committed prior attempt" }
    }

    private fun sameObligation(a: SettlementJournalResult, b: SettlementJournalResult): Boolean =
        a.tradeId == b.tradeId && a.sourceGeneration == b.sourceGeneration &&
            a.partitionId == b.partitionId && a.streamSequence == b.streamSequence &&
            a.effectOrdinal == b.effectOrdinal && a.sourceMemberDigest == b.sourceMemberDigest &&
            a.eventId == b.eventId && a.runId == b.runId &&
            a.venueSessionId == b.venueSessionId &&
            a.buyerParticipantId == b.buyerParticipantId && a.buyerAccountId == b.buyerAccountId &&
            a.sellerParticipantId == b.sellerParticipantId && a.sellerAccountId == b.sellerAccountId &&
            a.currency == b.currency && a.instrumentId == b.instrumentId &&
            a.cashAmount.compareTo(b.cashAmount) == 0 &&
            a.quantityUnits.compareTo(b.quantityUnits) == 0 &&
            a.occurredAt == b.occurredAt && a.policyControlId == b.policyControlId

    private fun insertBatch(connection: Connection, p: SettlementJournalBatchProposal,
        proposalDigest: String, batchDigest: String) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_batches
               (event_stream, batch_sequence, owner_epoch, incarnation_id, previous_digest,
                proposal_digest, batch_digest, source_window_count, control_count, result_count)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { s ->
            s.setString(1, p.eventStream); s.setLong(2, p.expectedBatchSequence)
            s.setLong(3, p.ownerEpoch); s.setString(4, p.incarnationId)
            s.setString(5, p.expectedPreviousDigest); s.setString(6, proposalDigest)
            s.setString(7, batchDigest); s.setInt(8, p.sourceWindows.size)
            s.setInt(9, p.controls.size); s.setInt(10, p.results.size)
            check(s.executeUpdate() == 1)
        }
    }

    private fun insertWindows(connection: Connection, p: SettlementJournalBatchProposal, e: Encoded) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_source_windows
               (event_stream, batch_sequence, window_index, step_index, source_generation, partition_id,
                from_exclusive_sequence, through_inclusive_sequence, coverage_proof_id,
                coverage_digest, member_count, member_manifest, window_digest)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { s ->
            p.sourceWindows.forEachIndexed { i, w ->
                s.setString(1, p.eventStream); s.setLong(2, p.expectedBatchSequence)
                s.setInt(3, i); s.setInt(4, w.stepIndex)
                s.setString(5, w.sourceGeneration); s.setInt(6, w.partitionId)
                s.setLong(7, w.fromExclusiveSequence); s.setLong(8, w.throughInclusiveSequence)
                s.setString(9, w.coverageProofId); s.setString(10, w.coverageDigest)
                s.setInt(11, w.members.size); s.setBytes(12, e.windowBytes[i])
                s.setString(13, e.windowDigests[i]); s.addBatch()
            }
            check(s.executeBatch().size == p.sourceWindows.size)
        }
    }

    private fun insertControls(connection: Connection, p: SettlementJournalBatchProposal,
        e: Encoded, prefixes: Map<Int, String>) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_controls
               (event_stream, control_sequence, batch_sequence, control_index, step_index, control_id,
                control_kind, control_version, payload, control_digest, prefix_digest)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { s ->
            p.controls.forEachIndexed { i, c ->
                s.setString(1, p.eventStream); s.setLong(2, c.sequence)
                s.setLong(3, p.expectedBatchSequence); s.setInt(4, i); s.setInt(5, c.stepIndex)
                s.setString(6, c.id); s.setString(7, c.kind); s.setInt(8, c.version)
                s.setBytes(9, e.controlPayloads[i]); s.setString(10, c.digest)
                s.setString(11, prefixes.getValue(c.stepIndex)); s.addBatch()
            }
            check(s.executeBatch().size == p.controls.size)
        }
    }

    private fun insertResults(connection: Connection, p: SettlementJournalBatchProposal, e: Encoded) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_results
               (event_stream, batch_sequence, result_index, decision_step_index, trade_id, attempt_number,
                source_generation, partition_id, stream_sequence, effect_ordinal,
                source_member_digest, event_id, run_id, venue_session_id,
                buyer_participant_id, buyer_account_id, seller_participant_id, seller_account_id,
                currency, instrument_id, cash_amount, quantity_units, occurred_at, occurred_at_text,
                policy_control_id, opening_control_ids, funding_control_ids, bound_control_digest,
                outcome, break_reason, workflow_facts, result_digest)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { s ->
            p.results.forEachIndexed { i, r ->
                s.setString(1, p.eventStream); s.setLong(2, p.expectedBatchSequence); s.setInt(3, i)
                s.setInt(4, r.decisionStepIndex); s.setString(5, r.tradeId); s.setInt(6, r.attemptNumber)
                s.setString(7, r.sourceGeneration); s.setInt(8, r.partitionId)
                s.setLong(9, r.streamSequence); s.setInt(10, r.effectOrdinal)
                s.setString(11, r.sourceMemberDigest); s.setString(12, r.eventId)
                s.setString(13, r.runId); s.setString(14, r.venueSessionId)
                s.setString(15, r.buyerParticipantId); s.setString(16, r.buyerAccountId)
                s.setString(17, r.sellerParticipantId); s.setString(18, r.sellerAccountId)
                s.setString(19, r.currency); s.setString(20, r.instrumentId)
                s.setBigDecimal(21, r.cashAmount); s.setBigDecimal(22, r.quantityUnits)
                s.setTimestamp(23, Timestamp.from(r.occurredAt)); s.setString(24, r.occurredAt.toString())
                s.setString(25, r.policyControlId)
                s.setArray(26, connection.createArrayOf("text", r.openingControlIds.toTypedArray()))
                s.setArray(27, connection.createArrayOf("text", r.fundingControlIds.toTypedArray()))
                s.setString(28, r.boundControlDigest); s.setString(29, r.outcome)
                s.setString(30, r.breakReason); s.setString(31, r.workflowFacts)
                s.setString(32, e.resultDigests[i]); s.addBatch()
            }
            check(s.executeBatch().size == p.results.size)
        }
    }

    private fun verifyStoredMembers(connection: Connection, p: SettlementJournalBatchProposal, e: Encoded) {
        connection.prepareStatement(
            """SELECT window_index, step_index, source_generation, partition_id,
                      from_exclusive_sequence, through_inclusive_sequence, coverage_proof_id,
                      coverage_digest, member_count, member_manifest, window_digest
               FROM $schema.settlement_journal_source_windows
               WHERE event_stream = ? AND batch_sequence = ? ORDER BY window_index"""
        ).use { s ->
            s.setString(1, p.eventStream); s.setLong(2, p.expectedBatchSequence)
            s.executeQuery().use { rows ->
                e.windowDigests.forEachIndexed { i, hash ->
                    check(rows.next()) { "committed source manifest is missing" }
                    val window = p.sourceWindows[i]
                    check(rows.getInt(1) == i && rows.getInt(2) == window.stepIndex &&
                        rows.getString(3) == window.sourceGeneration &&
                        rows.getInt(4) == window.partitionId &&
                        rows.getLong(5) == window.fromExclusiveSequence &&
                        rows.getLong(6) == window.throughInclusiveSequence &&
                        rows.getString(7) == window.coverageProofId &&
                        rows.getString(8) == window.coverageDigest &&
                        rows.getInt(9) == window.members.size &&
                        rows.getBytes(10).contentEquals(e.windowBytes[i]) &&
                        rows.getString(11) == hash &&
                        digestBytes(rows.getBytes(10)) == hash) {
                        "committed source manifest changed"
                    }
                }
                check(!rows.next()) { "committed source manifest count changed" }
            }
        }
        val previousControlDigest = p.controls.firstOrNull()?.let { first ->
            connection.prepareStatement(
                """SELECT prefix_digest FROM $schema.settlement_journal_controls
                   WHERE event_stream = ? AND control_sequence < ?
                   ORDER BY control_sequence DESC LIMIT 1"""
            ).use { s ->
                s.setString(1, p.eventStream); s.setLong(2, first.sequence)
                s.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else ORIGIN_DIGEST }
            }
        } ?: ORIGIN_DIGEST
        var controlPrefix = previousControlDigest
        connection.prepareStatement(
            """SELECT control_index, step_index, control_sequence, control_id, control_kind,
                      control_version, control_digest, payload, prefix_digest
               FROM $schema.settlement_journal_controls
               WHERE event_stream = ? AND batch_sequence = ? ORDER BY control_index"""
        ).use { s ->
            s.setString(1, p.eventStream); s.setLong(2, p.expectedBatchSequence)
            s.executeQuery().use { rows ->
                p.controls.forEachIndexed { i, control ->
                    check(rows.next()) { "committed control manifest is missing" }
                    controlPrefix = controlPrefixDigest(controlPrefix, control)
                    check(rows.getInt(1) == i && rows.getInt(2) == control.stepIndex &&
                        rows.getLong(3) == control.sequence && rows.getString(4) == control.id &&
                        rows.getString(5) == control.kind && rows.getInt(6) == control.version &&
                        rows.getString(7) == control.digest &&
                        rows.getBytes(8).contentEquals(e.controlPayloads[i]) &&
                        rows.getString(9) == controlPrefix &&
                        controlMemberDigest(control.copy(payload = rows.getBytes(8))) ==
                            rows.getString(7)) {
                        "committed control manifest changed"
                    }
                }
                check(!rows.next()) { "committed control count changed" }
            }
        }
        connection.prepareStatement(
            """SELECT result_index, decision_step_index, trade_id, attempt_number,
                      source_generation, partition_id, stream_sequence, effect_ordinal,
                      source_member_digest, event_id, run_id, venue_session_id,
                      buyer_participant_id, buyer_account_id, seller_participant_id,
                      seller_account_id, currency, instrument_id, cash_amount, quantity_units,
                      occurred_at, occurred_at_text, policy_control_id, opening_control_ids,
                      funding_control_ids, bound_control_digest, outcome, break_reason,
                      workflow_facts, result_digest
               FROM $schema.settlement_journal_results
               WHERE event_stream = ? AND batch_sequence = ? ORDER BY result_index"""
        ).use { s ->
            s.setString(1, p.eventStream); s.setLong(2, p.expectedBatchSequence)
            s.executeQuery().use { rows ->
                e.resultDigests.forEachIndexed { i, hash ->
                    check(rows.next() && rows.getInt(1) == i) { "committed result index changed" }
                    val stored = SettlementJournalResult(
                        decisionStepIndex = rows.getInt(2),
                        tradeId = rows.getString(3),
                        attemptNumber = rows.getInt(4),
                        sourceGeneration = rows.getString(5),
                        partitionId = rows.getInt(6),
                        streamSequence = rows.getLong(7),
                        effectOrdinal = rows.getInt(8),
                        sourceMemberDigest = rows.getString(9),
                        eventId = rows.getString(10),
                        runId = rows.getString(11),
                        venueSessionId = rows.getString(12),
                        buyerParticipantId = rows.getString(13),
                        buyerAccountId = rows.getString(14),
                        sellerParticipantId = rows.getString(15),
                        sellerAccountId = rows.getString(16),
                        currency = rows.getString(17),
                        instrumentId = rows.getString(18),
                        cashAmount = rows.getBigDecimal(19),
                        quantityUnits = rows.getBigDecimal(20),
                        occurredAt = Instant.parse(rows.getString(22)),
                        policyControlId = rows.getString(23),
                        openingControlIds = (rows.getArray(24).array as Array<*>).map { it as String },
                        fundingControlIds = (rows.getArray(25).array as Array<*>).map { it as String },
                        boundControlDigest = rows.getString(26),
                        outcome = rows.getString(27),
                        breakReason = rows.getString(28),
                        workflowFacts = rows.getString(29)
                    )
                    val postedTime = rows.getTimestamp(21).toInstant()
                    val nanosDifference = kotlin.math.abs(
                        java.time.Duration.between(stored.occurredAt, postedTime).toNanos())
                    check(stored == p.results[i] && nanosDifference <= 500 &&
                        rows.getString(30) == hash && digestBytes(resultBytes(stored)) == hash) {
                        "committed result manifest changed"
                    }
                }
                check(!rows.next()) { "committed result count changed" }
            }
        }
    }

    companion object {
        const val ORIGIN_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        private val CONTROL_KINDS = setOf("POLICY", "OPENING", "FUNDING", "REPAIR")
        private val BREAK_REASONS = setOf("CASH_LEG_FAILED", "SECURITY_LEG_FAILED")
        /** Hash exact retained control payload bytes with immutable identity. */
        fun controlMemberDigest(control: SettlementJournalControl): String = digestBytes(bytes { out ->
            out.field("reef.settlement.journal.control-member.v1")
            out.writeLong(control.sequence)
            out.field(control.id)
            out.field(control.kind)
            out.writeInt(control.version)
            out.field(control.payload)
        })

        /** Ordered prefix used by results and the head; callers can build a proposal without copying hash logic. */
        fun controlPrefixDigest(previous: String, control: SettlementJournalControl): String {
            require(hexDigest(previous) && hexDigest(control.digest))
            return digest(listOf("reef.settlement.journal.control-prefix.v1", previous,
                control.sequence.toString(), control.id, control.digest))
        }
        /** Structural digest only; authentic absence/retention proof remains caller's responsibility. */
        fun sourceCoverageDigest(eventStream: String, window: SettlementJournalSourceWindow): String =
            digest(listOf("reef.reference.source-coverage.v1", eventStream,
                window.sourceGeneration, window.partitionId.toString(),
                window.fromExclusiveSequence.toString(), window.throughInclusiveSequence.toString(),
                window.coverageProofId) + window.members.map { it.digest })
        private fun hexDigest(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
        private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use(write)
            buffer.toByteArray()
        }
        private fun DataOutputStream.field(value: String) = field(value.toByteArray(StandardCharsets.UTF_8))
        private fun DataOutputStream.field(value: ByteArray) {
            writeInt(value.size)
            write(value)
        }
        private fun digest(fields: List<String>): String = digestBytes(bytes { out ->
            fields.forEach { out.field(it) }
        })
        private fun digestBytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value).joinToString("") { "%02x".format(it) }
    }
}
