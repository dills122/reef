package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SettlementFactStoreTest {
    @Test
    fun appendsAndReadsP2SettlementChainByScenarioRunId() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2")

        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-p2")

        assertEquals(listOf("obl-1"), stored.obligations.map { it.settlementObligationId })
        assertEquals(listOf("break-1"), stored.breaks.map { it.settlementBreakId })
        assertEquals(listOf("repair-1"), stored.repairs.map { it.settlementRepairId })
        assertEquals(listOf("resolution-1"), stored.resolutions.map { it.settlementResolutionId })
    }

    @Test
    fun duplicateAppendKeepsFactsIdempotent() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2")

        store.appendFacts(facts)
        store.appendFacts(facts)
        val stored = store.factsByScenarioRunId("run-p2")

        assertEquals(1, stored.obligations.size)
        assertEquals(1, stored.breaks.size)
        assertEquals(1, stored.repairs.size)
        assertEquals(1, stored.resolutions.size)
    }

    @Test
    fun rejectsDuplicateFactIdWithDifferentPayload() {
        val store = InMemorySettlementFactStore()
        val facts = p2Facts("run-p2")

        store.appendFacts(facts)

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2").copy(tradeId = "other-trade"))
                )
            )
        }
    }

    @Test
    fun rejectsBreakWithoutObligation() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    breaks = listOf(
                        SettlementBreakOpenedFact(
                            settlementBreakId = "break-1",
                            settlementObligationId = "missing",
                            scenarioRunId = "run-p2",
                            correlationId = "corr-1",
                            causationId = "trade-1",
                            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rejectsResolutionWithoutRepair() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    breaks = listOf(breakOpened("run-p2")),
                    resolutions = listOf(resolution("run-p2"))
                )
            )
        }
    }

    @Test
    fun rejectsRepairWithoutBreak() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    repairs = listOf(repair("run-p2"))
                )
            )
        }
    }

    @Test
    fun rejectsRepairWhoseObligationDoesNotMatchBreak() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2"), obligation("run-p2").copy(settlementObligationId = "obl-2", tradeId = "trade-2")),
                    breaks = listOf(breakOpened("run-p2")),
                    repairs = listOf(repair("run-p2").copy(settlementObligationId = "obl-2"))
                )
            )
        }
    }

    @Test
    fun rejectsResolutionWhoseBreakDoesNotMatchRepair() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2")),
                    breaks = listOf(breakOpened("run-p2"), breakOpened("run-p2").copy(settlementBreakId = "break-2")),
                    repairs = listOf(repair("run-p2")),
                    resolutions = listOf(resolution("run-p2").copy(settlementBreakId = "break-2"))
                )
            )
        }
    }

    @Test
    fun rejectsResolutionWhoseObligationDoesNotMatchRepair() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = "run-p2",
                    obligations = listOf(obligation("run-p2"), obligation("run-p2").copy(settlementObligationId = "obl-2", tradeId = "trade-2")),
                    breaks = listOf(breakOpened("run-p2")),
                    repairs = listOf(repair("run-p2")),
                    resolutions = listOf(resolution("run-p2").copy(settlementObligationId = "obl-2"))
                )
            )
        }
    }

    @Test
    fun rejectsObligationWithBlankRequiredFields() {
        val store = InMemorySettlementFactStore()
        val bases = listOf(
            obligation("run-p2").copy(tradeId = ""),
            obligation("run-p2").copy(buyerParticipantId = ""),
            obligation("run-p2").copy(sellerParticipantId = ""),
            obligation("run-p2").copy(instrumentId = ""),
            obligation("run-p2").copy(quantity = ""),
            obligation("run-p2").copy(cashAmount = ""),
            obligation("run-p2").copy(currency = ""),
            obligation("run-p2").copy(state = "OTHER")
        )
        bases.forEach { bad ->
            assertFailsWith<IllegalArgumentException>("expected failure for $bad") {
                store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(bad)))
            }
        }
    }

    @Test
    fun rejectsBreakWithWrongReasonOrState() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2"))))

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", breaks = listOf(breakOpened("run-p2").copy(reason = "OTHER")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", breaks = listOf(breakOpened("run-p2").copy(state = "OTHER")))
            )
        }
    }

    @Test
    fun rejectsRepairWithWrongActionActorTypeOrBlankActorId() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                obligations = listOf(obligation("run-p2")),
                breaks = listOf(breakOpened("run-p2"))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", repairs = listOf(repair("run-p2").copy(repairAction = "OTHER"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", repairs = listOf(repair("run-p2").copy(actorType = "OTHER"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", repairs = listOf(repair("run-p2").copy(actorId = ""))))
        }
    }

    @Test
    fun rejectsResolutionWithWrongSettlementOrExceptionState() {
        val store = InMemorySettlementFactStore()
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-p2",
                obligations = listOf(obligation("run-p2")),
                breaks = listOf(breakOpened("run-p2")),
                repairs = listOf(repair("run-p2"))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", resolutions = listOf(resolution("run-p2").copy(settlementState = "OTHER"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", resolutions = listOf(resolution("run-p2").copy(exceptionState = "OTHER"))))
        }
    }

    @Test
    fun rejectsFactWithScenarioRunIdMismatch() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(
                SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("other-run")))
            )
        }
    }

    @Test
    fun rejectsFactWithBlankCorrelationOrCausationId() {
        val store = InMemorySettlementFactStore()

        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2").copy(correlationId = ""))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendFacts(SettlementFactBundle(scenarioRunId = "run-p2", obligations = listOf(obligation("run-p2").copy(causationId = ""))))
        }
    }

    @Test
    fun factsByScenarioRunIdRejectsBlankId() {
        val store = InMemorySettlementFactStore()
        assertFailsWith<IllegalArgumentException> {
            store.factsByScenarioRunId("")
        }
    }

    @Test
    fun appendFactsSkipsValidationForEmptyBundle() {
        val store = InMemorySettlementFactStore()
        val empty = SettlementFactBundle(scenarioRunId = "")
        assertEquals(empty, store.appendFacts(empty))
    }

    @Test
    fun defaultsPostgresSettlementNamesToSettlementSchema() {
        val names = PostgresSettlementSqlNames()

        assertEquals("settlement.obligations", names.obligations)
        assertEquals("settlement.breaks", names.breaks)
        assertEquals("settlement.repairs", names.repairs)
        assertEquals("settlement.resolutions", names.resolutions)
    }

    @Test
    fun blankPostgresSettlementSchemaFallsBackToSettlement() {
        val names = PostgresSettlementSqlNames(schema = "")

        assertFalse(names.obligations.startsWith("."))
        assertFalse(names.obligations.endsWith("."))
    }

    @Test
    fun rejectsUnsafePostgresSettlementSchema() {
        assertFailsWith<IllegalArgumentException> {
            PostgresSettlementSqlNames(schema = "settlement;drop schema runtime")
        }
    }
}

private fun p2Facts(scenarioRunId: String): SettlementFactBundle {
    return SettlementFactBundle(
        scenarioRunId = scenarioRunId,
        obligations = listOf(obligation(scenarioRunId)),
        breaks = listOf(breakOpened(scenarioRunId)),
        repairs = listOf(repair(scenarioRunId)),
        resolutions = listOf(resolution(scenarioRunId))
    )
}

private fun obligation(scenarioRunId: String): SettlementObligationCreatedFact {
    return SettlementObligationCreatedFact(
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "trade-1",
        tradeId = "trade-1",
        buyerParticipantId = "buyer-1",
        sellerParticipantId = "seller-1",
        instrumentId = "AAPL",
        quantity = "100",
        cashAmount = "15000.00",
        currency = "USD",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    )
}

private fun breakOpened(scenarioRunId: String): SettlementBreakOpenedFact {
    return SettlementBreakOpenedFact(
        settlementBreakId = "break-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "trade-1",
        occurredAt = Instant.parse("2026-01-01T00:00:01Z")
    )
}

private fun repair(scenarioRunId: String): SettlementRepairPostedFact {
    return SettlementRepairPostedFact(
        settlementRepairId = "repair-1",
        settlementBreakId = "break-1",
        settlementObligationId = "obl-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "repair-command-1",
        actorId = "ops-user-1",
        occurredAt = Instant.parse("2026-01-01T00:00:02Z")
    )
}

private fun resolution(scenarioRunId: String): SettlementResolvedFact {
    return SettlementResolvedFact(
        settlementResolutionId = "resolution-1",
        settlementObligationId = "obl-1",
        settlementBreakId = "break-1",
        settlementRepairId = "repair-1",
        scenarioRunId = scenarioRunId,
        correlationId = "corr-1",
        causationId = "repair-command-1",
        occurredAt = Instant.parse("2026-01-01T00:00:03Z")
    )
}
