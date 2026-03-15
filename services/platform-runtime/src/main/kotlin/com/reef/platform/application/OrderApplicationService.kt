package com.reef.platform.application

import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineClient

class OrderApplicationService(
    private val engineClient: EngineClient = EngineClient()
) {
    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return engineClient.submitOrder(command)
    }
}
