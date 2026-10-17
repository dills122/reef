package com.reef.platform.api

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class RecordingCommandCaptureStore : CommandCaptureStore {
    var receivedCalls: Int = 0
    var completedCalls: Int = 0
    var failedCalls: Int = 0
    var lastReceivedPayload: String = ""

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        receivedCalls++
        lastReceivedPayload = requestPayload
    }

    override fun markProcessing(
        clientId: String,
        route: String,
        idempotencyKey: String
    ) {
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
        completedCalls++
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
        failedCalls++
    }
}

internal class CountingIdempotencyStore : IdempotencyStore {
    var findCalls: Int = 0
    var saveCalls: Int = 0

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        findCalls++
        return null
    }

    override fun save(
        clientId: String,
        route: String,
        idempotencyKey: String,
        result: IdempotencyResult,
        ttlClass: IdempotencyTtlClass
    ) {
        saveCalls++
    }
}

internal class RecordingStreamCommandPublisher(
    private val fail: Boolean = false
) : StreamCommandPublisher {
    val published = mutableListOf<StreamCommandEnvelope>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        published.add(envelope)
        if (fail) {
            error("publish ack timeout")
        }
        return StreamPublishAck("REEF_COMMANDS", published.size.toLong())
    }
}

internal class SkipFirstPublishedMarkerIntakeStore(
    private val delegate: StreamCommandIntakeStore
) : StreamCommandIntakeStore {
    private val skipNext = AtomicBoolean(true)

    override fun reserve(envelope: StreamCommandEnvelope, reference: StreamCommandReference): StreamCommandReservation {
        return delegate.reserve(envelope, reference)
    }

    override fun markPublished(scope: String, idempotencyKey: String, streamSequence: Long): Boolean {
        if (skipNext.compareAndSet(true, false)) {
            return true
        }
        return delegate.markPublished(scope, idempotencyKey, streamSequence)
    }

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        if (skipNext.compareAndSet(true, false)) {
            return true
        }
        return delegate.markPublishedByCommandId(commandId, streamSequence)
    }

    override fun markPublishedByCommandIds(commands: List<Pair<String, Long>>): Int {
        return delegate.markPublishedByCommandIds(commands)
    }

    override fun findByCommandId(commandId: String): StreamCommandReference? {
        return delegate.findByCommandId(commandId)
    }
}

internal class DelayedAckStreamCommandPublisher : StreamCommandPublisher, AsyncStreamCommandPublisher {
    val published = mutableListOf<StreamCommandEnvelope>()
    private val pending = CompletableFuture<StreamPublishAck>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        return publishAsync(envelope).get(2, TimeUnit.SECONDS)
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        published.add(envelope)
        return pending
    }

    fun complete(ack: StreamPublishAck) {
        pending.complete(ack)
    }
}

internal class FixedStreamCommandHealthCheck(
    private val snapshot: StreamCommandHealthSnapshot
) : StreamCommandHealthCheck {
    override fun snapshot(): StreamCommandHealthSnapshot = snapshot
}

internal class CountingStreamCommandHealthCheck(
    private val snapshot: StreamCommandHealthSnapshot
) : StreamCommandHealthCheck {
    val calls = AtomicInteger(0)

    override fun snapshot(): StreamCommandHealthSnapshot {
        calls.incrementAndGet()
        return snapshot
    }
}

internal class StaticAcceptedEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-1",
                orderId = "ord-1",
                engineOrderId = "eng-1",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )
}

internal class EchoOrderEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

internal class CountingEngineGateway(
    private val delegate: EngineGateway
) : EngineGateway {
    var submitCalls: Int = 0
    var cancelCalls: Int = 0

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls++
        return delegate.submitOrder(command)
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        cancelCalls++
        return delegate.cancelOrder(command)
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return delegate.modifyOrder(command)
    }
}

internal class BlockingFirstSubmitGateway : EngineGateway {
    private val calls = AtomicInteger(0)
    private val firstStarted = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)

    val submitCalls: Int
        get() = calls.get()

    fun awaitFirstSubmit(): Boolean {
        return firstStarted.await(5, TimeUnit.SECONDS)
    }

    fun release() {
        releaseFirst.countDown()
    }

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val call = calls.incrementAndGet()
        if (call == 1) {
            firstStarted.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        }
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

internal class StaticRejectedEngineGateway(
    private val code: String,
    private val reason: String
) : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = "evt-rejected-${command.orderId}",
                orderId = command.orderId,
                code = code,
                reason = reason,
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

internal class StaticCircuitBreakerCheck(
    private val trippedInstrumentId: String
) : CommandCircuitBreakerCheck {
    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        if (request.instrumentId != trippedInstrumentId) return null
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for INSTRUMENT:${request.instrumentId}"
        )
    }
}

internal class RecordingAccountRiskStore : AccountRiskCheck, AccountRiskControlStore, AccountRiskDecisionLog {
    private val controls = linkedMapOf<String, AccountRiskControl>()
    val decisions = mutableListOf<AccountRiskDecisionAudit>()

    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        val botDecision = controls["BOT|${request.botId}"]
        if (botDecision != null && botDecision.decision != AccountRiskDecision.ALLOW) {
            return AccountRiskCheckResult(botDecision.decision, message = botDecision.reason)
        }
        val accountDecision = controls["ACCOUNT|${request.accountId}"]
        if (accountDecision != null && accountDecision.decision != AccountRiskDecision.ALLOW) {
            return AccountRiskCheckResult(accountDecision.decision, message = accountDecision.reason)
        }
        listOfNotNull(botDecision, accountDecision).forEach { control ->
            val quantity = request.quantityUnits.toBigDecimalOrNull()
            val maxQuantity = control.maxQuantityUnits.toBigDecimalOrNull()
            if (quantity != null && maxQuantity != null && quantity > maxQuantity) {
                return AccountRiskCheckResult(
                    AccountRiskDecision.REJECT,
                    code = "ACCOUNT_RISK_MAX_QUANTITY_EXCEEDED",
                    message = "max quantity exceeded"
                )
            }
        }
        return AccountRiskCheckResult(AccountRiskDecision.ALLOW)
    }

    override fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String,
        maxQuantityUnits: String,
        maxNotional: String,
        currency: String
    ) {
        controls["$scopeType|$scopeId"] = AccountRiskControl(
            scopeType = scopeType,
            scopeId = scopeId,
            decision = decision,
            reason = reason,
            maxQuantityUnits = maxQuantityUnits,
            maxNotional = maxNotional,
            currency = currency,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()

    override fun recentDecisions(limit: Int): List<AccountRiskDecisionAudit> = decisions.take(limit)
}

internal class RecordingCommandCircuitBreakerStore : CommandCircuitBreakerStore {
    private val breakers = linkedMapOf<String, CommandCircuitBreakerState>()

    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        val breaker = breakers["INSTRUMENT|${request.instrumentId}"] ?: breakers["VENUE_SESSION|${request.venueSessionId}"] ?: breakers["GLOBAL|*"]
        if (breaker?.tripped != true) return null
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for ${breaker.scopeType}:${breaker.scopeId}"
        )
    }

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        breakers["$scopeType|$scopeId"] = CommandCircuitBreakerState(
            scopeType = scopeType,
            scopeId = scopeId,
            tripped = tripped,
            reason = reason,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> = breakers.values.toList()
}

internal class RecordingInstrumentPriceCollarStore : InstrumentPriceCollarStore {
    private val collars = linkedMapOf<String, InstrumentPriceCollarState>()

    override fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError? {
        if (request.commandType != "SubmitOrder") return null
        val collar = collars[request.instrumentId] ?: return null
        if (collar.currency.isNotBlank() && !request.currency.equals(collar.currency, ignoreCase = true)) return null
        val price = request.limitPrice.toBigDecimalOrNull() ?: return null
        val minPrice = collar.minPrice.toBigDecimalOrNull()
        if (minPrice != null && price < minPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_LOW",
                "limit price below collar for ${collar.instrumentId}"
            )
        }
        val maxPrice = collar.maxPrice.toBigDecimalOrNull()
        if (maxPrice != null && price > maxPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_HIGH",
                "limit price above collar for ${collar.instrumentId}"
            )
        }
        return null
    }

    override fun setCollar(instrumentId: String, minPrice: String, maxPrice: String, currency: String, reason: String) {
        collars[instrumentId] = InstrumentPriceCollarState(
            instrumentId = instrumentId,
            minPrice = minPrice,
            maxPrice = maxPrice,
            currency = currency,
            reason = reason,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listCollars(): List<InstrumentPriceCollarState> = collars.values.toList()
}

internal class RecordingBoundaryRejectionLog : BoundaryRejectionLog {
    data class Record(
        val guardrailType: String,
        val scopeType: String,
        val scopeId: String,
        val request: AccountRiskCheckRequest,
        val error: BoundaryError
    )

    val records = mutableListOf<Record>()

    override fun recordRejection(
        guardrailType: String,
        scopeType: String,
        scopeId: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ) {
        records.add(Record(guardrailType, scopeType, scopeId, request, error))
    }
}

internal class ThrowingEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }
}
