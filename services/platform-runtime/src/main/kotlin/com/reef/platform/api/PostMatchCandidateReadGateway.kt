package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.infrastructure.persistence.MatchingMarketBook
import com.reef.platform.infrastructure.persistence.MatchingMarketFrontier
import com.reef.platform.infrastructure.persistence.MatchingMarketTape
import com.reef.platform.infrastructure.persistence.MatchingMarketVector
import com.reef.platform.infrastructure.persistence.MatchingOutcomeMarketCandidate
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.SettlementJournalProjectionStore
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityAnchor
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityAnchorReader
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import com.reef.platform.infrastructure.persistence.SettlementProjectionAccountRead
import com.reef.platform.infrastructure.persistence.SettlementProjectionFrontier
import com.reef.platform.infrastructure.persistence.SettlementProjectionTradeRead
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class CandidateMarketScope(
    val partitionId: Int,
    val runId: String,
    val venueSessionId: String,
    val instrumentId: String,
    val currency: String
) {
    init {
        require(partitionId in 0..32767 && runId.isNotBlank() && venueSessionId.isNotBlank() &&
            instrumentId.isNotBlank() && currency.isNotBlank())
    }
}

interface CandidateMarketReadPort {
    fun readBook(stream: String, partition: Int, marketGeneration: String,
        sourceGeneration: String, scope: CandidateMarketScope, depth: Int,
        requireCurrent: Boolean): MatchingMarketBook
    fun readTape(stream: String, partition: Int, marketGeneration: String,
        sourceGeneration: String, scope: CandidateMarketScope, limit: Int,
        beforeSequence: Long?, beforeOrdinal: Int?, requireCurrent: Boolean): MatchingMarketTape
    fun readVector(stream: String, marketGeneration: String,
        sourceGenerations: Map<Int, String>, requireCurrent: Boolean): MatchingMarketVector
}

class MatchingOutcomeCandidateReadPort(
    private val candidate: MatchingOutcomeMarketCandidate
) : CandidateMarketReadPort {
    override fun readBook(stream: String, partition: Int, marketGeneration: String,
        sourceGeneration: String, scope: CandidateMarketScope, depth: Int,
        requireCurrent: Boolean): MatchingMarketBook = candidate.readBook(stream, partition,
        marketGeneration, sourceGeneration, scope.runId, scope.venueSessionId,
        scope.instrumentId, scope.currency, depth, requireCurrent)

    override fun readTape(stream: String, partition: Int, marketGeneration: String,
        sourceGeneration: String, scope: CandidateMarketScope, limit: Int,
        beforeSequence: Long?, beforeOrdinal: Int?, requireCurrent: Boolean): MatchingMarketTape =
        candidate.readTape(stream, partition, marketGeneration, sourceGeneration, scope.runId,
            scope.venueSessionId, scope.instrumentId, scope.currency, limit, beforeSequence,
            beforeOrdinal, requireCurrent)

    override fun readVector(stream: String, marketGeneration: String,
        sourceGenerations: Map<Int, String>, requireCurrent: Boolean): MatchingMarketVector =
        candidate.readVector(stream, marketGeneration, sourceGenerations, requireCurrent)
}

interface CandidateSettlementReadPort {
    fun readAccount(stream: String, generation: String, account: ReferenceAccountKey,
        requireCurrent: Boolean): SettlementProjectionAccountRead
    fun readTrade(stream: String, generation: String, runId: String, tradeId: String,
        requireCurrent: Boolean): SettlementProjectionTradeRead
}

class SettlementJournalCandidateReadPort(
    private val projection: SettlementJournalProjectionStore
) : CandidateSettlementReadPort {
    override fun readAccount(stream: String, generation: String, account: ReferenceAccountKey,
        requireCurrent: Boolean): SettlementProjectionAccountRead =
        projection.readAccount(stream, generation, account, requireCurrent)

    override fun readTrade(stream: String, generation: String, runId: String, tradeId: String,
        requireCurrent: Boolean): SettlementProjectionTradeRead =
        projection.readTrade(stream, generation, runId, tradeId, requireCurrent)
}

/** Optional indexed diagnostic lookup. Its age is an upper bound at read, never commit latency. */
fun interface CandidateSourceTimestampReader {
    fun sourceCreatedAt(eventStream: String, partitionId: Int, sourceSequence: Long): Instant?
}

/** Checks one indexed projection frontier against a separately durable acknowledgment. */
internal object CandidateFinancialFinalityGuard {
    fun verify(frontier: SettlementProjectionFrontier,
        anchor: SettlementJournalFinalityAnchor): Boolean {
        check(anchor.eventStream == frontier.eventStream &&
            anchor.incarnationId == frontier.journalIncarnationId &&
            anchor.sourceBindingDigest?.matches(Regex("[0-9a-f]{64}")) == true &&
            anchor.acknowledgedBatchSequence >= 0 &&
            frontier.batchSequence >= 0 &&
            frontier.batchSequence <= anchor.acknowledgedBatchSequence &&
            frontier.observedJournalSequence >= anchor.acknowledgedBatchSequence &&
            frontier.observedJournalSequence - frontier.batchSequence == frontier.lagBatches) {
            "candidate financial frontier differs from external settlement finality"
        }
        if (frontier.batchSequence == anchor.acknowledgedBatchSequence) {
            check(frontier.batchDigest == anchor.acknowledgedBatchDigest) {
                "candidate financial digest differs from external settlement finality"
            }
        }
        if (frontier.observedJournalSequence == anchor.acknowledgedBatchSequence) {
            check(frontier.observedJournalDigest == anchor.acknowledgedBatchDigest) {
                "journal head digest differs from external settlement finality"
            }
        }
        if (frontier.batchSequence == 0L) {
            check(frontier.batchDigest == SettlementJournalStore.ORIGIN_DIGEST) {
                "candidate financial origin digest changed"
            }
        }
        return frontier.batchSequence == anchor.acknowledgedBatchSequence &&
            frontier.observedJournalSequence == anchor.acknowledgedBatchSequence
    }
}

/** Default-off JSON-ready candidate views; matching execution and settlement status stay separate. */
class PostMatchCandidateReadGateway(
    private val catalog: PostMatchReadSourceCatalog,
    private val market: CandidateMarketReadPort,
    private val settlement: CandidateSettlementReadPort,
    private val finality: SettlementJournalFinalityAnchorReader,
    private val eventStream: String,
    private val marketGeneration: String,
    private val settlementGeneration: String,
    private val partitionCount: Int,
    private val sourceTimestampReader: CandidateSourceTimestampReader? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    init {
        require(eventStream.isNotBlank() && marketGeneration.isNotBlank() &&
            settlementGeneration.isNotBlank() && partitionCount in 1..32)
    }

    fun book(scope: CandidateMarketScope, includeAge: Boolean = false): Map<String, Any?> =
        depth(scope, 1, includeAge)

    fun depth(scope: CandidateMarketScope, levels: Int,
        includeAge: Boolean = false): Map<String, Any?> {
        require(scope.partitionId < partitionCount && levels in 1..100)
        val generation = catalog.generation()
        val book = market.readBook(eventStream, scope.partitionId, marketGeneration, generation,
            scope, levels, false)
        val asOf = marketAsOf(book.frontier, generation, includeAge)
        check(catalog.generation() == generation) { "candidate market source generation changed" }
        return mapOf(
            "bids" to book.bids.map { mapOf("price" to it.price.toPlainString(),
                "quantity" to it.quantity.toPlainString()) },
            "asks" to book.asks.map { mapOf("price" to it.price.toPlainString(),
                "quantity" to it.quantity.toPlainString()) },
            "asOf" to asOf
        )
    }

    fun tape(scope: CandidateMarketScope, limit: Int = 50, beforeSequence: Long? = null,
        beforeOrdinal: Int? = null, includeAge: Boolean = false): Map<String, Any?> {
        require(scope.partitionId < partitionCount && limit in 1..500)
        val generation = catalog.generation()
        val tape = market.readTape(eventStream, scope.partitionId, marketGeneration, generation,
            scope, limit, beforeSequence, beforeOrdinal, false)
        val asOf = marketAsOf(tape.frontier, generation, includeAge)
        check(catalog.generation() == generation) { "candidate market source generation changed" }
        return mapOf(
            "trades" to tape.trades.map { trade -> mapOf(
                "tradeId" to trade.tradeId, "eventId" to trade.eventId,
                "executionId" to trade.executionId,
                "quantity" to trade.quantity.toPlainString(),
                "price" to trade.price.toPlainString(), "occurredAt" to trade.occurredAt,
                "sourceSequence" to trade.sourceSequence,
                "effectOrdinal" to trade.effectOrdinal) },
            "executionAuthority" to "MATCHING_OUTCOME",
            "asOf" to asOf
        )
    }

    fun vector(includeAge: Boolean = false): Map<String, Any?> {
        val generation = catalog.generation()
        val generations = (0 until partitionCount).associateWith { generation }
        val vector = market.readVector(eventStream, marketGeneration, generations, false)
        check(vector.frontiers.keys == generations.keys) {
            "candidate market vector lacks current source generation coverage"
        }
        val partitions = vector.frontiers.toSortedMap().values.map {
            marketAsOf(it, generation, includeAge)
        }
        check(catalog.generation() == generation) {
            "candidate market source generation changed"
        }
        return mapOf("eventStream" to eventStream, "sourceGeneration" to generation,
            "partitions" to partitions)
    }

    fun balance(account: ReferenceAccountKey,
        requireCurrent: Boolean = true): Map<String, Any?> {
        val anchor = financialAnchor()
        val read = settlement.readAccount(eventStream, settlementGeneration, account, requireCurrent)
        val current = verifyFinancialRead(read.frontier, anchor, requireCurrent)
        return mapOf("account" to mapOf(
            "runId" to account.runId, "participantId" to account.participantId,
            "accountId" to account.accountId, "assetType" to account.assetType,
            "assetId" to account.assetId), "balance" to read.balance?.toPlainString(),
            "asOf" to settlementAsOf(read.frontier, anchor, current))
    }

    fun tradeStatus(runId: String, tradeId: String,
        requireCurrent: Boolean = true): Map<String, Any?> {
        require(runId.isNotBlank() && tradeId.isNotBlank())
        val anchor = financialAnchor()
        val read = settlement.readTrade(eventStream, settlementGeneration,
            runId, tradeId, requireCurrent)
        val current = verifyFinancialRead(read.frontier, anchor, requireCurrent)
        val status = read.status
        return mapOf("runId" to runId, "tradeId" to tradeId,
            "settlement" to status?.let { mapOf(
                "attemptNumber" to it.attemptNumber, "outcome" to it.outcome,
                "breakReason" to it.breakReason, "batchSequence" to it.batchSequence,
                "resultIndex" to it.resultIndex) },
            "asOf" to settlementAsOf(read.frontier, anchor, current))
    }

    private fun financialAnchor(): SettlementJournalFinalityAnchor = try {
        finality.read(eventStream) ?: error("external settlement finality anchor is missing")
    } catch (failure: Exception) {
        throw IllegalStateException("external settlement finality is unavailable", failure)
    }

    private fun verifyFinancialRead(frontier: SettlementProjectionFrontier,
        anchor: SettlementJournalFinalityAnchor, requireCurrent: Boolean): Boolean {
        check(frontier.eventStream == eventStream && frontier.projectorGeneration == settlementGeneration)
        val current = CandidateFinancialFinalityGuard.verify(frontier, anchor)
        check(financialAnchor() == anchor) {
            "external settlement finality changed during candidate read"
        }
        if (requireCurrent) check(current) {
            "candidate financial read is behind external settlement finality or journal head"
        }
        return current
    }

    private fun marketAsOf(frontier: MatchingMarketFrontier, sourceGeneration: String,
        includeAge: Boolean): Map<String, Any?> {
        check(frontier.eventStream == eventStream &&
            frontier.projectorGeneration == marketGeneration &&
            frontier.sourceGeneration == sourceGeneration &&
            frontier.lagPositions >= 0 &&
            frontier.observedSourceSequence - frontier.projectedSequence == frontier.lagPositions)
        val origin = CanonicalStreamPosition.origin(frontier.partitionId)
        val upperBound = if (!includeAge || frontier.projectedSequence == origin) null else {
            sourceTimestampReader?.sourceCreatedAt(eventStream, frontier.partitionId,
                frontier.projectedSequence)?.let { createdAt ->
                val elapsed = Duration.between(createdAt, clock.instant()).toMillis()
                check(elapsed >= 0) { "candidate source timestamp is in the future" }
                elapsed
            }
        }
        return mapOf(
            "eventStream" to frontier.eventStream,
            "partitionId" to frontier.partitionId,
            "projectorGeneration" to frontier.projectorGeneration,
            "sourceGeneration" to frontier.sourceGeneration,
            "projectedSourceSequence" to frontier.projectedSequence,
            "observedSourceSequence" to frontier.observedSourceSequence,
            "sourceLagPositions" to frontier.lagPositions,
            "lastWindowDigest" to frontier.lastWindowDigest,
            "currentAtObservation" to (frontier.lagPositions == 0L),
            "sourceAgeUpperBoundMs" to upperBound,
            "sourceAgeMethod" to if (!includeAge) "NOT_REQUESTED"
                else if (upperBound == null) "UNAVAILABLE"
                else "UPPER_BOUND_AT_READ"
        )
    }

    private fun settlementAsOf(frontier: SettlementProjectionFrontier,
        anchor: SettlementJournalFinalityAnchor, current: Boolean): Map<String, Any?> {
        check(frontier.eventStream == eventStream &&
            frontier.projectorGeneration == settlementGeneration && frontier.lagBatches >= 0)
        return mapOf(
            "eventStream" to frontier.eventStream,
            "projectorGeneration" to frontier.projectorGeneration,
            "journalIncarnationId" to frontier.journalIncarnationId,
            "batchSequence" to frontier.batchSequence,
            "batchDigest" to frontier.batchDigest,
            "observedJournalSequence" to frontier.observedJournalSequence,
            "observedJournalDigest" to frontier.observedJournalDigest,
            "journalLagBatches" to frontier.lagBatches,
            "acknowledgedBatchSequence" to anchor.acknowledgedBatchSequence,
            "acknowledgedBatchDigest" to anchor.acknowledgedBatchDigest,
            "currentAtObservation" to current
        )
    }
}
