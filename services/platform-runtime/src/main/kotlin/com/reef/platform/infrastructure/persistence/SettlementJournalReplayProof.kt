package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectDecoder
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.postmatch.VerifiedCanonicalSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceControlProofVerifier
import com.reef.platform.application.settlementjournal.ReferenceCoverageEvidence
import com.reef.platform.application.settlementjournal.ReferenceDirection
import com.reef.platform.application.settlementjournal.ReferenceEffect
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferenceResult
import com.reef.platform.application.settlementjournal.ReferenceResultKind
import com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreter
import com.reef.platform.application.settlementjournal.ReferenceSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceStep
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Source implementation must read retained authority independently of journal. `readVerified`
 * must return the existing source verifier's window, including its sourceDigest proof; this
 * interface does not authenticate a caller-constructed window. `verifyEmpty` must consult an
 * independent absence authority, since the existing source verifier rejects empty windows.
 */
interface SettlementReplaySourceAuthority {
    fun readVerified(eventStream: String, window: SettlementJournalSourceWindow): VerifiedCanonicalSourceWindow
    fun verifyEmpty(eventStream: String, window: SettlementJournalSourceWindow): Boolean
}

data class SettlementJournalReplayState(
    val head: SettlementJournalHead,
    val balances: Map<ReferenceAccountKey, BigDecimal>,
    val outstandingTradeIds: Set<String>,
    val resultCount: Int
)

/**
 * Bounded genesis-to-head proof. Reference evaluation reruns for every batch (quadratic in batch
 * count), so this is local recovery evidence, not a hot-path restart or snapshot implementation.
 * It never grants an owner lease or starts a writer.
 */
class SettlementJournalReplayProof(private val store: SettlementJournalStore) {
    fun prove(
        eventStream: String,
        sourceAuthority: SettlementReplaySourceAuthority,
        controlVerifier: ReferenceControlProofVerifier,
        maxBatches: Int = 256
    ): SettlementJournalReplayState {
        require(eventStream.isNotBlank() && maxBatches in 1..10_000)
        val pinned = store.head(eventStream)
        check(pinned.nextBatchSequence - 1 <= maxBatches) { "journal replay exceeds batch bound" }
        val allSteps = mutableListOf<ReferenceStep>()
        val ledgerBalances = linkedMapOf<ReferenceAccountKey, BigDecimal>()
        val ledgerOutstanding = linkedSetOf<String>()
        val seenTrades = mutableSetOf<String>()
        val interpreter = ReferenceSettlementInterpreter({ _, _ -> true }, controlVerifier)
        val decoder = CanonicalEffectDecoder()
        val sourceVerifier = CanonicalSourceCoverageVerifier()
        var previousDigest = SettlementJournalStore.ORIGIN_DIGEST
        var controlSequence = 0L
        var controlPrefix = SettlementJournalStore.ORIGIN_DIGEST
        var resultCount = 0
        var stepCount = 0L
        var sourceMemberCount = 0L
        for (batchSequence in 1 until pinned.nextBatchSequence) {
            val batch = store.replayProposalCopy(store.readVerifiedBatch(eventStream, batchSequence))
            check(batch.expectedBatchSequence == batchSequence &&
                batch.expectedPreviousDigest == previousDigest && batch.eventStream == eventStream &&
                batch.incarnationId == pinned.incarnationId) { "journal replay chain or incarnation changed" }
            val stepsByIndex = (batch.sourceWindows.map { it.stepIndex to it } +
                batch.controls.map { it.stepIndex to it }).sortedBy { it.first }
            stepCount += stepsByIndex.size.toLong()
            sourceMemberCount += batch.sourceWindows.sumOf { it.members.size.toLong() }
            check(stepCount <= 10_000 && sourceMemberCount <= 100_000) {
                "journal replay exceeds step or retained-source bound"
            }
            check(stepsByIndex.map { it.first } == (0 until stepsByIndex.size).toList()) {
                "journal replay step order changed"
            }
            val fundingByStep = batch.controls.filter { it.kind == "FUNDING" }
                .associateBy { it.stepIndex }
            val sourceByStep = batch.sourceWindows.associateBy { it.stepIndex }
            val controlPrefixAtStep = mutableMapOf<Int, String>()
            val newSteps = mutableListOf<ReferenceStep>()
            val tradesByStep = mutableMapOf<Int, List<String>>()
            val decodedControls = mutableMapOf<Int, com.reef.platform.application.settlementjournal.ReferenceControl>()
            stepsByIndex.forEach { (stepIndex, member) ->
                when (member) {
                    is SettlementJournalSourceWindow -> {
                        val outcomes = if (member.members.isEmpty()) {
                            check(sourceAuthority.verifyEmpty(eventStream, member)) {
                                "source empty-range proof failed"
                            }
                            emptyList()
                        } else {
                            val verified = sourceAuthority.readVerified(eventStream, member)
                            check(verified.eventStream == eventStream &&
                                verified.sourceGeneration == member.sourceGeneration &&
                                verified.partitionId == member.partitionId &&
                                verified.fromExclusiveSequence == member.fromExclusiveSequence &&
                                verified.throughInclusiveSequence == member.throughInclusiveSequence) {
                                "retained source window identity changed"
                            }
                            val read = verified.outcomes.map { it.source }
                            val rechecked = sourceVerifier.verify(verified.consumerName, eventStream,
                                member.partitionId, member.sourceGeneration,
                                member.fromExclusiveSequence, member.throughInclusiveSequence, read)
                            check(rechecked.sourceDigest == verified.sourceDigest &&
                                rechecked.outcomes == verified.outcomes) {
                                "retained source verification changed before replay"
                            }
                            read
                        }
                        check(outcomes.size == member.members.size &&
                            outcomes.map { SettlementJournalSourceMember(it.streamSequence, sourceDigest(it)) } ==
                                member.members) { "retained source manifest changed" }
                        val source = ReferenceSourceWindow(stream = ReferenceStreamPartition(
                            eventStream, member.sourceGeneration, member.partitionId),
                            fromExclusiveSequence = member.fromExclusiveSequence,
                            throughInclusiveSequence = member.throughInclusiveSequence,
                            outcomes = outcomes, evidence = ReferenceCoverageEvidence(
                                proofId = member.coverageProofId, digest = member.coverageDigest))
                        newSteps += ReferenceStep.Source(source)
                        tradesByStep[stepIndex] = outcomes.flatMap { outcome ->
                            decoder.decode(outcome).mapNotNull { (it.effect as? CanonicalEffect.Trade)?.tradeId }
                        }
                    }
                    is SettlementJournalControl -> {
                        check(member.sequence == controlSequence + 1) { "control replay sequence gap" }
                        controlSequence = member.sequence
                        check(member.digest == SettlementJournalStore.controlMemberDigest(member)) {
                            "control replay payload changed"
                        }
                        val control = SettlementJournalControlCodec.decode(member.payload)
                        check(control.version == member.version &&
                            control.controlSequence == member.sequence && control.controlId == member.id &&
                            when (control) {
                                is com.reef.platform.application.settlementjournal.ReferencePolicyActivation -> member.kind == "POLICY"
                                is ReferenceOpening -> member.kind == "OPENING"
                                is ReferenceFunding -> member.kind == "FUNDING"
                            }) { "decoded control identity changed" }
                        decodedControls[stepIndex] = control
                        newSteps += ReferenceStep.Control(control)
                        controlPrefix = SettlementJournalStore.controlPrefixDigest(controlPrefix, member)
                    }
                }
                controlPrefixAtStep[stepIndex] = controlPrefix
            }
            allSteps += newSteps
            val evaluated = interpreter.evaluate(allSteps)
            val newResults = evaluated.results.drop(resultCount)
            check(evaluated.results.size >= resultCount && newResults.size == batch.results.size) {
                "journal result count differs from independent evaluation"
            }
            var resultIndex = 0
            stepsByIndex.forEach { (stepIndex, _) ->
                decodedControls[stepIndex]?.let { control -> when (control) {
                    is ReferenceOpening -> {
                        check(ledgerBalances.putIfAbsent(control.account, control.amount) == null) {
                            "opening replay repeated"
                        }
                    }
                    is ReferenceFunding -> ledgerBalances[control.account] =
                        (ledgerBalances[control.account] ?: error("funding lacks opening")) + control.amount
                    else -> Unit
                } }
                tradesByStep[stepIndex].orEmpty().forEach { tradeId ->
                    check(seenTrades.add(tradeId)) { "source trade repeated" }
                    ledgerOutstanding += tradeId
                }
                while (resultIndex < batch.results.size &&
                    batch.results[resultIndex].decisionStepIndex == stepIndex) {
                val stored = batch.results[resultIndex]
                val expected = newResults[resultIndex]
                check(stored.boundControlDigest == controlPrefixAtStep[stored.decisionStepIndex]) {
                    "result control prefix changed"
                }
                compareResult(stored, expected)
                val sourceStep = sourceByStep[stored.decisionStepIndex]
                if (sourceStep != null) {
                    check(stored.attemptNumber == 1 &&
                        stored.sourceGeneration == sourceStep.sourceGeneration &&
                        stored.partitionId == sourceStep.partitionId &&
                        stored.streamSequence in (sourceStep.fromExclusiveSequence + 1)..sourceStep.throughInclusiveSequence) {
                        "result source decision step changed"
                    }
                } else {
                    val funding = fundingByStep[stored.decisionStepIndex]
                        ?: error("result has no source or funding decision step")
                    val decoded = SettlementJournalControlCodec.decode(funding.payload) as ReferenceFunding
                    check(stored.tradeId in decoded.retryTradeIds && funding.id in stored.fundingControlIds) {
                        "result funding retry step changed"
                    }
                }
                if (stored.outcome == "SETTLED") {
                    check(expected.effects.size == 4) { "settled trade lacks four DvP effects" }
                    expected.effects.forEach { effect ->
                        val prior = ledgerBalances[effect.account] ?: BigDecimal.ZERO
                        ledgerBalances[effect.account] = if (effect.direction == ReferenceDirection.DEBIT)
                            prior - effect.amount else prior + effect.amount
                    }
                    check(ledgerOutstanding.remove(stored.tradeId)) { "settled trade was not outstanding" }
                } else check(expected.effects.isEmpty()) { "broken trade transferred assets" }
                resultIndex++
                }
            }
            check(resultIndex == batch.results.size) { "journal result step order changed" }
            check(equalBalances(ledgerBalances, evaluated.balances) &&
                ledgerOutstanding == evaluated.outstanding.keys) {
                "journal cumulative balances or outstanding trades differ"
            }
            resultCount = evaluated.results.size
            previousDigest = store.preview(batch).batchDigest
        }
        check(previousDigest == pinned.lastBatchDigest &&
            controlSequence == pinned.lastControlSequence &&
            controlPrefix == pinned.lastControlDigest && store.head(eventStream) == pinned) {
            "journal head moved during replay"
        }
        return SettlementJournalReplayState(pinned, ledgerBalances.toMap(),
            ledgerOutstanding.toSet(), resultCount)
    }

    private fun compareResult(stored: SettlementJournalResult, expected: ReferenceResult) {
        val trade = expected.trade
        check(stored.tradeId == expected.tradeId && stored.attemptNumber == expected.attemptNumber &&
            stored.sourceGeneration == expected.position.stream.sourceGeneration &&
            stored.partitionId == expected.position.stream.partitionId &&
            stored.streamSequence == expected.position.streamSequence &&
            stored.effectOrdinal == expected.position.effectOrdinal &&
            stored.sourceMemberDigest == expected.sourceMemberDigest &&
            stored.eventId == trade.eventId && stored.runId == trade.runId &&
            stored.venueSessionId == trade.venueSessionId &&
            stored.buyerParticipantId == trade.buyer.participantId &&
            stored.buyerAccountId == trade.buyer.accountId &&
            stored.sellerParticipantId == trade.seller.participantId &&
            stored.sellerAccountId == trade.seller.accountId &&
            stored.currency == trade.currency && stored.instrumentId == trade.instrumentId &&
            stored.cashAmount.compareTo(trade.cash) == 0 &&
            stored.quantityUnits.compareTo(trade.quantity) == 0 &&
            stored.occurredAt == trade.occurredAt &&
            stored.policyControlId == expected.policyControlId &&
            stored.openingControlIds == expected.boundOpeningControlIds &&
            stored.fundingControlIds == expected.boundFundingControlIds &&
            stored.outcome == expected.kind.name &&
            stored.breakReason == expected.breakReason?.name &&
            stored.workflowFacts == expected.workflowJson) {
            "journal typed result differs from independent evaluation"
        }
        val derivedEffects = if (stored.outcome == "SETTLED") listOf(
            ReferenceEffect(com.reef.platform.application.settlementjournal.ReferenceLegKind.BUYER_CASH_DEBIT,
                ReferenceAccountKey(stored.runId, stored.buyerParticipantId, stored.buyerAccountId,
                    "CASH", stored.currency), ReferenceDirection.DEBIT, stored.cashAmount),
            ReferenceEffect(com.reef.platform.application.settlementjournal.ReferenceLegKind.SELLER_CASH_CREDIT,
                ReferenceAccountKey(stored.runId, stored.sellerParticipantId, stored.sellerAccountId,
                    "CASH", stored.currency), ReferenceDirection.CREDIT, stored.cashAmount),
            ReferenceEffect(com.reef.platform.application.settlementjournal.ReferenceLegKind.SELLER_SECURITY_DEBIT,
                ReferenceAccountKey(stored.runId, stored.sellerParticipantId, stored.sellerAccountId,
                    "SECURITY", stored.instrumentId), ReferenceDirection.DEBIT, stored.quantityUnits),
            ReferenceEffect(com.reef.platform.application.settlementjournal.ReferenceLegKind.BUYER_SECURITY_CREDIT,
                ReferenceAccountKey(stored.runId, stored.buyerParticipantId, stored.buyerAccountId,
                    "SECURITY", stored.instrumentId), ReferenceDirection.CREDIT, stored.quantityUnits)
        ) else emptyList()
        check(derivedEffects.size == expected.effects.size &&
            derivedEffects.zip(expected.effects).all { (actual, reference) ->
                actual.kind == reference.kind && actual.account == reference.account &&
                    actual.direction == reference.direction && actual.amount.compareTo(reference.amount) == 0
            }) { "journal DvP effects differ from independent evaluation" }
    }

    private fun equalBalances(actual: Map<ReferenceAccountKey, BigDecimal>,
        expected: Map<ReferenceAccountKey, BigDecimal>): Boolean =
        actual.keys == expected.keys && actual.all { (key, value) ->
            value.compareTo(expected.getValue(key)) == 0
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
