package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class AcceptedAsyncCommandReceipt(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val backpressure: Boolean = false,
    val status: CommandStatusView? = null
)

data class AcceptedAsyncCommandStats(
    val enabled: Boolean,
    val laneCount: Int,
    val queueCapacityPerLane: Int,
    val inFlightPerLane: Int,
    val queued: Long,
    val maxLaneDepth: Int,
    val received: Long,
    val duplicates: Long,
    val backpressured: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long,
    val lastReceivedAt: String,
    val lastCompletedAt: String,
    val lastFailedAt: String
)

class AcceptedAsyncCommandIntake(
    private val api: PlatformApi,
    laneCount: Int = RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_LANES", 16, min = 1),
    private val queueCapacityPerLane: Int = RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_QUEUE_CAPACITY", 100_000, min = 1),
    private val inFlightPerLane: Int = RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE", 64, min = 1),
    private val offerTimeoutMs: Long = RuntimeEnv.long("EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS", 0L, min = 0L)
) : CommandStatusLookup {
    private val lanes: Array<ArrayBlockingQueue<AcceptedAsyncCommand>> =
        Array(laneCount) { ArrayBlockingQueue(queueCapacityPerLane) }
    private val recordsByCommandId = ConcurrentHashMap<String, AcceptedAsyncCommandRecord>()
    private val commandIdByIdempotency = ConcurrentHashMap<String, String>()
    private val started = AtomicBoolean(false)
    private val received = AtomicLong(0)
    private val duplicates = AtomicLong(0)
    private val backpressured = AtomicLong(0)
    private val processing = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val lastReceivedAtEpochMs = AtomicLong(0)
    private val lastCompletedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        lanes.indices.forEach { lane ->
            thread(name = "reef-accepted-async-lane-$lane", isDaemon = true) {
                processLane(lanes[lane])
            }
        }
    }

    fun stop() {
        started.set(false)
    }

    fun enqueueSubmit(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ): AcceptedAsyncCommandReceipt {
        val command = HotPathMetrics.time("api.acceptedAsync.parseSubmitOrder") {
            PlatformCommandParsers.submitOrder(body)
        }
        val idempotencyKeyValue = idempotencyKey(clientId, route, idempotencyKey)
        val existingCommandId = commandIdByIdempotency.putIfAbsent(idempotencyKeyValue, command.commandId)
        if (existingCommandId != null) {
            duplicates.incrementAndGet()
            return AcceptedAsyncCommandReceipt(
                accepted = false,
                duplicate = true,
                status = recordsByCommandId[existingCommandId]?.toStatusView()
            )
        }

        val now = System.currentTimeMillis()
        val record = AcceptedAsyncCommandRecord(
            commandId = command.commandId,
            clientId = clientId,
            route = route,
            idempotencyKey = idempotencyKey,
            status = CommandLogStatus.RECEIVED,
            responseStatus = 0,
            responsePayloadJson = "",
            lastError = "",
            updatedAtEpochMs = now
        )
        recordsByCommandId[command.commandId] = record
        val lane = laneFor(command.instrumentId)
        val accepted = HotPathMetrics.time("api.acceptedAsync.enqueue") {
            offer(lanes[lane], AcceptedAsyncCommand(record, body))
        }
        if (!accepted) {
            backpressured.incrementAndGet()
            val failed = record.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = 429,
                lastError = "accepted-async lane queue is full",
                updatedAtEpochMs = System.currentTimeMillis()
            )
            recordsByCommandId[command.commandId] = failed
            commandIdByIdempotency.remove(idempotencyKeyValue, command.commandId)
            return AcceptedAsyncCommandReceipt(accepted = false, backpressure = true, status = failed.toStatusView())
        }

        received.incrementAndGet()
        lastReceivedAtEpochMs.set(now)
        return AcceptedAsyncCommandReceipt(accepted = true, status = record.toStatusView())
    }

    override fun findCommandStatus(commandId: String): CommandStatusView? {
        return recordsByCommandId[commandId]?.toStatusView()
    }

    override fun findCommandStatus(clientId: String, route: String, idempotencyKey: String): CommandStatusView? {
        val commandId = commandIdByIdempotency[idempotencyKey(clientId, route, idempotencyKey)] ?: return null
        return findCommandStatus(commandId)
    }

    fun stats(): AcceptedAsyncCommandStats {
        val depths = lanes.map { it.size }
        return AcceptedAsyncCommandStats(
            enabled = started.get(),
            laneCount = lanes.size,
            queueCapacityPerLane = queueCapacityPerLane,
            inFlightPerLane = inFlightPerLane,
            queued = depths.sum().toLong(),
            maxLaneDepth = depths.maxOrNull() ?: 0,
            received = received.get(),
            duplicates = duplicates.get(),
            backpressured = backpressured.get(),
            processing = processing.get(),
            completed = completed.get(),
            failed = failed.get(),
            lastReceivedAt = instantString(lastReceivedAtEpochMs.get()),
            lastCompletedAt = instantString(lastCompletedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get())
        )
    }

    private fun processLane(queue: ArrayBlockingQueue<AcceptedAsyncCommand>) {
        val pending = ArrayDeque<PendingAcceptedAsyncCommand>(inFlightPerLane)
        while (started.get()) {
            while (pending.size < inFlightPerLane) {
                val command = if (pending.isEmpty()) {
                    queue.poll(100, TimeUnit.MILLISECONDS)
                } else {
                    queue.poll()
                } ?: break
                pending.add(submit(command))
            }

            val next = pending.poll()
            if (next != null) {
                complete(next)
            }
        }
    }

    private fun submit(command: AcceptedAsyncCommand): PendingAcceptedAsyncCommand {
        recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
            processing.incrementAndGet()
            record.copy(status = CommandLogStatus.PROCESSING, updatedAtEpochMs = System.currentTimeMillis())
        }
        val future = HotPathMetrics.time("acceptedAsync.prepareSubmitOrder") {
            api.prepareSubmitOrderAsync(command.body)
        }
        return PendingAcceptedAsyncCommand(command, future)
    }

    private fun complete(pending: PendingAcceptedAsyncCommand) {
        val command = pending.command
        try {
            val outcome = pending.future.get()
            HotPathMetrics.time("acceptedAsync.persistSubmitOutcome") {
                api.persistSubmitOutcomes(listOf(outcome))
            }
            val payload = api.submitOrderResponse(outcome)
            recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
                processing.decrementAndGet()
                completed.incrementAndGet()
                lastCompletedAtEpochMs.set(System.currentTimeMillis())
                record.copy(
                    status = CommandLogStatus.COMPLETED,
                    responseStatus = 200,
                    responsePayloadJson = payload,
                    lastError = "",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
        } catch (ex: ExecutionException) {
            fail(command, ex.cause ?: ex)
        } catch (ex: Exception) {
            fail(command, ex)
        }
    }

    private fun fail(command: AcceptedAsyncCommand, error: Throwable) {
        val message = error.message ?: error::class.simpleName ?: "unknown"
        recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
            processing.decrementAndGet()
            failed.incrementAndGet()
            lastFailedAtEpochMs.set(System.currentTimeMillis())
            record.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = 503,
                responsePayloadJson = "{}",
                lastError = message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    private fun offer(queue: ArrayBlockingQueue<AcceptedAsyncCommand>, command: AcceptedAsyncCommand): Boolean {
        if (offerTimeoutMs <= 0) {
            return queue.offer(command)
        }
        return queue.offer(command, offerTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun laneFor(instrumentId: String): Int {
        return Math.floorMod(instrumentId.hashCode(), lanes.size)
    }

    private fun idempotencyKey(clientId: String, route: String, idempotencyKey: String): String {
        return "$clientId|$route|$idempotencyKey"
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

private data class AcceptedAsyncCommand(
    val record: AcceptedAsyncCommandRecord,
    val body: String
)

private data class PendingAcceptedAsyncCommand(
    val command: AcceptedAsyncCommand,
    val future: CompletableFuture<PersistableSubmitOutcome>
)

private data class AcceptedAsyncCommandRecord(
    val commandId: String,
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val status: CommandLogStatus,
    val responseStatus: Int,
    val responsePayloadJson: String,
    val lastError: String,
    val updatedAtEpochMs: Long
) {
    fun toStatusView(): CommandStatusView {
        return CommandStatusView(
            commandId = commandId,
            clientId = clientId,
            route = route,
            idempotencyKey = idempotencyKey,
            status = status,
            processingMode = CommandProcessingMode.AcceptedAsync,
            responseStatus = responseStatus,
            responsePayloadJson = responsePayloadJson,
            lastError = lastError
        )
    }
}
