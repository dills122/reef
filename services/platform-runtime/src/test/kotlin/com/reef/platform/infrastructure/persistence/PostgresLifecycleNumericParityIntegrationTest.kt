package com.reef.platform.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresLifecycleNumericParityIntegrationTest {
    @Test
    fun incrementalLifecycleMatchesFullRebuildAndRepairsTerminalNumericRows() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(url, user, password, "lifecycle-parity-test")
        val schema = "lifecycle_parity_${UUID.randomUUID().toString().replace("-", "")}"
        val names = PostgresRuntimeSqlNames(runtimeSchema = schema)
        try {
            val persistence = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            source.connection.use { c ->
                c.exec("CREATE TABLE ${names.orderLifecycleDirty}(order_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                c.exec("CREATE TABLE ${names.marketDataSnapshotDirty}(instrument_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                // Install the latest migration-owned function, preserving production SQL verbatim.
                val migrationDir = Path.of("../../scripts/dev/db/migrations/runtime")
                val functionSql = Files.list(migrationDir).use { files ->
                    files.filter { it.toString().endsWith(".sql") }.sorted().toList()
                        .map { Files.readString(it) }
                        .last { it.contains("CREATE OR REPLACE FUNCTION runtime.runtime_project_order_lifecycle_state(") }
                }
                val start = functionSql.indexOf("CREATE OR REPLACE FUNCTION runtime.runtime_project_order_lifecycle_state(")
                val end = functionSql.indexOf("$$;", start) + 3
                c.exec(functionSql.substring(start, end).replace("runtime.", "$schema."))
                val cases = listOf("open", "partial", "filled", "overfilled", "cancelled", "cancel_partial", "rejected", "reject_partial", "modified", "modify_cancel", "modify_filled", "zero")
                cases.forEach { id ->
                    val quantity = if (id == "zero") "0" else "10.5"
                    c.exec("INSERT INTO ${names.orders}(order_id,engine_order_id,instrument_id,participant_id,account_id,side,order_type,quantity_units,limit_price,currency,time_in_force,accepted_at) VALUES ('$id','$id','instrument','participant','account','BUY','LIMIT','$quantity','12.25','USD','GTC','2026-09-24T00:00:00Z')")
                    c.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) VALUES ('$id')")
                }
                mapOf("partial" to "2.25", "filled" to "10.5", "overfilled" to "12", "cancel_partial" to "2.25", "reject_partial" to "2.25", "modified" to "2.25", "modify_cancel" to "2.25", "modify_filled" to "7.75").forEach { (id, quantity) ->
                    c.exec("INSERT INTO ${names.executions}(event_id,execution_id,order_id,instrument_id,quantity_units,execution_price,currency,occurred_at) VALUES ('$id','$id','$id','instrument','$quantity','12.25','USD','2026-09-24T00:01:00Z')")
                }
                fun event(id: String, type: String, sequence: Int, quantity: String = "", price: String = "") {
                    c.exec("INSERT INTO ${names.runtimeEvents}(event_id,event_type,order_id,trace_id,causation_id,correlation_id,producer,schema_version,sequence_number,occurred_at,modify_quantity_units,modify_limit_price) VALUES ('$id-$sequence','$type','$id','trace','cause','correlation','test','v1',$sequence,'2026-09-24T00:02:00Z','$quantity','$price')")
                }
                listOf("cancelled", "cancel_partial", "modify_cancel").forEach { event(it, "OrderCancelled", 3) }
                listOf("rejected", "reject_partial").forEach { event(it, "OrderRejected", 3) }
                listOf("modified", "modify_cancel", "modify_filled").forEach {
                    event(it, "OrderModified", 1, "9", "14")
                    event(it, "OrderModified", 2, "7.75", "13.75")
                }
            }
            assertEquals(12, persistence.projectOrderLifecycleState(500))
            val incremental = source.connection.use { c ->
                assertEquals(0, c.scalar("SELECT count(*) FROM ${names.orderLifecycleState} WHERE remaining_quantity_units::NUMERIC IS DISTINCT FROM remaining_quantity_units_num OR original_quantity_units::NUMERIC IS DISTINCT FROM original_quantity_units_num OR filled_quantity_units::NUMERIC IS DISTINCT FROM filled_quantity_units_num OR limit_price::NUMERIC IS DISTINCT FROM limit_price_num"), "text and numeric quantities/prices must agree, including terminal orders")
                c.rows(names.orderLifecycleState)
            }
            assertEquals(12, persistence.rebuildOrderLifecycleState())
            source.connection.use { c ->
                assertEquals(incremental, c.rows(names.orderLifecycleState), "all business columns match reference; only updated_at excluded")
                // Simulate already persisted pre-fix rows. Explicit re-dirty repairs without canonical mutation.
                c.exec("UPDATE ${names.orderLifecycleState} SET remaining_quantity_units_num = 10.5 WHERE status IN ('CANCELLED','REJECTED')")
                c.exec("DELETE FROM ${names.marketDataSnapshotDirty}")
                c.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) SELECT order_id FROM ${names.orderLifecycleState} WHERE status IN ('CANCELLED','REJECTED')")
            }
            assertEquals(5, persistence.projectOrderLifecycleState(500))
            source.connection.use { c ->
                assertEquals(incremental, c.rows(names.orderLifecycleState))
                assertEquals(1, c.scalar("SELECT count(*) FROM ${names.marketDataSnapshotDirty}"), "repaired numeric rows fan out to market refresh")
                c.exec("DELETE FROM ${names.marketDataSnapshotDirty}")
                c.exec("INSERT INTO ${names.orderLifecycleDirty}(order_id) SELECT order_id FROM ${names.orderLifecycleState}")
            }
            assertEquals(12, persistence.projectOrderLifecycleState(500))
            assertEquals(0, persistence.projectOrderLifecycleState(500))
            source.connection.use { c ->
                assertEquals(incremental, c.rows(names.orderLifecycleState))
                assertEquals(0, c.scalar("SELECT count(*) FROM ${names.marketDataSnapshotDirty}"), "unchanged replay must not fan out")
            }
        } finally {
            source.connection.use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            (source as? AutoCloseable)?.close()
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql: String): Long = createStatement().use { s ->
        s.executeQuery(sql).use { rs -> assertTrue(rs.next()); rs.getLong(1) }
    }
    private fun Connection.rows(table: String): List<String> = createStatement().use { s ->
        s.executeQuery("SELECT (to_jsonb(row) - 'updated_at')::TEXT FROM $table row ORDER BY order_id").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }
}
