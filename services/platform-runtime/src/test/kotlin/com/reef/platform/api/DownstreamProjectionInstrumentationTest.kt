package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.ProjectionDirtyQueueStats
import com.reef.platform.infrastructure.persistence.CommittedProjectionFrontier
import com.reef.platform.infrastructure.persistence.CommittedProjectionWatermark
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownstreamProjectionInstrumentationTest {
    @BeforeTest
    fun resetMetrics() {
        DownstreamProjectionCallerMetrics.resetForTests()
        DownstreamProjectionCoverageMetrics.resetForTests()
    }

    @Test
    fun lifecycleMarkerCoversCommittedPrefixEvenWithActiveNewerCaller() {
        val status = frontier(partition = 2, projected = 42)
        val dirty = dirtyQueues(lifecyclePending = 0, marketDataPending = 3)

        DownstreamProjectionCallerMetrics.record(
            stage = DownstreamProjectionStage.OrderLifecycle,
            caller = "order-lifecycle-projector",
            enabled = true
        ) {
            assertNotNull(
                DownstreamProjectionCoverageMetrics.observe(
                    stage = DownstreamProjectionStage.OrderLifecycle,
                    sourceFrontier = status,
                    dirtyQueues = dirty,
                    callerStats = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle),
                    observedAt = "2026-08-24T18:00:00.100Z"
                )
            )
            1L
        }

        val marker = DownstreamProjectionCoverageMetrics.observe(
            stage = DownstreamProjectionStage.OrderLifecycle,
            sourceFrontier = status,
            dirtyQueues = dirty,
            callerStats = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle),
            observedAt = "2026-08-24T18:00:00.200Z"
        )

        assertNotNull(marker)
        assertEquals(64, marker.markerId.length)
        assertEquals("runtime-normalized-venue-outcomes", marker.sourceProjectionName)
        assertEquals(42, marker.sourceWatermarks.single().lastPartitionSequence)
        assertEquals("2026-08-24T18:00:00Z", marker.databaseSnapshotAt)
        assertEquals("2026-08-24T18:00:00.100Z", marker.postCommitObservedAt)
        assertTrue(marker.postCommitObserved)
        assertEquals(1, marker.callerCount)
        assertEquals(setOf("order-lifecycle-projector"), marker.callers.toSet())
    }

    @Test
    fun marketMarkerRequiresLifecycleAndMarketQueuesToBeCovered() {
        val status = frontier(partition = 0, projected = 12)
        val callers = DownstreamProjectionCallerStats(
            calls = 5,
            completed = 5,
            failed = 0,
            active = 0,
            maxConcurrent = 1,
            callers = mapOf("market-data-projector" to 5)
        )

        val blocked = DownstreamProjectionCoverageMetrics.observe(
            stage = DownstreamProjectionStage.MarketData,
            sourceFrontier = status,
            dirtyQueues = dirtyQueues(lifecyclePending = 1, marketDataPending = 0),
            callerStats = callers,
            observedAt = "2026-08-24T18:00:01Z"
        )
        assertNull(blocked)

        val marker = DownstreamProjectionCoverageMetrics.observe(
            stage = DownstreamProjectionStage.MarketData,
            sourceFrontier = status,
            dirtyQueues = dirtyQueues(lifecyclePending = 0, marketDataPending = 0),
            callerStats = callers,
            observedAt = "2026-08-24T18:00:02Z"
        )

        assertNotNull(marker)
        assertTrue(marker.postCommitObserved)
        assertEquals(1, DownstreamProjectionCoverageMetrics.snapshot(DownstreamProjectionStage.MarketData).markerCount)
    }

    @Test
    fun markerFailsClosedForMissingPartitionsErrorsAndBackwardObservationClock() {
        val dirty = dirtyQueues(lifecyclePending = 0, marketDataPending = 0)
        val callers = DownstreamProjectionCallerStats.empty()

        assertNull(
            DownstreamProjectionCoverageMetrics.observe(
                stage = DownstreamProjectionStage.OrderLifecycle,
                sourceFrontier = frontier(partition = 1, projected = 8).copy(requestedPartitions = listOf(1, 2)),
                dirtyQueues = dirty,
                callerStats = callers,
                observedAt = "2026-08-24T18:00:01Z"
            )
        )
        assertNull(
            DownstreamProjectionCoverageMetrics.observe(
                stage = DownstreamProjectionStage.OrderLifecycle,
                sourceFrontier = frontier(partition = 1, projected = 9, lastError = "boom"),
                dirtyQueues = dirty,
                callerStats = callers,
                observedAt = "2026-08-24T18:00:01Z"
            )
        )
        assertNull(
            DownstreamProjectionCoverageMetrics.observe(
                stage = DownstreamProjectionStage.OrderLifecycle,
                sourceFrontier = frontier(partition = 1, projected = 9),
                dirtyQueues = dirty,
                callerStats = callers,
                observedAt = "2026-08-24T17:59:59Z"
            )
        )

        val snapshot = DownstreamProjectionCoverageMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle)
        assertEquals(1, snapshot.clockGuardFailures)
        assertFalse(snapshot.lastMarker?.postCommitObserved ?: false)
    }

    private fun frontier(
        partition: Int,
        projected: Long,
        lastError: String = ""
    ) = CommittedProjectionFrontier(
        projectionName = "runtime-normalized-venue-outcomes",
        requestedPartitions = listOf(partition),
        watermarks = listOf(CommittedProjectionWatermark(partition, projected, lastError)),
        databaseGeneration = "db-generation-1"
    )

    @Test
    fun projectionWideErrorRowFailsClosedBeforeFilteringPartitions() {
        val frontier = frontier(1, 9)
        assertNull(DownstreamProjectionCoverageMetrics.observe(
            stage = DownstreamProjectionStage.MarketData,
            sourceFrontier = frontier.copy(watermarks = frontier.watermarks + CommittedProjectionWatermark(-1, 0, "failed")),
            dirtyQueues = dirtyQueues(0, 0),
            callerStats = DownstreamProjectionCallerStats.empty(),
            observedAt = "2026-08-24T18:00:01Z"
        ))
    }

    @Test
    fun restartOrReadPairGenerationMismatchPermanentlyInvalidatesCoverage() {
        val initial = frontier(1, 9)
        val callers = DownstreamProjectionCallerStats.empty()
        fun observe(frontier: CommittedProjectionFrontier, queues: ProjectionDirtyQueueStats) =
            DownstreamProjectionCoverageMetrics.observe(DownstreamProjectionStage.MarketData, frontier, queues, callers, "2026-08-24T18:00:01Z")
        assertNull(observe(initial.copy(watermarks = emptyList()), dirtyQueues(0, 0)))
        assertEquals("db-generation-1", DownstreamProjectionCoverageMetrics.snapshot(DownstreamProjectionStage.MarketData).databaseGeneration)
        assertNotNull(observe(initial, dirtyQueues(0, 0)))
        assertNull(observe(initial, dirtyQueues(0, 0).copy(databaseGeneration = "db-generation-2")))
        assertNull(observe(initial.copy(databaseGeneration = "db-generation-2"), dirtyQueues(0, 0).copy(databaseGeneration = "db-generation-2")))
        assertNull(observe(initial, dirtyQueues(0, 0)))
        val stats = DownstreamProjectionCoverageMetrics.snapshot(DownstreamProjectionStage.MarketData)
        assertNull(stats.lastMarker)
        assertEquals(3, stats.generationGuardFailures)
    }

    @Test
    fun busyQueuesCoverOlderPrefixesAfterSeparateLifecycleAndMarketBarriers() {
        val callers = DownstreamProjectionCallerStats.empty()
        fun observe(stage: DownstreamProjectionStage, seq: Long, second: Int, life: Int, market: Int) =
            DownstreamProjectionCoverageMetrics.observe(stage, frontier(0, seq),
                dirtyQueues(1, 1).copy(
                    databaseSnapshotAt = "2026-08-24T18:00:0${second}Z",
                    orderLifecycleOldestDirtiedAt = "2026-08-24 18:00:0${life}+00",
                    marketDataOldestDirtiedAt = "2026-08-24 18:00:0${market}+00"
                ), callers, "2026-08-24T18:00:0${second}.100Z")
        assertNull(observe(DownstreamProjectionStage.OrderLifecycle, 10, 1, 0, 0))
        val lifecycle = assertNotNull(observe(DownstreamProjectionStage.OrderLifecycle, 20, 3, 2, 0))
        assertEquals(10, lifecycle.sourceWatermarks.single().lastPartitionSequence)
        assertEquals("2026-08-24T18:00:01Z", lifecycle.prefixRecordedAt)
        assertEquals("2026-08-24T18:00:03Z", lifecycle.lifecycleCoveredAt)
        assertEquals("2026-08-24T18:00:03Z", lifecycle.databaseSnapshotAt)
        assertNull(observe(DownstreamProjectionStage.MarketData, 10, 1, 0, 0))
        assertNull(observe(DownstreamProjectionStage.MarketData, 20, 3, 2, 2),
            "market oldest newer than the source observation alone does not prove lifecycle output covered")
        val market = assertNotNull(observe(DownstreamProjectionStage.MarketData, 30, 5, 4, 4))
        assertEquals(10, market.sourceWatermarks.single().lastPartitionSequence)
        assertEquals("2026-08-24T18:00:01Z", market.prefixRecordedAt)
        assertEquals("2026-08-24T18:00:03Z", market.lifecycleCoveredAt)
        assertEquals("2026-08-24T18:00:05Z", market.databaseSnapshotAt)
        assertEquals("2026-08-24T18:00:05.100Z", market.postCommitObservedAt)
    }

    @Test
    fun equalOldestTimestampCannotCoverEarlierSourceObservation() {
        val stage = DownstreamProjectionStage.OrderLifecycle
        val callers = DownstreamProjectionCallerStats.empty()
        assertNull(DownstreamProjectionCoverageMetrics.observe(stage, frontier(0, 10),
            dirtyQueues(1, 0), callers, "2026-08-24T18:00:00.100Z"))
        assertNull(DownstreamProjectionCoverageMetrics.observe(stage, frontier(0, 20),
            dirtyQueues(1, 0).copy(databaseSnapshotAt = "2026-08-24T18:00:02Z",
                orderLifecycleOldestDirtiedAt = "2026-08-24T18:00:00Z"),
            callers, "2026-08-24T18:00:02.100Z"))
    }

    private fun dirtyQueues(lifecyclePending: Long, marketDataPending: Long) = ProjectionDirtyQueueStats(
        orderLifecyclePending = lifecyclePending,
        orderLifecycleOldestDirtiedAt = if (lifecyclePending > 0) "2026-08-24T17:59:58Z" else "",
        marketDataPending = marketDataPending,
        marketDataOldestDirtiedAt = if (marketDataPending > 0) "2026-08-24T17:59:59Z" else "",
        databaseSnapshotAt = "2026-08-24T18:00:00Z",
        databaseGeneration = "db-generation-1"
    )
}
