package com.reef.platform.api

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics

/**
 * Routes for the "admin/data" surface (see docs/steering/architecture.md):
 * operator-approved administration plus intraday/historical data access. Spans
 * health/readiness, risk guardrail admin, arena bot/run/analytics admin (via
 * ArenaAdminGateway), settlement fact ingestion (via SettlementAdminGateway),
 * and runtime diagnostics/stats — not a single bounded context, but one route
 * table because they're all internal-only admin/data routes dispatched the
 * same way. Was named PlatformDiagnosticRoutes despite carrying arena/
 * analytics/settlement routes; renamed to match actual scope.
 */
internal class PlatformAdminDataRoutes(
    private val arenaAdminGateway: ArenaAdminGateway,
    private val arenaRoutesEnabled: Boolean,
    private val settlementAdminGateway: SettlementAdminGateway,
    private val healthJson: () -> String,
    private val readinessJson: () -> String,
    private val abuseStatsJson: () -> String,
    private val accountRiskControlsJson: () -> String,
    private val accountRiskDecisionsJson: (Int) -> String,
    private val commandCircuitBreakersJson: () -> String,
    private val instrumentPriceCollarsJson: () -> String,
    private val setAccountRiskControlJson: (String) -> PlatformHotPathResponse,
    private val setCommandCircuitBreakerJson: (String) -> PlatformHotPathResponse,
    private val setInstrumentPriceCollarJson: (String) -> PlatformHotPathResponse,
    private val dbPoolStatsJson: () -> String,
    private val asyncCommandStatsJson: () -> String,
    private val commandAccountingJson: (String) -> String,
    private val streamCommandHealthJson: () -> String,
    private val streamCommandWorkerStatsJson: () -> String,
    private val venueEventMaterializerStatsJson: () -> String,
    private val projectorStatusJson: () -> String,
    private val marketDataProjectorStatsJson: () -> String,
    private val orderLifecycleProjectorStatsJson: () -> String
) {
    val paths: List<String> = buildList {
        addAll(listOf(
        "/health",
        "/healthz",
        "/readyz",
        "/internal/boundary/abuse/stats",
        "/internal/boundary/account-risk/controls",
        "/internal/boundary/account-risk/decisions/recent",
        "/internal/boundary/circuit-breakers",
        "/internal/boundary/price-collars",
        "/internal/admin/account-risk/controls",
        "/internal/admin/circuit-breakers",
        "/internal/admin/price-collars",
        ))
        if (arenaRoutesEnabled) {
            addAll(listOf(
        "/internal/admin/arena/bots",
        "/internal/admin/arena/my/bots",
        "/internal/admin/arena/bot-versions",
        "/internal/admin/arena/bot-versions/transition",
        "/internal/admin/arena/qualification-reports",
        "/internal/admin/arena/operator-decisions",
        "/internal/admin/arena/runtime-config-descriptors",
        "/internal/admin/arena/runs",
        "/internal/admin/arena/runs/status",
        "/internal/admin/arena/run-bot-results",
        "/internal/admin/arena/run-enforcement-events",
        "/internal/admin/arena/leaderboard",
        "/internal/admin/arena/bots/openbao-provision",
        "/internal/admin/arena/bots/ownership",
        "/internal/admin/arena/bots/config",
            ))
        }
        addAll(listOf(
        "/internal/admin/analytics/run-exports",
        "/internal/admin/analytics/run-bot-summaries",
        "/internal/perf/hot-path",
        "/internal/perf/db-pools",
        "/internal/commands/async/stats",
        "/internal/commands/accounting",
        "/internal/stream-ack/health",
        "/internal/stream-ack/worker/stats",
        "/internal/venue-event-materializer/stats",
        "/internal/projector/status",
        "/internal/market-data/projector/status",
        "/internal/order-lifecycle/projector/status"
        ))
    }

    fun handle(method: String, path: String, query: String?, body: String = ""): PlatformHotPathResponse? {
        if (!arenaRoutesEnabled && path.startsWith("/internal/admin/arena/")) return null
        return when (path) {
            "/health" -> getOnly(method) { healthJson() }
            "/healthz" -> getOnly(method) { healthJson() }
            "/readyz" -> getOnly(method) { readinessJson() }
            "/internal/boundary/abuse/stats" -> getOnly(method) { abuseStatsJson() }
            "/internal/boundary/account-risk/controls" -> getOnly(method) { accountRiskControlsJson() }
            "/internal/boundary/account-risk/decisions/recent" -> getOnly(method) {
                accountRiskDecisionsJson(queryValue(query, "limit").toIntOrNull() ?: 50)
            }
            "/internal/boundary/circuit-breakers" -> getOnly(method) { commandCircuitBreakersJson() }
            "/internal/boundary/price-collars" -> getOnly(method) { instrumentPriceCollarsJson() }
            "/internal/admin/account-risk/controls" -> postOnly(method) { setAccountRiskControlJson(body) }
            "/internal/admin/circuit-breakers" -> postOnly(method) { setCommandCircuitBreakerJson(body) }
            "/internal/admin/price-collars" -> postOnly(method) { setInstrumentPriceCollarJson(body) }
            "/internal/admin/arena/bots" -> getOrPost(
                method,
                { arenaAdminGateway.arenaBotResponse(query) },
                { arenaAdminGateway.registerArenaBotResponse(body) }
            )
            "/internal/admin/arena/my/bots" -> getResponseOnly(method) {
                arenaAdminGateway.arenaMyBotsResponse(query)
            }
            "/internal/admin/arena/bot-versions" -> getOrPost(
                method,
                { arenaAdminGateway.arenaBotVersionResponse(query) },
                { arenaAdminGateway.registerArenaBotVersionResponse(body) }
            )
            "/internal/admin/arena/bot-versions/transition" -> postOnly(method) {
                arenaAdminGateway.transitionArenaBotVersionResponse(body)
            }
            "/internal/admin/arena/qualification-reports" -> getResponseOnly(method) {
                arenaAdminGateway.arenaQualificationReportsResponse(query)
            }
            "/internal/admin/arena/operator-decisions" -> getResponseOnly(method) {
                arenaAdminGateway.arenaOperatorDecisionsResponse(query)
            }
            "/internal/admin/arena/runtime-config-descriptors" -> getResponseOnly(method) {
                arenaAdminGateway.arenaRuntimeConfigDescriptorsResponse(query)
            }
            "/internal/admin/arena/runs" -> getOrPost(
                method,
                { arenaAdminGateway.arenaRunResponse(query) },
                { arenaAdminGateway.registerArenaRunResponse(body) }
            )
            "/internal/admin/arena/runs/status" -> postOnly(method) {
                arenaAdminGateway.updateArenaRunStatusResponse(body)
            }
            "/internal/admin/arena/run-bot-results" -> getOrPost(
                method,
                { arenaAdminGateway.arenaRunBotResultsResponse(query) },
                { arenaAdminGateway.recordArenaRunBotResultResponse(body) }
            )
            "/internal/admin/arena/run-enforcement-events" -> getOrPost(
                method,
                { arenaAdminGateway.arenaRunEnforcementEventsResponse(query) },
                { arenaAdminGateway.recordArenaRunEnforcementEventResponse(body) }
            )
            "/internal/admin/arena/leaderboard" -> getResponseOnly(method) {
                arenaAdminGateway.arenaLeaderboardResponse(query)
            }
            "/internal/admin/arena/bots/openbao-provision" -> postOnly(method) {
                arenaAdminGateway.arenaBotOpenBaoProvisionResponse(body)
            }
            "/internal/admin/arena/bots/ownership" -> postOnly(method) {
                arenaAdminGateway.assignArenaBotOwnershipResponse(body)
            }
            "/internal/admin/arena/bots/config" -> arenaAdminGateway.arenaBotOpenBaoConfigResponse(method, query, body)
            "/internal/admin/analytics/run-exports" -> getOrPost(
                method,
                { arenaAdminGateway.analyticsRunExportsResponse(query) },
                { arenaAdminGateway.recordAnalyticsRunExportResponse(body) }
            )
            "/internal/admin/analytics/run-bot-summaries" -> getResponseOnly(method) {
                arenaAdminGateway.analyticsRunBotSummariesResponse(query)
            }
            "/internal/admin/settlement/facts" -> postOnly(method) {
                settlementAdminGateway.appendSettlementFactsResponse(body)
            }
            "/internal/perf/hot-path" -> hotPathMetrics(method)
            "/internal/perf/db-pools" -> getOnly(method) { dbPoolStatsJson() }
            "/internal/commands/async/stats" -> getOnly(method) { asyncCommandStatsJson() }
            "/internal/commands/accounting" -> getOnly(method) {
                commandAccountingJson(queryValue(query, "runId"))
            }
            "/internal/stream-ack/health" -> getOnly(method) { streamCommandHealthJson() }
            "/internal/stream-ack/worker/stats" -> getOnly(method) { streamCommandWorkerStatsJson() }
            "/internal/venue-event-materializer/stats" -> getOnly(method) { venueEventMaterializerStatsJson() }
            "/internal/projector/status" -> getOnly(method) { projectorStatusJson() }
            "/internal/market-data/projector/status" -> getOnly(method) { marketDataProjectorStatsJson() }
            "/internal/order-lifecycle/projector/status" -> getOnly(method) { orderLifecycleProjectorStatsJson() }
            else -> null
        }
    }

    private fun hotPathMetrics(method: String): PlatformHotPathResponse {
        return when (method) {
            "GET" -> PlatformHotPathResponse(200, JsonCodec.writeObject("metrics" to HotPathMetrics.snapshot()))
            "POST" -> {
                HotPathMetrics.reset()
                PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "reset"))
            }
            else -> methodNotAllowedResponse()
        }
    }

    private fun getOnly(method: String, body: () -> String): PlatformHotPathResponse {
        return if (method == "GET") {
            PlatformHotPathResponse(200, body())
        } else {
            methodNotAllowedResponse()
        }
    }

    private fun getResponseOnly(method: String, response: () -> PlatformHotPathResponse): PlatformHotPathResponse {
        return if (method == "GET") {
            response()
        } else {
            methodNotAllowedResponse()
        }
    }

    private fun postOnly(method: String, response: () -> PlatformHotPathResponse): PlatformHotPathResponse {
        return if (method == "POST") {
            response()
        } else {
            methodNotAllowedResponse()
        }
    }

    private fun getOrPost(
        method: String,
        getResponse: () -> PlatformHotPathResponse,
        postResponse: () -> PlatformHotPathResponse
    ): PlatformHotPathResponse {
        return when (method) {
            "GET" -> getResponse()
            "POST" -> postResponse()
            else -> methodNotAllowedResponse()
        }
    }
}

internal fun methodNotAllowedResponse(): PlatformHotPathResponse {
    return PlatformHotPathResponse(status = 405, body = "", contentType = null)
}

internal fun queryValue(query: String?, key: String): String {
    if (query.isNullOrBlank()) return ""
    val values = query.split("&")
    for (value in values) {
        val parts = value.split("=", limit = 2)
        if (parts.size == 2 && urlDecode(parts[0]) == key) {
            return urlDecode(parts[1])
        }
    }
    return ""
}

private fun urlDecode(value: String): String {
    return try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}
