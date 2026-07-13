package com.reef.platform.api

/**
 * Owns account-risk/circuit-breaker/price-collar admin read+write routes and
 * protective-control audit logging. Does NOT own the inline guardrail checks run
 * during order-command dispatch (accountRiskViolation/commandCircuitBreakerViolation/
 * instrumentPriceCollarViolation and friends) — those stay with the hot-path mutation
 * dispatcher (OrderCommandDispatcher) since they're perf-sensitive and only ever called
 * from there, not from these admin routes.
 */
internal class RiskGuardrailGateway(
    private val api: PlatformApi,
    private val accountRiskControlStore: AccountRiskControlStore?,
    private val accountRiskDecisionLog: AccountRiskDecisionLog?,
    private val commandCircuitBreakerStore: CommandCircuitBreakerStore?,
    private val instrumentPriceCollarStore: InstrumentPriceCollarStore?,
    private val adminSessionAuth: AdminSessionAuth
) {
    fun abuseStatsJson(stats: AbuseProtectionStats): String {
        return JsonCodec.writeObject(
            "mode" to stats.mode,
            "enabled" to stats.enabled,
            "warningOnly" to stats.warningOnly,
            "maxRejects" to stats.maxRejects,
            "windowSeconds" to stats.windowSeconds,
            "blockSeconds" to stats.blockSeconds,
            "trackedRejectCodes" to stats.trackedRejectCodes.sorted(),
            "trackedRoutes" to stats.trackedRoutes.sorted(),
            "routePolicyOverrides" to stats.routePolicyOverrides.toSortedMap(),
            "trips" to stats.trips,
            "blocks" to stats.blocks,
            "releases" to stats.releases,
            "activeBlockedClients" to stats.activeBlockedClients
        )
    }

    fun accountRiskControlsJson(): String {
        val controls = accountRiskControlStore?.listControls().orEmpty()
        return JsonCodec.writeObject(
            "controls" to controls.map { control ->
                mapOf(
                    "scopeType" to control.scopeType,
                    "scopeId" to control.scopeId,
                    "decision" to control.decision.name,
                    "reason" to control.reason,
                    "maxQuantityUnits" to control.maxQuantityUnits,
                    "maxNotional" to control.maxNotional,
                    "currency" to control.currency,
                    "updatedAt" to control.updatedAt
                )
            },
            "controlsCount" to controls.size
        )
    }

    fun accountRiskDecisionsJson(limit: Int): String {
        val boundedLimit = limit.coerceIn(1, 500)
        val decisions = accountRiskDecisionLog?.recentDecisions(boundedLimit).orEmpty()
        return JsonCodec.writeObject(
            "decisions" to decisions.map { decision ->
                mapOf(
                    "decisionId" to decision.decisionId,
                    "decidedAt" to decision.decidedAt,
                    "decision" to decision.decision.name,
                    "code" to decision.code,
                    "message" to decision.message,
                    "clientId" to decision.clientId,
                    "route" to decision.route,
                    "commandType" to decision.commandType,
                    "commandId" to decision.commandId,
                    "correlationId" to decision.correlationId,
                    "actorId" to decision.actorId,
                    "participantId" to decision.participantId,
                    "accountId" to decision.accountId,
                    "botId" to decision.botId,
                    "venueSessionId" to decision.venueSessionId,
                    "instrumentId" to decision.instrumentId,
                    "orderId" to decision.orderId,
                    "quantityUnits" to decision.quantityUnits,
                    "limitPrice" to decision.limitPrice,
                    "currency" to decision.currency
                )
            },
            "decisionsCount" to decisions.size,
            "limit" to boundedLimit
        )
    }

    fun commandCircuitBreakersJson(): String {
        val breakers = commandCircuitBreakerStore?.listBreakers().orEmpty()
        return JsonCodec.writeObject(
            "breakers" to breakers.map { breaker ->
                mapOf(
                    "scopeType" to breaker.scopeType,
                    "scopeId" to breaker.scopeId,
                    "tripped" to breaker.tripped,
                    "reason" to breaker.reason,
                    "updatedAt" to breaker.updatedAt
                )
            },
            "breakersCount" to breakers.size
        )
    }

    fun instrumentPriceCollarsJson(): String {
        val collars = instrumentPriceCollarStore?.listCollars().orEmpty()
        return JsonCodec.writeObject(
            "collars" to collars.map { collar ->
                mapOf(
                    "instrumentId" to collar.instrumentId,
                    "minPrice" to collar.minPrice,
                    "maxPrice" to collar.maxPrice,
                    "currency" to collar.currency,
                    "reason" to collar.reason,
                    "updatedAt" to collar.updatedAt
                )
            },
            "collarsCount" to collars.size
        )
    }

    fun setAccountRiskControlResponse(body: String): PlatformHotPathResponse {
        val store = accountRiskControlStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "account risk control store unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val scopeType = normalizeAccountRiskScope(json.string("scopeType").ifBlank { json.string("scope") })
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid account risk scope"))
        val scopeId = json.string("scopeId").ifBlank { json.string("id") }
        if (scopeId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scopeId is required"))
        }
        val decision = normalizeAccountRiskDecision(json.string("decision"))
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid account risk decision"))
        val reason = json.string("reason")
        val maxQuantityUnits = json.string("maxQuantityUnits")
        if (maxQuantityUnits.isNotBlank() && maxQuantityUnits.toBigDecimalOrNull() == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid maxQuantityUnits"))
        }
        val maxNotional = json.string("maxNotional")
        if (maxNotional.isNotBlank() && maxNotional.toBigDecimalOrNull() == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid maxNotional"))
        }
        val currency = json.string("currency").uppercase()
        val principal = adminSessionAuth.currentPrincipal()
        val actorId = principal.actorId
        val correlationId = principal.correlationId
        val previous = store.listControls().firstOrNull { it.scopeType == scopeType && it.scopeId == scopeId }

        store.upsertControl(scopeType, scopeId, decision, reason, maxQuantityUnits, maxNotional, currency)
        val current = store.listControls().firstOrNull { it.scopeType == scopeType && it.scopeId == scopeId }
            ?: AccountRiskControl(scopeType, scopeId, decision, reason, maxQuantityUnits, maxNotional, currency)
        recordProtectiveControlAudit(
            actorId = actorId,
            correlationId = correlationId,
            eventType = "AccountRiskControlChanged",
            targetId = "$scopeType:$scopeId",
            payload = mapOf(
                "controlType" to "account-risk",
                "scopeType" to scopeType,
                "scopeId" to scopeId,
                "previousDecision" to previous?.decision?.name.orEmpty(),
                "previousReason" to previous?.reason.orEmpty(),
                "previousMaxQuantityUnits" to previous?.maxQuantityUnits.orEmpty(),
                "previousMaxNotional" to previous?.maxNotional.orEmpty(),
                "previousCurrency" to previous?.currency.orEmpty(),
                "decision" to decision.name,
                "reason" to reason,
                "maxQuantityUnits" to maxQuantityUnits,
                "maxNotional" to maxNotional,
                "currency" to currency
            )
        )

        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "control" to mapOf(
                    "scopeType" to current.scopeType,
                    "scopeId" to current.scopeId,
                    "decision" to current.decision.name,
                    "reason" to current.reason,
                    "maxQuantityUnits" to current.maxQuantityUnits,
                    "maxNotional" to current.maxNotional,
                    "currency" to current.currency,
                    "updatedAt" to current.updatedAt
                )
            )
        )
    }

    fun setCommandCircuitBreakerResponse(body: String): PlatformHotPathResponse {
        val store = commandCircuitBreakerStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "command circuit breaker store unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val scopeType = normalizeCircuitBreakerScope(json.string("scopeType").ifBlank { json.string("scope") })
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid circuit breaker scope"))
        val scopeId = if (scopeType == "GLOBAL") {
            json.string("scopeId").ifBlank { "*" }
        } else {
            json.string("scopeId").ifBlank { json.string("id") }
        }
        if (scopeId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scopeId is required"))
        }
        val tripped = normalizeBreakerTripState(json.string("tripped").ifBlank { json.string("action") })
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid circuit breaker action"))
        val reason = json.string("reason")
        val principal = adminSessionAuth.currentPrincipal()
        val actorId = principal.actorId
        val correlationId = principal.correlationId
        val normalizedScopeId = if (scopeType == "GLOBAL") "*" else scopeId
        val previous = store.listBreakers().firstOrNull { it.scopeType == scopeType && it.scopeId == normalizedScopeId }

        store.setBreaker(scopeType, normalizedScopeId, tripped, reason)
        val current = store.listBreakers().firstOrNull { it.scopeType == scopeType && it.scopeId == normalizedScopeId }
            ?: CommandCircuitBreakerState(scopeType, normalizedScopeId, tripped, reason)
        recordProtectiveControlAudit(
            actorId = actorId,
            correlationId = correlationId,
            eventType = "CommandCircuitBreakerChanged",
            targetId = "$scopeType:$normalizedScopeId",
            payload = mapOf(
                "controlType" to "command-circuit-breaker",
                "scopeType" to scopeType,
                "scopeId" to normalizedScopeId,
                "previousTripped" to (previous?.tripped ?: false),
                "previousReason" to previous?.reason.orEmpty(),
                "tripped" to tripped,
                "reason" to reason
            )
        )

        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "breaker" to mapOf(
                    "scopeType" to current.scopeType,
                    "scopeId" to current.scopeId,
                    "tripped" to current.tripped,
                    "reason" to current.reason,
                    "updatedAt" to current.updatedAt
                )
            )
        )
    }

    fun setInstrumentPriceCollarResponse(body: String): PlatformHotPathResponse {
        val store = instrumentPriceCollarStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "instrument price collar store unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val instrumentId = json.string("instrumentId").ifBlank { json.string("id") }
        if (instrumentId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "instrumentId is required"))
        }
        val minPrice = json.string("minPrice")
        val maxPrice = json.string("maxPrice")
        val parsedMin = minPrice.toBigDecimalOrNull()
        val parsedMax = maxPrice.toBigDecimalOrNull()
        if (minPrice.isNotBlank() && parsedMin == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid minPrice"))
        }
        if (maxPrice.isNotBlank() && parsedMax == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid maxPrice"))
        }
        if (parsedMin != null && parsedMax != null && parsedMax < parsedMin) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "maxPrice must be greater than or equal to minPrice"))
        }
        val currency = json.string("currency").uppercase()
        val reason = json.string("reason")
        val principal = adminSessionAuth.currentPrincipal()
        val actorId = principal.actorId
        val correlationId = principal.correlationId
        val previous = store.listCollars().firstOrNull { it.instrumentId == instrumentId }

        store.setCollar(instrumentId, minPrice, maxPrice, currency, reason)
        val current = store.listCollars().firstOrNull { it.instrumentId == instrumentId }
            ?: InstrumentPriceCollarState(instrumentId, minPrice, maxPrice, currency, reason)
        recordProtectiveControlAudit(
            actorId = actorId,
            correlationId = correlationId,
            eventType = "InstrumentPriceCollarChanged",
            targetId = instrumentId,
            payload = mapOf(
                "controlType" to "instrument-price-collar",
                "instrumentId" to instrumentId,
                "previousMinPrice" to previous?.minPrice.orEmpty(),
                "previousMaxPrice" to previous?.maxPrice.orEmpty(),
                "previousCurrency" to previous?.currency.orEmpty(),
                "previousReason" to previous?.reason.orEmpty(),
                "minPrice" to minPrice,
                "maxPrice" to maxPrice,
                "currency" to currency,
                "reason" to reason
            )
        )

        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "collar" to mapOf(
                    "instrumentId" to current.instrumentId,
                    "minPrice" to current.minPrice,
                    "maxPrice" to current.maxPrice,
                    "currency" to current.currency,
                    "reason" to current.reason,
                    "updatedAt" to current.updatedAt
                )
            )
        )
    }

    private fun recordProtectiveControlAudit(
        actorId: String,
        correlationId: String,
        eventType: String,
        targetId: String,
        payload: Map<String, Any?>
    ) {
        try {
            api.recordAdminEvent(actorId, correlationId, eventType, targetId, payload)
        } catch (ex: Exception) {
            System.err.println(
                "protective_control_audit_failed eventType=$eventType targetId=$targetId message=${JsonFields.escape(ex.message ?: "unknown")}"
            )
        }
    }

    private fun normalizeAccountRiskScope(value: String): String? {
        return when (value.trim().lowercase()) {
            "account" -> "ACCOUNT"
            "bot" -> "BOT"
            else -> null
        }
    }

    private fun normalizeAccountRiskDecision(value: String): AccountRiskDecision? {
        return when (value.trim().lowercase()) {
            "allow" -> AccountRiskDecision.ALLOW
            "reject" -> AccountRiskDecision.REJECT
            "backpressure" -> AccountRiskDecision.BACKPRESSURE
            "disabled-bot", "disabled_bot" -> AccountRiskDecision.DISABLED_BOT
            else -> null
        }
    }

    private fun normalizeCircuitBreakerScope(value: String): String? {
        return when (value.trim().lowercase()) {
            "global" -> "GLOBAL"
            "venue-session", "venue_session" -> "VENUE_SESSION"
            "instrument" -> "INSTRUMENT"
            else -> null
        }
    }

    private fun normalizeBreakerTripState(value: String): Boolean? {
        return when (value.trim().lowercase()) {
            "true", "trip", "tripped", "on" -> true
            "false", "reset", "clear", "off" -> false
            else -> null
        }
    }
}
