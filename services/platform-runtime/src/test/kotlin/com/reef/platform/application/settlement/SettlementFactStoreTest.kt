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
