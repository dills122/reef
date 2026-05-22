package com.reef.platform.infrastructure.engine

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult

interface EngineGateway {
    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult
    fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult
    fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult
}
