package com.reef.platform.application

import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineClient
import com.reef.platform.infrastructure.engine.EngineGateway

class OrderApplicationService(
    private val engineGateway: EngineGateway = EngineClient()
) {
    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return engineGateway.submitOrder(command)
    }
}
