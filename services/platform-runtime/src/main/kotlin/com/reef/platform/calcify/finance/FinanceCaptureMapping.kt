package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleBinding
import com.reef.platform.calcify.FiniteLifecycleContract
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1
import reef.contracts.calcify.v1.LifecycleDispositionV1
import reef.contracts.calcify.v1.LifecycleMemberV1
import reef.contracts.orderexecution.v1.OrderSide

/**
 * S2-A2 deliverable 1: the deterministic, pure capture-to-input mapping. It is versioned by `financeStateVersion`
 * ([VERSION]); changing any rule below requires a new version string, and the goldens in `FinanceCaptureMappingTest` pin it.
 *
 * Rules (mapping `calcify-finite-finance-state-v1`):
 *  1. Only `LIFECYCLE_READY_EXECUTION` members yield ledger inputs. APPLIED, REJECTED and REPLAY members never do: a REPLAY is an
 *     identical duplicate of a first capture and must not create a second obligation (O3 contract).
 *  2. The envelope's ready members are numbered `memberOrdinal` 0.. in envelope order; that ordinal is part of the history key.
 *  3. Execution key = (binding run, binding session, binding instrument, trade `executionId`). Physical provenance is audit data.
 *  4. Action key = ("calcify-finance", "capture", executionId). A byte-identical redelivery is therefore `PRIOR_RESULT`; the same
 *     execution with different economics is `ACTION_CONFLICT`, never silently re-booked.
 *  5. Buyer and seller accounts come from the member's two order dependencies (buy first, sell second, the capture contract),
 *     matched by the trade's buy/sell order ids and then required to be the BUY and SELL parties of the binding with the same
 *     participant. Quantity is the trade's `units`, price is the trade's `nanos`; both must be canonical positive decimal int64.
 *  6. A ready member that violates any check above is [Mapped.Invalid]: the processor records it as an `INVALID_INPUT` history
 *     entry (digest of the member's bytes) instead of stalling or skipping it. The ledger's own validation still applies afterwards.
 */
internal object FinanceCaptureMapping {
    const val VERSION = "calcify-finite-finance-state-v1"
    private const val NAMESPACE = "calcify-finance"
    private const val DOMAIN = "capture"

    sealed interface Mapped {
        val memberOrdinal: Int
        data class Ready(override val memberOrdinal: Int, val input: FinanceInput.Capture) : Mapped
        data class Invalid(override val memberOrdinal: Int, val memberDigest: String, val reason: String) : Mapped
    }

    fun map(envelope: FiniteLifecycleCaptureV1, binding: FiniteLifecycleBinding): List<Mapped> {
        var ordinal = 0
        val out = ArrayList<Mapped>()
        for (member in envelope.membersList) {
            if (member.disposition != LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION) continue
            val at = ordinal++
            out += try { Mapped.Ready(at, mapReady(member, binding)) } catch (e: IllegalArgumentException) {
                Mapped.Invalid(at, FiniteLifecycleContract.sha(member.toByteArray()), e.message ?: "invalid member")
            }
        }
        return out
    }

    private fun mapReady(member: LifecycleMemberV1, binding: FiniteLifecycleBinding): FinanceInput.Capture {
        require(member.hasTrade() && member.trade.hasFact()) { "ready member lacks a trade fact" }
        val trade = member.trade
        val fact = trade.fact
        require(trade.runId == binding.run && fact.instrumentId == binding.instrument) { "trade outside the bound run/instrument" }
        require(fact.price.currency == binding.currency) { "trade currency differs from the bound quote currency" }
        require(member.dependenciesCount == 2) { "ready member must carry exactly the buy and sell dependencies" }
        val buy = member.dependenciesList[0]
        val sell = member.dependenciesList[1]
        require(buy.orderId == fact.buyOrderId && sell.orderId == fact.sellOrderId && buy.orderId != sell.orderId) { "dependencies do not match the trade orders" }
        val buyFact = buy.acceptance.fact
        val sellFact = sell.acceptance.fact
        val buyer = binding.parties.singleOrNull { it.side == OrderSide.ORDER_SIDE_BUY }
        val seller = binding.parties.singleOrNull { it.side == OrderSide.ORDER_SIDE_SELL }
        require(buyer != null && seller != null) { "binding lacks one buyer and one seller party" }
        require(buyFact.accountId == buyer.account && buyFact.participantId == buyer.participant &&
            sellFact.accountId == seller.account && sellFact.participantId == seller.participant) { "dependency accounts differ from the bound parties" }
        val execution = ExecutionKey(binding.run, binding.session, binding.instrument, fact.executionId)
        return FinanceInput.Capture(ActionKey(NAMESPACE, DOMAIN, fact.executionId), execution, AccountId(buyer.account), AccountId(seller.account),
            positive(fact.quantity.units), positive(fact.price.nanos))
    }

    /** Canonical positive decimal int64: digits only, no sign, no leading zero, no whitespace. */
    private fun positive(text: String): Long {
        require(text.isNotEmpty() && text.all { it in '0'..'9' } && (text.length == 1 || text[0] != '0')) { "non-canonical decimal" }
        val value = text.toLongOrNull() ?: throw IllegalArgumentException("decimal exceeds int64")
        require(value > 0) { "amount must be positive" }
        return value
    }
}
