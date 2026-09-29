package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOrderIdentity
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceObligation
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceSourcePosition
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementEvaluatorHead
import com.reef.platform.application.settlementjournal.SettlementEvaluatorRecoveryState
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Writer state authenticated by an independently persisted finality snapshot pointer. */
data class SettlementJournalWriterRecoveryState(
    val evaluator: SettlementEvaluatorRecoveryState,
    val activeOrders: Map<String, CanonicalOrderIdentity>,
    val seenOrderIds: Set<String>,
    val sourceBindingDigest: String,
    val controlIncarnationId: String,
    /** Highest retained matching outcome per partition; empty broker coverage may extend beyond it. */
    val lastRetainedSourceFrontiers: Map<ReferenceStreamPartition, Long> = emptyMap()
)

/** Canonical, versioned payload. Decoding alone never grants a writer lease. */
object SettlementJournalWriterRecoveryCodec {
    const val VERSION = 2
    private const val DOMAIN = "reef.settlement.writer-recovery.v2"
    private const val MAX_STATE_BYTES = 512 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_ENTRIES = 5_000_000

    fun encode(state: SettlementJournalWriterRecoveryState): ByteArray {
        require(state.evaluator.head.batchSequence > 0 &&
            state.sourceBindingDigest.matches(Regex("[0-9a-f]{64}")) &&
            state.controlIncarnationId.isNotBlank() &&
            state.activeOrders.all { (id, order) -> id == order.orderId && id in state.seenOrderIds } &&
            state.evaluator.outstanding.keys.all { it.isNotBlank() } &&
            state.activeOrders.size <= MAX_ENTRIES && state.seenOrderIds.size <= MAX_ENTRIES &&
            state.evaluator.outstanding.size <= MAX_ENTRIES) {
            "invalid writer recovery state"
        }
        require(state.evaluator.sourceFrontiers.keys.all {
            it in state.lastRetainedSourceFrontiers
        } && state.lastRetainedSourceFrontiers.all { (stream, retained) ->
            retained >= CanonicalStreamPosition.origin(stream.partitionId) &&
                retained <= (state.evaluator.sourceFrontiers[stream] ?: retained)
        }) { "retained source frontier exceeds checkpoint coverage" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.field(DOMAIN)
            val evaluator = state.evaluator
            out.writeLong(evaluator.head.batchSequence)
            out.field(evaluator.head.batchDigest)
            out.writeLong(evaluator.head.ownerEpoch)
            out.field(evaluator.head.ownerIncarnation)
            out.field(state.sourceBindingDigest)
            out.field(state.controlIncarnationId)
            out.count(evaluator.orderedControls.size)
            evaluator.orderedControls.forEach { (id, digest) ->
                out.field(id); out.field(digest)
            }
            out.count(evaluator.policies.size)
            evaluator.policies.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .forEach { (_, policy) -> out.field(SettlementJournalControlCodec.encode(policy)) }
            out.count(evaluator.openingIds.size)
            evaluator.openingIds.entries.sortedBy { accountKey(it.key) }.forEach { (key, id) ->
                out.account(key); out.field(id)
            }
            out.count(evaluator.fundingIds.size)
            evaluator.fundingIds.entries.sortedBy { accountKey(it.key) }.forEach { (key, ids) ->
                out.account(key); out.count(ids.size); ids.forEach { out.field(it) }
            }
            out.count(evaluator.balances.size)
            evaluator.balances.entries.sortedBy { accountKey(it.key) }.forEach { (key, balance) ->
                out.account(key); out.field(balance.toPlainString())
            }
            out.count(evaluator.outstanding.size)
            evaluator.outstanding.toSortedMap().values.forEach { out.obligation(it) }
            out.count(evaluator.attempts.size)
            evaluator.attempts.toSortedMap().forEach { (id, number) ->
                out.field(id); out.writeInt(number)
            }
            out.count(evaluator.sourceFrontiers.size)
            evaluator.sourceFrontiers.entries.sortedWith(compareBy({ it.key.eventStream },
                { it.key.sourceGeneration }, { it.key.partitionId })).forEach { (key, frontier) ->
                out.stream(key); out.writeLong(frontier)
            }
            out.count(state.lastRetainedSourceFrontiers.size)
            state.lastRetainedSourceFrontiers.entries.sortedWith(compareBy({ it.key.eventStream },
                { it.key.sourceGeneration }, { it.key.partitionId })).forEach { (key, frontier) ->
                out.stream(key); out.writeLong(frontier)
            }
            out.count(state.activeOrders.size)
            state.activeOrders.toSortedMap().values.forEach { out.order(it) }
            out.count(state.seenOrderIds.size)
            state.seenOrderIds.sorted().forEach { out.field(it) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_STATE_BYTES) {
            "writer snapshot exceeds payload bound"
        } }
    }

    fun decode(bytes: ByteArray): SettlementJournalWriterRecoveryState {
        require(bytes.isNotEmpty() && bytes.size <= MAX_STATE_BYTES) {
            "writer snapshot exceeds payload bound"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.field() == DOMAIN) { "unsupported writer snapshot version" }
        val head = SettlementEvaluatorHead(input.readLong(), input.field(), input.readLong(), input.field())
        val binding = input.field()
        val controlIncarnation = input.field()
        val controls = List(input.count()) { input.field() to input.field() }
        val policies = linkedMapOf<Pair<String, String>, ReferencePolicyActivation>()
        repeat(input.count()) {
            val policy = SettlementJournalControlCodec.decode(input.bytes()) as? ReferencePolicyActivation
                ?: error("snapshot policy control changed kind")
            check(policies.putIfAbsent(policy.runId to policy.venueSessionId, policy) == null) {
                "snapshot policy repeated"
            }
        }
        val openings = linkedMapOf<ReferenceAccountKey, String>()
        repeat(input.count()) {
            check(openings.putIfAbsent(input.account(), input.field()) == null) {
                "snapshot opening repeated"
            }
        }
        val funding = linkedMapOf<ReferenceAccountKey, List<String>>()
        repeat(input.count()) {
            val key = input.account()
            val ids = List(input.count()) { input.field() }
            check(funding.putIfAbsent(key, ids) == null) { "snapshot funding account repeated" }
        }
        val balances = linkedMapOf<ReferenceAccountKey, BigDecimal>()
        repeat(input.count()) {
            check(balances.putIfAbsent(input.account(), BigDecimal(input.field())) == null) {
                "snapshot balance account repeated"
            }
        }
        val outstanding = linkedMapOf<String, ReferenceObligation>()
        repeat(input.count()) {
            val obligation = input.obligation()
            check(outstanding.putIfAbsent(obligation.tradeId, obligation) == null) {
                "snapshot obligation repeated"
            }
        }
        val attempts = linkedMapOf<String, Int>()
        repeat(input.count()) {
            check(attempts.putIfAbsent(input.field(), input.readInt()) == null) {
                "snapshot attempt repeated"
            }
        }
        val frontiers = linkedMapOf<ReferenceStreamPartition, Long>()
        repeat(input.count()) {
            check(frontiers.putIfAbsent(input.stream(), input.readLong()) == null) {
                "snapshot source frontier repeated"
            }
        }
        val retained = linkedMapOf<ReferenceStreamPartition, Long>()
        repeat(input.count()) {
            check(retained.putIfAbsent(input.stream(), input.readLong()) == null) {
                "snapshot retained source frontier repeated"
            }
        }
        val active = linkedMapOf<String, CanonicalOrderIdentity>()
        repeat(input.count()) {
            val order = input.order()
            check(active.putIfAbsent(order.orderId, order) == null) { "snapshot active order repeated" }
        }
        val seen = linkedSetOf<String>()
        repeat(input.count()) { check(seen.add(input.field())) { "snapshot seen order repeated" } }
        check(input.available() == 0) { "snapshot has trailing bytes" }
        val result = SettlementJournalWriterRecoveryState(
            SettlementEvaluatorRecoveryState(head, controls, policies, openings, funding,
                balances, outstanding, attempts, frontiers), active, seen, binding,
            controlIncarnation, retained)
        check(encode(result).contentEquals(bytes)) { "writer snapshot is not canonical" }
        return result
    }

    private fun accountKey(key: ReferenceAccountKey): String =
        listOf(key.runId, key.participantId, key.accountId, key.assetType, key.assetId)
            .joinToString("\u0000")

    private fun DataOutputStream.count(value: Int) {
        require(value in 0..MAX_ENTRIES) { "snapshot entry count exceeds bound" }
        writeInt(value)
    }

    private fun DataInputStream.count(): Int = readInt().also {
        check(it in 0..MAX_ENTRIES) { "snapshot entry count exceeds bound" }
    }

    private fun DataOutputStream.field(value: String) = field(value.toByteArray(StandardCharsets.UTF_8))
    private fun DataOutputStream.field(value: ByteArray) {
        require(value.size <= MAX_STRING_BYTES) { "snapshot field exceeds bound" }
        writeInt(value.size); write(value)
    }

    private fun DataInputStream.bytes(): ByteArray {
        val size = readInt()
        check(size in 0..MAX_STRING_BYTES) { "snapshot field exceeds bound" }
        return ByteArray(size).also { readFully(it) }
    }

    private fun DataInputStream.field(): String = String(bytes(), StandardCharsets.UTF_8)

    private fun DataOutputStream.account(key: ReferenceAccountKey) {
        listOf(key.runId, key.participantId, key.accountId, key.assetType, key.assetId)
            .forEach { field(it) }
    }

    private fun DataInputStream.account(): ReferenceAccountKey =
        ReferenceAccountKey(field(), field(), field(), field(), field())

    private fun DataOutputStream.stream(key: ReferenceStreamPartition) {
        field(key.eventStream); field(key.sourceGeneration); writeInt(key.partitionId)
    }

    private fun DataInputStream.stream(): ReferenceStreamPartition =
        ReferenceStreamPartition(field(), field(), readInt())

    private fun DataOutputStream.obligation(value: ReferenceObligation) {
        field(value.tradeId); field(value.eventId); stream(value.position.stream)
        writeLong(value.position.streamSequence); writeInt(value.position.effectOrdinal)
        field(value.sourceMemberDigest); field(value.runId); field(value.venueSessionId)
        account(value.buyer); account(value.seller)
        field(value.instrumentId); field(value.currency)
        field(value.quantity.toPlainString()); field(value.cash.toPlainString())
        field(value.occurredAt.toString()); field(value.policyControlId)
        writeInt(value.policyVersion); field(value.mode)
    }

    private fun DataInputStream.obligation(): ReferenceObligation = ReferenceObligation(
        field(), field(), ReferenceSourcePosition(stream(), readLong(), readInt()),
        field(), field(), field(), account(), account(), field(), field(),
        BigDecimal(field()), BigDecimal(field()), Instant.parse(field()), field(),
        readInt(), field())

    private fun DataOutputStream.order(value: CanonicalOrderIdentity) {
        listOf(value.orderId, value.engineOrderId, value.clientOrderId, value.runId,
            value.venueSessionId, value.instrumentId, value.participantId, value.accountId,
            value.side, value.orderType, value.quantityUnits, value.limitPrice, value.currency,
            value.timeInForce, value.acceptedAt).forEach { field(it) }
    }

    private fun DataInputStream.order(): CanonicalOrderIdentity = CanonicalOrderIdentity(
        field(), field(), field(), field(), field(), field(), field(), field(), field(), field(),
        field(), field(), field(), field(), field())
}
