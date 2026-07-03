package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.ProjectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamCommandDrainBackpressureTest {
    @Test
    fun rejectsWhenWorkerStreamLagReachesThreshold() {
        val snapshot = StreamCommandDrainBackpressureSampler(
            workerSources = listOf(FixedStreamCommandTelemetrySource(streamLag = 250))
        ).snapshot()

        val error = snapshot.backpressure(maxWorkerStreamLag = 200, maxProjectorLag = 0)

        assertEquals("STREAM_COMMAND_WORKER_BACKPRESSURE", error?.code)
        assertEquals(250L, snapshot.maxWorkerStreamLag)
    }

    @Test
    fun rejectsWhenProjectorLagReachesThreshold() {
        val snapshot = StreamCommandDrainBackpressureSampler(
            workerSources = emptyList(),
            projectionStatusProvider = {
                ProjectionStatus(
                    projectionName = "runtime-normalized-submit",
                    projectedCount = 10,
                    lag = 500,
                    watermarks = emptyList()
                )
            }
        ).snapshot()

        val error = snapshot.backpressure(maxWorkerStreamLag = 0, maxProjectorLag = 400)

        assertEquals("STREAM_COMMAND_PROJECTOR_BACKPRESSURE", error?.code)
        assertEquals(500L, snapshot.projectorLag)
    }

    @Test
    fun allowsProjectorLagWhenVenueCorePolicyIsConfigured() {
        val snapshot = StreamCommandDrainBackpressureSampler(
            workerSources = emptyList(),
            projectionStatusProvider = {
                ProjectionStatus(
                    projectionName = "runtime-normalized-submit",
                    projectedCount = 10,
                    lag = 500,
                    watermarks = emptyList()
                )
            }
        ).snapshot()

        val error = snapshot.backpressure(
            maxWorkerStreamLag = 0,
            maxProjectorLag = 400,
            policy = StreamCommandDrainBackpressurePolicy.VenueCore
        )

        assertNull(error)
        assertEquals(500L, snapshot.projectorLag)
    }

    @Test
    fun parsesBackpressurePolicyAliases() {
        assertEquals(
            StreamCommandDrainBackpressurePolicy.ControlRoomFresh,
            StreamCommandDrainBackpressurePolicy.fromConfig("control_room_fresh")
        )
        assertEquals(
            StreamCommandDrainBackpressurePolicy.VenueCore,
            StreamCommandDrainBackpressurePolicy.fromConfig("venue-core")
        )
    }

    @Test
    fun allowsIntakeWhenDrainLagIsBelowThresholds() {
        val snapshot = StreamCommandDrainBackpressureSampler(
            workerSources = listOf(FixedStreamCommandTelemetrySource(streamLag = 25)),
            projectionStatusProvider = {
                ProjectionStatus(
                    projectionName = "runtime-normalized-submit",
                    projectedCount = 10,
                    lag = 50,
                    watermarks = emptyList()
                )
            }
        ).snapshot()

        val error = snapshot.backpressure(maxWorkerStreamLag = 100, maxProjectorLag = 100)

        assertNull(error)
    }
}

private class FixedStreamCommandTelemetrySource(
    private val streamLag: Long
) : StreamCommandTelemetrySource {
    override fun consumerSnapshot(): StreamCommandConsumerSnapshot {
        return StreamCommandConsumerSnapshot(
            partition = 0,
            durableName = "reef-stream-worker-w0-p00",
            filterSubject = "reef.cmd.v1.p00.>",
            streamLag = streamLag
        )
    }
}
