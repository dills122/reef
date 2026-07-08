package com.reef.platform.api

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamCommandIntakeTest {
    @Test
    fun subjectBuilderUsesDeterministicPartitionAndRoutingTokens() {
        val config = StreamCommandConfig(
            streamName = "REEF_COMMANDS",
            subjectPrefix = "reef.cmd.v1",
            partitionCount = 16
        )
        val first = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            body = validStreamSubmitBody(),
            config = config
        )
        val second = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-2",
            body = validStreamSubmitBody().replace("\"commandId\":\"cmd-1\"", "\"commandId\":\"cmd-2\""),
            config = config
        )

        val firstEnvelope = assertIs<EitherBoundaryError.Envelope>(first).envelope
        val secondEnvelope = assertIs<EitherBoundaryError.Envelope>(second).envelope
        assertEquals(firstEnvelope.partition, secondEnvelope.partition)
        assertTrue(firstEnvelope.subject.matches(Regex("""reef\.cmd\.v1\.p\d{2}\.session-1\.AAPL\.SubmitOrder""")))
    }

    @Test
    fun submitAndCancelForSameBookUseSamePartitionLane() {
        val config = StreamCommandConfig(
            streamName = "REEF_COMMANDS",
            subjectPrefix = "reef.cmd.v1",
            partitionCount = 16
        )
        val submit = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-submit",
            body = validStreamSubmitBody(),
            config = config
        )
        val cancel = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/cancel",
            idempotencyKey = "idem-cancel",
            body = validStreamCancelBody(),
            config = config
        )

        val submitEnvelope = assertIs<EitherBoundaryError.Envelope>(submit).envelope
        val cancelEnvelope = assertIs<EitherBoundaryError.Envelope>(cancel).envelope
        assertEquals(submitEnvelope.partition, cancelEnvelope.partition)
        assertTrue(cancelEnvelope.subject.matches(Regex("""reef\.cmd\.v1\.p\d{2}\.session-1\.AAPL\.CancelOrder""")))
    }

    @Test
    fun envelopeBuilderRejectsMissingRoutingMetadata() {
        val result = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/cancel",
            idempotencyKey = "idem-1",
            body = """
                {
                  "commandId":"cmd-1",
                  "traceId":"trace-1",
                  "correlationId":"corr-1",
                  "actorId":"bot-1",
                  "occurredAt":"2026-05-22T00:00:00Z",
                  "orderId":"ord-1",
                  "reason":"test"
                }
            """.trimIndent()
        )

        val error = assertIs<EitherBoundaryError.Error>(result).error
        assertEquals("STREAM_ROUTING_METADATA_REQUIRED", error.code)
        assertEquals(400, error.status)
    }

    @Test
    fun envelopeBuilderRejectsMalformedJsonBeforeRoutingValidation() {
        val result = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            body = """{"commandId":"cmd-1""""
        )

        val error = assertIs<EitherBoundaryError.Error>(result).error
        assertEquals("INVALID_JSON", error.code)
        assertEquals(400, error.status)
        assertEquals("invalid json payload", error.message)
    }

    @Test
    fun intakeStoreReplaysSamePayloadAndConflictsDifferentPayload() {
        val store = InMemoryStreamCommandIntakeStore()
        val envelope = assertIs<EitherBoundaryError.Envelope>(
            StreamCommandEnvelopeBuilder.fromRequest(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-1",
                body = validStreamSubmitBody()
            )
        ).envelope
        val first = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        val published = store.markPublished(envelope.scope, envelope.idempotencyKey, 42L)
        val replay = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        val changedEnvelope = envelope.copy(payloadHash = "different-hash")
        val conflict = store.reserve(changedEnvelope, changedEnvelope.reference("REEF_COMMANDS"))

        assertIs<StreamCommandReservation.Reserved>(first)
        assertTrue(published)
        assertEquals(42L, assertIs<StreamCommandReservation.Replay>(replay).reference.streamSequence)
        assertIs<StreamCommandReservation.Conflict>(conflict)
    }

    @Test
    fun intakeStoreCanBoundInMemoryRetentionWindow() {
        val store = InMemoryStreamCommandIntakeStore(maxEntries = 2, shardCount = 1)
        val first = validEnvelope(commandId = "cmd-window-1", idempotencyKey = "idem-window-1")
        val second = validEnvelope(commandId = "cmd-window-2", idempotencyKey = "idem-window-2")
        val third = validEnvelope(commandId = "cmd-window-3", idempotencyKey = "idem-window-3")

        assertIs<StreamCommandReservation.Reserved>(store.reserve(first, first.reference("REEF_COMMANDS")))
        assertTrue(store.markPublishedByCommandId(first.commandId, 11L))
        assertIs<StreamCommandReservation.Reserved>(store.reserve(second, second.reference("REEF_COMMANDS")))
        assertIs<StreamCommandReservation.Reserved>(store.reserve(third, third.reference("REEF_COMMANDS")))

        assertTrue(!store.markPublishedByCommandId(first.commandId, 12L))
        assertIs<StreamCommandReservation.Reserved>(store.reserve(first, first.reference("REEF_COMMANDS")))
        val replay = store.reserve(third, third.reference("REEF_COMMANDS"))
        assertEquals(0L, assertIs<StreamCommandReservation.Replay>(replay).reference.streamSequence)
    }

    /**
     * WORK_PLAN.md crash/restart scenario 1: "API publishes then exits before boundary
     * published-marker update." The intake row is reserved (published=false,
     * streamSequence=0), the durable broker ack for the publish arrives, but the
     * process exits before `markPublished`/`markPublishedByCommandId` records it. This
     * proves the recovery path is safe: on "restart" (a retried request with the same
     * idempotency key), the intake store still reports the command as unconfirmed
     * (streamSequence == 0), so the boundary knows it must republish rather than
     * silently trusting a stale, unmarked reservation. Once the repair publish
     * succeeds and the marker is recorded, further replays converge on that single
     * durable stream sequence - no duplicate row, no lost command reference.
     */
    @Test
    fun apiExitsAfterPublishAckBeforeBoundaryMarkerUpdate_retryRepublishesAndConverges() {
        val store = InMemoryStreamCommandIntakeStore()
        val envelope = assertIs<EitherBoundaryError.Envelope>(
            StreamCommandEnvelopeBuilder.fromRequest(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-crash-before-mark",
                body = validStreamSubmitBody()
            )
        ).envelope

        val reservation = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        assertIs<StreamCommandReservation.Reserved>(reservation)

        // Durable publish is acknowledged by the broker (sequence 55 in a real system)
        // but the process exits here, before markPublished ever executes - the intake
        // row is left with streamSequence=0/published=false despite the command
        // already being durable in the stream.

        // "Restart": a client retry (or a boundary reconciliation sweep) reserves the
        // same idempotency key again. It must NOT be told the command is durably
        // confirmed, because the local marker never recorded that.
        val afterCrashRetry = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        val unmarkedReplay = assertIs<StreamCommandReservation.Replay>(afterCrashRetry)
        assertEquals(0L, unmarkedReplay.reference.streamSequence)

        // Recovery republishes (a new broker ack, sequence 56) and this time the
        // marker update completes before any further crash.
        assertTrue(store.markPublishedByCommandId(envelope.commandId, 56L))

        // Subsequent replays now observe the durable marker and stop retrying.
        val stable = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        assertEquals(56L, assertIs<StreamCommandReservation.Replay>(stable).reference.streamSequence)
        assertEquals(56L, store.findByCommandId(envelope.commandId)?.streamSequence)

        // A late duplicate mark-published call (e.g. the original, pre-crash async
        // marker flush finally landing after recovery already repaired it) must not
        // regress the recorded sequence.
        assertTrue(store.markPublishedByCommandId(envelope.commandId, 56L))
        assertEquals(56L, store.findByCommandId(envelope.commandId)?.streamSequence)
    }

    @Test
    fun asyncPublicationMarkerEventuallyMarksReservedCommandPublished() {
        val store = InMemoryStreamCommandIntakeStore()
        val envelope = assertIs<EitherBoundaryError.Envelope>(
            StreamCommandEnvelopeBuilder.fromRequest(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = "idem-async",
                body = validStreamSubmitBody()
            )
        ).envelope
        val first = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
        val marker = AsyncStreamCommandPublicationMarker(store, workerCount = 1, queueCapacity = 10)

        assertIs<StreamCommandReservation.Reserved>(first)
        assertTrue(marker.markPublishedByCommandId(envelope.commandId, 99L))
        eventually {
            val replay = store.reserve(envelope, envelope.reference("REEF_COMMANDS"))
            assertEquals(99L, assertIs<StreamCommandReservation.Replay>(replay).reference.streamSequence)
        }
    }

    @Test
    fun partitionedPublisherCompletesAfterAsyncDelegateDurableAck() {
        val delegate = ControlledAsyncPublisher()
        val publisher = PartitionedStreamCommandPublisher(
            delegate = delegate,
            config = StreamCommandConfig(partitionCount = 2),
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val envelope = validEnvelope(commandId = "cmd-pipeline-ack", idempotencyKey = "idem-pipeline-ack")

        val future = publisher.publishAsync(envelope)
        eventually { assertEquals(1, delegate.received.get()) }
        assertTrue(!future.isDone)

        delegate.completeNext(StreamPublishAck("REEF_COMMANDS", 77L))

        assertEquals(77L, future.get(2, TimeUnit.SECONDS).streamSequence)
        val snapshot = publisher.snapshot()
        assertEquals(2, snapshot.publishLaneCount)
        assertEquals(2, snapshot.publishMaxInFlight)
        assertEquals("partitioned-async-delegate:controlled", snapshot.publishMode)
        assertEquals(1, snapshot.publishAccepted)
        assertEquals(1, snapshot.publishCompleted)
        assertEquals(0, snapshot.publishFailed)
        assertEquals(0, snapshot.publishRejected)
        assertEquals(1, snapshot.publishLaneSnapshots.sumOf { it.maxInFlightObserved })
    }

    @Test
    fun partitionedPublisherRejectsWhenLaneQueueIsFull() {
        val delegate = ControlledAsyncPublisher()
        val publisher = PartitionedStreamCommandPublisher(
            delegate = delegate,
            config = StreamCommandConfig(partitionCount = 1),
            queueCapacityPerLane = 1,
            maxInFlightPerLane = 1
        )

        publisher.publishAsync(validEnvelope(commandId = "cmd-pipeline-1", idempotencyKey = "idem-pipeline-1"))
        eventually { assertEquals(1, delegate.received.get()) }
        publisher.publishAsync(validEnvelope(commandId = "cmd-pipeline-2", idempotencyKey = "idem-pipeline-2"))
        eventually {
            val snapshot = publisher.snapshot()
            assertEquals(2, snapshot.publishAccepted)
            assertEquals(0, snapshot.publishQueueDepth)
            assertEquals(1, snapshot.publishInFlight)
        }
        publisher.publishAsync(validEnvelope(commandId = "cmd-pipeline-3", idempotencyKey = "idem-pipeline-3"))
        eventually { assertEquals(1, publisher.snapshot().publishQueueDepth) }

        val rejected = publisher.publishAsync(validEnvelope(commandId = "cmd-pipeline-4", idempotencyKey = "idem-pipeline-4"))

        assertTrue(rejected.isCompletedExceptionally)
        val snapshot = publisher.snapshot()
        assertEquals(3, snapshot.publishAccepted)
        assertEquals(1, snapshot.publishRejected)
        assertEquals(1, snapshot.publishLaneSnapshots.single().maxQueueDepthObserved)
    }

    @Test
    fun partitionedPublisherCanSubmitQueuedRequestsAsBatch() {
        val delegate = ControlledAsyncPublisher()
        val publisher = PartitionedStreamCommandPublisher(
            delegate = delegate,
            config = StreamCommandConfig(partitionCount = 1),
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 3,
            batchSizePerLane = 3,
            batchLinger = Duration.ofMillis(100)
        )

        val first = publisher.publishAsync(validEnvelope(commandId = "cmd-batch-1", idempotencyKey = "idem-batch-1"))
        val second = publisher.publishAsync(validEnvelope(commandId = "cmd-batch-2", idempotencyKey = "idem-batch-2"))
        val third = publisher.publishAsync(validEnvelope(commandId = "cmd-batch-3", idempotencyKey = "idem-batch-3"))

        eventually {
            assertEquals(1, delegate.batchCalls.get())
            assertEquals(3, delegate.received.get())
            assertEquals(listOf(3), delegate.batchSizes.toList())
        }
        assertTrue(!first.isDone)
        assertTrue(!second.isDone)
        assertTrue(!third.isDone)

        delegate.completeNext(StreamPublishAck("REEF_COMMANDS", 101L))
        delegate.completeNext(StreamPublishAck("REEF_COMMANDS", 102L))
        delegate.completeNext(StreamPublishAck("REEF_COMMANDS", 103L))

        assertEquals(101L, first.get(2, TimeUnit.SECONDS).streamSequence)
        assertEquals(102L, second.get(2, TimeUnit.SECONDS).streamSequence)
        assertEquals(103L, third.get(2, TimeUnit.SECONDS).streamSequence)
        val snapshot = publisher.snapshot()
        assertEquals("partitioned-batch-async-delegate:controlled", snapshot.publishMode)
        assertEquals(3, snapshot.publishCompleted)
        assertEquals(3, snapshot.publishLaneSnapshots.single().maxInFlightObserved)
    }

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 2_000_000_000L
        var last: AssertionError? = null
        while (System.nanoTime() < deadline) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                last = error
                Thread.sleep(10)
            }
        }
        throw last ?: AssertionError("condition was not met")
    }

    private fun validStreamSubmitBody(): String {
        return """
            {
              "commandId":"cmd-1",
              "traceId":"trace-1",
              "causationId":"",
              "correlationId":"corr-1",
              "actorId":"bot-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "runId":"run-1",
              "venueSessionId":"session-1",
              "clientOrderId":"clord-1",
              "orderId":"ord-1",
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

    private fun validStreamCancelBody(): String {
        return """
            {
              "commandId":"cmd-cancel-1",
              "traceId":"trace-cancel-1",
              "correlationId":"corr-1",
              "causationId":"cause-1",
              "actorId":"bot-1",
              "runId":"run-1",
              "venueSessionId":"session-1",
              "orderId":"ord-1",
              "instrumentId":"AAPL",
              "reason":"test"
            }
        """.trimIndent()
    }

    private fun validEnvelope(commandId: String, idempotencyKey: String): StreamCommandEnvelope {
        return assertIs<EitherBoundaryError.Envelope>(
            StreamCommandEnvelopeBuilder.fromRequest(
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                idempotencyKey = idempotencyKey,
                body = validStreamSubmitBody().replace("\"commandId\":\"cmd-1\"", "\"commandId\":\"$commandId\""),
                config = StreamCommandConfig(partitionCount = 1)
            )
        ).envelope
    }
}

private class ControlledAsyncPublisher : StreamCommandPublisher, AsyncStreamCommandPublisher, BatchAsyncStreamCommandPublisher, StreamCommandHealthCheck {
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<StreamPublishAck>>()
    val received = AtomicInteger(0)
    val batchCalls = AtomicInteger(0)
    val batchSizes = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        return publishAsync(envelope).get(2, TimeUnit.SECONDS)
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        received.incrementAndGet()
        val future = CompletableFuture<StreamPublishAck>()
        pending.add(future)
        return future
    }

    override fun publishAsyncBatch(envelopes: List<StreamCommandEnvelope>): List<CompletableFuture<StreamPublishAck>> {
        batchCalls.incrementAndGet()
        batchSizes.add(envelopes.size)
        return envelopes.map { envelope -> publishAsync(envelope) }
    }

    override fun snapshot(): StreamCommandHealthSnapshot {
        return StreamCommandHealthSnapshot(
            available = true,
            streamName = "REEF_COMMANDS",
            publishMode = "controlled"
        )
    }

    fun completeNext(ack: StreamPublishAck) {
        pending.poll()?.complete(ack)
    }
}
