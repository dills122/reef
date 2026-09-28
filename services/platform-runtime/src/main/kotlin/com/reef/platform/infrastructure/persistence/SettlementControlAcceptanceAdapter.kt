package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.settlementjournal.ReferenceControl
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition

data class AcceptedSettlementControl(val control: ReferenceControl, val duplicate: Boolean)

fun interface SettlementControlAcceptance {
    fun accept(input: ReferenceControl): AcceptedSettlementControl
}

/**
 * Fresh-generation control intake. Callers submit a control with sequence zero; this authority
 * assigns order and commits it before any optional mutable compatibility projection is updated.
 * The declared genesis boundary is configuration, not proof of historical mutable rows.
 */
class SettlementControlAcceptanceAdapter(
    private val store: SettlementControlLogStore,
    private val eventStream: String,
    private val incarnationId: String,
    private val ownerEpoch: Long,
    private val ownerNonce: String,
    genesisFrontiers: Map<ReferenceStreamPartition, Long>
) : SettlementControlAcceptance {
    private val genesisFrontiers = genesisFrontiers.toMap()

    init {
        require(eventStream.isNotBlank() && incarnationId.isNotBlank() && ownerEpoch > 0 &&
            ownerNonce.isNotBlank() && genesisFrontiers.isNotEmpty())
        require(genesisFrontiers.all { (partition, frontier) ->
            partition.eventStream == eventStream && partition.sourceGeneration.isNotBlank() &&
                partition.partitionId in 0..32767 &&
                frontier >= CanonicalStreamPosition.origin(partition.partitionId) &&
                frontier <= CanonicalStreamPosition.origin(partition.partitionId) + ((1L shl 48) - 1)
        }) { "control acceptance requires a valid fresh source boundary" }
    }

    override fun accept(input: ReferenceControl): AcceptedSettlementControl {
        require(input.controlSequence == 0L && input.controlId.isNotBlank()) {
            "control acceptance assigns sequence from the durable head"
        }
        val verified = store.readVerifiedPrefix(eventStream, incarnationId)
        val head = verified.head
        check(head.ownerEpoch == ownerEpoch && head.ownerNonce == ownerNonce) {
            "control acceptance owner is fenced"
        }
        val existing = verified.batches.asSequence().flatMap { it.members.asSequence() }
            .firstOrNull { it.id == input.controlId }
        if (existing != null) {
            val saved = existing.decode()
            check(saved == input.withSequence(existing.sequence)) {
                "control ID was already accepted with different facts"
            }
            return AcceptedSettlementControl(saved, true)
        }
        when (input) {
            is ReferencePolicyActivation -> {
                check(input.effectiveAfterSourceFrontiers.keys == genesisFrontiers.keys &&
                    input.effectiveAfterSourceFrontiers.all { (key, frontier) ->
                        frontier == genesisFrontiers.getValue(key)
                    }) { "future policy activation requires a journal-frontier scheduler" }
            }
            is ReferenceOpening -> check(verified.batches.asSequence()
                .flatMap { it.members.asSequence() }.map { it.decode() }
                .filterIsInstance<ReferenceOpening>().none { it.account == input.account }) {
                "opening account already accepted"
            }
            is ReferenceFunding -> {
                check(input.retryTradeIds.isEmpty()) {
                    "funding retries require journal-backed outstanding-trade proof"
                }
                check(verified.batches.asSequence()
                    .flatMap { it.members.asSequence() }.map { it.decode() }
                    .filterIsInstance<ReferenceOpening>().any { it.account == input.account }) {
                    "funding requires accepted opening"
                }
            }
        }
        val ordered = input.withSequence(head.nextControlSequence)
        store.appendBatch(SettlementControlLogProposal(eventStream, head.nextControlSequence,
            head.lastControlDigest, ownerEpoch, ownerNonce, incarnationId, listOf(ordered)))
        return AcceptedSettlementControl(ordered, false)
    }

    private fun ReferenceControl.withSequence(sequence: Long): ReferenceControl = when (this) {
        is ReferencePolicyActivation -> copy(controlSequence = sequence)
        is ReferenceOpening -> copy(controlSequence = sequence)
        is ReferenceFunding -> copy(controlSequence = sequence)
    }
}
