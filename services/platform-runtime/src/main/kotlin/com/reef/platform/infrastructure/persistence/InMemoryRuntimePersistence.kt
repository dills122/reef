package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.TradeCreated

class InMemoryRuntimePersistence : RuntimePersistence {
    private val orders = linkedMapOf<String, PersistedOrder>()
    private val executions = mutableListOf<ExecutionCreated>()
    private val trades = mutableListOf<TradeCreated>()

    override fun saveAcceptedOrder(order: PersistedOrder) {
        orders[order.orderId] = order
    }

    override fun saveExecutions(executions: List<ExecutionCreated>) {
        this.executions.addAll(executions)
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        this.trades.addAll(trades)
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        return orders[orderId]
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> {
        return executions.filter { it.orderId == orderId }
    }

    override fun tradesForOrder(orderId: String): List<TradeCreated> {
        return trades.filter { it.buyOrderId == orderId || it.sellOrderId == orderId }
    }
}
