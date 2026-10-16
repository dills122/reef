package com.reef.platform.calcify.finance

import com.google.protobuf.Descriptors
import kotlin.test.Test
import kotlin.test.assertEquals
import reef.contracts.calcify.finance.v1.FinanceDecisionV1

/**
 * `FinanceWire.noUnknownFields` checks, by hand, every nested message the finance schema holds today (it replaced a reflective walk that followed whatever the schema grew).
 * If a message-typed field is added to the decision or input, unknown bytes inside it would be accepted silently until that check is extended. This test fails first.
 */
class FinanceWireSchemaGuardTest {
    private fun reachable(root: Descriptors.Descriptor, into: MutableSet<String> = sortedSetOf()): MutableSet<String> {
        if (!into.add(root.fullName)) return into
        for (f in root.fields) if (f.type == Descriptors.FieldDescriptor.Type.MESSAGE) reachable(f.messageType, into)
        return into
    }

    @Test fun everyMessageTypeReachableFromTheDecisionIsOneTheTypedUnknownFieldCheckCovers() {
        val reached = reachable(FinanceDecisionV1.getDescriptor())
        val covered = sortedSetOf(
            "reef.contracts.calcify.finance.v1.FinanceDecisionV1",
            "reef.contracts.calcify.finance.v1.FinanceInputV1",
            "reef.contracts.calcify.finance.v1.FinanceActionKeyV1",
            "reef.contracts.calcify.finance.v1.FinanceExecutionKeyV1",
            "reef.contracts.calcify.finance.v1.FinanceLegV1",
            "reef.contracts.calcify.finance.v1.FinanceCaptureV1",
            "reef.contracts.calcify.finance.v1.FinanceCaptureSettleV1",
            "reef.contracts.calcify.finance.v1.FinanceFundV1",
            "reef.contracts.calcify.finance.v1.FinanceSettleV1",
        )
        assertEquals(covered, reached, "the finance schema gained or lost a message type: extend FinanceWire.noUnknownFields and this list together")
    }
}
