package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchApplyResult
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementCanonicalIntakeStore
import com.reef.platform.infrastructure.persistence.SettlementIntakeWindowTooLarge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Opt-in trade intake. Workflow and ledger cutover remain separate gates. */
internal class PostMatchSettlementIntakeWorker(
    private val catalog: PostMatchSourceCatalog,
    private val reader: PostgresCanonicalOutcomeSourceReader,
    private val store: SettlementCanonicalIntakeStore,
    private val eventStream: String,
    private val partitions: List<Int>,
    private val batchSize: Int,
    private val pollMs: Long,
    private val maxResultBytes: Long = 16L * 1024 * 1024
) {
    private val running = AtomicBoolean(false)

    init {
        require(eventStream.isNotBlank() && partitions.isNotEmpty() && partitions.distinct().size == partitions.size)
        require(partitions.all { it in 0..32767 } && batchSize in 1..5000 && pollMs > 0 && maxResultBytes > 0)
    }

    fun processOnce(): Int {
        val generation = catalog.generation()
        return partitions.sumOf { processPartition(it, generation) }
    }

    private fun processPartition(partition: Int, generation: String): Int {
        val frontier = store.lastCommittedSequence(eventStream, partition, generation)
        check(frontier >= CanonicalStreamPosition.origin(partition)) { "settlement frontier predates partition origin" }
        check(catalog.generation() == generation) { "settlement source generation changed before processing" }
        var maxOutcomes = batchSize
        while (true) {
            val window = reader.readNextWindow(
                SettlementCanonicalIntakeStore.CONSUMER, eventStream, partition, generation, frontier,
                maxOutcomes, maxResultBytes
            ) ?: return 0
            check(catalog.generation() == generation) { "settlement source generation changed during processing" }
            try {
                return if (store.apply(window) == PostMatchApplyResult.APPLIED) window.outcomes.size else 0
            } catch (error: SettlementIntakeWindowTooLarge) {
                if (window.outcomes.size == 1) throw error
                maxOutcomes = maxOf(1, window.outcomes.size / 2)
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "reef-postmatch-settlement-intake", isDaemon = true) {
            var sourceFailure = ""
            val failures = mutableMapOf<Int, String>()
            val retryAfter = mutableMapOf<Int, Long>()
            while (running.get()) {
                val generation = try {
                    catalog.generation().also {
                        if (sourceFailure.isNotEmpty()) System.err.println("postmatch_settlement_recovered source=true")
                        sourceFailure = ""
                    }
                } catch (error: Exception) {
                    val failure = error.message ?: error::class.simpleName ?: "unknown"
                    if (failure != sourceFailure) System.err.println("postmatch_settlement_failed source=true reason=$failure")
                    sourceFailure = failure
                    Thread.sleep(maxOf(pollMs, 1000L))
                    continue
                }
                var count = 0
                partitions.forEach { partition ->
                    if (System.currentTimeMillis() < (retryAfter[partition] ?: 0L)) return@forEach
                    try {
                        count += processPartition(partition, generation)
                        if (failures.remove(partition) != null) {
                            System.err.println("postmatch_settlement_recovered partition=$partition")
                        }
                        retryAfter.remove(partition)
                    } catch (error: Exception) {
                        val failure = error.message ?: error::class.simpleName ?: "unknown"
                        if (failure != failures[partition]) {
                            System.err.println("postmatch_settlement_failed partition=$partition reason=$failure")
                            failures[partition] = failure
                        }
                        retryAfter[partition] = System.currentTimeMillis() + maxOf(pollMs, 1000L)
                    }
                }
                if (count == 0) Thread.sleep(pollMs)
            }
        }
    }

    fun stop() { running.set(false) }

    companion object {
        fun fromEnv(): PostMatchSettlementIntakeWorker {
            val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val partitionList = RuntimeEnv.string("POSTMATCH_WORKER_PARTITIONS",
                RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", ""))
            require(partitionList.isNotBlank()) { "settlement intake requires assigned partitions" }
            val partitions = partitionList.split(',').map { part ->
                val trimmed = part.trim()
                require(trimmed.isNotEmpty()) { "settlement intake requires non-empty partitions" }
                trimmed.toIntOrNull() ?: error("settlement intake requires numeric partitions: $part")
            }
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val targetUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "").ifBlank { sourceUrl }
            require(sourceUrl.isNotBlank() && targetUrl.isNotBlank()) {
                "settlement intake requires canonical source and settlement PostgreSQL"
            }
            val sourceUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            val sourcePassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
            val source = RuntimeDataSources.dataSource(sourceUrl, sourceUser, sourcePassword, "settlement-intake-source")
            val target = RuntimeDataSources.dataSource(
                targetUrl, RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", "").ifBlank { sourceUser },
                RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", "").ifBlank { sourcePassword },
                "settlement-intake-target"
            )
            return PostMatchSettlementIntakeWorker(
                PostMatchSourceCatalog(source), PostgresCanonicalOutcomeSourceReader(source),
                SettlementCanonicalIntakeStore(
                    target, RuntimeEnv.int("POSTMATCH_SETTLEMENT_MAX_EFFECTS", 20_000, min = 1)
                ), stream, partitions,
                RuntimeEnv.int("POSTMATCH_SETTLEMENT_BATCH_SIZE", 500, min = 1),
                RuntimeEnv.long("POSTMATCH_SETTLEMENT_POLL_MS", 50, min = 1),
                RuntimeEnv.long("POSTMATCH_SETTLEMENT_MAX_RESULT_BYTES", 16L * 1024 * 1024, min = 1)
            )
        }
    }
}
