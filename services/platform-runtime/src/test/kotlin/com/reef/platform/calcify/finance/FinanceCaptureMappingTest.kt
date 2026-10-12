package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1
import reef.contracts.calcify.v1.LifecycleDispositionV1
import reef.contracts.calcify.v1.LifecycleMemberV1

/** Pins capture-to-input mapping `calcify-finite-finance-state-v1` (spec section 8; S2-A2 deliverable 1). */
class FinanceCaptureMappingTest {
    private val binding = FiniteLifecycleFixtures.binding()
    private val envelopes = FiniteLifecycleFixtures.sequence().completedRecordsList
    private val withTwoTrades = envelopes.single { it.source.sourceOffset == 2L }
    private fun mapped(e: FiniteLifecycleCaptureV1) = FinanceCaptureMapping.map(e, binding)
    private fun ready(e: FiniteLifecycleCaptureV1) = mapped(e).map { it as FinanceCaptureMapping.Mapped.Ready }
    private fun readyIndex(e: FiniteLifecycleCaptureV1, n: Int = 0) = e.membersList.withIndex().filter { it.value.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION }[n].index
    private fun edit(e: FiniteLifecycleCaptureV1, n: Int = 0, change: (LifecycleMemberV1.Builder) -> Unit): FiniteLifecycleCaptureV1 {
        val i = readyIndex(e, n)
        return e.toBuilder().setMembers(i, e.getMembers(i).toBuilder().also(change)).build()
    }

    @Test fun versionIsTheFinanceStateVersionOfTheBinding() {
        assertEquals("calcify-finite-finance-state-v1", FinanceCaptureMapping.VERSION)
    }

    @Test fun goldenInputsForTheFixtureTradesAreBitStable() {
        val all = envelopes.flatMap { ready(it) }.map { it.input }
        assertEquals(listOf(3L, 1L, 2L), all.map { it.quantity })
        assertTrue(all.all { it.priceNanos == 100_000_000_000L && it.buyer == AccountId("buyer-account") && it.seller == AccountId("seller-account") })
        assertEquals(listOf("4ff05f3004bd7980f72fd68cb7e2be19391a54b4fb4235bbdd661c6dc2ff43da", "38f043dc4dd68ceca805d1697f185b3ee9877fa3cf930b4cb0635eca0a0fd328",
            "6e8a3f3f048245b22638e41287b2e21f4baecf46ec8bf8156641c979e55d625c"), all.map { it.digest() })
        for (i in all) {
            assertEquals(ActionKey("calcify-finance", "capture", i.execution.executionId), i.action)
            assertEquals(ExecutionKey("p3-run", "p3-session", "AAPL", i.execution.executionId), i.execution)
        }
        // 3 + 1 + 2 units at 100 quote units funds the packet's 600,000,000,000-nano scenario exactly.
        assertEquals(600_000_000_000L, all.sumOf { it.quantity * it.priceNanos })
    }

    @Test fun readyMembersGetOrdinalsInEnvelopeOrderAndOtherDispositionsYieldNothing() {
        assertEquals(listOf(0, 1), mapped(withTwoTrades).map { it.memberOrdinal })
        assertEquals(3, withTwoTrades.membersCount)
        val noReady = envelopes.filter { e -> e.membersList.none { it.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION } }
        assertTrue(noReady.isNotEmpty() && noReady.all { mapped(it).isEmpty() })
        assertTrue(mapped(FiniteLifecycleCaptureV1.getDefaultInstance()).isEmpty())
    }

    @Test fun replayMembersNeverCreateASecondObligation() {
        val replayed = edit(withTwoTrades, 0) { it.setDisposition(LifecycleDispositionV1.LIFECYCLE_REPLAY) }
        assertEquals(1, mapped(replayed).size)
        assertEquals(0, mapped(replayed).single().memberOrdinal, "ordinals count ready members only")
    }

    @Test fun everyMalformedReadyMemberIsAnInvalidEntryNotAStallOrASkip() {
        val bad: Map<String, (LifecycleMemberV1.Builder) -> Unit> = mapOf(
            "other run" to { it.tradeBuilder.setRunId("other-run") },
            "other instrument" to { it.tradeBuilder.factBuilder.setInstrumentId("MSFT") },
            "other currency" to { it.tradeBuilder.factBuilder.priceBuilder.setCurrency("EUR") },
            "missing trade" to { it.clearTrade() },
            "one dependency" to { it.removeDependencies(1) },
            "three dependencies" to { it.addDependencies(it.getDependencies(0)) },
            "buy order mismatch" to { it.tradeBuilder.factBuilder.setBuyOrderId("p0-9") },
            "same order both sides" to { it.tradeBuilder.factBuilder.setSellOrderId(it.tradeBuilder.factBuilder.buyOrderId) },
            "buyer account swapped" to { it.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId("seller-account") },
            "seller participant wrong" to { it.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setParticipantId("buyer") },
            "unknown account" to { it.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setAccountId("stranger") },
            "zero quantity" to { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("0") },
            "leading zero" to { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("03") },
            "signed quantity" to { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("+3") },
            "negative price" to { it.tradeBuilder.factBuilder.priceBuilder.setNanos("-1") },
            "decimal price" to { it.tradeBuilder.factBuilder.priceBuilder.setNanos("1.5") },
            "empty price" to { it.tradeBuilder.factBuilder.priceBuilder.setNanos("") },
            "int64 overflow" to { it.tradeBuilder.factBuilder.priceBuilder.setNanos("9223372036854775808") },
            "blank execution id" to { it.tradeBuilder.factBuilder.setExecutionId(" ") },
        )
        for ((name, change) in bad) {
            val outcome = mapped(edit(withTwoTrades, 0, change))
            assertEquals(2, outcome.size, name)
            val invalid = outcome[0] as? FinanceCaptureMapping.Mapped.Invalid ?: error("$name accepted")
            assertEquals(0, invalid.memberOrdinal, name)
            assertEquals(64, invalid.memberDigest.length, name)
            assertTrue(outcome[1] is FinanceCaptureMapping.Mapped.Ready, "$name poisoned its sibling")
        }
    }

    @Test fun invalidDigestCoversTheWholeMemberSoDifferentBadMembersDiffer() {
        val a = mapped(edit(withTwoTrades, 0) { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("0") })[0] as FinanceCaptureMapping.Mapped.Invalid
        val b = mapped(edit(withTwoTrades, 0) { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("00") })[0] as FinanceCaptureMapping.Mapped.Invalid
        assertTrue(a.memberDigest != b.memberDigest)
    }

    @Test fun mappingIsDeterministicAcrossRepeatedAndRoundTrippedEnvelopes() {
        val again = FiniteLifecycleCaptureV1.parseFrom(withTwoTrades.toByteArray())
        assertEquals(mapped(withTwoTrades), mapped(again))
    }
}
