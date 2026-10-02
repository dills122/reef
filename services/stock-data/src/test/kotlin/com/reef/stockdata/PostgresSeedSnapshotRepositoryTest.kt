package com.reef.stockdata

import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import java.time.Instant
import java.time.temporal.ChronoUnit

@Tag("postgres")
class PostgresSeedSnapshotRepositoryTest {
    private fun dataSource(): PGSimpleDataSource = PGSimpleDataSource().apply {
        setURL(System.getenv("STOCK_DATA_POSTGRES_JDBC_URL_TEST") ?: error("Set STOCK_DATA_POSTGRES_JDBC_URL_TEST to an isolated test database"))
        user = System.getenv("STOCK_DATA_POSTGRES_USER_TEST") ?: "reef"
        password = System.getenv("STOCK_DATA_POSTGRES_PASSWORD_TEST") ?: "reef"
        connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(Files.readString(Path.of("../../scripts/dev/db/migrations/stock_data/0001_seed_snapshots.sql")))
            }
        }
    }

    @Test
    fun `PostgreSQL publication and replay equal in-memory canonical facts`() {
        val repository = PostgresSeedSnapshotRepository(dataSource())
        val asOf = Instant.parse("2026-07-08T15:00:00.123456789Z")
        val candidate = FakeStockDataProvider().getSeedSnapshots(UUID.randomUUID().toString(), listOf("MSFT", "AAPL"), asOf)
        val memoryWinner = InMemorySeedSnapshotRepository().createOrExisting(candidate)
        val postgresWinner = repository.createOrExisting(candidate)
        assertEquals(memoryWinner, postgresWinner)
        assertEquals(memoryWinner.batchSeedHash, postgresWinner.batchSeedHash)
        assertEquals(memoryWinner, repository.find(candidate.gameSeedId))
        assertFailsWith<UnsupportedOperationException> {
            (postgresWinner.snapshots as MutableList<StockSeedSnapshot>).clear()
        }
        val replay = checkNotNull(repository.find(candidate.gameSeedId))
        assertFailsWith<UnsupportedOperationException> {
            (replay.snapshots as MutableList<StockSeedSnapshot>).clear()
        }
    }

    @Test
    fun `invalid batch children cannot create or extend seed facts`() {
        val repository = PostgresSeedSnapshotRepository(dataSource())
        val seed = UUID.randomUUID().toString()
        val candidate = FakeStockDataProvider().getSeedSnapshots(seed, listOf("AAPL"), Instant.EPOCH)
        val invalidBatches = listOf(
            candidate.copy(snapshots = emptyList()),
            candidate.copy(snapshots = candidate.snapshots + candidate.snapshots),
            candidate.copy(snapshots = candidate.snapshots.map { it.copy(gameSeedId = "other-seed") }),
        )
        for (invalid in invalidBatches) {
            assertFailsWith<IllegalArgumentException> { repository.createOrExisting(invalid) }
            assertEquals(null, repository.find(seed))
        }
    }

    @Test
    fun `child insert failure rolls back header and every snapshot`() {
        val dataSource = dataSource()
        val repository = PostgresSeedSnapshotRepository(dataSource)
        val seed = UUID.randomUUID().toString()
        val rejectedPayload = UUID.randomUUID().toString()
        val constraint = "reject_" + UUID.randomUUID().toString().replace("-", "")
        val candidate = FakeStockDataProvider().getSeedSnapshots(seed, listOf("AAPL", "MSFT"), Instant.EPOCH)
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE stock_data.seed_snapshots ADD CONSTRAINT $constraint CHECK (raw_provider_payload_hash <> '$rejectedPayload')")
            }
        }
        try {
            val invalid = candidate.copy(snapshots = listOf(candidate.snapshots[0], candidate.snapshots[1].copy(rawProviderPayloadHash = rejectedPayload)))
            assertFailsWith<SQLException> { repository.createOrExisting(invalid) }
            assertEquals(null, repository.find(seed))
            assertEquals(candidate, repository.createOrExisting(candidate), "Rolled-back claim must be available to retry")
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("ALTER TABLE stock_data.seed_snapshots DROP CONSTRAINT $constraint") }
            }
        }
    }

    @Test
    fun `uncommitted batch stays invisible while unrelated seed can publish`() {
        val dataSource = dataSource()
        val beforeCommit = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val firstCommit = AtomicBoolean(true)
        val gatedDataSource = object : DataSource by dataSource {
            override fun getConnection(): Connection {
                val connection = dataSource.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "commit" && firstCommit.compareAndSet(true, false)) {
                        beforeCommit.countDown()
                        check(allowCommit.await(15, TimeUnit.SECONDS)) { "Test did not release publication" }
                    }
                    try {
                        method.invoke(connection, *(args ?: emptyArray()))
                    } catch (error: InvocationTargetException) {
                        throw error.targetException
                    }
                } as Connection
            }
        }
        val repository = PostgresSeedSnapshotRepository(gatedDataSource)
        val candidate = FakeStockDataProvider().getSeedSnapshots(UUID.randomUUID().toString(), listOf("AAPL", "MSFT"), Instant.EPOCH)
        val loser = FakeStockDataProvider().getSeedSnapshots(candidate.gameSeedId, listOf("GOOG"), Instant.EPOCH.plusSeconds(1))
        val independent = FakeStockDataProvider().getSeedSnapshots(UUID.randomUUID().toString(), listOf("TSLA"), Instant.EPOCH)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val winnerFuture = executor.submit<StockSeedSnapshotBatch> { repository.createOrExisting(candidate) }
            assertTrue(beforeCommit.await(10, TimeUnit.SECONDS))
            assertEquals(null, repository.find(candidate.gameSeedId), "Uncommitted header and children must be invisible")
            val loserStarted = CountDownLatch(1)
            val loserFuture = executor.submit<StockSeedSnapshotBatch> {
                loserStarted.countDown()
                repository.createOrExisting(loser)
            }
            assertTrue(loserStarted.await(10, TimeUnit.SECONDS))
            val otherFuture = executor.submit<StockSeedSnapshotBatch> { repository.createOrExisting(independent) }
            assertEquals(independent, otherFuture.get(10, TimeUnit.SECONDS), "Different seed must progress before first commit")
            assertFalse(loserFuture.isDone, "Same-seed loser must wait for publication")
            allowCommit.countDown()
            assertEquals(candidate, winnerFuture.get(10, TimeUnit.SECONDS))
            assertEquals(candidate, loserFuture.get(10, TimeUnit.SECONDS))
            assertEquals(candidate, repository.find(candidate.gameSeedId))
        } finally {
            allowCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `published hash describes stored snapshots at microsecond precision`() {
        val dataSource = dataSource()
        val repository = PostgresSeedSnapshotRepository(dataSource)
        val asOf = Instant.parse("2026-07-08T15:00:00.123456789Z")
        val seed = java.util.UUID.randomUUID().toString()
        val candidate = FakeStockDataProvider().getSeedSnapshots(seed, listOf("AAPL"), asOf)
        val winner = repository.createOrExisting(candidate)
        assertEquals(asOf.truncatedTo(ChronoUnit.MICROS), winner.asOf)
        assertEquals(winner, repository.find(seed))
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT batch_seed_hash FROM stock_data.seed_snapshot_batches WHERE game_seed_id = ?").use { statement ->
                statement.setString(1, seed)
                statement.executeQuery().use { result ->
                    check(result.next())
                    assertEquals(winner.batchSeedHash, result.getString(1))
                }
            }
        }
    }

    @Test
    fun `concurrent connections publish one whole canonical batch and replay it`() {
        val dataSource = dataSource()
        assertConcurrentSeedWinner(PostgresSeedSnapshotRepository(dataSource), java.util.UUID.randomUUID().toString())
    }
}
