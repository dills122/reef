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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun sameLanePipelineSubmitsUpToWindowButPersistsInIntakeOrder() {
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
            val third = intake.enqueueSubmit(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-3",
                correlationId = "corr-3",
                body = validSubmitBody("cmd-accepted-async-3", "trace-3", "ord-3")
            )
            intake.start()

            assertTrue(first.accepted)
            assertTrue(second.accepted)
            assertTrue(third.accepted)
            assertTrue(
                waitFor(timeoutMs = 30_000) { gateway.submittedCount() == 2 },
                "submitted=${gateway.submittedCount()} pending=${gateway.pendingCount()} stats=${intake.stats()}"
            )
            assertTrue(waitFor(timeoutMs = 30_000) { intake.stats().inFlight == 2L })
            assertEquals(1, intake.stats().saturatedLaneCount)
            assertEquals(2, gateway.pendingCount())

            gateway.completeOrderAccepted("ord-2")
            assertTrue(waitFor { intake.stats().completedWaiting == 1L })
            assertEquals(null, persistence.submitResult("cmd-accepted-async-1"))
            assertEquals(null, persistence.submitResult("cmd-accepted-async-2"))
            assertEquals(CommandLogStatus.PROCESSING, intake.findCommandStatus("cmd-accepted-async-1")?.status)
            assertEquals(CommandLogStatus.PROCESSING, intake.findCommandStatus("cmd-accepted-async-2")?.status)

            gateway.completeOrderAccepted("ord-1")
            assertTrue(waitFor(timeoutMs = 30_000) { gateway.submittedCount() == 3 })
            assertTrue(waitFor(timeoutMs = 30_000) { intake.stats().inFlight == 1L })
            assertEquals(CommandLogStatus.COMPLETED, intake.findCommandStatus("cmd-accepted-async-1")?.status)
            assertEquals(CommandLogStatus.COMPLETED, intake.findCommandStatus("cmd-accepted-async-2")?.status)
            assertEquals(CommandLogStatus.PROCESSING, intake.findCommandStatus("cmd-accepted-async-3")?.status)

            gateway.completeOrderAccepted("ord-3")
            assertTrue(waitFor { intake.stats().completed == 3L })
            assertEquals(
                listOf("ord-1", "ord-2", "ord-3"),
                persistence.acceptedOrders().map { it.orderId }
            )
        } finally {
            intake.stop()
        }
    }

    @Test
    fun evictsOldTerminalStatusesWhenRetentionWindowIsFull() {
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
            terminalStatusMaxRecords = 1
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

            assertTrue(waitFor { intake.stats().statusRecordsEvicted == 1L })
            assertNull(intake.findCommandStatus("cmd-retention-1"))
            assertNull(intake.findCommandStatus("client-1", "/api/v1/orders/submit", "idem-retention-1"))
            assertEquals(CommandLogStatus.COMPLETED, intake.findCommandStatus("cmd-retention-2")?.status)
            assertEquals(1L, intake.stats().retainedTerminalStatusRecords)
            assertEquals(1L, intake.stats().retainedStatusRecords)
            assertEquals(1L, intake.stats().retainedStatuses)
            assertEquals(1L, intake.stats().retentionEvicted)

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
            terminalStatusMaxRecords = 10,
            terminalStatusTtlMs = 100L,
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
            assertEquals(1L, intake.stats().retainedTerminalStatusRecords)
            assertEquals(1L, intake.stats().statusRecordsEvicted)
            assertEquals(100L, intake.stats().terminalStatusTtlMs)
        } finally {
            intake.stop()
        }
    }

    @Test
    fun offerTimeoutMsAcceptsImmediatelyWhenLaneHasCapacity() {
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
            offerTimeoutMs = 2_000L,
            offerWaitMaxConcurrency = 1
        )

        try {
            val elapsedNanos = System.nanoTime()
            val receipt = enqueue(intake, "cmd-offer-happy", "ord-offer-happy", "idem-offer-happy")
            val elapsedMs = (System.nanoTime() - elapsedNanos) / 1_000_000
            assertTrue(receipt.accepted, "expected accept when lane has free capacity")
            assertTrue(elapsedMs < 500, "expected an immediate accept, took ${elapsedMs}ms")
        } finally {
            intake.stop()
        }
    }

    // Regression test for a runBlocking thread-pool-exhaustion risk: with
    // EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS > 0, offer() used to block
    // the calling thread inside runBlocking with no limit on how many
    // callers could do so concurrently. Since callers are HTTP application
    // threads drawn from a small, shared, bounded pool, a lane-capacity
    // backpressure spike could park the entire pool waiting for space,
    // starving unrelated routes. offerWaitMaxConcurrency bounds concurrent
    // waiters; callers past the limit must fail fast instead of queueing for
    // a wait slot.
    @Test
    fun offerTimeoutMsBulkheadFailsFastPastMaxConcurrencyInsteadOfQueueing() {
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
            queueCapacityPerLane = 1,
            inFlightPerLane = 1,
            offerTimeoutMs = 2_000L,
            offerWaitMaxConcurrency = 1
        )
        // Deliberately never call intake.start(): nothing drains the single
        // lane slot, so every enqueue after the first must wait for space.

        try {
            val first = enqueue(intake, "cmd-bulkhead-fill", "ord-bulkhead-fill", "idem-bulkhead-fill")
            assertTrue(first.accepted, "expected the first command to fill the single lane slot")

            val pool = Executors.newFixedThreadPool(2)
            val startLatch = CountDownLatch(2)
            val elapsedMsByCaller = ConcurrentLinkedQueue<Long>()
            val backpressuredByCaller = ConcurrentLinkedQueue<Boolean>()
            try {
                val futures = (0 until 2).map { i ->
                    pool.submit {
                        startLatch.countDown()
                        startLatch.await()
                        val startNanos = System.nanoTime()
                        val receipt = enqueue(
                            intake,
                            "cmd-bulkhead-waiter-$i",
                            "ord-bulkhead-waiter-$i",
                            "idem-bulkhead-waiter-$i"
                        )
                        elapsedMsByCaller.add((System.nanoTime() - startNanos) / 1_000_000)
                        backpressuredByCaller.add(receipt.backpressure)
                    }
                }
                futures.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
            }

            // Both waiters must ultimately observe backpressure: one because
            // it acquired the single wait permit and then timed out with the
            // lane still full, the other because the bulkhead had no permit
            // free at all.
            assertEquals(listOf(true, true), backpressuredByCaller.toList().sorted())

            // The key regression signal: at least one of the two concurrent
            // waiters must return fast (bulkhead-rejected), not both parked
            // for the full offerTimeoutMs. Before the fix, both callers
            // would have blocked the full ~2000ms concurrently.
            val fastCallers = elapsedMsByCaller.count { it < 500 }
            assertTrue(
                fastCallers >= 1,
                "expected at least one caller to fail fast via the bulkhead, elapsed=$elapsedMsByCaller"
            )
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
        completeAccepted(next)
    }

    fun completeOrderAccepted(orderId: String) {
        val next = pending.firstOrNull { it.command.orderId == orderId }
            ?: error("no pending submit for order $orderId")
        pending.remove(next)
        completeAccepted(next)
    }

    private fun completeAccepted(next: PendingSubmit) {
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
