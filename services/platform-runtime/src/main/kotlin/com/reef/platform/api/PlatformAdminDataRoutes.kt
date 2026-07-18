package com.reef.platform.api

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics

/**
 * Routes for the "admin/data" surface (see docs/steering/architecture.md):
 * operator-approved administration plus intraday/historical data access. Spans
 * health/readiness, risk guardrail admin, optional product routes, settlement fact ingestion (via SettlementAdminGateway),
 * and runtime diagnostics/stats — not a single bounded context, but one route
 * table because they're all internal-only admin/data routes dispatched the
 * same way. Was named PlatformDiagnosticRoutes despite carrying arena/
 * analytics/settlement routes; renamed to match actual scope.
 */
internal class PlatformAdminDataRoutes(
    private val optionalProductRouteExtensions: List<OptionalProductRouteExtension>,
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
        optionalProductRouteExtensions.forEach { addAll(it.internalPaths) }
        addAll(listOf(
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
            else -> optionalProductRouteExtensions.firstNotNullOfOrNull {
                it.handleInternal(method, path, query, body)
            }
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

fun methodNotAllowedResponse(): PlatformHotPathResponse {
    return PlatformHotPathResponse(status = 405, body = "", contentType = null)
}

fun queryValue(query: String?, key: String): String {
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
