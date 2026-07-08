package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.partition.PartitionLaneHash
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import com.reef.platform.domain.SubmitOrderCommand
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class AcceptedAsyncCommandReceipt(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val backpressure: Boolean = false,
    val status: CommandStatusView? = null
)

data class AcceptedAsyncLaneStats(
    val lane: Int,
    val queued: Long,
    val inFlight: Long,
    val completedWaiting: Long,
    val oldestInFlightAgeMs: Long,
    val windowSaturated: Boolean,
    val received: Long,
    val backpressured: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long
)

data class AcceptedAsyncCommandStats(
    val enabled: Boolean,
    val laneCount: Int,
    val activeLaneCount: Int,
    val queueCapacityPerLane: Int,
    val inFlightPerLane: Int,
    val queued: Long,
    val maxLaneDepth: Long,
    val inFlight: Long,
    val completedWaiting: Long,
    val maxOldestInFlightAgeMs: Long,
    val saturatedLaneCount: Int,
    val received: Long,
    val duplicates: Long,
    val backpressured: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long,
    val terminalStatusMaxRecords: Int,
    val retainedTerminalStatusRecords: Long,
    val retainedStatusRecords: Long,
    val statusRecordsEvicted: Long,
    val lastReceivedAt: String,
    val lastCompletedAt: String,
    val lastFailedAt: String,
    val lanes: List<AcceptedAsyncLaneStats>
)

class AcceptedAsyncCommandIntake(
    private val api: PlatformApi,
    laneCount: Int = RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_LANES", 16, min = 1),
    private val queueCapacityPerLane: Int = RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_QUEUE_CAPACITY", 100_000, min = 1),
    private val inFlightPerLane: Int = RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE", 32, min = 1),
    private val offerTimeoutMs: Long = RuntimeEnv.long("EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS", 0L, min = 0L),
    private val terminalStatusMaxRecords: Int =
        RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_TERMINAL_STATUS_MAX_RECORDS", 100_000, min = 0)
) : CommandStatusLookup {
    private val lanes: Array<Channel<AcceptedAsyncCommand>> =
        Array(laneCount) { Channel(queueCapacityPerLane) }
    private val laneQueued = AtomicLongArray(laneCount)
    private val laneReceived = AtomicLongArray(laneCount)
    private val laneInFlight = AtomicLongArray(laneCount)
    private val laneCompletedWaiting = AtomicLongArray(laneCount)
    private val laneOldestInFlightAtEpochMs = AtomicLongArray(laneCount)
    private val laneWindowSaturated = AtomicLongArray(laneCount)
    private val laneBackpressured = AtomicLongArray(laneCount)
    private val laneProcessing = AtomicLongArray(laneCount)
    private val laneCompleted = AtomicLongArray(laneCount)
    private val laneFailed = AtomicLongArray(laneCount)
    private val dispatcher = Executors.newFixedThreadPool(laneCount) { runnable ->
        Thread(runnable, "reef-accepted-async-lane-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val recordsByCommandId = ConcurrentHashMap<String, AcceptedAsyncCommandRecord>()
    private val commandIdByIdempotency = ConcurrentHashMap<String, String>()
    private val terminalCommandIds = ConcurrentHashMap.newKeySet<String>()
    private val terminalStatusOrder = ConcurrentLinkedQueue<String>()
    private val started = AtomicBoolean(false)
    private val received = AtomicLong(0)
    private val duplicates = AtomicLong(0)
    private val backpressured = AtomicLong(0)
    private val processing = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val retainedTerminalStatusRecords = AtomicLong(0)
    private val statusRecordsEvicted = AtomicLong(0)
    private val lastReceivedAtEpochMs = AtomicLong(0)
    private val lastCompletedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        lanes.indices.forEach { lane ->
            scope.launch(CoroutineName("reef-accepted-async-lane-$lane")) {
                processLane(lane, lanes[lane])
            }
        }
    }

    fun stop() {
        started.set(false)
        scope.cancel()
        dispatcher.close()
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
        val lane = laneFor(command.instrumentId)
        val record = AcceptedAsyncCommandRecord(
            commandId = command.commandId,
            clientId = clientId,
            route = route,
            idempotencyKey = idempotencyKey,
            lane = lane,
            status = CommandLogStatus.RECEIVED,
            responseStatus = 0,
            responsePayloadJson = "",
            lastError = "",
            updatedAtEpochMs = now
        )
        recordsByCommandId[command.commandId] = record
        val accepted = HotPathMetrics.time("api.acceptedAsync.enqueue") {
            offer(lane, lanes[lane], AcceptedAsyncCommand(record, command))
        }
        if (!accepted) {
            backpressured.incrementAndGet()
            laneBackpressured.incrementAndGet(lane)
            val failed = record.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = 429,
                lastError = "accepted-async lane queue is full",
                updatedAtEpochMs = System.currentTimeMillis()
            )
            recordsByCommandId[command.commandId] = failed
            markTerminalStatus(command.commandId)
            commandIdByIdempotency.remove(idempotencyKeyValue, command.commandId)
            return AcceptedAsyncCommandReceipt(accepted = false, backpressure = true, status = failed.toStatusView())
        }

        received.incrementAndGet()
        laneReceived.incrementAndGet(lane)
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
        val laneStats = lanes.indices.map { lane ->
            val oldestInFlightAt = laneOldestInFlightAtEpochMs.get(lane)
            AcceptedAsyncLaneStats(
                lane = lane,
                queued = laneQueued.get(lane),
                inFlight = laneInFlight.get(lane),
                completedWaiting = laneCompletedWaiting.get(lane),
                oldestInFlightAgeMs = ageMs(oldestInFlightAt),
                windowSaturated = laneWindowSaturated.get(lane) > 0,
                received = laneReceived.get(lane),
                backpressured = laneBackpressured.get(lane),
                processing = laneProcessing.get(lane),
                completed = laneCompleted.get(lane),
                failed = laneFailed.get(lane)
            )
        }
        val depths = laneStats.map { it.queued }
        val oldestInFlightAges = laneStats.map { it.oldestInFlightAgeMs }
        return AcceptedAsyncCommandStats(
            enabled = started.get(),
            laneCount = lanes.size,
            activeLaneCount = laneStats.count {
                it.received > 0 || it.processing > 0 || it.completed > 0 || it.failed > 0 || it.backpressured > 0
            },
            queueCapacityPerLane = queueCapacityPerLane,
            inFlightPerLane = inFlightPerLane,
            queued = depths.sum(),
            maxLaneDepth = depths.maxOrNull() ?: 0,
            inFlight = laneStats.sumOf { it.inFlight },
            completedWaiting = laneStats.sumOf { it.completedWaiting },
            maxOldestInFlightAgeMs = oldestInFlightAges.maxOrNull() ?: 0,
            saturatedLaneCount = laneStats.count { it.windowSaturated },
            received = received.get(),
            duplicates = duplicates.get(),
            backpressured = backpressured.get(),
            processing = processing.get(),
            completed = completed.get(),
            failed = failed.get(),
            terminalStatusMaxRecords = terminalStatusMaxRecords,
            retainedTerminalStatusRecords = retainedTerminalStatusRecords.get(),
            retainedStatusRecords = recordsByCommandId.size.toLong(),
            statusRecordsEvicted = statusRecordsEvicted.get(),
            lastReceivedAt = instantString(lastReceivedAtEpochMs.get()),
            lastCompletedAt = instantString(lastCompletedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lanes = laneStats
        )
    }

    private suspend fun processLane(lane: Int, queue: Channel<AcceptedAsyncCommand>) {
        val pending = ArrayDeque<PendingAcceptedAsyncCommand>(inFlightPerLane)
        laneLoop@ while (started.get() && scope.isActive) {
            while (pending.size < inFlightPerLane) {
                val command = receiveNext(queue, pending.isEmpty())
                    ?: if (pending.isEmpty()) break@laneLoop else break
                laneQueued.decrementAndGet(lane)
                try {
                    pending.addLast(submit(lane, command))
                    recordLanePipeline(lane, pending)
                } catch (ex: Exception) {
                    fail(lane, command, ex)
                }
            }
            val next = pending.pollFirst() ?: continue
            complete(lane, next)
            recordLanePipeline(lane, pending)
        }
    }

    private suspend fun receiveNext(queue: Channel<AcceptedAsyncCommand>, waitForCommand: Boolean): AcceptedAsyncCommand? {
        if (!waitForCommand) return queue.tryReceive().getOrNull()
        return queue.receiveCatching().getOrNull()
    }

    private fun submit(lane: Int, command: AcceptedAsyncCommand): PendingAcceptedAsyncCommand {
        recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
            processing.incrementAndGet()
            laneProcessing.incrementAndGet(lane)
            record.copy(status = CommandLogStatus.PROCESSING, updatedAtEpochMs = System.currentTimeMillis())
        }
        val future = HotPathMetrics.time("acceptedAsync.prepareSubmitOrder") {
            api.prepareSubmitOrderAsync(command.command)
        }
        val pending = PendingAcceptedAsyncCommand(
            command = command,
            future = future,
            submittedAtEpochMs = System.currentTimeMillis()
        )
        future.whenComplete { _, _ ->
            if (pending.completionRecorded.compareAndSet(false, true)) {
                laneCompletedWaiting.incrementAndGet(lane)
            }
        }
        return pending
    }

    private suspend fun complete(lane: Int, pending: PendingAcceptedAsyncCommand) {
        val command = pending.command
        try {
            val outcome = pending.future.awaitResult()
            HotPathMetrics.time("acceptedAsync.persistSubmitOutcome") {
                api.persistSubmitOutcomes(listOf(outcome))
            }
            val payload = api.submitOrderResponse(outcome)
            recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
                processing.decrementAndGet()
                laneProcessing.decrementAndGet(lane)
                completed.incrementAndGet()
                laneCompleted.incrementAndGet(lane)
                lastCompletedAtEpochMs.set(System.currentTimeMillis())
                record.copy(
                    status = CommandLogStatus.COMPLETED,
                    responseStatus = 200,
                    responsePayloadJson = payload,
                    lastError = "",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            markTerminalStatus(command.record.commandId)
        } catch (ex: ExecutionException) {
            fail(lane, command, ex.cause ?: ex)
        } catch (ex: Exception) {
            fail(lane, command, ex)
        } finally {
            if (pending.completionRecorded.compareAndSet(true, false)) {
                laneCompletedWaiting.decrementAndGet(lane)
            }
        }
    }

    private fun fail(lane: Int, command: AcceptedAsyncCommand, error: Throwable) {
        val message = error.message ?: error::class.simpleName ?: "unknown"
        recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
            if (record.status == CommandLogStatus.PROCESSING) {
                processing.decrementAndGet()
                laneProcessing.decrementAndGet(lane)
            }
            failed.incrementAndGet()
            laneFailed.incrementAndGet(lane)
            lastFailedAtEpochMs.set(System.currentTimeMillis())
            record.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = 503,
                responsePayloadJson = "{}",
                lastError = message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
        markTerminalStatus(command.record.commandId)
    }

    private fun offer(lane: Int, queue: Channel<AcceptedAsyncCommand>, command: AcceptedAsyncCommand): Boolean {
        val accepted = if (offerTimeoutMs <= 0) {
            queue.trySend(command).isSuccess
        } else {
            runBlocking {
                withTimeoutOrNull(offerTimeoutMs) {
                    queue.send(command)
                    true
                } ?: false
            }
        }
        if (accepted) {
            laneQueued.incrementAndGet(lane)
        }
        return accepted
    }

    private fun laneFor(instrumentId: String): Int {
        return PartitionLaneHash.laneFor(instrumentId, lanes.size)
    }

    private fun idempotencyKey(clientId: String, route: String, idempotencyKey: String): String {
        return "$clientId|$route|$idempotencyKey"
    }

    private fun recordLanePipeline(lane: Int, pending: ArrayDeque<PendingAcceptedAsyncCommand>) {
        laneInFlight.set(lane, pending.size.toLong())
        laneWindowSaturated.set(lane, if (pending.size >= inFlightPerLane) 1 else 0)
        laneOldestInFlightAtEpochMs.set(lane, pending.peekFirst()?.submittedAtEpochMs ?: 0L)
    }

    private fun markTerminalStatus(commandId: String) {
        if (terminalStatusMaxRecords <= 0) return
        if (terminalCommandIds.add(commandId)) {
            retainedTerminalStatusRecords.incrementAndGet()
            terminalStatusOrder.add(commandId)
        }
        trimTerminalStatuses()
    }

    private fun trimTerminalStatuses() {
        while (retainedTerminalStatusRecords.get() > terminalStatusMaxRecords) {
            val commandId = terminalStatusOrder.poll() ?: return
            val record = recordsByCommandId[commandId] ?: continue
            if (!record.status.isTerminal()) continue
            if (recordsByCommandId.remove(commandId, record)) {
                val idempotencyKey = idempotencyKey(record.clientId, record.route, record.idempotencyKey)
                commandIdByIdempotency.remove(idempotencyKey, commandId)
                if (terminalCommandIds.remove(commandId)) {
                    retainedTerminalStatusRecords.decrementAndGet()
                }
                statusRecordsEvicted.incrementAndGet()
            }
        }
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }

    private fun ageMs(epochMs: Long): Long {
        if (epochMs <= 0) return 0L
        return (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
    }
}

private fun CommandLogStatus.isTerminal(): Boolean {
    return this == CommandLogStatus.COMPLETED || this == CommandLogStatus.FAILED
}

private suspend fun <T> CompletableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

private data class AcceptedAsyncCommand(
    val record: AcceptedAsyncCommandRecord,
    val command: SubmitOrderCommand
)

private data class PendingAcceptedAsyncCommand(
    val command: AcceptedAsyncCommand,
    val future: CompletableFuture<PersistableSubmitOutcome>,
    val submittedAtEpochMs: Long,
    val completionRecorded: AtomicBoolean = AtomicBoolean(false)
)

private data class AcceptedAsyncCommandRecord(
    val commandId: String,
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val lane: Int,
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
