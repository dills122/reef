package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated

class InMemoryRuntimePersistence : RuntimePersistence {
    private val submitResults = linkedMapOf<String, SubmitOrderResult>()
    private val orders = linkedMapOf<String, PersistedOrder>()
    private val executions = mutableListOf<ExecutionCreated>()
    private val trades = mutableListOf<TradeCreated>()
    private val events = mutableListOf<RuntimeEvent>()

    override fun saveSubmitResult(commandId: String, result: SubmitOrderResult) {
        submitResults[commandId] = result
    }

    override fun submitResult(commandId: String): SubmitOrderResult? {
        return submitResults[commandId]
    }

    override fun saveAcceptedOrder(order: PersistedOrder) {
        orders[order.orderId] = order
    }

    override fun saveExecutions(executions: List<ExecutionCreated>) {
        this.executions.addAll(executions)
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        this.trades.addAll(trades)
    }

    override fun saveEvent(event: RuntimeEvent) {
        events.add(event)
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        return orders[orderId]
    }

    override fun acceptedOrders(): List<PersistedOrder> {
        return orders.values.toList()
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> {
        return executions.filter { it.orderId == orderId }
    }

    override fun tradesForOrder(orderId: String): List<TradeCreated> {
        return trades.filter { it.buyOrderId == orderId || it.sellOrderId == orderId }
    }

    override fun trades(): List<TradeCreated> {
        return trades.toList()
    }

    override fun eventsForOrder(orderId: String): List<RuntimeEvent> {
        return events.filter { it.orderId == orderId }
    }

    override fun eventsForTrace(traceId: String): List<RuntimeEvent> {
        return events.filter { it.traceId == traceId }
    }

    override fun events(): List<RuntimeEvent> {
        return events.toList()
    }
}
