package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchApplyResult
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementBoundedTransitionStore
import com.reef.platform.infrastructure.persistence.SettlementAdmissionTiming
import com.reef.platform.infrastructure.persistence.SettlementExecutionReadiness
import com.reef.platform.infrastructure.persistence.SettlementTransitionWindowTooLarge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
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
    internal data class Metrics(
        val readinessChecks: Long, val readinessNone: Long, val readinessBlocked: Long,
        val readinessReady: Long, val readinessNanos: Long, val admissionReads: Long,
        val admissionReadNanos: Long, val admissionWrites: Long, val admissionWriteNanos: Long,
        val executionReads: Long, val executionReadNanos: Long, val executionWrites: Long,
        val executionWriteNanos: Long, val admittedWindows: Long, val appliedWindows: Long,
        val appliedObligations: Long, val counterCallNanos: Long, val maxCounterCallNanos: Long,
        val maxPredecessorCount: Long, val blockedPartitions: Int, val oldestBlockedMillis: Long
    )
    private val readinessChecks = AtomicLong()
    private val readinessNone = AtomicLong()
    private val readinessBlocked = AtomicLong()
    private val readinessReady = AtomicLong()
    private val readinessNanos = AtomicLong()
    private val admissionReads = AtomicLong()
    private val admissionReadNanos = AtomicLong()
    private val admissionWrites = AtomicLong()
    private val admissionWriteNanos = AtomicLong()
    private val executionReads = AtomicLong()
    private val executionReadNanos = AtomicLong()
    private val executionWrites = AtomicLong()
    private val executionWriteNanos = AtomicLong()
    private val admittedWindows = AtomicLong()
    private val appliedWindows = AtomicLong()
    private val appliedObligations = AtomicLong()
    private val counterCallNanos = AtomicLong()
    private val maxCounterCallNanos = AtomicLong()
    private val maxPredecessorCount = AtomicLong()
    private val blockedSince = ConcurrentHashMap<Int, Long>()

    internal fun metrics(): Metrics = Metrics(readinessChecks.get(), readinessNone.get(), readinessBlocked.get(),
        readinessReady.get(), readinessNanos.get(), admissionReads.get(), admissionReadNanos.get(),
        admissionWrites.get(), admissionWriteNanos.get(), executionReads.get(), executionReadNanos.get(),
        executionWrites.get(), executionWriteNanos.get(), admittedWindows.get(), appliedWindows.get(),
        appliedObligations.get(), counterCallNanos.get(), maxCounterCallNanos.get(),
        maxPredecessorCount.get(), blockedSince.size,
        blockedSince.values.maxOfOrNull { (System.nanoTime() - it) / 1_000_000L } ?: 0L)

    private inline fun <T> timed(count: AtomicLong, nanos: AtomicLong, block: () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally {
            nanos.addAndGet(System.nanoTime() - started)
            count.incrementAndGet()
        }
    }

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
            val readiness = timed(readinessChecks, readinessNanos) {
                store.nextExecutionReadiness(eventStream, partition, generation)
            }
            when (readiness) {
                SettlementExecutionReadiness.NONE -> {
                    blockedSince.remove(partition)
                    readinessNone.incrementAndGet()
                    return Progress(0, false)
                }
                SettlementExecutionReadiness.BLOCKED -> {
                    // The earliest admitted window owns this partition until its predecessors complete.
                    blockedSince.putIfAbsent(partition, System.nanoTime())
                    readinessBlocked.incrementAndGet()
                    return Progress(0, false)
                }
                SettlementExecutionReadiness.READY -> {
                    blockedSince.remove(partition)
                    readinessReady.incrementAndGet()
                }
            }
            val window = timed(executionReads, executionReadNanos) {
                store.readNextAdmittedWindow(eventStream, partition, generation)
            }
                ?: error("settlement ready admission disappeared before execution")
            check(catalog.generation() == generation) { "settlement source generation changed during execution" }
            return if (timed(executionWrites, executionWriteNanos) { store.apply(window) } == PostMatchApplyResult.APPLIED) {
                appliedWindows.incrementAndGet()
                appliedObligations.addAndGet(window.obligations.size.toLong())
                Progress(window.obligations.size, true)
            } else Progress(0, false)
        }
        val firstExecution = execute()
        var positions = batchSize
        var admitted = false
        while (true) {
            check(catalog.generation() == generation) { "settlement source generation changed before admission read" }
            val window = try {
                timed(admissionReads, admissionReadNanos) {
                    store.readNextAdmissionWindow(eventStream, partition, generation, positions, maxObligations)
                }
            } catch (error: SettlementTransitionWindowTooLarge) {
                if (positions == 1) throw error
                positions = maxOf(1, positions / 2)
                continue
            }
            if (window != null) {
                check(catalog.generation() == generation) { "settlement source generation changed during admission" }
                val timing = SettlementAdmissionTiming()
                try {
                    admitted = timed(admissionWrites, admissionWriteNanos) {
                        store.admit(window, timing)
                    } == PostMatchApplyResult.APPLIED
                } finally {
                    if (timing.counterCallNanos > 0) {
                        counterCallNanos.addAndGet(timing.counterCallNanos)
                        maxCounterCallNanos.accumulateAndGet(timing.counterCallNanos) { current, next -> maxOf(current, next) }
                    }
                    maxPredecessorCount.accumulateAndGet(timing.predecessorCount.toLong()) { current, next -> maxOf(current, next) }
                }
                if (admitted) admittedWindows.incrementAndGet()
            }
            break
        }
        val nextExecution = if (firstExecution.advanced || !admitted) Progress(0, false) else execute()
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
                var nextMetricsLog = System.currentTimeMillis() + 10_000L
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
                    if (workerIndex == 0 && System.currentTimeMillis() >= nextMetricsLog) {
                        val value = metrics()
                        System.err.println("postmatch_transition_metrics sampledAt=${System.currentTimeMillis()} " +
                            "readinessChecks=${value.readinessChecks} readinessNone=${value.readinessNone} " +
                            "readinessBlocked=${value.readinessBlocked} readinessReady=${value.readinessReady} " +
                            "readinessNanos=${value.readinessNanos} admissionReads=${value.admissionReads} " +
                            "admissionReadNanos=${value.admissionReadNanos} admissionWrites=${value.admissionWrites} " +
                            "admissionWriteNanos=${value.admissionWriteNanos} admittedWindows=${value.admittedWindows} " +
                            "executionReads=${value.executionReads} executionReadNanos=${value.executionReadNanos} " +
                            "executionWrites=${value.executionWrites} executionWriteNanos=${value.executionWriteNanos} " +
                            "appliedWindows=${value.appliedWindows} appliedObligations=${value.appliedObligations} " +
                            "counterCallNanos=${value.counterCallNanos} " +
                            "maxCounterCallNanos=${value.maxCounterCallNanos} " +
                            "maxPredecessorCount=${value.maxPredecessorCount} " +
                            "blockedPartitions=${value.blockedPartitions} " +
                            "oldestBlockedMillis=${value.oldestBlockedMillis}")
                        nextMetricsLog = System.currentTimeMillis() + 10_000L
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
