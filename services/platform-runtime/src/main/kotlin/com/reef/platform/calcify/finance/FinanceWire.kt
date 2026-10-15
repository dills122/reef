package com.reef.platform.calcify.finance

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
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
    private const val MAX_LEGS = 4   // a settlement is the widest decision: four legs
    fun toProto(i: FinanceInput): FinanceInputV1 {
        val b = FinanceInputV1.newBuilder().setAction(toProto(i.action))
        when (i) {
            is FinanceInput.Capture -> b.setCapture(FinanceCaptureV1.newBuilder().setExecution(toProto(i.execution))
                .setBuyerAccountId(i.buyer.value).setSellerAccountId(i.seller.value).setQuantity(i.quantity).setPriceNanos(i.priceNanos))
            is FinanceInput.Fund -> b.setFund(FinanceFundV1.newBuilder().setAuthority(toProto(i.authority))
                .setAccountId(i.account.value).setAssetId(i.asset.value).setAmount(i.amount))
            is FinanceInput.Settle -> b.setSettle(FinanceSettleV1.newBuilder().setAuthority(toProto(i.authority)).setExecution(toProto(i.execution)).setAttempt(i.attempt))
        }
        return b.build()
    }

    /**
     * Several byte strings must not decode to one value: unknown fields are refused at every nesting level. Written against the known message shapes instead of
     * reflecting over `allFields` (which builds a sorted map of every populated field of every message, per record): an unset message field reads as the default
     * instance, whose unknown field set is empty, so checking every nested message the schema can hold is the same test. Differential-tested against the reflective form.
     */
    private fun noUnknownFields(p: FinanceInputV1) {
        require(p.unknownFields.isEmpty && p.action.unknownFields.isEmpty) { "unknown protobuf fields" }
        val ok = when (p.bodyCase) {
            FinanceInputV1.BodyCase.CAPTURE -> p.capture.let { it.unknownFields.isEmpty && it.execution.unknownFields.isEmpty }
            FinanceInputV1.BodyCase.FUND -> p.fund.unknownFields.isEmpty
            FinanceInputV1.BodyCase.SETTLE -> p.settle.let { it.unknownFields.isEmpty && it.execution.unknownFields.isEmpty }
            else -> true
        }
        require(ok) { "unknown protobuf fields" }
    }
    private fun noUnknownFields(p: FinanceDecisionV1) {
        require(p.unknownFields.isEmpty && p.action.unknownFields.isEmpty) { "unknown protobuf fields" }
        for (leg in p.legsList) require(leg.unknownFields.isEmpty) { "unknown protobuf fields" }
        if (p.hasInput()) noUnknownFields(p.input)
    }
    private val HEX_CHAR = BooleanArray(128).also { t -> for (c in '0'..'9') t[c.code] = true; for (c in 'a'..'f') t[c.code] = true }
    /** `[0-9a-f]{64}`, one table lookup per character (the regex it replaced cost about 650 ns per digest, four digests per decision). */
    private fun isHex64(s: String): Boolean {
        if (s.length != 64) return false
        var ok = true
        for (i in 0 until 64) { val c = s[i].code; ok = ok and (c < 128 && HEX_CHAR[c]) }
        return ok
    }

    fun fromProto(p: FinanceInputV1): FinanceInput {
        noUnknownFields(p)
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
                FinanceInput.Settle(action, fromProto(it.authority), fromProto(it.execution), it.attempt)
            }
            else -> throw IllegalArgumentException("missing input body")
        }
    }

    fun toProto(d: Decision): FinanceDecisionV1 = FinanceDecisionV1.newBuilder()
        .setSequence(d.sequence).setAction(toProto(d.action)).setInputDigest(d.inputDigest).setDisposition(toProto(d.disposition))
        .addAllLegs(d.legs.map { FinanceLegV1.newBuilder().setAccountId(it.account.value).setAssetId(it.asset.value).setAmount(it.amount).build() })
        .setPriorChain(d.priorChain).setStateDigest(d.stateDigest).setChecksum(d.checksum).build()

    /** A decision record as published on FINANCE_DECISIONS: the decision plus the input it answers. */
    fun toProto(d: Decision, input: FinanceInput): FinanceDecisionV1 {
        require(input.digest() == d.inputDigest) { "input does not match the decision's input digest" }
        return toProto(d).toBuilder().setInput(toProto(input)).build()
    }

    // ---- direct encoders: the same bytes as `toProto(...).toByteArray()` without building the message objects (differential-tested on random values) ----
    //
    // protobuf-java writes fields in field-number order and omits proto3 scalars at their default (empty string, zero number, enum 0); a message field that is set is
    // written even when empty. Strings go through CodedOutputStream, the same code the generated messages use, so text is encoded identically.

    private fun strSize(field: Int, v: String) = if (v.isEmpty()) 0 else CodedOutputStream.computeStringSize(field, v)
    private fun strWrite(out: CodedOutputStream, field: Int, v: String) { if (v.isNotEmpty()) out.writeString(field, v) }
    private fun longSize(field: Int, v: Long) = if (v == 0L) 0 else CodedOutputStream.computeInt64Size(field, v)
    private fun longWrite(out: CodedOutputStream, field: Int, v: Long) { if (v != 0L) out.writeInt64(field, v) }
    private fun enumSize(field: Int, number: Int) = if (number == 0) 0 else CodedOutputStream.computeEnumSize(field, number)
    private fun enumWrite(out: CodedOutputStream, field: Int, number: Int) { if (number != 0) out.writeEnum(field, number) }
    private fun msgSize(field: Int, inner: Int) = CodedOutputStream.computeTagSize(field) + CodedOutputStream.computeUInt32SizeNoTag(inner) + inner
    private fun msgHeader(out: CodedOutputStream, field: Int, inner: Int) { out.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED); out.writeUInt32NoTag(inner) }

    private fun actionSize(k: ActionKey) = strSize(1, k.namespace) + strSize(2, k.domain) + strSize(3, k.actionId)
    private fun actionWrite(out: CodedOutputStream, field: Int, k: ActionKey) {
        msgHeader(out, field, actionSize(k)); strWrite(out, 1, k.namespace); strWrite(out, 2, k.domain); strWrite(out, 3, k.actionId)
    }
    private fun executionSize(k: ExecutionKey) = strSize(1, k.runId) + strSize(2, k.venueSessionId) + strSize(3, k.instrumentId) + strSize(4, k.executionId)
    private fun executionWrite(out: CodedOutputStream, field: Int, k: ExecutionKey) {
        msgHeader(out, field, executionSize(k)); strWrite(out, 1, k.runId); strWrite(out, 2, k.venueSessionId); strWrite(out, 3, k.instrumentId); strWrite(out, 4, k.executionId)
    }
    private fun authorityNumber(a: Authority) = if (a == Authority.OPERATOR) FinanceAuthorityV1.FINANCE_AUTHORITY_V1_OPERATOR.number else FinanceAuthorityV1.FINANCE_AUTHORITY_V1_TRADING.number
    private val DISPOSITION_NUMBERS = IntArray(Disposition.entries.size).also { n -> for (d in Disposition.entries) n[d.ordinal] = toProto(d).number }

    private fun captureSize(i: FinanceInput.Capture) = msgSize(1, executionSize(i.execution)) + strSize(2, i.buyer.value) + strSize(3, i.seller.value) + longSize(4, i.quantity) + longSize(5, i.priceNanos)
    private fun fundSize(i: FinanceInput.Fund) = enumSize(1, authorityNumber(i.authority)) + strSize(2, i.account.value) + strSize(3, i.asset.value) + longSize(4, i.amount)
    private fun settleSize(i: FinanceInput.Settle) = msgSize(1, executionSize(i.execution)) + (if (i.attempt == 0) 0 else CodedOutputStream.computeInt32Size(2, i.attempt)) + enumSize(3, authorityNumber(i.authority))
    private fun inputSize(i: FinanceInput): Int = msgSize(1, actionSize(i.action)) + when (i) {
        is FinanceInput.Capture -> msgSize(2, captureSize(i))
        is FinanceInput.Fund -> msgSize(3, fundSize(i))
        is FinanceInput.Settle -> msgSize(4, settleSize(i))
    }
    private fun inputWrite(out: CodedOutputStream, i: FinanceInput) {
        actionWrite(out, 1, i.action)
        when (i) {
            is FinanceInput.Capture -> { msgHeader(out, 2, captureSize(i)); executionWrite(out, 1, i.execution); strWrite(out, 2, i.buyer.value); strWrite(out, 3, i.seller.value); longWrite(out, 4, i.quantity); longWrite(out, 5, i.priceNanos) }
            is FinanceInput.Fund -> { msgHeader(out, 3, fundSize(i)); enumWrite(out, 1, authorityNumber(i.authority)); strWrite(out, 2, i.account.value); strWrite(out, 3, i.asset.value); longWrite(out, 4, i.amount) }
            is FinanceInput.Settle -> { msgHeader(out, 4, settleSize(i)); executionWrite(out, 1, i.execution); if (i.attempt != 0) out.writeInt32(2, i.attempt); enumWrite(out, 3, authorityNumber(i.authority)) }
        }
    }
    private fun legSize(l: Leg) = strSize(1, l.account.value) + strSize(2, l.asset.value) + longSize(3, l.amount)
    private fun decisionSize(d: Decision): Int {
        var n = longSize(1, d.sequence) + msgSize(2, actionSize(d.action)) + strSize(3, d.inputDigest) + enumSize(4, DISPOSITION_NUMBERS[d.disposition.ordinal])
        for (l in d.legs) n += msgSize(5, legSize(l))
        return n + strSize(6, d.priorChain) + strSize(7, d.checksum) + strSize(8, d.stateDigest)
    }
    private fun decisionWrite(out: CodedOutputStream, d: Decision) {
        longWrite(out, 1, d.sequence); actionWrite(out, 2, d.action); strWrite(out, 3, d.inputDigest); enumWrite(out, 4, DISPOSITION_NUMBERS[d.disposition.ordinal])
        for (l in d.legs) { msgHeader(out, 5, legSize(l)); strWrite(out, 1, l.account.value); strWrite(out, 2, l.asset.value); longWrite(out, 3, l.amount) }
        strWrite(out, 6, d.priorChain); strWrite(out, 7, d.checksum); strWrite(out, 8, d.stateDigest)
    }

    /** `toProto(i).toByteArray()`. */
    fun encode(i: FinanceInput): ByteArray { val bytes = ByteArray(inputSize(i)); val out = CodedOutputStream.newInstance(bytes); inputWrite(out, i); out.checkNoSpaceLeft(); return bytes }
    /** `toProto(d).toByteArray()`: the decision without its input (the head record of the store). */
    fun encode(d: Decision): ByteArray { val bytes = ByteArray(decisionSize(d)); val out = CodedOutputStream.newInstance(bytes); decisionWrite(out, d); out.checkNoSpaceLeft(); return bytes }
    /**
     * `toProto(d, input).toByteArray()` WITHOUT the digest check, for the processor that has just made [d] from [input] (the check would hash the input a second time to
     * learn what the ledger computed a moment ago). Anything that receives a decision and an input from outside must use [toProto] or [recordFromProto].
     */
    fun encodeTrusted(d: Decision, input: FinanceInput): ByteArray = withInput(encode(d), input)

    /**
     * The decision record from the decision's own bytes ([head], `encode(d)`): the input is field 9, the last field, so the published record is exactly the head record
     * followed by the input field. A lane that stores the head record and publishes the record encodes the decision once.
     */
    fun withInput(head: ByteArray, input: FinanceInput): ByteArray {
        val inner = inputSize(input)
        val bytes = head.copyOf(head.size + msgSize(9, inner)); val out = CodedOutputStream.newInstance(bytes, head.size, bytes.size - head.size)
        msgHeader(out, 9, inner); inputWrite(out, input); out.checkNoSpaceLeft(); return bytes
    }

    /** Decodes a published record; the carried input must hash to the decision's input digest. */
    fun recordFromProto(p: FinanceDecisionV1): Pair<Decision, FinanceInput> {
        require(p.hasInput()) { "decision record without input" }
        val decision = fromProto(p)
        val input = fromProto(p.input)
        require(input.digest() == decision.inputDigest) { "input does not match the decision's input digest" }
        return decision to input
    }

    fun fromProto(p: FinanceDecisionV1): Decision {
        noUnknownFields(p)
        require(p.hasAction()) { "missing action" }
        require(p.sequence >= 1) { "non-positive sequence" }
        require(isHex64(p.inputDigest) && isHex64(p.priorChain) && isHex64(p.stateDigest) && isHex64(p.checksum)) { "digest is not lowercase hex SHA-256" }
        require(p.legsCount <= MAX_LEGS) { "too many legs" }
        return Decision(p.sequence, fromProto(p.action), p.inputDigest, fromProto(p.disposition),
            p.legsList.map { Leg(AccountId(it.accountId), AssetId(it.assetId), it.amount) }, p.priorChain, p.stateDigest, p.checksum)
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
