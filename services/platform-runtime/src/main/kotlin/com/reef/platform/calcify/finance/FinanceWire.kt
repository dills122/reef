package com.reef.platform.calcify.finance

import reef.contracts.calcify.finance.v1.FinanceActionKeyV1
import reef.contracts.calcify.finance.v1.FinanceAuthorityV1
import reef.contracts.calcify.finance.v1.FinanceCaptureV1
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.finance.v1.FinanceDispositionV1
import reef.contracts.calcify.finance.v1.FinanceExecutionKeyV1
import reef.contracts.calcify.finance.v1.FinanceFundV1
import reef.contracts.calcify.finance.v1.FinanceInputV1
import reef.contracts.calcify.finance.v1.FinanceLegV1
import reef.contracts.calcify.finance.v1.FinanceSettleV1

/**
 * Strict mapping between the pure O3a model and `calcify_finance.proto`. Decoding refuses unspecified or unknown enums and a
 * missing body; text bounds are enforced by the model constructors, so a decoded value is always a valid model value.
 */
internal object FinanceWire {
    fun toProto(i: FinanceInput): FinanceInputV1 {
        val b = FinanceInputV1.newBuilder().setAction(toProto(i.action))
        when (i) {
            is FinanceInput.Capture -> b.setCapture(FinanceCaptureV1.newBuilder().setExecution(toProto(i.execution))
                .setBuyerAccountId(i.buyer.value).setSellerAccountId(i.seller.value).setQuantity(i.quantity).setPriceNanos(i.priceNanos))
            is FinanceInput.Fund -> b.setFund(FinanceFundV1.newBuilder().setAuthority(toProto(i.authority))
                .setAccountId(i.account.value).setAssetId(i.asset.value).setAmount(i.amount))
            is FinanceInput.Settle -> b.setSettle(FinanceSettleV1.newBuilder().setExecution(toProto(i.execution)).setAttempt(i.attempt))
        }
        return b.build()
    }

    fun fromProto(p: FinanceInputV1): FinanceInput {
        require(p.hasAction()) { "missing action" }
        val action = fromProto(p.action)
        return when (p.bodyCase) {
            FinanceInputV1.BodyCase.CAPTURE -> p.capture.let {
                require(it.hasExecution()) { "missing execution" }
                FinanceInput.Capture(action, fromProto(it.execution), AccountId(it.buyerAccountId), AccountId(it.sellerAccountId), it.quantity, it.priceNanos)
            }
            FinanceInputV1.BodyCase.FUND -> p.fund.let { FinanceInput.Fund(action, fromProto(it.authority), AccountId(it.accountId), AssetId(it.assetId), it.amount) }
            FinanceInputV1.BodyCase.SETTLE -> p.settle.let {
                require(it.hasExecution()) { "missing execution" }
                FinanceInput.Settle(action, fromProto(it.execution), it.attempt)
            }
            else -> throw IllegalArgumentException("missing input body")
        }
    }

    fun toProto(d: Decision): FinanceDecisionV1 = FinanceDecisionV1.newBuilder()
        .setSequence(d.sequence).setAction(toProto(d.action)).setInputDigest(d.inputDigest).setDisposition(toProto(d.disposition))
        .addAllLegs(d.legs.map { FinanceLegV1.newBuilder().setAccountId(it.account.value).setAssetId(it.asset.value).setAmount(it.amount).build() })
        .setPriorChain(d.priorChain).setChecksum(d.checksum).build()

    fun fromProto(p: FinanceDecisionV1): Decision {
        require(p.hasAction()) { "missing action" }
        return Decision(p.sequence, fromProto(p.action), p.inputDigest, fromProto(p.disposition),
            p.legsList.map { Leg(AccountId(it.accountId), AssetId(it.assetId), it.amount) }, p.priorChain, p.checksum)
    }

    private fun toProto(k: ActionKey) = FinanceActionKeyV1.newBuilder().setNamespace(k.namespace).setDomain(k.domain).setActionId(k.actionId).build()
    private fun fromProto(p: FinanceActionKeyV1) = ActionKey(p.namespace, p.domain, p.actionId)
    private fun toProto(k: ExecutionKey) = FinanceExecutionKeyV1.newBuilder().setRunId(k.runId).setVenueSessionId(k.venueSessionId)
        .setInstrumentId(k.instrumentId).setExecutionId(k.executionId).build()
    private fun fromProto(p: FinanceExecutionKeyV1) = ExecutionKey(p.runId, p.venueSessionId, p.instrumentId, p.executionId)

    private fun toProto(a: Authority) = when (a) {
        Authority.OPERATOR -> FinanceAuthorityV1.FINANCE_AUTHORITY_V1_OPERATOR
        Authority.TRADING -> FinanceAuthorityV1.FINANCE_AUTHORITY_V1_TRADING
    }
    private fun fromProto(a: FinanceAuthorityV1) = when (a) {
        FinanceAuthorityV1.FINANCE_AUTHORITY_V1_OPERATOR -> Authority.OPERATOR
        FinanceAuthorityV1.FINANCE_AUTHORITY_V1_TRADING -> Authority.TRADING
        else -> throw IllegalArgumentException("unspecified or unknown authority")
    }

    // Wire names are the model names behind the fixed prefix, so the mapping cannot drift silently (tested exhaustively).
    private const val PREFIX = "FINANCE_DISPOSITION_V1_"
    private fun toProto(d: Disposition): FinanceDispositionV1 = FinanceDispositionV1.valueOf(PREFIX + d.name)
    private fun fromProto(d: FinanceDispositionV1): Disposition {
        require(d != FinanceDispositionV1.FINANCE_DISPOSITION_V1_UNSPECIFIED && d != FinanceDispositionV1.UNRECOGNIZED) { "unspecified or unknown disposition" }
        return Disposition.valueOf(d.name.removePrefix(PREFIX))
    }
}
