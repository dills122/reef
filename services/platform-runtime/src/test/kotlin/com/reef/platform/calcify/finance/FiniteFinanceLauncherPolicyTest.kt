package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals

class FiniteFinanceLauncherPolicyTest {
    @Test fun projectionFailuresAreClassifiedFatalOrTransient() {
        val fatal = listOf(StaleProjectionEpoch("stale"), FinanceProjectionDiverged("fork"), IllegalArgumentException("bad chain"))
        val transient = listOf(java.sql.SQLException("connection reset"), org.apache.kafka.common.errors.TimeoutException("broker"), IllegalStateException("timed out reading topic"), RuntimeException("io"))
        for (e in fatal) assertEquals(true, FiniteFinanceLauncher.isFatal(e), "$e")
        for (e in transient) assertEquals(false, FiniteFinanceLauncher.isFatal(e), "$e")
    }
}
