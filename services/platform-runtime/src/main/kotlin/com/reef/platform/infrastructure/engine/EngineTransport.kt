package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult

fun defaultEngineGateway(): EngineGateway {
    val transport = (System.getenv("ENGINE_TRANSPORT") ?: "http").lowercase()
    val httpBaseUrl = System.getenv("MATCHING_ENGINE_BASE_URL") ?: "http://localhost:8081"
    val grpcTarget = System.getenv("MATCHING_ENGINE_GRPC_TARGET") ?: "localhost:9081"

    return when (transport) {
        "grpc" -> GrpcEngineClient(grpcTarget, EngineClient(httpBaseUrl))
        else -> EngineClient(httpBaseUrl)
    }
}

class GrpcEngineClient(
    private val grpcTarget: String,
    private val fallback: EngineGateway
) : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        // Scaffold phase: keep behavior stable by delegating to HTTP adapter
        // while protobuf-generated stubs and RPC mappings are integrated.
        return fallback.submitOrder(command)
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return fallback.cancelOrder(command)
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return fallback.modifyOrder(command)
    }

    fun target(): String = grpcTarget
}
