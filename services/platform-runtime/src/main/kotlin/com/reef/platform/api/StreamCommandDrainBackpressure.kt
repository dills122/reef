package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.ProjectionStatus

data class StreamCommandDrainBackpressureSnapshot(
    val maxWorkerStreamLag: Long,
    val workerSamples: Int,
    val workerErrors: List<String>,
    val projectorLag: Long,
    val projectionName: String
) {
    fun backpressure(maxWorkerStreamLag: Long, maxProjectorLag: Long): BoundaryError? {
        if (maxWorkerStreamLag > 0L && this.maxWorkerStreamLag >= maxWorkerStreamLag) {
            return BoundaryError(
                429,
                "STREAM_COMMAND_WORKER_BACKPRESSURE",
                "stream command intake rejected because worker stream lag is ${this.maxWorkerStreamLag}"
            )
        }
        if (maxProjectorLag > 0L && projectorLag >= maxProjectorLag) {
            return BoundaryError(
                429,
                "STREAM_COMMAND_PROJECTOR_BACKPRESSURE",
                "stream command intake rejected because projection lag is $projectorLag"
            )
        }
        return null
    }
}

class StreamCommandDrainBackpressureSampler(
    private val workerSources: List<StreamCommandTelemetrySource>,
    private val projectionStatusProvider: (() -> ProjectionStatus?)? = null
) {
    fun snapshot(): StreamCommandDrainBackpressureSnapshot {
        val workerSnapshots = workerSources.map { source ->
            try {
                source.consumerSnapshot()
            } catch (ex: Exception) {
                StreamCommandConsumerSnapshot(
                    partition = -1,
                    durableName = "",
                    filterSubject = "",
                    error = ex.message ?: ex::class.simpleName ?: "unknown"
                )
            }
        }
        val projectionStatus = projectionStatusProvider?.invoke()
        return StreamCommandDrainBackpressureSnapshot(
            maxWorkerStreamLag = workerSnapshots
                .filter { it.error.isBlank() }
                .maxOfOrNull { it.streamLag } ?: 0L,
            workerSamples = workerSnapshots.size,
            workerErrors = workerSnapshots.mapNotNull { snapshot ->
                snapshot.error.ifBlank { null }
            },
            projectorLag = projectionStatus?.lag ?: 0L,
            projectionName = projectionStatus?.projectionName.orEmpty()
        )
    }
}
