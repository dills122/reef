package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated

interface RuntimePersistence {
    fun saveSubmitResult(commandId: String, result: SubmitOrderResult)
    fun submitResult(commandId: String): SubmitOrderResult?
    fun saveAcceptedOrder(order: PersistedOrder)
    fun saveExecutions(executions: List<ExecutionCreated>)
    fun saveTrades(trades: List<TradeCreated>)
    fun saveEvent(event: RuntimeEvent)
    fun acceptedOrder(orderId: String): PersistedOrder?
    fun acceptedOrders(): List<PersistedOrder>
    fun executionsForOrder(orderId: String): List<ExecutionCreated>
    fun trades(): List<TradeCreated>
    fun tradesForOrder(orderId: String): List<TradeCreated>
    fun eventsForOrder(orderId: String): List<RuntimeEvent>
    fun eventsForTrace(traceId: String): List<RuntimeEvent>
    fun events(): List<RuntimeEvent>
}
