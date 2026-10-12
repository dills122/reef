package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlLogStateTest {
    private val a = AccountId("buyer-account"); private val usd = AssetId("USD")
    private fun fund(id: String, amount: Long = 5) = FinanceInput.Fund(ActionKey("op", "fund", id), Authority.OPERATOR, a, usd, amount)
    private fun settle(id: String, attempt: Int = 1) = FinanceInput.Settle(ActionKey("op", "repair", id), Authority.OPERATOR, ExecutionKey("r", "s", "i", "e"), attempt)
    private fun bytes(i: FinanceInput) = FinanceWire.toProto(i).toByteArray()

    @Test fun newKeyIsAdmittedAndRecordedThenIdenticalResubmitIsAlreadySubmitted() {
        val s = ControlLogState(); val f = fund("f1")
        assertNull(s.admit(f)); s.record(f, 7)
        assertEquals(ControlSubmission.AlreadySubmitted(7), s.admit(f))
        assertEquals(1, s.funds); assertEquals(1, s.writes)
    }

    @Test fun sameKeyDifferentPayloadIsAnActionConflictAndConsumesNothing() {
        val s = ControlLogState(); s.record(fund("f1", 5), 3)
        assertEquals(ControlSubmission.ActionConflict(3), s.admit(fund("f1", 6)))
        assertEquals(1, s.writes)
    }

    @Test fun fundBudgetIsExactlyEightAndIsIndependentOfSettleWrites() {
        val s = ControlLogState()
        repeat(8) { val f = fund("f$it"); assertNull(s.admit(f)); s.record(f, it.toLong()) }
        assertTrue(s.admit(fund("f8")) is ControlSubmission.BudgetExceeded)
        assertNull(s.admit(settle("s0")), "a settle is still admitted when the fund budget is spent")
    }

    @Test fun totalWriteBudgetIsExactlyOneHundredSixtyEight() {
        val s = ControlLogState()
        repeat(168) { val x = settle("s$it"); assertNull(s.admit(x)); s.record(x, it.toLong()) }
        assertTrue(s.admit(settle("s168")) is ControlSubmission.BudgetExceeded)
        assertTrue(s.admit(fund("f0")) is ControlSubmission.BudgetExceeded)
        assertEquals(ControlSubmission.AlreadySubmitted(0), s.admit(settle("s0")), "a known key is still answered after the budget is spent")
    }

    @Test fun refusedInputDoesNotChangeState() {
        val s = ControlLogState(); s.record(fund("f1"), 1)
        repeat(3) { s.admit(fund("f1", 99)); s.admit(fund("f1")) }
        assertEquals(1, s.writes); assertEquals(1, s.funds)
    }

    @Test fun rebuildReproducesKeysCountsAndOffsets() {
        val s = ControlLogState.rebuild(listOf(0L to bytes(fund("f1")), 2L to bytes(settle("s1")), 5L to bytes(fund("f2"))))
        assertEquals(3, s.writes); assertEquals(2, s.funds)
        assertEquals(ControlSubmission.AlreadySubmitted(2), s.admit(settle("s1")))
        assertEquals(ControlSubmission.ActionConflict(5), s.admit(fund("f2", 1)))
    }

    @Test fun rebuildRefusesCorruptLogs() {
        assertFailsWith<IllegalStateException>("garbage") { ControlLogState.rebuild(listOf(0L to byteArrayOf(1, 2, 3))) }
        assertFailsWith<IllegalStateException>("capture body") {
            ControlLogState.rebuild(listOf(0L to bytes(FinanceInput.Capture(ActionKey("a", "b", "c"), ExecutionKey("r", "s", "i", "e"), a, AccountId("s"), 1, 1))))
        }
        assertFailsWith<IllegalStateException>("repeated key") { ControlLogState.rebuild(listOf(0L to bytes(fund("f1")), 1L to bytes(fund("f1")))) }
        assertFailsWith<IllegalStateException>("over fund budget") { ControlLogState.rebuild((0 until 9).map { it.toLong() to bytes(fund("f$it")) }) }
        assertFailsWith<IllegalStateException>("over write budget") { ControlLogState.rebuild((0 until 169).map { it.toLong() to bytes(settle("s$it")) }) }
    }
}
