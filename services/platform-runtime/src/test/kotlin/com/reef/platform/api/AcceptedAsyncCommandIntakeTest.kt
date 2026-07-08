package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.AsyncSubmitEngineGateway
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcceptedAsyncCommandIntakeTest {
    @Test
    fun sameLaneCommandsDoNotSubmitSecondCommandBeforeFirstCompletes() {
        val persistence = seededPersistence()
        val gateway = ControlledAsyncSubmitGateway()
        val intake = AcceptedAsyncCommandIntake(
            api = PlatformApi(
                OrderApplicationService(
                    engineGateway = gateway,
                    runtimePersistence = persistence
                )
            ),
            laneCount = 1,
            queueCapacityPerLane = 10,
            inFlightPerLane = 1
        )

        intake.start()
        try {
            val first = intake.enqueueSubmit(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-1",
                correlationId = "corr-1",
                body = validSubmitBody("cmd-accepted-async-1", "trace-1", "ord-1")
            )
            val second = intake.enqueueSubmit(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-2",
                correlationId = "corr-2",
                body = validSubmitBody("cmd-accepted-async-2", "trace-2", "ord-2")
            )

            assertTrue(first.accepted)
            assertTrue(second.accepted)
            assertTrue(waitFor { gateway.pendingCount() == 1 })
            Thread.sleep(100)
            assertEquals(1, gateway.pendingCount(), "second same-lane command must wait for first completion")

            gateway.completeNextAccepted()
            assertTrue(waitFor { persistence.submitResult("cmd-accepted-async-1") != null })
            assertEquals(null, persistence.submitResult("cmd-accepted-async-2"))

            assertTrue(waitFor { gateway.submittedCount() == 2 })
            gateway.completeNextAccepted()
            assertTrue(waitFor { persistence.submitResult("cmd-accepted-async-2") != null })

            assertNotNull(intake.findCommandStatus("cmd-accepted-async-1"))
            assertEquals(
                listOf("ord-1", "ord-2"),
                persistence.acceptedOrders().map { it.orderId }
            )
        } finally {
            intake.stop()
        }
    }

    @Test
    fun sameLaneCommandsHonorConfiguredInFlightWindow() {
        val persistence = seededPersistence()
        val gateway = ControlledAsyncSubmitGateway()
        val intake = AcceptedAsyncCommandIntake(
            api = PlatformApi(
                OrderApplicationService(
                    engineGateway = gateway,
                    runtimePersistence = persistence
                )
            ),
            laneCount = 1,
            queueCapacityPerLane = 10,
            inFlightPerLane = 2
        )

        intake.start()
        try {
            val first = intake.enqueueSubmit(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-1",
                correlationId = "corr-1",
                body = validSubmitBody("cmd-accepted-async-window-1", "trace-1", "ord-window-1")
            )
            val second = intake.enqueueSubmit(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-2",
                correlationId = "corr-2",
                body = validSubmitBody("cmd-accepted-async-window-2", "trace-2", "ord-window-2")
            )

            assertTrue(first.accepted)
            assertTrue(second.accepted)
            assertTrue(waitFor { gateway.pendingCount() == 2 })
            assertEquals(2, intake.stats().inFlightPerLane)
            assertEquals(2, intake.stats().processing)

            gateway.completeNextAccepted()
            gateway.completeNextAccepted()
            assertTrue(waitFor { persistence.submitResult("cmd-accepted-async-window-1") != null })
            assertTrue(waitFor { persistence.submitResult("cmd-accepted-async-window-2") != null })
        } finally {
            intake.stop()
        }
    }

    @Test
    fun terminalStatusesAreBoundedByRetentionMaxRecords() {
        val persistence = seededPersistence()
        val gateway = ControlledAsyncSubmitGateway()
        val intake = AcceptedAsyncCommandIntake(
            api = PlatformApi(
                OrderApplicationService(
                    engineGateway = gateway,
                    runtimePersistence = persistence
                )
            ),
            laneCount = 1,
            queueCapacityPerLane = 10,
            inFlightPerLane = 1,
            statusRetentionMaxRecords = 1,
            statusRetentionTtlMs = 0L
        )

        intake.start()
        try {
            assertTrue(enqueue(intake, "cmd-retention-1", "ord-retention-1", "idem-retention-1").accepted)
            assertTrue(waitFor { gateway.pendingCount() == 1 })
            gateway.completeNextAccepted()
            assertTrue(waitFor { intake.findCommandStatus("cmd-retention-1")?.status == CommandLogStatus.COMPLETED })

            assertTrue(enqueue(intake, "cmd-retention-2", "ord-retention-2", "idem-retention-2").accepted)
            assertTrue(waitFor { gateway.pendingCount() == 1 })
            gateway.completeNextAccepted()
            assertTrue(waitFor { intake.findCommandStatus("cmd-retention-2")?.status == CommandLogStatus.COMPLETED })

            assertNull(intake.findCommandStatus("cmd-retention-1"))
            assertNull(intake.findCommandStatus("client-1", "/api/v1/orders/submit", "idem-retention-1"))
            assertEquals(1, intake.stats().retainedStatuses)
            assertEquals(1, intake.stats().retentionEvicted)

            val reused = enqueue(intake, "cmd-retention-3", "ord-retention-3", "idem-retention-1")
            assertTrue(reused.accepted, "evicted idempotency key should be reusable")
        } finally {
            intake.stop()
        }
    }

    @Test
    fun terminalStatusesExpireByRetentionTtl() {
        var now = 1_000L
        val persistence = seededPersistence()
        val gateway = ControlledAsyncSubmitGateway()
        val intake = AcceptedAsyncCommandIntake(
            api = PlatformApi(
                OrderApplicationService(
                    engineGateway = gateway,
                    runtimePersistence = persistence
                )
            ),
            laneCount = 1,
            queueCapacityPerLane = 10,
            inFlightPerLane = 1,
            statusRetentionMaxRecords = 10,
            statusRetentionTtlMs = 100L,
            clockMillis = { now }
        )

        intake.start()
        try {
            assertTrue(enqueue(intake, "cmd-retention-ttl-1", "ord-retention-ttl-1", "idem-retention-ttl-1").accepted)
            assertTrue(waitFor { gateway.pendingCount() == 1 })
            gateway.completeNextAccepted()
            assertTrue(waitFor { intake.findCommandStatus("cmd-retention-ttl-1")?.status == CommandLogStatus.COMPLETED })

            now += 100L
            assertTrue(enqueue(intake, "cmd-retention-ttl-2", "ord-retention-ttl-2", "idem-retention-ttl-2").accepted)
            assertTrue(waitFor { gateway.pendingCount() == 1 })
            gateway.completeNextAccepted()
            assertTrue(waitFor { intake.findCommandStatus("cmd-retention-ttl-2")?.status == CommandLogStatus.COMPLETED })

            assertNull(intake.findCommandStatus("cmd-retention-ttl-1"))
            assertEquals(1, intake.stats().retainedStatuses)
            assertEquals(1, intake.stats().retentionEvicted)
        } finally {
            intake.stop()
        }
    }

    private fun enqueue(
        intake: AcceptedAsyncCommandIntake,
        commandId: String,
        orderId: String,
        idempotencyKey: String
    ): AcceptedAsyncCommandReceipt {
        return intake.enqueueSubmit(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = idempotencyKey,
            correlationId = "corr-$commandId",
            body = validSubmitBody(commandId, "trace-$commandId", orderId)
        )
    }

    private fun seededPersistence(): InMemoryRuntimePersistence {
        return InMemoryRuntimePersistence().also { persistence ->
            persistence.saveInstrument(Instrument("AAPL", "AAPL"))
            persistence.saveParticipant(Participant("participant-1", "Participant 1"))
            persistence.saveAccount(Account("account-1", "participant-1"))
            persistence.saveRole(RoleDefinition("bot-trader", listOf(Permission.ORDER_SUBMIT)))
            persistence.saveActorRoleBinding(ActorRoleBinding("bot-1", "bot-trader"))
        }
    }

    private fun validSubmitBody(commandId: String, traceId: String, orderId: String): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"corr-$commandId",
              "actorId":"bot-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()
    }

    private fun waitFor(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

private class ControlledAsyncSubmitGateway : EngineGateway, AsyncSubmitEngineGateway {
    private val pending = ConcurrentLinkedQueue<PendingSubmit>()
    private val submitted = AtomicInteger(0)

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        throw UnsupportedOperationException("expected async submit path")
    }

    override fun submitOrderAsync(command: SubmitOrderCommand): CompletableFuture<SubmitOrderResult> {
        val future = CompletableFuture<SubmitOrderResult>()
        pending.add(PendingSubmit(command, future))
        submitted.incrementAndGet()
        return future
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        throw UnsupportedOperationException("cancel not used")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        throw UnsupportedOperationException("modify not used")
    }

    fun pendingCount(): Int = pending.size

    fun submittedCount(): Int = submitted.get()

    fun completeNextAccepted() {
        val next = pending.poll() ?: error("no pending submit")
        next.future.complete(
            SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = "evt-${next.command.orderId}",
                    orderId = next.command.orderId,
                    engineOrderId = "eng-${next.command.orderId}",
                    occurredAt = next.command.occurredAt
                )
            )
        )
    }

    private data class PendingSubmit(
        val command: SubmitOrderCommand,
        val future: CompletableFuture<SubmitOrderResult>
    )
}
