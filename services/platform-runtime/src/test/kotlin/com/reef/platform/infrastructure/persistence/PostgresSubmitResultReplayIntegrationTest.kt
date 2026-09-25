package com.reef.platform.infrastructure.persistence

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresSubmitResultReplayIntegrationTest {
    @Test
    fun identicalReplaySucceedsAndChangedResultFails() {
        val source = testSource() ?: return
        val commandId = UUID.randomUUID().toString()
        try {
            source.connection.use { conn ->
                conn.autoCommit = false
                try {
                    assertEquals(1L, persist(conn, payload(commandId, "ACCEPTED")))
                    assertEquals(1L, persist(conn, payload(commandId, "ACCEPTED")))
                    val conflict = assertFailsWith<SQLException> {
                        persist(conn, payload(commandId, "REJECTED"))
                    }
                    assertEquals("23505", conflict.sqlState)
                    assertContains(conflict.message.orEmpty(), "submit result replay conflict")
                } finally {
                    conn.rollback()
                }
            }
        } finally {
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun concurrentConflictingInsertIsRejectedAfterUniqueKeyWait() {
        val source = testSource() ?: return
        val commandId = UUID.randomUUID().toString()
        val executor = Executors.newSingleThreadExecutor()
        try {
            source.connection.use { first ->
                first.autoCommit = false
                source.connection.use { second ->
                    try {
                        first.prepareStatement(
                            """
                            INSERT INTO runtime.submit_results(
                              command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at
                            ) VALUES (?, 'ACCEPTED', ?, '', '', '', '', '2026-09-25T00:00:00Z')
                            """.trimIndent()
                        ).use { ps ->
                            ps.setString(1, commandId)
                            ps.setString(2, "result-$commandId")
                            ps.executeUpdate()
                        }
                        val secondPid = second.createStatement().use { statement ->
                            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                rows.next()
                                rows.getInt(1)
                            }
                        }
                        val started = CountDownLatch(1)
                        val conflictingReplay = executor.submit<SQLException?> {
                            started.countDown()
                            try {
                                persist(second, payload(commandId, "REJECTED"))
                                null
                            } catch (ex: SQLException) {
                                ex
                            }
                        }
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        var blocked = false
                        for (attempt in 0 until 200) {
                            source.connection.use { observer ->
                                observer.prepareStatement("SELECT cardinality(pg_blocking_pids(?))").use { ps ->
                                    ps.setInt(1, secondPid)
                                    ps.executeQuery().use { rows ->
                                        rows.next()
                                        blocked = rows.getInt(1) > 0
                                    }
                                }
                            }
                            if (blocked) break
                            Thread.sleep(25)
                        }
                        assertTrue(blocked, "conflicting replay never reached the unique-key lock")
                        first.commit()
                        val conflict = conflictingReplay.get(10, TimeUnit.SECONDS)
                        assertEquals("23505", conflict?.sqlState)
                        assertContains(conflict?.message.orEmpty(), "submit result replay conflict")
                    } finally {
                        first.rollback()
                    }
                }
            }
            source.connection.use { conn ->
                conn.prepareStatement("SELECT result_type FROM runtime.submit_results WHERE command_id = ?").use { ps ->
                    ps.setString(1, commandId)
                    ps.executeQuery().use { rows ->
                        rows.next()
                        assertEquals("ACCEPTED", rows.getString(1))
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            source.connection.use { conn ->
                conn.prepareStatement("DELETE FROM runtime.submit_results WHERE command_id = ?").use { ps ->
                    ps.setString(1, commandId)
                    ps.executeUpdate()
                }
            }
            (source as? AutoCloseable)?.close()
        }
    }

    private fun testSource() = run {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return@run null
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return@run null
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return@run null
        RuntimeDataSources.dataSource(url, user, password, "submit-replay-${UUID.randomUUID()}")
    }

    private fun persist(conn: Connection, payload: String): Long =
        conn.prepareStatement("SELECT runtime.runtime_persist_submit_outcome_status_stage(?::jsonb)").use { ps ->
            ps.setString(1, payload)
            ps.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun payload(commandId: String, resultType: String): String = """
        [{
          "commandId":"$commandId",
          "resultType":"$resultType",
          "eventId":"result-$commandId",
          "orderId":"",
          "engineOrderId":"",
          "code":"",
          "reason":"",
          "occurredAt":"2026-09-25T00:00:00Z",
          "executions":[],
          "trades":[]
        }]
    """.trimIndent()
}
