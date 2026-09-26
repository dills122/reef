package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.ProjectionStage
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalProjectionMetricsTest {
    @Test
    fun pairedStageDiagnosticsDoNotCountTimelineAsCommandStatus() {
        CanonicalProjectionMetrics.resetForTests()
        try {
            CanonicalProjectionMetrics.recordProjected(3, ProjectionStage.Timeline)
            CanonicalProjectionMetrics.recordProjected(2, ProjectionStage.CommandStatus)
            val combined = CanonicalProjectionMetrics.snapshot().projected
            assertEquals(5, combined)
            assertEquals(3, CanonicalProjectionMetrics.timelineProjected())
            assertEquals(2, combined - CanonicalProjectionMetrics.timelineProjected())
        } finally {
            CanonicalProjectionMetrics.resetForTests()
        }
    }
}
