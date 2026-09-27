package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchApplyResult
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementBoundedTransitionStore
import com.reef.platform.infrastructure.persistence.SettlementTransitionWindowTooLarge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Default-off settlement transition over committed shadow obligations. */
internal class PostMatchSettlementTransitionWorker(
    private val catalog: PostMatchSourceCatalog,
    private val store: SettlementBoundedTransitionStore,
    private val eventStream: String,
    private val partitions: List<Int>,
    private val batchSize: Int,
    private val maxObligations: Int,
    private val pollMs: Long,
    private val workerCount: Int = minOf(partitions.size, 4)
) {
    private val running = AtomicBoolean(false)
    private val workerThreads = mutableListOf<Thread>()
    internal data class Progress(val obligations: Int, val advanced: Boolean)

    init {
        require(eventStream.isNotBlank() && partitions.isNotEmpty() && partitions.distinct().size == partitions.size)
        require(partitions.all { it in 0..32767 } && batchSize in 1..5000 &&
            maxObligations in 1..20_000 && pollMs > 0 && workerCount in 1..32)
    }

    fun processOnce(): Int = processOnceProgress().obligations

    internal fun processOnceProgress(): Progress {
        val generation = catalog.generation()
        val progress = partitions.map { processPartition(it, generation) }
        return Progress(progress.sumOf { it.obligations }, progress.any { it.advanced })
    }

    private fun processPartition(partition: Int, generation: String): Progress {
        fun execute(): Progress {
            check(catalog.generation() == generation) { "settlement source generation changed before execution" }
            val window = store.readNextAdmittedWindow(eventStream, partition, generation)
                ?: return Progress(0, false)
            check(catalog.generation() == generation) { "settlement source generation changed during execution" }
            return if (store.apply(window) == PostMatchApplyResult.APPLIED)
                Progress(window.obligations.size, true) else Progress(0, false)
        }
        val firstExecution = execute()
        var positions = batchSize
        var admitted = false
        while (true) {
            check(catalog.generation() == generation) { "settlement source generation changed before admission read" }
            val window = try {
                store.readNextAdmissionWindow(eventStream, partition, generation, positions, maxObligations)
            } catch (error: SettlementTransitionWindowTooLarge) {
                if (positions == 1) throw error
                positions = maxOf(1, positions / 2)
                continue
            }
            if (window != null) {
                check(catalog.generation() == generation) { "settlement source generation changed during admission" }
                admitted = store.admit(window) == PostMatchApplyResult.APPLIED
            }
            break
        }
        val nextExecution = if (firstExecution.advanced) Progress(0, false) else execute()
        return Progress(firstExecution.obligations + nextExecution.obligations,
            firstExecution.advanced || nextExecution.advanced || admitted)
    }

    @Synchronized
    fun start() {
        if (running.get()) return
        check(workerThreads.none { it.isAlive }) { "settlement transition workers have not stopped" }
        running.set(true)
        workerThreads.clear()
        val activeWorkers = minOf(workerCount, partitions.size)
        repeat(activeWorkers) { workerIndex ->
            val assigned = partitions.filterIndexed { index, _ -> index % activeWorkers == workerIndex }
            workerThreads += thread(name = "reef-postmatch-settlement-transition-$workerIndex", isDaemon = true) {
                var sourceFailure = ""
                val failures = mutableMapOf<Int, String>()
                val retryAfter = mutableMapOf<Int, Long>()
                while (running.get()) {
                    val generation = try {
                        catalog.generation().also {
                            if (sourceFailure.isNotEmpty()) System.err.println("postmatch_transition_recovered source=true")
                            sourceFailure = ""
                        }
                    } catch (error: Exception) {
                        val failure = error.message ?: error::class.simpleName ?: "unknown"
                        if (failure != sourceFailure) System.err.println("postmatch_transition_failed source=true reason=$failure")
                        sourceFailure = failure
                        Thread.sleep(maxOf(pollMs, 1000L))
                        continue
                    }
                    var advanced = false
                    assigned.forEach { partition ->
                        if (System.currentTimeMillis() < (retryAfter[partition] ?: 0L)) return@forEach
                        try {
                            advanced = processPartition(partition, generation).advanced || advanced
                            if (failures.remove(partition) != null) {
                                System.err.println("postmatch_transition_recovered partition=$partition")
                            }
                            retryAfter.remove(partition)
                        } catch (error: Exception) {
                            val failure = error.message ?: error::class.simpleName ?: "unknown"
                            if (failure != failures[partition]) {
                                System.err.println("postmatch_transition_failed partition=$partition reason=$failure")
                                failures[partition] = failure
                            }
                            retryAfter[partition] = System.currentTimeMillis() + maxOf(pollMs, 1000L)
                        }
                    }
                    if (!advanced) Thread.sleep(pollMs)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        workerThreads.forEach { it.join(minOf(maxOf(pollMs, 1000L) + 500L, 5000L)) }
        workerThreads.removeAll { !it.isAlive }
    }

    companion object {
        fun fromEnv(): PostMatchSettlementTransitionWorker {
            val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val partitionList = RuntimeEnv.string("POSTMATCH_WORKER_PARTITIONS",
                RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", ""))
            require(partitionList.isNotBlank()) { "settlement transition requires assigned partitions" }
            val partitions = partitionList.split(',').map { part ->
                val trimmed = part.trim()
                require(trimmed.isNotEmpty()) { "settlement transition requires non-empty partitions" }
                trimmed.toIntOrNull() ?: error("settlement transition requires numeric partitions: $part")
            }
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val targetUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
            require(sourceUrl.isNotBlank() && targetUrl.isNotBlank() && sourceUrl != targetUrl) {
                "settlement transition requires distinct canonical source and settlement PostgreSQL URLs"
            }
            val sourceUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            val sourcePassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
            val source = RuntimeDataSources.dataSource(sourceUrl, sourceUser, sourcePassword, "settlement-transition-source")
            val target = RuntimeDataSources.dataSource(
                targetUrl, RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", "").ifBlank { sourceUser },
                RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", "").ifBlank { sourcePassword },
                "settlement-transition-target"
            )
            return PostMatchSettlementTransitionWorker(
                PostMatchSourceCatalog(source), SettlementBoundedTransitionStore(target), stream, partitions,
                RuntimeEnv.int("POSTMATCH_SETTLEMENT_TRANSITION_BATCH_SIZE", 100, min = 1),
                RuntimeEnv.int("POSTMATCH_SETTLEMENT_TRANSITION_MAX_OBLIGATIONS", 1000, min = 1),
                RuntimeEnv.long("POSTMATCH_SETTLEMENT_TRANSITION_POLL_MS", 50, min = 1),
                RuntimeEnv.int("POSTMATCH_SETTLEMENT_TRANSITION_WORKERS", minOf(partitions.size, 4), min = 1)
            )
        }
    }
}
