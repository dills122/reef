package com.reef.platform.application.settlementjournal

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectDecoder
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Pure proof model. Source coverage proof authentication belongs to the source reader. */
data class ReferenceCoverageEvidence(
    val version: Int = 1,
    val proofId: String,
    val digest: String
)

data class ReferenceStreamPartition(val eventStream: String, val sourceGeneration: String, val partitionId: Int)

data class ReferenceSourceWindow(
    val version: Int = 1,
    val stream: ReferenceStreamPartition,
    val fromExclusiveSequence: Long,
    val throughInclusiveSequence: Long,
    val outcomes: List<CanonicalOutcomeSource>,
    val evidence: ReferenceCoverageEvidence
)

/** Implementations must authenticate evidence against retained source authority, including empty ranges. */
fun interface ReferenceCoverageProofVerifier {
    fun verify(window: ReferenceSourceWindow, computedDigest: String): Boolean
}

/** Must authenticate each immutable control member against an ordered external control authority. */
fun interface ReferenceControlProofVerifier {
    fun verify(control: ReferenceControl, computedDigest: String): Boolean
}

data class ReferenceAccountKey(
    val runId: String,
    val participantId: String,
    val accountId: String,
    val assetType: String,
    val assetId: String
)

sealed interface ReferenceControl {
    val version: Int
    val controlSequence: Long
    val controlId: String
}

data class ReferencePolicyActivation(
    override val version: Int = 1,
    override val controlSequence: Long,
    override val controlId: String,
    val runId: String,
    val venueSessionId: String,
    val effectiveAfterSourceFrontiers: Map<ReferenceStreamPartition, Long>,
    val profileId: String,
    val policyVersion: Int,
    val mode: String,
    val settlementCycle: String,
    val nettingMode: String,
    val ledgerPostingMode: String,
    val selectionSource: String
) : ReferenceControl

data class ReferenceOpening(
    override val version: Int = 1,
    override val controlSequence: Long,
    override val controlId: String,
    val account: ReferenceAccountKey,
    val amount: BigDecimal
) : ReferenceControl

/** Funding is an explicit ordered fact. Retry order is the order of retryTradeIds. */
data class ReferenceFunding(
    override val version: Int = 1,
    override val controlSequence: Long,
    override val controlId: String,
    val account: ReferenceAccountKey,
    val amount: BigDecimal,
    val retryTradeIds: List<String>
) : ReferenceControl

sealed interface ReferenceStep {
    data class Source(val window: ReferenceSourceWindow) : ReferenceStep
    data class Control(val input: ReferenceControl) : ReferenceStep
}

data class ReferenceSourcePosition(
    val stream: ReferenceStreamPartition,
    val streamSequence: Long,
    val effectOrdinal: Int
)

data class ReferenceObligation(
    val tradeId: String,
    val eventId: String,
    val position: ReferenceSourcePosition,
    val sourceMemberDigest: String,
    val runId: String,
    val venueSessionId: String,
    val buyer: ReferenceAccountKey,
    val seller: ReferenceAccountKey,
    val instrumentId: String,
    val currency: String,
    val quantity: BigDecimal,
    val cash: BigDecimal,
    val occurredAt: Instant,
    val policyControlId: String,
    val policyVersion: Int,
    val mode: String
)

enum class ReferenceResultKind { SETTLED, BREAK }
enum class ReferenceBreakReason { CASH_LEG_FAILED, SECURITY_LEG_FAILED }
enum class ReferenceDirection { DEBIT, CREDIT }
enum class ReferenceLegKind { BUYER_CASH_DEBIT, SELLER_CASH_CREDIT, SELLER_SECURITY_DEBIT, BUYER_SECURITY_CREDIT }

data class ReferenceEffect(
    val kind: ReferenceLegKind,
    val account: ReferenceAccountKey,
    val direction: ReferenceDirection,
    val amount: BigDecimal
)

data class ReferenceWorkflowEvent(
    val id: String,
    val kind: String,
    val occurredAt: String,
    val state: String? = null
)

data class ReferenceResult(
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
    val balancesBefore: Map<ReferenceAccountKey, BigDecimal>,
    val balancesAfter: Map<ReferenceAccountKey, BigDecimal>
)

data class ReferenceEvaluation(
    val sourceMemberDigests: Map<ReferenceSourcePosition, String>,
    val coverageDigests: Map<ReferenceSourceWindowKey, String>,
    val controlDigests: Map<String, String>,
    val results: List<ReferenceResult>,
    val outstanding: Map<String, ReferenceObligation>,
    val balances: Map<ReferenceAccountKey, BigDecimal>
)

data class ReferenceSourceWindowKey(
    val stream: ReferenceStreamPartition,
    val fromExclusiveSequence: Long,
    val throughInclusiveSequence: Long
)

/**
 * Independent settlement reference: no SQL, worker clock, mutable policy table, or posting store.
 * Step order is the proposed journal arbitration order; PMJ-02 must persist that order before exposure.
 */
class ReferenceSettlementInterpreter(
    private val coverageVerifier: ReferenceCoverageProofVerifier,
    private val controlVerifier: ReferenceControlProofVerifier
) {
    private val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()
    private val decoder = CanonicalEffectDecoder()

    fun evaluate(steps: List<ReferenceStep>): ReferenceEvaluation {
        val sourceMembers = linkedMapOf<ReferenceSourcePosition, String>()
        val windows = linkedMapOf<ReferenceSourceWindowKey, String>()
        val frontiers = mutableMapOf<ReferenceStreamPartition, Long>()
        val partitionGenerations = mutableMapOf<Pair<String, Int>, String>()
        val controls = linkedMapOf<String, String>()
        val controlSequences = mutableMapOf<Long, String>()
        val policies = mutableMapOf<Pair<String, String>, ReferencePolicyActivation>()
        val orders = mutableMapOf<String, com.reef.platform.application.postmatch.CanonicalOrderIdentity>()
        val openingIds = mutableMapOf<ReferenceAccountKey, String>()
        val fundingIds = mutableMapOf<ReferenceAccountKey, MutableList<String>>()
        val openingKeys = mutableSetOf<ReferenceAccountKey>()
        val balances = linkedMapOf<ReferenceAccountKey, BigDecimal>()
        val obligations = linkedMapOf<String, ReferenceObligation>()
        val outstanding = linkedMapOf<String, ReferenceObligation>()
        val attempts = mutableMapOf<String, Int>()
        val results = mutableListOf<ReferenceResult>()
        // Proof control stream starts at 1 and never skips an ordered input.
        var lastControlSequence = 0L

        fun controlDigest(): String = digest(controls.entries.flatMap { listOf(it.key, it.value) })

        fun attempt(obligation: ReferenceObligation) {
            check(outstanding.containsKey(obligation.tradeId)) { "settlement retry has no outstanding obligation" }
            val number = (attempts[obligation.tradeId] ?: 0) + 1
            val effects = effects(obligation)
            val cashAvailable = (balances[effects[0].account] ?: BigDecimal.ZERO) >= obligation.cash
            val securityAvailable = (balances[effects[2].account] ?: BigDecimal.ZERO) >= obligation.quantity
            val settled = cashAvailable && securityAvailable
            val reason = when {
                !cashAvailable -> ReferenceBreakReason.CASH_LEG_FAILED
                !securityAvailable -> ReferenceBreakReason.SECURITY_LEG_FAILED
                else -> null
            }
            val before = balances.toMap()
            if (settled) effects.forEach { effect ->
                val prior = balances[effect.account] ?: BigDecimal.ZERO
                balances[effect.account] = if (effect.direction == ReferenceDirection.DEBIT) prior - effect.amount
                    else prior + effect.amount
            }
            val workflow = workflow(obligation, number, cashAvailable, securityAvailable)
            val affected = effects.map { it.account }.distinct()
            results += ReferenceResult(
                obligation.tradeId, number, obligation, obligation.position, obligation.sourceMemberDigest,
                obligation.policyControlId,
                affected.mapNotNull(openingIds::get).distinct(),
                affected.flatMap { fundingIds[it].orEmpty() }.distinct(),
                controlDigest(),
                if (settled) ReferenceResultKind.SETTLED else ReferenceResultKind.BREAK,
                reason, workflow, workflowJson(workflow), if (settled) effects else emptyList(),
                before, balances.toMap()
            )
            attempts[obligation.tradeId] = number
            if (settled) outstanding.remove(obligation.tradeId)
        }

        for (step in steps) when (step) {
            is ReferenceStep.Control -> {
                val input = step.input
                require(input.version == 1 && input.controlSequence > 0 && input.controlId.isNotBlank()) {
                    "unsupported or incomplete control identity"
                }
                val memberDigest = controlMemberDigest(input)
                check(controlVerifier.verify(input, memberDigest)) { "control proof failed" }
                val earlier = controls[input.controlId]
                if (earlier != null) {
                    check(earlier == memberDigest && controlSequences[input.controlSequence] == input.controlId) {
                        "changed control input"
                    }
                    continue
                }
                check(input.controlSequence == lastControlSequence + 1 &&
                    controlSequences.putIfAbsent(input.controlSequence, input.controlId) == null) {
                    "control order changed or has a gap"
                }
                lastControlSequence = input.controlSequence
                controls[input.controlId] = memberDigest
                when (input) {
                    is ReferencePolicyActivation -> {
                        require(input.runId.isNotBlank() && input.venueSessionId.isNotBlank() &&
                            input.profileId.isNotBlank() && input.policyVersion > 0 &&
                            input.mode in setOf("instant-post-trade", "ops-realistic")) {
                            "invalid policy activation"
                        }
                        check(input.effectiveAfterSourceFrontiers.all { (stream, sequence) ->
                            stream.eventStream.isNotBlank() && stream.sourceGeneration.isNotBlank() &&
                                stream.partitionId in 0..32767 &&
                                sequence >= CanonicalStreamPosition.origin(stream.partitionId) &&
                                sequence <= CanonicalStreamPosition.origin(stream.partitionId) +
                                    ((1L shl 48) - 1) &&
                                (partitionGenerations[stream.eventStream to stream.partitionId]
                                    ?.let { it == stream.sourceGeneration } ?: true) &&
                                (frontiers[stream] ?: CanonicalStreamPosition.origin(stream.partitionId)) >= sequence
                        }) { "policy activated before source frontier" }
                        policies[input.runId to input.venueSessionId] = input
                    }
                    is ReferenceOpening -> {
                        require(input.amount >= BigDecimal.ZERO) { "opening amount must be nonnegative" }
                        check(openingKeys.add(input.account) && input.account !in balances) {
                            "opening account repeated or already affected"
                        }
                        openingIds[input.account] = input.controlId
                        balances[input.account] = input.amount
                    }
                    is ReferenceFunding -> {
                        require(input.amount > BigDecimal.ZERO && input.retryTradeIds.distinct() == input.retryTradeIds) {
                            "funding amount or retry list is invalid"
                        }
                        check(openingKeys.contains(input.account)) { "funding account has no opening authority" }
                        balances[input.account] = balances.getValue(input.account) + input.amount
                        fundingIds.getOrPut(input.account) { mutableListOf() }.add(input.controlId)
                        input.retryTradeIds.forEach { tradeId ->
                            val obligation = outstanding[tradeId]
                                ?: error("funding retry references settled or unknown trade")
                            check(input.account == obligation.buyer || input.account == obligation.seller) {
                                "funding retry does not affect trade debit resources"
                            }
                            attempt(obligation)
                        }
                    }
                }
            }
            is ReferenceStep.Source -> {
                val window = step.window
                require(window.version == 1 && window.evidence.version == 1 &&
                    window.stream.eventStream.isNotBlank() && window.stream.sourceGeneration.isNotBlank() &&
                    window.stream.partitionId in 0..32767 &&
                    window.fromExclusiveSequence >= CanonicalStreamPosition.origin(window.stream.partitionId) &&
                    window.throughInclusiveSequence > window.fromExclusiveSequence &&
                    window.throughInclusiveSequence <=
                        CanonicalStreamPosition.origin(window.stream.partitionId) + ((1L shl 48) - 1) &&
                    window.throughInclusiveSequence - window.fromExclusiveSequence <= 5000 &&
                    window.evidence.proofId.isNotBlank() && window.evidence.digest.isNotBlank()) {
                    "unsupported or incomplete source window"
                }
                val partition = window.stream.eventStream to window.stream.partitionId
                val generation = partitionGenerations.putIfAbsent(partition, window.stream.sourceGeneration)
                check(generation == null || generation == window.stream.sourceGeneration) {
                    "source generation changed"
                }
                val memberDigests = window.outcomes.mapIndexed { index, source ->
                    require(source.eventStream == window.stream.eventStream &&
                        source.partitionId == window.stream.partitionId &&
                        source.streamSequence == window.fromExclusiveSequence + index + 1L) {
                        "source position gap, duplicate, or identity conflict"
                    }
                    sourceMemberDigest(source)
                }
                require(window.outcomes.isEmpty() ||
                    window.outcomes.size.toLong() == window.throughInclusiveSequence - window.fromExclusiveSequence) {
                    "source window has missing member"
                }
                val coverageDigest = digest(listOf(
                    "reef.reference.source-coverage.v1", window.stream.eventStream,
                    window.stream.sourceGeneration, window.stream.partitionId.toString(),
                    window.fromExclusiveSequence.toString(), window.throughInclusiveSequence.toString(),
                    window.evidence.proofId
                ) + memberDigests)
                check(window.evidence.digest == coverageDigest &&
                    coverageVerifier.verify(window, coverageDigest)) {
                    "source coverage proof failed"
                }
                val key = ReferenceSourceWindowKey(window.stream,
                    window.fromExclusiveSequence, window.throughInclusiveSequence)
                val previous = windows[key]
                if (previous != null) {
                    check(previous == coverageDigest) { "changed source window" }
                    continue
                }
                check(window.fromExclusiveSequence == (frontiers[window.stream]
                    ?: CanonicalStreamPosition.origin(window.stream.partitionId))) {
                    "source frontier gap or overlap"
                }
                window.outcomes.forEachIndexed { index, source ->
                    val sourceDigest = memberDigests[index]
                    decoder.decode(source).forEach { envelope ->
                        val position = ReferenceSourcePosition(window.stream, source.streamSequence,
                            envelope.position.effectOrdinal)
                        check(sourceMembers.putIfAbsent(position, sourceDigest) == null) {
                            "duplicate source effect"
                        }
                        when (val effect = envelope.effect) {
                            is CanonicalEffect.Accepted -> effect.newOrder?.let { identity ->
                                check(orders.putIfAbsent(identity.orderId, identity) == null) {
                                    "order identity changed or repeated"
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
                                val policy = policies[buyer.runId to buyer.venueSessionId]
                                    ?: error("missing immutable policy activation")
                                val quantity = positiveDecimal(effect.quantityUnits)
                                val cash = positiveDecimal(effect.price).multiply(quantity)
                                val obligation = ReferenceObligation(
                                    effect.tradeId, effect.eventId, position, sourceDigest,
                                    buyer.runId, buyer.venueSessionId,
                                    ReferenceAccountKey(buyer.runId, buyer.participantId, buyer.accountId,
                                        "CASH", effect.currency),
                                    ReferenceAccountKey(seller.runId, seller.participantId, seller.accountId,
                                        "SECURITY", effect.instrumentId),
                                    effect.instrumentId, effect.currency, quantity, cash,
                                    Instant.parse(effect.occurredAt), policy.controlId,
                                    policy.policyVersion, policy.mode
                                )
                                check(openingKeys.contains(obligation.buyer) &&
                                    openingKeys.contains(obligation.seller)) {
                                    "trade debit resources lack immutable opening input"
                                }
                                check(obligations.putIfAbsent(effect.tradeId, obligation) == null) {
                                    "trade identity repeated"
                                }
                                outstanding[effect.tradeId] = obligation
                                if (policy.mode == "instant-post-trade") attempt(obligation)
                            }
                            else -> Unit
                        }
                    }
                }
                windows[key] = coverageDigest
                frontiers[window.stream] = window.throughInclusiveSequence
            }
        }
        return ReferenceEvaluation(sourceMembers.toMap(), windows.toMap(), controls.toMap(),
            results.toList(), outstanding.toMap(), balances.toMap())
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
        val events = prefix.map { ReferenceWorkflowEvent("${o.tradeId}:$it:$attempt", it, at) }
        return events + listOf(
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

    private fun sourceMemberDigest(source: CanonicalOutcomeSource): String {
        // Reader supplies retained JSONB::text. Bind those exact UTF-8 bytes, not transport bytes.
        require(mapper.readTree(source.resultPayloadJson)?.isObject == true) {
            "retained canonical result must be a JSON object"
        }
        return digest(listOf(
            "reef.reference.canonical-source.v1", source.eventStream, source.partitionId.toString(),
            source.streamSequence.toString(), source.batchId, source.commandId, source.commandType,
            source.payloadHash, source.instrumentId, source.orderId, source.resultStatus,
            source.resultPayloadJson
        ))
    }

    private fun controlMemberDigest(control: ReferenceControl): String = digest(when (control) {
        is ReferencePolicyActivation -> listOf(
            "reef.reference.policy.v1", control.controlSequence.toString(), control.controlId,
            control.runId, control.venueSessionId,
            control.effectiveAfterSourceFrontiers.entries.sortedWith(compareBy(
                { it.key.eventStream }, { it.key.sourceGeneration }, { it.key.partitionId }
            )).flatMap { (stream, frontier) ->
                listOf(stream.eventStream, stream.sourceGeneration, stream.partitionId.toString(),
                    frontier.toString())
            }.let { digest(it) },
            control.profileId, control.policyVersion.toString(), control.mode,
            control.settlementCycle, control.nettingMode, control.ledgerPostingMode, control.selectionSource
        )
        is ReferenceOpening -> listOf("reef.reference.opening.v1",
            control.controlSequence.toString(), control.controlId) + accountFields(control.account) +
            control.amount.toPlainString()
        is ReferenceFunding -> listOf("reef.reference.funding.v1",
            control.controlSequence.toString(), control.controlId) + accountFields(control.account) +
            listOf(control.amount.toPlainString()) + control.retryTradeIds
    })

    private fun accountFields(key: ReferenceAccountKey): List<String> =
        listOf(key.runId, key.participantId, key.accountId, key.assetType, key.assetId)

    private fun positiveDecimal(value: String): BigDecimal = BigDecimal(value).also {
        require(it > BigDecimal.ZERO) { "trade quantity and price must be positive" }
    }

    private fun digest(fields: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
