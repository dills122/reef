package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ModifyOrderCommand
import com.sun.net.httpserver.HttpServer
import io.grpc.MethodDescriptor
import io.grpc.ServerBuilder
import io.grpc.ServerServiceDefinition
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ServerCalls
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import reef.contracts.orderexecution.v1.CancelOrder
import reef.contracts.orderexecution.v1.ModifyOrder
import reef.contracts.orderexecution.v1.OrderAccepted
import reef.contracts.orderexecution.v1.OrderQuantity
import reef.contracts.orderexecution.v1.Price
import reef.contracts.orderexecution.v1.SubmitOrder
import reef.contracts.orderexecution.v1.SubmitOrderResult
import reef.contracts.orderexecution.v1.TradeCreated
import reef.contracts.orderexecution.v1.ExecutionCreated

class EngineTransportParityTest {
    @Test
    fun httpAndGrpcReturnEquivalentSubmitResults() {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/orders/submit") { exchange ->
            val response = """
                {
                  "accepted":{
                    "eventId":"evt-1",
                    "orderId":"ord-1",
                    "engineOrderId":"eng-ord-1",
                    "occurredAt":"2026-03-14T18:00:00Z"
                  },
                  "executions":[
                    {
                      "eventId":"evt-exec-1",
                      "executionId":"exec-1-buy",
                      "orderId":"ord-1",
                      "instrumentId":"AAPL",
                      "quantityUnits":"100",
                      "executionPrice":"150250000000",
                      "currency":"USD",
                      "occurredAt":"2026-03-14T18:00:00Z"
                    }
                  ],
                  "trades":[
                    {
                      "eventId":"evt-trade-1",
                      "tradeId":"trade-1",
                      "executionId":"exec-1",
                      "buyOrderId":"ord-1",
                      "sellOrderId":"ord-2",
                      "instrumentId":"AAPL",
                      "quantityUnits":"100",
                      "price":"150250000000",
                      "currency":"USD",
                      "occurredAt":"2026-03-14T18:00:00Z"
                    }
                  ]
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { output -> output.write(response) }
        }
        httpServer.start()

        val grpcServer = ServerBuilder.forPort(0).directExecutor().addService(
            ServerServiceDefinition.builder(SERVICE_NAME_PARITY)
                .addMethod(
                    submitMethod(),
                    ServerCalls.asyncUnaryCall { request: SubmitOrder, observer ->
                        observer.onNext(
                            SubmitOrderResult.newBuilder()
                                .setAccepted(
                                    OrderAccepted.newBuilder()
                                        .setEventId("evt-1")
                                        .setOrderId(request.orderId)
                                        .setEngineOrderId("eng-ord-1")
                                        .setOccurredAt("2026-03-14T18:00:00Z")
                                        .build()
                                )
                                .addExecutions(
                                    ExecutionCreated.newBuilder()
                                        .setEventId("evt-exec-1")
                                        .setExecutionId("exec-1-buy")
                                        .setOrderId(request.orderId)
                                        .setInstrumentId("AAPL")
                                        .setQuantity(OrderQuantity.newBuilder().setUnits("100").build())
                                        .setExecutionPrice(Price.newBuilder().setNanos("150250000000").setCurrency("USD").build())
                                        .setOccurredAt("2026-03-14T18:00:00Z")
                                        .build()
                                )
                                .addTrades(
                                    TradeCreated.newBuilder()
                                        .setEventId("evt-trade-1")
                                        .setTradeId("trade-1")
                                        .setExecutionId("exec-1")
                                        .setBuyOrderId(request.orderId)
                                        .setSellOrderId("ord-2")
                                        .setInstrumentId("AAPL")
                                        .setQuantity(OrderQuantity.newBuilder().setUnits("100").build())
                                        .setPrice(Price.newBuilder().setNanos("150250000000").setCurrency("USD").build())
                                        .setOccurredAt("2026-03-14T18:00:00Z")
                                        .build()
                                )
                                .build()
                        )
                        observer.onCompleted()
                    }
                )
                .build()
        ).build()
        grpcServer.start()

        try {
            val command = command()
            val httpResult = EngineClient("http://localhost:${httpServer.address.port}").submitOrder(command)
            val grpcResult = GrpcEngineClient("localhost:${grpcServer.port}").submitOrder(command)
            assertEquals(httpResult, grpcResult)
        } finally {
            httpServer.stop(0)
            grpcServer.shutdownNow()
        }
    }

    @Test
    fun httpAndGrpcReturnEquivalentCancelAndModifyResults() {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        val accepted = """
            {
              "accepted":{
                "eventId":"evt-2",
                "orderId":"ord-1",
                "engineOrderId":"eng-ord-1",
                "occurredAt":"2026-03-14T18:00:00Z"
              },
              "executions":[],
              "trades":[]
            }
        """.trimIndent().toByteArray()
        httpServer.createContext("/orders/cancel") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, accepted.size.toLong())
            exchange.responseBody.use { it.write(accepted) }
        }
        httpServer.createContext("/orders/modify") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, accepted.size.toLong())
            exchange.responseBody.use { it.write(accepted) }
        }
        httpServer.start()

        val grpcServer = ServerBuilder.forPort(0).directExecutor().addService(
            ServerServiceDefinition.builder(SERVICE_NAME_PARITY)
                .addMethod(cancelMethod(), ServerCalls.asyncUnaryCall { request: CancelOrder, observer ->
                    observer.onNext(simpleAccepted(request.orderId, "evt-2"))
                    observer.onCompleted()
                })
                .addMethod(modifyMethod(), ServerCalls.asyncUnaryCall { request: ModifyOrder, observer ->
                    observer.onNext(simpleAccepted(request.orderId, "evt-2"))
                    observer.onCompleted()
                })
                .build()
        ).build()
        grpcServer.start()

        try {
            val http = EngineClient("http://localhost:${httpServer.address.port}")
            val grpc = GrpcEngineClient("localhost:${grpcServer.port}")

            val cancel = CancelOrderCommand(
                commandId = "cmd-cancel",
                traceId = "trace-1",
                causationId = "",
                correlationId = "corr-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-1",
                reason = "test"
            )
            val modify = ModifyOrderCommand(
                commandId = "cmd-modify",
                traceId = "trace-1",
                causationId = "",
                correlationId = "corr-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-1",
                quantityUnits = "100",
                limitPrice = "150250000000"
            )

            assertEquals(http.cancelOrder(cancel), grpc.cancelOrder(cancel))
            assertEquals(http.modifyOrder(modify), grpc.modifyOrder(modify))
        } finally {
            httpServer.stop(0)
            grpcServer.shutdownNow()
        }
    }

    private fun command(): SubmitOrderCommand {
        return SubmitOrderCommand(
            commandId = "cmd-1",
            traceId = "trace-1",
            causationId = "",
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
    }

    private fun submitMethod(): MethodDescriptor<SubmitOrder, SubmitOrderResult> =
        MethodDescriptor.newBuilder<SubmitOrder, SubmitOrderResult>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME_PARITY, "SubmitOrder"))
            .setRequestMarshaller(ProtoUtils.marshaller(SubmitOrder.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(SubmitOrderResult.getDefaultInstance()))
            .build()

    private fun cancelMethod(): MethodDescriptor<CancelOrder, SubmitOrderResult> =
        MethodDescriptor.newBuilder<CancelOrder, SubmitOrderResult>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME_PARITY, "CancelOrder"))
            .setRequestMarshaller(ProtoUtils.marshaller(CancelOrder.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(SubmitOrderResult.getDefaultInstance()))
            .build()

    private fun modifyMethod(): MethodDescriptor<ModifyOrder, SubmitOrderResult> =
        MethodDescriptor.newBuilder<ModifyOrder, SubmitOrderResult>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME_PARITY, "ModifyOrder"))
            .setRequestMarshaller(ProtoUtils.marshaller(ModifyOrder.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(SubmitOrderResult.getDefaultInstance()))
            .build()

    private fun simpleAccepted(orderId: String, eventId: String): SubmitOrderResult {
        return SubmitOrderResult.newBuilder()
            .setAccepted(
                OrderAccepted.newBuilder()
                    .setEventId(eventId)
                    .setOrderId(orderId)
                    .setEngineOrderId("eng-ord-1")
                    .setOccurredAt("2026-03-14T18:00:00Z")
                    .build()
            )
            .build()
    }
}

private const val SERVICE_NAME_PARITY = "reef.contracts.orderexecution.v1.OrderExecutionService"
