package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapacityPlanToolTest {
    private val instruments = (0 until 64).map { "INS-$it" }

    @Test fun theGeneratedPlanParsesAndUsesTheEnginesRouting() {
        val json = CapacityPlanTool.plan(CapacityPlanTool.Spec(run = "run-x", session = "session-x", lanes = 4, instruments = instruments, expectedTrades = 9_000_000, decisionMode = DecisionMode.CAPTURE_SETTLE, applicationId = "capacity-x"))
        val deployment = CapacityRuntime.parsePlan(json.toByteArray())
        assertEquals(4, deployment.plan.lanes)
        assertEquals(CapacityRouting.engineLanes("run-x", "session-x", instruments, 4), deployment.plan.laneInstruments)
        assertEquals(DecisionMode.CAPTURE_SETTLE, deployment.plan.decisionMode)
        assertEquals("capacity-x", deployment.applicationId)
        deployment.plan.requireEngineAligned()
        assertTrue(deployment.decoder is CapacityResolvedContextDecoder)
    }

    @Test fun theBoundsCoverTheExpectedTradesOnTheBusiestLaneWithHeadroom() {
        val spec = CapacityPlanTool.Spec(run = "r", session = "s", lanes = 4, instruments = instruments, expectedTrades = 9_000_000, decisionMode = DecisionMode.TWO_STEP)
        val plan = CapacityRuntime.parsePlan(CapacityPlanTool.plan(spec).toByteArray()).plan
        // two decisions per trade in two-step mode; a skewed lane may carry most of the trades, so each lane is bounded for all of them
        assertTrue(plan.sequenceBound >= 2L * 9_000_000)
        assertTrue(plan.maxCaptures >= 9_000_000 && plan.maxObligations >= 9_000_000 && plan.maxSettleAttempts >= 1)
        val combined = CapacityRuntime.parsePlan(CapacityPlanTool.plan(spec.copy(decisionMode = DecisionMode.CAPTURE_SETTLE)).toByteArray()).plan
        assertTrue(combined.sequenceBound >= 9_000_000)
    }

    @Test fun twoStepIsTheDefaultAndSpellsNoModeField() {
        val json = CapacityPlanTool.plan(CapacityPlanTool.Spec(run = "r", session = "s", lanes = 2, instruments = instruments.take(16), expectedTrades = 1000, decisionMode = DecisionMode.TWO_STEP))
        assertTrue("decisionMode" !in json)
        assertEquals(json, CapacityPlanTool.plan(CapacityPlanTool.Spec(run = "r", session = "s", lanes = 2, instruments = instruments.take(16), expectedTrades = 1000, decisionMode = DecisionMode.TWO_STEP)), "deterministic")
    }

    @Test fun anInstrumentSetThatLeavesALaneEmptyIsRefusedWithAClearMessage() {
        val failure = assertFailsWith<IllegalArgumentException> { CapacityPlanTool.plan(CapacityPlanTool.Spec(run = "r", session = "s", lanes = 4, instruments = listOf("ONLY-ONE"), expectedTrades = 10, decisionMode = DecisionMode.TWO_STEP)) }
        assertTrue(failure.message!!.contains("lane"), failure.message)
    }

    @Test fun commandLineArgumentsBuildTheSameSpec() {
        val spec = CapacityPlanTool.parseArgs(listOf("--run", "r", "--session", "s", "--lanes", "4", "--instruments", "A1,B2,C3,D4,E5,F6,G7,H8,I9,J10,K11,L12,M13,N14,O15,P16", "--expected-trades", "5000", "--mode", "capture-settle-v1"))
        assertEquals(4, spec.lanes); assertEquals(DecisionMode.CAPTURE_SETTLE, spec.decisionMode); assertEquals(5000L, spec.expectedTrades); assertEquals(16, spec.instruments.size)
        assertFailsWith<IllegalArgumentException> { CapacityPlanTool.parseArgs(listOf("--lanes", "4")) }
    }
}
