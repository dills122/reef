package com.reef.platform.calcify.finance

import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record
import reef.contracts.calcify.finance.v1.FinanceActionKeyV1
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.finance.v1.FinanceInputV1

/**
 * The lane's redundant re-encoding was removed without changing a byte of the verification contract. Every shortcut is compared here, on random values, with the
 * code it replaced, which this file keeps as the reference ("old*"): the same bytes, the same digests, the same accept and reject.
 */
class ReencodingDifferentialTest {
    private val hex = HexFormat.of()
    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest()

    // ---- reference: the code before the change ----

    private fun oldStrictUtf8(value: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try { encoder.encode(CharBuffer.wrap(value)) } catch (e: CharacterCodingException) { throw IllegalArgumentException("invalid UTF-16 text", e) }
        return ByteArray(buffer.remaining()).also(buffer::get)
    }
    private fun oldBoundedText(value: String): Boolean {
        if (value.isBlank() || value.any { it.isISOControl() }) return false
        return try { oldStrictUtf8(value).size <= 128 } catch (_: IllegalArgumentException) { false }
    }
    private fun oldKeyOf(e: StateCommitment.Entity): ByteArray = sha(Canon.bytes { str("calcify-finance-key-v2"); str(e.tag) }, e.keyBytes)
    private fun oldLeafOf(e: StateCommitment.Entity, key: ByteArray): ByteArray = sha(byteArrayOf(0x00), key, sha(byteArrayOf(0x01), e.valueBytes))
    private fun oldEntityKey(e: StateCommitment.Entity) = "e/${e.tag}/${hex.formatHex(e.keyBytes)}"
    private fun oldEntityValue(e: StateCommitment.Entity) = Canon.bytes { str(CapacityStore.ENTITY_VERSION) } + e.valueBytes
    private fun oldBalanced(legs: List<Leg>): Boolean {
        val net = HashMap<AssetId, BigInteger>()
        for (l in legs) net.merge(l.asset, BigInteger.valueOf(l.amount), BigInteger::add)
        return net.values.all { it.signum() == 0 }
    }
    private fun oldNoUnknownFields(m: com.google.protobuf.Message) {
        require(m.unknownFields.serializedSize == 0) { "unknown protobuf fields" }
        for ((field, value) in m.allFields) {
            if (field.javaType != com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) (value as List<*>).forEach { oldNoUnknownFields(it as com.google.protobuf.Message) } else oldNoUnknownFields(value as com.google.protobuf.Message)
        }
    }

    // ---- random values ----

    private var textMax = 24
    private fun text(rnd: Random, max: Int = textMax): String {
        while (true) { val t = rawText(rnd, max); if (oldBoundedText(t)) return t }
    }
    private fun rawText(rnd: Random, max: Int): String {
        val length = 1 + rnd.nextInt(max)
        return buildString {
            repeat(length) {
                when (rnd.nextInt(8)) {
                    0 -> append((0xa1 + rnd.nextInt(0x600)).toChar())
                    1 -> append((0x800 + rnd.nextInt(0xd000 - 0x800)).toChar())
                    2 -> appendCodePoint(0x10000 + rnd.nextInt(0x100000))
                    else -> append((0x21 + rnd.nextInt(0x5d)).toChar())
                }
            }
        }
    }
    private fun account(rnd: Random) = AccountId(text(rnd)); private fun asset(rnd: Random) = AssetId(text(rnd))
    private fun action(rnd: Random) = ActionKey(text(rnd), text(rnd), text(rnd))
    private fun execution(rnd: Random) = ExecutionKey(text(rnd), text(rnd), text(rnd), text(rnd))
    private fun amount(rnd: Random): Long = when (rnd.nextInt(6)) { 0 -> 0L; 1 -> Long.MAX_VALUE; 2 -> Long.MIN_VALUE; 3 -> -rnd.nextInt(1000).toLong(); else -> rnd.nextLong() }
    private fun input(rnd: Random): FinanceInput = when (rnd.nextInt(4)) {
        0 -> FinanceInput.Capture(action(rnd), execution(rnd), account(rnd), account(rnd), amount(rnd), amount(rnd))
        3 -> FinanceInput.CaptureSettle(action(rnd), execution(rnd), account(rnd), account(rnd), amount(rnd), amount(rnd))
        1 -> FinanceInput.Fund(action(rnd), Authority.entries[rnd.nextInt(2)], account(rnd), asset(rnd), amount(rnd))
        else -> FinanceInput.Settle(action(rnd), Authority.entries[rnd.nextInt(2)], execution(rnd), listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, rnd.nextInt())[rnd.nextInt(6)])
    }
    private fun hexString(rnd: Random) = hex.formatHex(ByteArray(32).also(rnd::nextBytes))
    private fun recorded(rnd: Random): Disposition = Disposition.entries.filter { !it.neverRecorded }[rnd.nextInt(Disposition.entries.size - 3)]
    /** [wellFormed] decisions are ones the decoder accepts (sequence from 1, at most four legs); the others exercise the encoder on any value. */
    private fun decision(rnd: Random, digest: String = hexString(rnd), wellFormed: Boolean = false): Decision = Decision(
        if (wellFormed) 1L + (rnd.nextLong() ushr 1) else listOf(0L, 1L, -5L, Long.MAX_VALUE, rnd.nextLong())[rnd.nextInt(5)], action(rnd), digest, recorded(rnd),
        List(rnd.nextInt(if (wellFormed) 5 else 7)) { Leg(account(rnd), asset(rnd), amount(rnd)) }, hexString(rnd), hexString(rnd), hexString(rnd))

    // ---- tests ----

    @Test fun boundedTextAcceptsAndRefusesExactlyWhatItDid() {
        val rnd = Random(20261015)
        val alphabet = listOf(' ', 'a', 'Z', '0', '\t', '\n', '\u000b', '\u001c', '\u0000', '\u001f', '\u007f', '\u0080', '\u009f', ' ', '\u0085', ' ', '　', 'é', ' ', '\ud800', '\udc00', 'x')
        repeat(60_000) {
            val value = when (rnd.nextInt(4)) {
                0 -> String(CharArray(rnd.nextInt(6)) { alphabet[rnd.nextInt(alphabet.size)] })
                1 -> String(CharArray(rnd.nextInt(300)) { (0x20 + rnd.nextInt(0x5f)).toChar() })
                2 -> "é".repeat(rnd.nextInt(80)) + " ".repeat(rnd.nextInt(3))
                else -> String(CharArray(120 + rnd.nextInt(20)) { if (rnd.nextInt(30) == 0) alphabet[rnd.nextInt(alphabet.size)] else 'k' })
            }
            val accepted = try { AccountId(value); true } catch (_: IllegalArgumentException) { false }
            assertEquals(oldBoundedText(value), accepted, "value ${value.map { it.code }}")
        }
        for (n in listOf(127, 128, 129)) { val ascii = "k".repeat(n); assertEquals(oldBoundedText(ascii), runCatching { AccountId(ascii) }.isSuccess, "ascii length $n") }
        for (n in listOf(63, 64, 65)) { val wide = "é".repeat(n); assertEquals(oldBoundedText(wide), runCatching { AssetId(wide) }.isSuccess, "two-byte length $n") }
        assertFailsWith<IllegalArgumentException> { ExecutionKey("r", "s", " ", "e") }
        assertFailsWith<IllegalArgumentException> { ActionKey("n", "d", "") }
    }

    @Test fun theReusedWriterAndDirectDigestsEqualAFreshEncoding() {
        val rnd = Random(11)
        repeat(5_000) {
            val strings = List(rnd.nextInt(6)) { text(rnd, 60) }; val longs = List(rnd.nextInt(4)) { rnd.nextLong() }
            val block: Canon.Writer.() -> Unit = { strings.forEach { str(it) }; longs.forEach { long(it) }; int(strings.size) }
            val fresh = Canon.Writer().also { it.block() }.toByteArray()
            assertContentEquals(fresh, Canon.bytes(block))
            assertEquals(hex.formatHex(sha(fresh)), Canon.digestHex(block)); assertEquals(hex.formatHex(sha(fresh)), Canon.sha(fresh))
            val tail = ByteArray(32).also(rnd::nextBytes)
            assertContentEquals(sha(fresh, tail), Canon.digestBytes(tail, block)); assertContentEquals(sha(fresh), Canon.digestBytes(null, block))
        }
    }

    @Test fun anEncodingInsideAnEncodingAndAfterAFailureDoesNotCorruptTheOuterOne() {
        val outer = Canon.bytes { str("outer"); str(Canon.digestHex { str("inner") }); str(hex.formatHex(Canon.bytes { long(7) })); int(3) }
        val expected = Canon.Writer().also { it.str("outer"); it.str(hex.formatHex(sha(Canon.Writer().also { w -> w.str("inner") }.toByteArray()))); it.str(hex.formatHex(Canon.Writer().also { w -> w.long(7) }.toByteArray())); it.int(3) }.toByteArray()
        assertContentEquals(expected, outer)
        assertFailsWith<IllegalArgumentException> { Canon.bytes { str("before"); str("\ud800") } }       // invalid UTF-16 half way through
        assertContentEquals(Canon.Writer().also { it.str("after") }.toByteArray(), Canon.bytes { str("after") })
        assertContentEquals(Canon.Writer().also { it.str("x".repeat(200_000)); }.toByteArray(), Canon.bytes { str("x".repeat(200_000)) })
        assertContentEquals(Canon.Writer().also { it.str("small") }.toByteArray(), Canon.bytes { str("small") })
    }

    @Test fun anExecutionKeyFramesTheSameBytesWhetherOrNotItIsCached() {
        val rnd = Random(12)
        repeat(2_000) {
            val key = execution(rnd); val reference = Canon.Writer().also { it.str(key.runId); it.str(key.venueSessionId); it.str(key.instrumentId); it.str(key.executionId) }.toByteArray()
            repeat(4) { use -> assertContentEquals(reference, Canon.bytes { execution(key) }, "use ${use + 1}") }
            assertEquals(key, key.copy()); assertEquals(key.hashCode(), key.copy().hashCode())
        }
    }

    @Test fun keyAndLeafDigestsEqualTheDefinition() {
        val rnd = Random(13)
        val tags = listOf("P", "X", "O", "A", "K", "Z", "é", "")
        repeat(4_000) {
            val e = StateCommitment.Entity(tags[rnd.nextInt(tags.size)], ByteArray(rnd.nextInt(200)).also(rnd::nextBytes), ByteArray(rnd.nextInt(120)).also(rnd::nextBytes))
            val key = StateCommitment.keyOf(e)
            assertContentEquals(oldKeyOf(e), key); assertContentEquals(oldLeafOf(e, key), StateCommitment.leafOf(e, key)); assertContentEquals(oldLeafOf(e, key), StateCommitment.leafOf(e))
            val trie = ByteArray(32).also(rnd::nextBytes); val seq = rnd.nextLong(); val controls = rnd.nextInt()
            assertEquals(hex.formatHex(sha(Canon.bytes { str(StateCommitment.LABEL); long(seq); int(controls) }, trie)), StateCommitment.commitment(seq, controls, trie))
        }
    }

    @Test fun storeKeysAndValuesEqualTheDefinition() {
        val rnd = Random(14)
        repeat(3_000) {
            val e = StateCommitment.Entity(listOf("P", "X", "O", "A", "K", "é")[rnd.nextInt(6)], ByteArray(rnd.nextInt(200)).also(rnd::nextBytes), ByteArray(rnd.nextInt(120)).also(rnd::nextBytes))
            assertEquals(oldEntityKey(e), CapacityStore.entityKey(e)); assertContentEquals(oldEntityValue(e), CapacityStore.entityValue(e))
        }
    }

    @Test fun legBalanceEqualsTheExactSumIncludingOverflow() {
        val rnd = Random(15)
        val a = AccountId("a"); val usd = AssetId("USD"); val sec = AssetId("SEC")
        repeat(30_000) {
            val legs = List(rnd.nextInt(6)) { Leg(a, if (rnd.nextBoolean()) usd else sec, when (rnd.nextInt(5)) { 0 -> Long.MAX_VALUE; 1 -> Long.MIN_VALUE; 2 -> 1L; 3 -> -1L; else -> (rnd.nextInt(5) - 2).toLong() }) }
            assertEquals(oldBalanced(legs), CapacityLedger.legsBalance(legs), "legs $legs")
        }
        val overflowing = listOf(Leg(a, usd, Long.MAX_VALUE), Leg(a, usd, 1L), Leg(a, usd, Long.MIN_VALUE), Leg(a, usd, Long.MIN_VALUE), Leg(a, usd, Long.MAX_VALUE), Leg(a, usd, 1L))
        assertEquals(oldBalanced(overflowing), CapacityLedger.legsBalance(overflowing))
        assertTrue(CapacityLedger.legsBalance(emptyList())); assertFalse(CapacityLedger.legsBalance(listOf(Leg(a, usd, 1L))))
    }

    @Test fun directProtobufEncodersWriteExactlyTheGeneratedMessageBytes() {
        val rnd = Random(16)
        repeat(20_000) {
            val i = input(rnd)
            assertContentEquals(FinanceWire.toProto(i).toByteArray(), FinanceWire.encode(i), "input $i")
            val d = decision(rnd, i.digest())
            assertContentEquals(FinanceWire.toProto(d).toByteArray(), FinanceWire.encode(d), "decision $d")
            assertContentEquals(FinanceWire.toProto(d, i).toByteArray(), FinanceWire.encodeTrusted(d, i), "record $d $i")
            // and the generated parser reads them back to the same values
            val good = decision(rnd, i.digest(), wellFormed = true)
            val back = FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(FinanceWire.encodeTrusted(good, i)))
            assertEquals(good, back.first); assertEquals(i, back.second)
        }
    }

    @Test fun directEncodersAgreeWhenNestedMessagesNeedMultiByteLengths() {
        // ids up to 128 bytes make an action 300+ bytes, a capture 600+ and a record over 1,000: the one-byte length reserved for a nested message must grow
        val rnd = Random(23); val before = textMax; textMax = 128
        try {
            repeat(3_000) {
                val i = input(rnd); val d = decision(rnd, i.digest())
                assertContentEquals(FinanceWire.toProto(i).toByteArray(), FinanceWire.encode(i))
                assertContentEquals(FinanceWire.toProto(d).toByteArray(), FinanceWire.encode(d))
                assertContentEquals(FinanceWire.toProto(d, i).toByteArray(), FinanceWire.encodeTrusted(d, i))
            }
            val big = "k".repeat(128)
            val maxInput = FinanceInput.Capture(ActionKey(big, big, big), ExecutionKey(big, big, big, big), AccountId(big), AccountId(big), Long.MAX_VALUE, Long.MAX_VALUE)
            val maxDecision = Decision(Long.MAX_VALUE, maxInput.action, maxInput.digest(), Disposition.SETTLED, List(4) { Leg(AccountId(big), AssetId(big), Long.MIN_VALUE) }, "a".repeat(64), "b".repeat(64), "c".repeat(64))
            assertContentEquals(FinanceWire.toProto(maxDecision, maxInput).toByteArray(), FinanceWire.encodeTrusted(maxDecision, maxInput))
            assertEquals(maxInput, FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(FinanceWire.encodeTrusted(maxDecision, maxInput))).second)
        } finally { textMax = before }
    }

    @Test fun directEncodersCoverEveryDispositionAuthorityAndEmptyField() {
        val a = ActionKey("n", "d", "x"); val e = ExecutionKey("r", "s", "i", "x")
        for (disposition in Disposition.entries.filter { !it.neverRecorded }) {
            val d = Decision(1, a, "0".repeat(64), disposition, emptyList(), "1".repeat(64), "2".repeat(64), "3".repeat(64))
            assertContentEquals(FinanceWire.toProto(d).toByteArray(), FinanceWire.encode(d), disposition.name)
        }
        for (authority in Authority.entries) {
            val inputs = listOf(FinanceInput.Fund(a, authority, AccountId("u"), AssetId("USD"), 0), FinanceInput.Settle(a, authority, e, 0), FinanceInput.Capture(a, e, AccountId("b"), AccountId("s"), 0, 0), FinanceInput.CaptureSettle(a, e, AccountId("b"), AccountId("s"), 0, 0))
            for (i in inputs) assertContentEquals(FinanceWire.toProto(i).toByteArray(), FinanceWire.encode(i), "$authority $i")
        }
        val empty = Decision(0, a, "", Disposition.CAPTURED, emptyList(), "", "", ""); assertContentEquals(FinanceWire.toProto(empty).toByteArray(), FinanceWire.encode(empty))
    }

    @Test fun decisionKeysEqualThePaddedFormat() {
        val rnd = Random(21)
        fun old(lane: Int, sequence: Long) = "${lane.toString().padStart(4, '0')}/${sequence.toString().padStart(20, '0')}"
        val lanes = listOf(0, 1, 9, 10, 99, 100, 9999, 10000, 123456, Int.MAX_VALUE, -1, Int.MIN_VALUE)
        val sequences = listOf(0L, 1L, 9L, 10L, 99999999999999999L, 100000000000000000L, 9000000000000000000L, Long.MAX_VALUE, -1L, Long.MIN_VALUE)
        for (l in lanes) for (s in sequences) assertEquals(old(l, s), LaneByKey.key(l, s), "lane $l sequence $s")
        repeat(50_000) { val l = rnd.nextInt(64); val s = rnd.nextLong() ushr rnd.nextInt(64); assertEquals(old(l, s), LaneByKey.key(l, s)); assertEquals(l, LaneByKey.laneOf(LaneByKey.key(l, s))) }
    }

    @Test fun aRecordIsTheHeadBytesFollowedByTheInput() {
        val rnd = Random(22)
        repeat(5_000) {
            val i = input(rnd); val d = decision(rnd, i.digest())
            val head = FinanceWire.encode(d)
            assertContentEquals(FinanceWire.toProto(d, i).toByteArray(), FinanceWire.withInput(head, i))
            assertContentEquals(head, FinanceWire.toProto(d).toByteArray())           // withInput does not touch its argument
            assertContentEquals(FinanceWire.encodeTrusted(d, i), FinanceWire.withInput(head, i))
        }
    }

    @Test fun anInputRemembersItsDigestAndACopyDoesNot() {
        val rnd = Random(17)
        repeat(2_000) {
            val i = input(rnd)
            val first = i.digest(); assertEquals(hex.formatHex(sha(i.canonical())), first); assertEquals(first, i.digest())
            val changed = when (i) {
                is FinanceInput.Capture -> i.copy(quantity = i.quantity + 1)
                is FinanceInput.Fund -> i.copy(amount = i.amount + 1)
                is FinanceInput.Settle -> i.copy(attempt = i.attempt + 1)
                is FinanceInput.CaptureSettle -> i.copy(quantity = i.quantity + 1)
            }
            assertEquals(hex.formatHex(sha(changed.canonical())), changed.digest()); assertTrue(first != changed.digest())
        }
    }

    @Test fun unknownFieldsAreRefusedExactlyWhereTheReflectiveCheckRefusedThem() {
        val rnd = Random(18)
        val unknown = com.google.protobuf.UnknownFieldSet.newBuilder().addField(99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(5).build()).build()
        fun refusedBoth(p: com.google.protobuf.Message, decode: () -> Any) {
            val reflective = runCatching { oldNoUnknownFields(p) }.isFailure; val actual = runCatching { decode() }.exceptionOrNull()
            assertEquals(reflective, actual is IllegalArgumentException, "reflective=$reflective actual=$actual")
        }
        repeat(3_000) {
            val i = input(rnd); val p = FinanceWire.toProto(i)
            refusedBoth(p) { FinanceWire.fromProto(p) }
            val edited = when (rnd.nextInt(5)) {
                0 -> p.toBuilder().setUnknownFields(unknown).build()
                1 -> p.toBuilder().apply { actionBuilder.setUnknownFields(unknown) }.build()
                else -> when (i) {
                    is FinanceInput.Capture -> p.toBuilder().apply { if (rnd.nextBoolean()) captureBuilder.setUnknownFields(unknown) else captureBuilder.executionBuilder.setUnknownFields(unknown) }.build()
                    is FinanceInput.Fund -> p.toBuilder().apply { fundBuilder.setUnknownFields(unknown) }.build()
                    is FinanceInput.Settle -> p.toBuilder().apply { if (rnd.nextBoolean()) settleBuilder.setUnknownFields(unknown) else settleBuilder.executionBuilder.setUnknownFields(unknown) }.build()
                    is FinanceInput.CaptureSettle -> p.toBuilder().apply { if (rnd.nextBoolean()) captureSettleBuilder.setUnknownFields(unknown) else captureSettleBuilder.executionBuilder.setUnknownFields(unknown) }.build()
                }
            }
            refusedBoth(edited) { FinanceWire.fromProto(edited) }
            assertFailsWith<IllegalArgumentException> { FinanceWire.fromProto(edited) }
            val d = decision(rnd, i.digest(), wellFormed = true); val record = FinanceWire.toProto(d, i)
            refusedBoth(record) { FinanceWire.recordFromProto(record) }
            val editedRecord = when (rnd.nextInt(5)) {
                0 -> record.toBuilder().setUnknownFields(unknown)
                1 -> record.toBuilder().apply { actionBuilder.setUnknownFields(unknown) }
                2 -> record.toBuilder().apply { if (legsCount > 0) getLegsBuilder(rnd.nextInt(legsCount)).setUnknownFields(unknown) else setUnknownFields(unknown) }
                3 -> record.toBuilder().apply { inputBuilder.setUnknownFields(unknown) }
                else -> record.toBuilder().apply { inputBuilder.actionBuilder.setUnknownFields(unknown) }
            }.build()
            refusedBoth(editedRecord) { FinanceWire.recordFromProto(editedRecord) }
            assertFailsWith<IllegalArgumentException> { FinanceWire.recordFromProto(editedRecord) }
        }
        assertEquals(FinanceActionKeyV1.getDefaultInstance().unknownFields.serializedSize, 0)
    }

    @Test fun digestFormatIsCheckedExactlyAsTheRegexDid() {
        val regex = Regex("[0-9a-f]{64}")
        val rnd = Random(19)
        val chars = "0123456789abcdefABCDEFg- é"
        repeat(20_000) {
            val s = when (rnd.nextInt(3)) { 0 -> String(CharArray(64) { chars[rnd.nextInt(16)] }); 1 -> String(CharArray(60 + rnd.nextInt(10)) { chars[rnd.nextInt(chars.length)] }); else -> String(CharArray(64) { if (rnd.nextInt(40) == 0) chars[rnd.nextInt(chars.length)] else chars[rnd.nextInt(16)] }) }
            val d = FinanceDecisionV1.newBuilder().setSequence(1).setAction(FinanceActionKeyV1.newBuilder().setNamespace("n").setDomain("d").setActionId("a")).setDisposition(reef.contracts.calcify.finance.v1.FinanceDispositionV1.FINANCE_DISPOSITION_V1_CAPTURED)
                .setInputDigest(s).setPriorChain("1".repeat(64)).setStateDigest("2".repeat(64)).setChecksum("3".repeat(64)).build()
            assertEquals(regex.matches(s), runCatching { FinanceWire.fromProto(d) }.isSuccess, "digest $s")
        }
    }

    // ---- the processor, end to end: every byte it stores and forwards equals what the old formulas give ----

    @Test fun theProcessorStoresAndForwardsTheBytesTheOldFormulasGive() {
        val usd = AssetId("USD"); val opening = AccountId("opening"); val securities = (0 until 3).map { AssetId("S$it") }
        val capacity = CapacityConfig(null, usd, securities.toSet(), opening, 10_000, 10_000, 10_000, 10_000)
        val cfg = CapacityProcessorConfig("b".repeat(64), capacity, "CAPTURE", "CONTROL", "DECISIONS", "reenc-diff")
        val decoder = CapacityInputDecoder { role, _, _, raw -> if (role == "CAPTURE") capture(raw) else CapacityInputDecoders.controlItems(raw) }
        val context = MockProcessorContext<String, ByteArray>()
        val store = CapacityTopology.storeBuilder(cfg, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        val processor = CapacityProcessor(cfg, decoder); processor.init(context)
        val reference = CapacityLedger(capacity)
        val expectedStore = LinkedHashMap<String, ByteArray>(); val expectedForwards = ArrayList<ByteArray>()
        var offset = 0L
        fun send(topic: String, i: FinanceInput) {
            context.setRecordMetadata(topic, 0, offset++)
            processor.process(Record(ByteArray(0), FinanceWire.toProto(i).toByteArray(), 0L))
            val outcome = reference.decide(i); val d = outcome.decision ?: return
            for (e in reference.lastWrites) expectedStore[oldEntityKey(e)] = oldEntityValue(e)
            expectedStore[CapacityStore.HEAD] = FinanceWire.toProto(d).toByteArray()
            expectedStore[CapacityStore.META] = CapacityStore.Meta("b".repeat(64), capacity.digest(), reference.sequence, reference.chain, reference.controls).bytes()
            expectedForwards += FinanceWire.toProto(d, i).toByteArray()
        }
        val rnd = Random(20); var n = 0
        val accounts = (0 until 12).map { AccountId("acct-é$it") }
        fun key() = ActionKey("calcify-finance", "x", "a${n++}")
        for (a in accounts) { send("CONTROL", FinanceInput.Fund(key(), Authority.OPERATOR, a, usd, 10_000_000)); for (s in securities) send("CONTROL", FinanceInput.Fund(key(), Authority.OPERATOR, a, s, 1_000)) }
        val executions = ArrayList<ExecutionKey>()
        repeat(1_500) { t ->
            val e = ExecutionKey("run", "session", securities[rnd.nextInt(3)].value, "ex-$t"); executions += e
            val buyer = accounts[rnd.nextInt(accounts.size)]; val seller = accounts[rnd.nextInt(accounts.size)]
            send("CAPTURE", FinanceInput.Capture(ActionKey("calcify-finance", "capture", "ex-$t"), e, buyer, seller, 1L + rnd.nextInt(30), 1L + rnd.nextInt(2000)))
            send("CAPTURE", FinanceInput.Settle(ActionKey("calcify-finance", "settle", "ex-$t/1"), Authority.OPERATOR, e, 1))
            if (t % 7 == 0) send("CONTROL", FinanceInput.Settle(ActionKey("calcify-finance", "settle", "ex-$t/2"), Authority.OPERATOR, executions[rnd.nextInt(executions.size)], 2))
            if (t % 11 == 0) send("CAPTURE", FinanceInput.Capture(ActionKey("calcify-finance", "capture", "ex-$t"), e, buyer, seller, 1, 1))   // a redelivery: refused, nothing stored
        }
        val actualStore = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value } }
        assertEquals(expectedStore.keys, actualStore.keys)
        for ((k, v) in expectedStore) assertContentEquals(v, actualStore.getValue(k), "store entry $k")
        val actualForwards = context.forwarded().map { it.record().value() as ByteArray }
        assertEquals(expectedForwards.size, actualForwards.size)
        expectedForwards.forEachIndexed { at, bytes -> assertContentEquals(bytes, actualForwards[at], "forwarded record $at") }
        // and the store restores to the state the head decision committed to
        val restored = CapacityStore.restore(capacity, com.reef.platform.calcify.StreamsLifecycleKv(store), "b".repeat(64))!!
        assertEquals(reference.commitment(), restored.commitment())
    }

    private fun capture(raw: ByteArray): List<CapacityItem> = listOf(CapacityItem.Run(FinanceWire.fromProto(FinanceInputV1.parseFrom(raw))))
}
