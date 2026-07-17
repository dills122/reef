package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.IntradayBar
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.OwnExecutionView
import com.reef.platform.domain.OwnOrderView
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.PublicTradeTapeEntry
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.SupportedIntradayBarIntervals
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.CanonicalCommandOutcome
import com.reef.platform.infrastructure.persistence.CanonicalCommandResult
import com.reef.platform.infrastructure.persistence.CanonicalSubmitOutcome
import com.reef.platform.infrastructure.persistence.MarketDataDepthLevel
import com.reef.platform.infrastructure.persistence.MarketDataDepthSnapshot
import com.reef.platform.infrastructure.persistence.MarketDataSnapshot
import com.reef.platform.infrastructure.persistence.OrderLifecycleState
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import com.reef.platform.infrastructure.persistence.ProjectionStatus
import com.reef.platform.infrastructure.persistence.VenueEventBatchCommandReference
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.CompletableFuture

private const val MaxIntradayBarBuckets = 1_500L

class PlatformApi(
    private val orderService: OrderApplicationService = OrderApplicationService()
) {
    private val defaultProjectionSource = "venue-event-batch"
    private val defaultVenueProjectionName = "runtime-normalized-venue-outcomes"
    private val defaultMarketDataProjectionName = "market-data-top-of-book"

    fun health(): String {
        return """{"service":"platform-runtime","status":"ok"}"""
    }

    fun submitOrder(body: String): String {
        val command = HotPathMetrics.time("api.parse.submitOrder") {
            PlatformCommandParsers.submitOrder(body)
        }
        val result = HotPathMetrics.time("api.orderService.submitOrder") {
            orderService.submitOrder(command)
        }
        return HotPathMetrics.time("api.response.serializeSubmitOrder") {
            toJson(result)
        }
    }

    fun prepareSubmitOrder(body: String): PersistableSubmitOutcome {
        val command = HotPathMetrics.time("api.parse.submitOrder") {
            PlatformCommandParsers.submitOrder(body)
        }
        return HotPathMetrics.time("api.orderService.prepareSubmitOrder") {
            orderService.prepareSubmitOrder(command)
        }
    }

    fun prepareSubmitOrderAsync(body: String): CompletableFuture<PersistableSubmitOutcome> {
        val command = HotPathMetrics.time("api.parse.submitOrder") {
            PlatformCommandParsers.submitOrder(body)
        }
        return prepareSubmitOrderAsync(command)
    }

    fun prepareSubmitOrderAsync(command: SubmitOrderCommand): CompletableFuture<PersistableSubmitOutcome> {
        return HotPathMetrics.time("api.orderService.prepareSubmitOrderAsync") {
            orderService.prepareSubmitOrderAsync(command)
        }
    }

    fun prepareCancelOrder(body: String): PersistableSubmitOutcome {
        val command = HotPathMetrics.time("api.parse.cancelOrder") {
            PlatformCommandParsers.cancelOrder(body)
        }
        return prepareCancelOrder(command)
    }

    fun prepareCancelOrder(command: CancelOrderCommand): PersistableSubmitOutcome {
        return HotPathMetrics.time("api.orderService.prepareCancelOrder") {
            orderService.prepareCancelOrder(command)
        }
    }

    fun prepareModifyOrder(body: String): PersistableSubmitOutcome {
        val command = HotPathMetrics.time("api.parse.modifyOrder") {
            PlatformCommandParsers.modifyOrder(body)
        }
        return prepareModifyOrder(command)
    }

    fun prepareModifyOrder(command: ModifyOrderCommand): PersistableSubmitOutcome {
        return HotPathMetrics.time("api.orderService.prepareModifyOrder") {
            orderService.prepareModifyOrder(command)
        }
    }

    fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {
        orderService.persistSubmitOutcomes(outcomes)
    }

    fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        orderService.appendCanonicalSubmitOutcomes(outcomes)
    }

    fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int> = emptyList()): Long {
        return orderService.projectCanonicalSubmitOutcomes(projectionName, batchSize, partitions)
    }

    fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int> = emptyList(),
        includeFills: Boolean = true,
        eventStream: String = ""
    ): Long {
        return orderService.projectCanonicalCommandOutcomes(projectionName, batchSize, partitions, includeFills, eventStream)
    }

    fun projectionStatus(
        projectionName: String,
        partitions: List<Int> = emptyList(),
        source: String = "canonical-submit"
    ): ProjectionStatus {
        return orderService.projectionStatus(projectionName, partitions, source)
    }

    fun dataAvailability(
        venueProjectionName: String = defaultVenueProjectionName,
        marketDataProjectionName: String = defaultMarketDataProjectionName,
        source: String = defaultProjectionSource
    ): String {
        val venueStatus = projectionStatus(venueProjectionName, source = source)
        val marketDataStatus = projectionStatus(marketDataProjectionName, source = source)
        return JsonCodec.writeObject(
            "generatedAt" to java.time.Instant.now().toString(),
            "source" to source,
            "projections" to listOf(
                projectionStatusMap(venueStatus, "canonical venue outcome projection"),
                projectionStatusMap(marketDataStatus, "top-of-book market data projection")
            ),
            "surfaces" to listOf(
                surfaceAvailability(
                    name = "marketDataSnapshots",
                    endpoint = "/api/v1/market-data/snapshots/{instrumentId}",
                    source = "runtime.market_data_snapshots",
                    freshness = "projection-watermark",
                    status = marketDataStatus,
                    scope = "public-market-data",
                    requiredQuery = emptyList(),
                    optionalQuery = listOf("projectionName")
                ),
                surfaceAvailability(
                    name = "marketDataDepth",
                    endpoint = "/api/v1/market-data/depth/{instrumentId}",
                    source = "runtime.order_lifecycle_state",
                    freshness = "read-time bounded aggregation",
                    status = venueStatus,
                    scope = "public-market-data",
                    requiredQuery = emptyList(),
                    optionalQuery = listOf("levels", "projectionName", "sourceProjectionName"),
                    notes = "venueSessionId filtering is not exposed until runtime.orders or order_lifecycle_state persist venue_session_id"
                ),
                surfaceAvailability(
                    name = "tradeTape",
                    endpoint = "/api/v1/market-data/trades/{instrumentId}",
                    source = "runtime.trades",
                    freshness = "durable fact rows",
                    status = venueStatus,
                    scope = "public-market-data",
                    requiredQuery = emptyList(),
                    optionalQuery = listOf("limit", "before")
                ),
                surfaceAvailability(
                    name = "intradayBars",
                    endpoint = "/api/v1/market-data/bars/{instrumentId}",
                    source = "runtime.trades",
                    freshness = "durable fact row aggregation",
                    status = venueStatus,
                    scope = "public-market-data",
                    requiredQuery = listOf("interval", "start", "end"),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "currentOrders",
                    endpoint = "/api/v1/orders/current",
                    source = "runtime.order_lifecycle_state",
                    freshness = "dirty-tracked lifecycle projection",
                    status = venueStatus,
                    scope = "participant-own-orders",
                    requiredQuery = listOf("participantId"),
                    optionalQuery = listOf("instrumentId", "limit")
                ),
                surfaceAvailability(
                    name = "orderHistory",
                    endpoint = "/api/v1/orders/history",
                    source = "runtime.order_lifecycle_state",
                    freshness = "dirty-tracked lifecycle projection",
                    status = venueStatus,
                    scope = "participant-own-orders",
                    requiredQuery = listOf("participantId"),
                    optionalQuery = listOf("instrumentId", "limit")
                ),
                surfaceAvailability(
                    name = "orderFills",
                    endpoint = "/api/v1/orders/fills",
                    source = "runtime.orders + runtime.executions",
                    freshness = "durable execution rows scoped by participant order ownership",
                    status = venueStatus,
                    scope = "participant-own-orders",
                    requiredQuery = listOf("participantId"),
                    optionalQuery = listOf("instrumentId", "limit")
                ),
                surfaceAvailability(
                    name = "settlementFacts",
                    endpoint = "/api/v1/settlement/facts/{scenarioRunId}",
                    source = "settlement append-only fact store",
                    freshness = "durable fact rows",
                    status = venueStatus,
                    scope = "scenario-settlement-evidence",
                    requiredQuery = emptyList(),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "settlementObligations",
                    endpoint = "/api/v1/settlement/obligations/{scenarioRunId}",
                    source = "settlement obligation projection over append-only facts",
                    freshness = "derived from durable settlement facts",
                    status = venueStatus,
                    scope = "scenario-settlement-evidence",
                    requiredQuery = emptyList(),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "settlementLedger",
                    endpoint = "/api/v1/settlement/ledger/{scenarioRunId}",
                    source = "append-only settlement ledger entry facts",
                    freshness = "replayable participant/account/asset balances derived from ledger facts",
                    status = venueStatus,
                    scope = "scenario-settlement-evidence",
                    requiredQuery = emptyList(),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "settlementExceptions",
                    endpoint = "/api/v1/settlement/exceptions/{scenarioRunId}",
                    source = "settlement append-only fact store exception projection",
                    freshness = "rebuildable exception queue over clearing rejections and settlement breaks",
                    status = venueStatus,
                    scope = "scenario-settlement-evidence",
                    requiredQuery = emptyList(),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "settlementProof",
                    endpoint = "/api/v1/settlement/proof/{scenarioRunId}",
                    source = "settlement append-only fact store + replayable ledger projection",
                    freshness = "rebuildable proof projection",
                    status = venueStatus,
                    scope = "scenario-settlement-evidence",
                    requiredQuery = emptyList(),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "settlementScore",
                    endpoint = "/api/v1/settlement/score/{scenarioRunId}",
                    source = "settlement append-only fact store + ledger/obligation projections",
                    freshness = "rebuildable scoring projection",
                    status = venueStatus,
                    scope = "scenario-settlement-score",
                    requiredQuery = emptyList(),
                    optionalQuery = emptyList()
                ),
                surfaceAvailability(
                    name = "arenaLeaderboard",
                    endpoint = "/api/v1/arena/leaderboard",
                    source = "arena.run_bot_results + arena.bots",
                    freshness = "durable rows written after run completion",
                    status = venueStatus,
                    scope = "public-game-data",
                    requiredQuery = listOf("modeId", "scoringPolicyVersion"),
                    optionalQuery = listOf("limit"),
                    notes = "rows are pre-filtered by score_eligible/public_leaderboard/disqualified at the query level"
                )
            )
        )
    }

    fun materializeVenueEventBatch(batch: VenueEventBatchFact): Long {
        return orderService.materializeVenueEventBatch(batch)
    }

    fun canonicalCommandOutcome(commandId: String): CanonicalCommandOutcome? {
        return orderService.canonicalCommandOutcome(commandId)
    }

    fun canonicalCommandResult(commandId: String): CanonicalCommandResult? {
        return orderService.canonicalCommandResult(commandId)
    }

    fun venueEventBatchCommandReference(commandId: String): VenueEventBatchCommandReference? {
        return orderService.venueEventBatchCommandReference(commandId)
    }

    fun submitOrderResponse(outcome: PersistableSubmitOutcome): String {
        return toJson(outcome.result)
    }

    fun cancelOrder(body: String): String {
        return toJson(orderService.cancelOrder(PlatformCommandParsers.cancelOrder(body)))
    }

    fun modifyOrder(body: String): String {
        return toJson(orderService.modifyOrder(PlatformCommandParsers.modifyOrder(body)))
    }

    fun createInstrument(body: String): String {
        val instrument = PlatformCommandParsers.instrument(body)
        orderService.createInstrument(instrument)
        return JsonCodec.writeObject("instrumentId" to instrument.instrumentId)
    }

    fun createParticipant(body: String): String {
        val participant = PlatformCommandParsers.participant(body)
        orderService.createParticipant(participant)
        return JsonCodec.writeObject("participantId" to participant.participantId)
    }

    fun createAccount(body: String): String {
        val account = PlatformCommandParsers.account(body)
        orderService.createAccount(account)
        return JsonCodec.writeObject("accountId" to account.accountId)
    }

    fun createRole(body: String): String {
        val role = PlatformCommandParsers.roleDefinition(body)
        orderService.createRole(role)
        return JsonCodec.writeObject("roleId" to role.roleId)
    }

    fun assignRole(body: String): String {
        val binding = PlatformCommandParsers.actorRoleBinding(body)
        orderService.assignRole(binding)
        return JsonCodec.writeObject("actorId" to binding.actorId, "roleId" to binding.roleId)
    }

    fun instruments(): String {
        return JsonCodec.writeObject("instruments" to orderService.instruments().map { instrument ->
            mapOf(
                "instrumentId" to instrument.instrumentId,
                "symbol" to instrument.symbol
            )
        })
    }

    fun participants(): String {
        return JsonCodec.writeObject("participants" to orderService.participants().map { participant ->
            mapOf(
                "participantId" to participant.participantId,
                "name" to participant.name
            )
        })
    }

    fun accounts(): String {
        return JsonCodec.writeObject("accounts" to orderService.accounts().map { account ->
            mapOf(
                "accountId" to account.accountId,
                "participantId" to account.participantId
            )
        })
    }

    fun roles(): String {
        return JsonCodec.writeObject("roles" to orderService.roles().map { role ->
            mapOf(
                "roleId" to role.roleId,
                "permissions" to role.permissions
            )
        })
    }

    fun actorRoles(actorId: String): String {
        return JsonCodec.writeObject("actorRoles" to orderService.actorRoleBindings(actorId).map { binding ->
            mapOf(
                "actorId" to binding.actorId,
                "roleId" to binding.roleId
            )
        })
    }

    fun findOrderByClientOrderId(participantId: String, clientOrderId: String): PersistedOrder? {
        return orderService.findOrderByClientOrderId(participantId, clientOrderId)
    }

    fun order(orderId: String): String = orderWithStatus(orderId).body

    // Callers that need the HTTP status alongside the body (PlatformHttpServer)
    // should use this instead of sniffing the "error" field out of order()'s
    // JSON string: that string-matching approach breaks silently if the
    // success-path payload ever legitimately contains the same substring.
    fun orderWithStatus(orderId: String): ApiLookupResult {
        val order = orderService.persistedOrder(orderId)
        if (order == null) {
            return ApiLookupResult(
                found = false,
                body = JsonCodec.writeObject("error" to "order not found", "orderId" to orderId)
            )
        }

        return ApiLookupResult(
            found = true,
            body = JsonCodec.writeObject(
                "order" to toOrderMap(order),
                "lifecycleState" to orderService.orderLifecycleState(orderId)?.toMap(),
                "executions" to orderService.persistedExecutions(orderId).map { it.toMap() },
                "trades" to orderService.persistedTrades(orderId).map { it.toMap() }
            )
        )
    }

    fun orderEvents(orderId: String): String {
        return JsonCodec.writeObject(
            "orderId" to orderId,
            "events" to orderService.persistedEvents(orderId).map { it.toMap() }
        )
    }

    fun events(): String {
        return JsonCodec.writeObject("events" to orderService.events().map { it.toMap() })
    }

    fun recentEvents(limit: Int): String {
        return JsonCodec.writeObject("events" to orderService.recentEvents(limit).map { it.toMap() })
    }

    fun traceEvents(traceId: String): String {
        return JsonCodec.writeObject(
            "traceId" to traceId,
            "events" to orderService.persistedTraceEvents(traceId).map { it.toMap() }
        )
    }

    fun recordAdminEvent(
        actorId: String,
        correlationId: String,
        eventType: String,
        targetId: String,
        payload: Map<String, Any?>
    ) {
        val normalizedActor = actorId.ifBlank { "internal-admin" }
        val normalizedCorrelation = correlationId.ifBlank { "internal-admin" }
        orderService.saveRuntimeEvent(
            RuntimeEvent(
                eventId = "evt-admin-${UUID.randomUUID()}",
                eventType = eventType,
                orderId = targetId,
                traceId = "admin:$normalizedActor",
                causationId = eventType,
                correlationId = normalizedCorrelation,
                producer = "platform-runtime-admin",
                schemaVersion = "v1",
                occurredAt = java.time.Instant.now().toString(),
                actorId = normalizedActor,
                payloadJson = JsonCodec.writeObject(*payload.toList().toTypedArray())
            )
        )
    }

    fun orders(): String {
        return JsonCodec.writeObject("orders" to orderService.persistedOrders().map { toOrderMap(it) })
    }

    fun refreshMarketDataSnapshots(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ): String {
        val refreshed = refreshMarketDataSnapshotsCount(projectionName, sourceProjectionName)
        return JsonCodec.writeObject(
            "projectionName" to projectionName,
            "sourceProjectionName" to sourceProjectionName,
            "refreshed" to refreshed
        )
    }

    fun refreshMarketDataSnapshotsCount(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ): Long {
        return orderService.refreshMarketDataSnapshots(projectionName, sourceProjectionName)
    }

    fun projectMarketDataSnapshotsCount(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes",
        batchSize: Int = 500
    ): Long {
        return orderService.projectMarketDataSnapshots(projectionName, sourceProjectionName, batchSize)
    }

    fun rebuildOrderLifecycleState(): String {
        return JsonCodec.writeObject("rebuilt" to orderService.rebuildOrderLifecycleState())
    }

    fun rebuildOrderLifecycleStateCount(): Long {
        return orderService.rebuildOrderLifecycleState()
    }

    fun projectOrderLifecycleStateCount(batchSize: Int): Long {
        return orderService.projectOrderLifecycleState(batchSize)
    }

    fun marketDataSnapshot(
        instrumentId: String,
        projectionName: String = "market-data-top-of-book"
    ): String = marketDataSnapshotWithStatus(instrumentId, projectionName).body

    fun marketDataSnapshotWithStatus(
        instrumentId: String,
        projectionName: String = "market-data-top-of-book"
    ): ApiLookupResult {
        val snapshot = orderService.marketDataSnapshot(instrumentId, projectionName)
        if (snapshot == null) {
            return ApiLookupResult(
                found = false,
                body = JsonCodec.writeObject(
                    "error" to "market data snapshot not found",
                    "instrumentId" to instrumentId,
                    "projectionName" to projectionName
                )
            )
        }
        return ApiLookupResult(found = true, body = JsonCodec.writeObject("snapshot" to snapshot.toMap()))
    }

    fun marketDataDepthSnapshot(
        instrumentId: String,
        levels: Int = 5,
        projectionName: String = "market-data-depth",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ): String = marketDataDepthSnapshotWithStatus(instrumentId, levels, projectionName, sourceProjectionName).body

    fun marketDataDepthSnapshotWithStatus(
        instrumentId: String,
        levels: Int = 5,
        projectionName: String = "market-data-depth",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ): ApiLookupResult {
        val snapshot = orderService.marketDataDepthSnapshot(instrumentId, levels, projectionName, sourceProjectionName)
        if (snapshot == null) {
            return ApiLookupResult(
                found = false,
                body = JsonCodec.writeObject(
                    "error" to "market data depth not found",
                    "instrumentId" to instrumentId,
                    "projectionName" to projectionName
                )
            )
        }
        return ApiLookupResult(found = true, body = JsonCodec.writeObject("depth" to snapshot.toMap()))
    }

    fun trades(): String {
        return JsonCodec.writeObject("trades" to orderService.persistedTrades().map { it.toMap() })
    }

    fun recentTrades(limit: Int): String {
        return JsonCodec.writeObject("trades" to orderService.recentTrades(limit).map { it.toMap() })
    }

    fun tradeTape(instrumentId: String, limit: Int = 50, beforeSequence: Long? = null): String {
        val entries = orderService.tradeTape(instrumentId, limit, beforeSequence)
        return JsonCodec.writeObject(
            "instrumentId" to instrumentId,
            "meta" to mapOf(
                "source" to "runtime.trades",
                "freshness" to "durable fact rows",
                "limit" to limit.coerceIn(1, 500),
                "before" to (beforeSequence?.toString() ?: "")
            ),
            "trades" to entries.map { it.toMap() }
        )
    }

    fun intradayBars(instrumentId: String, interval: String, start: String, end: String): String =
        intradayBarsWithStatus(instrumentId, interval, start, end).body

    fun intradayBarsWithStatus(instrumentId: String, interval: String, start: String, end: String): ApiLookupResult {
        if (interval !in SupportedIntradayBarIntervals) {
            return ApiLookupResult(
                found = false,
                body = JsonCodec.writeObject(
                    "error" to "unsupported interval",
                    "instrumentId" to instrumentId,
                    "interval" to interval
                )
            )
        }
        val rangeError = intradayBarsRangeError(interval, start, end)
        if (rangeError != null) {
            return ApiLookupResult(
                found = false,
                body = JsonCodec.writeObject(
                    "error" to rangeError,
                    "instrumentId" to instrumentId,
                    "interval" to interval
                )
            )
        }
        val bars = orderService.intradayBars(instrumentId, interval, start, end)
        return ApiLookupResult(
            found = true,
            body = JsonCodec.writeObject(
                "instrumentId" to instrumentId,
                "interval" to interval,
                "meta" to mapOf(
                    "source" to "runtime.trades",
                    "freshness" to "durable fact row aggregation",
                    "start" to start,
                    "end" to end
                ),
                "bars" to bars.map { it.toMap() }
            )
        )
    }

    private fun intradayBarsRangeError(interval: String, start: String, end: String): String? {
        val startInstant = parseInstantOrNull(start) ?: return "invalid time range"
        val endInstant = parseInstantOrNull(end) ?: return "invalid time range"
        if (!endInstant.isAfter(startInstant)) return "invalid time range"
        val intervalDuration = when (interval) {
            "1m" -> Duration.ofMinutes(1)
            "5m" -> Duration.ofMinutes(5)
            "15m" -> Duration.ofMinutes(15)
            "1h" -> Duration.ofHours(1)
            "1d" -> Duration.ofDays(1)
            else -> return "unsupported interval"
        }
        val rangeMillis = try {
            Duration.between(startInstant, endInstant).toMillis()
        } catch (_: ArithmeticException) {
            return "invalid time range"
        }
        val intervalMillis = intervalDuration.toMillis()
        val bucketCount = (rangeMillis + intervalMillis - 1) / intervalMillis
        return if (bucketCount > MaxIntradayBarBuckets) "intraday bars range too large" else null
    }

    private fun parseInstantOrNull(value: String): Instant? {
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun ownOrders(participantId: String, openOnly: Boolean, instrumentId: String = "", limit: Int = 0): String {
        val boundedLimit = limit.coerceIn(0, 500)
        return JsonCodec.writeObject(
            "participantId" to participantId,
            "meta" to mapOf(
                "source" to "runtime.order_lifecycle_state",
                "freshness" to "dirty-tracked lifecycle projection",
                "scope" to "participant",
                "openOnly" to openOnly,
                "instrumentId" to instrumentId,
                "limit" to boundedLimit
            ),
            "orders" to orderService.ordersForParticipant(participantId, openOnly, instrumentId, boundedLimit).map { it.toMap() }
        )
    }

    fun ownExecutions(participantId: String, instrumentId: String = "", limit: Int = 0): String {
        val boundedLimit = limit.coerceIn(0, 500)
        return JsonCodec.writeObject(
            "participantId" to participantId,
            "meta" to mapOf(
                "source" to "runtime.orders + runtime.executions",
                "freshness" to "durable execution rows scoped by participant order ownership",
                "scope" to "participant",
                "instrumentId" to instrumentId,
                "limit" to boundedLimit
            ),
            "fills" to orderService.executionsForParticipant(participantId, instrumentId, boundedLimit).map { it.toMap() }
        )
    }

    private fun toJson(result: SubmitOrderResult): String {
        val accepted = result.accepted
        if (accepted != null) {
            return JsonCodec.writeObject(
                "accepted" to mapOf(
                    "eventId" to accepted.eventId,
                    "orderId" to accepted.orderId,
                    "engineOrderId" to accepted.engineOrderId,
                    "occurredAt" to accepted.occurredAt
                ),
                "executions" to result.executions.map { it.toMap() },
                "trades" to result.trades.map { it.toMap() }
            )
        }

        val rejected = result.rejected
        return JsonCodec.writeObject(
            "rejected" to mapOf(
                "eventId" to rejected?.eventId.orEmpty(),
                "orderId" to rejected?.orderId.orEmpty(),
                "code" to rejected?.code.orEmpty(),
                "reason" to rejected?.reason.orEmpty(),
                "occurredAt" to rejected?.occurredAt.orEmpty()
            ),
            "executions" to emptyList<Any>(),
            "trades" to emptyList<Any>()
        )
    }

    private fun ExecutionCreated.toMap(): Map<String, Any> = mapOf(
        "eventId" to eventId,
        "executionId" to executionId,
        "orderId" to orderId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "executionPrice" to executionPrice,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun TradeCreated.toMap(): Map<String, Any> = mapOf(
        "eventId" to eventId,
        "tradeId" to tradeId,
        "executionId" to executionId,
        "buyOrderId" to buyOrderId,
        "sellOrderId" to sellOrderId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "price" to price,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun PublicTradeTapeEntry.toMap(): Map<String, Any> = mapOf(
        "sequence" to sequence,
        "tradeId" to tradeId,
        "instrumentId" to instrumentId,
        "quantityUnits" to quantityUnits,
        "price" to price,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun IntradayBar.toMap(): Map<String, Any> = mapOf(
        "instrumentId" to instrumentId,
        "start" to start,
        "end" to end,
        "open" to open,
        "high" to high,
        "low" to low,
        "close" to close,
        "volume" to volume
    )

    private fun OwnOrderView.toMap(): Map<String, Any> = mapOf(
        "orderId" to orderId,
        "instrumentId" to instrumentId,
        "side" to side,
        "quantityUnits" to quantityUnits,
        "remainingQuantityUnits" to remainingQuantityUnits,
        "limitPrice" to limitPrice,
        "status" to status
    )

    private fun OwnExecutionView.toMap(): Map<String, Any> = mapOf(
        "executionId" to executionId,
        "orderId" to orderId,
        "instrumentId" to instrumentId,
        "side" to side,
        "quantityUnits" to quantityUnits,
        "executionPrice" to executionPrice,
        "currency" to currency,
        "occurredAt" to occurredAt
    )

    private fun MarketDataSnapshot.toMap(): Map<String, Any> = mapOf(
        "projectionName" to projectionName,
        "sourceProjectionName" to sourceProjectionName,
        "instrumentId" to instrumentId,
        "bestBidPrice" to bestBidPrice,
        "bestBidQuantity" to bestBidQuantity,
        "bestAskPrice" to bestAskPrice,
        "bestAskQuantity" to bestAskQuantity,
        "currency" to currency,
        "lastPartitionSequence" to lastPartitionSequence,
        "lag" to lag,
        "updatedAt" to updatedAt
    )

    private fun MarketDataDepthSnapshot.toMap(): Map<String, Any> = mapOf(
        "projectionName" to projectionName,
        "sourceProjectionName" to sourceProjectionName,
        "instrumentId" to instrumentId,
        "bidLevels" to bidLevels.map { it.toMap() },
        "askLevels" to askLevels.map { it.toMap() },
        "currency" to currency,
        "levels" to levels,
        "lastPartitionSequence" to lastPartitionSequence,
        "lag" to lag,
        "updatedAt" to updatedAt
    )

    private fun MarketDataDepthLevel.toMap(): Map<String, Any> = mapOf(
        "price" to price,
        "quantity" to quantity
    )

    private fun OrderLifecycleState.toMap(): Map<String, Any> = mapOf(
        "orderId" to orderId,
        "engineOrderId" to engineOrderId,
        "instrumentId" to instrumentId,
        "participantId" to participantId,
        "accountId" to accountId,
        "side" to side,
        "orderType" to orderType,
        "originalQuantityUnits" to originalQuantityUnits,
        "remainingQuantityUnits" to remainingQuantityUnits,
        "filledQuantityUnits" to filledQuantityUnits,
        "limitPrice" to limitPrice,
        "currency" to currency,
        "timeInForce" to timeInForce,
        "status" to status,
        "acceptedAt" to acceptedAt,
        "lastEventAt" to lastEventAt,
        "updatedAt" to updatedAt
    )

    private fun projectionStatusMap(status: ProjectionStatus, role: String): Map<String, Any> = mapOf(
        "projectionName" to status.projectionName,
        "role" to role,
        "projectedCount" to status.projectedCount,
        "lag" to status.lag,
        "watermarks" to status.watermarks.map { watermark ->
            mapOf(
                "projectionName" to watermark.projectionName,
                "partition" to watermark.partitionId,
                "lastPartitionSequence" to watermark.lastPartitionSequence,
                "canonicalMaxPartitionSequence" to watermark.canonicalMaxPartitionSequence,
                "lag" to watermark.lag,
                "updatedAt" to watermark.updatedAt,
                "lastError" to watermark.lastError
            )
        }
    )

    private fun surfaceAvailability(
        name: String,
        endpoint: String,
        source: String,
        freshness: String,
        status: ProjectionStatus,
        scope: String,
        requiredQuery: List<String>,
        optionalQuery: List<String>,
        notes: String = ""
    ): Map<String, Any> = mapOf(
        "name" to name,
        "endpoint" to endpoint,
        "source" to source,
        "freshness" to freshness,
        "scope" to scope,
        "requiredQuery" to requiredQuery,
        "optionalQuery" to optionalQuery,
        "projectionName" to status.projectionName,
        "lag" to status.lag,
        "lastPartitionSequence" to (
            status.watermarks
                .filter { it.partitionId >= 0 }
                .maxOfOrNull { it.lastPartitionSequence } ?: 0L
            ),
        "lastUpdatedAt" to (
            status.watermarks
                .filter { it.updatedAt.isNotBlank() }
                .maxOfOrNull { it.updatedAt } ?: ""
            ),
        "notes" to notes
    )

    private fun RuntimeEvent.toMap(): Map<String, Any> = mapOf(
        "eventId" to eventId,
        "eventType" to eventType,
        "orderId" to orderId,
        "traceId" to traceId,
        "causationId" to causationId,
        "correlationId" to correlationId,
        "actorId" to actorId,
        "producer" to producer,
        "schemaVersion" to schemaVersion,
        "sequenceNumber" to sequenceNumber,
        "occurredAt" to occurredAt,
        "payloadJson" to payloadJson
    )

    private fun toOrderMap(order: PersistedOrder): Map<String, Any> = mapOf(
        "orderId" to order.orderId,
        "engineOrderId" to order.engineOrderId,
        "instrumentId" to order.instrumentId,
        "participantId" to order.participantId,
        "accountId" to order.accountId,
        "side" to order.side,
        "orderType" to order.orderType,
        "quantityUnits" to order.quantityUnits,
        "limitPrice" to order.limitPrice,
        "currency" to order.currency,
        "timeInForce" to order.timeInForce,
        "acceptedAt" to order.acceptedAt
    )
}

// Pairs a JSON response body with whether the lookup/validation it
// represents actually succeeded, so callers that need an HTTP status code
// don't have to infer it by string-matching the body's "error" field.
data class ApiLookupResult(val found: Boolean, val body: String)
