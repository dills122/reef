package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv

/** Bounded SQL consumers sharing one maintainer's caller and coverage metrics. */
internal class OrderLifecycleProjectionWorkerGroup(
    api: PlatformApi,
    workerCount: Int = 1,
    pollIntervalMs: Long = 250L,
    batchSize: Int = 500,
    instrumentationEnabled: Boolean = RuntimeEnv.bool("PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED", false)
) {
    private val workers = List(workerCount.also { require(it in 1..32) { "lifecycle worker count must be 1..32" } }) { index ->
        OrderLifecycleProjectionWorker(
            api = api,
            pollIntervalMs = pollIntervalMs,
            batchSize = batchSize,
            workerName = "reef-order-lifecycle-projector-$index",
            instrumentationEnabled = instrumentationEnabled
        )
    }

    @Synchronized
    fun start() = workers.forEach { it.start() }

    @Synchronized
    fun stop() = workers.forEach { it.stop() }
}
