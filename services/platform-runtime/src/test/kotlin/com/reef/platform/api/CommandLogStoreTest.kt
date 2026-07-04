package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandLogStoreTest {
    @Test
    fun inMemoryStoreAppendsAndFindsCommand() {
        val store = InMemoryCommandLogStore()
        val record = commandLogRecord(commandId = "cmd-1", idempotencyKey = "idem-1")

        val result = store.append(record)

        assertTrue(result.appended)
        assertEquals(record, result.record)
        assertEquals(record, store.findByCommandId("cmd-1"))
        assertEquals(record, store.findByIdempotency("client-1", "/api/v1/orders/submit", "idem-1"))
    }

    @Test
    fun inMemoryStoreReturnsExistingCommandForDuplicateIdempotencyKey() {
        val store = InMemoryCommandLogStore()
        val original = commandLogRecord(commandId = "cmd-1", idempotencyKey = "idem-1")
        val duplicate = commandLogRecord(commandId = "cmd-2", idempotencyKey = "idem-1")

        assertTrue(store.append(original).appended)
        val result = store.append(duplicate)

        assertFalse(result.appended)
        assertEquals(original, result.record)
        assertEquals(original, store.findByIdempotency("client-1", "/api/v1/orders/submit", "idem-1"))
    }

    @Test
    fun inMemoryStoreReturnsExistingCommandForDuplicateCommandId() {
        val store = InMemoryCommandLogStore()
        val original = commandLogRecord(commandId = "cmd-1", idempotencyKey = "idem-1")
        val duplicate = commandLogRecord(commandId = "cmd-1", idempotencyKey = "idem-2")

        assertTrue(store.append(original).appended)
        val result = store.append(duplicate)

        assertFalse(result.appended)
        assertEquals(original, result.record)
        assertEquals(original, store.findByCommandId("cmd-1"))
    }

    @Test
    fun inMemoryStoreTracksProcessingCompletedAndFailedStatus() {
        val store = InMemoryCommandLogStore()
        val completed = commandLogRecord(commandId = "cmd-completed", idempotencyKey = "idem-completed")
        val failed = commandLogRecord(commandId = "cmd-failed", idempotencyKey = "idem-failed")

        store.append(completed)
        store.append(failed)
        store.markProcessing("cmd-completed")
        store.markCompleted("cmd-completed", 200, """{"accepted":true}""")
        store.markProcessing("cmd-failed")
        store.markFailed("cmd-failed", 503, "runtime unavailable")

        val completedRecord = store.findByCommandId("cmd-completed")
        assertEquals(CommandLogStatus.COMPLETED, completedRecord?.status)
        assertEquals(1, completedRecord?.attemptCount)
        assertEquals(200, completedRecord?.responseStatus)
        assertEquals("""{"accepted":true}""", completedRecord?.responsePayloadJson)
        assertEquals("", completedRecord?.lastError)

        val failedRecord = store.findByCommandId("cmd-failed")
        assertEquals(CommandLogStatus.FAILED, failedRecord?.status)
        assertEquals(1, failedRecord?.attemptCount)
        assertEquals(503, failedRecord?.responseStatus)
        assertEquals("runtime unavailable", failedRecord?.lastError)
    }

    @Test
    fun inMemoryStoreClaimsReceivedCommandsAndCountsStatuses() {
        val store = InMemoryCommandLogStore()
        val first = commandLogRecord(commandId = "cmd-claim-1", idempotencyKey = "idem-claim-1")
        val second = commandLogRecord(commandId = "cmd-claim-2", idempotencyKey = "idem-claim-2")
            .copy(receivedAt = Instant.parse("2026-06-04T13:00:01Z"))

        store.append(second)
        store.append(first)

        val claimed = store.claimReceived(1)
        val counts = store.statusCounts()

        assertEquals(listOf("cmd-claim-1"), claimed.map { it.commandId })
        assertEquals(CommandLogStatus.PROCESSING, claimed.single().status)
        assertEquals(1, claimed.single().attemptCount)
        assertEquals(CommandLogStatus.PROCESSING, store.findByCommandId("cmd-claim-1")?.status)
        assertEquals(listOf("cmd-claim-2"), store.findByStatus(CommandLogStatus.RECEIVED, 10).map { it.commandId })
        assertEquals(1L, counts[CommandLogStatus.RECEIVED])
        assertEquals(1L, counts[CommandLogStatus.PROCESSING])
        assertEquals(0L, counts[CommandLogStatus.COMPLETED])
        assertEquals(0L, counts[CommandLogStatus.FAILED])
    }

    @Test
    fun inMemoryStoreBuildsAccountingSnapshotByRun() {
        val store = InMemoryCommandLogStore()
        val first = commandLogRecord(commandId = "cmd-accounting-1", idempotencyKey = "idem-accounting-1")
            .copy(runId = "run-1")
        val second = commandLogRecord(commandId = "cmd-accounting-2", idempotencyKey = "idem-accounting-2")
            .copy(runId = "run-1")
        val otherRun = commandLogRecord(commandId = "cmd-accounting-3", idempotencyKey = "idem-accounting-3")
            .copy(runId = "run-2")

        store.append(first)
        store.append(second)
        store.append(otherRun)
        store.markProcessing(first.commandId)
        store.markCompleted(first.commandId, 200, """{"accepted":true}""")
        store.markProcessing(otherRun.commandId)

        val runOne = store.accountingSnapshot("run-1")
        val allRuns = store.accountingSnapshot()

        assertEquals(2L, runOne.accepted)
        assertEquals(1L, runOne.received)
        assertEquals(0L, runOne.processing)
        assertEquals(1L, runOne.completed)
        assertEquals(0L, runOne.accountingGap)
        assertEquals(3L, allRuns.accepted)
        assertEquals(1L, allRuns.processing)
    }

    @Test
    fun inMemoryStoreMarksTerminalBatch() {
        val store = InMemoryCommandLogStore()
        val completed = commandLogRecord(commandId = "cmd-batch-completed", idempotencyKey = "idem-batch-completed")
        val failed = commandLogRecord(commandId = "cmd-batch-failed", idempotencyKey = "idem-batch-failed")

        store.append(completed)
        store.append(failed)
        store.markProcessing(completed.commandId)
        store.markProcessing(failed.commandId)
        store.markTerminal(
            listOf(
                CommandTerminalUpdate(
                    commandId = completed.commandId,
                    status = CommandLogStatus.COMPLETED,
                    responseStatus = 200,
                    responsePayloadJson = """{"accepted":true}"""
                ),
                CommandTerminalUpdate(
                    commandId = failed.commandId,
                    status = CommandLogStatus.FAILED,
                    responseStatus = 503,
                    responsePayloadJson = "{}",
                    errorMessage = "runtime unavailable"
                )
            )
        )

        val accounting = store.accountingSnapshot()
        assertEquals(CommandLogStatus.COMPLETED, store.findByCommandId(completed.commandId)?.status)
        assertEquals(CommandLogStatus.FAILED, store.findByCommandId(failed.commandId)?.status)
        assertEquals(2L, accounting.terminal)
        assertEquals(0L, accounting.active)
        assertEquals(0L, accounting.accountingGap)
    }

    @Test
    fun inMemoryStoreAllowsRepeatedTerminalUpdates() {
        val store = InMemoryCommandLogStore()
        val record = commandLogRecord(commandId = "cmd-repeat-terminal", idempotencyKey = "idem-repeat-terminal")

        store.append(record)
        store.markProcessing(record.commandId)
        store.markCompleted(record.commandId, 200, """{"accepted":true}""")
        store.markCompleted(record.commandId, 200, """{"accepted":true,"replayed":true}""")

        val stored = store.findByCommandId(record.commandId)
        val accounting = store.accountingSnapshot()

        assertEquals(CommandLogStatus.COMPLETED, stored?.status)
        assertEquals("""{"accepted":true,"replayed":true}""", stored?.responsePayloadJson)
        assertEquals(1L, accounting.terminal)
        assertEquals(0L, accounting.accountingGap)
    }
}

private fun commandLogRecord(
    commandId: String,
    idempotencyKey: String,
    clientId: String = "client-1",
    route: String = "/api/v1/orders/submit"
): CommandLogRecord {
    return CommandLogRecord(
        commandId = commandId,
        clientId = clientId,
        route = route,
        idempotencyKey = idempotencyKey,
        traceId = "trace-$commandId",
        correlationId = "corr-$commandId",
        actorId = "actor-1",
        commandType = "SubmitOrder",
        receivedAt = Instant.parse("2026-06-04T13:00:00Z"),
        payloadJson = """{"commandId":"$commandId"}"""
    )
}

class PostgresCommandLogStoreIntegrationTest {
    @Test
    fun postgresStoreAppendsAndReplaysCommandWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val record = commandLogRecord(commandId = "cmd-$suffix", idempotencyKey = "idem-$suffix")

        val appended = store.append(record)
        val restartedStore = postgresStoreOrNull() ?: return
        val found = restartedStore.findByIdempotency(record.clientId, record.route, record.idempotencyKey)
        val foundByCommandId = restartedStore.findByCommandId(record.commandId)

        assertTrue(appended.appended)
        assertNotNull(found)
        assertEquals(record.commandId, found.commandId)
        assertTrue(found.payloadJson.contains(record.commandId))
        assertEquals(CommandLogStatus.RECEIVED, found.status)
        assertEquals(record.commandId, foundByCommandId?.commandId)
    }

    @Test
    fun postgresStoreWritesPayloadToSideTableByDefaultWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val record = commandLogRecord(commandId = "cmd-side-payload-$suffix", idempotencyKey = "idem-side-payload-$suffix")

        assertTrue(store.append(record).appended)
        val stored = store.findByCommandId(record.commandId)
        val physicalPayloads = physicalCommandPayloads(record.commandId) ?: return

        assertEquals(record.payloadJson, stored?.payloadJson)
        assertEquals("""{}""", physicalPayloads.commandRowPayloadJson)
        assertEquals(record.payloadJson, physicalPayloads.payloadRowPayloadJson)
    }

    @Test
    fun postgresStoreReturnsExistingCommandForDuplicateIdempotencyWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val original = commandLogRecord(commandId = "cmd-original-$suffix", idempotencyKey = "idem-$suffix")
        val duplicate = commandLogRecord(commandId = "cmd-duplicate-$suffix", idempotencyKey = "idem-$suffix")

        assertTrue(store.append(original).appended)
        val result = store.append(duplicate)

        assertFalse(result.appended)
        assertEquals(original.commandId, result.record.commandId)
        assertEquals(original.commandId, store.findByIdempotency(original.clientId, original.route, original.idempotencyKey)?.commandId)
    }

    @Test
    fun postgresStoreFunctionAppendModeReturnsExistingCommandForDuplicateIdempotencyWhenConfigured() {
        val store = postgresStoreOrNull(appendMode = PostgresCommandLogAppendMode.Function) ?: return
        val suffix = UUID.randomUUID().toString()
        val original = commandLogRecord(commandId = "cmd-function-original-$suffix", idempotencyKey = "idem-function-$suffix")
        val duplicate = commandLogRecord(commandId = "cmd-function-duplicate-$suffix", idempotencyKey = "idem-function-$suffix")

        assertTrue(store.append(original).appended)
        val result = store.append(duplicate)

        assertFalse(result.appended)
        assertEquals(original.commandId, result.record.commandId)
        assertEquals(CommandLogStatus.RECEIVED, result.record.status)
        assertEquals(original.commandId, store.findByIdempotency(original.clientId, original.route, original.idempotencyKey)?.commandId)
    }

    @Test
    fun postgresStoreReturnsSingleWinnerForConcurrentDuplicateIdempotencyWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val idempotencyKey = "idem-concurrent-$suffix"
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            val futures = (0 until workers).map { index ->
                executor.submit<CommandLogAppendResult> {
                    val record = commandLogRecord(
                        commandId = "cmd-concurrent-$suffix-$index",
                        idempotencyKey = idempotencyKey
                    )
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    postgresStoreOrNull()?.append(record) ?: error("Postgres store unavailable during concurrent append")
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach the start gate")
            start.countDown()

            val results = futures.map { future -> future.get(10, TimeUnit.SECONDS) }
            val winnerIds = results.map { it.record.commandId }.toSet()
            val stored = store.findByIdempotency("client-1", "/api/v1/orders/submit", idempotencyKey)

            assertEquals(1, results.count { it.appended }, "exactly one append should win")
            assertEquals(1, winnerIds.size, "all duplicate attempts should replay the same command")
            assertNotNull(stored)
            assertEquals(winnerIds.single(), stored.commandId)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun postgresStoreReturnsExistingCommandForDuplicateCommandIdWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val original = commandLogRecord(commandId = "cmd-$suffix", idempotencyKey = "idem-original-$suffix")
        val duplicate = commandLogRecord(commandId = "cmd-$suffix", idempotencyKey = "idem-duplicate-$suffix")

        assertTrue(store.append(original).appended)
        val result = store.append(duplicate)

        assertFalse(result.appended)
        assertEquals(original.idempotencyKey, result.record.idempotencyKey)
        assertEquals(original.idempotencyKey, store.findByCommandId(original.commandId)?.idempotencyKey)
    }

    @Test
    fun postgresStorePersistsStatusTransitionsWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val record = commandLogRecord(commandId = "cmd-status-$suffix", idempotencyKey = "idem-status-$suffix")

        assertTrue(store.append(record).appended)
        store.markProcessing(record.commandId)
        store.markCompleted(record.commandId, 200, """{"accepted":true}""")
        val completed = postgresStoreOrNull()?.findByCommandId(record.commandId)

        assertEquals(CommandLogStatus.COMPLETED, completed?.status)
        assertEquals(1, completed?.attemptCount)
        assertEquals(200, completed?.responseStatus)
        assertTrue(completed?.responsePayloadJson?.contains("accepted") == true)
    }

    @Test
    fun postgresStoreKeepsRepeatedTerminalUpdatesIdempotentWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val record = commandLogRecord(commandId = "cmd-repeat-terminal-$suffix", idempotencyKey = "idem-repeat-terminal-$suffix")
            .copy(runId = "run-repeat-terminal-$suffix")

        assertTrue(store.append(record).appended)
        store.markProcessing(record.commandId)
        store.markCompleted(record.commandId, 200, """{"accepted":true}""")
        store.markCompleted(record.commandId, 200, """{"accepted":true,"replayed":true}""")

        val stored = store.findByCommandId(record.commandId)
        val accounting = store.accountingSnapshot(record.runId)

        assertEquals(CommandLogStatus.COMPLETED, stored?.status)
        assertTrue(stored?.responsePayloadJson?.contains("replayed") == true)
        assertEquals(1L, accounting.terminal)
        assertEquals(0L, accounting.accountingGap)
    }

    @Test
    fun postgresStoreCanMarkKnownCommandTerminalWithoutQueueRowWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val record = commandLogRecord(commandId = "cmd-terminal-no-queue-$suffix", idempotencyKey = "idem-terminal-no-queue-$suffix")
            .copy(runId = "run-terminal-no-queue-$suffix")

        assertTrue(store.append(record).appended)
        deleteQueueRow(record.commandId) ?: return
        store.markFailed(record.commandId, 503, "worker unavailable")

        val stored = store.findByCommandId(record.commandId)
        val accounting = store.accountingSnapshot(record.runId)

        assertEquals(CommandLogStatus.FAILED, stored?.status)
        assertEquals("worker unavailable", stored?.lastError)
        assertEquals(1L, accounting.terminal)
        assertEquals(0L, accounting.accountingGap)
    }

    @Test
    fun postgresStoreClaimsReceivedCommandsWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val first = commandLogRecord(commandId = "cmd-claim-first-$suffix", idempotencyKey = "idem-claim-first-$suffix")
            .copy(receivedAt = Instant.parse("1800-01-01T00:00:00Z"))
        val second = commandLogRecord(commandId = "cmd-claim-second-$suffix", idempotencyKey = "idem-claim-second-$suffix")
            .copy(receivedAt = Instant.parse("1800-01-01T00:00:01Z"))

        assertTrue(store.append(second).appended)
        assertTrue(store.append(first).appended)

        val claimed = store.claimReceived(1)
        val stored = store.findByCommandId(first.commandId)
        val counts = store.statusCounts()

        assertEquals(listOf(first.commandId), claimed.map { it.commandId })
        assertEquals(CommandLogStatus.PROCESSING, claimed.single().status)
        assertEquals(1, claimed.single().attemptCount)
        assertEquals(CommandLogStatus.PROCESSING, stored?.status)
        assertTrue((counts[CommandLogStatus.PROCESSING] ?: 0L) >= 1L)

        store.markCompleted(first.commandId, 200, """{"accepted":true}""")
        store.markFailed(second.commandId, 503, "test cleanup")
    }

    @Test
    fun postgresStoreReclaimsStaleProcessingCommandsWhenConfigured() {
        val store = postgresStoreOrNull(processingLeaseMs = 60_000L) ?: return
        val suffix = UUID.randomUUID().toString()
        val stale = commandLogRecord(
            commandId = "cmd-stale-processing-$suffix",
            idempotencyKey = "idem-stale-processing-$suffix"
        ).copy(receivedAt = Instant.parse("1900-01-01T00:00:00Z"))

        assertTrue(store.append(stale).appended)
        store.markProcessing(stale.commandId)
        forceStaleProcessing(stale.commandId) ?: return

        val claimed = store.claimReceived(1)
        val stored = store.findByCommandId(stale.commandId)

        assertEquals(listOf(stale.commandId), claimed.map { it.commandId })
        assertEquals(CommandLogStatus.PROCESSING, claimed.single().status)
        assertEquals(2, claimed.single().attemptCount)
        assertEquals(CommandLogStatus.PROCESSING, stored?.status)
        assertEquals(2, stored?.attemptCount)

        store.markCompleted(stale.commandId, 200, """{"accepted":true}""")
    }

    @Test
    fun postgresCompatBootstrapReconstructsMissingActiveQueueRowsWhenConfigured() {
        val store = postgresStoreOrNull() ?: return
        val suffix = UUID.randomUUID().toString()
        val record = commandLogRecord(
            commandId = "cmd-rebuild-queue-$suffix",
            idempotencyKey = "idem-rebuild-queue-$suffix"
        ).copy(runId = "run-rebuild-queue-$suffix")

        assertTrue(store.append(record).appended)
        deleteQueueRow(record.commandId) ?: return

        val recoveredStore = postgresStoreOrNull(bootstrapMode = PostgresBootstrapMode.Compat) ?: return
        val recovered = recoveredStore.findByCommandId(record.commandId)
        val accounting = recoveredStore.accountingSnapshot(record.runId)

        assertEquals(CommandLogStatus.RECEIVED, recovered?.status)
        assertEquals(1L, accounting.accepted)
        assertEquals(1L, accounting.active)
        assertEquals(0L, accounting.accountingGap)

        recoveredStore.markCompleted(record.commandId, 200, """{"accepted":true}""")
    }

    private fun postgresStoreOrNull(
        appendMode: PostgresCommandLogAppendMode = PostgresCommandLogAppendMode.Inline,
        payloadMode: PostgresCommandLogPayloadMode = PostgresCommandLogPayloadMode.SideTable,
        processingLeaseMs: Long = 60_000L,
        bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.Validate
    ): PostgresCommandLogStore? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        return PostgresCommandLogStore(
            dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword),
            bootstrapMode = bootstrapMode,
            appendMode = appendMode,
            payloadMode = payloadMode,
            processingLeaseMs = processingLeaseMs
        )
    }

    private data class PhysicalCommandPayloads(
        val commandRowPayloadJson: String,
        val payloadRowPayloadJson: String
    )

    private fun physicalCommandPayloads(commandId: String): PhysicalCommandPayloads? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                SELECT commands.payload_json::text AS command_payload_json,
                       payloads.payload_json::text AS payload_row_payload_json
                FROM command_log.commands commands
                JOIN command_log.command_payloads payloads ON payloads.command_id = commands.command_id
                WHERE commands.command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return PhysicalCommandPayloads(
                        commandRowPayloadJson = rs.getString("command_payload_json"),
                        payloadRowPayloadJson = rs.getString("payload_row_payload_json")
                    )
                }
            }
        }
    }

    private fun forceStaleProcessing(commandId: String): Boolean? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                UPDATE command_log.command_work_queue
                SET status = 'PROCESSING',
                    updated_at = '1900-01-01T00:00:00Z'::timestamptz,
                    leased_by = '',
                    leased_until = NULL
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeUpdate()
            }
        }
        return true
    }

    private fun deleteQueueRow(commandId: String): Boolean? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM command_log.command_work_queue
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeUpdate()
            }
        }
        return true
    }
}
