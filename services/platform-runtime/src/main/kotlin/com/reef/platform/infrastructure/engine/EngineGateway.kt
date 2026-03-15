package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult

interface EngineGateway {
    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult
}
