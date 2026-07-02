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

    private fun postgresStoreOrNull(
        appendMode: PostgresCommandLogAppendMode = PostgresCommandLogAppendMode.Inline,
        processingLeaseMs: Long = 60_000L
    ): PostgresCommandLogStore? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        return PostgresCommandLogStore(
            dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword),
            bootstrapMode = PostgresBootstrapMode.Validate,
            appendMode = appendMode,
            processingLeaseMs = processingLeaseMs
        )
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
}
