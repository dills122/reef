package com.reef.platform.api

import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementAppendReceipt
import com.reef.platform.application.settlementjournal.SettlementEvaluatorHead
import com.reef.platform.application.settlementjournal.SettlementEvaluatorRecoveryState
import com.reef.platform.application.settlementjournal.SettlementJournalEvaluator
import com.reef.platform.application.settlementjournal.SettlementPreparedInput
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.SettlementControlLogStore
import com.reef.platform.infrastructure.persistence.SettlementJournalExternalAnchorGate
import com.reef.platform.infrastructure.persistence.SettlementJournalExternalLease
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityAnchor
import com.reef.platform.infrastructure.persistence.SettlementJournalHead
import com.reef.platform.infrastructure.persistence.SettlementJournalProposalMapper
import com.reef.platform.infrastructure.persistence.SettlementJournalSnapshotProof
import com.reef.platform.infrastructure.persistence.SettlementJournalSourceWindow
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import com.reef.platform.infrastructure.persistence.SettlementJournalSourceMember
import com.reef.platform.infrastructure.persistence.SettlementReplaySourceAuthority
import com.reef.platform.infrastructure.persistence.SettlementSourceBindingDigestReader
import com.reef.platform.infrastructure.persistence.SettlementJournalWriterRecoveryState
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class SettlementCandidateRecoveredWriter(
    val lease: SettlementJournalExternalLease,
    val state: SettlementJournalWriterRecoveryState
)

/**
 * Bounded snapshot+tail recovery for the candidate writer's controls-then-one-source batch shape.
 * Any unsupported envelope or ambiguous finality halts before a lease is acquired.
 */
internal class PostMatchSettlementJournalRecovery(
    private val catalog: PostMatchReadSourceCatalog,
    private val sourceAuthority: SettlementReplaySourceAuthority,
    private val controlStore: SettlementControlLogStore,
    private val journal: SettlementJournalStore,
    private val snapshots: SettlementJournalSnapshotProof,
    private val finality: PostgresSettlementJournalFinalityAuthority,
    private val bindingReader: SettlementSourceBindingDigestReader
) {
    fun acquire(eventStream: String, partitions: List<Int>, controlIncarnationId: String,
        maxTailBatches: Int = 256, leaseSeconds: Int = 30): SettlementCandidateRecoveredWriter {
        require(eventStream.isNotBlank() && partitions.isNotEmpty() &&
            partitions == (0 until partitions.size).toList() &&
            maxTailBatches in 1..256 && leaseSeconds in 1..300)
        val anchor = finality.read(eventStream) ?: error("external finality anchor is missing")
        val binding = bindingReader.readDigest(eventStream)
            ?: error("retained source topic binding is missing")
        check(anchor.sourceBindingDigest == binding) { "source binding differs from external finality" }
        val pinned = journal.head(eventStream)
        SettlementJournalExternalAnchorGate.verify(eventStream,
            com.reef.platform.infrastructure.persistence.SettlementJournalReplayState(
                pinned, emptyMap(), emptySet(), 0), anchor)
        val pointer = anchor.snapshot?.takeIf { it.stateVersion == 2 }
        val checkpoint = pointer?.let { snapshots.readAnchoredWriterCheckpoint(eventStream, anchor) }
        val startSequence = pointer?.batchSequence ?: 0L
        check(anchor.acknowledgedBatchSequence - startSequence <= maxTailBatches) {
            "writer recovery tail exceeds verified bound"
        }
        val sourceGeneration = catalog.generation()
        val initial = checkpoint?.state ?: SettlementJournalWriterRecoveryState(
            SettlementEvaluatorRecoveryState(SettlementEvaluatorHead(0,
                SettlementJournalStore.ORIGIN_DIGEST, pinned.ownerEpoch, anchor.incarnationId),
                emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                emptyMap(), emptyMap(), emptyMap()),
            emptyMap(), emptySet(), binding, controlIncarnationId,
            partitions.associate { partition ->
                ReferenceStreamPartition(eventStream, sourceGeneration, partition) to
                    com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(partition)
            })
        val checkpointHead = checkpoint?.head ?: SettlementJournalHead(1,
            SettlementJournalStore.ORIGIN_DIGEST, pinned.ownerEpoch, anchor.incarnationId,
            0, SettlementJournalStore.ORIGIN_DIGEST)
        check(initial.controlIncarnationId == controlIncarnationId &&
            checkpointHead.lastControlSequence == initial.evaluator.orderedControls.size.toLong()) {
            "checkpoint control incarnation or sequence changed"
        }
        if (pointer != null) {
            val snapshotBatch = journal.readVerifiedBatch(eventStream, pointer.batchSequence)
            check(snapshotBatch.batchDigest == pointer.batchDigest &&
                snapshotBatch.incarnationId == anchor.incarnationId) {
                "checkpoint journal batch differs from external pointer"
            }
            verifyHistoricalSourcePrefix(eventStream, pointer.batchSequence,
                pointer.batchDigest, initial)
        }
        val controlsAtStart = controlStore.readVerifiedPrefix(eventStream, controlIncarnationId)
        val allControls = controlsAtStart.batches.flatMap { it.members }
        check(allControls.size.toLong() >= anchor.controlSequence &&
            initial.evaluator.orderedControls.withIndex().all { (index, pair) ->
                val member = allControls[index]
                pair.first == member.id &&
                    pair.second == settlementReferenceControlDigest(member.decode())
            }) { "retained control prefix differs from writer checkpoint" }
        checkSourceFrontiers(eventStream, partitions, initial)
        var activeSource: SettlementCandidatePreparedSource? = null
        var activeWindow: SettlementJournalSourceWindow? = null
        var activeRead: VerifiedCanonicalSourceWindow? = null
        var mapped: SettlementJournalProposalMapper.Mapped? = null
        var storedPreview: com.reef.platform.infrastructure.persistence.SettlementJournalProposalPreview? = null
        lateinit var evaluator: SettlementJournalEvaluator
        val manifestVerifier = com.reef.platform.application.settlementjournal.SettlementSourceManifestVerifier { coverage, digest ->
            val selected = activeSource
            val window = activeWindow
            selected != null && window != null && coverage.key == selected.key &&
                coverage.evidence == selected.evidence &&
                coverage.expectedTrades == selected.trades &&
                digest == evaluator.tradeManifestDigest(selected.trades) &&
                verifySource(eventStream, window, activeRead)
        }
        val receiptVerifier = com.reef.platform.application.settlementjournal.SettlementCommitReceiptVerifier { decision, receipt ->
            val candidate = mapped
            candidate != null && candidate.decision === decision &&
                candidate.preview == storedPreview &&
                receipt.storeProposalDigest == candidate.preview.proposalDigest
        }
        evaluator = if (startSequence == 0L) SettlementJournalEvaluator(initial.evaluator.head,
            manifestVerifier, receiptVerifier) else SettlementJournalEvaluator.restoreVerified(
            initial.evaluator, initial.evaluator.head.ownerEpoch,
            manifestVerifier, receiptVerifier)
        val committedSeenOrderIds = initial.seenOrderIds.toMutableSet()
        var manifestState = SettlementCandidateManifestState(initial.activeOrders,
            committedSeenOrderIds, initial.evaluator.policies,
            initial.evaluator.openingIds.keys)
        val retainedFrontiers = initial.lastRetainedSourceFrontiers.toMutableMap()
        var priorHead = checkpointHead
        val manifest = SettlementCandidateTradeManifest()
        val mapper = SettlementJournalProposalMapper(journal)
        for (sequence in startSequence + 1..anchor.acknowledgedBatchSequence) {
            val verified = journal.readVerifiedBatch(eventStream, sequence)
            val batch = journal.replayProposalCopy(verified)
            check(batch.expectedBatchSequence == sequence &&
                batch.expectedPreviousDigest == priorHead.lastBatchDigest &&
                batch.incarnationId == anchor.incarnationId && batch.ownerEpoch > 0) {
                "writer recovery journal tail chain changed"
            }
            val controls = batch.controls.sortedBy { it.stepIndex }
            check(controls.map { it.stepIndex } == (0 until controls.size).toList() &&
                controls.map { it.sequence } ==
                    (priorHead.lastControlSequence + 1..priorHead.lastControlSequence + controls.size)
                        .toList() &&
                batch.sourceWindows.size <= 1 &&
                (batch.sourceWindows.singleOrNull()?.stepIndex == controls.size ||
                    batch.sourceWindows.isEmpty()) &&
                (controls.isNotEmpty() || batch.sourceWindows.isNotEmpty())) {
                "journal tail has unsupported candidate input shape"
            }
            val decoded = controls.map { journalControl ->
                val member = allControls.getOrNull(Math.toIntExact(journalControl.sequence - 1))
                    ?: error("retained control tail is missing")
                val control = member.decode()
                check(member.sequence == journalControl.sequence &&
                    member.id == journalControl.id &&
                    control.controlId == journalControl.id &&
                    control.controlSequence == journalControl.sequence &&
                    com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
                        .encode(control).contentEquals(journalControl.payload) &&
                    SettlementJournalStore.controlMemberDigest(journalControl) == journalControl.digest) {
                    "journal control differs from retained control authority"
                }
                control
            }
            var nextManifest = manifestState.withControls(decoded)
            val inputs = mutableListOf<SettlementPreparedInput>()
            decoded.forEachIndexed { index, control ->
                inputs += SettlementPreparedInput.Control(index.toLong(), control,
                    settlementReferenceControlDigest(control))
            }
            val window = batch.sourceWindows.singleOrNull()
            val prepared = window?.let { sourceWindow ->
                val selected = if (sourceWindow.members.isEmpty()) {
                    check(sourceAuthority.verifyEmpty(eventStream, sourceWindow)) {
                        "journal tail empty source range lacks independent proof"
                    }
                    manifest.prepareEmpty(controls.size, nextManifest,
                        ReferenceStreamPartition(eventStream, sourceWindow.sourceGeneration,
                            sourceWindow.partitionId), sourceWindow.fromExclusiveSequence,
                        sourceWindow.throughInclusiveSequence, sourceWindow.coverageProofId)
                } else {
                    val read = sourceAuthority.readVerified(eventStream, sourceWindow)
                    activeRead = read
                    manifest.prepare(controls.size, nextManifest, read)
                }
                check(selected.journalWindow == sourceWindow) {
                    "journal tail source manifest changed"
                }
                nextManifest = selected.nextManifestState
                inputs += SettlementPreparedInput.SourceCoverage(controls.size.toLong(),
                    selected.key, selected.evidence, selected.trades)
                selected.trades.forEach { trade ->
                    inputs += SettlementPreparedInput.Trade(controls.size.toLong(), trade)
                }
                selected
            }
            activeSource = prepared
            activeWindow = window
            evaluator.rebindVerifiedReplayOwner(batch.ownerEpoch)
            val replayHead = priorHead.copy(ownerEpoch = batch.ownerEpoch)
            val decision = evaluator.prepare(inputs)
            mapped = mapper.map(eventStream, replayHead, decision, inputs,
                if (window == null) emptyList() else listOf(window))
            storedPreview = journal.preview(batch)
            check(mapped.preview == storedPreview &&
                verified.batchDigest == mapped.preview.batchDigest &&
                verified.proposalDigest == mapped.preview.proposalDigest) {
                "journal tail result differs from restored evaluator"
            }
            val committed = decision.expectedHead.copy(batchSequence = sequence,
                batchDigest = verified.batchDigest)
            evaluator.confirm(decision, SettlementAppendReceipt(decision.expectedHead,
                decision.proposalDigest, mapped.preview.proposalDigest, committed))
            priorHead = SettlementJournalHead(sequence + 1, verified.batchDigest,
                batch.ownerEpoch, anchor.incarnationId,
                priorHead.lastControlSequence + controls.size,
                controls.fold(priorHead.lastControlDigest) { digest, control ->
                    SettlementJournalStore.controlPrefixDigest(digest, control)
                })
            prepared?.newOrderIds?.let(committedSeenOrderIds::addAll)
            manifestState = nextManifest.copy(seenOrderIds = committedSeenOrderIds)
            if (window != null && window.members.isNotEmpty()) {
                val stream = ReferenceStreamPartition(eventStream, window.sourceGeneration,
                    window.partitionId)
                retainedFrontiers[stream] = window.members.last().streamSequence
            }
            activeSource = null
            activeWindow = null
            activeRead = null
            mapped = null
            storedPreview = null
        }
        check(priorHead.nextBatchSequence == pinned.nextBatchSequence &&
            priorHead.lastBatchDigest == pinned.lastBatchDigest &&
            priorHead.lastControlSequence == pinned.lastControlSequence &&
            priorHead.lastControlDigest == pinned.lastControlDigest &&
            evaluator.snapshot().sourceFrontiers == evaluator.recoveryState().sourceFrontiers &&
            journal.head(eventStream) == pinned && finality.read(eventStream) == anchor &&
            bindingReader.readDigest(eventStream) == binding &&
            controlStore.readVerifiedPrefix(eventStream, controlIncarnationId).let { current ->
                current.head == controlsAtStart.head && current.batches == controlsAtStart.batches
            }) {
            "writer recovery authority moved during bounded tail proof"
        }
        val recovered = SettlementJournalWriterRecoveryState(evaluator.recoveryState(),
            manifestState.orders, committedSeenOrderIds.toSet(), binding, controlIncarnationId,
            retainedFrontiers)
        checkSourceFrontiers(eventStream, partitions, recovered)
        val lease = finality.acquireLease(anchor, pinned.ownerEpoch, leaseSeconds)
        journal.fenceOwner(eventStream, pinned, lease.epoch)
        check(journal.head(eventStream) == pinned.copy(ownerEpoch = lease.epoch) &&
            finality.read(eventStream) == anchor && bindingReader.readDigest(eventStream) == binding) {
            "writer recovery owner fence or independent authority moved"
        }
        return SettlementCandidateRecoveredWriter(lease,
            recovered.copy(evaluator = recovered.evaluator.copy(
                head = recovered.evaluator.head.copy(ownerEpoch = lease.epoch))))
    }

    private fun verifySource(eventStream: String, window: SettlementJournalSourceWindow,
        expected: VerifiedCanonicalSourceWindow?): Boolean =
        if (window.members.isEmpty()) sourceAuthority.verifyEmpty(eventStream, window)
        else expected != null && sourceAuthority.readVerified(eventStream, window).let { verified ->
            verified.eventStream == eventStream &&
                verified.sourceGeneration == window.sourceGeneration &&
                verified.partitionId == window.partitionId &&
                verified.fromExclusiveSequence == window.fromExclusiveSequence &&
                verified.throughInclusiveSequence == window.throughInclusiveSequence &&
                verified.sourceDigest == expected.sourceDigest &&
                verified.outcomes == expected.outcomes
        }

    private fun checkSourceFrontiers(eventStream: String, partitions: List<Int>,
        state: SettlementJournalWriterRecoveryState) {
        val generation = catalog.generation()
        check(state.evaluator.sourceFrontiers.all { (stream, frontier) ->
            stream.eventStream == eventStream && stream.sourceGeneration == generation &&
                stream.partitionId in partitions &&
                state.lastRetainedSourceFrontiers[stream]?.let { retained ->
                    sourceAuthority.verifyRecoveredFrontier(eventStream, generation,
                        stream.partitionId, retained, frontier)
                } == true
        }) { "canonical source is behind writer checkpoint or recovered tail" }
    }

    /** Full source-prefix scan is bounded separately from evaluator tail replay. */
    private fun verifyHistoricalSourcePrefix(eventStream: String, throughBatch: Long,
        expectedDigest: String, checkpoint: SettlementJournalWriterRecoveryState) {
        check(throughBatch in 1L..20_000L) {
            "historical source verification exceeds batch bound"
        }
        val frontiers = linkedMapOf<ReferenceStreamPartition, Long>()
        val retained = linkedMapOf<ReferenceStreamPartition, Long>()
        val generations = mutableMapOf<Pair<String, Int>, String>()
        val verifier = CanonicalSourceCoverageVerifier()
        var previousDigest = SettlementJournalStore.ORIGIN_DIGEST
        var members = 0L
        var emptyPositions = 0L
        for (sequence in 1..throughBatch) {
            val verifiedBatch = journal.readVerifiedBatch(eventStream, sequence)
            val batch = journal.replayProposalCopy(verifiedBatch)
            check(batch.expectedPreviousDigest == previousDigest &&
                batch.incarnationId == checkpoint.evaluator.head.ownerIncarnation) {
                "historical journal source chain changed"
            }
            previousDigest = verifiedBatch.batchDigest
            batch.sourceWindows.sortedBy { it.stepIndex }.forEach { window ->
                val stream = ReferenceStreamPartition(eventStream, window.sourceGeneration,
                    window.partitionId)
                val partition = eventStream to window.partitionId
                val priorGeneration = generations.putIfAbsent(partition, window.sourceGeneration)
                check(priorGeneration == null || priorGeneration == window.sourceGeneration) {
                    "historical source generation changed"
                }
                val origin = com.reef.platform.application.postmatch.CanonicalStreamPosition
                    .origin(window.partitionId)
                check(window.fromExclusiveSequence == (frontiers[stream] ?: origin) &&
                    window.throughInclusiveSequence > window.fromExclusiveSequence) {
                    "historical source frontier has a gap or overlap"
                }
                if (window.members.isEmpty()) {
                    emptyPositions += window.throughInclusiveSequence - window.fromExclusiveSequence
                    check(emptyPositions <= 5_000_000L &&
                        sourceAuthority.verifyEmpty(eventStream, window)) {
                        "historical broker-empty source proof failed or exceeded bound"
                    }
                } else {
                    members += window.members.size
                    check(members <= 5_000_000L) {
                        "historical retained-source proof exceeds member bound"
                    }
                    val read = sourceAuthority.readVerified(eventStream, window)
                    val outcomes = read.outcomes.map { it.source }
                    val independentlyChecked = verifier.verify(read.consumerName, eventStream,
                        window.partitionId, window.sourceGeneration,
                        window.fromExclusiveSequence, window.throughInclusiveSequence, outcomes)
                    check(read.eventStream == eventStream &&
                        read.sourceGeneration == window.sourceGeneration &&
                        read.partitionId == window.partitionId &&
                        read.fromExclusiveSequence == window.fromExclusiveSequence &&
                        read.throughInclusiveSequence == window.throughInclusiveSequence &&
                        read.sourceDigest == independentlyChecked.sourceDigest &&
                        read.outcomes == independentlyChecked.outcomes &&
                        read.sourceDigest == window.coverageProofId &&
                        outcomes.map { source -> SettlementJournalSourceMember(
                            source.streamSequence, sourceDigest(source)) } == window.members) {
                        "historical retained JSONB text differs from journal source manifest"
                    }
                    retained[stream] = window.members.last().streamSequence
                }
                frontiers[stream] = window.throughInclusiveSequence
            }
        }
        check(previousDigest == expectedDigest &&
            frontiers == checkpoint.evaluator.sourceFrontiers &&
            checkpoint.lastRetainedSourceFrontiers.all { (stream, last) ->
                last == (retained[stream] ?: com.reef.platform.application.postmatch
                    .CanonicalStreamPosition.origin(stream.partitionId))
            }) { "writer checkpoint source state differs from verified historical source" }
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
