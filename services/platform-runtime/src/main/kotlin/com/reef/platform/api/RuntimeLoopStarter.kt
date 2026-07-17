package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.defaultRuntimePersistence
import com.reef.platform.infrastructure.persistence.ProjectionStage
import java.time.Duration

/**
 * Starts the background worker/projector threads (stream-ack workers, canonical
 * projector, market-data/order-lifecycle projectors, venue-event materializer) and
 * exposes the "should this role run X" gates that DiagnosticsGateway also reports on.
 * Does not own the drain-backpressure sampler thread — that shares volatile snapshot
 * state with the mutation dispatcher's backpressure gating and stays there.
 */
internal class RuntimeLoopStarter(
    private val api: PlatformApi,
    private val runtimeRole: PlatformRuntimeRole,
    private val commandProcessingMode: CommandProcessingMode,
    private val streamCommandConfig: StreamCommandConfig,
    private val streamCommandIntakeStore: StreamCommandIntakeStore?,
    private val streamCommandWorkerBatchSize: Int,
    private val streamCommandWorkerPollMs: Long,
    private val streamCommandWorkerFetchTimeoutMs: Long,
    private val streamCommandWorkerDedicatedRuntimePoolEnabled: Boolean,
    private val streamCommandWorkerPartitions: String,
    private val streamAckProjectorPartitions: String,
    private val streamAckProjectionName: String,
    private val streamAckProjectionSource: CanonicalProjectionSource,
    private val streamAckProjectionEventStream: String,
    private val streamAckProjectionStage: ProjectionStage,
    private val streamAckProjectorBatchSize: Int,
    private val streamAckProjectorPollMs: Long,
    private val marketDataProjectorEnabled: Boolean,
    private val marketDataProjectorProjectionName: String,
    private val marketDataProjectorSourceProjectionName: String,
    private val marketDataProjectorPollMs: Long,
    private val marketDataProjectorBatchSize: Int,
    private val orderLifecycleProjectorEnabled: Boolean,
    private val orderLifecycleProjectorPollMs: Long,
    private val orderLifecycleProjectorBatchSize: Int,
    private val venueEventMaterializerEnabled: Boolean,
    private val venueEventMaterializerBatchSize: Int,
    private val venueEventMaterializerPollMs: Long,
    private val venueEventMaterializerFetchTimeoutMs: Long
) {
    fun startStreamCommandWorkers() {
        val partitions = streamWorkerPartitions()
        if (partitions.isEmpty()) {
            System.err.println("stream_command_worker_unavailable reason=no_partitions_configured")
            return
        }
        val workerApi = streamCommandWorkerApi()
        partitions.forEach { partition ->
            val source = StreamCommandWorkerFactory.sourceForPartition(streamCommandConfig, partition)
            if (source is StreamCommandTelemetrySource) {
                StreamCommandWorkerMetrics.registerConsumerTelemetry(partition, source)
            }
            StreamCommandWorker(
                source = source,
                api = workerApi,
                publicationMarker = streamCommandIntakeStore,
                partition = partition,
                batchSize = streamCommandWorkerBatchSize,
                pollIntervalMs = streamCommandWorkerPollMs,
                fetchTimeout = Duration.ofMillis(streamCommandWorkerFetchTimeoutMs),
                workerName = "reef-stream-command-worker-p$partition"
            ).start()
        }
    }

    fun startCanonicalProjector() {
        val partitions = projectorPartitions()
        if (partitions.isEmpty()) {
            System.err.println("canonical_projector_unavailable reason=no_partitions_configured")
            return
        }
        CanonicalProjectionWorker(
            api = api,
            projectionName = streamAckProjectionName,
            projectionSource = streamAckProjectionSource,
            eventStream = streamAckProjectionEventStream,
            projectionStage = streamAckProjectionStage,
            partitions = partitions,
            batchSize = streamAckProjectorBatchSize,
            pollIntervalMs = streamAckProjectorPollMs,
            workerName = "reef-canonical-projector-$streamAckProjectionName"
        ).start()
    }

    fun marketDataProjectorShouldStart(): Boolean {
        return runtimeRole == PlatformRuntimeRole.Projector && marketDataProjectorEnabled
    }

    fun startMarketDataProjector() {
        MarketDataProjectionWorker(
            api = api,
            projectionName = marketDataProjectorProjectionName,
            sourceProjectionName = marketDataProjectorSourceProjectionName,
            pollIntervalMs = marketDataProjectorPollMs,
            batchSize = marketDataProjectorBatchSize,
            workerName = "reef-market-data-projector-$marketDataProjectorProjectionName"
        ).start()
    }

    fun orderLifecycleProjectorShouldStart(): Boolean {
        return runtimeRole == PlatformRuntimeRole.Projector && orderLifecycleProjectorEnabled
    }

    fun startOrderLifecycleProjector() {
        OrderLifecycleProjectionWorker(
            api = api,
            pollIntervalMs = orderLifecycleProjectorPollMs,
            batchSize = orderLifecycleProjectorBatchSize,
            workerName = "reef-order-lifecycle-projector"
        ).start()
    }

    fun venueEventMaterializerShouldStart(): Boolean {
        return commandProcessingMode == CommandProcessingMode.StreamAck &&
            runtimeRole == PlatformRuntimeRole.Materializer &&
            venueEventMaterializerEnabled
    }

    fun startVenueEventMaterializer() {
        val provider = StreamCommandLogProvider.fromEnv()
        if (provider != StreamCommandLogProvider.Redpanda) {
            System.err.println("venue_event_materializer_unavailable reason=unsupported_log_provider provider=${provider.configValue}")
            return
        }
        VenueEventBatchMaterializer(
            source = venueEventBatchSourceWithLocalFaultHooks(KafkaVenueEventBatchSource()),
            api = api,
            batchSize = venueEventMaterializerBatchSize,
            pollIntervalMs = venueEventMaterializerPollMs,
            fetchTimeout = Duration.ofMillis(venueEventMaterializerFetchTimeoutMs),
            workerName = "reef-venue-event-batch-materializer"
        ).start()
    }

    fun projectorPartitions(): List<Int> {
        return configuredRuntimePartitions(streamAckProjectorPartitions, streamCommandConfig.partitionCount)
    }

    fun streamWorkerPartitions(): List<Int> {
        return configuredRuntimePartitions(streamCommandWorkerPartitions, streamCommandConfig.partitionCount)
    }

    private fun streamCommandWorkerApi(): PlatformApi {
        if (!streamCommandWorkerDedicatedRuntimePoolEnabled) return api
        return PlatformApi(
            OrderApplicationService(
                runtimePersistence = defaultRuntimePersistence("stream-runtime")
            )
        )
    }
}
