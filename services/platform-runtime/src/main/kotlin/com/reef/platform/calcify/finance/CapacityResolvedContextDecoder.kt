package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import reef.contracts.calcify.v1.MatchContextResolvedV1

/**
 * The capacity profile's decoder for the production upstream record: a [MatchContextResolvedV1] from the Phase 2 resolver (one trade with the acceptance facts of its
 * buy and sell orders), written to the venue source partition, which is the finance lane. Mapping `calcify-capacity-resolved-state-v1`:
 *  - the context must be policy [POLICY_VERSION] and in the plan's run and session, and its instrument must trade in the lane it arrived on (the plan is engine-aligned (stream-command routing),
 *    so a mismatch is a routing fault and the record is counted, never recorded);
 *  - the buyer and seller accounts are the accounts of the two acceptance facts, whose order ids must be the trade's buy and sell order ids;
 *  - quantity is the trade's `units` and price its `nanos`, canonical positive decimals at the plan's cash currency;
 *  - execution key = (plan run, plan session, instrument, `executionId`); action key = ("calcify-finance", "capture", executionId); a redelivered context is therefore a
 *    prior result, and the same execution with different economics a conflict, never re-booked;
 *  - in the instant profile the first settlement attempt of the execution follows the capture in the same record: as a second input (a `Settle` of attempt 1, action key
 *    ("calcify-finance", "settle", "<executionId>/1")) in decision mode `two-step`, or folded into the one input in `capture-settle-v1`, where the input is a
 *    [FinanceInput.CaptureSettle] with action key ("calcify-finance", "capture-settle", executionId) and the trade is one decision. A new domain, so a ledger that holds a trade
 *    booked the two-step way answers a redelivery with `EXECUTION_ALREADY_CAPTURED` (benign), not an action conflict.
 * CONTROL records are Fund or Settle as for the other capture source ([CapacityInputDecoders]).
 */
internal class CapacityResolvedContextDecoder(private val plan: CapacityPlan) : CapacityInputDecoder {
    override fun decode(role: String, partition: Int, offset: Long, raw: ByteArray): List<CapacityItem> {
        if (role != "CAPTURE") return CapacityInputDecoders.controlItems(raw)
        // hashed only when the record is unusable: the digest names the bad bytes, and every good record would otherwise pay for it
        fun bad() = listOf<CapacityItem>(CapacityItem.Bad(FiniteLifecycleContract.sha(raw)))
        val context = try { MatchContextResolvedV1.parseFrom(raw) } catch (e: com.google.protobuf.InvalidProtocolBufferException) { return bad() }
        val input = try { map(context, partition) } catch (e: IllegalArgumentException) { return bad() }
        return when {
            input is FinanceInput.CaptureSettle -> listOf(CapacityItem.Run(input))
            plan.autoSettle -> listOf(CapacityItem.Run(input), CapacityItem.Run(CapacityCaptureMapping.firstSettle((input as FinanceInput.Capture).execution)))
            else -> listOf(CapacityItem.Run(input))
        }
    }

    /** A [FinanceInput.Capture], or in decision mode `capture-settle-v1` the [FinanceInput.CaptureSettle] of the same trade. */
    private fun map(c: MatchContextResolvedV1, lane: Int): FinanceInput {
        require(c.policyVersion == POLICY_VERSION) { "unsupported context policy" }
        require(c.hasTrade() && c.trade.hasFact() && c.hasBuyAcceptedOrder() && c.hasSellAcceptedOrder()) { "context lacks the trade or an acceptance" }
        val fact = c.trade.fact; val buy = c.buyAcceptedOrder.fact; val sell = c.sellAcceptedOrder.fact
        require(c.runId == plan.run && c.trade.runId == plan.run) { "context outside the plan's run" }
        require(buy.venueSessionId == plan.session && sell.venueSessionId == plan.session) { "context outside the plan's session" }
        require(plan.laneOf(fact.instrumentId) == lane) { "instrument is not in this lane" }
        require(buy.instrumentId == fact.instrumentId && sell.instrumentId == fact.instrumentId) { "acceptance for another instrument" }
        require(buy.orderId == fact.buyOrderId && sell.orderId == fact.sellOrderId && buy.orderId != sell.orderId) { "acceptances do not match the trade orders" }
        require(fact.price.currency == plan.cashAsset.value) { "trade currency differs from the plan's cash asset" }
        val execution = ExecutionKey(plan.run, plan.session, fact.instrumentId, fact.executionId)
        val quantity = CapacityCaptureMapping.positive(fact.quantity.units); val price = CapacityCaptureMapping.positive(fact.price.nanos)
        return if (plan.decisionMode == DecisionMode.CAPTURE_SETTLE) FinanceInput.CaptureSettle(CapacityCaptureMapping.captureSettleKey(fact.executionId), execution, AccountId(buy.accountId), AccountId(sell.accountId), quantity, price)
        else FinanceInput.Capture(ActionKey("calcify-finance", "capture", fact.executionId), execution, AccountId(buy.accountId), AccountId(sell.accountId), quantity, price)
    }

    companion object { const val POLICY_VERSION = 1 }
}
