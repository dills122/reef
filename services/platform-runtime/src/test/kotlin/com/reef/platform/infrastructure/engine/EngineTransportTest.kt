package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.SubmitOrderCommand
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerServiceDefinition
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import reef.contracts.orderexecution.v1.OrderAccepted
import reef.contracts.orderexecution.v1.OrderSide
import reef.contracts.orderexecution.v1.OrderType
import reef.contracts.orderexecution.v1.SubmitOrder
import reef.contracts.orderexecution.v1.SubmitOrderResult
import reef.contracts.orderexecution.v1.TimeInForce

class EngineTransportTest {
    @Test
    fun grpcTransportCallsSubmitOrderOverGrpc() {
        val capture = Capture()
        val server = buildSubmitServer(capture)
        server.start()

        try {
            val gateway = GrpcEngineClient("localhost:${server.port}")

            val result = gateway.submitOrder(
                SubmitOrderCommand(
                    commandId = "cmd-1",
                    traceId = "trace-1",
                    causationId = "cause-1",
                    correlationId = "corr-1",
                    actorId = "trader-1",
                    occurredAt = "2026-03-14T18:00:00Z",
                    orderId = "ord-1",
                    instrumentId = "AAPL",
                    participantId = "participant-1",
                    accountId = "account-1",
                    side = "BUY",
                    orderType = "LIMIT",
                    quantityUnits = "100",
                    limitPrice = "150250000000",
                    currency = "USD",
                    timeInForce = "DAY"
                )
            )

            assertEquals("localhost:${server.port}", gateway.target())
            assertNotNull(capture.request)
            assertEquals("cmd-1", capture.request!!.metadata.commandId)
            assertEquals("trace-1", capture.request!!.metadata.traceId)
            assertEquals("cause-1", capture.request!!.metadata.causationId)
            assertEquals(OrderSide.ORDER_SIDE_BUY, capture.request!!.side)
            assertEquals(OrderType.ORDER_TYPE_LIMIT, capture.request!!.orderType)
            assertEquals(TimeInForce.TIME_IN_FORCE_DAY, capture.request!!.timeInForce)
            assertNotNull(result.accepted)
            assertEquals("eng-ord-1", result.accepted!!.engineOrderId)
        } finally {
            server.shutdownNow()
        }
    }

    @Test
    fun grpcTransportUsesConfiguredDeadline() {
        val server = buildHangingSubmitServer()
        server.start()

        try {
            val gateway = GrpcEngineClient(
                grpcTarget = "localhost:${server.port}",
                requestDeadline = Duration.ofMillis(50)
            )

            val ex = assertFailsWith<EngineTransportException> {
                gateway.submitOrder(validSubmitCommand())
            }
            assertTrue(ex.message.orEmpty().contains("deadline", ignoreCase = true))
        } finally {
            server.shutdownNow()
        }
    }

    @Test
    fun grpcTransportRejectsInvalidEnumValuesBeforeCallingEngine() {
        val capture = Capture()
        val server = buildSubmitServer(capture)
        server.start()

        try {
            val gateway = GrpcEngineClient("localhost:${server.port}")

            assertFailsWith<EngineTransportException> {
                gateway.submitOrder(validSubmitCommand(side = "BID"))
            }
            assertEquals(null, capture.request)
        } finally {
            server.shutdownNow()
        }
    }

    @Test
    fun grpcTransportClosesManagedChannel() {
        val gateway = GrpcEngineClient("localhost:1")

        gateway.close()

        assertTrue(gateway.isShutdown())
    }

    @Test
    fun grpcStreamTransportCallsSubmitOrderOverPersistentStream() {
        val capture = Capture()
        val server = buildSubmitStreamServer(capture)
        server.start()

        try {
            val gateway = GrpcStreamEngineClient(
                grpcTarget = "localhost:${server.port}",
                laneCount = 1
            )

            val first = gateway.submitOrder(validSubmitCommand(commandId = "cmd-stream-1", orderId = "ord-stream-1"))
            val second = gateway.submitOrder(validSubmitCommand(commandId = "cmd-stream-2", orderId = "ord-stream-2"))

            assertEquals("localhost:${server.port}", gateway.target())
            assertEquals(listOf("cmd-stream-1", "cmd-stream-2"), capture.requests.map { it.metadata.commandId })
            assertEquals("eng-ord-stream-1", first.accepted!!.engineOrderId)
            assertEquals("eng-ord-stream-2", second.accepted!!.engineOrderId)
            gateway.close()
            assertTrue(gateway.isShutdown())
        } finally {
            server.shutdownNow()
        }
    }
}

private class Capture {
    var request: SubmitOrder? = null
    val requests = mutableListOf<SubmitOrder>()
}

private fun buildSubmitServer(capture: Capture): Server {
    val method = MethodDescriptor.newBuilder<SubmitOrder, SubmitOrderResult>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SubmitOrder"))
        .setRequestMarshaller(ProtoUtils.marshaller(SubmitOrder.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(SubmitOrderResult.getDefaultInstance()))
        .build()

    val service = ServerServiceDefinition.builder(SERVICE_NAME)
        .addMethod(method, ServerCalls.asyncUnaryCall { request: SubmitOrder, observer ->
            capture.request = request

            val response = SubmitOrderResult.newBuilder()
                .setAccepted(
                    OrderAccepted.newBuilder()
                        .setEventId("evt-1")
                        .setOrderId(request.orderId)
                        .setEngineOrderId("eng-ord-1")
                        .setOccurredAt("2026-03-14T18:00:01Z")
                        .build()
                )
                .build()

            observer.onNext(response)
            observer.onCompleted()
        })
        .build()

    return ServerBuilder.forPort(0).directExecutor().addService(service).build()
}

private fun buildSubmitStreamServer(capture: Capture): Server {
    val method = MethodDescriptor.newBuilder<SubmitOrder, SubmitOrderResult>()
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SubmitOrders"))
        .setRequestMarshaller(ProtoUtils.marshaller(SubmitOrder.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(SubmitOrderResult.getDefaultInstance()))
        .build()

    val service = ServerServiceDefinition.builder(SERVICE_NAME)
        .addMethod(method, ServerCalls.asyncBidiStreamingCall { observer ->
            object : StreamObserver<SubmitOrder> {
                override fun onNext(request: SubmitOrder) {
                    capture.requests.add(request)
                    observer.onNext(
                        SubmitOrderResult.newBuilder()
                            .setAccepted(
                                OrderAccepted.newBuilder()
                                    .setEventId("evt-${request.orderId}")
                                    .setOrderId(request.orderId)
                                    .setEngineOrderId("eng-${request.orderId}")
                                    .setOccurredAt("2026-03-14T18:00:01Z")
                                    .build()
                            )
                            .build()
                    )
                }

                override fun onError(t: Throwable) {
                }

                override fun onCompleted() {
                    observer.onCompleted()
                }
            }
        })
        .build()

    return ServerBuilder.forPort(0).directExecutor().addService(service).build()
}

private fun buildHangingSubmitServer(): Server {
    val method = MethodDescriptor.newBuilder<SubmitOrder, SubmitOrderResult>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SubmitOrder"))
        .setRequestMarshaller(ProtoUtils.marshaller(SubmitOrder.getDefaultInstance()))
        .setResponseMarshaller(ProtoUtils.marshaller(SubmitOrderResult.getDefaultInstance()))
        .build()

    val service = ServerServiceDefinition.builder(SERVICE_NAME)
        .addMethod(method, ServerCalls.asyncUnaryCall { _: SubmitOrder, _ ->
            // Intentionally leave the observer open so the client deadline is the only exit path.
        })
        .build()

    return ServerBuilder.forPort(0).directExecutor().addService(service).build()
}

private fun validSubmitCommand(
    side: String = "BUY",
    commandId: String = "cmd-1",
    orderId: String = "ord-1"
): SubmitOrderCommand {
    return SubmitOrderCommand(
        commandId = commandId,
        traceId = "trace-1",
        causationId = "cause-1",
        correlationId = "corr-1",
        actorId = "trader-1",
        occurredAt = "2026-03-14T18:00:00Z",
        orderId = orderId,
        instrumentId = "AAPL",
        participantId = "participant-1",
        accountId = "account-1",
        side = side,
        orderType = "LIMIT",
        quantityUnits = "100",
        limitPrice = "150250000000",
        currency = "USD",
        timeInForce = "DAY"
    )
}

private const val SERVICE_NAME = "reef.contracts.orderexecution.v1.OrderExecutionService"
