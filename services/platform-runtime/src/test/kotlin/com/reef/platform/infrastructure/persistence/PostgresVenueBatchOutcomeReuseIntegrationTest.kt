package com.reef.platform.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresVenueBatchOutcomeReuseIntegrationTest {
    @Test
    fun normalBatchReplaysAndConflictingCommandRollsBack() = withSchema { conn, schema ->
        val first = batch("first", "checksum-first", 2, "cmd-a" to 1, "cmd-b" to 2)
        assertEquals(2, conn.materialize(schema, first))
        assertEquals(0, conn.materialize(schema, first))
        assertEquals(2, conn.count(schema, "canonical_command_outcomes"))

        val checksumConflict = batch("first", "changed-checksum", 2, "cmd-a" to 1, "cmd-b" to 2)
        assertTrue(assertFailsWith<SQLException> { conn.materialize(schema, checksumConflict) }.message.orEmpty().contains("checksum conflict"))

        val commandConflict = batch("second", "checksum-second", 1, "cmd-a" to 3)
        assertTrue(assertFailsWith<SQLException> { conn.materialize(schema, commandConflict) }.message.orEmpty().contains("canonical command outcome conflict"))
        assertEquals(1, conn.count(schema, "canonical_venue_event_batches"))
        assertEquals(2, conn.count(schema, "canonical_command_outcomes"))
    }

    @Test
    fun duplicateCommandIdsRejectWholeBatch() = withSchema { conn, schema ->
        val duplicate = batch("duplicate", "checksum-duplicate", 2, "cmd-a" to 1, "cmd-a" to 2)
        assertTrue(assertFailsWith<SQLException> { conn.materialize(schema, duplicate) }.message.orEmpty().contains("duplicate commandId"))
        assertEquals(0, conn.count(schema, "canonical_venue_event_batches"))
        assertEquals(0, conn.count(schema, "canonical_command_outcomes"))
    }

    @Test
    fun commandCountMismatchRejectsWholeBatch() = withSchema { conn, schema ->
        val mismatch = batch("mismatch", "checksum-mismatch", 3, "cmd-a" to 1, "cmd-b" to 2)
        assertTrue(assertFailsWith<SQLException> { conn.materialize(schema, mismatch) }.message.orEmpty().contains("command count mismatch"))
        assertEquals(0, conn.count(schema, "canonical_venue_event_batches"))
        assertEquals(0, conn.count(schema, "canonical_command_outcomes"))
    }

    private fun withSchema(block: (Connection, String) -> Unit) {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(url, user, password, "batch-outcome-reuse-${UUID.randomUUID()}")
        val schema = "batch_reuse_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            dataSource.connection.use { conn ->
                conn.exec("CREATE SCHEMA $schema")
                try {
                    conn.exec(
                        "CREATE TABLE $schema.canonical_venue_event_batches (" +
                            "batch_id TEXT, shard_id TEXT, partition_id INTEGER, command_stream TEXT, event_stream TEXT, " +
                            "first_sequence BIGINT, last_sequence BIGINT, command_count INTEGER, payload_checksum TEXT, " +
                            "payload_format TEXT, payload_version TEXT, payload_json JSONB, created_at TEXT, " +
                            "UNIQUE(event_stream, batch_id))"
                    )
                    conn.exec(
                        "CREATE TABLE $schema.canonical_command_outcomes (" +
                            "command_id TEXT PRIMARY KEY, batch_id TEXT, shard_id TEXT, partition_id INTEGER, " +
                            "command_stream TEXT, event_stream TEXT, stream_sequence BIGINT, delivered_count BIGINT, " +
                            "command_type TEXT, payload_hash TEXT, instrument_id TEXT, order_id TEXT, " +
                            "result_status TEXT, reject_code TEXT, result_payload JSONB, " +
                            "UNIQUE(partition_id, stream_sequence))"
                    )
                    conn.exec(
                        Files.readString(Path.of("../../scripts/dev/db/migrations/runtime/0063_reuse_materialized_batch_outcomes.sql"))
                            .replace("runtime.", "$schema.")
                    )
                    block(conn, schema)
                } finally {
                    conn.exec("DROP SCHEMA $schema CASCADE")
                }
            }
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    }

    private fun batch(id: String, checksum: String, declaredCount: Int, vararg outcomes: Pair<String, Int>): String {
        val outcomeJson = outcomes.joinToString(",") { (commandId, sequence) ->
            """{"commandId":"$commandId","streamSequence":$sequence,"commandType":"SubmitOrder"}"""
        }
        return """{"batchId":"$id","shardId":"shard","partition":1,"commandStream":"commands","eventStream":"events","firstSequence":1,"lastSequence":${outcomes.size},"commandCount":$declaredCount,"payloadChecksum":"$checksum","outcomes":[$outcomeJson]}"""
    }

    private fun Connection.materialize(schema: String, batch: String): Long =
        prepareStatement("SELECT $schema.runtime_materialize_venue_event_batch(?::jsonb)").use { ps ->
            ps.setString(1, batch)
            ps.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    private fun Connection.count(schema: String, table: String): Long =
        createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $schema.$table").use { rows -> rows.next(); rows.getLong(1) }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
