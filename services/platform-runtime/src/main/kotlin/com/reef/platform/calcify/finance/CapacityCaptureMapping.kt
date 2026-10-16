package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import reef.contracts.calcify.finance.v1.FinanceInputV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1
import reef.contracts.calcify.v1.LifecycleDispositionV1
import reef.contracts.calcify.v1.LifecycleMemberV1

/**
 * The capacity profile's capture-to-input mapping, `calcify-capacity-finance-state-v1`. Same rules as the finite mapping ([FinanceCaptureMapping], rules 1 to 4) with
 * the finite pins removed:
 *  - any number of instruments (those of the plan) and of participants: the buyer and seller accounts are the `accountId` of the two acceptance facts the trade depends on
 *    (buy first, sell second). Which accounts may trade is decided by the ledger (an account exists once funded), not by a party list here;
 *  - the trade must be in the plan's run and in the lane the record arrived on (the capture stage writes each lane's trades to that lane's partition), at the plan's
 *    cash currency;
 *  - execution key = (plan run, plan session, trade instrument, `executionId`); action key = ("calcify-finance", "capture", executionId).
 * A ready member that fails any check is [FinanceCaptureMapping.Mapped.Invalid]: counted and never recorded, never a stall.
 */
internal object CapacityCaptureMapping {
    const val VERSION = "calcify-capacity-finance-state-v1"
    private const val NAMESPACE = "calcify-finance"

    fun map(envelope: FiniteLifecycleCaptureV1, plan: CapacityPlan, lane: Int): List<FinanceCaptureMapping.Mapped> {
        var ordinal = 0
        val out = ArrayList<FinanceCaptureMapping.Mapped>()
        for (member in envelope.membersList) {
            if (member.disposition != LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION) continue
            val at = ordinal++
            out += try { FinanceCaptureMapping.Mapped.Ready(at, mapReady(member, plan, lane)) } catch (e: IllegalArgumentException) {
                FinanceCaptureMapping.Mapped.Invalid(at, FiniteLifecycleContract.sha(member.toByteArray()), e.message ?: "invalid member")
            }
        }
        return out
    }

    private fun mapReady(member: LifecycleMemberV1, plan: CapacityPlan, lane: Int): FinanceInput.Capture {
        require(member.hasTrade() && member.trade.hasFact()) { "ready member lacks a trade fact" }
        val trade = member.trade; val fact = trade.fact
        require(trade.runId == plan.run) { "trade outside the plan's run" }
        require(plan.laneOf(fact.instrumentId) == lane) { "instrument is not in this lane" }
        require(fact.price.currency == plan.cashAsset.value) { "trade currency differs from the plan's cash asset" }
        require(member.dependenciesCount == 2) { "ready member must carry exactly the buy and sell dependencies" }
        val buy = member.dependenciesList[0]; val sell = member.dependenciesList[1]
        require(buy.orderId == fact.buyOrderId && sell.orderId == fact.sellOrderId && buy.orderId != sell.orderId) { "dependencies do not match the trade orders" }
        val execution = ExecutionKey(plan.run, plan.session, fact.instrumentId, fact.executionId)
        return FinanceInput.Capture(ActionKey(NAMESPACE, "capture", fact.executionId), execution, AccountId(buy.acceptance.fact.accountId), AccountId(sell.acceptance.fact.accountId),
            positive(fact.quantity.units), positive(fact.price.nanos))
    }

    /** The action key of a combined capture and first settlement (decision mode `capture-settle-v1`): one key, one action leaf per booked trade. */
    fun captureSettleKey(executionId: String) = ActionKey(NAMESPACE, "capture-settle", executionId)

    /** The combined input of a mapped capture (same economics, the first settlement attempt implied). */
    fun captureSettle(c: FinanceInput.Capture) = FinanceInput.CaptureSettle(captureSettleKey(c.execution.executionId), c.execution, c.buyer, c.seller, c.quantity, c.priceNanos)

    /** The first settlement attempt of an execution, decided in the same record as its capture (the instant profile, decision mode `two-step`). */
    fun firstSettle(execution: ExecutionKey): FinanceInput.Settle =
        FinanceInput.Settle(ActionKey(NAMESPACE, "settle", "${execution.executionId}/1"), Authority.OPERATOR, execution, 1)

    internal fun positive(text: String): Long {
        require(text.isNotEmpty() && text.all { it in '0'..'9' } && (text.length == 1 || text[0] != '0')) { "non-canonical decimal" }
        val value = text.toLongOrNull() ?: throw IllegalArgumentException("decimal exceeds int64")
        require(value > 0) { "amount must be positive" }
        return value
    }
}

/**
 * The capacity processor's decoder. CAPTURE records are [FiniteLifecycleCaptureV1] envelopes (schema, binding and content digest checked; a record that fails is
 * [CapacityItem.Bad]); each ready member becomes a Capture and, when the plan auto-settles, the first Settle of that execution (decision mode `two-step`), or one CaptureSettle (`capture-settle-v1`). CONTROL records are [FinanceInputV1]
 * Fund or Settle; a Capture on the control topic or an unparseable body is [CapacityItem.Bad]. The record's partition is its lane.
 *
 * [verifyContentDigest] can be turned off to skip the per-envelope content digest (about 16 microseconds per capture record, see the sharding note section 2); the
 * topic ACL and the transport CRC still apply. It is on by default.
 */
internal class CapacityInputDecoders(private val plan: CapacityPlan, private val captureBindingDigest: String, private val verifyContentDigest: Boolean = true) : CapacityInputDecoder {
    override fun decode(role: String, partition: Int, offset: Long, raw: ByteArray): List<CapacityItem> = if (role == "CAPTURE") capture(partition, raw) else control(raw)

    private fun capture(lane: Int, raw: ByteArray): List<CapacityItem> {
        // hashed only when the record is unusable: the digest names the bad bytes, and every good record would otherwise pay for it
        fun bad() = listOf<CapacityItem>(CapacityItem.Bad(FiniteLifecycleContract.sha(raw)))
        val envelope = try { FiniteLifecycleCaptureV1.parseFrom(raw) } catch (e: com.google.protobuf.InvalidProtocolBufferException) { return bad() }
        if (envelope.schema != FiniteLifecycleContract.CAPTURE_SCHEMA || envelope.finiteBindingDigest != captureBindingDigest) return bad()
        if (verifyContentDigest && envelope.contentDigest != FiniteLifecycleContract.digest(envelope)) return bad()
        val mapped = CapacityCaptureMapping.map(envelope, plan, lane)
        if (mapped.isEmpty()) return listOf(CapacityItem.Skip)
        val out = ArrayList<CapacityItem>(mapped.size * 2)
        for (m in mapped) when (m) {
            is FinanceCaptureMapping.Mapped.Ready ->
                if (plan.decisionMode == DecisionMode.CAPTURE_SETTLE) out += CapacityItem.Run(CapacityCaptureMapping.captureSettle(m.input))
                else {
                    out += CapacityItem.Run(m.input)
                    if (plan.autoSettle) out += CapacityItem.Run(CapacityCaptureMapping.firstSettle(m.input.execution))
                }
            is FinanceCaptureMapping.Mapped.Invalid -> out += CapacityItem.Bad(m.memberDigest)
        }
        return out
    }

    private fun control(raw: ByteArray): List<CapacityItem> = controlItems(raw)

    companion object {
        /** A CONTROL record: Fund or Settle only; a Capture or CaptureSettle body or an unparseable one is Bad. */
        fun controlItems(raw: ByteArray): List<CapacityItem> {
            val input = try { FinanceWire.fromProto(FinanceInputV1.parseFrom(raw)) } catch (e: Exception) { return listOf(CapacityItem.Bad(FiniteLifecycleContract.sha(raw))) }
            return if (input is FinanceInput.Capture || input is FinanceInput.CaptureSettle) listOf(CapacityItem.Bad(FiniteLifecycleContract.sha(raw))) else listOf(CapacityItem.Run(input))
        }
    }
}
