package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceDirection
import com.reef.platform.application.settlementjournal.ReferenceEffect
import com.reef.platform.application.settlementjournal.ReferenceLegKind
import com.reef.platform.application.settlementjournal.ReferenceResultKind
import com.reef.platform.application.settlementjournal.SettlementAppendReceipt
import com.reef.platform.application.settlementjournal.SettlementDecisionProposal
import com.reef.platform.application.settlementjournal.SettlementDecisionResult
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import com.reef.platform.application.settlementjournal.SettlementJournalEvaluator
import com.reef.platform.application.settlementjournal.SettlementPreparedInput
import java.math.BigDecimal

/** Exact bridge between tentative decisions and the single durable settlement authority. */
class SettlementJournalProposalMapper(private val store: SettlementJournalStore) {
    data class Mapped(
        val decision: SettlementDecisionProposal,
        val batch: SettlementJournalBatchProposal,
        val preview: SettlementJournalProposalPreview
    ) {
        fun verifies(decision: SettlementDecisionProposal, receipt: SettlementAppendReceipt,
            durable: SettlementJournalCommitReceipt, store: SettlementJournalStore): Boolean {
            if (this.decision !== decision || receipt.expectedHead != decision.expectedHead ||
                receipt.proposalDigest != decision.proposalDigest ||
                receipt.storeProposalDigest != preview.proposalDigest ||
                receipt.committedHead.batchSequence != durable.batchSequence ||
                receipt.committedHead.batchDigest != preview.batchDigest ||
                durable.proposalDigest != preview.proposalDigest ||
                durable.batchDigest != preview.batchDigest ||
                durable.resultCount != batch.results.size) return false
            val committed = store.head(batch.eventStream)
            return store.preview(batch) == preview &&
                committed.nextBatchSequence == durable.batchSequence + 1 &&
                committed.lastBatchDigest == durable.batchDigest &&
                committed.ownerEpoch == batch.ownerEpoch &&
                committed.incarnationId == batch.incarnationId
        }
    }

    fun map(eventStream: String, head: SettlementJournalHead,
        decision: SettlementDecisionProposal, inputs: List<SettlementPreparedInput>,
        windows: List<SettlementJournalSourceWindow>): Mapped {
        require(decision.expectedHead.batchSequence + 1 == head.nextBatchSequence &&
            decision.expectedHead.batchDigest == head.lastBatchDigest &&
            decision.expectedHead.ownerEpoch == head.ownerEpoch &&
            decision.expectedHead.ownerIncarnation == head.incarnationId &&
            decision.inputStepIndices == inputs.map { it.stepIndex } &&
            decision.preparedInputsDigest == SettlementJournalEvaluator.preparedInputsDigest(inputs)) {
            "evaluator proposal and journal head/input differ"
        }
        val coverages = inputs.filterIsInstance<SettlementPreparedInput.SourceCoverage>()
        require(windows.size == coverages.size)
        val windowByStep = windows.associateBy { it.stepIndex.toLong() }
        require(windowByStep.size == windows.size)
        coverages.forEach { input ->
            val window = windowByStep[input.stepIndex]
                ?: error("verified source window missing from journal proposal")
            require(input.key.stream.eventStream == eventStream &&
                input.key.stream.sourceGeneration == window.sourceGeneration &&
                input.key.stream.partitionId == window.partitionId &&
                input.key.fromExclusiveSequence == window.fromExclusiveSequence &&
                input.key.throughInclusiveSequence == window.throughInclusiveSequence &&
                input.evidence.proofId == window.coverageProofId &&
                input.evidence.digest == window.coverageDigest &&
                window.coverageDigest == SettlementJournalStore.sourceCoverageDigest(eventStream, window)) {
                "journal source manifest differs from evaluator coverage"
            }
            val memberBySequence = window.members.associate { it.streamSequence to it.digest }
            require(memberBySequence.size == window.members.size) { "source manifest repeats a sequence" }
            require(input.expectedTrades.all { it.mode == "instant-post-trade" } &&
                input.expectedTrades.all { trade ->
                    trade.position.stream == input.key.stream &&
                        trade.position.streamSequence > window.fromExclusiveSequence &&
                        trade.position.streamSequence <= window.throughInclusiveSequence &&
                        memberBySequence[trade.position.streamSequence] == trade.sourceMemberDigest
                } &&
                decision.results.filter { it.decisionStepIndex == input.stepIndex }
                    .map { it.trade } == input.expectedTrades) {
                "instant-mode journal decision omitted or changed a covered trade"
            }
        }
        val controls = inputs.filterIsInstance<SettlementPreparedInput.Control>().map { input ->
            val kind = when (input.value) {
                is com.reef.platform.application.settlementjournal.ReferencePolicyActivation -> "POLICY"
                is com.reef.platform.application.settlementjournal.ReferenceOpening -> "OPENING"
                is com.reef.platform.application.settlementjournal.ReferenceFunding -> "FUNDING"
            }
            val initial = SettlementJournalControl(Math.toIntExact(input.stepIndex),
                input.value.controlSequence, input.value.controlId, kind,
                input.value.version, SettlementJournalControlCodec.encode(input.value), "0".repeat(64))
            initial.copy(digest = SettlementJournalStore.controlMemberDigest(initial))
        }
        val controlByStep = controls.associateBy { it.stepIndex }
        var prefix = head.lastControlDigest
        val prefixAtStep = mutableMapOf<Int, String>()
        inputs.map { Math.toIntExact(it.stepIndex) }.distinct().forEach { step ->
            controlByStep[step]?.let { prefix = SettlementJournalStore.controlPrefixDigest(prefix, it) }
            prefixAtStep[step] = prefix
        }
        val results = decision.results.map { result ->
            validateDecision(result, eventStream)
            val step = Math.toIntExact(result.decisionStepIndex)
            val controlPrefix = prefixAtStep[step]
                ?: error("settlement decision has no ordered authority step")
            val trade = result.trade
            SettlementJournalResult(step, result.tradeId, result.attemptNumber,
                result.position.stream.sourceGeneration, result.position.stream.partitionId,
                result.position.streamSequence, result.position.effectOrdinal,
                result.sourceMemberDigest, trade.eventId, trade.runId, trade.venueSessionId,
                trade.buyer.participantId, trade.buyer.accountId,
                trade.seller.participantId, trade.seller.accountId, trade.currency,
                trade.instrumentId, trade.cash, trade.quantity, trade.occurredAt,
                result.policyControlId, result.boundOpeningControlIds,
                result.boundFundingControlIds, controlPrefix, result.kind.name,
                result.breakReason?.name, result.workflowJson)
        }
        val batch = SettlementJournalBatchProposal(eventStream, head.nextBatchSequence,
            head.lastBatchDigest, head.ownerEpoch, head.incarnationId, windows, controls, results)
        return Mapped(decision, batch, store.preview(batch))
    }

    private fun validateDecision(result: SettlementDecisionResult, eventStream: String) {
        val trade = result.trade
        require(result.tradeId == trade.tradeId && result.position == trade.position &&
            result.position.stream.eventStream == eventStream &&
            result.sourceMemberDigest == trade.sourceMemberDigest &&
            result.policyControlId == trade.policyControlId &&
            result.workflowJson.isNotBlank() && result.workflow.isNotEmpty()) {
            "journal result differs from evaluated trade"
        }
        val buyerCash = trade.buyer
        val sellerCash = ReferenceAccountKey(trade.runId, trade.seller.participantId,
            trade.seller.accountId, "CASH", trade.currency)
        val sellerSecurity = trade.seller
        val buyerSecurity = ReferenceAccountKey(trade.runId, trade.buyer.participantId,
            trade.buyer.accountId, "SECURITY", trade.instrumentId)
        val expected = listOf(
            ReferenceEffect(ReferenceLegKind.BUYER_CASH_DEBIT, buyerCash, ReferenceDirection.DEBIT, trade.cash),
            ReferenceEffect(ReferenceLegKind.SELLER_CASH_CREDIT, sellerCash, ReferenceDirection.CREDIT, trade.cash),
            ReferenceEffect(ReferenceLegKind.SELLER_SECURITY_DEBIT, sellerSecurity, ReferenceDirection.DEBIT, trade.quantity),
            ReferenceEffect(ReferenceLegKind.BUYER_SECURITY_CREDIT, buyerSecurity, ReferenceDirection.CREDIT, trade.quantity)
        )
        val affected = expected.map { it.account }.toSet()
        require(result.touchedBalancesBefore.keys == affected &&
            result.touchedBalancesAfter.keys == affected) {
            "settlement decision lacks four affected balances"
        }
        when (result.kind) {
            ReferenceResultKind.SETTLED -> {
                require(result.breakReason == null && result.effects == expected)
                val expectedAfter = result.touchedBalancesBefore.toMutableMap()
                expected.forEach { effect ->
                    val before = expectedAfter.getValue(effect.account)
                    expectedAfter[effect.account] = if (effect.direction == ReferenceDirection.DEBIT)
                        before - effect.amount else before + effect.amount
                }
                require(equalBalances(expectedAfter, result.touchedBalancesAfter)) {
                    "settled decision balance effect differs from four DvP legs"
                }
            }
            ReferenceResultKind.BREAK -> require(result.breakReason != null &&
                result.effects.isEmpty() &&
                equalBalances(result.touchedBalancesBefore, result.touchedBalancesAfter)) {
                "break transferred an asset or lost its reason"
            }
        }
    }

    private fun equalBalances(left: Map<ReferenceAccountKey, BigDecimal>,
        right: Map<ReferenceAccountKey, BigDecimal>) =
        left.keys == right.keys && left.all { (key, value) -> value.compareTo(right.getValue(key)) == 0 }
}
