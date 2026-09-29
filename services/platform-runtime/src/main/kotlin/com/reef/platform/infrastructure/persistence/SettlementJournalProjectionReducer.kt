package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import java.math.BigDecimal
import java.util.Base64

data class SettlementProjectionTrade(
    val runId: String,
    val tradeId: String,
    val attemptNumber: Int,
    val outcome: String,
    val breakReason: String?,
    val batchSequence: Long,
    val resultIndex: Int
)

data class SettlementProjectionDelta(
    val balances: Map<ReferenceAccountKey, BigDecimal>,
    val trades: Map<Pair<String, String>, SettlementProjectionTrade>
)

/**
 * Pure ordered reducer. Caller supplies only touched account/trade state from its locked
 * checkpoint transaction. Missing account means no opening fact was projected.
 */
class SettlementJournalProjectionReducer {
    fun reduce(
        batch: SettlementJournalVerifiedBatch,
        startingBalances: Map<ReferenceAccountKey, BigDecimal>,
        startingTrades: Map<Pair<String, String>, SettlementProjectionTrade>
    ): SettlementProjectionDelta {
        val balances = startingBalances.toMutableMap()
        val trades = startingTrades.toMutableMap()
        val controls = batch.controls.associateBy { it.stepIndex }
        val windows = batch.sourceWindows.associateBy { it.stepIndex }
        val results = batch.results.withIndex().groupBy { it.value.decisionStepIndex }
        check(controls.size == batch.controls.size && windows.size == batch.sourceWindows.size &&
            controls.keys.intersect(windows.keys).isEmpty()) { "journal projection step is repeated" }
        val stepCount = controls.size + windows.size
        check((controls.keys + windows.keys).toSet() == (0 until stepCount).toSet()) {
            "journal projection step has gap"
        }
        check(results.keys.all { it in 0 until stepCount }) { "result step is outside batch" }
        for (step in 0 until stepCount) {
            controls[step]?.let { member ->
                val control = SettlementJournalControlCodec.decode(
                    Base64.getDecoder().decode(member.payloadBase64))
                check(control.controlId == member.id &&
                    control.controlSequence == member.sequence && control.version == member.version &&
                    when (control) {
                        is ReferencePolicyActivation -> "POLICY"
                        is ReferenceOpening -> "OPENING"
                        is ReferenceFunding -> "FUNDING"
                    } == member.kind) { "journal projection control identity changed" }
                when (control) {
                    is ReferencePolicyActivation -> Unit
                    is ReferenceOpening -> {
                        check(control.amount >= BigDecimal.ZERO && control.account !in balances) {
                            "journal projection opening repeats account"
                        }
                        balances[control.account] = control.amount
                    }
                    is ReferenceFunding -> {
                        check(control.amount > BigDecimal.ZERO && control.account in balances) {
                            "journal projection funding lacks opened account"
                        }
                        balances[control.account] = balances.getValue(control.account) + control.amount
                    }
                }
            }
            results[step].orEmpty().forEach { (resultIndex, result) ->
                check(result.runId.isNotBlank() && result.tradeId.isNotBlank() &&
                    result.cashAmount > BigDecimal.ZERO && result.quantityUnits > BigDecimal.ZERO) {
                    "journal projection result has invalid identity or amount"
                }
                val tradeKey = result.runId to result.tradeId
                val prior = trades[tradeKey]
                check(result.attemptNumber == (prior?.attemptNumber ?: 0) + 1 &&
                    prior?.outcome != "SETTLED") { "journal projection trade attempt changed" }
                val buyerCash = ReferenceAccountKey(result.runId, result.buyerParticipantId,
                    result.buyerAccountId, "CASH", result.currency)
                val sellerCash = ReferenceAccountKey(result.runId, result.sellerParticipantId,
                    result.sellerAccountId, "CASH", result.currency)
                val sellerSecurity = ReferenceAccountKey(result.runId, result.sellerParticipantId,
                    result.sellerAccountId, "SECURITY", result.instrumentId)
                val buyerSecurity = ReferenceAccountKey(result.runId, result.buyerParticipantId,
                    result.buyerAccountId, "SECURITY", result.instrumentId)
                val cashReady = (balances[buyerCash] ?: BigDecimal.ZERO) >= result.cashAmount
                val securityReady = (balances[sellerSecurity] ?: BigDecimal.ZERO) >= result.quantityUnits
                val expectedBreak = when {
                    !cashReady -> "CASH_LEG_FAILED"
                    !securityReady -> "SECURITY_LEG_FAILED"
                    else -> null
                }
                when (result.outcome) {
                    "SETTLED" -> {
                        check(expectedBreak == null && result.breakReason == null) {
                            "journal projection settlement lacks resources"
                        }
                        listOf(buyerCash to result.cashAmount.negate(),
                            sellerCash to result.cashAmount,
                            sellerSecurity to result.quantityUnits.negate(),
                            buyerSecurity to result.quantityUnits).forEach { (key, change) ->
                            balances[key] = (balances[key] ?: BigDecimal.ZERO) + change
                        }
                    }
                    "BREAK" -> check(result.breakReason == expectedBreak && expectedBreak != null) {
                        "journal projection break differs from resources"
                    }
                    else -> error("journal projection result outcome is unsupported")
                }
                trades[tradeKey] = SettlementProjectionTrade(result.runId, result.tradeId,
                    result.attemptNumber, result.outcome, result.breakReason,
                    batch.batchSequence, resultIndex)
            }
        }
        return SettlementProjectionDelta(balances, trades)
    }
}
