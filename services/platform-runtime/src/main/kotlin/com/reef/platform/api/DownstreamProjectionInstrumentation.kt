package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.ProjectionDirtyQueueStats
import com.reef.platform.infrastructure.persistence.CommittedProjectionFrontier
import com.reef.platform.infrastructure.persistence.CommittedProjectionWatermark
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DownstreamProjectionStage {
    OrderLifecycle,
    MarketData
}

data class DownstreamProjectionCallerStats(
    val calls: Long,
    val completed: Long,
    val failed: Long,
    val active: Long,
    val maxConcurrent: Long,
    val callers: Map<String, Long>
) {
    companion object {
        fun empty() = DownstreamProjectionCallerStats(0, 0, 0, 0, 0, emptyMap())
    }
}

object DownstreamProjectionCallerMetrics {
    private class StageMetrics {
        val calls = AtomicLong(0)
        val completed = AtomicLong(0)
        val failed = AtomicLong(0)
        val active = AtomicLong(0)
        val maxConcurrent = AtomicLong(0)
        val callers = ConcurrentHashMap<String, AtomicLong>()
    }

    private val metrics = DownstreamProjectionStage.entries.associateWith { StageMetrics() }

    fun <T : Number> record(
        stage: DownstreamProjectionStage,
        caller: String,
        enabled: Boolean = true,
        block: () -> T
    ): T {
        if (!enabled) return block()
        val stageMetrics = checkNotNull(metrics[stage])
        stageMetrics.calls.incrementAndGet()
        stageMetrics.callers.computeIfAbsent(caller) { AtomicLong(0) }.incrementAndGet()
        val active = stageMetrics.active.incrementAndGet()
        stageMetrics.maxConcurrent.accumulateAndGet(active, ::maxOf)
        try {
            return block().also {
                stageMetrics.completed.incrementAndGet()
            }
        } catch (ex: Exception) {
            stageMetrics.failed.incrementAndGet()
            throw ex
        } finally {
            stageMetrics.active.decrementAndGet()
        }
    }

    fun snapshot(stage: DownstreamProjectionStage): DownstreamProjectionCallerStats {
        val stageMetrics = checkNotNull(metrics[stage])
        return DownstreamProjectionCallerStats(
            calls = stageMetrics.calls.get(),
            completed = stageMetrics.completed.get(),
            failed = stageMetrics.failed.get(),
            active = stageMetrics.active.get(),
            maxConcurrent = stageMetrics.maxConcurrent.get(),
            callers = stageMetrics.callers.entries
                .sortedBy { it.key }
                .associate { it.key to it.value.get() }
        )
    }

    fun resetForTests() {
        metrics.values.forEach { stageMetrics ->
            stageMetrics.calls.set(0)
            stageMetrics.completed.set(0)
            stageMetrics.failed.set(0)
            stageMetrics.active.set(0)
            stageMetrics.maxConcurrent.set(0)
            stageMetrics.callers.clear()
        }
    }
}

data class DownstreamProjectionCoverageMarker(
    val markerId: String,
    val stage: DownstreamProjectionStage,
    val sourceProjectionName: String,
    val databaseGeneration: String,
    val sourceWatermarks: List<CommittedProjectionWatermark>,
    val prefixRecordedAt: String,
    val lifecycleCoveredAt: String,
    val databaseSnapshotAt: String,
    val postCommitObservedAt: String,
    val postCommitObserved: Boolean,
    val callerCount: Int,
    val callers: List<String>
)

data class DownstreamProjectionCoverageStats(
    val markerCount: Long,
    val clockGuardFailures: Long,
    val lastMarker: DownstreamProjectionCoverageMarker?,
    val databaseGeneration: String,
    val generationGuardFailures: Long
)

object DownstreamProjectionCoverageMetrics {
    private data class PendingPrefix(
        val projectionName: String,
        val watermarks: List<CommittedProjectionWatermark>,
        val observedThrough: Instant,
        var lifecycleCoveredAt: Instant? = null
    )

    private class StageCoverage {
        val pending = ArrayDeque<PendingPrefix>()
        var lastSnapshotAt: Instant? = null
        val markerCount = AtomicLong(0)
        val generationGuardFailures = AtomicLong(0)
        var databaseGeneration = ""
        val clockGuardFailures = AtomicLong(0)
        @Volatile
        var lastMarker: DownstreamProjectionCoverageMarker? = null
    }

    private val coverage = DownstreamProjectionStage.entries.associateWith { StageCoverage() }

    /**
     * The frontier must be read before the dirty-queue statement on the same projection store.
     * Projection writes and dirty removal commit together: MVCC retains a pending row while a
     * drain is uncommitted. An empty later snapshot covers this committed prefix.
     * Under0052 invalidation coalescing preserves the original database dirtied time;
     * a later minimum strictly after an observation also proves its older prefix drained.
     * Market proof requires an additional barrier after lifecycle coverage. None of these
     * observations asserts that newer canonical work is caught up or callers are idle.
     */
    @Synchronized
    fun observe(
        stage: DownstreamProjectionStage,
        sourceFrontier: CommittedProjectionFrontier,
        dirtyQueues: ProjectionDirtyQueueStats,
        callerStats: DownstreamProjectionCallerStats,
        observedAt: String = Instant.now().toString()
    ): DownstreamProjectionCoverageMarker? {
        val stageCoverage = checkNotNull(coverage[stage])
        val generation = sourceFrontier.databaseGeneration
        if (stageCoverage.databaseGeneration.isEmpty() && generation.isNotEmpty()) {
            stageCoverage.databaseGeneration = generation
        }
        if (generation.isEmpty() || generation != dirtyQueues.databaseGeneration ||
            generation != stageCoverage.databaseGeneration || stageCoverage.generationGuardFailures.get() > 0
        ) {
            stageCoverage.generationGuardFailures.incrementAndGet()
            stageCoverage.lastMarker = null
            stageCoverage.pending.clear()
            return null
        }
        // Error sentinels must be checked before dropping non-partition rows.
        if (sourceFrontier.watermarks.any { it.lastError.isNotEmpty() }) return null
        val watermarks = sourceFrontier.watermarks
            .filter { it.partitionId >= 0 }
            .sortedBy { it.partitionId }
        if (watermarks.isEmpty() || watermarks.any { it.lastPartitionSequence < 0 }) return null
        if (!watermarks.map { it.partitionId }.containsAll(sourceFrontier.requestedPartitions)) return null
        val databaseSnapshot = parseInstant(dirtyQueues.databaseSnapshotAt) ?: return null
        val observation = parseInstant(observedAt) ?: return null
        val lifecycleOldest = parseInstant(dirtyQueues.orderLifecycleOldestDirtiedAt)
        val marketOldest = parseInstant(dirtyQueues.marketDataOldestDirtiedAt)
        if (observation.isBefore(databaseSnapshot) ||
            stageCoverage.lastSnapshotAt?.isAfter(databaseSnapshot) == true ||
            lifecycleOldest?.isAfter(databaseSnapshot) == true || marketOldest?.isAfter(databaseSnapshot) == true
        ) {
            stageCoverage.clockGuardFailures.incrementAndGet()
            stageCoverage.pending.clear()
            return null
        }
        stageCoverage.lastSnapshotAt = databaseSnapshot
        if (dirtyQueues.orderLifecyclePending < 0 || dirtyQueues.marketDataPending < 0 ||
            (dirtyQueues.orderLifecyclePending > 0 && lifecycleOldest == null) ||
            (dirtyQueues.marketDataPending > 0 && marketOldest == null)
        ) return null

        stageCoverage.pending.removeAll { it.projectionName != sourceFrontier.projectionName }
        if (stageCoverage.pending.none { it.watermarks == watermarks }) {
            stageCoverage.pending.addLast(PendingPrefix(sourceFrontier.projectionName, watermarks, databaseSnapshot))
        }
        // Bound observer memory. Dropping old candidates delays proof; it never
        // makes a prefix look complete sooner. No threshold is relaxed.
        while (stageCoverage.pending.size > 128) stageCoverage.pending.removeFirst()
        for (prefix in stageCoverage.pending) {
            if (prefix.lifecycleCoveredAt == null &&
                (dirtyQueues.orderLifecyclePending == 0L || lifecycleOldest?.isAfter(prefix.observedThrough) == true)
            ) prefix.lifecycleCoveredAt = databaseSnapshot
        }
        val previous = stageCoverage.lastMarker?.takeIf { it.sourceProjectionName == sourceFrontier.projectionName }
        val covered = stageCoverage.pending.lastOrNull { prefix ->
            val lifecycleAt = prefix.lifecycleCoveredAt
            lifecycleAt != null &&
                (stage == DownstreamProjectionStage.OrderLifecycle || dirtyQueues.marketDataPending == 0L ||
                    marketOldest?.isAfter(lifecycleAt) == true) &&
                (previous == null || previous.sourceWatermarks.all { old ->
                    prefix.watermarks.any { it.partitionId == old.partitionId && it.lastPartitionSequence >= old.lastPartitionSequence }
                })
        } ?: return null
        // Dirtied times preserve the first pending invalidation. Once every
        // pending lifecycle row is newer than a prior frontier observation,
        // that prefix has committed. Market needs a SECOND barrier after that
        // lifecycle proof, because lifecycle completion creates market work.
        val coveredWatermarks = covered.watermarks
        while (stageCoverage.pending.isNotEmpty()) {
            if (stageCoverage.pending.removeFirst() === covered) break
        }
        val markerId = markerIdentity(sourceFrontier.projectionName, generation, coveredWatermarks)
        stageCoverage.lastMarker?.takeIf { it.markerId == markerId }?.let { return it }
        val marker = DownstreamProjectionCoverageMarker(
            markerId = markerId,
            stage = stage,
            sourceProjectionName = sourceFrontier.projectionName,
            databaseGeneration = generation,
            sourceWatermarks = coveredWatermarks,
            prefixRecordedAt = covered.observedThrough.toString(),
            lifecycleCoveredAt = checkNotNull(covered.lifecycleCoveredAt).toString(),
            databaseSnapshotAt = dirtyQueues.databaseSnapshotAt,
            postCommitObservedAt = observedAt,
            postCommitObserved = true,
            callerCount = callerStats.callers.size,
            callers = callerStats.callers.keys.sorted()
        )
        stageCoverage.markerCount.incrementAndGet()
        stageCoverage.lastMarker = marker
        return marker
    }

    @Synchronized
    fun snapshot(stage: DownstreamProjectionStage): DownstreamProjectionCoverageStats {
        val stageCoverage = checkNotNull(coverage[stage])
        return DownstreamProjectionCoverageStats(
            markerCount = stageCoverage.markerCount.get(),
            clockGuardFailures = stageCoverage.clockGuardFailures.get(),
            lastMarker = stageCoverage.lastMarker,
            databaseGeneration = stageCoverage.databaseGeneration,
            generationGuardFailures = stageCoverage.generationGuardFailures.get()
        )
    }

    fun resetForTests() {
        coverage.values.forEach { stageCoverage ->
            stageCoverage.markerCount.set(0)
            stageCoverage.generationGuardFailures.set(0)
            stageCoverage.databaseGeneration = ""
            stageCoverage.clockGuardFailures.set(0)
            stageCoverage.lastMarker = null
            stageCoverage.pending.clear()
            stageCoverage.lastSnapshotAt = null
        }
    }

    private fun markerIdentity(projectionName: String, generation: String, watermarks: List<CommittedProjectionWatermark>): String {
        val source = buildString {
            append("reef-downstream-covering-marker-v2\n")
            append(generation)
            append('\n')
            append(projectionName)
            append('\n')
            watermarks.forEach { watermark ->
                append(watermark.partitionId)
                append(':')
                append(watermark.lastPartitionSequence)
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun parseInstant(value: String): Instant? = runCatching {
        val iso = value.replace(' ', 'T')
        Instant.parse(if (Regex(".*[+-][0-9]{2}$").matches(iso)) "$iso:00" else iso)
    }.getOrNull()
}
