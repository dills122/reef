package com.reef.platform.application.settlementjournal

import com.fasterxml.jackson.databind.json.JsonMapper
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Inputs have already passed source/control authority verification and are ordered for journal arbitration. */
sealed interface SettlementPreparedInput {
    val stepIndex: Long

    data class SourceCoverage(
        override val stepIndex: Long,
        val key: ReferenceSourceWindowKey,
        val evidence: ReferenceCoverageEvidence,
        /** Exact prepared trades decoded from this verified retained source window, in source order. */
        val expectedTrades: List<ReferenceObligation>
    ) : SettlementPreparedInput

    data class Control(
        override val stepIndex: Long,
        val value: ReferenceControl,
        val verifiedMemberDigest: String
    ) : SettlementPreparedInput

    data class Trade(override val stepIndex: Long, val obligation: ReferenceObligation) : SettlementPreparedInput
}

data class SettlementEvaluatorHead(
    /** Last committed batch. Genesis is sequence 0; store's nextBatchSequence is this plus one. */
    val batchSequence: Long,
    val batchDigest: String,
    val ownerEpoch: Long,
    val ownerIncarnation: String
)

data class SettlementDecisionProposal(
    val expectedHead: SettlementEvaluatorHead,
    val proposalDigest: String,
    val preparedInputsDigest: String,
    val results: List<SettlementDecisionResult>,
    val inputStepIndices: List<Long>
)

/** Journal-facing result; balances are limited to the four affected accounts. */
data class SettlementDecisionResult(
    val decisionStepIndex: Long,
    val tradeId: String,
    val attemptNumber: Int,
    val trade: ReferenceObligation,
    val position: ReferenceSourcePosition,
    val sourceMemberDigest: String,
    val policyControlId: String,
    val boundOpeningControlIds: List<String>,
    val boundFundingControlIds: List<String>,
    val boundControlDigest: String,
    val kind: ReferenceResultKind,
    val breakReason: ReferenceBreakReason?,
    val workflow: List<ReferenceWorkflowEvent>,
    val workflowJson: String,
    val effects: List<ReferenceEffect>,
    val touchedBalancesBefore: Map<ReferenceAccountKey, BigDecimal>,
    val touchedBalancesAfter: Map<ReferenceAccountKey, BigDecimal>
)

/** Created only after the append writer verifies a committed batch (including an ambiguous retry). */
data class SettlementAppendReceipt(
    val expectedHead: SettlementEvaluatorHead,
    val proposalDigest: String,
    /** Digest of the exact store request bytes, independently checked against durable rows. */
    val storeProposalDigest: String,
    val committedHead: SettlementEvaluatorHead
)

/** Authenticates the append receipt and exact store-request mapping after commit or ambiguous retry. */
fun interface SettlementCommitReceiptVerifier {
    fun verify(proposal: SettlementDecisionProposal, receipt: SettlementAppendReceipt): Boolean
}

/** Authenticates decoded trade manifest against exact retained source members, including zero-trade windows. */
fun interface SettlementSourceManifestVerifier {
    fun verify(coverage: SettlementPreparedInput.SourceCoverage, computedManifestDigest: String): Boolean
}

data class SettlementEvaluatorSnapshot(
    val head: SettlementEvaluatorHead,
    val balances: Map<ReferenceAccountKey, BigDecimal>,
    val outstanding: Map<String, ReferenceObligation>,
    val attempts: Map<String, Int>,
    val controlDigests: Map<String, String>,
    val sourceFrontiers: Map<ReferenceStreamPartition, Long>
)

/**
 * Single-owner decision stage. The caller persists each proposal (even when results is empty)
 * before confirming it. No account or policy database access occurs in the decision loop.
 */
class SettlementJournalEvaluator(
    initialHead: SettlementEvaluatorHead,
    private val manifestVerifier: SettlementSourceManifestVerifier,
    private val commitVerifier: SettlementCommitReceiptVerifier
) {
    companion object {
        /** Recompute exact ordered prepared-input binding before mapping proposal to a store request. */
        fun preparedInputsDigest(inputs: List<SettlementPreparedInput>): String =
            computePreparedInputsDigest(inputs)
    }

    private val mapper = JsonMapper.builder().build()
    private var head = initialHead.also(::validateHead)
    private var live = State()
    private var pending: Pending? = null

    init {
        require(initialHead.batchSequence == 0L && initialHead.batchDigest == "0".repeat(64)) {
            "non-genesis evaluator state requires verified snapshot and replay"
        }
    }

    fun prepare(inputs: List<SettlementPreparedInput>): SettlementDecisionProposal {
        check(pending == null) { "an earlier journal proposal is awaiting confirmation" }
        require(inputs.isNotEmpty()) { "empty proposal has no authority input" }
        val frozenInputs = inputs.map(::freezePreparedInput)
        val inputsDigest = preparedInputsDigest(frozenInputs)
        val staged = live.copy()
        val results = mutableListOf<SettlementDecisionResult>()
        val pendingManifests = mutableMapOf<ReferenceSourceWindowKey, ManifestCursor>()
        var lastInputStepIndex = -1L
        var addedAuthorityInput = false
        frozenInputs.forEach { input ->
            require(input.stepIndex == lastInputStepIndex || input.stepIndex == lastInputStepIndex + 1) {
                "prepared input order changed"
            }
            lastInputStepIndex = input.stepIndex
            when (input) {
                is SettlementPreparedInput.SourceCoverage -> {
                    val key = input.key
                    val evidence = input.evidence
                    require(evidence.version == 1 && evidence.proofId.isNotBlank() &&
                        evidence.digest.matches(Regex("[0-9a-f]{64}")) &&
                        key.stream.eventStream.isNotBlank() && key.stream.sourceGeneration.isNotBlank() &&
                        key.stream.partitionId in 0..32767 &&
                        key.fromExclusiveSequence >=
                            CanonicalStreamPosition.origin(key.stream.partitionId) &&
                        key.throughInclusiveSequence > key.fromExclusiveSequence &&
                        key.throughInclusiveSequence <=
                            CanonicalStreamPosition.origin(
                                key.stream.partitionId) + ((1L shl 48) - 1) &&
                        key.throughInclusiveSequence - key.fromExclusiveSequence <= 5000) {
                        "invalid verified source coverage"
                    }
                    val manifest = input.expectedTrades.toList()
                    var priorTradePosition: Pair<Long, Int>? = null
                    manifest.forEach { trade ->
                        val position = trade.position
                        val order = position.streamSequence to position.effectOrdinal
                        val prior = priorTradePosition
                        require(position.stream == key.stream &&
                            position.streamSequence > key.fromExclusiveSequence &&
                            position.streamSequence <= key.throughInclusiveSequence &&
                            position.effectOrdinal >= 0 &&
                            trade.tradeId.isNotBlank() && trade.eventId.isNotBlank() &&
                            trade.sourceMemberDigest.matches(Regex("[0-9a-f]{64}")) &&
                            (prior == null || order.first > prior.first ||
                                (order.first == prior.first && order.second > prior.second))) {
                            "source trade manifest is incomplete or out of order"
                        }
                        priorTradePosition = order
                    }
                    val manifestDigest = tradeManifestDigest(manifest)
                    check(manifestVerifier.verify(input.copy(expectedTrades = manifest), manifestDigest)) {
                        "decoded trade manifest proof failed"
                    }
                    val earlier = staged.sourceWindows[key]
                    if (earlier != null) {
                        check(earlier == CoverageRecord(evidence, manifestDigest)) {
                            "changed source coverage or decoded trade manifest"
                        }
                    } else {
                        addedAuthorityInput = true
                        val partition = key.stream.eventStream to key.stream.partitionId
                        val generation = staged.partitionGenerations.putIfAbsent(
                            partition, key.stream.sourceGeneration)
                        check(generation == null || generation == key.stream.sourceGeneration) {
                            "source generation changed"
                        }
                        check(key.fromExclusiveSequence == (staged.sourceFrontiers[key.stream]
                            ?: CanonicalStreamPosition.origin(
                                key.stream.partitionId))) { "source frontier gap or overlap" }
                        staged.sourceWindows[key] = CoverageRecord(evidence, manifestDigest)
                        staged.sourceFrontiers[key.stream] = key.throughInclusiveSequence
                        staged.latestCoverage[key.stream] = CoverageLocation(
                            key, head.batchSequence + 1, input.stepIndex)
                        pendingManifests[key] = ManifestCursor(manifest)
                    }
                }
                is SettlementPreparedInput.Control -> {
                    val control = input.value
                    val digest = controlMemberDigest(control)
                    require(input.verifiedMemberDigest == digest) { "control bytes changed after verification" }
                    if (control.controlId !in staged.controls) addedAuthorityInput = true
                    applyControl(staged, control, input.stepIndex, digest, results)
                }
                is SettlementPreparedInput.Trade -> {
                    val obligation = input.obligation
                    applyTrade(staged, obligation, input.stepIndex, pendingManifests, results)
                }
            }
        }
        check(pendingManifests.values.all { it.nextIndex == it.members.size }) {
            "verified source window has omitted trade decisions"
        }
        check(addedAuthorityInput || results.isNotEmpty()) {
            "duplicate-only delivery is a no-op; do not append a journal batch"
        }
        val proposalDigest = digest(listOf(
            "reef.settlement.evaluator-proposal.v2", head.batchSequence.toString(),
            head.batchDigest, head.ownerEpoch.toString(), head.ownerIncarnation,
            inputsDigest, results.size.toString()
        ) + results.map(::resultDigest))
        val proposal = SettlementDecisionProposal(head, proposalDigest, inputsDigest,
            results.toList(), frozenInputs.map { it.stepIndex })
        pending = Pending(proposal, staged)
        return proposal
    }

    fun confirm(proposal: SettlementDecisionProposal, receipt: SettlementAppendReceipt) {
        val awaiting = pending ?: error("no journal proposal is pending")
        check(awaiting.proposal === proposal && proposal.expectedHead == head) {
            "stale or changed journal proposal"
        }
        check(receipt.expectedHead == head && receipt.proposalDigest == proposal.proposalDigest &&
            receipt.storeProposalDigest.matches(Regex("[0-9a-f]{64}")) &&
            receipt.committedHead.batchSequence == head.batchSequence + 1 &&
            receipt.committedHead.ownerEpoch == head.ownerEpoch &&
            receipt.committedHead.ownerIncarnation == head.ownerIncarnation &&
            receipt.committedHead.batchDigest.matches(Regex("[0-9a-f]{64}"))) {
            "journal receipt does not confirm proposed batch and owner fence"
        }
        check(commitVerifier.verify(proposal, receipt)) {
            "journal receipt or store proposal mapping failed verification"
        }
        awaiting.staged.commitInto(live)
        head = receipt.committedHead
        pending = null
    }

    /** A failed append discards all tentative balances and attempts. Ambiguous append needs a verified receipt first. */
    fun discard(proposal: SettlementDecisionProposal) {
        check(pending?.proposal === proposal) { "stale or changed journal proposal" }
        pending = null
    }

    fun snapshot(): SettlementEvaluatorSnapshot = SettlementEvaluatorSnapshot(
        head, live.balances.toMap(), live.outstanding.toMap(), live.attempts.toMap(),
        live.controls.toMap(), live.sourceFrontiers.toMap()
    )

    /** Stable structural digest of the source-decoded, policy-bound trade manifest. */
    fun tradeManifestDigest(trades: List<ReferenceObligation>): String = tradeManifestDigestOf(trades)

    private fun applyControl(state: State, control: ReferenceControl, stepIndex: Long,
                             memberDigest: String,
                             results: MutableList<SettlementDecisionResult>) {
        require(control.version == 1 && control.controlSequence > 0 && control.controlId.isNotBlank()) {
            "unsupported or incomplete control identity"
        }
        val previous = state.controls[control.controlId]
        if (previous != null) {
            check(previous == memberDigest && state.controlSequences[control.controlSequence] == control.controlId) {
                "changed control input"
            }
            return
        }
        check(control.controlSequence == state.lastControlSequence + 1 &&
            state.controlSequences.putIfAbsent(control.controlSequence, control.controlId) == null) {
            "control order changed or has a gap"
        }
        state.lastControlSequence = control.controlSequence
        state.controls[control.controlId] = memberDigest
        appendDigestField(state.controlPrefixHash, control.controlId)
        appendDigestField(state.controlPrefixHash, memberDigest)
        when (control) {
            is ReferencePolicyActivation -> {
                require(control.runId.isNotBlank() && control.venueSessionId.isNotBlank() &&
                    control.profileId.isNotBlank() && control.policyVersion > 0 &&
                    control.mode in setOf("instant-post-trade", "ops-realistic")) {
                    "invalid policy activation"
                }
                check(control.effectiveAfterSourceFrontiers.all { (stream, sequence) ->
                    stream.eventStream.isNotBlank() && stream.sourceGeneration.isNotBlank() &&
                        stream.partitionId in 0..32767 &&
                        sequence >= CanonicalStreamPosition.origin(stream.partitionId) &&
                        sequence <= CanonicalStreamPosition.origin(stream.partitionId) + ((1L shl 48) - 1) &&
                        (state.partitionGenerations[stream.eventStream to stream.partitionId]
                            ?.let { it == stream.sourceGeneration } ?: true) &&
                        (state.sourceFrontiers[stream] ?:
                            CanonicalStreamPosition.origin(stream.partitionId)) >= sequence
                }) { "policy activated before source frontier" }
                state.policies[control.runId to control.venueSessionId] = control
            }
            is ReferenceOpening -> {
                require(control.amount >= BigDecimal.ZERO) { "opening amount must be nonnegative" }
                check(control.account !in state.openingIds && control.account !in state.balances) {
                    "opening account repeated or already affected"
                }
                state.openingIds[control.account] = control.controlId
                state.balances[control.account] = control.amount
            }
            is ReferenceFunding -> {
                require(control.amount > BigDecimal.ZERO &&
                    control.retryTradeIds.distinct() == control.retryTradeIds) {
                    "funding amount or retry list is invalid"
                }
                check(control.account in state.openingIds) { "funding account has no opening authority" }
                state.balances[control.account] = state.balances.getValue(control.account) + control.amount
                state.fundingIds[control.account] =
                    (state.fundingIds[control.account].orEmpty() + control.controlId).toMutableList()
                control.retryTradeIds.forEach { tradeId ->
                    val obligation = state.outstanding[tradeId]
                        ?: error("funding retry references settled or unknown trade")
                    check(control.account == obligation.buyer || control.account == obligation.seller) {
                        "funding retry does not affect trade debit resources"
                    }
                    attempt(state, obligation, stepIndex, results)
                }
            }
        }
    }

    private fun applyTrade(state: State, obligation: ReferenceObligation, stepIndex: Long,
                           pendingManifests: Map<ReferenceSourceWindowKey, ManifestCursor>,
                           results: MutableList<SettlementDecisionResult>) {
        val earlier = state.obligations[obligation.tradeId]
        if (earlier != null) {
            check(earlier == obligation) { "changed trade input" }
            return // Duplicate delivery is not a new attempt.
        }
        val covering = state.latestCoverage[obligation.position.stream]
            ?: error("trade lacks verified source coverage")
        check(obligation.position.streamSequence > covering.key.fromExclusiveSequence &&
            obligation.position.streamSequence <= covering.key.throughInclusiveSequence &&
            obligation.position.effectOrdinal >= 0 &&
            covering.batchSequence == head.batchSequence + 1 &&
            covering.stepIndex == stepIndex) {
            "trade position is outside its verified source step"
        }
        val manifest = pendingManifests[covering.key]
            ?: error("new trade has no uncommitted verified source manifest")
        check(manifest.members.getOrNull(manifest.nextIndex) == obligation) {
            "trade is missing from, changed from, or out of order with verified source manifest"
        }
        manifest.nextIndex++
        val sourceOrder = obligation.position.streamSequence to obligation.position.effectOrdinal
        val priorSourceOrder = state.lastTradePositionByCoverage[covering.key]
        check(priorSourceOrder == null ||
            sourceOrder.first > priorSourceOrder.first ||
            (sourceOrder.first == priorSourceOrder.first && sourceOrder.second > priorSourceOrder.second)) {
            "trade decision order changed within verified source window"
        }
        require(obligation.tradeId.isNotBlank() && obligation.eventId.isNotBlank() &&
            obligation.sourceMemberDigest.matches(Regex("[0-9a-f]{64}")) &&
            obligation.cash > BigDecimal.ZERO && obligation.quantity > BigDecimal.ZERO &&
            obligation.buyer.assetType == "CASH" && obligation.buyer.assetId == obligation.currency &&
            obligation.seller.assetType == "SECURITY" && obligation.seller.assetId == obligation.instrumentId &&
            obligation.buyer.runId == obligation.runId && obligation.seller.runId == obligation.runId) {
            "invalid prepared obligation"
        }
        val policy = state.policies[obligation.runId to obligation.venueSessionId]
            ?: error("missing immutable policy activation")
        check(policy.controlId == obligation.policyControlId &&
            policy.policyVersion == obligation.policyVersion && policy.mode == obligation.mode) {
            "prepared obligation policy binding changed"
        }
        check(obligation.buyer in state.openingIds && obligation.seller in state.openingIds) {
            "trade debit resources lack immutable opening input"
        }
        state.obligations[obligation.tradeId] = obligation
        state.lastTradePositionByCoverage[covering.key] = sourceOrder
        state.outstanding[obligation.tradeId] = obligation
        if (obligation.mode == "instant-post-trade") attempt(state, obligation, stepIndex, results)
    }

    private fun attempt(state: State, obligation: ReferenceObligation, stepIndex: Long,
                        results: MutableList<SettlementDecisionResult>) {
        check(state.outstanding.containsKey(obligation.tradeId)) { "settlement retry has no outstanding obligation" }
        val number = (state.attempts[obligation.tradeId] ?: 0) + 1
        val effects = effects(obligation)
        val cashAvailable = (state.balances[obligation.buyer] ?: BigDecimal.ZERO) >= obligation.cash
        val securityAvailable = (state.balances[obligation.seller] ?: BigDecimal.ZERO) >= obligation.quantity
        val settled = cashAvailable && securityAvailable
        val affected = effects.map { it.account }.distinct()
        val before = affected.associateWith { state.balances[it] ?: BigDecimal.ZERO }
        if (settled) effects.forEach { effect ->
            val previous = state.balances[effect.account] ?: BigDecimal.ZERO
            state.balances[effect.account] = if (effect.direction == ReferenceDirection.DEBIT)
                previous - effect.amount else previous + effect.amount
        }
        val workflow = workflow(obligation, number, cashAvailable, securityAvailable)
        results += SettlementDecisionResult(
            stepIndex,
            obligation.tradeId, number, obligation, obligation.position, obligation.sourceMemberDigest,
            obligation.policyControlId, affected.mapNotNull(state.openingIds::get).distinct(),
            affected.flatMap { state.fundingIds[it].orEmpty() }.distinct(),
            state.controlPrefixHash.clone().let { (it as MessageDigest).digest().toHex() },
            if (settled) ReferenceResultKind.SETTLED else ReferenceResultKind.BREAK,
            when { !cashAvailable -> ReferenceBreakReason.CASH_LEG_FAILED
                !securityAvailable -> ReferenceBreakReason.SECURITY_LEG_FAILED
                else -> null },
            workflow, workflowJson(workflow), if (settled) effects else emptyList(),
            before, affected.associateWith { state.balances[it] ?: BigDecimal.ZERO }
        )
        state.attempts[obligation.tradeId] = number
        if (settled) state.outstanding.remove(obligation.tradeId)
    }

    private fun effects(o: ReferenceObligation): List<ReferenceEffect> = listOf(
        ReferenceEffect(ReferenceLegKind.BUYER_CASH_DEBIT, o.buyer, ReferenceDirection.DEBIT, o.cash),
        ReferenceEffect(ReferenceLegKind.SELLER_CASH_CREDIT,
            ReferenceAccountKey(o.runId, o.seller.participantId, o.seller.accountId, "CASH", o.currency),
            ReferenceDirection.CREDIT, o.cash),
        ReferenceEffect(ReferenceLegKind.SELLER_SECURITY_DEBIT, o.seller, ReferenceDirection.DEBIT, o.quantity),
        ReferenceEffect(ReferenceLegKind.BUYER_SECURITY_CREDIT,
            ReferenceAccountKey(o.runId, o.buyer.participantId, o.buyer.accountId, "SECURITY", o.instrumentId),
            ReferenceDirection.CREDIT, o.quantity)
    )

    private fun workflow(o: ReferenceObligation, attempt: Int, cash: Boolean, security: Boolean):
        List<ReferenceWorkflowEvent> {
        val at = o.occurredAt.toString()
        val prefix = listOf("ALLOCATION_PROPOSED", "CONFIRMATION_GENERATED", "AFFIRMATION_ACCEPTED",
            "CLEARING_SUBMITTED", "CLEARING_ACCEPTED", "NOVATION_RECORDED",
            "INSTRUCTION_CREATED", "ATTEMPT_STARTED")
        return prefix.map { ReferenceWorkflowEvent("${o.tradeId}:$it:$attempt", it, at) } + listOf(
            ReferenceWorkflowEvent("${o.tradeId}:CASH_LEG:$attempt", "CASH_LEG", at,
                if (cash) "LEG_SUCCEEDED" else "LEG_FAILED"),
            ReferenceWorkflowEvent("${o.tradeId}:SECURITY_LEG:$attempt", "SECURITY_LEG", at,
                if (security) "LEG_SUCCEEDED" else "LEG_FAILED"),
            ReferenceWorkflowEvent("${o.tradeId}:${if (cash && security) "SETTLED" else "BREAK_OPENED"}:$attempt",
                if (cash && security) "SETTLED" else "BREAK_OPENED", at)
        )
    }

    private fun workflowJson(events: List<ReferenceWorkflowEvent>): String =
        mapper.writeValueAsString(events.map { event ->
            linkedMapOf<String, String>().apply {
                put("id", event.id)
                put("kind", event.kind)
                event.state?.let { put("state", it) }
                put("occurredAt", event.occurredAt)
            }
        })

    /** Structural digest only; caller must independently authenticate control authority. */
    fun controlMemberDigest(control: ReferenceControl): String = structuralControlMemberDigest(control)

    private fun resultDigest(result: SettlementDecisionResult): String = digest(listOf(
        "reef.settlement.proposed-result.v1", result.decisionStepIndex.toString(),
        result.tradeId, result.attemptNumber.toString(),
        result.sourceMemberDigest, result.policyControlId, result.boundControlDigest,
        result.kind.name, result.breakReason?.name.orEmpty(), result.workflowJson,
        obligationDigest(result.trade), result.boundOpeningControlIds.joinToString("\u0000"),
        result.boundFundingControlIds.joinToString("\u0000")
    ) + result.effects.flatMap { effect ->
        listOf(effect.kind.name, effect.direction.name, effect.amount.toPlainString()) +
            accountFields(effect.account)
    } + result.touchedBalancesBefore.entries.flatMap { (account, balance) ->
        accountFields(account) + balance.toPlainString() +
            result.touchedBalancesAfter.getValue(account).toPlainString()
    })

    private fun validateHead(value: SettlementEvaluatorHead) {
        require(value.batchSequence >= 0 && value.batchDigest.matches(Regex("[0-9a-f]{64}")) &&
            value.ownerEpoch >= 0 && value.ownerIncarnation.isNotBlank()) {
            "invalid committed journal head"
        }
    }

    private data class Pending(val proposal: SettlementDecisionProposal, val staged: State)

    private data class CoverageLocation(
        val key: ReferenceSourceWindowKey,
        val batchSequence: Long,
        val stepIndex: Long
    )

    private data class CoverageRecord(
        val evidence: ReferenceCoverageEvidence,
        val manifestDigest: String
    )

    private class ManifestCursor(val members: List<ReferenceObligation>, var nextIndex: Int = 0)

    private class State(
        val controls: MutableMap<String, String> = linkedMapOf(),
        val controlSequences: MutableMap<Long, String> = mutableMapOf(),
        var lastControlSequence: Long = 0,
        val policies: MutableMap<Pair<String, String>, ReferencePolicyActivation> = mutableMapOf(),
        val openingIds: MutableMap<ReferenceAccountKey, String> = mutableMapOf(),
        val fundingIds: MutableMap<ReferenceAccountKey, MutableList<String>> = mutableMapOf(),
        val balances: MutableMap<ReferenceAccountKey, BigDecimal> = linkedMapOf(),
        val obligations: MutableMap<String, ReferenceObligation> = mutableMapOf(),
        val outstanding: MutableMap<String, ReferenceObligation> = linkedMapOf(),
        val attempts: MutableMap<String, Int> = mutableMapOf(),
        val sourceWindows: MutableMap<ReferenceSourceWindowKey, CoverageRecord> = mutableMapOf(),
        val sourceFrontiers: MutableMap<ReferenceStreamPartition, Long> = mutableMapOf(),
        val partitionGenerations: MutableMap<Pair<String, Int>, String> = mutableMapOf(),
        val latestCoverage: MutableMap<ReferenceStreamPartition, CoverageLocation> =
            mutableMapOf(),
        val lastTradePositionByCoverage: MutableMap<ReferenceSourceWindowKey, Pair<Long, Int>> =
            mutableMapOf(),
        var controlPrefixHash: MessageDigest = MessageDigest.getInstance("SHA-256")
    ) {
        fun copy(): State = State(OverlayMap(controls), OverlayMap(controlSequences),
            lastControlSequence, OverlayMap(policies), OverlayMap(openingIds),
            OverlayMap(fundingIds), OverlayMap(balances), OverlayMap(obligations),
            OverlayMap(outstanding), OverlayMap(attempts), OverlayMap(sourceWindows),
            OverlayMap(sourceFrontiers), OverlayMap(partitionGenerations),
            OverlayMap(latestCoverage), OverlayMap(lastTradePositionByCoverage),
            controlPrefixHash.clone() as MessageDigest)

        fun commitInto(target: State) {
            (controls as OverlayMap).commitInto(target.controls)
            (controlSequences as OverlayMap).commitInto(target.controlSequences)
            (policies as OverlayMap).commitInto(target.policies)
            (openingIds as OverlayMap).commitInto(target.openingIds)
            (fundingIds as OverlayMap).commitInto(target.fundingIds)
            (balances as OverlayMap).commitInto(target.balances)
            (obligations as OverlayMap).commitInto(target.obligations)
            (outstanding as OverlayMap).commitInto(target.outstanding)
            (attempts as OverlayMap).commitInto(target.attempts)
            (sourceWindows as OverlayMap).commitInto(target.sourceWindows)
            (sourceFrontiers as OverlayMap).commitInto(target.sourceFrontiers)
            (partitionGenerations as OverlayMap).commitInto(target.partitionGenerations)
            (latestCoverage as OverlayMap).commitInto(target.latestCoverage)
            (lastTradePositionByCoverage as OverlayMap).commitInto(target.lastTradePositionByCoverage)
            target.lastControlSequence = lastControlSequence
            target.controlPrefixHash = controlPrefixHash
        }
    }

    /** Copy-on-write batch state: work and commit scale with changed keys, not all live accounts. */
    private class OverlayMap<K, V>(private val base: MutableMap<K, V>) : AbstractMutableMap<K, V>() {
        private val writes = linkedMapOf<K, V>()
        private val deleted = mutableSetOf<K>()

        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = LinkedHashMap<K, V>().apply {
                putAll(base)
                deleted.forEach { remove(it) }
                putAll(writes)
            }.entries

        override fun containsKey(key: K): Boolean = key in writes || (key !in deleted && key in base)

        override fun get(key: K): V? = when {
            key in writes -> writes[key]
            key in deleted -> null
            else -> base[key]
        }

        override fun put(key: K, value: V): V? {
            val previous = get(key)
            deleted.remove(key)
            writes[key] = value
            return previous
        }

        override fun remove(key: K): V? {
            val previous = get(key)
            writes.remove(key)
            deleted.add(key)
            return previous
        }

        fun commitInto(target: MutableMap<K, V>) {
            deleted.forEach(target::remove)
            target.putAll(writes)
        }
    }
}

private fun freezePreparedInput(input: SettlementPreparedInput): SettlementPreparedInput = when (input) {
    is SettlementPreparedInput.Control -> input.copy(value = freezeControl(input.value))
    is SettlementPreparedInput.SourceCoverage -> input.copy(expectedTrades = input.expectedTrades.toList())
    is SettlementPreparedInput.Trade -> input.copy()
}

private fun freezeControl(control: ReferenceControl): ReferenceControl = when (control) {
    is ReferencePolicyActivation -> control.copy(
        effectiveAfterSourceFrontiers = control.effectiveAfterSourceFrontiers.toMap())
    is ReferenceOpening -> control.copy()
    is ReferenceFunding -> control.copy(retryTradeIds = control.retryTradeIds.toList())
}

private fun computePreparedInputsDigest(inputs: List<SettlementPreparedInput>): String {
    val members = inputs.map { original ->
        when (val input = freezePreparedInput(original)) {
            is SettlementPreparedInput.Control -> digest(listOf(
                "reef.settlement.prepared-input.control.v1", input.stepIndex.toString(),
                input.value.version.toString(), structuralControlMemberDigest(input.value),
                input.verifiedMemberDigest
            ))
            is SettlementPreparedInput.SourceCoverage -> digest(listOf(
                "reef.settlement.prepared-input.coverage.v1", input.stepIndex.toString(),
                input.key.stream.eventStream, input.key.stream.sourceGeneration,
                input.key.stream.partitionId.toString(), input.key.fromExclusiveSequence.toString(),
                input.key.throughInclusiveSequence.toString(), input.evidence.version.toString(),
                input.evidence.proofId, input.evidence.digest,
                tradeManifestDigestOf(input.expectedTrades)
            ))
            is SettlementPreparedInput.Trade -> digest(listOf(
                "reef.settlement.prepared-input.trade.v1", input.stepIndex.toString(),
                obligationDigest(input.obligation)
            ))
        }
    }
    return digest(listOf("reef.settlement.prepared-inputs.v1", inputs.size.toString()) + members)
}

private fun tradeManifestDigestOf(trades: List<ReferenceObligation>): String = digest(
    listOf("reef.settlement.trade-manifest.v1", trades.size.toString()) +
        trades.map(::obligationDigest)
)

private fun structuralControlMemberDigest(control: ReferenceControl): String = digest(when (control) {
    is ReferencePolicyActivation -> listOf(
        "reef.reference.policy.v1", control.controlSequence.toString(), control.controlId,
        control.runId, control.venueSessionId,
        control.effectiveAfterSourceFrontiers.entries.sortedWith(compareBy(
            { it.key.eventStream }, { it.key.sourceGeneration }, { it.key.partitionId }
        )).flatMap { (stream, frontier) -> listOf(
            stream.eventStream, stream.sourceGeneration, stream.partitionId.toString(), frontier.toString()
        ) }.let(::digest),
        control.profileId, control.policyVersion.toString(), control.mode, control.settlementCycle,
        control.nettingMode, control.ledgerPostingMode, control.selectionSource
    )
    is ReferenceOpening -> listOf("reef.reference.opening.v1", control.controlSequence.toString(),
        control.controlId) + accountFields(control.account) + control.amount.toPlainString()
    is ReferenceFunding -> listOf("reef.reference.funding.v1", control.controlSequence.toString(),
        control.controlId) + accountFields(control.account) + listOf(control.amount.toPlainString()) +
        control.retryTradeIds
})

private fun obligationDigest(o: ReferenceObligation): String = digest(listOf(
    "reef.settlement.prepared-trade.v1", o.tradeId, o.eventId,
    o.position.stream.eventStream, o.position.stream.sourceGeneration,
    o.position.stream.partitionId.toString(), o.position.streamSequence.toString(),
    o.position.effectOrdinal.toString(), o.sourceMemberDigest, o.runId, o.venueSessionId
) + accountFields(o.buyer) + accountFields(o.seller) + listOf(
    o.instrumentId, o.currency, o.quantity.toPlainString(), o.cash.toPlainString(),
    o.occurredAt.toString(), o.policyControlId, o.policyVersion.toString(), o.mode
))

private fun accountFields(key: ReferenceAccountKey): List<String> =
    listOf(key.runId, key.participantId, key.accountId, key.assetType, key.assetId)

private fun digest(fields: List<String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fields.forEach { appendDigestField(hash, it) }
    return hash.digest().toHex()
}

private fun appendDigestField(hash: MessageDigest, field: String) {
    val bytes = field.toByteArray(StandardCharsets.UTF_8)
    hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    hash.update(bytes)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
