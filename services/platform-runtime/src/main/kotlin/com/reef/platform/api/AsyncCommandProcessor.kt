package com.reef.platform.api

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AsyncCommandProcessor(
    private val queue: CapturedCommandQueue,
    private val api: PlatformApi,
    private val batchSize: Int = 100,
    private val pollIntervalMs: Long = 25L
) {
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "reef-async-command-processor", isDaemon = true) {
            while (running.get()) {
                val processed = processOnce()
                if (processed == 0) {
                    Thread.sleep(pollIntervalMs)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }

    fun processOnce(): Int {
        val commands = queue.pendingCommands(batchSize)
        commands.forEach { command ->
            process(command)
        }
        return commands.size
    }

    private fun process(command: CommandLogRecord) {
        try {
            queue.markCommandProcessing(command.commandId)
            val payload = HotPathMetrics.time("async.operation") {
                execute(command)
            }
            queue.markCommandCompleted(command.commandId, 200, payload)
        } catch (ex: Exception) {
            val message = ex.message ?: ex::class.simpleName ?: "unknown"
            queue.markCommandFailed(command.commandId, 503, message)
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
