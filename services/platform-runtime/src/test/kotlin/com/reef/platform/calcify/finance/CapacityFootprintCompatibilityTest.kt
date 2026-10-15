package com.reef.platform.calcify.finance

import com.reef.platform.calcify.MemoryLifecycleKv
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Retained representation may change; decoded values and persisted bytes may not. */
class CapacityFootprintCompatibilityTest {
    private val config = CapacityConfig(setOf(AccountId("buyer"), AccountId("seller")), AssetId("USD"), setOf(AssetId("SEC")), AccountId("opening"), 100, 100, 100, 100)
    private val exKey = ExecutionKey("run", "session", "SEC", "exec")

    private fun entities(obligation: Obligation): List<StateCommitment.Entity> = listOf(
        StateCommitment.Entity("X", Canon.bytes { execution(exKey) }, Canon.bytes { str("buyer"); str("seller"); long(1); long(10) }),
        StateCommitment.Entity("O", Canon.bytes { execution(exKey) }, Canon.bytes { str(obligation.status.name); long(obligation.cashResidual); long(obligation.shareResidual) }),
    )

    @Test fun restorePreservesParsedObligationValuesBeforeCallerValidatesCommitment() {
        for (o in listOf(Obligation(ObligationStatus.SETTLED, 0, 0), Obligation(ObligationStatus.SETTLED, 1, 0), Obligation(ObligationStatus.PENDING, 10, 1))) {
            val ledger = CapacityLedger.restore(config, entities(o), 0, 0, CapacityChain.genesisOf(config))
            assertEquals(o, ledger.snapshot().obligations.getValue(exKey))
            assertEquals(StateCommitment.reference(ledger.snapshot()), ledger.commitment())
        }
    }

    @Test fun restoreRejectsTrailingBytesEvenForSharedSettledValue() {
        val records = entities(Obligation(ObligationStatus.SETTLED, 0, 0)).map { if (it.tag == "O") StateCommitment.Entity(it.tag, it.keyBytes, it.valueBytes + byteArrayOf(1)) else it }
        assertFailsWith<IllegalArgumentException> { CapacityLedger.restore(config, records, 0, 0, CapacityChain.genesisOf(config)) }
    }

    @Test fun decisionsAndStoreWritesMatchFrozenBaseBytes() {
        assertEquals("94a435bfb7837d7e5fd8b65374a50f304393ae0f026fe6b335aeea4a06fcb598", footprintByteProbe(DecisionMode.TWO_STEP))
        assertEquals("a87df51318bec9af86dcdf240821ddc08b56975c40292d5b639e2e365589f0b0", footprintByteProbe(DecisionMode.CAPTURE_SETTLE))
    }
}

/** Run same compiled probe against base/head classpaths; digest covers every refusal, decision, store write and restore continuation. */
internal fun footprintByteProbe(mode: DecisionMode): String {
    val md = MessageDigest.getInstance("SHA-256")
    val users = (0 until 6).map { AccountId("u$it") }
    val config = CapacityConfig(users.toSet(), AssetId("USD"), setOf(AssetId("SEC")), AccountId("opening"), 2000, 2000, 4000, 100, mode)
    var ledger = CapacityLedger(config)
    val kv = MemoryLifecycleKv()
    var seq = 0
    fun apply(input: FinanceInput) {
        val result = ledger.decide(input)
        md.update(Canon.bytes { str(result.disposition.name) })
        result.decision?.let { decision ->
            md.update(FinanceWire.withInput(FinanceWire.encode(decision), input))
            val writes = CapacityStore.writes(ledger, decision, "b".repeat(64))
            for ((key, value) in writes.toSortedMap()) { md.update(Canon.bytes { str(key); int(value.size) }); md.update(value) }
            kv.apply(writes)
        }
        if (++seq % 97 == 0) ledger = CapacityStore.restore(config, kv, "b".repeat(64)) ?: ledger
    }
    for (user in users) {
        apply(FinanceInput.Fund(ActionKey("n", "fund", "cash-${user.value}"), Authority.OPERATOR, user, AssetId("USD"), 10000))
        apply(FinanceInput.Fund(ActionKey("n", "fund", "shares-${user.value}"), Authority.OPERATOR, user, AssetId("SEC"), 1000))
    }
    val rnd = Random(239)
    val prior = ArrayList<FinanceInput>()
    repeat(1500) { i ->
        val ex = ExecutionKey("r", "s", "SEC", "e${rnd.nextInt(900)}")
        val b = users[rnd.nextInt(users.size)]; val s = users[rnd.nextInt(users.size)]
        val action = ActionKey("n", "trade", "a$i")
        val q = rnd.nextInt(0, 100).toLong(); val price = rnd.nextInt(0, 1000).toLong()
        val input = if (mode == DecisionMode.CAPTURE_SETTLE) FinanceInput.CaptureSettle(action, ex, b, s, q, price) else FinanceInput.Capture(action, ex, b, s, q, price)
        apply(input); prior += input
        if (mode == DecisionMode.TWO_STEP || i % 3 == 0) apply(FinanceInput.Settle(ActionKey("n", "settle", "s$i"), Authority.OPERATOR, ex, 1 + i % 3))
        if (i % 5 == 0) apply(prior[rnd.nextInt(prior.size)])
    }
    md.update(ledger.commitment().toByteArray())
    return HexFormat.of().formatHex(md.digest())
}

internal object CapacityFootprintByteProbe {
    @JvmStatic fun main(args: Array<String>) { for (mode in DecisionMode.entries) println("FOOTPRINT-BYTES mode=${mode.wire} digest=${footprintByteProbe(mode)}") }
}
