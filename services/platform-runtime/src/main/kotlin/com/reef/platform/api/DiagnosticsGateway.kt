package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.ProjectionPersistenceRetryMetrics
import com.reef.platform.infrastructure.persistence.ProjectionStage
import com.reef.platform.infrastructure.persistence.RuntimeDataSources

internal data class ProjectionDiagnosticsConfig(
    val venueEventMaterializerBatchSize: Int,
    val venueEventMaterializerPollMs: Long,
    val venueEventMaterializerFetchTimeoutMs: Long,
    val marketDataProjectorProjectionName: String,
    val marketDataProjectorSourceProjectionName: String,
    val marketDataProjectorPollMs: Long,
    val marketDataProjectorBatchSize: Int,
    val marketDataProjectorEnabled: Boolean,
    val orderLifecycleProjectorPollMs: Long,
    val orderLifecycleProjectorBatchSize: Int,
    val orderLifecycleProjectorEnabled: Boolean,
    val downstreamProjectionInstrumentationEnabled: Boolean,
    val streamAckProjectorEnabled: Boolean,
    val streamAckProjectionName: String,
    val streamAckProjectionSource: CanonicalProjectionSource,
    val streamAckProjectionEventStream: String,
    val streamAckProjectionStage: ProjectionStage,
    val orderLifecycleProjectorWorkers: Int = 1
)

/**
 * Owns the internal read-only status/stats JSON endpoints (async command stats,
 * command accounting, DB pool stats, stream-ack health/worker stats, venue-event
 * materializer/market-data/order-lifecycle projector status). Pure reporting over
 * config and metrics snapshots — does not gate or reject traffic; see
 * RiskGuardrailGateway for admin controls and the mutation dispatcher for the
 * backpressure-gating checks that share volatile snapshot state with it.
 */
internal class DiagnosticsGateway(
    private val runtimeRole: PlatformRuntimeRole,
    private val commandProcessingMode: CommandProcessingMode,
    private val acceptedAsyncCommandIntake: AcceptedAsyncCommandIntake?,
    private val capturedCommandQueue: CapturedCommandQueue?,
    private val asyncCommandWorkerEnabled: Boolean,
    private val asyncCommandWorkerThreads: Int,
    private val asyncCommandWorkerBatchSize: Int,
    private val asyncCommandWorkerPollMs: Long,
    private val commandIntakeMaxActive: Long,
    private val commandIntakeMaxStaleProcessing: Long,
    private val commandIntakeBackpressureSampleMs: Long,
    private val streamCommandHealthCheck: StreamCommandHealthCheck?,
    private val streamCommandConfig: StreamCommandConfig,
    private val streamCommandMaxStorageUtilization: Double,
    private val streamCommandBackpressureSampleMs: Long,
    private val streamCommandDrainBackpressurePolicy: StreamCommandDrainBackpressurePolicy,
    private val streamCommandMaxWorkerStreamLag: Long,
    private val streamCommandMaxProjectorLag: Long,
    private val streamCommandDrainBackpressureSampleMs: Long,
    private val streamCommandMarkPublishedMode: String,
    private val streamCommandBackpressureWorkerDurables: String,
    private val streamCommandWorkerEnabled: Boolean,
    private val streamCommandWorkerBatchSize: Int,
    private val streamCommandWorkerPollMs: Long,
    private val streamCommandWorkerFetchTimeoutMs: Long,
    private val streamCommandWorkerDedicatedRuntimePoolEnabled: Boolean,
    private val projectionConfig: ProjectionDiagnosticsConfig,
    private val api: PlatformApi,
    private val runtimeLoopStarter: RuntimeLoopStarter
) {
    fun projectorStatusJson(includeProjectedCount: Boolean = true): String {
        val partitions = runtimeLoopStarter.projectorPartitions()
        val splitPoc = RuntimeEnv.bool("STREAM_ACK_PROJECTION_SPLIT_POC", false)
        val status = if (includeProjectedCount) {
            api.projectionStatus(
                projectionConfig.streamAckProjectionName,
                partitions,
                projectionConfig.streamAckProjectionSource.configValue
            )
        } else {
            api.projectionStatusWithoutCount(
                projectionConfig.streamAckProjectionName,
                partitions,
                projectionConfig.streamAckProjectionSource.configValue
            )
        }
        val metrics = CanonicalProjectionMetrics.snapshot()
        val timelineProjected = if (splitPoc) CanonicalProjectionMetrics.timelineProjected() else 0L
        val retryMetrics = ProjectionPersistenceRetryMetrics.snapshot()
        val fields = mutableListOf<Pair<String, Any?>>(
            "role" to runtimeRole.configValue,
            "status" to if (
                runtimeRole == PlatformRuntimeRole.Projector && projectionConfig.streamAckProjectorEnabled
            ) "running" else "inactive",
            "implementation" to "canonical-submit-projector",
            "source" to projectionConfig.streamAckProjectionSource.configValue,
            "eventStream" to projectionConfig.streamAckProjectionEventStream,
            "projectionStage" to if (splitPoc) ProjectionStage.CommandStatus.configValue else projectionConfig.streamAckProjectionStage.configValue,
            "splitPoc" to splitPoc,
            "workerMetricsScope" to if (splitPoc) "projected-status; other counters combined" else "configured-stage",
            "orderLifecycleProjectorEnabled" to projectionConfig.orderLifecycleProjectorEnabled,
            "marketDataProjectorEnabled" to projectionConfig.marketDataProjectorEnabled,
            "projectionName" to status.projectionName,
            "partitions" to partitions,
            "lag" to status.lag,
            "metrics" to mapOf(
                "projected" to metrics.projected - timelineProjected,
                "batches" to metrics.batches,
                "lastBatchSize" to metrics.lastBatchSize,
                "maxBatchSize" to metrics.maxBatchSize,
                "failed" to metrics.failed,
                "emptyPolls" to metrics.emptyPolls,
                "lastProjectedAt" to metrics.lastProjectedAt,
                "lastFailedAt" to metrics.lastFailedAt,
                "lastError" to metrics.lastError,
                "retryAttempts" to retryMetrics.retryAttempts,
                "retryExhausted" to retryMetrics.retryExhausted,
                "lastRetryAt" to retryMetrics.lastRetryAt,
                "lastRetrySqlState" to retryMetrics.lastRetrySqlState,
                "lastRetryError" to retryMetrics.lastRetryError
            ),
            "watermarks" to status.watermarks.map { watermark ->
                mapOf(
                    "projectionName" to watermark.projectionName,
                    "partition" to watermark.partitionId,
                    "lastPartitionSequence" to watermark.lastPartitionSequence,
                    "canonicalMaxPartitionSequence" to watermark.canonicalMaxPartitionSequence,
                    "lag" to watermark.lag,
                    "updatedAt" to watermark.updatedAt,
                    "lastError" to watermark.lastError
                )
            }
        )
        if (splitPoc) {
            val timeline = api.projectionStatusWithoutCount(
                "${projectionConfig.streamAckProjectionName}-timeline",
                partitions,
                projectionConfig.streamAckProjectionSource.configValue
            )
            fields += "timelineLag" to timeline.lag
            fields += "timelineProjected" to timelineProjected
            fields += "timelineWatermarks" to timeline.watermarks.map { watermark ->
                mapOf(
                    "projectionName" to watermark.projectionName,
                    "partition" to watermark.partitionId,
                    "lastPartitionSequence" to watermark.lastPartitionSequence,
                    "canonicalMaxPartitionSequence" to watermark.canonicalMaxPartitionSequence,
                    "lag" to watermark.lag,
                    "updatedAt" to watermark.updatedAt,
                    "lastError" to watermark.lastError
                )
            }
        }
        if (includeProjectedCount) fields.add("projectedCount" to status.projectedCount)
        return JsonCodec.writeObject(*fields.toTypedArray())
    }
    fun asyncCommandStatsJson(): String {
        val acceptedAsyncStats = acceptedAsyncCommandIntake?.stats()
        val queueCounts = capturedCommandQueue
            ?.statusCounts()
            ?.mapKeys { (status, _) -> status.name }
            ?: emptyMap()
        val counts = CommandLogStatus.values().associate { status ->
            status.name to (queueCounts[status.name] ?: 0L)
        }
        val metrics = AsyncCommandProcessorMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to asyncCommandWorkerEnabled,
            "processingMode" to commandProcessingMode.configValue,
            "workerThreads" to asyncCommandWorkerThreads,
            "batchSize" to asyncCommandWorkerBatchSize,
            "pollIntervalMs" to asyncCommandWorkerPollMs,
            "acceptedAsync" to if (acceptedAsyncStats == null) {
                mapOf("enabled" to false)
            } else {
                mapOf(
                    "enabled" to acceptedAsyncStats.enabled,
                    "laneCount" to acceptedAsyncStats.laneCount,
                    "activeLaneCount" to acceptedAsyncStats.activeLaneCount,
                    "queueCapacityPerLane" to acceptedAsyncStats.queueCapacityPerLane,
                    "inFlightPerLane" to acceptedAsyncStats.inFlightPerLane,
                    "queued" to acceptedAsyncStats.queued,
                    "maxLaneDepth" to acceptedAsyncStats.maxLaneDepth,
                    "inFlight" to acceptedAsyncStats.inFlight,
                    "completedWaiting" to acceptedAsyncStats.completedWaiting,
                    "maxOldestInFlightAgeMs" to acceptedAsyncStats.maxOldestInFlightAgeMs,
                    "saturatedLaneCount" to acceptedAsyncStats.saturatedLaneCount,
                    "received" to acceptedAsyncStats.received,
                    "duplicates" to acceptedAsyncStats.duplicates,
                    "backpressured" to acceptedAsyncStats.backpressured,
                    "processing" to acceptedAsyncStats.processing,
                    "completed" to acceptedAsyncStats.completed,
                    "failed" to acceptedAsyncStats.failed,
                    "retainedStatuses" to acceptedAsyncStats.retainedStatuses,
                    "retentionMaxRecords" to acceptedAsyncStats.retentionMaxRecords,
                    "retentionTtlMs" to acceptedAsyncStats.retentionTtlMs,
                    "retentionEvicted" to acceptedAsyncStats.retentionEvicted,
                    "terminalStatusMaxRecords" to acceptedAsyncStats.terminalStatusMaxRecords,
                    "terminalStatusTtlMs" to acceptedAsyncStats.terminalStatusTtlMs,
                    "retainedTerminalStatusRecords" to acceptedAsyncStats.retainedTerminalStatusRecords,
                    "retainedStatusRecords" to acceptedAsyncStats.retainedStatusRecords,
                    "statusRecordsEvicted" to acceptedAsyncStats.statusRecordsEvicted,
                    "lastReceivedAt" to acceptedAsyncStats.lastReceivedAt,
                    "lastCompletedAt" to acceptedAsyncStats.lastCompletedAt,
                    "lastFailedAt" to acceptedAsyncStats.lastFailedAt,
                    "lanes" to acceptedAsyncStats.lanes.map { lane ->
                        mapOf(
                            "lane" to lane.lane,
                            "queued" to lane.queued,
                            "inFlight" to lane.inFlight,
                            "completedWaiting" to lane.completedWaiting,
                            "oldestInFlightAgeMs" to lane.oldestInFlightAgeMs,
                            "windowSaturated" to lane.windowSaturated,
                            "received" to lane.received,
                            "backpressured" to lane.backpressured,
                            "processing" to lane.processing,
                            "completed" to lane.completed,
                            "failed" to lane.failed
                        )
                    }
                )
            },
            "intakeBackpressure" to mapOf(
                "maxActiveCommands" to commandIntakeMaxActive,
                "maxStaleProcessing" to commandIntakeMaxStaleProcessing,
                "sampleMs" to commandIntakeBackpressureSampleMs
            ),
            "queue" to counts,
            "metrics" to mapOf(
                "claimed" to metrics.claimed,
                "completed" to metrics.completed,
                "failed" to metrics.failed,
                "emptyPolls" to metrics.emptyPolls,
                "lastClaimedAt" to metrics.lastClaimedAt,
                "lastCompletedAt" to metrics.lastCompletedAt,
                "lastFailedAt" to metrics.lastFailedAt
            )
        )
    }

    fun commandAccountingJson(runId: String): String {
        val snapshot = capturedCommandQueue?.accountingSnapshot(runId)
        if (snapshot == null) {
            return JsonCodec.writeObject(
                "available" to false,
                "runId" to runId,
                "error" to "captured command queue unavailable"
            )
        }
        return JsonCodec.writeObject(
            "available" to true,
            "runId" to snapshot.runId,
            "accepted" to snapshot.accepted,
            "received" to snapshot.received,
            "processing" to snapshot.processing,
            "completed" to snapshot.completed,
            "failed" to snapshot.failed,
            "active" to snapshot.active,
            "terminal" to snapshot.terminal,
            "accountingGap" to snapshot.accountingGap,
            "staleProcessing" to snapshot.staleProcessing
        )
    }

    fun dbPoolStatsJson(): String {
        return JsonCodec.writeObject(
            "pools" to RuntimeDataSources.snapshots().map { snapshot ->
                mapOf(
                    "key" to snapshot.key,
                    "poolName" to snapshot.poolName,
                    "jdbcUrl" to snapshot.jdbcUrl,
                    "username" to snapshot.username,
                    "maximumPoolSize" to snapshot.maximumPoolSize,
                    "minimumIdle" to snapshot.minimumIdle,
                    "activeConnections" to snapshot.activeConnections,
                    "idleConnections" to snapshot.idleConnections,
                    "totalConnections" to snapshot.totalConnections,
                    "threadsAwaitingConnection" to snapshot.threadsAwaitingConnection
                )
            }
        )
    }

    fun streamCommandHealthJson(): String {
        val snapshot = streamCommandHealthCheck?.snapshot()
        if (snapshot == null) {
            return JsonCodec.writeObject(
                "available" to false,
                "processingMode" to commandProcessingMode.configValue,
                "stream" to streamCommandConfig.streamName,
                "error" to "stream command health unavailable"
            )
        }
        return JsonCodec.writeObject(
            "available" to snapshot.available,
            "processingMode" to commandProcessingMode.configValue,
            "stream" to snapshot.streamName,
            "messages" to snapshot.messageCount,
            "bytes" to snapshot.byteCount,
            "maxBytes" to snapshot.maxBytes,
            "storageUtilization" to snapshot.storageUtilization,
            "maxStorageUtilization" to streamCommandMaxStorageUtilization,
            "backpressureSampleMs" to streamCommandBackpressureSampleMs,
            "drainBackpressure" to mapOf(
                "policy" to streamCommandDrainBackpressurePolicy.configValue,
                "maxWorkerStreamLag" to streamCommandMaxWorkerStreamLag,
                "maxProjectorLag" to streamCommandMaxProjectorLag,
                "sampleMs" to streamCommandDrainBackpressureSampleMs,
                "workerDurables" to streamCommandBackpressureWorkerDurableNames()
            ),
            "markPublishedMode" to streamCommandMarkPublishedMode,
            "publishMode" to snapshot.publishMode,
            "publishInFlight" to snapshot.publishInFlight,
            "publishMaxInFlight" to snapshot.publishMaxInFlight,
            "publishQueueDepth" to snapshot.publishQueueDepth,
            "publishMaxQueueDepth" to snapshot.publishMaxQueueDepth,
            "publishLaneCount" to snapshot.publishLaneCount,
            "publishAccepted" to snapshot.publishAccepted,
            "publishCompleted" to snapshot.publishCompleted,
            "publishFailed" to snapshot.publishFailed,
            "publishRejected" to snapshot.publishRejected,
            "publishQueueWaitLastMs" to snapshot.publishQueueWaitLastMs,
            "publishQueueWaitMaxMs" to snapshot.publishQueueWaitMaxMs,
            "publishSlotWaitLastMs" to snapshot.publishSlotWaitLastMs,
            "publishSlotWaitMaxMs" to snapshot.publishSlotWaitMaxMs,
            "publishDelegateAckLastMs" to snapshot.publishDelegateAckLastMs,
            "publishDelegateAckMaxMs" to snapshot.publishDelegateAckMaxMs,
            "publishPipelineTotalLastMs" to snapshot.publishPipelineTotalLastMs,
            "publishPipelineTotalMaxMs" to snapshot.publishPipelineTotalMaxMs,
            "publishLanes" to snapshot.publishLaneSnapshots.map {
                mapOf(
                    "partition" to it.partition,
                    "accepted" to it.accepted,
                    "completed" to it.completed,
                    "failed" to it.failed,
                    "rejected" to it.rejected,
                    "queueDepth" to it.queueDepth,
                    "maxQueueDepthObserved" to it.maxQueueDepthObserved,
                    "inFlight" to it.inFlight,
                    "maxInFlightObserved" to it.maxInFlightObserved,
                    "queueWaitLastMs" to it.queueWaitLastMs,
                    "queueWaitMaxMs" to it.queueWaitMaxMs,
                    "slotWaitLastMs" to it.slotWaitLastMs,
                    "slotWaitMaxMs" to it.slotWaitMaxMs,
                    "delegateAckLastMs" to it.delegateAckLastMs,
                    "delegateAckMaxMs" to it.delegateAckMaxMs,
                    "totalLastMs" to it.totalLastMs,
                    "totalMaxMs" to it.totalMaxMs
                )
            },
            "publishAckLastMs" to snapshot.publishAckLastMs,
            "publishAckMaxMs" to snapshot.publishAckMaxMs,
            "sourceTopicFrontiers" to snapshot.sourceTopicFrontiers.map { frontier ->
                mapOf(
                    "partition" to frontier.partition,
                    "lastOffsetExclusive" to frontier.lastOffsetExclusive.toString()
                )
            },
            "acceptedSourceFrontiers" to snapshot.acceptedSourceFrontiers.map { frontier ->
                mapOf(
                    "partition" to frontier.partition,
                    "accepted" to frontier.accepted.toString(),
                    "firstOffsetInclusive" to frontier.firstOffsetInclusive.toString(),
                    "lastOffsetExclusive" to frontier.lastOffsetExclusive.toString()
                )
            },
            "producerMetrics" to snapshot.producerMetrics,
            "checkedAt" to snapshot.checkedAt.toString(),
            "error" to snapshot.error
        )
    }

    fun streamCommandWorkerStatsJson(): String {
        val stats = StreamCommandWorkerMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to streamCommandWorkerEnabled,
            "processingMode" to commandProcessingMode.configValue,
            "partitions" to runtimeLoopStarter.streamWorkerPartitions(),
            "batchSize" to streamCommandWorkerBatchSize,
            "pollIntervalMs" to streamCommandWorkerPollMs,
            "fetchTimeoutMs" to streamCommandWorkerFetchTimeoutMs,
            "dedicatedRuntimePoolEnabled" to streamCommandWorkerDedicatedRuntimePoolEnabled,
            "metrics" to mapOf(
                "fetched" to stats.fetched,
                "completed" to stats.completed,
                "failed" to stats.failed,
                "ackFailed" to stats.ackFailed,
                "unsupported" to stats.unsupported,
                "emptyPolls" to stats.emptyPolls,
                "lastFetchedAt" to stats.lastFetchedAt,
                "lastCompletedAt" to stats.lastCompletedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastAckFailedAt" to stats.lastAckFailedAt,
                "lastError" to stats.lastError
            ),
            "partitionMetrics" to stats.partitions.map { partition ->
                mapOf(
                    "partition" to partition.partition,
                    "fetched" to partition.fetched,
                    "completed" to partition.completed,
                    "failed" to partition.failed,
                    "ackFailed" to partition.ackFailed,
                    "unsupported" to partition.unsupported,
                    "localInFlight" to partition.localInFlight,
                    "maxDeliveredCount" to partition.maxDeliveredCount,
                    "lastFetchedStreamSequence" to partition.lastFetchedStreamSequence,
                    "lastCompletedStreamSequence" to partition.lastCompletedStreamSequence,
                    "lastFetchedAt" to partition.lastFetchedAt,
                    "lastCompletedAt" to partition.lastCompletedAt,
                    "lastFailedAt" to partition.lastFailedAt,
                    "lastAckFailedAt" to partition.lastAckFailedAt,
                    "oldestLocalInFlightAt" to partition.oldestLocalInFlightAt,
                    "oldestLocalInFlightAgeMs" to partition.oldestLocalInFlightAgeMs,
                    "lastError" to partition.lastError
                )
            },
            "consumerMetrics" to stats.consumers.map { consumer ->
                mapOf(
                    "partition" to consumer.partition,
                    "durableName" to consumer.durableName,
                    "filterSubject" to consumer.filterSubject,
                    "pending" to consumer.pending,
                    "waiting" to consumer.waiting,
                    "ackPending" to consumer.ackPending,
                    "redelivered" to consumer.redelivered,
                    "deliveredConsumerSequence" to consumer.deliveredConsumerSequence,
                    "deliveredStreamSequence" to consumer.deliveredStreamSequence,
                    "ackFloorConsumerSequence" to consumer.ackFloorConsumerSequence,
                    "ackFloorStreamSequence" to consumer.ackFloorStreamSequence,
                    "streamLastSequence" to consumer.streamLastSequence,
                    "streamLag" to consumer.streamLag,
                    "lastActiveAt" to consumer.lastActiveAt,
                    "sampledAt" to consumer.sampledAt,
                    "error" to consumer.error
                )
            }
        )
    }

    fun venueEventMaterializerTimingJson(): String = JsonCodec.writeObject(
        *MaterializerTimingJournal.global.snapshot().entries.map { it.key to it.value }.toTypedArray()
    )

    fun venueEventMaterializerStatsJson(): String {
        val stats = VenueEventBatchMaterializerMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to runtimeLoopStarter.venueEventMaterializerShouldStart(),
            "role" to runtimeRole.configValue,
            "processingMode" to commandProcessingMode.configValue,
            "batchSize" to projectionConfig.venueEventMaterializerBatchSize,
            "pollIntervalMs" to projectionConfig.venueEventMaterializerPollMs,
            "fetchTimeoutMs" to projectionConfig.venueEventMaterializerFetchTimeoutMs,
            "source" to "kafka",
            "metrics" to mapOf(
                "fetched" to stats.fetched,
                "materialized" to stats.materialized,
                "materializedOutcomes" to stats.materializedOutcomes,
                "failed" to stats.failed,
                "ackFailed" to stats.ackFailed,
                "unsupported" to stats.unsupported,
                "emptyPolls" to stats.emptyPolls,
                "lastFetchedStreamSequence" to stats.lastFetchedStreamSequence,
                "lastMaterializedStreamSequence" to stats.lastMaterializedStreamSequence,
                "lastMaterializedBatchId" to stats.lastMaterializedBatchId,
                "lastMaterializedPartition" to stats.lastMaterializedPartition,
                "lastMaterializedFirstSequence" to stats.lastMaterializedFirstSequence,
                "lastMaterializedLastSequence" to stats.lastMaterializedLastSequence,
                "materializerLag" to stats.materializerLag,
                "sourcePartitions" to stats.sourcePartitions.map { partition ->
                    mapOf(
                        "partition" to partition.partition,
                        "fetched" to partition.fetched.toString(),
                        "commitObserved" to partition.commitObserved.toString(),
                        "firstFetchedOffsetInclusive" to partition.firstFetchedOffsetInclusive.toString(),
                        "lastFetchedOffsetExclusive" to partition.lastFetchedOffsetExclusive.toString(),
                        "lastCommitObservedOffsetExclusive" to partition.lastCommitObservedOffsetExclusive.toString(),
                        "lag" to partition.lag.toString()
                    )
                },
                "materializedSourceFrontiers" to stats.materializedSourceFrontiers.map { frontier ->
                    mapOf(
                        "partition" to frontier.partition,
                        "observedBatches" to frontier.observedBatches.toString(),
                        "observedOutcomes" to frontier.observedOutcomes.toString(),
                        "firstOffsetInclusive" to frontier.firstOffsetInclusive.toString(),
                        "lastOffsetExclusive" to frontier.lastOffsetExclusive.toString(),
                        "coveredRanges" to frontier.coveredRanges.map { range ->
                            mapOf(
                                "firstOffsetInclusive" to range.firstOffsetInclusive.toString(),
                                "lastOffsetExclusive" to range.lastOffsetExclusive.toString()
                            )
                        }
                    )
                },
                "checksumValidatedBatches" to stats.checksumValidatedBatches,
                "legacyUncheckedBatches" to stats.legacyUncheckedBatches,
                "validatedMembershipOutcomes" to stats.validatedMembershipOutcomes,
                "canonicalCommitObservedBatches" to stats.canonicalCommitObservedBatches,
                "sourceCommitObservedBatches" to stats.sourceCommitObservedBatches,
                "canonicalCommitElapsedNanos" to stats.canonicalCommitElapsedNanos,
                "canonicalCommitMaxNanos" to stats.canonicalCommitMaxNanos,
                "sourceCommitElapsedNanos" to stats.sourceCommitElapsedNanos,
                "sourceCommitMaxNanos" to stats.sourceCommitMaxNanos,
                "sourceResidenceSamples" to stats.sourceResidenceSamples,
                "sourceResidenceElapsedMs" to stats.sourceResidenceElapsedMs,
                "sourceResidenceMaxMs" to stats.sourceResidenceMaxMs,
                "clockGuardFailures" to stats.clockGuardFailures,
                "lastSourceWorkFinishedAt" to stats.lastSourceWorkFinishedAt,
                "lastCanonicalCommitObservedAt" to stats.lastCanonicalCommitObservedAt,
                "lastSourceCommitObservedAt" to stats.lastSourceCommitObservedAt,
                "lastMaterializedAt" to stats.lastMaterializedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastError" to stats.lastError
            )
        )
    }

    fun marketDataProjectorStatusJson(includeDirtyCounts: Boolean = true): String {
        val stats = MarketDataProjectionMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to runtimeLoopStarter.marketDataProjectorShouldStart(),
            "role" to runtimeRole.configValue,
            "projectionName" to projectionConfig.marketDataProjectorProjectionName,
            "sourceProjectionName" to projectionConfig.marketDataProjectorSourceProjectionName,
            "pollIntervalMs" to projectionConfig.marketDataProjectorPollMs,
            "batchSize" to projectionConfig.marketDataProjectorBatchSize,
            "metrics" to mapOf(
                "cycles" to stats.cycles,
                "processedRows" to stats.processedRows,
                "lastProcessedRows" to stats.lastProcessedRows,
                "failed" to stats.failed,
                "lastProcessedAt" to stats.lastProcessedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastError" to stats.lastError
            ),
            "instrumentation" to downstreamProjectionInstrumentation(DownstreamProjectionStage.MarketData, includeDirtyCounts)
        )
    }

    fun orderLifecycleProjectorStatusJson(includeDirtyCounts: Boolean = true): String {
        val stats = OrderLifecycleProjectionMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to runtimeLoopStarter.orderLifecycleProjectorShouldStart(),
            "role" to runtimeRole.configValue,
            "pollIntervalMs" to projectionConfig.orderLifecycleProjectorPollMs,
            "batchSize" to projectionConfig.orderLifecycleProjectorBatchSize,
            "workers" to projectionConfig.orderLifecycleProjectorWorkers,
            "metrics" to mapOf(
                "cycles" to stats.cycles,
                "processedRows" to stats.processedRows,
                "lastProcessedRows" to stats.lastProcessedRows,
                "failed" to stats.failed,
                "lastProcessedAt" to stats.lastProcessedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastError" to stats.lastError
            ),
            "instrumentation" to downstreamProjectionInstrumentation(DownstreamProjectionStage.OrderLifecycle, includeDirtyCounts)
        )
    }

    private fun downstreamProjectionInstrumentation(stage: DownstreamProjectionStage, includeDirtyCounts: Boolean): Map<String, Any?> {
        val callerStats = DownstreamProjectionCallerMetrics.snapshot(stage)
        if (!projectionConfig.downstreamProjectionInstrumentationEnabled) {
            return mapOf(
                "enabled" to false,
                "callerStats" to callerStats.toMap()
            )
        }
        val stageEnabled = when (stage) {
            DownstreamProjectionStage.OrderLifecycle -> runtimeLoopStarter.orderLifecycleProjectorShouldStart()
            DownstreamProjectionStage.MarketData -> runtimeLoopStarter.marketDataProjectorShouldStart()
        }
        if (!stageEnabled) {
            return mapOf(
                "enabled" to true,
                "stageEnabled" to false,
                "sampleIntervalMs" to 1_000,
                "callerStats" to callerStats.toMap(),
                "coverage" to DownstreamProjectionCoverageMetrics.snapshot(stage).toMap()
            )
        }
        // Downstream lifecycle and market-data stages are global maintainers. Their
        // covering marker must therefore describe the complete source frontier,
        // even when this process owns only one canonical-projector partition slice.
        val partitions = (0 until streamCommandConfig.partitionCount).toList()
        // Read the committed prefix BEFORE the single MVCC snapshot of both queues.
        // A later empty snapshot covers this prefix even if newer source work is active.
        val sourceFrontier = api.committedProjectionFrontier(
            projectionConfig.streamAckProjectionName,
            partitions
        )
        val dirtyQueues = if (includeDirtyCounts) api.projectionDirtyQueueStats() else api.projectionDirtyQueueMarkerStats()
        DownstreamProjectionCoverageMetrics.observe(
            stage = stage,
            sourceFrontier = sourceFrontier,
            dirtyQueues = dirtyQueues,
            callerStats = callerStats
        )
        val coverage = DownstreamProjectionCoverageMetrics.snapshot(stage)
        return mapOf(
            "enabled" to true,
            "stageEnabled" to stageEnabled,
            "sampleIntervalMs" to 1_000,
            "dirtyQueues" to if (includeDirtyCounts) dirtyQueues.toMap() else dirtyQueues.toMarkerMap(),
            "callerStats" to callerStats.toMap(),
            "coverage" to coverage.toMap()
        )
    }

    private fun com.reef.platform.infrastructure.persistence.ProjectionDirtyQueueStats.toMap(): Map<String, Any> = mapOf(
        "orderLifecyclePending" to orderLifecyclePending,
        "orderLifecycleOldestDirtiedAt" to orderLifecycleOldestDirtiedAt,
        "marketDataPending" to marketDataPending,
        "marketDataOldestDirtiedAt" to marketDataOldestDirtiedAt,
        "databaseSnapshotAt" to databaseSnapshotAt,
        "databaseGeneration" to databaseGeneration
    )

    private fun com.reef.platform.infrastructure.persistence.ProjectionDirtyQueueStats.toMarkerMap(): Map<String, Any> = mapOf(
        "orderLifecycleEmpty" to (orderLifecyclePending == 0L),
        "orderLifecycleOldestDirtiedAt" to orderLifecycleOldestDirtiedAt,
        "marketDataEmpty" to (marketDataPending == 0L),
        "marketDataOldestDirtiedAt" to marketDataOldestDirtiedAt,
        "databaseSnapshotAt" to databaseSnapshotAt,
        "databaseGeneration" to databaseGeneration
    )

    private fun DownstreamProjectionCallerStats.toMap(): Map<String, Any> = mapOf(
        "calls" to calls,
        "completed" to completed,
        "failed" to failed,
        "active" to active,
        "maxConcurrent" to maxConcurrent,
        "callerCount" to callers.size,
        "callers" to callers
    )

    private fun DownstreamProjectionCoverageStats.toMap(): Map<String, Any?> = mapOf(
        "markerCount" to markerCount,
        "databaseGeneration" to databaseGeneration,
        "generationGuardFailures" to generationGuardFailures,
        "clockGuardFailures" to clockGuardFailures,
        "lastMarker" to lastMarker?.let { marker ->
            mapOf(
                "markerId" to marker.markerId,
                "stage" to marker.stage.name,
                "sourceProjectionName" to marker.sourceProjectionName,
                "databaseGeneration" to marker.databaseGeneration,
                "sourceWatermarks" to marker.sourceWatermarks.map { watermark ->
                    mapOf(
                        "partition" to watermark.partitionId,
                        "lastPartitionSequence" to watermark.lastPartitionSequence.toString()
                    )
                },
                "prefixRecordedAt" to marker.prefixRecordedAt,
                "lifecycleCoveredAt" to marker.lifecycleCoveredAt,
                "databaseSnapshotAt" to marker.databaseSnapshotAt,
                "postCommitObservedAt" to marker.postCommitObservedAt,
                "postCommitObserved" to marker.postCommitObserved,
                "callerCount" to marker.callerCount,
                "callers" to marker.callers
            )
        }
    )

    private fun streamCommandBackpressureWorkerDurableNames(): List<String> {
        return streamCommandBackpressureWorkerDurables
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
