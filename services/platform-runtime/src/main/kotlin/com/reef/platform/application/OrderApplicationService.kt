package com.reef.platform.application

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.AsyncSubmitEngineGateway
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.engine.defaultEngineGateway
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.CanonicalCommandOutcome
import com.reef.platform.infrastructure.persistence.CanonicalSubmitOutcome
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.NoopRuntimePersistence
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import com.reef.platform.infrastructure.persistence.PostgresRuntimePersistence
import com.reef.platform.infrastructure.persistence.ProjectionStatus
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.RuntimePersistence
import com.reef.platform.infrastructure.persistence.VenueEventBatchCommandReference
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.util.concurrent.CompletableFuture

class OrderApplicationService(
    private val engineGateway: EngineGateway = defaultEngineGateway(),
    private val runtimePersistence: RuntimePersistence = defaultRuntimePersistence()
) {
    private val eventProducer = "platform-runtime"
    private val eventSchemaVersion = "v1"

    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return HotPathMetrics.time("runtime.submitOrder.total") {
            val outcome = prepareSubmitOrder(command)
            HotPathMetrics.time("runtime.persistence.persistSubmitOutcome") {
                runtimePersistence.persistSubmitOutcome(outcome)
            }
            outcome.result
        }
    }

    fun prepareSubmitOrder(command: SubmitOrderCommand): PersistableSubmitOutcome {
        val context = validatedSubmitContext(command)
        if (context.outcome != null) {
            return context.outcome
        }

        val result = HotPathMetrics.time("runtime.engine.submit") {
            engineGateway.submitOrder(command)
        }
        return submitOutcomeFromResult(command, context.traceId, result)
    }

    fun prepareSubmitOrderAsync(command: SubmitOrderCommand): CompletableFuture<PersistableSubmitOutcome> {
        val context = validatedSubmitContext(command)
        if (context.outcome != null) {
            return CompletableFuture.completedFuture(context.outcome)
        }

        val asyncGateway = engineGateway as? AsyncSubmitEngineGateway
        if (asyncGateway == null) {
            val result = HotPathMetrics.time("runtime.engine.submit") {
                engineGateway.submitOrder(command)
            }
            return CompletableFuture.completedFuture(submitOutcomeFromResult(command, context.traceId, result))
        }

        val started = System.nanoTime()
        return asyncGateway.submitOrderAsync(command).thenApply { result ->
            HotPathMetrics.record("runtime.engine.submit", System.nanoTime() - started)
            submitOutcomeFromResult(command, context.traceId, result)
        }
    }

    private fun validatedSubmitContext(command: SubmitOrderCommand): ValidatedSubmitContext {
        val existingResult = HotPathMetrics.time("runtime.submitResult.lookup") {
            runtimePersistence.submitResult(command.commandId)
        }
        if (existingResult != null) {
            return ValidatedSubmitContext(
                traceId = traceId(command.traceId, command.orderId),
                outcome = PersistableSubmitOutcome(
                    commandId = command.commandId,
                    result = existingResult,
                    acceptedOrder = null,
                    lifecycleEvents = emptyList()
                )
            )
        }
        val traceId = traceId(command.traceId, command.orderId)

        val authorizationError = HotPathMetrics.time("runtime.authorization") {
            rejectUnauthorizedSubmitOutcome(
                commandId = command.commandId,
                traceId = traceId,
                correlationId = command.correlationId,
                actorId = command.actorId,
                orderId = command.orderId,
                occurredAt = command.occurredAt,
                permission = Permission.ORDER_SUBMIT,
                rejectedEventType = "OrderRejected"
            )
        }
        if (authorizationError != null) {
            return ValidatedSubmitContext(traceId = traceId, outcome = authorizationError)
        }

        val validationError = HotPathMetrics.time("runtime.referenceData.validate") {
            validateReferenceData(command)
        }
        if (validationError != null) {
            val rejected = validationError.rejected
            val lifecycleEvents = if (rejected != null) {
                listOf(
                    lifecycleEvent(
                        eventId = rejected.eventId,
                        eventType = "OrderRejected",
                        orderId = rejected.orderId,
                        traceId = traceId,
                        causationId = command.commandId,
                        correlationId = command.correlationId,
                        occurredAt = rejected.occurredAt,
                        actorId = command.actorId,
                        payloadJson = commandPayload(command.commandId)
                    )
                )
            } else {
                emptyList()
            }
            return ValidatedSubmitContext(
                traceId = traceId,
                outcome = PersistableSubmitOutcome(
                    commandId = command.commandId,
                    result = validationError,
                    acceptedOrder = null,
                    lifecycleEvents = lifecycleEvents
                )
            )
        }

        return ValidatedSubmitContext(traceId = traceId)
    }

    private fun submitOutcomeFromResult(
        command: SubmitOrderCommand,
        traceId: String,
        result: SubmitOrderResult
    ): PersistableSubmitOutcome {
        val accepted = result.accepted
        var acceptedOrder: PersistedOrder? = null
        val lifecycleEvents = ArrayList<RuntimeEvent>(1 + result.executions.size + result.trades.size)
        if (accepted != null) {
            acceptedOrder = PersistedOrder(
                orderId = command.orderId,
                engineOrderId = accepted.engineOrderId,
                instrumentId = command.instrumentId,
                participantId = command.participantId,
                accountId = command.accountId,
                side = command.side,
                orderType = command.orderType,
                quantityUnits = command.quantityUnits,
                limitPrice = command.limitPrice,
                currency = command.currency,
                timeInForce = command.timeInForce,
                acceptedAt = accepted.occurredAt
            )
            lifecycleEvents.add(
                lifecycleEvent(
                    eventId = accepted.eventId,
                    eventType = "OrderAccepted",
                    orderId = accepted.orderId,
                    traceId = traceId,
                    causationId = command.commandId,
                    correlationId = command.correlationId,
                    occurredAt = accepted.occurredAt,
                    actorId = command.actorId,
                    payloadJson = commandPayload(command.commandId)
                )
            )
            result.executions.forEach { execution ->
                lifecycleEvents.add(
                    lifecycleEvent(
                        eventId = execution.eventId,
                        eventType = "ExecutionCreated",
                        orderId = execution.orderId,
                        traceId = traceId,
                        causationId = accepted.eventId,
                        correlationId = command.correlationId,
                        occurredAt = execution.occurredAt,
                        actorId = command.actorId,
                        payloadJson = commandPayload(command.commandId)
                    )
                )
            }
            result.trades.forEach { trade ->
                lifecycleEvents.add(
                    lifecycleEvent(
                        eventId = trade.eventId,
                        eventType = "TradeCreated",
                        orderId = command.orderId,
                        traceId = traceId,
                        causationId = accepted.eventId,
                        correlationId = command.correlationId,
                        occurredAt = trade.occurredAt,
                        actorId = command.actorId,
                        payloadJson = commandPayload(command.commandId)
                    )
                )
            }
        } else {
            val rejected = result.rejected
            if (rejected != null) {
                acceptedOrder = PersistedOrder(
                    orderId = command.orderId,
                    engineOrderId = "",
                    instrumentId = command.instrumentId,
                    participantId = command.participantId,
                    accountId = command.accountId,
                    side = command.side,
                    orderType = command.orderType,
                    quantityUnits = command.quantityUnits,
                    limitPrice = command.limitPrice,
                    currency = command.currency,
                    timeInForce = command.timeInForce,
                    acceptedAt = rejected.occurredAt
                )
                lifecycleEvents.add(
                    lifecycleEvent(
                        eventId = rejected.eventId,
                        eventType = "OrderRejected",
                        orderId = rejected.orderId,
                        traceId = traceId,
                        causationId = command.commandId,
                        correlationId = command.correlationId,
                        occurredAt = rejected.occurredAt,
                        actorId = command.actorId,
                        payloadJson = commandPayload(command.commandId)
                    )
                )
            }
        }

        return PersistableSubmitOutcome(
            commandId = command.commandId,
            result = result,
            acceptedOrder = acceptedOrder,
            lifecycleEvents = lifecycleEvents
        )
    }

    fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {
        if (outcomes.isEmpty()) return
        HotPathMetrics.time("runtime.persistence.persistSubmitOutcomes") {
            runtimePersistence.persistSubmitOutcomes(outcomes)
        }
    }

    fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        if (outcomes.isEmpty()) return
        HotPathMetrics.time("runtime.persistence.appendCanonicalSubmitOutcomes") {
            runtimePersistence.appendCanonicalSubmitOutcomes(outcomes)
        }
    }

    fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int> = emptyList()): Long {
        if (batchSize <= 0) return 0
        return HotPathMetrics.time("runtime.persistence.projectCanonicalSubmitOutcomes") {
            runtimePersistence.projectCanonicalSubmitOutcomes(projectionName, batchSize, partitions)
        }
    }

    fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int> = emptyList(),
        includeFills: Boolean = true,
        eventStream: String = ""
    ): Long {
        if (batchSize <= 0) return 0
        return HotPathMetrics.time("runtime.persistence.projectCanonicalCommandOutcomes") {
            runtimePersistence.projectCanonicalCommandOutcomes(projectionName, batchSize, partitions, includeFills, eventStream)
        }
    }

    fun projectionStatus(
        projectionName: String,
        partitions: List<Int> = emptyList(),
        source: String = "canonical-submit"
    ): ProjectionStatus {
        return runtimePersistence.projectionStatus(projectionName, partitions, source)
    }

    fun materializeVenueEventBatch(batch: VenueEventBatchFact): Long {
        return HotPathMetrics.time("runtime.persistence.materializeVenueEventBatch") {
            runtimePersistence.materializeVenueEventBatch(batch)
        }
    }

    fun canonicalCommandOutcome(commandId: String): CanonicalCommandOutcome? {
        return HotPathMetrics.time("runtime.persistence.canonicalCommandOutcome") {
            runtimePersistence.canonicalCommandOutcome(commandId)
        }
    }

    fun venueEventBatchCommandReference(commandId: String): VenueEventBatchCommandReference? {
        return HotPathMetrics.time("runtime.persistence.venueEventBatchCommandReference") {
            runtimePersistence.venueEventBatchCommandReference(commandId)
        }
    }

    fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        val existingResult = HotPathMetrics.time("runtime.submitResult.lookup") {
            runtimePersistence.submitResult(command.commandId)
        }
        if (existingResult != null) {
            return existingResult
        }

        val authorizationError = HotPathMetrics.time("runtime.authorization") {
            rejectUnauthorizedActor(
                commandId = command.commandId,
                traceId = traceId(command.traceId, command.orderId),
                correlationId = command.correlationId,
                actorId = command.actorId,
                orderId = command.orderId,
                occurredAt = command.occurredAt,
                permission = Permission.ORDER_CANCEL,
                rejectedEventType = "OrderCancelRejected"
            )
        }
        if (authorizationError != null) {
            return authorizationError
        }

        val result = HotPathMetrics.time("runtime.engine.cancel") {
            engineGateway.cancelOrder(command)
        }
        val traceId = traceId(command.traceId, command.orderId)
        HotPathMetrics.time("runtime.persistence.saveSubmitResult") {
            runtimePersistence.saveSubmitResult(command.commandId, result)
        }
        HotPathMetrics.time("runtime.persistence.appendLifecycleEvent") {
            appendLifecycleEvent(
                command.orderId,
                command.commandId,
                command.correlationId,
                command.actorId,
                traceId,
                result.accepted,
                result.rejected,
                "OrderCancelled",
                "OrderCancelRejected"
            )
        }
        return result
    }

    fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        val existingResult = HotPathMetrics.time("runtime.submitResult.lookup") {
            runtimePersistence.submitResult(command.commandId)
        }
        if (existingResult != null) {
            return existingResult
        }

        val authorizationError = HotPathMetrics.time("runtime.authorization") {
            rejectUnauthorizedActor(
                commandId = command.commandId,
                traceId = traceId(command.traceId, command.orderId),
                correlationId = command.correlationId,
                actorId = command.actorId,
                orderId = command.orderId,
                occurredAt = command.occurredAt,
                permission = Permission.ORDER_MODIFY,
                rejectedEventType = "OrderModifyRejected"
            )
        }
        if (authorizationError != null) {
            return authorizationError
        }

        val result = HotPathMetrics.time("runtime.engine.modify") {
            engineGateway.modifyOrder(command)
        }
        val traceId = traceId(command.traceId, command.orderId)
        HotPathMetrics.time("runtime.persistence.saveSubmitResult") {
            runtimePersistence.saveSubmitResult(command.commandId, result)
        }
        HotPathMetrics.time("runtime.persistence.appendLifecycleEvent") {
            appendLifecycleEvent(
                command.orderId,
                command.commandId,
                command.correlationId,
                command.actorId,
                traceId,
                result.accepted,
                result.rejected,
                "OrderModified",
                "OrderModifyRejected"
            )
        }
        return result
    }

    private fun appendLifecycleEvent(
        defaultOrderId: String,
        commandId: String,
        correlationId: String,
        actorId: String,
        traceId: String,
        accepted: EngineOrderAccepted?,
        rejected: EngineOrderRejected?,
        acceptedEventType: String,
        rejectedEventType: String
    ) {
        if (accepted != null) {
            runtimePersistence.saveEvent(
                lifecycleEvent(
                    eventId = accepted.eventId,
                    eventType = acceptedEventType,
                    orderId = accepted.orderId.ifBlank { defaultOrderId },
                    traceId = traceId,
                    causationId = commandId,
                    correlationId = correlationId,
                    occurredAt = accepted.occurredAt,
                    actorId = actorId,
                    payloadJson = commandPayload(commandId)
                )
            )
            return
        }

        if (rejected != null) {
            runtimePersistence.saveEvent(
                lifecycleEvent(
                    eventId = rejected.eventId,
                    eventType = rejectedEventType,
                    orderId = rejected.orderId.ifBlank { defaultOrderId },
                    traceId = traceId,
                    causationId = commandId,
                    correlationId = correlationId,
                    occurredAt = rejected.occurredAt,
                    actorId = actorId,
                    payloadJson = commandPayload(commandId)
                )
            )
        }
    }

    private fun rejectUnauthorizedSubmitOutcome(
        commandId: String,
        traceId: String,
        correlationId: String,
        actorId: String,
        orderId: String,
        occurredAt: String,
        permission: String,
        rejectedEventType: String
    ): PersistableSubmitOutcome? {
        if (hasPermission(actorId, permission)) {
            return null
        }

        val result = unauthorizedResult(commandId, orderId, occurredAt, actorId, permission)
        val rejected = result.rejected ?: return null
        return PersistableSubmitOutcome(
            commandId = commandId,
            result = result,
            acceptedOrder = null,
            lifecycleEvents = listOf(
                lifecycleEvent(
                    eventId = rejected.eventId,
                    eventType = rejectedEventType,
                    orderId = orderId,
                    traceId = traceId,
                    causationId = commandId,
                    correlationId = correlationId,
                    occurredAt = occurredAt,
                    actorId = actorId,
                    payloadJson = commandPayload(commandId)
                )
            )
        )
    }

    private fun rejectUnauthorizedActor(
        commandId: String,
        traceId: String,
        correlationId: String,
        actorId: String,
        orderId: String,
        occurredAt: String,
        permission: String,
        rejectedEventType: String
    ): SubmitOrderResult? {
        if (hasPermission(actorId, permission)) {
            return null
        }

        val result = unauthorizedResult(commandId, orderId, occurredAt, actorId, permission)
        val rejected = result.rejected ?: return result
        runtimePersistence.persistSubmitOutcome(
            commandId = commandId,
            result = result,
            acceptedOrder = null,
            lifecycleEvents = listOf(
                lifecycleEvent(
                    eventId = rejected.eventId,
                    eventType = rejectedEventType,
                    orderId = orderId,
                    traceId = traceId,
                    causationId = commandId,
                    correlationId = correlationId,
                    occurredAt = occurredAt,
                    actorId = actorId,
                    payloadJson = commandPayload(commandId)
                )
            )
        )
        return result
    }

    private fun unauthorizedResult(
        commandId: String,
        orderId: String,
        occurredAt: String,
        actorId: String,
        permission: String
    ): SubmitOrderResult {
        return SubmitOrderResult(
            rejected = EngineOrderRejected(
            eventId = "evt-reject-unauthorized-$commandId",
            orderId = orderId,
            code = "AUTHORIZATION_ERROR",
            reason = authorizationReason(actorId, permission),
            occurredAt = occurredAt
            )
        )
    }

    private fun hasPermission(actorId: String, permission: String): Boolean {
        if (actorId.isBlank()) {
            return false
        }
        val boundRoleIds = runtimePersistence.actorRoleBindings(actorId).map { it.roleId }.toSet()
        if (boundRoleIds.isEmpty()) {
            return false
        }
        val allowed = runtimePersistence.roles()
            .asSequence()
            .filter { it.roleId in boundRoleIds }
            .flatMap { it.permissions.asSequence() }
            .toSet()
        return permission in allowed || Permission.SUPERUSER in allowed
    }

    private fun authorizationReason(actorId: String, permission: String): String {
        if (actorId.isBlank()) {
            return "actorId is required"
        }
        return "actorId missing permission $permission"
    }

    private fun lifecycleEvent(
        eventId: String,
        eventType: String,
        orderId: String,
        traceId: String,
        causationId: String,
        correlationId: String,
        occurredAt: String,
        actorId: String = "",
        payloadJson: String = "{}"
    ): RuntimeEvent {
        return RuntimeEvent(
            eventId = eventId,
            eventType = eventType,
            orderId = orderId,
            traceId = traceId,
            causationId = causationId,
            correlationId = correlationId,
            producer = eventProducer,
            schemaVersion = eventSchemaVersion,
            occurredAt = occurredAt,
            actorId = actorId,
            payloadJson = payloadJson
        )
    }

    private fun commandPayload(commandId: String): String {
        return JsonCodec.writeObject("commandId" to commandId)
    }

    private fun traceId(traceId: String, orderId: String): String {
        return traceId.ifBlank { orderId }
    }

    fun persistedOrder(orderId: String) = runtimePersistence.acceptedOrder(orderId)

    fun persistedOrders() = runtimePersistence.acceptedOrders()

    fun findOrderByClientOrderId(participantId: String, clientOrderId: String) =
        runtimePersistence.findOrderByClientOrderId(participantId, clientOrderId)

    fun persistedExecutions(orderId: String) = runtimePersistence.executionsForOrder(orderId)

    fun persistedTrades(orderId: String) = runtimePersistence.tradesForOrder(orderId)

    fun persistedTrades() = runtimePersistence.trades()

    fun recentTrades(limit: Int) = runtimePersistence.recentTrades(limit)

    fun tradeTape(instrumentId: String, limit: Int, beforeSequence: Long?) =
        runtimePersistence.tradeTape(instrumentId, limit, beforeSequence)

    fun intradayBars(instrumentId: String, interval: String, start: String, end: String) =
        runtimePersistence.intradayBars(instrumentId, interval, start, end)

    fun ordersForParticipant(participantId: String, openOnly: Boolean, instrumentId: String = "", limit: Int = 0) =
        runtimePersistence.ordersForParticipant(participantId, openOnly, instrumentId, limit)

    fun persistedEvents(orderId: String) = runtimePersistence.eventsForOrder(orderId)

    fun persistedTraceEvents(traceId: String) = runtimePersistence.eventsForTrace(traceId)

    fun events() = runtimePersistence.events()

    fun recentEvents(limit: Int) = runtimePersistence.recentEvents(limit)

    fun saveRuntimeEvent(event: RuntimeEvent) = runtimePersistence.saveEvent(event)

    fun rebuildOrderLifecycleState() = runtimePersistence.rebuildOrderLifecycleState()

    fun projectOrderLifecycleState(batchSize: Int) = runtimePersistence.projectOrderLifecycleState(batchSize)

    fun orderLifecycleState(orderId: String) = runtimePersistence.orderLifecycleState(orderId)

    fun refreshMarketDataSnapshots(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ) = runtimePersistence.refreshMarketDataSnapshots(projectionName, sourceProjectionName)

    fun projectMarketDataSnapshots(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes",
        batchSize: Int = 500
    ) = runtimePersistence.projectMarketDataSnapshots(projectionName, sourceProjectionName, batchSize)

    fun marketDataSnapshot(
        instrumentId: String,
        projectionName: String = "market-data-top-of-book"
    ) = runtimePersistence.marketDataSnapshot(instrumentId, projectionName)

    fun marketDataDepthSnapshot(
        instrumentId: String,
        levels: Int = 5,
        projectionName: String = "market-data-depth",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ) = runtimePersistence.marketDataDepthSnapshot(instrumentId, levels, projectionName, sourceProjectionName)

    fun createInstrument(instrument: Instrument) = runtimePersistence.saveInstrument(instrument)

    fun createParticipant(participant: Participant) = runtimePersistence.saveParticipant(participant)

    fun createAccount(account: Account) = runtimePersistence.saveAccount(account)

    fun createRole(role: RoleDefinition) = runtimePersistence.saveRole(role)

    fun assignRole(binding: ActorRoleBinding) = runtimePersistence.saveActorRoleBinding(binding)

    fun instruments() = runtimePersistence.instruments()

    fun participants() = runtimePersistence.participants()

    fun accounts() = runtimePersistence.accounts()

    fun roles() = runtimePersistence.roles()

    fun actorRoleBindings(actorId: String) = runtimePersistence.actorRoleBindings(actorId)

    private fun validateReferenceData(command: SubmitOrderCommand): SubmitOrderResult? {
        val now = command.occurredAt
        val validation = runtimePersistence.validateReferenceData(
            instrumentId = command.instrumentId,
            participantId = command.participantId,
            accountId = command.accountId
        )
        if (!validation.instrumentExists) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-missing-instrument-ref-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "instrumentId does not exist",
                    occurredAt = now
                )
            )
        }

        if (!validation.participantExists) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-missing-participant-ref-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "participantId does not exist",
                    occurredAt = now
                )
            )
        }

        if (!validation.accountExists) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-missing-account-ref-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "accountId does not exist",
                    occurredAt = now
                )
            )
        }

        if (!validation.accountBelongsToParticipant) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-account-participant-mismatch-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "accountId does not belong to participantId",
                    occurredAt = now
                )
            )
        }

        return null
    }
}

private data class ValidatedSubmitContext(
    val traceId: String,
    val outcome: PersistableSubmitOutcome? = null
)

internal fun defaultRuntimePersistence(poolName: String = "runtime"): RuntimePersistence {
    val persistence = (System.getenv("RUNTIME_PERSISTENCE") ?: "inmemory").trim().lowercase()
    when (persistence) {
        "inmemory", "memory" -> return InMemoryRuntimePersistence()
        "noop", "no-op", "benchmark-noop" -> return NoopRuntimePersistence()
        "postgres" -> {}
        else -> throw IllegalArgumentException("Unsupported RUNTIME_PERSISTENCE: $persistence")
    }

    val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val user = System.getenv("RUNTIME_POSTGRES_USER") ?: "reef"
    val password = System.getenv("RUNTIME_POSTGRES_PASSWORD") ?: "reef"
    val projectionJdbcUrl = System.getenv("RUNTIME_PROJECTION_POSTGRES_JDBC_URL")?.trim().orEmpty()
    val projectionUser = System.getenv("RUNTIME_PROJECTION_POSTGRES_USER") ?: user
    val projectionPassword = System.getenv("RUNTIME_PROJECTION_POSTGRES_PASSWORD") ?: password
    val runtimeDataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, poolName)
    val projectionDataSource = if (projectionJdbcUrl.isBlank()) {
        runtimeDataSource
    } else {
        RuntimeDataSources.dataSource(
            projectionJdbcUrl,
            projectionUser,
            projectionPassword,
            "$poolName-projection"
        )
    }
    return PostgresRuntimePersistence(
        dataSource = runtimeDataSource,
        projectionDataSource = projectionDataSource
    )
}
