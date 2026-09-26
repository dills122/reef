package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.MarketAdvanceResult
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.PostMatchLiveEffectWriter
import com.reef.platform.infrastructure.persistence.PostMatchMarketMaintainer
import com.reef.platform.infrastructure.persistence.PostMatchOperationalStore
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostMatchApplyResult
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Opt-in shadow consumers. Live and market stages poll independently and commit separate frontiers. */
internal class PostMatchRuntimeWorkers(
    private val catalog: PostMatchSourceCatalog,
    private val reader: PostgresCanonicalOutcomeSourceReader,
    private val store: PostMatchOperationalStore,
    private val writer: PostMatchLiveEffectWriter,
    private val market: PostMatchMarketMaintainer,
    private val eventStream: String,
    private val partitions: List<Int>,
    private val batchSize: Int,
    private val pollMs: Long
) {
    private val running = AtomicBoolean(false)

    init {
        require(eventStream.isNotBlank() && partitions.isNotEmpty() && partitions.distinct().size == partitions.size)
        require(partitions.all { it >= 0 && it <= 32767 })
        require(batchSize in 1..5000 && pollMs > 0)
    }

    fun processLiveOnce(): Int {
        val generation = catalog.generation()
        return partitions.sumOf { processLivePartition(it, generation) }
    }

    fun processMarketOnce(): Int {
        val generation = catalog.generation()
        return partitions.sumOf { processMarketPartition(it, generation) }
    }

    private fun processLivePartition(partition: Int, generation: String): Int {
        val frontier = store.lastCommittedSequence(LIVE_CONSUMER, eventStream, partition, generation)
        val origin = CanonicalStreamPosition.origin(partition)
        check(frontier >= origin) { "live post-match frontier predates partition origin" }
        val window = reader.readNextWindow(
            LIVE_CONSUMER, eventStream, partition, generation, frontier, batchSize
        ) ?: return 0
        return if (store.apply(window, writer::apply) == PostMatchApplyResult.APPLIED) window.outcomes.size else 0
    }

    private fun processMarketPartition(partition: Int, generation: String): Int =
        if (market.applyNext(eventStream, partition, generation) == MarketAdvanceResult.APPLIED) 1 else 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runLoop("reef-postmatch-live-worker", ::processLivePartition)
        runLoop("reef-postmatch-market-worker", ::processMarketPartition)
    }

    fun stop() { running.set(false) }

    private fun runLoop(name: String, process: (Int, String) -> Int) {
        thread(name = name, isDaemon = true) {
            var sourceFailure = ""
            val failures = mutableMapOf<Int, String>()
            val retryAfter = mutableMapOf<Int, Long>()
            while (running.get()) {
                val generation = try {
                    catalog.generation().also {
                        if (sourceFailure.isNotEmpty()) System.err.println("postmatch_worker_recovered worker=$name source=true")
                        sourceFailure = ""
                    }
                } catch (error: Exception) {
                    val failure = error.message ?: error::class.simpleName ?: "unknown"
                    if (failure != sourceFailure) {
                        System.err.println("postmatch_worker_failed worker=$name source=true reason=$failure")
                        sourceFailure = failure
                    }
                    Thread.sleep(maxOf(pollMs, 1000L))
                    continue
                }
                var count = 0
                partitions.forEach { partition ->
                    if (System.currentTimeMillis() < (retryAfter[partition] ?: 0L)) return@forEach
                    try {
                        count += process(partition, generation)
                        if (failures.remove(partition) != null) {
                            System.err.println("postmatch_worker_recovered worker=$name partition=$partition")
                        }
                        retryAfter.remove(partition)
                    } catch (error: Exception) {
                        val failure = error.message ?: error::class.simpleName ?: "unknown"
                        if (failure != failures[partition]) {
                            System.err.println("postmatch_worker_failed worker=$name partition=$partition reason=$failure")
                            failures[partition] = failure
                        }
                        retryAfter[partition] = System.currentTimeMillis() + maxOf(pollMs, 1000L)
                    }
                }
                if (count == 0) Thread.sleep(pollMs)
            }
        }
    }

    companion object {
        const val LIVE_CONSUMER = "live-v1"

        fun fromEnv(): PostMatchRuntimeWorkers {
            val eventStream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val partitionList = RuntimeEnv.string("POSTMATCH_WORKER_PARTITIONS",
                RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", ""))
            require(partitionList.isNotBlank()) { "post-match shadow workers require assigned partitions" }
            val partitions = partitionList.split(',').map { it.trim().toInt() }
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val targetUrl = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_JDBC_URL", "")
            require(sourceUrl.isNotBlank() && targetUrl.isNotBlank()) { "post-match source and target JDBC URLs are required" }
            val sourceUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            val sourcePassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
            val targetUser = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_USER", sourceUser)
            val targetPassword = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_PASSWORD", sourcePassword)
            val source = RuntimeDataSources.dataSource(sourceUrl, sourceUser, sourcePassword, "postmatch-source")
            val target = RuntimeDataSources.dataSource(targetUrl, targetUser, targetPassword, "postmatch-operational")
            return PostMatchRuntimeWorkers(
                PostMatchSourceCatalog(source), PostgresCanonicalOutcomeSourceReader(source),
                PostMatchOperationalStore(target), PostMatchLiveEffectWriter(), PostMatchMarketMaintainer(target),
                eventStream, partitions,
                RuntimeEnv.int("POSTMATCH_WORKER_BATCH_SIZE", 500, min = 1),
                RuntimeEnv.long("POSTMATCH_WORKER_POLL_MS", 50, min = 1)
            )
        }
    }
}
