package com.reef.platform.api

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.sun.net.httpserver.HttpExchange

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
    private val currentAdminPrincipal: () -> AdminRequestPrincipal,
    private val settlementAdminGateway: SettlementAdminGateway,
    private val riskGuardrailGateway: RiskGuardrailGateway,
    private val diagnosticsGateway: DiagnosticsGateway,
    private val healthJson: () -> String,
    private val readinessJson: () -> String,
    private val abuseStatsJson: () -> String
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
            "/internal/boundary/account-risk/controls" -> getOnly(method) { riskGuardrailGateway.accountRiskControlsJson() }
            "/internal/boundary/account-risk/decisions/recent" -> getOnly(method) {
                riskGuardrailGateway.accountRiskDecisionsJson(queryValue(query, "limit").toIntOrNull() ?: 50)
            }
            "/internal/boundary/circuit-breakers" -> getOnly(method) { riskGuardrailGateway.commandCircuitBreakersJson() }
            "/internal/boundary/price-collars" -> getOnly(method) { riskGuardrailGateway.instrumentPriceCollarsJson() }
            "/internal/admin/account-risk/controls" -> postOnly(method) { riskGuardrailGateway.setAccountRiskControlResponse(body) }
            "/internal/admin/circuit-breakers" -> postOnly(method) { riskGuardrailGateway.setCommandCircuitBreakerResponse(body) }
            "/internal/admin/price-collars" -> postOnly(method) { riskGuardrailGateway.setInstrumentPriceCollarResponse(body) }
            "/internal/admin/settlement/facts" -> postOnly(method) {
                settlementAdminGateway.appendSettlementFactsResponse(body)
            }
            "/internal/perf/hot-path" -> hotPathMetrics(method)
            "/internal/perf/db-pools" -> getOnly(method) { diagnosticsGateway.dbPoolStatsJson() }
            "/internal/commands/async/stats" -> getOnly(method) { diagnosticsGateway.asyncCommandStatsJson() }
            "/internal/commands/accounting" -> getOnly(method) {
                diagnosticsGateway.commandAccountingJson(queryValue(query, "runId"))
            }
            "/internal/stream-ack/health" -> getOnly(method) { diagnosticsGateway.streamCommandHealthJson() }
            "/internal/stream-ack/worker/stats" -> getOnly(method) { diagnosticsGateway.streamCommandWorkerStatsJson() }
            "/internal/venue-event-materializer/stats" -> getOnly(method) { diagnosticsGateway.venueEventMaterializerStatsJson() }
            "/internal/projector/status" -> getOnly(method) { diagnosticsGateway.projectorStatusJson() }
            "/internal/market-data/projector/status" -> getOnly(method) { diagnosticsGateway.marketDataProjectorStatusJson() }
            "/internal/order-lifecycle/projector/status" -> getOnly(method) { diagnosticsGateway.orderLifecycleProjectorStatusJson() }
            else -> optionalProductRouteExtensions.firstNotNullOfOrNull {
                it.handleInternal(method, path, query, body, currentAdminPrincipal())
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

fun HttpExchange.queryValue(key: String): String {
    val query = requestURI.query ?: return ""
    return queryValue(query, key)
}

private fun urlDecode(value: String): String {
    return try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}
