package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.TradeCreated

interface RuntimePersistence {
    fun saveAcceptedOrder(order: PersistedOrder)
    fun saveExecutions(executions: List<ExecutionCreated>)
    fun saveTrades(trades: List<TradeCreated>)
    fun saveEvent(event: RuntimeEvent)
    fun acceptedOrder(orderId: String): PersistedOrder?
    fun executionsForOrder(orderId: String): List<ExecutionCreated>
    fun tradesForOrder(orderId: String): List<TradeCreated>
    fun eventsForOrder(orderId: String): List<RuntimeEvent>
    fun events(): List<RuntimeEvent>
}
