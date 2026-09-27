package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchApplyResult
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementBoundedObligationStore
import com.reef.platform.infrastructure.persistence.SettlementObligationPolicySource
import com.reef.platform.infrastructure.persistence.SettlementObligationWindowTooLarge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Opt-in obligation projection. Workflow and ledger finality remain separate gates. */
internal class PostMatchSettlementObligationWorker(
    private val catalog: PostMatchSourceCatalog,
    private val policySource: SettlementObligationPolicySource,
    private val store: SettlementBoundedObligationStore,
    private val eventStream: String,
    private val partitions: List<Int>,
    private val batchSize: Int,
    private val maxTrades: Int,
    private val pollMs: Long
) {
    private val running = AtomicBoolean(false)
    internal data class Progress(val trades: Int, val advanced: Boolean)

    init {
        require(eventStream.isNotBlank() && partitions.isNotEmpty() && partitions.distinct().size == partitions.size)
        require(partitions.all { it in 0..32767 } && batchSize in 1..5000 && maxTrades in 1..20_000 && pollMs > 0)
    }

    fun processOnce(): Int = processOnceProgress().trades

    internal fun processOnceProgress(): Progress {
        val generation = catalog.generation()
        val progress = partitions.map { processPartition(it, generation) }
        return Progress(progress.sumOf { it.trades }, progress.any { it.advanced })
    }

    private fun processPartition(partition: Int, generation: String): Progress {
        var positions = batchSize
        while (true) {
            check(catalog.generation() == generation) { "settlement source generation changed before obligation read" }
            val window = try {
                store.readNextWindow(eventStream, partition, generation, positions, maxTrades)
            } catch (error: SettlementObligationWindowTooLarge) {
                if (positions == 1) throw error
                positions = maxOf(1, positions / 2)
                continue
            } ?: return Progress(0, false)
            val selections = policySource.resolve(window.trades.mapTo(mutableSetOf()) { it.policyKey })
            check(catalog.generation() == generation) { "settlement source generation changed during obligation processing" }
            return if (store.apply(window, selections) == PostMatchApplyResult.APPLIED)
                Progress(window.trades.size, true) else Progress(0, false)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "reef-postmatch-settlement-obligations", isDaemon = true) {
            var sourceFailure = ""
            val failures = mutableMapOf<Int, String>()
            val retryAfter = mutableMapOf<Int, Long>()
            while (running.get()) {
                val generation = try {
                    catalog.generation().also {
                        if (sourceFailure.isNotEmpty()) System.err.println("postmatch_obligations_recovered source=true")
                        sourceFailure = ""
                    }
                } catch (error: Exception) {
                    val failure = error.message ?: error::class.simpleName ?: "unknown"
                    if (failure != sourceFailure) System.err.println("postmatch_obligations_failed source=true reason=$failure")
                    sourceFailure = failure
                    Thread.sleep(maxOf(pollMs, 1000L))
                    continue
                }
                var advanced = false
                partitions.forEach { partition ->
                    if (System.currentTimeMillis() < (retryAfter[partition] ?: 0L)) return@forEach
                    try {
                        advanced = processPartition(partition, generation).advanced || advanced
                        if (failures.remove(partition) != null) {
                            System.err.println("postmatch_obligations_recovered partition=$partition")
                        }
                        retryAfter.remove(partition)
                    } catch (error: Exception) {
                        val failure = error.message ?: error::class.simpleName ?: "unknown"
                        if (failure != failures[partition]) {
                            System.err.println("postmatch_obligations_failed partition=$partition reason=$failure")
                            failures[partition] = failure
                        }
                        retryAfter[partition] = System.currentTimeMillis() + maxOf(pollMs, 1000L)
                    }
                }
                if (!advanced) Thread.sleep(pollMs)
            }
        }
    }

    fun stop() { running.set(false) }

    companion object {
        fun fromEnv(): PostMatchSettlementObligationWorker {
            val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val partitionList = RuntimeEnv.string("POSTMATCH_WORKER_PARTITIONS",
                RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", ""))
            require(partitionList.isNotBlank()) { "settlement obligations require assigned partitions" }
            val partitions = partitionList.split(',').map { part ->
                val trimmed = part.trim()
                require(trimmed.isNotEmpty()) { "settlement obligations require non-empty partitions" }
                trimmed.toIntOrNull() ?: error("settlement obligations require numeric partitions: $part")
            }
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val targetUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
            require(sourceUrl.isNotBlank() && targetUrl.isNotBlank() && sourceUrl != targetUrl) {
                "settlement obligations require distinct canonical source and settlement PostgreSQL URLs"
            }
            val sourceUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            val sourcePassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
            val source = RuntimeDataSources.dataSource(sourceUrl, sourceUser, sourcePassword, "settlement-policy-source")
            val target = RuntimeDataSources.dataSource(
                targetUrl, RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", "").ifBlank { sourceUser },
                RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", "").ifBlank { sourcePassword },
                "settlement-obligation-target"
            )
            return PostMatchSettlementObligationWorker(
                PostMatchSourceCatalog(source),
                SettlementObligationPolicySource(source,
                    RuntimeEnv.string("POST_TRADE_PROFILE", ""),
                    RuntimeEnv.int("POST_TRADE_POLICY_VERSION", 1, min = 1)),
                SettlementBoundedObligationStore(target), stream, partitions,
                RuntimeEnv.int("POSTMATCH_SETTLEMENT_OBLIGATION_BATCH_SIZE", 500, min = 1),
                RuntimeEnv.int("POSTMATCH_SETTLEMENT_OBLIGATION_MAX_TRADES", 1000, min = 1),
                RuntimeEnv.long("POSTMATCH_SETTLEMENT_OBLIGATION_POLL_MS", 50, min = 1)
            )
        }
    }
}
