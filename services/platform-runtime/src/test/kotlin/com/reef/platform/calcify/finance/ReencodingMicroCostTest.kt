package com.reef.platform.calcify.finance

import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Random
import kotlin.test.Test
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * Per-change cost of each re-encoding shortcut, old code against new in one JVM, interleaved: CPU nanoseconds and allocated bytes of the calling thread per operation
 * (min and median over rounds). A and B alternate round by round, so machine load moves both together. Research only (-PresearchTests).
 * The "old" side is the code before the change, kept here as the reference ([ReencodingDifferentialTest] proves it byte-identical).
 */
@org.junit.jupiter.api.Tag("research")
class ReencodingMicroCostTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val hex = HexFormat.of()
    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest()
    private var sink = 0L

    private class Result(val cpuNs: List<Double>, val alloc: List<Double>)
    private fun time(iterations: Int, op: (Int) -> Any?): Pair<Double, Double> {
        val id = Thread.currentThread().id
        val c0 = threads.currentThreadCpuTime; val a0 = threads.getThreadAllocatedBytes(id)
        var s = 0L
        for (i in 0 until iterations) s += (op(i)?.hashCode() ?: 0)
        val c = threads.currentThreadCpuTime - c0; val a = threads.getThreadAllocatedBytes(id) - a0
        sink += s
        return c.toDouble() / iterations to a.toDouble() / iterations
    }
    private fun compare(name: String, iterations: Int, rounds: Int = 7, old: (Int) -> Any?, new: (Int) -> Any?) {
        repeat(3) { time(iterations / 4, old); time(iterations / 4, new) }
        val oc = ArrayList<Double>(); val oa = ArrayList<Double>(); val nc = ArrayList<Double>(); val na = ArrayList<Double>()
        repeat(rounds) { val o = time(iterations, old); val n = time(iterations, new); oc += o.first; oa += o.second; nc += n.first; na += n.second }
        fun med(v: List<Double>) = v.sorted()[v.size / 2]
        println("MICRO %-44s old %7.0f ns (min %6.0f) %6.0f B | new %7.0f ns (min %6.0f) %6.0f B | cpu %+5.1f%% alloc %+5.1f%%".format(
            name, med(oc), oc.min(), med(oa), med(nc), nc.min(), med(na), 100 * (med(nc) / med(oc) - 1), 100 * (med(na) / med(oa) - 1)))
    }

    private val rnd = Random(5)
    private fun id(prefix: String) = "$prefix-${rnd.nextInt(100000)}"
    private val captures = Array(512) { FinanceInput.Capture(ActionKey("calcify-finance", "capture", id("exec")), ExecutionKey("p3-run", "p3-session", "INS${rnd.nextInt(16)}", id("exec")), AccountId(id("u")), AccountId(id("u")), 1L + rnd.nextInt(5), 1000L + rnd.nextInt(50)) }
    private val settles = Array(512) { i -> FinanceInput.Settle(ActionKey("calcify-finance", "settle", "${captures[i].execution.executionId}/1"), Authority.OPERATOR, captures[i].execution, 1) }
    private fun hexString() = hex.formatHex(ByteArray(32).also(rnd::nextBytes))
    private val decisions = Array(512) { i ->
        val a = captures[i].buyer; val b = captures[i].seller
        Decision(1L + i, settles[i].action, settles[i].digest(), Disposition.SETTLED,
            listOf(Leg(a, AssetId("USD"), -5000), Leg(b, AssetId("USD"), 5000), Leg(b, AssetId("INS1"), -3), Leg(a, AssetId("INS1"), 3)), hexString(), hexString(), hexString())
    }
    private val entities = Array(512) { i ->
        val c = captures[i]
        StateCommitment.Entity("O", Canon.bytes { execution(c.execution) }, Canon.bytes { str("PENDING"); long(1234); long(5) })
    }

    @Test fun eachShortcutAgainstTheCodeItReplaced() {
        val n = 400_000
        // input digest: canonical bytes then hash then hex (old), directly from the writer (new, first call), remembered (new, second call)
        compare("input digest (capture, first call)", n, old = { Canon.sha(oldBytes { writeInput(captures[it and 511]) }) }, new = { captures[it and 511].copy().digest() })
        compare("input digest (second call, same instance)", n, old = { Canon.sha(oldBytes { writeInput(captures[it and 511]) }) }, new = { captures[it and 511].digest() })
        // entity key and leaf hashes (3 digests, each with a provider lookup, versus the reused digest and the cached prefix)
        compare("StateCommitment.keyOf + leafOf", n, old = { val e = entities[it and 511]; val k = sha(oldBytes { str("calcify-finance-key-v2"); str(e.tag) }, e.keyBytes); sha(byteArrayOf(0), k, sha(byteArrayOf(1), e.valueBytes)) },
            new = { val e = entities[it and 511]; val k = StateCommitment.keyOf(e); StateCommitment.leafOf(e, k) })
        // one position entity's key and value framing (a fresh writer and its array per call, versus the reused writer)
        compare("framing a position entity (key+value)", n, old = { i -> val c = captures[i and 511]; Canon.Writer().also { it.str(c.buyer.value); it.str("USD") }.toByteArray() to Canon.Writer().also { it.long(i.toLong()) }.toByteArray() },
            new = { i -> val c = captures[i and 511]; Canon.bytes { str(c.buyer.value); str("USD") } to Canon.bytes { long(i.toLong()) } })
        // the trade's execution key framed six times per trade: fresh each time, or cached from the second use
        compare("execution key frame x6 (one trade)", n / 4, old = { i -> val k = captures[i and 511].execution; var t = 0; repeat(6) { t += Canon.Writer().also { w -> w.str(k.runId); w.str(k.venueSessionId); w.str(k.instrumentId); w.str(k.executionId) }.toByteArray().size }; t },
            new = { i -> val k = captures[i and 511].execution.copy(); var t = 0; repeat(6) { t += Canon.bytes { execution(k) }.size }; t })
        // store key and value of an entity
        compare("CapacityStore.entityKey + entityValue", n, old = { val e = entities[it and 511]; "e/${e.tag}/${hex.formatHex(e.keyBytes)}" to (oldBytes { str(CapacityStore.ENTITY_VERSION) } + e.valueBytes) },
            new = { val e = entities[it and 511]; CapacityStore.entityKey(e) to CapacityStore.entityValue(e) })
        // decision checksum
        compare("CapacityLedger.checksumOf (4 legs)", n, old = { val d = decisions[it and 511]; Canon.sha(oldBytes { writeChecksum(d) }) },
            new = { val d = decisions[it and 511]; CapacityLedger.checksumOf(d.sequence, d.action, d.inputDigest, d.disposition, d.legs, d.priorChain, d.stateDigest) })
        // leg balance
        compare("leg balance check (4 legs)", n, old = { val legs = decisions[it and 511].legs; val net = HashMap<AssetId, java.math.BigInteger>(); for (l in legs) net.merge(l.asset, java.math.BigInteger.valueOf(l.amount), java.math.BigInteger::add); net.values.all { v -> v.signum() == 0 } },
            new = { CapacityLedger.legsBalance(decisions[it and 511].legs) })
        // decision record published on the decisions topic: message objects then toByteArray (with the input digest checked a second time), versus encoded directly
        compare("forwarded record (decision+input) bytes", n, old = { val d = decisions[it and 511]; FinanceWire.toProto(d.copy(inputDigest = settles[it and 511].digest()), settles[it and 511].copy()).toByteArray() },
            new = { FinanceWire.encodeTrusted(decisions[it and 511], settles[it and 511]) })
        // what the processor does for one consumed record of two decisions: two published records, one head record
        compare("one trade: 2 published records + head", n / 2,
            old = { i -> val k = i and 511; val d = decisions[k]; val s = settles[k]; val c = captures[k]
                FinanceWire.toProto(d.copy(inputDigest = c.copy().digest()), c.copy()).toByteArray().size + FinanceWire.toProto(d.copy(inputDigest = s.copy().digest()), s.copy()).toByteArray().size + FinanceWire.toProto(d).toByteArray().size },
            new = { i -> val k = i and 511; val d = decisions[k]; FinanceWire.withInput(FinanceWire.encode(d), captures[k]).size + FinanceWire.withInput(FinanceWire.encode(d), settles[k]).size })
        compare("head record (decision) bytes", n, old = { FinanceWire.toProto(decisions[it and 511]).toByteArray() }, new = { FinanceWire.encode(decisions[it and 511]) })
        // id validation on every construction of an account, asset, action or execution key
        compare("ExecutionKey+ActionKey construction", n, old = { i -> val c = captures[i and 511]; oldValidate(c.execution.runId) && oldValidate(c.execution.venueSessionId) && oldValidate(c.execution.instrumentId) && oldValidate(c.execution.executionId) && oldValidate(c.action.namespace) && oldValidate(c.action.domain) && oldValidate(c.action.actionId) },
            new = { i -> val c = captures[i and 511]; ExecutionKey(c.execution.runId, c.execution.venueSessionId, c.execution.instrumentId, c.execution.executionId); ActionKey(c.action.namespace, c.action.domain, c.action.actionId) })
        // the lane configuration's digest was recomputed for every consumed record (sort of the security set with a byte-wise comparator, canonical encoding, SHA-256)
        for (securities in listOf(8, 64, 256)) {
            val config = CapacityConfig(null, AssetId("USD"), (0 until securities).map { AssetId("INS$it") }.toSet(), AccountId("opening"), 1000, 1000, 1000, 1000)
            val cached = config.digest()
            compare("CapacityConfig.digest per record ($securities securities)", n / 20, old = { config.digest() }, new = { cached })
        }
        // the fixed price of reaching the thread's scratch (a ThreadLocal read) against holding it
        val held = Canon.Scratch()
        compare("scratch: ThreadLocal read vs held reference", n * 10, old = { Canon.digest() }, new = { held.md.also { m -> m.reset() } })
        println("MICRO sink=$sink")
    }

    @Test fun projectorDecodeAgainstTheCodeItReplaced() {
        val n = 100_000
        val raws = Array(512) { i -> FinanceWire.toProto(decisions[i].copy(inputDigest = settles[i].digest()), settles[i]).toByteArray() }
        val regex = Regex("[0-9a-f]{64}")
        compare("projector: parse + reflective unknown-field check", n, old = { val p = FinanceDecisionV1.parseFrom(raws[it and 511]); oldNoUnknownFields(p); p }, new = { FinanceDecisionV1.parseFrom(raws[it and 511]) })
        compare("projector: 4 digest format checks", n, old = { val d = decisions[it and 511]; listOf(d.inputDigest, d.priorChain, d.stateDigest, d.checksum).all { s -> regex.matches(s) } },
            new = { val d = decisions[it and 511]; hexOk(d.inputDigest) && hexOk(d.priorChain) && hexOk(d.stateDigest) && hexOk(d.checksum) })
        compare("projector: recordFromProto (whole)", n, old = { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(raws[it and 511])) }, new = { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(raws[it and 511])) })
        println("MICRO sink=$sink")
    }

    /** The encoder as it was: a fresh writer and its buffer per encoding. */
    private fun oldBytes(block: Canon.Writer.() -> Unit): ByteArray = Canon.Writer().also { it.block() }.toByteArray()
    private val hexChar = BooleanArray(128).also { t -> for (c in '0'..'9') t[c.code] = true; for (c in 'a'..'f') t[c.code] = true }
    private fun hexOk(s: String): Boolean { if (s.length != 64) return false; var ok = true; for (i in 0 until 64) { val c = s[i].code; ok = ok and (c < 128 && hexChar[c]) }; return ok }
    private fun oldValidate(value: String): Boolean {
        if (value.isBlank() || value.any { it.isISOControl() }) return false
        return try { val e = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT); e.encode(java.nio.CharBuffer.wrap(value)).remaining() <= 128 } catch (_: java.nio.charset.CharacterCodingException) { false }
    }
    private fun oldNoUnknownFields(m: com.google.protobuf.Message) {
        require(m.unknownFields.serializedSize == 0) { "unknown protobuf fields" }
        for ((field, value) in m.allFields) {
            if (field.javaType != com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) (value as List<*>).forEach { oldNoUnknownFields(it as com.google.protobuf.Message) } else oldNoUnknownFields(value as com.google.protobuf.Message)
        }
    }
    private fun Canon.Writer.writeInput(i: FinanceInput.Capture) {
        str("calcify-finance-input-v1"); str(i.action.namespace); str(i.action.domain); str(i.action.actionId)
        str("CAPTURE"); execution(i.execution); str(i.buyer.value); str(i.seller.value); long(i.quantity); long(i.priceNanos)
    }
    private fun Canon.Writer.writeChecksum(d: Decision) {
        str("calcify-finance-decision-v1"); str(d.priorChain); long(d.sequence); str(d.stateDigest)
        str(d.action.namespace); str(d.action.domain); str(d.action.actionId); str(d.inputDigest); str(d.disposition.name); int(d.legs.size); d.legs.forEach { leg(it) }
    }
}
