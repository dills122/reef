package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchApplyResult
import com.reef.platform.infrastructure.persistence.PostMatchAuditStore
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Independent, opt-in audit owner. Public history routes stay on mixed legacy audit until parity. */
internal class PostMatchAuditWorker(
    private val catalog: PostMatchSourceCatalog,
    private val reader: PostgresCanonicalOutcomeSourceReader,
    private val store: PostMatchAuditStore,
    private val eventStream: String,
    private val partitions: List<Int>,
    private val batchSize: Int,
    private val pollMs: Long
) {
    private val running = AtomicBoolean(false)

    init {
        require(eventStream.isNotBlank() && partitions.isNotEmpty() && partitions.distinct().size == partitions.size)
        require(partitions.all { it in 0..32767 } && batchSize in 1..5000 && pollMs > 0)
    }

    fun processOnce(): Int {
        val generation = catalog.generation()
        return partitions.sumOf { processPartition(it, generation) }
    }

    private fun processPartition(partition: Int, generation: String): Int {
        val frontier = store.lastCommittedSequence(eventStream, partition, generation)
        check(frontier >= CanonicalStreamPosition.origin(partition)) { "audit frontier predates partition origin" }
        val window = reader.readNextWindow(
            PostMatchAuditStore.CONSUMER, eventStream, partition, generation, frontier, batchSize
        ) ?: return 0
        check(catalog.generation() == generation) { "audit source generation changed during processing" }
        return if (store.apply(window) == PostMatchApplyResult.APPLIED) window.outcomes.size else 0
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "reef-postmatch-audit-worker", isDaemon = true) {
            var sourceFailure = ""
            val failures = mutableMapOf<Int, String>()
            val retryAfter = mutableMapOf<Int, Long>()
            while (running.get()) {
                val generation = try {
                    catalog.generation().also {
                        if (sourceFailure.isNotEmpty()) System.err.println("postmatch_audit_recovered source=true")
                        sourceFailure = ""
                    }
                } catch (error: Exception) {
                    val failure = error.message ?: error::class.simpleName ?: "unknown"
                    if (failure != sourceFailure) System.err.println("postmatch_audit_failed source=true reason=$failure")
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
                            System.err.println("postmatch_audit_recovered partition=$partition")
                        }
                        retryAfter.remove(partition)
                    } catch (error: Exception) {
                        val failure = error.message ?: error::class.simpleName ?: "unknown"
                        if (failure != failures[partition]) {
                            System.err.println("postmatch_audit_failed partition=$partition reason=$failure")
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
        fun fromEnv(): PostMatchAuditWorker {
            val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val partitionList = RuntimeEnv.string("POSTMATCH_WORKER_PARTITIONS",
                RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", ""))
            require(partitionList.isNotBlank()) { "audit worker requires assigned partitions" }
            val partitions = partitionList.split(',').map { part ->
                part.trim().toIntOrNull() ?: error("audit worker requires numeric partitions: $part")
            }
            require(partitions.distinct().size == partitions.size) { "audit worker partitions must be unique" }
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val targetUrl = RuntimeEnv.string("RUNTIME_PROJECTION_POSTGRES_JDBC_URL", "")
            require(sourceUrl.isNotBlank() && targetUrl.isNotBlank()) {
                "audit worker requires canonical source and projection PostgreSQL"
            }
            val sourceUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            val sourcePassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
            val source = RuntimeDataSources.dataSource(
                sourceUrl, sourceUser, sourcePassword, "postmatch-audit-source"
            )
            val target = RuntimeDataSources.dataSource(
                targetUrl, RuntimeEnv.string("RUNTIME_PROJECTION_POSTGRES_USER", sourceUser),
                RuntimeEnv.string("RUNTIME_PROJECTION_POSTGRES_PASSWORD", sourcePassword), "postmatch-audit-target"
            )
            return PostMatchAuditWorker(
                PostMatchSourceCatalog(source), PostgresCanonicalOutcomeSourceReader(source),
                PostMatchAuditStore(target), stream,
                partitions,
                RuntimeEnv.int("POSTMATCH_AUDIT_BATCH_SIZE", 500, min = 1),
                RuntimeEnv.long("POSTMATCH_AUDIT_POLL_MS", 50, min = 1)
            )
        }
    }
}
