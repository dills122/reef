package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalOrderIdentity
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceControl
import com.reef.platform.application.settlementjournal.ReferenceCoverageEvidence
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceObligation
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceSourcePosition
import com.reef.platform.application.settlementjournal.ReferenceSourceWindowKey
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementAppendReceipt
import com.reef.platform.application.settlementjournal.SettlementEvaluatorHead
import com.reef.platform.application.settlementjournal.SettlementEvaluatorRecoveryState
import com.reef.platform.application.settlementjournal.SettlementJournalEvaluator
import com.reef.platform.application.settlementjournal.SettlementPreparedInput
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.SettlementControlLogStore
import com.reef.platform.infrastructure.persistence.SettlementJournalCommitReceipt
import com.reef.platform.infrastructure.persistence.SettlementJournalExternalLease
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityProtocol
import com.reef.platform.infrastructure.persistence.SettlementJournalHead
import com.reef.platform.infrastructure.persistence.SettlementJournalProposalMapper
import com.reef.platform.infrastructure.persistence.SettlementReplaySourceAuthority
import com.reef.platform.infrastructure.persistence.SettlementSourceBindingDigestReader
import com.reef.platform.infrastructure.persistence.SettlementJournalSourceMember
import com.reef.platform.infrastructure.persistence.SettlementJournalSourceWindow
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import com.reef.platform.infrastructure.persistence.SettlementJournalSnapshotProof
import com.reef.platform.infrastructure.persistence.SettlementJournalWriterRecoveryState
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource

/** Ordered identities kept only after external finality acknowledgment. */
internal data class SettlementCandidateManifestState(
    val orders: Map<String, CanonicalOrderIdentity> = emptyMap(),
    val seenOrderIds: Set<String> = emptySet(),
    val policies: Map<Pair<String, String>, ReferencePolicyActivation> = emptyMap(),
    val openings: Set<ReferenceAccountKey> = emptySet()
) {
    fun withControls(controls: List<ReferenceControl>): SettlementCandidateManifestState {
        val nextPolicies = policies.toMutableMap()
        val nextOpenings = openings.toMutableSet()
        controls.forEach { control -> when (control) {
            is ReferencePolicyActivation -> nextPolicies[control.runId to control.venueSessionId] = control
            is ReferenceOpening -> check(nextOpenings.add(control.account)) {
                "settlement opening account repeated"
            }
            is ReferenceFunding -> check(control.account in nextOpenings) {
                "settlement funding lacks opening"
            }
        } }
        return copy(policies = nextPolicies.toMap(), openings = nextOpenings.toSet())
    }
}

internal data class SettlementCandidatePreparedSource(
    val journalWindow: SettlementJournalSourceWindow,
    val evidence: ReferenceCoverageEvidence,
    val key: ReferenceSourceWindowKey,
    val trades: List<ReferenceObligation>,
    val nextManifestState: SettlementCandidateManifestState,
    val newOrderIds: Set<String> = emptySet()
)

/** Pending identities remain separate until external finality acknowledges their source batch. */
internal class SettlementCandidateSeenOrderOverlay(
    private val committed: Set<String>, val added: Set<String>
) : AbstractSet<String>() {
    override val size: Int get() = committed.size + added.size
    override fun contains(element: String): Boolean = element in committed || element in added
    override fun iterator(): Iterator<String> = sequence {
        yieldAll(committed)
        yieldAll(added)
    }.iterator()
}

internal data class SettlementCandidateJournalProgress(
    val batchSequence: Long,
    val resultCount: Int,
    val sourceWindows: Int,
    val controls: Int
)

/** In-memory state only after the corresponding batch has external finality acknowledgment. */
internal data class SettlementCandidateRecoverySnapshot(
    val evaluator: SettlementEvaluatorRecoveryState,
    val manifest: SettlementCandidateManifestState,
    val lastRetainedSourceFrontiers: Map<ReferenceStreamPartition, Long>
)

/**
 * One default-off writer for all canonical partitions. Every result is externally acknowledged
 * before local state advances; restart requires verified snapshot and bounded tail replay.
 */
internal class PostMatchSettlementJournalCandidateWorker private constructor(
    private val sourceDataSource: DataSource,
    private val sourceCatalog: PostMatchReadSourceCatalog,
    private val sourceReader: PostgresCanonicalOutcomeSourceReader,
    private val sourceAuthority: SettlementReplaySourceAuthority,
    private val controlStore: SettlementControlLogStore,
    private val controlIncarnationId: String,
    private val journal: SettlementJournalStore,
    private val protocol: SettlementJournalFinalityProtocol,
    private val finalityAuthority: PostgresSettlementJournalFinalityAuthority,
    private val bindingReader: SettlementSourceBindingDigestReader,
    private val snapshots: SettlementJournalSnapshotProof?,
    private val eventStream: String,
    private val partitions: List<Int>,
    private val sourceGeneration: String,
    private val sourceBindingDigest: String,
    lease: SettlementJournalExternalLease,
    recoveredState: SettlementJournalWriterRecoveryState?,
    private val maxSourcePositions: Int,
    private val maxControlsPerBatch: Int
) {
    private var lease = lease
    private var stopped = false
    private var manifestState = SettlementCandidateManifestState()
    private val manifest = SettlementCandidateTradeManifest()
    private var activeSource: SettlementCandidatePreparedSource? = null
    private var activeSourceRead: VerifiedCanonicalSourceWindow? = null
    private var activeMapped: SettlementJournalProposalMapper.Mapped? = null
    private var activeReceipt: SettlementJournalCommitReceipt? = null
    private val mapper = SettlementJournalProposalMapper(journal)
    private val lastRetainedSourceFrontiers = partitions.associate { partition ->
        ReferenceStreamPartition(eventStream, sourceGeneration, partition) to
            com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(partition)
    }.toMutableMap()
    private val lastIdleTailProbeNanos = LongArray(partitions.size)
    private lateinit var evaluator: SettlementJournalEvaluator

    init {
        if (recoveredState != null) {
            check(recoveredState.sourceBindingDigest == sourceBindingDigest &&
                recoveredState.controlIncarnationId == controlIncarnationId) {
                "restored writer authority differs from current configuration"
            }
            manifestState = SettlementCandidateManifestState(recoveredState.activeOrders,
                recoveredState.seenOrderIds.toMutableSet(), recoveredState.evaluator.policies,
                recoveredState.evaluator.openingIds.keys)
            lastRetainedSourceFrontiers.putAll(recoveredState.lastRetainedSourceFrontiers)
        }
        evaluator = if (recoveredState == null) SettlementJournalEvaluator(
            SettlementEvaluatorHead(0, SettlementJournalStore.ORIGIN_DIGEST,
                lease.epoch, lease.journalIncarnationId),
            { coverage, digest ->
                val source = activeSource
                source != null && coverage.key == source.key &&
                    coverage.evidence == source.evidence &&
                    coverage.expectedTrades == source.trades &&
                    digest == evaluator.tradeManifestDigest(source.trades) &&
                    verifyActiveSource()
            },
            { proposal, receipt ->
                val mapped = activeMapped
                val durable = activeReceipt
                mapped != null && durable != null &&
                    mapped.verifies(proposal, receipt, durable, journal)
            }
        ) else SettlementJournalEvaluator.restoreVerified(recoveredState.evaluator,
            lease.epoch,
            { coverage, digest ->
                val source = activeSource
                source != null && coverage.key == source.key && coverage.evidence == source.evidence &&
                    coverage.expectedTrades == source.trades &&
                    digest == evaluator.tradeManifestDigest(source.trades) && verifyActiveSource()
            },
            { proposal, receipt ->
                val mapped = activeMapped
                val durable = activeReceipt
                mapped != null && durable != null && mapped.verifies(proposal, receipt, durable, journal)
            })
    }

    /** Existing journal resumes only after independent anchor, snapshot and tail verification. */
    companion object {
        fun open(sourceDataSource: DataSource, sourceCatalog: PostMatchReadSourceCatalog,
            sourceReader: PostgresCanonicalOutcomeSourceReader,
            sourceAuthority: SettlementReplaySourceAuthority,
            controlStore: SettlementControlLogStore, controlIncarnationId: String,
            journal: SettlementJournalStore, protocol: SettlementJournalFinalityProtocol,
            finalityAuthority: PostgresSettlementJournalFinalityAuthority,
            bindingReader: SettlementSourceBindingDigestReader,
            eventStream: String, partitions: List<Int>, journalIncarnationId: String,
            maxSourcePositions: Int = 640, maxControlsPerBatch: Int = 256,
            leaseSeconds: Int = 30,
            snapshots: SettlementJournalSnapshotProof? = null): PostMatchSettlementJournalCandidateWorker {
            require(eventStream.isNotBlank() && controlIncarnationId.isNotBlank() &&
                journalIncarnationId.isNotBlank() && partitions.isNotEmpty() &&
                partitions == (0 until partitions.size).toList() &&
                maxSourcePositions in 1..5000 && maxControlsPerBatch in 1..1000)
            val generation = sourceCatalog.generation()
            val binding = bindingReader.readDigest(eventStream)
                ?: error("verified retained source topic binding is missing")
            val head = journal.initialize(eventStream, 1, journalIncarnationId)
            val anchor = finalityAuthority.read(eventStream)
            val recovered = if (anchor == null) {
                check(head.nextBatchSequence == 1L &&
                    head.lastBatchDigest == SettlementJournalStore.ORIGIN_DIGEST &&
                    head.lastControlSequence == 0L && head.incarnationId == journalIncarnationId) {
                    "nonempty journal has no independent finality authority"
                }
                null
            } else {
                val checkpointStore = snapshots ?: error("verified writer checkpoint store is required")
                PostMatchSettlementJournalRecovery(sourceCatalog, sourceAuthority, controlStore,
                    journal, checkpointStore, finalityAuthority, bindingReader)
                    .acquire(eventStream, partitions, controlIncarnationId,
                        leaseSeconds = leaseSeconds)
            }
            val lease = if (recovered == null) {
                val controls = controlStore.readVerifiedPrefix(eventStream, controlIncarnationId)
                protocol.bootstrapAtOrigin(eventStream, bindingReader)
                protocol.acquireAndFence(eventStream, sourceAuthority,
                    controls.referenceVerifier(), bindingReader, leaseSeconds = leaseSeconds)
            } else recovered.lease
            check(sourceCatalog.generation() == generation &&
                bindingReader.readDigest(eventStream) == binding) {
                "source generation or binding changed during writer startup"
            }
            return PostMatchSettlementJournalCandidateWorker(sourceDataSource, sourceCatalog,
                sourceReader, sourceAuthority, controlStore, controlIncarnationId, journal,
                protocol, finalityAuthority, bindingReader, snapshots, eventStream, partitions,
                generation, binding, lease, recovered?.state, maxSourcePositions,
                maxControlsPerBatch)
        }
    }

    /** One bounded ordered decision; null means no retained or control input is ready. */
    @Synchronized
    fun pollOnce(): SettlementCandidateJournalProgress? {
        check(!stopped) { "candidate settlement writer stopped after an ambiguous or invalid input" }
        try {
            lease = finalityAuthority.renewLease(lease)
            check(sourceCatalog.generation() == sourceGeneration &&
                bindingReader.readDigest(eventStream) == sourceBindingDigest) {
                "source identity changed during settlement writing"
            }
            val head = journal.head(eventStream)
            val local = evaluator.snapshot().head
            check(head.nextBatchSequence == local.batchSequence + 1 &&
                head.lastBatchDigest == local.batchDigest &&
                head.ownerEpoch == lease.epoch && head.incarnationId == lease.journalIncarnationId) {
                "journal head moved outside candidate writer"
            }
            val prefix = controlStore.readVerifiedPrefix(eventStream, controlIncarnationId)
            val members = prefix.batches.flatMap { it.members }
            val priorDigest = if (head.lastControlSequence == 0L)
                SettlementJournalStore.ORIGIN_DIGEST else
                members.getOrNull(Math.toIntExact(head.lastControlSequence - 1))?.prefixDigest
                    ?: error("journal control frontier missing from retained control log")
            check(priorDigest == head.lastControlDigest) {
                "journal and retained control frontiers differ"
            }
            val pending = members.drop(Math.toIntExact(head.lastControlSequence))
                .take(maxControlsPerBatch).map { it to it.decode() }
            val current = evaluator.snapshot()
            val readyCount = pending.takeWhile { (_, control) ->
                settlementControlReady(control, current.sourceFrontiers, current.outstanding)
            }.size
            val newMembers = pending.take(readyCount).map { it.first }
            val controls = pending.take(readyCount).map { it.second }
            val blockedControl = pending.getOrNull(readyCount)?.second
            val stagedState = manifestState.withControls(controls)
            val prepared = mutableListOf<SettlementPreparedInput>()
            controls.forEachIndexed { index, control ->
                prepared += SettlementPreparedInput.Control(index.toLong(), control,
                    settlementReferenceControlDigest(control))
            }
            // Drain a bounded accepted control prefix before source; a future control may
            // advance only after its required source frontier or outstanding trade exists.
            val hasMoreControls = members.size > head.lastControlSequence + readyCount
            val selected = if (hasMoreControls && blockedControl == null) null else
                selectSourceWindow(controls.size, stagedState, blockedControl)
            val source = selected?.first
            val sourceRead = selected?.second
            if (source != null) {
                activeSource = source
                activeSourceRead = sourceRead
                prepared += SettlementPreparedInput.SourceCoverage(controls.size.toLong(),
                    source.key, source.evidence, source.trades)
                source.trades.forEach { prepared += SettlementPreparedInput.Trade(
                    controls.size.toLong(), it) }
            }
            if (prepared.isEmpty()) return null
            val decision = evaluator.prepare(prepared)
            val mapped = mapper.map(eventStream, head, decision, prepared,
                source?.let { listOf(it.journalWindow) } ?: emptyList())
            activeMapped = mapped
            verifyControlMembers(newMembers)
            check(source == null || verifyActiveSource()) {
                "matching source changed before journal append"
            }
            val acknowledged = protocol.appendAndAcknowledge(lease, mapped.batch)
            activeReceipt = acknowledged.journalReceipt
            evaluator.confirm(decision, SettlementAppendReceipt(decision.expectedHead,
                decision.proposalDigest, acknowledged.journalReceipt.proposalDigest,
                decision.expectedHead.copy(batchSequence = acknowledged.journalReceipt.batchSequence,
                    batchDigest = acknowledged.journalReceipt.batchDigest)))
            source?.journalWindow?.members?.maxOfOrNull { it.streamSequence }?.let { retained ->
                val key = ReferenceStreamPartition(eventStream, sourceGeneration,
                    source.journalWindow.partitionId)
                check(retained >= lastRetainedSourceFrontiers.getValue(key)) {
                    "committed retained source frontier regressed"
                }
                lastRetainedSourceFrontiers[key] = retained
            }
            if (source == null) {
                manifestState = stagedState
            } else {
                val committedSeen = (manifestState.seenOrderIds as? MutableSet<String>)
                    ?: manifestState.seenOrderIds.toMutableSet()
                check(source.newOrderIds.none { it in committedSeen }) {
                    "source order identity changed after finality acknowledgment"
                }
                committedSeen.addAll(source.newOrderIds)
                manifestState = source.nextManifestState.copy(seenOrderIds = committedSeen)
            }
            if (snapshots != null && (acknowledged.journalReceipt.batchSequence == 1L ||
                    acknowledged.journalReceipt.batchSequence % 128L == 0L)) {
                val state = recoverySnapshot()
                protocol.publishWriterCurrentSnapshot(lease, snapshots,
                    SettlementJournalWriterRecoveryState(state.evaluator, state.manifest.orders,
                        state.manifest.seenOrderIds, sourceBindingDigest, controlIncarnationId,
                        state.lastRetainedSourceFrontiers), bindingReader)
            }
            return SettlementCandidateJournalProgress(acknowledged.journalReceipt.batchSequence,
                acknowledged.journalReceipt.resultCount, if (source == null) 0 else 1,
                controls.size)
        } catch (failure: Throwable) {
            stopped = true
            throw failure
        } finally {
            activeSource = null
            activeSourceRead = null
            activeMapped = null
            activeReceipt = null
        }
    }

    @Synchronized
    fun recoverySnapshot(): SettlementCandidateRecoverySnapshot {
        check(!stopped) { "candidate writer stopped before a verified recovery snapshot" }
        return SettlementCandidateRecoverySnapshot(evaluator.recoveryState(), manifestState.copy(
            orders = manifestState.orders.toMap(),
            seenOrderIds = manifestState.seenOrderIds.toSet(),
            policies = manifestState.policies.toMap(),
            openings = manifestState.openings.toSet()),
            lastRetainedSourceFrontiers.toMap())
    }

    private fun verifyControlMembers(selected: List<com.reef.platform.infrastructure.persistence.SettlementControlLogVerifiedMember>) {
        if (selected.isEmpty()) return
        val reread = controlStore.readVerifiedPrefix(eventStream, controlIncarnationId)
            .batches.flatMap { it.members }
        check(selected.all { member -> reread.getOrNull(Math.toIntExact(member.sequence - 1)) == member }) {
            "retained control changed before journal append"
        }
    }

    private fun verifyActiveSource(): Boolean {
        val source = activeSource ?: return false
        val read = activeSourceRead
        return if (read == null) sourceAuthority.verifyEmpty(eventStream, source.journalWindow)
        else {
            val again = sourceAuthority.readVerified(eventStream, source.journalWindow)
            again.sourceDigest == read.sourceDigest &&
                again.outcomes.map { it.source } == read.outcomes.map { it.source }
        }
    }

    private fun selectSourceWindow(stepIndex: Int, state: SettlementCandidateManifestState,
        blockedControl: ReferenceControl? = null):
        Pair<SettlementCandidatePreparedSource, VerifiedCanonicalSourceWindow?>? {
        val heads = sourceCatalog.partitionHeads(eventStream, partitions.size)
        val frontiers = evaluator.snapshot().sourceFrontiers
        partitions.forEach { partition ->
            val key = ReferenceStreamPartition(eventStream, sourceGeneration, partition)
            val from = frontiers[key] ?: com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(partition)
            val policyLimit = (blockedControl as? ReferencePolicyActivation)
                ?.effectiveAfterSourceFrontiers?.get(key)
            if (blockedControl is ReferencePolicyActivation &&
                (policyLimit == null || from >= policyLimit)) return@forEach
            val head = heads[partition] ?: error("source partition head is missing")
            check(head >= lastRetainedSourceFrontiers.getValue(key)) {
                "retained source rolled back behind committed journal coverage"
            }
            if (head <= from) {
                val now = System.nanoTime()
                if (now - lastIdleTailProbeNanos[partition] < 500_000_000L) return@forEach
                lastIdleTailProbeNanos[partition] = now
                val stable = sourceAuthority.stableEndSequence(eventStream, sourceGeneration,
                    partition, from) ?: return@forEach
                check(stable >= from) { "command broker stable frontier regressed" }
                if (stable == from) return@forEach
                val through = minOf(stable, from + maxSourcePositions, policyLimit ?: Long.MAX_VALUE)
                val proofId = settlementDigest(listOf("reef.settlement.broker-absence.v1",
                    sourceBindingDigest, eventStream, sourceGeneration, partition.toString(),
                    from.toString(), through.toString()))
                val empty = manifest.prepareEmpty(stepIndex, state, key, from, through, proofId)
                // A committed command may be awaiting materialization; do not claim its offset empty.
                if (!sourceAuthority.verifyEmpty(eventStream, empty.journalWindow)) return@forEach
                return empty to null
            }
            val range = nextRetainedRange(partition, from, head)
            if (range.first > from + 1) {
                val through = minOf(range.first - 1, from + maxSourcePositions,
                    policyLimit ?: Long.MAX_VALUE)
                val proofId = settlementDigest(listOf("reef.settlement.broker-absence.v1",
                    sourceBindingDigest, eventStream, sourceGeneration, partition.toString(),
                    from.toString(), through.toString()))
                val empty = manifest.prepareEmpty(stepIndex, state, key, from, through, proofId)
                check(sourceAuthority.verifyEmpty(eventStream, empty.journalWindow)) {
                    "source gap lacks broker-backed empty-range proof"
                }
                return empty to null
            }
            val verified = sourceReader.readVerifiedWindow("settlement-journal-candidate",
                eventStream, partition, sourceGeneration, from,
                minOf(range.second, policyLimit ?: Long.MAX_VALUE))
            return manifest.prepare(stepIndex, state, verified) to verified
        }
        return null
    }

    /** Indexed sequence-only scan bounds next contiguous range before JSON payload read. */
    private fun nextRetainedRange(partition: Int, from: Long, head: Long): Pair<Long, Long> =
        sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT event_stream, stream_sequence FROM runtime.canonical_command_outcomes
                   WHERE partition_id = ? AND stream_sequence > ?
                   ORDER BY stream_sequence LIMIT ?"""
            ).use { statement ->
                statement.setInt(1, partition)
                statement.setLong(2, from)
                statement.setInt(3, maxSourcePositions)
                statement.executeQuery().use { rows ->
                    var first = -1L
                    var through = from
                    while (rows.next()) {
                        check(rows.getString(1) == eventStream) {
                            "source partition contains foreign event stream"
                        }
                        val sequence = rows.getLong(2)
                        if (first == -1L) first = sequence
                        if (sequence != through + 1) break
                        through = sequence
                    }
                    check(first > from && first <= head) { "source head has no retained next row" }
                    first to if (through == from) first else through
                }
            }
        }
}

/** Decodes already verified retained outcomes into exact settlement trade manifest. */
internal class SettlementCandidateTradeManifest {
    fun prepare(stepIndex: Int, state: SettlementCandidateManifestState,
        verified: VerifiedCanonicalSourceWindow): SettlementCandidatePreparedSource {
        val partition = ReferenceStreamPartition(verified.eventStream,
            verified.sourceGeneration, verified.partitionId)
        val orders = state.orders.toMutableMap()
        val newOrderIds = mutableSetOf<String>()
        val members = mutableListOf<SettlementJournalSourceMember>()
        val trades = mutableListOf<ReferenceObligation>()
        verified.outcomes.forEach { outcome ->
            val source = outcome.source
            val sourceDigest = settlementSourceMemberDigest(source)
            members += SettlementJournalSourceMember(source.streamSequence, sourceDigest)
            outcome.effects.forEach { envelope ->
                when (val effect = envelope.effect) {
                    is CanonicalEffect.Accepted -> effect.newOrder?.let { identity ->
                        check(identity.orderId !in state.seenOrderIds &&
                            newOrderIds.add(identity.orderId) &&
                            orders.putIfAbsent(identity.orderId, identity) == null) {
                            "settlement order identity repeated"
                        }
                    }
                    is CanonicalEffect.Trade -> {
                        val buyer = orders[effect.buyOrderId] ?: error("missing buyer ownership")
                        val seller = orders[effect.sellOrderId] ?: error("missing seller ownership")
                        check(buyer.side == "BUY" && seller.side == "SELL" &&
                            buyer.runId.isNotBlank() && buyer.runId == seller.runId &&
                            buyer.venueSessionId == seller.venueSessionId &&
                            buyer.instrumentId == effect.instrumentId &&
                            seller.instrumentId == effect.instrumentId) {
                            "trade ownership or session conflict"
                        }
                        val policy = state.policies[buyer.runId to buyer.venueSessionId]
                            ?: error("missing immutable policy activation")
                        check(policy.mode == "instant-post-trade") {
                            "candidate writer supports instant post-trade policy only"
                        }
                        val quantity = positiveSettlementDecimal(effect.quantityUnits)
                        val cash = positiveSettlementDecimal(effect.price) * quantity
                        val buyerCash = ReferenceAccountKey(buyer.runId, buyer.participantId,
                            buyer.accountId, "CASH", effect.currency)
                        val sellerSecurity = ReferenceAccountKey(seller.runId,
                            seller.participantId, seller.accountId, "SECURITY", effect.instrumentId)
                        check(buyerCash in state.openings && sellerSecurity in state.openings) {
                            "trade debit resources lack immutable opening input"
                        }
                        trades += ReferenceObligation(effect.tradeId, effect.eventId,
                            ReferenceSourcePosition(partition, source.streamSequence,
                                envelope.position.effectOrdinal), sourceDigest, buyer.runId,
                            buyer.venueSessionId, buyerCash, sellerSecurity, effect.instrumentId,
                            effect.currency, quantity, cash, Instant.parse(effect.occurredAt),
                            policy.controlId, policy.policyVersion, policy.mode)
                    }
                    is CanonicalEffect.OrderStateChanged -> {
                        if (effect.status in setOf("FILLED", "CANCELED", "CANCELLED", "EXPIRED",
                                "REJECTED")) {
                            orders.remove(effect.orderId)
                        } else if (effect.orderId in state.seenOrderIds ||
                            effect.orderId in newOrderIds) {
                            check(effect.orderId in orders) {
                                "closed order returned to active matching state"
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        val proofId = verified.sourceDigest
        return prepared(stepIndex, partition, verified.fromExclusiveSequence,
            verified.throughInclusiveSequence, proofId, members, trades,
            state.copy(orders = orders.toMap(), seenOrderIds =
                SettlementCandidateSeenOrderOverlay(state.seenOrderIds, newOrderIds)),
            newOrderIds)
    }

    fun prepareEmpty(stepIndex: Int, state: SettlementCandidateManifestState,
        partition: ReferenceStreamPartition, fromExclusive: Long, throughInclusive: Long,
        proofId: String): SettlementCandidatePreparedSource =
        prepared(stepIndex, partition, fromExclusive, throughInclusive, proofId,
            emptyList(), emptyList(), state)

    private fun prepared(stepIndex: Int, partition: ReferenceStreamPartition,
        fromExclusive: Long, throughInclusive: Long, proofId: String,
        members: List<SettlementJournalSourceMember>, trades: List<ReferenceObligation>,
        state: SettlementCandidateManifestState,
        newOrderIds: Set<String> = emptySet()): SettlementCandidatePreparedSource {
        require(stepIndex >= 0 && proofId.isNotBlank() && throughInclusive > fromExclusive)
        val referenceCoverageDigest = settlementDigest(listOf(
            "reef.reference.source-coverage.v1", partition.eventStream,
            partition.sourceGeneration, partition.partitionId.toString(),
            fromExclusive.toString(), throughInclusive.toString(), proofId
        ) + members.map { it.digest })
        val initial = SettlementJournalSourceWindow(stepIndex, partition.sourceGeneration,
            partition.partitionId, fromExclusive, throughInclusive, proofId,
            referenceCoverageDigest, members.toList())
        val window = initial.copy(coverageDigest =
            SettlementJournalStore.sourceCoverageDigest(partition.eventStream, initial))
        check(window.coverageDigest == referenceCoverageDigest) {
            "reference and journal source coverage digests differ"
        }
        val evidence = ReferenceCoverageEvidence(proofId = proofId, digest = referenceCoverageDigest)
        return SettlementCandidatePreparedSource(window, evidence,
            ReferenceSourceWindowKey(partition, fromExclusive, throughInclusive),
            trades.toList(), state, newOrderIds.toSet())
    }
}

/** Only a contiguous ready control prefix may enter the next journal batch. */
internal fun settlementControlReady(control: ReferenceControl,
    frontiers: Map<ReferenceStreamPartition, Long>,
    outstanding: Map<String, ReferenceObligation>): Boolean = when (control) {
    is ReferencePolicyActivation -> control.effectiveAfterSourceFrontiers.all { (key, required) ->
        (frontiers[key] ?: com.reef.platform.application.postmatch.CanonicalStreamPosition.origin(
            key.partitionId)) >= required
    }
    is ReferenceOpening -> true
    is ReferenceFunding -> control.retryTradeIds.all { tradeId ->
        val trade = outstanding[tradeId] ?: return@all false
        check(control.account == trade.buyer || control.account == trade.seller) {
            "accepted funding retry does not affect trade debit resources"
        }
        true
    }
}

/** Control proof digest independently checked against the retained control-log member. */
internal fun settlementReferenceControlDigest(control: ReferenceControl): String =
    settlementDigest(when (control) {
        is ReferencePolicyActivation -> listOf("reef.reference.policy.v1",
            control.controlSequence.toString(), control.controlId, control.runId,
            control.venueSessionId,
            settlementDigest(control.effectiveAfterSourceFrontiers.entries.sortedWith(compareBy(
                { it.key.eventStream }, { it.key.sourceGeneration }, { it.key.partitionId }
            )).flatMap { (stream, frontier) -> listOf(stream.eventStream,
                stream.sourceGeneration, stream.partitionId.toString(), frontier.toString()) }),
            control.profileId, control.policyVersion.toString(), control.mode,
            control.settlementCycle, control.nettingMode, control.ledgerPostingMode,
            control.selectionSource)
        is ReferenceOpening -> listOf("reef.reference.opening.v1",
            control.controlSequence.toString(), control.controlId) +
            settlementAccountFields(control.account) + control.amount.toPlainString()
        is ReferenceFunding -> listOf("reef.reference.funding.v1",
            control.controlSequence.toString(), control.controlId) +
            settlementAccountFields(control.account) +
            listOf(control.amount.toPlainString()) + control.retryTradeIds
    })

private fun settlementAccountFields(key: ReferenceAccountKey) = listOf(key.runId,
    key.participantId, key.accountId, key.assetType, key.assetId)

private fun positiveSettlementDecimal(text: String): BigDecimal = BigDecimal(text).also {
    require(it > BigDecimal.ZERO) { "settlement quantity or price must be positive" }
}

private fun settlementSourceMemberDigest(source: com.reef.platform.application.postmatch.CanonicalOutcomeSource) =
    settlementDigest(listOf("reef.reference.canonical-source.v1", source.eventStream,
        source.partitionId.toString(), source.streamSequence.toString(), source.batchId,
        source.commandId, source.commandType, source.payloadHash, source.instrumentId,
        source.orderId, source.resultStatus, source.resultPayloadJson))

private fun settlementDigest(fields: List<String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        hash.update(bytes)
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
