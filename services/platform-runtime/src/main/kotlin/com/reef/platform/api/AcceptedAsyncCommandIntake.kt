package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.partition.PartitionLaneHash
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
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
import kotlinx.coroutines.coroutineScope
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
    private val statusRetentionMaxRecords: Int = RuntimeEnv.int(
        "EXTERNAL_API_ACCEPTED_ASYNC_STATUS_RETENTION_MAX_RECORDS",
        1_000_000,
        min = 1
    ),
    private val statusRetentionTtlMs: Long = RuntimeEnv.long(
        "EXTERNAL_API_ACCEPTED_ASYNC_STATUS_RETENTION_TTL_MS",
        900_000L,
        min = 0L
    ),
    private val clockMillis: () -> Long = System::currentTimeMillis
) : CommandStatusLookup {
    private val lanes: Array<Channel<AcceptedAsyncCommand>> =
        Array(laneCount) { Channel(queueCapacityPerLane) }
    private val laneQueued = AtomicLongArray(laneCount)
    private val laneReceived = AtomicLongArray(laneCount)
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
    private val retainedTerminalStatuses = ConcurrentLinkedQueue<AcceptedAsyncRetentionEntry>()
    private val started = AtomicBoolean(false)
    private val received = AtomicLong(0)
    private val duplicates = AtomicLong(0)
    private val backpressured = AtomicLong(0)
    private val processing = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val retainedStatuses = AtomicLong(0)
    private val retentionEvicted = AtomicLong(0)
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
            offer(lane, lanes[lane], AcceptedAsyncCommand(record, body))
        }
        if (!accepted) {
            backpressured.incrementAndGet()
            laneBackpressured.incrementAndGet(lane)
            val failed = record.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = 429,
                lastError = "accepted-async lane queue is full",
                updatedAtEpochMs = clockMillis()
            )
            recordsByCommandId.remove(command.commandId, record)
            commandIdByIdempotency.remove(idempotencyKeyValue, command.commandId)
            return AcceptedAsyncCommandReceipt(accepted = false, backpressure = true, status = failed.toStatusView())
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
            AcceptedAsyncLaneStats(
                lane = lane,
                queued = laneQueued.get(lane),
                received = laneReceived.get(lane),
                backpressured = laneBackpressured.get(lane),
                processing = laneProcessing.get(lane),
                completed = laneCompleted.get(lane),
                failed = laneFailed.get(lane)
            )
        }
        val depths = laneStats.map { it.queued }
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
            received = received.get(),
            duplicates = duplicates.get(),
            backpressured = backpressured.get(),
            processing = processing.get(),
            completed = completed.get(),
            failed = failed.get(),
            retainedStatuses = retainedStatuses.get(),
            retentionMaxRecords = statusRetentionMaxRecords,
            retentionTtlMs = statusRetentionTtlMs,
            retentionEvicted = retentionEvicted.get(),
            lastReceivedAt = instantString(lastReceivedAtEpochMs.get()),
            lastCompletedAt = instantString(lastCompletedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lanes = laneStats
        )
    }

    private suspend fun processLane(lane: Int, queue: Channel<AcceptedAsyncCommand>) {
        coroutineScope {
            repeat(inFlightPerLane) { slot ->
                launch(CoroutineName("reef-accepted-async-lane-$lane-slot-$slot")) {
                    for (command in queue) {
                        if (!started.get() || !scope.isActive) break
                        laneQueued.decrementAndGet(lane)
                        val pending = try {
                            submit(lane, command)
                        } catch (ex: Exception) {
                            fail(lane, command, ex)
                            continue
                        }
                        complete(lane, pending)
                    }
                }
            }
        }
    }

    private fun submit(lane: Int, command: AcceptedAsyncCommand): PendingAcceptedAsyncCommand {
        recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
            processing.incrementAndGet()
            laneProcessing.incrementAndGet(lane)
            record.copy(status = CommandLogStatus.PROCESSING, updatedAtEpochMs = clockMillis())
        }
        val future = HotPathMetrics.time("acceptedAsync.prepareSubmitOrder") {
            api.prepareSubmitOrderAsync(command.body)
        }
        return PendingAcceptedAsyncCommand(command, future)
    }

    private suspend fun complete(lane: Int, pending: PendingAcceptedAsyncCommand) {
        val command = pending.command
        try {
            val outcome = pending.future.awaitResult()
            HotPathMetrics.time("acceptedAsync.persistSubmitOutcome") {
                api.persistSubmitOutcomes(listOf(outcome))
            }
            val payload = api.submitOrderResponse(outcome)
            val completedRecord = recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
                processing.decrementAndGet()
                laneProcessing.decrementAndGet(lane)
                completed.incrementAndGet()
                laneCompleted.incrementAndGet(lane)
                lastCompletedAtEpochMs.set(clockMillis())
                record.copy(
                    status = CommandLogStatus.COMPLETED,
                    responseStatus = 200,
                    responsePayloadJson = payload,
                    lastError = "",
                    updatedAtEpochMs = clockMillis()
                )
            }
            if (completedRecord != null) {
                retainTerminalStatus(completedRecord)
            }
        } catch (ex: ExecutionException) {
            fail(lane, command, ex.cause ?: ex)
        } catch (ex: Exception) {
            fail(lane, command, ex)
        }
    }

    private fun fail(lane: Int, command: AcceptedAsyncCommand, error: Throwable) {
        val message = error.message ?: error::class.simpleName ?: "unknown"
        val failedRecord = recordsByCommandId.computeIfPresent(command.record.commandId) { _, record ->
            if (record.status == CommandLogStatus.PROCESSING) {
                processing.decrementAndGet()
                laneProcessing.decrementAndGet(lane)
            }
            failed.incrementAndGet()
            laneFailed.incrementAndGet(lane)
            lastFailedAtEpochMs.set(clockMillis())
            record.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = 503,
                responsePayloadJson = "{}",
                lastError = message,
                updatedAtEpochMs = clockMillis()
            )
        }
        if (failedRecord != null) {
            retainTerminalStatus(failedRecord)
        }
    }

    private fun retainTerminalStatus(record: AcceptedAsyncCommandRecord) {
        retainedTerminalStatuses.add(AcceptedAsyncRetentionEntry(record.commandId, record.updatedAtEpochMs))
        retainedStatuses.incrementAndGet()
        evictTerminalStatuses(clockMillis())
    }

    private fun evictTerminalStatuses(now: Long) {
        while (true) {
            val next = retainedTerminalStatuses.peek() ?: return
            val overLimit = retainedStatuses.get() > statusRetentionMaxRecords
            val expired = statusRetentionTtlMs > 0 && now - next.updatedAtEpochMs >= statusRetentionTtlMs
            if (!overLimit && !expired) return
            val polled = retainedTerminalStatuses.poll() ?: return
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
                    retainedStatuses.decrementAndGet()
                    retentionEvicted.incrementAndGet()
                }
            } else {
                retainedStatuses.decrementAndGet()
            }
        }
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

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
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
    val body: String
)

private data class PendingAcceptedAsyncCommand(
    val command: AcceptedAsyncCommand,
    val future: CompletableFuture<PersistableSubmitOutcome>
)

private data class AcceptedAsyncRetentionEntry(
    val commandId: String,
    val updatedAtEpochMs: Long
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

private fun CommandLogStatus.isTerminal(): Boolean =
    this == CommandLogStatus.COMPLETED || this == CommandLogStatus.FAILED
