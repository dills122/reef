package com.reef.platform.api

import java.util.concurrent.ConcurrentHashMap

class InMemoryCommandLogStore : CommandLogStore {
    private val byCommandId = ConcurrentHashMap<String, CommandLogRecord>()
    private val idempotencyToCommandId = ConcurrentHashMap<String, String>()

    override fun append(record: CommandLogRecord): CommandLogAppendResult {
        val idempotencyKey = idempotencyKey(record.clientId, record.route, record.idempotencyKey)
        val existingByIdempotency = idempotencyToCommandId[idempotencyKey]?.let { byCommandId[it] }
        if (existingByIdempotency != null) {
            return CommandLogAppendResult(appended = false, record = existingByIdempotency)
        }

        val existingByCommandId = byCommandId.putIfAbsent(record.commandId, record)
        if (existingByCommandId != null) {
            return CommandLogAppendResult(appended = false, record = existingByCommandId)
        }

        val previousCommandId = idempotencyToCommandId.putIfAbsent(idempotencyKey, record.commandId)
        if (previousCommandId != null) {
            byCommandId.remove(record.commandId, record)
            return CommandLogAppendResult(appended = false, record = byCommandId.getValue(previousCommandId))
        }

        return CommandLogAppendResult(appended = true, record = record)
    }

    override fun findByCommandId(commandId: String): CommandLogRecord? = byCommandId[commandId]

    override fun findByIdempotency(clientId: String, route: String, idempotencyKey: String): CommandLogRecord? {
        return idempotencyToCommandId[idempotencyKey(clientId, route, idempotencyKey)]?.let { byCommandId[it] }
    }

    override fun findByStatus(status: CommandLogStatus, limit: Int): List<CommandLogRecord> {
        if (limit <= 0) return emptyList()
        return byCommandId.values
            .asSequence()
            .filter { it.status == status }
            .sortedBy { it.receivedAt }
            .take(limit)
            .toList()
    }

    override fun claimReceived(limit: Int): List<CommandLogRecord> {
        if (limit <= 0) return emptyList()
        return synchronized(this) {
            byCommandId.values
                .asSequence()
                .filter { it.status == CommandLogStatus.RECEIVED }
                .sortedBy { it.receivedAt }
                .take(limit)
                .map { existing ->
                    val claimed = existing.copy(
                        status = CommandLogStatus.PROCESSING,
                        attemptCount = existing.attemptCount + 1,
                        lastError = ""
                    )
                    byCommandId[existing.commandId] = claimed
                    claimed
                }
                .toList()
        }
    }

    override fun statusCounts(): Map<CommandLogStatus, Long> {
        val counts = CommandLogStatus.values().associateWith { 0L }.toMutableMap()
        byCommandId.values.forEach { record ->
            counts[record.status] = counts.getValue(record.status) + 1L
        }
        return counts
    }

    override fun accountingSnapshot(runId: String): CommandLogAccountingSnapshot {
        val records = byCommandId.values.filter { runId.isBlank() || it.runId == runId }
        val counts = CommandLogStatus.values().associateWith { 0L }.toMutableMap()
        records.forEach { record ->
            counts[record.status] = counts.getValue(record.status) + 1L
        }
        val received = counts.getValue(CommandLogStatus.RECEIVED)
        val processing = counts.getValue(CommandLogStatus.PROCESSING)
        val completed = counts.getValue(CommandLogStatus.COMPLETED)
        val failed = counts.getValue(CommandLogStatus.FAILED)
        val active = received + processing
        val terminal = completed + failed
        val accepted = records.size.toLong()
        return CommandLogAccountingSnapshot(
            runId = runId,
            accepted = accepted,
            received = received,
            processing = processing,
            completed = completed,
            failed = failed,
            active = active,
            terminal = terminal,
            accountingGap = accepted - active - terminal,
            staleProcessing = 0L
        )
    }

    override fun markProcessing(commandId: String) {
        byCommandId.computeIfPresent(commandId) { _, existing ->
            existing.copy(
                status = CommandLogStatus.PROCESSING,
                attemptCount = existing.attemptCount + 1,
                lastError = ""
            )
        }
    }

    override fun markCompleted(commandId: String, responseStatus: Int, responsePayloadJson: String) {
        byCommandId.computeIfPresent(commandId) { _, existing ->
            existing.copy(
                status = CommandLogStatus.COMPLETED,
                responseStatus = responseStatus,
                responsePayloadJson = responsePayloadJson,
                lastError = ""
            )
        }
    }

    override fun markFailed(commandId: String, responseStatus: Int, errorMessage: String) {
        byCommandId.computeIfPresent(commandId) { _, existing ->
            existing.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = responseStatus,
                lastError = errorMessage
            )
        }
    }

    override fun markTerminal(updates: List<CommandTerminalUpdate>) {
        updates.forEach { update ->
            when (update.status) {
                CommandLogStatus.COMPLETED -> markCompleted(update.commandId, update.responseStatus, update.responsePayloadJson)
                CommandLogStatus.FAILED -> markFailed(update.commandId, update.responseStatus, update.errorMessage)
                CommandLogStatus.RECEIVED,
                CommandLogStatus.PROCESSING -> error("terminal update status must be COMPLETED or FAILED")
            }
        }
    }

    private fun idempotencyKey(clientId: String, route: String, idempotencyKey: String): String {
        return "$clientId|$route|$idempotencyKey"
    }
}
