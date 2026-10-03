package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresIdempotencyStoreIntegrationTest {
    private val original = IdempotencyResult(202, "original")
    private val renewed = IdempotencyResult(200, "renewed")

    @Test
    fun persistedLiveRecordCanBeReadByNewStoreInstanceAndCannotBeOverwritten() = withDatabase { dataSource, names ->
        val storeA = store(dataSource, names)
        save(storeA, original)
        val before = metadata(dataSource, names)
        val storeB = store(dataSource, names)
        save(storeB, renewed)

        assertEquals(original, find(storeB))
        assertEquals(before, metadata(dataSource, names))
    }

    @Test
    fun expiredRecordRenewsWithoutCleanupAndPersistsAcrossInstances() = withDatabase { dataSource, names ->
        val storeA = store(dataSource, names)
        save(storeA, original)
        expire(dataSource, names)
        val before = metadata(dataSource, names)
        assertNull(find(storeA))

        save(storeA, renewed)

        assertEquals(renewed, find(store(dataSource, names)))
        val after = metadata(dataSource, names)
        assertTrue(after.first > before.first, "creation time must belong to renewal")
        assertTrue(after.second > after.first, "renewal must receive a fresh TTL")
        // A live duplicate must not extend that TTL or replace the response.
        save(storeA, original)
        assertEquals(renewed, find(storeA))
        assertEquals(after, metadata(dataSource, names))
    }

    @Test
    fun concurrentRenewalsKeepOneLiveWinnerAcrossInstances() = withDatabase { dataSource, names ->
        val storeA = store(dataSource, names)
        val storeB = store(dataSource, names)
        save(storeA, original)
        expire(dataSource, names)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val candidates = listOf(renewed, IdempotencyResult(409, "other-renewal"))
        try {
            val futures = listOf(storeA, storeB).zip(candidates).map { (writer, candidate) ->
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    save(writer, candidate)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            val winner = find(storeA)
            assertTrue(winner in candidates)
            assertEquals(winner, find(storeB))
            val winnerMetadata = metadata(dataSource, names)
            candidates.forEach { save(storeB, it) }
            assertEquals(winner, find(store(dataSource, names)))
            assertEquals(winnerMetadata, metadata(dataSource, names))
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun store(dataSource: DataSource, names: PostgresBoundarySqlNames) =
        PostgresIdempotencyStore(dataSource, names = names, bootstrapMode = PostgresBootstrapMode.Compat)

    private fun save(store: IdempotencyStore, result: IdempotencyResult) =
        store.save("client", "/api/v1/orders/submit", "key", result, IdempotencyTtlClass.STANDARD)

    private fun find(store: IdempotencyStore) = store.find("client", "/api/v1/orders/submit", "key")

    private fun expire(dataSource: DataSource, names: PostgresBoundarySqlNames) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE ${names.idempotencyRecords} SET created_at = NOW() - INTERVAL '2 days', expires_at = NOW() - INTERVAL '1 day'"
                )
            }
        }
    }

    private fun metadata(dataSource: DataSource, names: PostgresBoundarySqlNames): Pair<Instant, Instant> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT created_at, expires_at FROM ${names.idempotencyRecords}").use { rows ->
                    assertTrue(rows.next())
                    rows.getTimestamp("created_at").toInstant() to rows.getTimestamp("expires_at").toInstant()
                }
            }
        }

    private fun withDatabase(block: (DataSource, PostgresBoundarySqlNames) -> Unit) {
        val jdbcUrl = System.getenv("RUNTIME_DB_URL_TEST")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "Postgres test skipped: set RUNTIME_DB_URL_TEST, RUNTIME_DB_USER_TEST, RUNTIME_DB_PASSWORD_TEST")
        val dbUser = assertNotNull(System.getenv("RUNTIME_DB_USER_TEST"), "RUNTIME_DB_USER_TEST required when DB URL configured")
        val dbPassword = assertNotNull(System.getenv("RUNTIME_DB_PASSWORD_TEST"), "RUNTIME_DB_PASSWORD_TEST required when DB URL configured")
        val dataSource = RuntimeDataSources.dataSource(assertNotNull(jdbcUrl), dbUser, dbPassword)
        val names = PostgresBoundarySqlNames("idem_test_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            block(dataSource, names)
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS ${names.schemaName} CASCADE") }
            }
        }
    }
}
