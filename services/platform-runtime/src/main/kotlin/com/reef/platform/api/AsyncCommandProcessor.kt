package com.reef.platform.api

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AsyncCommandProcessor(
    private val queue: CapturedCommandQueue,
    private val api: PlatformApi,
    private val batchSize: Int = 100,
    private val pollIntervalMs: Long = 25L,
    private val workerName: String = "reef-async-command-processor"
) {
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = workerName, isDaemon = true) {
            while (running.get()) {
                val processed = processOnce()
                if (processed == 0) {
                    AsyncCommandProcessorMetrics.recordEmptyPoll()
                    Thread.sleep(pollIntervalMs)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }

    fun processOnce(): Int {
        val commands = HotPathMetrics.time("async.claim") {
            queue.claimReceivedCommands(batchSize)
        }
        AsyncCommandProcessorMetrics.recordClaimed(commands.size)
        val terminalUpdates = commands.map { command ->
            process(command)
        }
        if (terminalUpdates.isNotEmpty()) {
            HotPathMetrics.time("async.completeBatch") {
                try {
                    queue.markCommandTerminal(terminalUpdates)
                } catch (_: Exception) {
                    terminalUpdates.forEach { update -> markTerminalIndividually(update) }
                }
            }
            terminalUpdates.forEach { update ->
                when (update.status) {
                    CommandLogStatus.COMPLETED -> AsyncCommandProcessorMetrics.recordCompleted()
                    CommandLogStatus.FAILED -> AsyncCommandProcessorMetrics.recordFailed()
                    CommandLogStatus.RECEIVED,
                    CommandLogStatus.PROCESSING -> Unit
                }
            }
        }
        return commands.size
    }

    private fun process(command: CommandLogRecord): CommandTerminalUpdate {
        return try {
            val payload = HotPathMetrics.time("async.operation") {
                execute(command)
            }
            CommandTerminalUpdate(
                commandId = command.commandId,
                status = CommandLogStatus.COMPLETED,
                responseStatus = 200,
                responsePayloadJson = payload
            )
        } catch (ex: Exception) {
            val message = ex.message ?: ex::class.simpleName ?: "unknown"
            CommandTerminalUpdate(
                commandId = command.commandId,
                status = CommandLogStatus.FAILED,
                responseStatus = 503,
                responsePayloadJson = "{}",
                errorMessage = message
            )
        }
    }

    private fun markTerminalIndividually(update: CommandTerminalUpdate) {
        when (update.status) {
            CommandLogStatus.COMPLETED -> queue.markCommandCompleted(
                update.commandId,
                update.responseStatus,
                update.responsePayloadJson
            )
            CommandLogStatus.FAILED -> queue.markCommandFailed(
                update.commandId,
                update.responseStatus,
                update.errorMessage
            )
            CommandLogStatus.RECEIVED,
            CommandLogStatus.PROCESSING -> Unit
        }
    }

    private fun execute(command: CommandLogRecord): String {
        return when (command.route) {
            "/api/v1/orders/submit" -> api.submitOrder(command.payloadJson)
            "/api/v1/orders/cancel" -> api.cancelOrder(command.payloadJson)
            "/api/v1/orders/modify" -> api.modifyOrder(command.payloadJson)
            else -> JsonCodec.writeObject(
                "rejected" to mapOf(
                    "code" to "UNSUPPORTED_COMMAND_ROUTE",
                    "reason" to "unsupported async command route: ${command.route}"
                )
            )
        }
    }
}

data class AsyncCommandProcessorStats(
    val claimed: Long,
    val completed: Long,
    val failed: Long,
    val emptyPolls: Long,
    val lastClaimedAt: String,
    val lastCompletedAt: String,
    val lastFailedAt: String
)

object AsyncCommandProcessorMetrics {
    private val claimed = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val emptyPolls = AtomicLong(0)
    private val lastClaimedAtEpochMs = AtomicLong(0)
    private val lastCompletedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)

    fun recordClaimed(count: Int) {
        if (count <= 0) return
        claimed.addAndGet(count.toLong())
        lastClaimedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordCompleted() {
        completed.incrementAndGet()
        lastCompletedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed() {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordEmptyPoll() {
        emptyPolls.incrementAndGet()
    }

    fun snapshot(): AsyncCommandProcessorStats {
        return AsyncCommandProcessorStats(
            claimed = claimed.get(),
            completed = completed.get(),
            failed = failed.get(),
            emptyPolls = emptyPolls.get(),
            lastClaimedAt = instantString(lastClaimedAtEpochMs.get()),
            lastCompletedAt = instantString(lastCompletedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get())
        )
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}
