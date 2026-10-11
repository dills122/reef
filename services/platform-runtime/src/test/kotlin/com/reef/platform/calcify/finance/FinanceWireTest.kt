package com.reef.platform.calcify.finance

import java.io.ByteArrayOutputStream
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.finance.v1.FinanceDispositionV1
import reef.contracts.calcify.finance.v1.FinanceInputV1

class FinanceWireTest {
    // Independent protobuf encoder (varint + length-delimited) so the goldens are not just the generated code agreeing with itself.
    private class Enc {
        val out = ByteArrayOutputStream()
        fun varint(n: Long) { var v = n; while (true) { val b = (v and 0x7f).toInt(); v = v ushr 7; if (v != 0L) out.write(b or 0x80) else { out.write(b); return } } }
        fun tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
        fun str(f: Int, s: String) { if (s.isEmpty()) return; val b = s.toByteArray(Charsets.UTF_8); tag(f, 2); varint(b.size.toLong()); out.write(b) }
        fun msg(f: Int, e: Enc.() -> Unit) { val b = Enc().apply(e).out.toByteArray(); tag(f, 2); varint(b.size.toLong()); out.write(b) }
        fun num(f: Int, n: Long) { if (n == 0L) return; tag(f, 0); varint(n) }
    }
    private fun enc(e: Enc.() -> Unit) = Enc().apply(e).out.toByteArray()
    private fun hex(b: ByteArray) = HexFormat.of().formatHex(b)

    private val act = ActionKey("p3", "control", "open-1")
    private val ek = ExecutionKey("p3-run", "p3-session", "AAPL", "e1")
    private val capture = FinanceInput.Capture(act, ek, AccountId("buyer-account"), AccountId("seller-account"), 3, 100_000_000_000L)
    private val fund = FinanceInput.Fund(act, Authority.OPERATOR, AccountId("buyer-account"), AssetId("USD"), 6)
    private val settle = FinanceInput.Settle(act, ek, 1)
    private val decision = Decision(7, act, "a".repeat(64), Disposition.SETTLED,
        listOf(Leg(AccountId("buyer-account"), AssetId("USD"), -300_000_000_000L), Leg(AccountId("seller-account"), AssetId("AAPL"), -3)), "b".repeat(64), "c".repeat(64))

    // Goldens produced by a separate Python varint encoder; they pin field numbers and wire types.
    private val goldenCapture = "0a150a0270331207636f6e74726f6c1a066f70656e2d3112480a1e0a0670332d72756e120a70332d73657373696f6e1a044141504c22026531120d62757965722d6163636f756e741a0e73656c6c65722d6163636f756e7420032880d0dbc3f402"
    private val goldenFund = "0a150a0270331207636f6e74726f6c1a066f70656e2d311a180801120d62757965722d6163636f756e741a035553442006"
    private val goldenSettle = "0a150a0270331207636f6e74726f6c1a066f70656e2d3122220a1e0a0670332d72756e120a70332d73657373696f6e1a044141504c220265311001"
    private val goldenDecision = "080712150a0270331207636f6e74726f6c1a066f70656e2d311a40" + "61".repeat(64) +
        "20052a1f0a0d62757965722d6163636f756e741203555344188090edb4a2f7ffffff012a210a0e73656c6c65722d6163636f756e7412044141504c18fdffffffffffffffff013240" +
        "62".repeat(64) + "3a40" + "63".repeat(64)

    @Test fun inputsMatchTheGoldenBytesAndRoundTrip() {
        for ((input, golden) in listOf(capture to goldenCapture, fund to goldenFund, settle to goldenSettle)) {
            val bytes = FinanceWire.toProto(input).toByteArray()
            assertEquals(golden, hex(bytes), "golden for ${input::class.simpleName}")
            assertEquals(input, FinanceWire.fromProto(FinanceInputV1.parseFrom(bytes)))
        }
    }

    @Test fun decisionMatchesTheGoldenBytesAndRoundTrips() {
        val bytes = FinanceWire.toProto(decision).toByteArray()
        assertEquals(goldenDecision, hex(bytes))
        assertEquals(decision, FinanceWire.fromProto(FinanceDecisionV1.parseFrom(bytes)))
    }

    @Test fun independentEncoderAgreesWithGeneratedCodeForTheCapture() {
        val bytes = enc {
            msg(1) { str(1, "p3"); str(2, "control"); str(3, "open-1") }
            msg(2) {
                msg(1) { str(1, "p3-run"); str(2, "p3-session"); str(3, "AAPL"); str(4, "e1") }
                str(2, "buyer-account"); str(3, "seller-account"); num(4, 3); num(5, 100_000_000_000L)
            }
        }
        assertEquals(hex(FinanceWire.toProto(capture).toByteArray()), hex(bytes))
    }

    @Test fun everyDispositionMapsBothWays() {
        for (d in Disposition.entries) {
            val action = ActionKey("p3", "x", "y")
            val back = FinanceWire.fromProto(FinanceWire.toProto(Decision(1, action, "d", d.takeUnless { it.neverRecorded } ?: Disposition.CAPTURED, emptyList(), "p", "c")))
            assertTrue(back.disposition == (d.takeUnless { it.neverRecorded } ?: Disposition.CAPTURED))
        }
        val wire = FinanceDispositionV1.entries.filter { it != FinanceDispositionV1.UNRECOGNIZED && it != FinanceDispositionV1.FINANCE_DISPOSITION_V1_UNSPECIFIED }
        assertEquals(Disposition.entries.size, wire.size, "wire and model disposition sets must match")
        for (w in wire) assertEquals(w.name.removePrefix("FINANCE_DISPOSITION_V1_"), Disposition.valueOf(w.name.removePrefix("FINANCE_DISPOSITION_V1_")).name)
    }

    @Test fun decodingRefusesUnspecifiedMissingAndInvalidValues() {
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(FinanceInputV1.getDefaultInstance()) }
        val noBody = FinanceInputV1.newBuilder().setAction(FinanceWire.toProto(fund).action).build()
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(noBody) }
        val unspecifiedAuthority = FinanceWire.toProto(fund).toBuilder().apply { fundBuilder.clearAuthority() }.build()
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(unspecifiedAuthority) }
        val noExecution = FinanceWire.toProto(settle).toBuilder().apply { settleBuilder.clearExecution() }.build()
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(noExecution) }
        val unspecifiedDisposition = FinanceWire.toProto(decision).toBuilder().clearDisposition().build()
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(unspecifiedDisposition) }
        // Wire-valid but model-invalid: a control character in an account id and a recorded-never disposition.
        val badText = FinanceWire.toProto(fund).toBuilder().apply { fundBuilder.setAccountId("a\u0000b") }.build()
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(badText) }
        val neverRecorded = FinanceWire.toProto(decision).toBuilder().setDisposition(FinanceDispositionV1.FINANCE_DISPOSITION_V1_PRIOR_RESULT).build()
        assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(neverRecorded) }
    }

    @Test fun maximumSizedMessagesStayWithinThePinnedWireBounds() {
        val t = "é".repeat(64)   // 128 UTF-8 bytes, the per-field maximum
        assertEquals(128, t.toByteArray(Charsets.UTF_8).size)
        val a = ActionKey(t, t, t); val e = ExecutionKey(t, t, t, t)
        val maxCapture = FinanceInput.Capture(a, e, AccountId(t), AccountId(t), Long.MAX_VALUE, Long.MAX_VALUE)
        val maxFund = FinanceInput.Fund(a, Authority.TRADING, AccountId(t), AssetId(t), Long.MAX_VALUE)
        val maxSettle = FinanceInput.Settle(a, e, Int.MAX_VALUE)
        val maxDecision = Decision(Long.MAX_VALUE, a, "f".repeat(64), Disposition.SETTLED,
            List(4) { Leg(AccountId(t), AssetId(t), Long.MIN_VALUE) }, "f".repeat(64), "f".repeat(64))
        // Pinned ceilings: a field added or widened without revisiting these bounds fails here.
        assertTrue(FinanceWire.toProto(maxCapture).serializedSize <= 1_400, "capture ${FinanceWire.toProto(maxCapture).serializedSize}")
        assertTrue(FinanceWire.toProto(maxFund).serializedSize <= 1_000, "fund ${FinanceWire.toProto(maxFund).serializedSize}")
        assertTrue(FinanceWire.toProto(maxSettle).serializedSize <= 1_000, "settle ${FinanceWire.toProto(maxSettle).serializedSize}")
        assertTrue(FinanceWire.toProto(maxDecision).serializedSize <= 2_700, "decision ${FinanceWire.toProto(maxDecision).serializedSize}")
        assertTrue(maxCapture.canonical().size <= 1_400 && maxDecision.legs.size == 4)
        assertFailsWith<IllegalArgumentException> { AccountId(t + "x") }   // over-long text is refused at construction
    }
}
