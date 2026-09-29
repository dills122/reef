package com.reef.platform.api

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalEffectDecoder
import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.MatchingOutcomeMarketCandidate
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCandidateSourceTimestampReader
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementJournalProjectionStore
import java.math.BigDecimal
import javax.sql.DataSource

/** Default-off diagnostic routes; public cutover needs a separate API contract decision. */
class PostMatchCandidateHttpRoutes(private val gateway: PostMatchCandidateReadGateway) {
    fun handle(path: String, query: String?): PlatformHotPathResponse {
        return try {
            val includeAge = queryValue(query, "includeAge") == "true"
            val response = when (path.removePrefix(PREFIX)) {
                "book" -> gateway.book(scope(query), includeAge)
                "depth" -> gateway.depth(scope(query), queryValue(query, "levels").toIntOrNull() ?: 5,
                    includeAge)
                "tape" -> gateway.tape(scope(query), queryValue(query, "limit").toIntOrNull() ?: 50,
                    queryValue(query, "beforeSequence").toLongOrNull(),
                    queryValue(query, "beforeOrdinal").toIntOrNull(), includeAge)
                "vector" -> gateway.vector(includeAge)
                "balance" -> gateway.balance(ReferenceAccountKey(
                    required(query, "runId"), required(query, "participantId"),
                    required(query, "accountId"), required(query, "assetType"),
                    required(query, "assetId")), requireCurrent = false)
                "status" -> gateway.tradeStatus(required(query, "runId"), required(query, "tradeId"),
                    requireCurrent = false)
                else -> return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "not found"))
            }
            PlatformHotPathResponse(200, JsonCodec.writeObject(*response.entries.map {
                it.key to it.value
            }.toTypedArray()))
        } catch (_: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid candidate read request"))
        } catch (failure: IllegalStateException) {
            System.err.println("postmatch_candidate_read_unavailable reason=${failure.message}")
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "candidate view unavailable"))
        }
    }

    companion object {
        const val PREFIX = "/api/v1/postmatch-candidate/"

        fun fromEnvOrNull(role: PlatformRuntimeRole): PostMatchCandidateHttpRoutes? {
            if (role != PlatformRuntimeRole.Api ||
                !RuntimeEnv.bool("POSTMATCH_CANDIDATE_READS_ENABLED", false)) return null
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val marketUrl = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_JDBC_URL", "")
            val settlementUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
            val finalityUrl = RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_JDBC_URL", "")
            require(listOf(sourceUrl, marketUrl, settlementUrl, finalityUrl).all(String::isNotBlank) &&
                listOf(sourceUrl, marketUrl, settlementUrl, finalityUrl).distinct().size == 4) {
                "candidate reads require distinct source, market, settlement, and finality PostgreSQL URLs"
            }
            val stream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "").also { require(it.isNotBlank()) }
            val generation = RuntimeEnv.string("POSTMATCH_LEDGER_CANDIDATE_GENERATION", "")
                .also { require(it.isNotBlank()) }
            val source = RuntimeDataSources.dataSource(sourceUrl,
                RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef"),
                RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef"), "candidate-read-source")
            val market = RuntimeDataSources.dataSource(marketUrl,
                RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_USER", "reef"),
                RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_PASSWORD", "reef"), "candidate-read-market")
            val settlement = RuntimeDataSources.dataSource(settlementUrl,
                RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", "reef"),
                RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", "reef"), "candidate-read-financial")
            val finality = RuntimeDataSources.dataSource(finalityUrl,
                RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_USER", "reef"),
                RuntimeEnv.string("SETTLEMENT_FINALITY_POSTGRES_PASSWORD", "reef"),
                "candidate-read-finality")
            return PostMatchCandidateHttpRoutes(PostMatchCandidateReadGateway(
                PostMatchSourceCatalog(source),
                MatchingOutcomeCandidateReadPort(MatchingOutcomeMarketCandidate(source, market)),
                SettlementJournalCandidateReadPort(SettlementJournalProjectionStore(settlement)),
                PostgresSettlementJournalFinalityAuthority(finality),
                stream, generation, generation,
                RuntimeEnv.int("POSTMATCH_CANDIDATE_PARTITION_COUNT", 16, min = 1),
                PostgresCandidateSourceTimestampReader(source),
                executionSource = PostgresCandidateExecutionSourceReader(source, market)))
        }

        private fun required(query: String?, key: String): String = queryValue(query, key).also {
            require(it.isNotBlank()) { "$key is required" }
        }

        private fun scope(query: String?): CandidateMarketScope = CandidateMarketScope(
            required(query, "partitionId").toInt(), required(query, "runId"),
            required(query, "venueSessionId"), required(query, "instrumentId"),
            required(query, "currency"))
    }
}

/** Uses tape's partition-key index to find one source position, then verifies canonical payload. */
internal class PostgresCandidateExecutionSourceReader(
    private val sourceDataSource: DataSource,
    private val marketDataSource: DataSource,
    private val decoder: CanonicalEffectDecoder = CanonicalEffectDecoder(),
    private val sourceSchema: String = "runtime",
    private val marketSchema: String = "postmatch"
) : CandidateExecutionSourceReadPort {
    init {
        require(sourceSchema.matches(Regex("[a-z][a-z0-9_]*")) &&
            marketSchema.matches(Regex("[a-z][a-z0-9_]*")))
    }
    private data class Locator(val partitionId: Int, val runId: String, val venueSessionId: String,
        val instrumentId: String, val currency: String, val eventId: String,
        val executionId: String, val quantity: BigDecimal, val price: BigDecimal,
        val sourceGeneration: String, val sequence: Long, val ordinal: Int)

    override fun readExecution(eventStream: String, marketGeneration: String,
        sourceGeneration: String, partitionCount: Int, runId: String,
        tradeId: String): CandidateExecutedTrade {
        require(eventStream.isNotBlank() && marketGeneration.isNotBlank() &&
            sourceGeneration.isNotBlank() && runId.isNotBlank() && tradeId.isNotBlank() &&
            partitionCount in 1..32)
        val locator = marketDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT run_id, venue_session_id, instrument_id, currency, event_id,
                          execution_id, quantity_units, price, source_generation,
                          source_stream_sequence, source_effect_ordinal
                   FROM $marketSchema.matching_market_candidate_tape
                   WHERE event_stream = ? AND partition_id = ? AND projector_generation = ?
                     AND trade_id = ?"""
            ).use { statement ->
                var found: Locator? = null
                for (partition in 0 until partitionCount) {
                    statement.setString(1, eventStream)
                    statement.setInt(2, partition)
                    statement.setString(3, marketGeneration)
                    statement.setString(4, tradeId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            check(found == null) { "candidate trade ID appears in multiple partitions" }
                            found = Locator(partition, rows.getString(1), rows.getString(2),
                                rows.getString(3), rows.getString(4), rows.getString(5),
                                rows.getString(6), rows.getBigDecimal(7), rows.getBigDecimal(8),
                                rows.getString(9), rows.getLong(10), rows.getInt(11))
                            check(!rows.next()) { "candidate trade locator is duplicated" }
                        }
                    }
                }
                found ?: error("candidate execution position is not yet visible")
            }
        }
        check(locator.runId == runId && locator.sourceGeneration == sourceGeneration) {
            "candidate trade locator has inconsistent run or source generation"
        }
        val source = sourceDataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT event_stream, batch_id, command_id, command_type, payload_hash,
                          instrument_id, order_id, result_status, result_payload::text
                   FROM $sourceSchema.canonical_command_outcomes
                   WHERE partition_id = ? AND stream_sequence = ?"""
            ).use { statement ->
                statement.setInt(1, locator.partitionId)
                statement.setLong(2, locator.sequence)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "candidate trade canonical source is missing" }
                    val value = CanonicalOutcomeSource(rows.getString(1), locator.partitionId,
                        locator.sequence, rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                        rows.getString(9))
                    check(!rows.next()) { "candidate trade canonical source is duplicated" }
                    value
                }
            }
        }
        check(source.eventStream == eventStream) { "candidate trade canonical stream differs" }
        val effects = try { decoder.decode(source) } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("candidate trade canonical source is invalid", failure)
        }
        val accepted = effects.map { it.effect }.filterIsInstance<CanonicalEffect.Accepted>().singleOrNull()
        val order = accepted?.newOrder
        val trade = effects.singleOrNull { it.position.effectOrdinal == locator.ordinal }?.effect
            as? CanonicalEffect.Trade
        check(order != null && order.runId == runId &&
            order.venueSessionId == locator.venueSessionId &&
            order.instrumentId == locator.instrumentId && order.currency == locator.currency &&
            trade != null && trade.tradeId == tradeId && trade.eventId == locator.eventId &&
            trade.executionId == locator.executionId &&
            BigDecimal(trade.quantityUnits).compareTo(locator.quantity) == 0 &&
            BigDecimal(trade.price).compareTo(locator.price) == 0 &&
            trade.instrumentId == locator.instrumentId && trade.currency == locator.currency) {
            "candidate trade locator differs from committed matching outcome"
        }
        return CandidateExecutedTrade(runId, tradeId, trade.eventId, trade.executionId,
            sourceGeneration, locator.partitionId, locator.sequence, locator.ordinal)
    }
}
