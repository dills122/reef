package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.TradeCreated
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresTradeReplayIntegrationTest {
    @Test
    fun directTradeSaveRejectsConflictingReplay() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "direct-trade-replay-${UUID.randomUUID()}")
        val eventId = UUID.randomUUID().toString()
        val persistence = PostgresRuntimePersistence(source, bootstrapMode = PostgresBootstrapMode.Compat)
        val original = TradeCreated(
            eventId = eventId,
            tradeId = "trade-$eventId",
            executionId = "execution-$eventId",
            buyOrderId = "buy-$eventId",
            sellOrderId = "sell-$eventId",
            instrumentId = "AAPL",
            quantityUnits = "10",
            price = "150",
            currency = "USD",
            occurredAt = "2026-09-25T00:00:00Z"
        )
        try {
            persistence.saveTrades(listOf(original))
            persistence.saveTrades(listOf(original))
            val conflict = assertFailsWith<SQLException> {
                persistence.saveTrades(listOf(original.copy(price = "151")))
            }
            assertEquals("23505", conflict.sqlState)
            assertContains(conflict.message.orEmpty(), "trade replay conflict")
            source.connection.use { conn ->
                conn.prepareStatement("SELECT price FROM runtime.trades WHERE event_id = ?").use { ps ->
                    ps.setString(1, eventId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        assertEquals("150", rs.getString(1))
                    }
                }
            }
        } finally {
            source.connection.use { conn ->
                conn.prepareStatement("DELETE FROM runtime.trades WHERE event_id = ?").use { ps ->
                    ps.setString(1, eventId)
                    ps.executeUpdate()
                }
            }
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun identicalTradeReplaySucceedsButConflictingTradeIsRejected() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "trade-replay-${UUID.randomUUID()}")
        val commandId = UUID.randomUUID().toString()
        val tradeEventId = UUID.randomUUID().toString()
        try {
            source.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val original = payload(commandId, tradeEventId, "150")
                    assertEquals(1L, persist(conn, original))
                    assertEquals(1L, persist(conn, original))
                    conn.prepareStatement("SELECT COUNT(*) FROM runtime.trades WHERE event_id = ?").use { ps ->
                        ps.setString(1, tradeEventId)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            assertEquals(1L, rs.getLong(1))
                        }
                    }
                    val conflict = assertFailsWith<SQLException> {
                        persist(conn, payload(commandId, tradeEventId, "151"))
                    }
                    assertEquals("23505", conflict.sqlState)
                    assertContains(conflict.message.orEmpty(), "trade replay conflict")
                } finally {
                    conn.rollback()
                }
            }
        } finally {
            (source as? AutoCloseable)?.close()
        }
    }

    private fun persist(conn: Connection, payload: String): Long =
        conn.prepareStatement("SELECT runtime.runtime_persist_submit_outcome_status_stage(?::jsonb)").use { ps ->
            ps.setString(1, payload)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun payload(commandId: String, tradeEventId: String, price: String): String = """
        [{
          "commandId":"$commandId",
          "resultType":"ACCEPTED",
          "eventId":"result-$commandId",
          "orderId":"buy-$commandId",
          "engineOrderId":"engine-$commandId",
          "code":"",
          "reason":"",
          "occurredAt":"2026-09-25T00:00:00Z",
          "executions":[],
          "trades":[{
            "eventId":"$tradeEventId",
            "tradeId":"trade-$tradeEventId",
            "executionId":"execution-$tradeEventId",
            "buyOrderId":"buy-$commandId",
            "sellOrderId":"sell-$commandId",
            "instrumentId":"AAPL",
            "quantityUnits":"10",
            "price":"$price",
            "currency":"USD",
            "occurredAt":"2026-09-25T00:00:00Z"
          }]
        }]
    """.trimIndent()
}
