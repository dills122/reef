package com.reef.platform.api

import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.partition.PartitionLaneHash
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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
    val retainedStatuses: Long,
    val retentionMaxRecords: Int,
    val retentionTtlMs: Long,
    val retentionEvicted: Long,
    val terminalStatusMaxRecords: Int,
    val terminalStatusTtlMs: Long,
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
    private val offerWaitMaxConcurrency: Int =
        RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_OFFER_WAIT_MAX_CONCURRENCY", 8, min = 1),
    private val terminalStatusMaxRecords: Int =
        RuntimeEnv.int("EXTERNAL_API_ACCEPTED_ASYNC_TERMINAL_STATUS_MAX_RECORDS", 100_000, min = 0),
    private val terminalStatusTtlMs: Long =
        RuntimeEnv.long("EXTERNAL_API_ACCEPTED_ASYNC_TERMINAL_STATUS_TTL_MS", 900_000L, min = 0L),
    private val clockMillis: () -> Long = System::currentTimeMillis
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

    // Bounds how many calling threads (drawn from the shared, bounded HTTP
    // application thread pool) can ever be parked inside offer()'s
    // runBlocking wait at once. Without this, a lane-capacity backpressure
    // spike with EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS > 0 could park
    // every application thread waiting for lane space, starving unrelated
    // routes on the same server. Callers beyond the limit fail fast as
    // ordinary backpressure instead of queueing for a permit.
    private val offerWaitPermits = Semaphore(offerWaitMaxConcurrency)
    private val recordsByCommandId = ConcurrentHashMap<String, AcceptedAsyncCommandRecord>()
    private val commandIdByIdempotency = ConcurrentHashMap<String, String>()
    private val terminalCommandIds = ConcurrentHashMap.newKeySet<String>()
    private val terminalStatusOrder = ConcurrentLinkedQueue<AcceptedAsyncRetentionEntry>()
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
        val now = clockMillis()
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
        val existingRecord = recordsByCommandId.putIfAbsent(command.commandId, record)
        if (existingRecord != null) {
            duplicates.incrementAndGet()
            return AcceptedAsyncCommandReceipt(
                accepted = false,
                duplicate = true,
                status = existingRecord.toStatusView()
            )
        }
        val idempotencyKeyValue = idempotencyKey(clientId, route, idempotencyKey)
        while (true) {
            val existingCommandId = commandIdByIdempotency.putIfAbsent(idempotencyKeyValue, command.commandId)
            if (existingCommandId == null) {
                break
            }
            val existingStatus = recordsByCommandId[existingCommandId]?.toStatusView()
            if (existingStatus != null) {
                recordsByCommandId.remove(command.commandId, record)
                duplicates.incrementAndGet()
                return AcceptedAsyncCommandReceipt(
                    accepted = false,
                    duplicate = true,
                    status = existingStatus
                )
            }
            commandIdByIdempotency.remove(idempotencyKeyValue, existingCommandId)
        }
        val accepted = HotPathMetrics.time("api.acceptedAsync.enqueue") {
            offer(lane, lanes[lane], AcceptedAsyncCommand(record, command))
        }
        if (!accepted) {
            backpressured.incrementAndGet()
            laneBackpressured.incrementAndGet(lane)
            record.markFailed(
                responseStatus = 429,
                responsePayloadJson = "{}",
                lastError = "accepted-async lane queue is full",
                updatedAtEpochMs = clockMillis()
            )
            recordsByCommandId.remove(command.commandId, record)
            commandIdByIdempotency.remove(idempotencyKeyValue, command.commandId)
            return AcceptedAsyncCommandReceipt(accepted = false, backpressure = true, status = record.toStatusView())
        }

        received.incrementAndGet()
        laneReceived.incrementAndGet(lane)
        lastReceivedAtEpochMs.set(now)
        return AcceptedAsyncCommandReceipt(accepted = true, status = record.toStatusView())
    }

    override fun findCommandStatus(commandId: String): CommandStatusView? {
        evictTerminalStatuses(clockMillis())
        return recordsByCommandId[commandId]?.toStatusView()
    }

    override fun findCommandStatus(clientId: String, route: String, idempotencyKey: String): CommandStatusView? {
        val commandId = commandIdByIdempotency[idempotencyKey(clientId, route, idempotencyKey)] ?: return null
        return findCommandStatus(commandId)
    }

    fun stats(): AcceptedAsyncCommandStats {
        evictTerminalStatuses(clockMillis())
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
        val retained = retainedTerminalStatusRecords.get()
        val evicted = statusRecordsEvicted.get()
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
            retainedStatuses = retained,
            retentionMaxRecords = terminalStatusMaxRecords,
            retentionTtlMs = terminalStatusTtlMs,
            retentionEvicted = evicted,
            terminalStatusMaxRecords = terminalStatusMaxRecords,
            terminalStatusTtlMs = terminalStatusTtlMs,
            retainedTerminalStatusRecords = retained,
            retainedStatusRecords = recordsByCommandId.size.toLong(),
            statusRecordsEvicted = evicted,
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
        recordsByCommandId[command.record.commandId]?.let { record ->
            processing.incrementAndGet()
            laneProcessing.incrementAndGet(lane)
            record.markProcessing(clockMillis())
        }
        val future = HotPathMetrics.time("acceptedAsync.prepareSubmitOrder") {
            api.prepareSubmitOrderAsync(command.command)
        }
        val pending = PendingAcceptedAsyncCommand(
            command = command,
            future = future,
            submittedAtEpochMs = clockMillis()
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
            recordsByCommandId[command.record.commandId]?.let { record ->
                processing.decrementAndGet()
                laneProcessing.decrementAndGet(lane)
                completed.incrementAndGet()
                laneCompleted.incrementAndGet(lane)
                val now = clockMillis()
                lastCompletedAtEpochMs.set(now)
                record.markCompleted(
                    responseStatus = 200,
                    responsePayloadJson = payload,
                    updatedAtEpochMs = now
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
        recordsByCommandId[command.record.commandId]?.let { record ->
            if (record.status == CommandLogStatus.PROCESSING) {
                processing.decrementAndGet()
                laneProcessing.decrementAndGet(lane)
            }
            failed.incrementAndGet()
            laneFailed.incrementAndGet(lane)
            val now = clockMillis()
            lastFailedAtEpochMs.set(now)
            record.markFailed(
                responseStatus = 503,
                responsePayloadJson = "{}",
                lastError = message,
                updatedAtEpochMs = now
            )
        }
        markTerminalStatus(command.record.commandId)
    }

    private fun offer(lane: Int, queue: Channel<AcceptedAsyncCommand>, command: AcceptedAsyncCommand): Boolean {
        val accepted = if (offerTimeoutMs <= 0) {
            queue.trySend(command).isSuccess
        } else if (!offerWaitPermits.tryAcquire()) {
            // Bulkhead full: rather than queue this calling thread up behind
            // other in-progress waits (which would let a backpressure spike
            // exhaust the shared application thread pool one blocked thread
            // at a time), fail fast as ordinary backpressure.
            false
        } else {
            try {
                runBlocking {
                    withTimeoutOrNull(offerTimeoutMs) {
                        queue.send(command)
                        true
                    } ?: false
                }
            } finally {
                offerWaitPermits.release()
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
        val record = recordsByCommandId[commandId] ?: return
        if (!record.status.isTerminal()) return
        if (terminalCommandIds.add(commandId)) {
            retainedTerminalStatusRecords.incrementAndGet()
            terminalStatusOrder.add(AcceptedAsyncRetentionEntry(commandId, record.updatedAtEpochMs))
        }
        evictTerminalStatuses(clockMillis())
    }

    private fun evictTerminalStatuses(now: Long) {
        while (true) {
            val next = terminalStatusOrder.peek() ?: return
            val overLimit = retainedTerminalStatusRecords.get() > terminalStatusMaxRecords
            val expired = terminalStatusTtlMs > 0 && now - next.updatedAtEpochMs >= terminalStatusTtlMs
            if (!overLimit && !expired) return
            val polled = terminalStatusOrder.poll() ?: return
            val record = recordsByCommandId[polled.commandId]
            if (record != null &&
                record.updatedAtEpochMs == polled.updatedAtEpochMs &&
                record.status.isTerminal()
            ) {
                if (recordsByCommandId.remove(polled.commandId, record)) {
                    commandIdByIdempotency.remove(
                        idempotencyKey(record.clientId, record.route, record.idempotencyKey),
                        record.commandId
                    )
                    if (terminalCommandIds.remove(polled.commandId)) {
                        retainedTerminalStatusRecords.decrementAndGet()
                    }
                    statusRecordsEvicted.incrementAndGet()
                }
            } else if (terminalCommandIds.remove(polled.commandId)) {
                retainedTerminalStatusRecords.decrementAndGet()
            }
        }
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }

    private fun ageMs(epochMs: Long): Long {
        if (epochMs <= 0) return 0L
        return (clockMillis() - epochMs).coerceAtLeast(0L)
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

private data class AcceptedAsyncRetentionEntry(
    val commandId: String,
    val updatedAtEpochMs: Long
)

private class AcceptedAsyncCommandRecord(
    val commandId: String,
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val lane: Int,
    status: CommandLogStatus,
    responseStatus: Int,
    responsePayloadJson: String,
    lastError: String,
    updatedAtEpochMs: Long
) {
    @Volatile
    var status: CommandLogStatus = status
        private set

    @Volatile
    var responseStatus: Int = responseStatus
        private set

    @Volatile
    var responsePayloadJson: String = responsePayloadJson
        private set

    @Volatile
    var lastError: String = lastError
        private set

    @Volatile
    var updatedAtEpochMs: Long = updatedAtEpochMs
        private set

    fun markProcessing(updatedAtEpochMs: Long) {
        status = CommandLogStatus.PROCESSING
        this.updatedAtEpochMs = updatedAtEpochMs
    }

    fun markCompleted(responseStatus: Int, responsePayloadJson: String, updatedAtEpochMs: Long) {
        this.responseStatus = responseStatus
        this.responsePayloadJson = responsePayloadJson
        lastError = ""
        this.updatedAtEpochMs = updatedAtEpochMs
        status = CommandLogStatus.COMPLETED
    }

    fun markFailed(
        responseStatus: Int,
        responsePayloadJson: String,
        lastError: String,
        updatedAtEpochMs: Long
    ) {
        this.responseStatus = responseStatus
        this.responsePayloadJson = responsePayloadJson
        this.lastError = lastError
        this.updatedAtEpochMs = updatedAtEpochMs
        status = CommandLogStatus.FAILED
    }

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
