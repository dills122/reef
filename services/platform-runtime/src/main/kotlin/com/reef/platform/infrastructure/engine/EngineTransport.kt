package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
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

    return when (transport) {
        "grpc" -> GrpcEngineClient(grpcTarget)
        else -> EngineClient(httpBaseUrl)
    }
}

class GrpcEngineClient(
    private val grpcTarget: String
) : EngineGateway {
    private val channel: ManagedChannel = ManagedChannelBuilder.forTarget(grpcTarget).usePlaintext().build()

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val req = SubmitOrder.newBuilder()
            .setMetadata(command.toMetadata())
            .setOrderId(command.orderId)
            .setInstrumentId(command.instrumentId)
            .setParticipantId(command.participantId)
            .setAccountId(command.accountId)
            .setSide(command.side.toProtoSide())
            .setOrderType(command.orderType.toProtoOrderType())
            .setQuantity(OrderQuantity.newBuilder().setUnits(command.quantityUnits).build())
            .setLimitPrice(Price.newBuilder().setNanos(command.limitPrice).setCurrency(command.currency).build())
            .setTimeInForce(command.timeInForce.toProtoTimeInForce())
            .build()

        val response = ClientCalls.blockingUnaryCall(channel, submitMethod, io.grpc.CallOptions.DEFAULT, req)
        return response.toDomainResult()
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        val req = CancelOrder.newBuilder()
            .setMetadata(command.toMetadata())
            .setOrderId(command.orderId)
            .setReason(command.reason)
            .build()

        val response = ClientCalls.blockingUnaryCall(channel, cancelMethod, io.grpc.CallOptions.DEFAULT, req)
        return response.toDomainResult()
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        val req = ModifyOrder.newBuilder()
            .setMetadata(command.toMetadata())
            .setOrderId(command.orderId)
            .setQuantity(OrderQuantity.newBuilder().setUnits(command.quantityUnits).build())
            .setLimitPrice(Price.newBuilder().setNanos(command.limitPrice).setCurrency("USD").build())
            .build()

        val response = ClientCalls.blockingUnaryCall(channel, modifyMethod, io.grpc.CallOptions.DEFAULT, req)
        return response.toDomainResult()
    }

    fun target(): String = grpcTarget

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

private fun SubmitOrderCommand.toMetadata(): CommandMetadata =
    CommandMetadata.newBuilder()
        .setCommandId(commandId)
        .setCorrelationId(correlationId)
        .setActorId(actorId)
        .setOccurredAt(occurredAt)
        .build()

private fun CancelOrderCommand.toMetadata(): CommandMetadata =
    CommandMetadata.newBuilder()
        .setCommandId(commandId)
        .setCorrelationId(correlationId)
        .setActorId(actorId)
        .setOccurredAt(occurredAt)
        .build()

private fun ModifyOrderCommand.toMetadata(): CommandMetadata =
    CommandMetadata.newBuilder()
        .setCommandId(commandId)
        .setCorrelationId(correlationId)
        .setActorId(actorId)
        .setOccurredAt(occurredAt)
        .build()

private fun String.toProtoSide(): OrderSide =
    when (uppercase()) {
        "SELL" -> OrderSide.ORDER_SIDE_SELL
        "BUY" -> OrderSide.ORDER_SIDE_BUY
        else -> OrderSide.ORDER_SIDE_UNSPECIFIED
    }

private fun String.toProtoOrderType(): OrderType =
    when (uppercase()) {
        "LIMIT" -> OrderType.ORDER_TYPE_LIMIT
        else -> OrderType.ORDER_TYPE_UNSPECIFIED
    }

private fun String.toProtoTimeInForce(): TimeInForce =
    when (uppercase()) {
        "IOC" -> TimeInForce.TIME_IN_FORCE_IOC
        "DAY" -> TimeInForce.TIME_IN_FORCE_DAY
        else -> TimeInForce.TIME_IN_FORCE_UNSPECIFIED
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
