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
    // written even when empty. Everything is written forward into one reusable per-thread buffer (a nested message reserves one byte for its length and shifts only if
    // the length needs more), so no size pass is needed and a record costs its result array. Text outside ASCII is written by CodedOutputStream, the code the generated
    // messages use, so it is encoded identically; ASCII (every digest, nearly every id) is its own UTF-8 and is copied.

    private class ProtoOut {
        var buf = ByteArray(1024)
        var pos = 0
        fun reset() { pos = 0; if (buf.size > (1 shl 20)) buf = ByteArray(1024) }
        private fun ensure(n: Int) { if (pos + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, pos + n)) }
        fun varint(value: Long) { ensure(10); var v = value; while (v and 0x7fL.inv() != 0L) { buf[pos++] = ((v and 0x7f) or 0x80).toByte(); v = v ushr 7 }; buf[pos++] = v.toByte() }
        fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())
        fun string(field: Int, v: String) {
            if (v.isEmpty()) return
            val n = v.length
            var ascii = true
            for (i in 0 until n) if (v[i].code >= 0x80) { ascii = false; break }
            tag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED)
            if (ascii) {
                varint(n.toLong()); ensure(n)
                for (i in 0 until n) buf[pos + i] = v[i].code.toByte()
                pos += n
            } else {
                val encoded = ByteArray(CodedOutputStream.computeStringSizeNoTag(v)); CodedOutputStream.newInstance(encoded).writeStringNoTag(v)
                ensure(encoded.size); System.arraycopy(encoded, 0, buf, pos, encoded.size); pos += encoded.size
            }
        }
        fun int64(field: Int, v: Long) { if (v != 0L) { tag(field, WireFormat.WIRETYPE_VARINT); varint(v) } }
        /** int32 and enum fields: a negative value is a ten byte varint, as protobuf writes it. */
        fun int32(field: Int, v: Int) { if (v != 0) { tag(field, WireFormat.WIRETYPE_VARINT); varint(v.toLong()) } }
        /** Starts a nested message and returns where its length goes; [end] closes it. */
        fun begin(field: Int): Int { tag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED); ensure(1); return pos++ }
        fun end(lengthAt: Int) {
            val length = pos - lengthAt - 1
            if (length < 128) { buf[lengthAt] = length.toByte(); return }
            var extra = 0; var x = length ushr 7; while (x != 0) { extra++; x = x ushr 7 }       // the length varint takes 1 + extra bytes; one is already reserved
            ensure(extra); System.arraycopy(buf, lengthAt + 1, buf, lengthAt + 1 + extra, length); pos += extra
            var at = lengthAt; var v = length
            while (v and 0x7f.inv() != 0) { buf[at++] = ((v and 0x7f) or 0x80).toByte(); v = v ushr 7 }
            buf[at] = v.toByte()
        }
        fun copy(): ByteArray = buf.copyOf(pos)
    }
    private val scratch = ThreadLocal.withInitial { ProtoOut() }

    private fun writeAction(out: ProtoOut, field: Int, k: ActionKey) { val at = out.begin(field); out.string(1, k.namespace); out.string(2, k.domain); out.string(3, k.actionId); out.end(at) }
    private fun writeExecution(out: ProtoOut, field: Int, k: ExecutionKey) {
        val at = out.begin(field); out.string(1, k.runId); out.string(2, k.venueSessionId); out.string(3, k.instrumentId); out.string(4, k.executionId); out.end(at)
    }
    private fun authorityNumber(a: Authority) = if (a == Authority.OPERATOR) FinanceAuthorityV1.FINANCE_AUTHORITY_V1_OPERATOR.number else FinanceAuthorityV1.FINANCE_AUTHORITY_V1_TRADING.number
    private val DISPOSITION_NUMBERS = IntArray(Disposition.entries.size).also { n -> for (d in Disposition.entries) n[d.ordinal] = toProto(d).number }

    private fun writeInput(out: ProtoOut, i: FinanceInput) {
        writeAction(out, 1, i.action)
        when (i) {
            is FinanceInput.Capture -> { val at = out.begin(2); writeExecution(out, 1, i.execution); out.string(2, i.buyer.value); out.string(3, i.seller.value); out.int64(4, i.quantity); out.int64(5, i.priceNanos); out.end(at) }
            is FinanceInput.Fund -> { val at = out.begin(3); out.int32(1, authorityNumber(i.authority)); out.string(2, i.account.value); out.string(3, i.asset.value); out.int64(4, i.amount); out.end(at) }
            is FinanceInput.Settle -> { val at = out.begin(4); writeExecution(out, 1, i.execution); out.int32(2, i.attempt); out.int32(3, authorityNumber(i.authority)); out.end(at) }
        }
    }
    private fun writeDecision(out: ProtoOut, d: Decision) {
        out.int64(1, d.sequence); writeAction(out, 2, d.action); out.string(3, d.inputDigest); out.int32(4, DISPOSITION_NUMBERS[d.disposition.ordinal])
        for (l in d.legs) { val at = out.begin(5); out.string(1, l.account.value); out.string(2, l.asset.value); out.int64(3, l.amount); out.end(at) }
        out.string(6, d.priorChain); out.string(7, d.checksum); out.string(8, d.stateDigest)
    }
    private inline fun <T> encoded(write: (ProtoOut) -> Unit, result: (ProtoOut) -> T): T {
        val out = scratch.get(); out.reset()
        try { write(out); return result(out) } finally { out.reset() }
    }

    /** `toProto(i).toByteArray()`. */
    fun encode(i: FinanceInput): ByteArray = encoded({ writeInput(it, i) }) { it.copy() }
    /** `toProto(d).toByteArray()`: the decision without its input (the head record of the store). */
    fun encode(d: Decision): ByteArray = encoded({ writeDecision(it, d) }) { it.copy() }
    /**
     * The decision record from the decision's own bytes ([head], `encode(d)`): the input is field 9, the last field, so the published record is exactly the head record
     * followed by the input field. A lane that stores the head record and publishes the record encodes the decision once.
     */
    fun withInput(head: ByteArray, input: FinanceInput): ByteArray = encoded({ val at = it.begin(9); writeInput(it, input); it.end(at) }) { out ->
        val bytes = ByteArray(head.size + out.pos); System.arraycopy(head, 0, bytes, 0, head.size); System.arraycopy(out.buf, 0, bytes, head.size, out.pos); bytes
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
