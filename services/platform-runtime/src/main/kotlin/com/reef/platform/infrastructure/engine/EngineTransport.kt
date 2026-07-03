package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.config.RuntimeEnv
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import reef.contracts.orderexecution.v1.CancelOrder
import reef.contracts.orderexecution.v1.CommandMetadata
import reef.contracts.orderexecution.v1.ModifyOrder
import reef.contracts.orderexecution.v1.OrderAccepted
import reef.contracts.orderexecution.v1.OrderExecution
import reef.contracts.orderexecution.v1.OrderQuantity
import reef.contracts.orderexecution.v1.OrderRejected
import reef.contracts.orderexecution.v1.OrderSide
import reef.contracts.orderexecution.v1.OrderType
import reef.contracts.orderexecution.v1.Price
import reef.contracts.orderexecution.v1.SubmitOrder
import reef.contracts.orderexecution.v1.SubmitOrderResult as ProtoSubmitOrderResult
import reef.contracts.orderexecution.v1.TimeInForce

fun defaultEngineGateway(): EngineGateway {
    val transport = (System.getenv("ENGINE_TRANSPORT") ?: "http").lowercase()
    val httpBaseUrl = System.getenv("MATCHING_ENGINE_BASE_URL") ?: "http://localhost:8081"
    val grpcTarget = System.getenv("MATCHING_ENGINE_GRPC_TARGET") ?: "localhost:9081"
    val grpcDeadline = Duration.ofMillis(RuntimeEnv.long("ENGINE_GRPC_DEADLINE_MS", 2_000, min = 1))

    return when (transport) {
        "grpc" -> GrpcEngineClient(grpcTarget, grpcDeadline)
        "grpc-stream", "grpcstream", "stream" -> GrpcStreamEngineClient(grpcTarget, grpcDeadline)
        else -> EngineClient(httpBaseUrl)
    }
}

class GrpcEngineClient(
    private val grpcTarget: String,
    private val requestDeadline: Duration = Duration.ofMillis(RuntimeEnv.long("ENGINE_GRPC_DEADLINE_MS", 2_000, min = 1))
) : EngineGateway, AutoCloseable {
    private val channel: ManagedChannel = ManagedChannelBuilder.forTarget(grpcTarget).usePlaintext().build()

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val response = blockingEngineCall("SubmitOrder", submitMethod, command.toProtoSubmitOrder())
        return response.toDomainResult()
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        val req = CancelOrder.newBuilder()
            .setMetadata(command.toMetadata())
            .setOrderId(command.orderId)
            .setReason(command.reason)
            .build()

        val response = blockingEngineCall("CancelOrder", cancelMethod, req)
        return response.toDomainResult()
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        val req = ModifyOrder.newBuilder()
            .setMetadata(command.toMetadata())
            .setOrderId(command.orderId)
            .setQuantity(OrderQuantity.newBuilder().setUnits(command.quantityUnits).build())
            .setLimitPrice(Price.newBuilder().setNanos(command.limitPrice).setCurrency("USD").build())
            .build()

        val response = blockingEngineCall("ModifyOrder", modifyMethod, req)
        return response.toDomainResult()
    }

    fun target(): String = grpcTarget

    override fun close() {
        channel.shutdown()
    }

    internal fun isShutdown(): Boolean = channel.isShutdown

    private fun <RequestT> blockingEngineCall(
        operation: String,
        method: MethodDescriptor<RequestT, ProtoSubmitOrderResult>,
        request: RequestT
    ): ProtoSubmitOrderResult {
        val deadlineMs = requestDeadline.toMillis().coerceAtLeast(1)
        return try {
            ClientCalls.blockingUnaryCall(
                channel,
                method,
                CallOptions.DEFAULT.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS),
                request
            )
        } catch (ex: StatusRuntimeException) {
            throw EngineTransportException(
                "engine gRPC $operation failed with ${ex.status.code}: ${ex.status.description ?: ex.message ?: "unknown"}",
                ex
            )
        } catch (ex: RuntimeException) {
            throw EngineTransportException("engine gRPC $operation failed: ${ex.message ?: "unknown"}", ex)
        }
    }

    companion object {
        private const val SERVICE_NAME = "reef.contracts.orderexecution.v1.OrderExecutionService"

        private val submitMethod: MethodDescriptor<SubmitOrder, ProtoSubmitOrderResult> =
            MethodDescriptor.newBuilder<SubmitOrder, ProtoSubmitOrderResult>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SubmitOrder"))
                .setRequestMarshaller(ProtoUtils.marshaller(SubmitOrder.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(ProtoSubmitOrderResult.getDefaultInstance()))
                .build()

        private val cancelMethod: MethodDescriptor<CancelOrder, ProtoSubmitOrderResult> =
            MethodDescriptor.newBuilder<CancelOrder, ProtoSubmitOrderResult>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "CancelOrder"))
                .setRequestMarshaller(ProtoUtils.marshaller(CancelOrder.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(ProtoSubmitOrderResult.getDefaultInstance()))
                .build()

        private val modifyMethod: MethodDescriptor<ModifyOrder, ProtoSubmitOrderResult> =
            MethodDescriptor.newBuilder<ModifyOrder, ProtoSubmitOrderResult>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "ModifyOrder"))
                .setRequestMarshaller(ProtoUtils.marshaller(ModifyOrder.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(ProtoSubmitOrderResult.getDefaultInstance()))
                .build()
    }
}

class GrpcStreamEngineClient(
    private val grpcTarget: String,
    private val requestDeadline: Duration = Duration.ofMillis(RuntimeEnv.long("ENGINE_GRPC_DEADLINE_MS", 2_000, min = 1)),
    laneCount: Int = RuntimeEnv.int("ENGINE_GRPC_STREAM_LANES", 16, min = 1),
    queueCapacity: Int = RuntimeEnv.int("ENGINE_GRPC_STREAM_QUEUE_CAPACITY", 100_000, min = 1)
) : EngineGateway, AsyncSubmitEngineGateway, AutoCloseable {
    private val channel: ManagedChannel = ManagedChannelBuilder.forTarget(grpcTarget).usePlaintext().build()
    private val unaryFallback = GrpcEngineClient(grpcTarget, requestDeadline)
    private val lanes = Array(laneCount) {
        SubmitStreamLane(
            channel = channel,
            requestDeadline = requestDeadline,
            queueCapacity = queueCapacity
        )
    }

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return lane(command).submit(command)
    }

    override fun submitOrderAsync(command: SubmitOrderCommand): CompletableFuture<SubmitOrderResult> {
        return lane(command).submitAsync(command).thenApply { it.toDomainResult() }
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return unaryFallback.cancelOrder(command)
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return unaryFallback.modifyOrder(command)
    }

    fun target(): String = grpcTarget

    override fun close() {
        lanes.forEach { it.close() }
        unaryFallback.close()
        channel.shutdown()
    }

    internal fun isShutdown(): Boolean = channel.isShutdown

    private fun lane(command: SubmitOrderCommand): SubmitStreamLane {
        val key = command.instrumentId
        return lanes[Math.floorMod(key.hashCode(), lanes.size)]
    }
}

private data class StreamSubmit(
    val request: SubmitOrder,
    val response: CompletableFuture<ProtoSubmitOrderResult>
)

private class SubmitStreamLane(
    private val channel: ManagedChannel,
    private val requestDeadline: Duration,
    queueCapacity: Int
) : AutoCloseable {
    private val inFlight = Semaphore(queueCapacity)
    private val pending = ConcurrentLinkedQueue<CompletableFuture<ProtoSubmitOrderResult>>()
    private val closed = AtomicBoolean(false)
    private val streamFailure = AtomicReference<Throwable?>()
    private val streamLock = Any()
    @Volatile
    private var requestObserver: StreamObserver<SubmitOrder> = openStream()

    fun submit(command: SubmitOrderCommand): SubmitOrderResult {
        val deadlineMs = requestDeadline.toMillis().coerceAtLeast(1)
        val response = submitAsync(command)
        return try {
            response.get(deadlineMs, TimeUnit.MILLISECONDS).toDomainResult()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            response.cancel(true)
            throw EngineTransportException("engine gRPC SubmitOrders interrupted", ex)
        } catch (ex: TimeoutException) {
            response.cancel(true)
            throw EngineTransportException("engine gRPC SubmitOrders deadline exceeded", ex)
        } catch (ex: ExecutionException) {
            val cause = ex.cause ?: ex
            throw EngineTransportException("engine gRPC SubmitOrders failed: ${cause.message ?: "unknown"}", cause)
        }
    }

    fun submitAsync(command: SubmitOrderCommand): CompletableFuture<ProtoSubmitOrderResult> {
        val deadlineMs = requestDeadline.toMillis().coerceAtLeast(1)
        val response = CompletableFuture<ProtoSubmitOrderResult>()
        val item = StreamSubmit(command.toProtoSubmitOrder(), response)
        var acquired = false
        var sent = false
        try {
            if (!inFlight.tryAcquire(deadlineMs, TimeUnit.MILLISECONDS)) {
                throw EngineTransportException("engine gRPC SubmitOrders lane in-flight limit reached")
            }
            acquired = true
            send(item)
            sent = true
            return response
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            if (acquired && !sent) inFlight.release()
            throw EngineTransportException("engine gRPC SubmitOrders interrupted", ex)
        } catch (ex: RuntimeException) {
            if (acquired && !sent) inFlight.release()
            throw ex
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(streamLock) {
            requestObserver.onCompleted()
        }
        failPending(EngineTransportException("engine gRPC SubmitOrders lane closed"))
    }

    private fun send(item: StreamSubmit) {
        synchronized(streamLock) {
            if (closed.get()) {
                throw EngineTransportException("engine gRPC SubmitOrders lane closed")
            }
            streamFailure.getAndSet(null)?.let {
                requestObserver = reopenStream(requestObserver)
            }
            pending.add(item.response)
            try {
                requestObserver.onNext(item.request)
            } catch (ex: RuntimeException) {
                pending.remove(item.response)
                item.response.completeExceptionally(ex)
                requestObserver = reopenStream(requestObserver)
                throw ex
            }
        }
    }

    private fun openStream(): StreamObserver<SubmitOrder> {
        val responseObserver = object : StreamObserver<ProtoSubmitOrderResult> {
            override fun onNext(value: ProtoSubmitOrderResult) {
                pending.poll()?.complete(value)
                inFlight.release()
            }

            override fun onError(t: Throwable) {
                streamFailure.set(t)
                failPending(t)
            }

            override fun onCompleted() {
                val error = EngineTransportException("engine gRPC SubmitOrders stream completed")
                streamFailure.set(error)
                failPending(error)
            }
        }
        return ClientCalls.asyncBidiStreamingCall(
            channel.newCall(submitOrdersMethod, CallOptions.DEFAULT),
            responseObserver
        )
    }

    private fun reopenStream(current: StreamObserver<SubmitOrder>): StreamObserver<SubmitOrder> {
        try {
            current.onCompleted()
        } catch (_: RuntimeException) {
        }
        return openStream()
    }

    private fun failPending(error: Throwable) {
        while (true) {
            val pendingResponse = pending.poll() ?: return
            pendingResponse.completeExceptionally(error)
            inFlight.release()
        }
    }

    companion object {
        private const val SERVICE_NAME = "reef.contracts.orderexecution.v1.OrderExecutionService"

        private val submitOrdersMethod: MethodDescriptor<SubmitOrder, ProtoSubmitOrderResult> =
            MethodDescriptor.newBuilder<SubmitOrder, ProtoSubmitOrderResult>()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SubmitOrders"))
                .setRequestMarshaller(ProtoUtils.marshaller(SubmitOrder.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(ProtoSubmitOrderResult.getDefaultInstance()))
                .build()
    }
}

private fun SubmitOrderCommand.toProtoSubmitOrder(): SubmitOrder =
    SubmitOrder.newBuilder()
        .setMetadata(toMetadata())
        .setOrderId(orderId)
        .setInstrumentId(instrumentId)
        .setParticipantId(participantId)
        .setAccountId(accountId)
        .setSide(side.toProtoSide())
        .setOrderType(orderType.toProtoOrderType())
        .setQuantity(OrderQuantity.newBuilder().setUnits(quantityUnits).build())
        .setLimitPrice(Price.newBuilder().setNanos(limitPrice).setCurrency(currency).build())
        .setTimeInForce(timeInForce.toProtoTimeInForce())
        .build()

private fun SubmitOrderCommand.toMetadata(): CommandMetadata =
    CommandMetadata.newBuilder()
        .setCommandId(commandId)
        .setTraceId(traceId)
        .setCausationId(causationId)
        .setCorrelationId(correlationId)
        .setActorId(actorId)
        .setOccurredAt(occurredAt)
        .build()

private fun CancelOrderCommand.toMetadata(): CommandMetadata =
    CommandMetadata.newBuilder()
        .setCommandId(commandId)
        .setTraceId(traceId)
        .setCausationId(causationId)
        .setCorrelationId(correlationId)
        .setActorId(actorId)
        .setOccurredAt(occurredAt)
        .build()

private fun ModifyOrderCommand.toMetadata(): CommandMetadata =
    CommandMetadata.newBuilder()
        .setCommandId(commandId)
        .setTraceId(traceId)
        .setCausationId(causationId)
        .setCorrelationId(correlationId)
        .setActorId(actorId)
        .setOccurredAt(occurredAt)
        .build()

private fun String.toProtoSide(): OrderSide =
    when (uppercase()) {
        "SELL" -> OrderSide.ORDER_SIDE_SELL
        "BUY" -> OrderSide.ORDER_SIDE_BUY
        else -> throw EngineTransportException("invalid order side: $this")
    }

private fun String.toProtoOrderType(): OrderType =
    when (uppercase()) {
        "LIMIT" -> OrderType.ORDER_TYPE_LIMIT
        else -> throw EngineTransportException("invalid order type: $this")
    }

private fun String.toProtoTimeInForce(): TimeInForce =
    when (uppercase()) {
        "IOC" -> TimeInForce.TIME_IN_FORCE_IOC
        "DAY" -> TimeInForce.TIME_IN_FORCE_DAY
        else -> throw EngineTransportException("invalid time in force: $this")
    }

private fun ProtoSubmitOrderResult.toDomainResult(): SubmitOrderResult =
    SubmitOrderResult(
        accepted = if (hasAccepted()) accepted.toDomain() else null,
        rejected = if (hasRejected()) rejected.toDomain() else null,
        executions = executionsList.map { execution ->
            ExecutionCreated(
                eventId = execution.eventId,
                executionId = execution.executionId,
                orderId = execution.orderId,
                instrumentId = execution.instrumentId,
                quantityUnits = execution.quantity.units,
                executionPrice = execution.executionPrice.nanos,
                currency = execution.executionPrice.currency,
                occurredAt = execution.occurredAt
            )
        },
        trades = tradesList.map { trade ->
            TradeCreated(
                eventId = trade.eventId,
                tradeId = trade.tradeId,
                executionId = trade.executionId,
                buyOrderId = trade.buyOrderId,
                sellOrderId = trade.sellOrderId,
                instrumentId = trade.instrumentId,
                quantityUnits = trade.quantity.units,
                price = trade.price.nanos,
                currency = trade.price.currency,
                occurredAt = trade.occurredAt
            )
        }
    )

private fun OrderAccepted.toDomain(): EngineOrderAccepted =
    EngineOrderAccepted(
        eventId = eventId,
        orderId = orderId,
        engineOrderId = engineOrderId,
        occurredAt = occurredAt
    )

private fun OrderRejected.toDomain(): EngineOrderRejected =
    EngineOrderRejected(
        eventId = eventId,
        orderId = orderId,
        code = code,
        reason = reason,
        occurredAt = occurredAt
    )
