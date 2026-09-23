package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresProjectionBatchClaimIntegrationTest {
    @Test
    fun rollbackRemovesClaimAndEffectsBeforeRetryWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val fixture = fixture("rollback")

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            assertTrue(claim(conn, fixture, databaseNow(conn).plusSeconds(60)).first)
            upsertWatermark(conn, fixture.projectionName, fixture.candidate.partitionId, fixture.candidate.streamSequence)
            conn.rollback()
        }

        assertEquals(0, countClaims(dataSource, fixture.projectionName))
        assertEquals(0, countWatermarks(dataSource, fixture.projectionName))

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            assertTrue(claim(conn, fixture, databaseNow(conn).plusSeconds(60)).first)
            upsertWatermark(conn, fixture.projectionName, fixture.candidate.partitionId, fixture.candidate.streamSequence)
            complete(conn, fixture.identity, 1)
            conn.commit()
        }

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            val duplicate = claim(conn, fixture, databaseNow(conn).plusSeconds(60))
            assertFalse(duplicate.first)
            assertEquals(1, duplicate.second)
            conn.rollback()
        }
    }

    @Test
    fun incompleteCommittedClaimFailsClosedWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val fixture = fixture("incomplete")

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            assertTrue(claim(conn, fixture, databaseNow(conn).plusSeconds(60)).first)
            conn.commit()
        }

        dataSource.connection.use { conn ->
            assertFailsWith<SQLException> {
                claim(conn, fixture, databaseNow(conn).plusSeconds(60))
            }
        }
    }

    @Test
    fun identityMismatchFailsBeforeClaimInsertionWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val fixture = fixture("identity-conflict").copy(identity = "0".repeat(64))

        dataSource.connection.use { conn ->
            assertFailsWith<SQLException> {
                claim(conn, fixture, databaseNow(conn).plusSeconds(60))
            }
        }
        assertEquals(0, countClaims(dataSource, fixture.projectionName))
    }

    @Test
    fun cleanupRequiresExpiredDeadlineAndStrictlyAdvancedWatermarkWhenMigratedPostgresIsAvailable() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val fixture = fixture("cleanup")

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            assertTrue(claim(conn, fixture, databaseNow(conn).plusMillis(100), retryHorizonMs = 100).first)
            upsertWatermark(conn, fixture.projectionName, fixture.candidate.partitionId, fixture.candidate.streamSequence)
            complete(conn, fixture.identity, 1)
            conn.commit()
        }

        Thread.sleep(150)

        cleanup(dataSource)
        assertEquals(1, countClaims(dataSource, fixture.projectionName))

        dataSource.connection.use { conn ->
            upsertWatermark(conn, fixture.projectionName, fixture.candidate.partitionId, fixture.candidate.streamSequence + 1)
        }
        cleanup(dataSource)
        assertEquals(0, countClaims(dataSource, fixture.projectionName))
    }

    @Test
    fun cleanupRetainsAuthorityUntilEveryStaleSelectorDeadlineHasExpired() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val fixture = fixture("staggered-cleanup")
        val retryHorizonMs = 1_000L
        val firstSelectorDeadline = databaseNow(dataSource).plusMillis(retryHorizonMs)

        Thread.sleep(300)
        val laterSelectorDeadline = databaseNow(dataSource).plusMillis(retryHorizonMs)

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            assertTrue(claim(conn, fixture, firstSelectorDeadline, retryHorizonMs).first)
            upsertWatermark(
                conn,
                fixture.projectionName,
                fixture.candidate.partitionId,
                fixture.candidate.streamSequence + 1
            )
            complete(conn, fixture.identity, 1)
            conn.commit()
        }

        waitUntilDatabaseAfter(dataSource, firstSelectorDeadline)
        cleanup(dataSource)

        dataSource.connection.use { conn ->
            val duplicate = claim(conn, fixture, laterSelectorDeadline, retryHorizonMs)
            assertFalse(duplicate.first)
            assertEquals(1, duplicate.second)
        }
        assertEquals(1, countClaims(dataSource, fixture.projectionName))
    }

    @Test
    fun completionRejectsResultCountThatDoesNotMatchClaimedMembership() {
        val dataSource = migratedDataSourceOrNull() ?: return
        val fixture = fixture("completion-count")

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            assertTrue(claim(conn, fixture, databaseNow(conn).plusSeconds(60)).first)
            assertFailsWith<SQLException> {
                complete(conn, fixture.identity, 0)
            }
            conn.rollback()
        }

        assertEquals(0, countClaims(dataSource, fixture.projectionName))
    }

    private fun fixture(label: String): ClaimFixture {
        val suffix = UUID.randomUUID().toString()
        val projectionName = "projection-claim-$label-$suffix"
        val candidate = ProjectionBatchIdentityCandidate(
            partitionId = 1_000_000 + (suffix.hashCode() and 0x7fffffff) % 1_000_000,
            streamSequence = 10_000_000L + (suffix.hashCode().toLong() and 0xffffffffL),
            commandId = "command-$suffix",
            canonicalBatchId = "batch-$suffix",
            commandType = "SubmitOrder",
            payloadHash = "payload-$suffix"
        )
        val candidatesJson = JsonCodec.writeArray(
            listOf(
                mapOf(
                    "partitionId" to candidate.partitionId,
                    "streamSequence" to candidate.streamSequence,
                    "commandId" to candidate.commandId,
                    "canonicalBatchId" to candidate.canonicalBatchId,
                    "commandType" to candidate.commandType,
                    "payloadHash" to candidate.payloadHash
                )
            )
        )
        return ClaimFixture(
            projectionName = projectionName,
            candidate = candidate,
            candidatesJson = candidatesJson,
            identity = ProjectionBatchIdentityV1.digest(
                projectionName,
                "REEF_VENUE_EVENTS",
                ProjectionStage.Full,
                true,
                listOf(candidate)
            )
        )
    }

    private fun claim(
        conn: Connection,
        fixture: ClaimFixture,
        deadline: Instant,
        retryHorizonMs: Long = 60_000
    ): Pair<Boolean, Long?> {
        conn.prepareStatement(
            """
            SELECT is_new, stored_result_count
            FROM runtime.runtime_claim_projection_batch_v1(?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, fixture.identity)
            ps.setString(2, fixture.projectionName)
            ps.setString(3, "REEF_VENUE_EVENTS")
            ps.setString(4, ProjectionStage.Full.configValue)
            ps.setBoolean(5, true)
            ps.setString(6, fixture.candidatesJson)
            ps.setTimestamp(7, Timestamp.from(deadline))
            ps.setLong(8, retryHorizonMs)
            ps.executeQuery().use { rs ->
                rs.next()
                val stored = rs.getLong(2).let { if (rs.wasNull()) null else it }
                return rs.getBoolean(1) to stored
            }
        }
    }

    private fun databaseNow(dataSource: DataSource): Instant {
        dataSource.connection.use { conn ->
            return databaseNow(conn)
        }
    }

    private fun databaseNow(conn: Connection): Instant {
        conn.prepareStatement("SELECT clock_timestamp()").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getTimestamp(1).toInstant()
            }
        }
    }

    private fun waitUntilDatabaseAfter(dataSource: DataSource, deadline: Instant) {
        val timeoutAt = System.nanoTime() + 5_000_000_000L
        while (!databaseNow(dataSource).isAfter(deadline)) {
            check(System.nanoTime() < timeoutAt) { "database clock did not pass the expected deadline" }
            Thread.sleep(25)
        }
    }

    private fun complete(conn: Connection, identity: String, resultCount: Long) {
        conn.prepareStatement("SELECT runtime.runtime_complete_projection_batch_v1(?, ?)").use { ps ->
            ps.setString(1, identity)
            ps.setLong(2, resultCount)
            ps.executeQuery().use { rs ->
                rs.next()
                assertEquals(resultCount, rs.getLong(1))
            }
        }
    }

    private fun upsertWatermark(conn: Connection, projectionName: String, partitionId: Int, sequence: Long) {
        conn.prepareStatement(
            """
            INSERT INTO runtime.projection_watermarks(
              projection_name, partition_id, last_partition_seq, last_projected_at, updated_at, last_error
            ) VALUES (?, ?, ?, now(), now(), '')
            ON CONFLICT (projection_name, partition_id) DO UPDATE SET
              last_partition_seq = EXCLUDED.last_partition_seq,
              updated_at = EXCLUDED.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, projectionName)
            ps.setInt(2, partitionId)
            ps.setLong(3, sequence)
            ps.executeUpdate()
        }
    }

    private fun cleanup(dataSource: DataSource): Long {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT runtime.runtime_cleanup_projection_batch_claims(1000)").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun countClaims(dataSource: DataSource, projectionName: String): Long =
        count(dataSource, "runtime.projection_batch_claims", projectionName)

    private fun countWatermarks(dataSource: DataSource, projectionName: String): Long =
        count(dataSource, "runtime.projection_watermarks", projectionName)

    private fun count(dataSource: DataSource, table: String, projectionName: String): Long {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE projection_name = ?").use { ps ->
                ps.setString(1, projectionName)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun migratedDataSourceOrNull(): DataSource? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        return RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
    }

    private data class ClaimFixture(
        val projectionName: String,
        val candidate: ProjectionBatchIdentityCandidate,
        val candidatesJson: String,
        val identity: String
    )
}
