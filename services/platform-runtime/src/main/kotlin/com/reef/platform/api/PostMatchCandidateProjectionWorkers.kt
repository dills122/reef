package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.MatchingMarketAdvance
import com.reef.platform.infrastructure.persistence.MatchingOutcomeMarketCandidate
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityAnchorReader
import com.reef.platform.infrastructure.persistence.SettlementJournalProjectionStore
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Default-off sibling consumers. They never make settlement decisions or serve public reads. */
internal class PostMatchCandidateProjectionWorkers(
    private val sourceCatalog: PostMatchSourceCatalog?,
    private val market: MatchingOutcomeMarketCandidate?,
    private val journal: SettlementJournalStore?,
    private val financial: SettlementJournalProjectionStore?,
    private val finality: SettlementJournalFinalityAnchorReader?,
    private val eventStream: String,
    private val generation: String,
    private val partitions: List<Int>,
    private val batchSize: Int,
    private val pollMs: Long
) {
    private val running = AtomicBoolean(false)

    init {
        require(eventStream.isNotBlank() && generation.isNotBlank())
        require((market != null) == (sourceCatalog != null))
        require((journal != null) == (financial != null))
        require((financial != null) == (finality != null))
        require(market != null || financial != null)
        require(partitions.distinct().size == partitions.size && partitions.all { it in 0..32767 })
        require(market == null || partitions.isNotEmpty())
        require(batchSize in 1..5000 && pollMs > 0)
    }

    fun processMarketOnce(partition: Int): Int {
        val candidate = market ?: return 0
        require(partition in partitions)
        val sourceGeneration = requireNotNull(sourceCatalog).generation()
        return if (candidate.advanceNext(eventStream, partition, sourceGeneration,
                generation, batchSize) == MatchingMarketAdvance.APPLIED) 1 else 0
    }

    fun processFinancialOnce(): Int {
        val store = journal ?: return 0
        val projection = requireNotNull(financial)
        projection.initialize(eventStream, generation)
        val frontier = projection.frontier(eventStream, generation)
        val head = store.head(eventStream)
        check(frontier.journalIncarnationId == head.incarnationId) { "financial projection incarnation changed" }
        val anchor = requireNotNull(finality).read(eventStream)
            ?: error("external settlement finality anchor is missing")
        CandidateFinancialFinalityGuard.verify(frontier, anchor)
        check(anchor.incarnationId == head.incarnationId &&
            anchor.acknowledgedBatchSequence <= head.nextBatchSequence - 1 &&
            (anchor.acknowledgedBatchSequence != head.nextBatchSequence - 1 ||
                anchor.acknowledgedBatchDigest == head.lastBatchDigest)) {
            "journal head differs from external settlement finality"
        }
        if (frontier.batchSequence >= anchor.acknowledgedBatchSequence) return 0
        val batch = store.readVerifiedBatch(eventStream, frontier.batchSequence + 1)
        check(batch.incarnationId == anchor.incarnationId &&
            batch.batchSequence <= anchor.acknowledgedBatchSequence &&
            (batch.batchSequence != anchor.acknowledgedBatchSequence ||
                batch.batchDigest == anchor.acknowledgedBatchDigest)) {
            "projection batch differs from external settlement finality"
        }
        val latestAnchor = requireNotNull(finality).read(eventStream)
            ?: error("external settlement finality anchor disappeared")
        check(latestAnchor.incarnationId == anchor.incarnationId &&
            latestAnchor.acknowledgedBatchSequence >= batch.batchSequence &&
            (latestAnchor.acknowledgedBatchSequence != batch.batchSequence ||
                latestAnchor.acknowledgedBatchDigest == batch.batchDigest)) {
            "external settlement finality changed before projection"
        }
        projection.apply(generation, batch)
        return 1
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (market != null) partitions.forEach { partition ->
            runLoop("reef-postmatch-market-candidate-$partition") { processMarketOnce(partition) }
        }
        if (financial != null) runLoop("reef-settlement-journal-projection") { processFinancialOnce() }
    }

    fun stop() { running.set(false) }

    private fun runLoop(name: String, process: () -> Int) {
        thread(name = name, isDaemon = true) {
            var failure: String? = null
            while (running.get()) {
                val count = try {
                    process().also {
                        if (failure != null) System.err.println("postmatch_candidate_recovered worker=$name")
                        failure = null
                    }
                } catch (error: Exception) {
                    val reason = error.message ?: error::class.simpleName ?: "unknown"
                    if (failure != reason) System.err.println(
                        "postmatch_candidate_failed worker=$name reason=$reason")
                    failure = reason
                    0
                }
                if (count == 0) Thread.sleep(if (failure == null) pollMs else maxOf(pollMs, 1000L))
            }
        }
    }

    companion object {
        fun fromEnv(marketEnabled: Boolean, financialEnabled: Boolean): PostMatchCandidateProjectionWorkers {
            require(marketEnabled || financialEnabled)
            val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val generation = RuntimeEnv.string("POSTMATCH_LEDGER_CANDIDATE_GENERATION", "")
            val partitionList = RuntimeEnv.string("POSTMATCH_WORKER_PARTITIONS", "")
            val partitions = if (marketEnabled) {
                require(partitionList.isNotBlank()) { "candidate market requires assigned partitions" }
                partitionList.split(',').map { it.trim().toInt() }
            } else emptyList()
            val source = if (marketEnabled) {
                val url = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
                require(url.isNotBlank()) { "candidate market source JDBC URL is required" }
                RuntimeDataSources.dataSource(url,
                    RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef"),
                    RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef"),
                    "candidate-market-source")
            } else null
            val market = source?.let {
                val url = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_JDBC_URL", "")
                require(url.isNotBlank()) { "candidate market target JDBC URL is required" }
                val target = RuntimeDataSources.dataSource(url,
                    RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_USER", "reef"),
                    RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_PASSWORD", "reef"),
                    "candidate-market-target")
                val ageMetrics = if (RuntimeEnv.bool("POSTMATCH_CANDIDATE_AGE_METRICS_ENABLED", false))
                    PostMatchCandidateAgeMetrics(it) else null
                MatchingOutcomeMarketCandidate(it, target,
                    afterWindowVisible = { window, observedAt ->
                        ageMetrics?.recordVisibleWindow(window, observedAt)
                    })
            }
            val settlement = if (financialEnabled) {
                val url = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
                require(url.isNotBlank()) { "candidate financial projection JDBC URL is required" }
                RuntimeDataSources.dataSource(url,
                    RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", "reef"),
                    RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", "reef"),
                    "candidate-financial-projection")
            } else null
            val finality = if (financialEnabled) {
                val url = RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_JDBC_URL", "")
                val settlementUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
                require(url.isNotBlank() && url != settlementUrl &&
                    (source == null || url != RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")) &&
                    (market == null || url != RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_JDBC_URL", ""))) {
                    "candidate financial projection requires an independent finality JDBC URL"
                }
                RuntimeDataSources.dataSource(url,
                    RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_USER", "reef"),
                    RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_PASSWORD", "reef"),
                    "candidate-financial-finality")
            } else null
            return PostMatchCandidateProjectionWorkers(source?.let(::PostMatchSourceCatalog), market,
                settlement?.let(::SettlementJournalStore),
                settlement?.let(::SettlementJournalProjectionStore),
                finality?.let(::PostgresSettlementJournalFinalityAuthority),
                stream, generation, partitions,
                RuntimeEnv.int("POSTMATCH_LEDGER_CANDIDATE_BATCH_SIZE", 500, min = 1),
                RuntimeEnv.long("POSTMATCH_LEDGER_CANDIDATE_POLL_MS", 50, min = 1))
        }
    }
}
