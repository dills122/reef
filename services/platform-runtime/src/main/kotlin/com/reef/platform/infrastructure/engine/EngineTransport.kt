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
import com.reef.platform.infrastructure.partition.PartitionLaneHash
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientCalls
import io.grpc.stub.ClientResponseObserver
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
    val transport = (System.getenv("ENGINE_TRANSPORT") ?: "grpc").lowercase()
    val httpBaseUrl = System.getenv("MATCHING_ENGINE_BASE_URL") ?: "http://localhost:8081"
    val grpcTarget = System.getenv("MATCHING_ENGINE_GRPC_TARGET") ?: "localhost:9081"
    val grpcDeadline = Duration.ofMillis(RuntimeEnv.long("ENGINE_GRPC_DEADLINE_MS", 2_000, min = 1))

    return when (transport) {
        "http", "legacy-http" -> EngineClient(httpBaseUrl)
        "grpc" -> GrpcEngineClient(grpcTarget, grpcDeadline)
        "grpc-stream", "grpcstream", "stream" -> GrpcStreamEngineClient(grpcTarget, grpcDeadline)
        else -> GrpcEngineClient(grpcTarget, grpcDeadline)
    }
}

class GrpcEngineClient(
    private val grpcTarget: String,
    private val requestDeadline: Duration = Duration.ofMillis(RuntimeEnv.long("ENGINE_GRPC_DEADLINE_MS", 2_000, min = 1)),
    managedChannel: ManagedChannel? = null,
    private val closeManagedChannel: Boolean = true
) : EngineGateway, AutoCloseable {
    private val channel: ManagedChannel = managedChannel ?: buildGrpcChannel(grpcTarget)

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
        if (closeManagedChannel) channel.shutdown()
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
    private val channel: ManagedChannel = buildGrpcChannel(grpcTarget)
    private val unaryFallback = GrpcEngineClient(grpcTarget, requestDeadline, channel, closeManagedChannel = false)
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
        return lanes[PartitionLaneHash.laneFor(key, lanes.size)]
    }
}

private enum class EngineGrpcSecurityMode {
    Plaintext,
    Tls,
    ServiceMesh;

    companion object {
        fun fromEnv(): EngineGrpcSecurityMode {
            return when ((System.getenv("ENGINE_GRPC_SECURITY") ?: "plaintext").trim().lowercase()) {
                "tls", "transport-security" -> Tls
                "service-mesh", "mesh", "mtls-mesh" -> ServiceMesh
                else -> Plaintext
            }
        }
    }
}

private fun buildGrpcChannel(grpcTarget: String): ManagedChannel {
    validateEngineGrpcSecurityMode()
    val keepAliveTimeMs = RuntimeEnv.long("ENGINE_GRPC_KEEPALIVE_TIME_MS", 30_000L, min = 1L)
    val keepAliveTimeoutMs = RuntimeEnv.long("ENGINE_GRPC_KEEPALIVE_TIMEOUT_MS", 10_000L, min = 1L)
    val idleTimeoutMs = RuntimeEnv.long("ENGINE_GRPC_IDLE_TIMEOUT_MS", 300_000L, min = 1L)
    val builder = ManagedChannelBuilder.forTarget(grpcTarget)
        .keepAliveTime(keepAliveTimeMs, TimeUnit.MILLISECONDS)
        .keepAliveTimeout(keepAliveTimeoutMs, TimeUnit.MILLISECONDS)
        .keepAliveWithoutCalls(RuntimeEnv.bool("ENGINE_GRPC_KEEPALIVE_WITHOUT_CALLS", true))
        .idleTimeout(idleTimeoutMs, TimeUnit.MILLISECONDS)

    return when (EngineGrpcSecurityMode.fromEnv()) {
        EngineGrpcSecurityMode.Tls -> builder.useTransportSecurity().build()
        EngineGrpcSecurityMode.ServiceMesh,
        EngineGrpcSecurityMode.Plaintext -> builder.usePlaintext().build()
    }
}

private fun validateEngineGrpcSecurityMode() {
    if (isLocalEngineProfile()) return
    val raw = System.getenv("ENGINE_GRPC_SECURITY")
        ?: error("non-local runtime requires ENGINE_GRPC_SECURITY=tls or service-mesh")
    require(raw.trim().lowercase() in setOf("tls", "transport-security", "service-mesh", "mesh", "mtls-mesh")) {
        "non-local runtime requires ENGINE_GRPC_SECURITY=tls or service-mesh"
    }
}

private fun isLocalEngineProfile(): Boolean {
    val profile = listOf(
        "ENGINE_DEPLOYMENT_PROFILE",
        "REEF_ENV",
        "REEF_DEPLOYMENT_ENV",
        "DEPLOYMENT_ENV",
        "ENVIRONMENT",
        "APP_ENV",
        "PROFILE"
    ).firstNotNullOfOrNull { key -> System.getenv(key)?.trim()?.takeIf { it.isNotBlank() } }
        ?.lowercase()
        ?: return true
    return profile in setOf("local", "dev", "development", "test", "ci", "single-host", "hosted-single-host")
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
    private val outbound = ConcurrentLinkedQueue<StreamSubmit>()
    private val pending = ConcurrentLinkedQueue<CompletableFuture<ProtoSubmitOrderResult>>()
    private val closed = AtomicBoolean(false)
    private val streamFailure = AtomicReference<Throwable?>()
    private val streamLock = Any()
    @Volatile
    private var readyObserver: ClientCallStreamObserver<SubmitOrder>? = null
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
        try {
            if (!inFlight.tryAcquire(deadlineMs, TimeUnit.MILLISECONDS)) {
                throw EngineTransportException("engine gRPC SubmitOrders lane in-flight limit reached")
            }
            acquired = true
            enqueue(item)
            return response
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            if (acquired) inFlight.release()
            throw EngineTransportException("engine gRPC SubmitOrders interrupted", ex)
        } catch (ex: RuntimeException) {
            if (acquired) inFlight.release()
            throw ex
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(streamLock) {
            requestObserver.onCompleted()
            failOutbound(EngineTransportException("engine gRPC SubmitOrders lane closed"))
        }
        failPending(EngineTransportException("engine gRPC SubmitOrders lane closed"))
    }

    private fun enqueue(item: StreamSubmit) {
        synchronized(streamLock) {
            if (closed.get()) {
                throw EngineTransportException("engine gRPC SubmitOrders lane closed")
            }
            outbound.add(item)
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        streamFailure.getAndSet(null)?.let {
            requestObserver = reopenStream(requestObserver)
        }
        while (true) {
            val observer = readyObserver
            if (observer != null && !observer.isReady) {
                return
            }
            val item = outbound.poll() ?: return
            if (item.response.isCancelled) {
                inFlight.release()
                continue
            }
            pending.add(item.response)
            try {
                requestObserver.onNext(item.request)
            } catch (ex: RuntimeException) {
                pending.remove(item.response)
                item.response.completeExceptionally(ex)
                inFlight.release()
                requestObserver = reopenStream(requestObserver)
            }
        }
    }

    private fun failOutbound(error: Throwable) {
        while (true) {
            val item = outbound.poll() ?: return
            item.response.completeExceptionally(error)
            inFlight.release()
        }
    }

    private fun reopenStream(current: StreamObserver<SubmitOrder>): StreamObserver<SubmitOrder> {
        try {
            current.onCompleted()
        } catch (_: RuntimeException) {
        }
        return openStream()
    }

    private fun openStream(): StreamObserver<SubmitOrder> {
        val responseObserver = object : ClientResponseObserver<SubmitOrder, ProtoSubmitOrderResult> {
            override fun beforeStart(requestStream: ClientCallStreamObserver<SubmitOrder>) {
                readyObserver = requestStream
                requestStream.setOnReadyHandler {
                    synchronized(streamLock) {
                        pumpLocked()
                    }
                }
            }

            override fun onNext(value: ProtoSubmitOrderResult) {
                pending.poll()?.complete(value)
                inFlight.release()
            }

            override fun onError(t: Throwable) {
                synchronized(streamLock) {
                    streamFailure.set(t)
                    failPending(t)
                    pumpLocked()
                }
            }

            override fun onCompleted() {
                val error = EngineTransportException("engine gRPC SubmitOrders stream completed")
                synchronized(streamLock) {
                    streamFailure.set(error)
                    failPending(error)
                    pumpLocked()
                }
            }
        }
        return ClientCalls.asyncBidiStreamingCall(
            channel.newCall(submitOrdersMethod, CallOptions.DEFAULT),
            responseObserver
        )
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
        .setRunId(runId)
        .setVenueSessionId(venueSessionId)
        .setClientOrderId(clientOrderId)
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
        "LIMIT_HIDDEN" -> OrderType.ORDER_TYPE_LIMIT
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
                occurredAt = execution.occurredAt,
                liquidityRole = when (execution.liquidityRole) {
                    reef.contracts.orderexecution.v1.LiquidityRole.LIQUIDITY_ROLE_MAKER -> "MAKER"
                    reef.contracts.orderexecution.v1.LiquidityRole.LIQUIDITY_ROLE_TAKER -> "TAKER"
                    else -> "UNSPECIFIED"
                }
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
