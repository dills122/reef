package com.reef.platform.api

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.MatchingOutcomeMarketCandidate
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCandidateSourceTimestampReader
import com.reef.platform.infrastructure.persistence.PostgresSettlementJournalFinalityAuthority
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.SettlementJournalProjectionStore

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
                PostgresCandidateSourceTimestampReader(source)))
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
