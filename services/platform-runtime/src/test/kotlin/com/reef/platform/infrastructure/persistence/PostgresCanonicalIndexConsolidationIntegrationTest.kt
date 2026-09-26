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

class PostgresCanonicalIndexConsolidationIntegrationTest {
    @Test
    fun uniqueCoveringIndexReplacesBothPriorIndexesAndRejectsDuplicateSequence() = withSchema { conn, schema ->
        conn.exec("INSERT INTO $schema.canonical_command_outcomes VALUES ('one', 3, 7, 'SubmitOrder')")
        conn.autoCommit = false
        conn.exec(migrationSql(schema))
        conn.commit()
        conn.autoCommit = true

        conn.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT indisunique, indnkeyatts, indnatts, pg_get_indexdef(indexrelid) " +
                    "FROM pg_index WHERE indexrelid = '$schema.idx_canonical_command_outcomes_partition_seq'::regclass"
            ).use { rows ->
                assertTrue(rows.next())
                assertTrue(rows.getBoolean(1))
                assertEquals(2, rows.getInt(2))
                assertEquals(3, rows.getInt(3))
                assertTrue(rows.getString(4).contains("INCLUDE (command_type)"))
            }
            statement.executeQuery(
                "SELECT to_regclass('$schema.idx_canonical_command_outcomes_partition_sequence_unique') IS NULL"
            ).use { rows ->
                assertTrue(rows.next())
                assertTrue(rows.getBoolean(1))
            }
        }
        val conflict = assertFailsWith<SQLException> {
            conn.exec("INSERT INTO $schema.canonical_command_outcomes VALUES ('two', 3, 7, 'CancelOrder')")
        }
        assertEquals("23505", conflict.sqlState)
    }

    @Test
    fun wrongPrebuiltIndexIsRejectedWithoutDroppingExistingIndexes() = withSchema { conn, schema ->
        conn.exec("CREATE INDEX idx_canonical_command_outcomes_partition_seq_next ON $schema.canonical_command_outcomes(partition_id)")
        conn.autoCommit = false
        val invalid = assertFailsWith<SQLException> { conn.exec(migrationSql(schema)) }
        conn.rollback()
        conn.autoCommit = true
        assertTrue(invalid.message.orEmpty().contains("unique covering index"))
        conn.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT to_regclass('$schema.idx_canonical_command_outcomes_partition_seq') IS NOT NULL, " +
                    "to_regclass('$schema.idx_canonical_command_outcomes_partition_sequence_unique') IS NOT NULL"
            ).use { rows ->
                assertTrue(rows.next())
                assertTrue(rows.getBoolean(1))
                assertTrue(rows.getBoolean(2))
            }
        }
    }

    @Test
    fun validPrebuiltIndexIsAdoptedWithoutRebuilding() = withSchema { conn, schema ->
        conn.exec(
            "CREATE UNIQUE INDEX idx_canonical_command_outcomes_partition_seq_next " +
                "ON $schema.canonical_command_outcomes(partition_id, stream_sequence) INCLUDE (command_type)"
        )
        conn.autoCommit = false
        conn.exec(migrationSql(schema))
        conn.commit()
        conn.autoCommit = true
        conn.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT indisunique, indnkeyatts, indnatts FROM pg_index " +
                    "WHERE indexrelid = '$schema.idx_canonical_command_outcomes_partition_seq'::regclass"
            ).use { rows ->
                assertTrue(rows.next())
                assertTrue(rows.getBoolean(1))
                assertEquals(2, rows.getInt(2))
                assertEquals(3, rows.getInt(3))
            }
        }
    }

    private fun withSchema(block: (Connection, String) -> Unit) {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(url, user, password, "index-consolidation-${UUID.randomUUID()}")
        val schema = "index_merge_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            dataSource.connection.use { conn ->
                conn.exec("CREATE SCHEMA $schema")
                try {
                    conn.exec(
                        "CREATE TABLE $schema.canonical_command_outcomes " +
                            "(command_id TEXT PRIMARY KEY, partition_id INTEGER NOT NULL, stream_sequence BIGINT NOT NULL, command_type TEXT NOT NULL)"
                    )
                    conn.exec(
                        "CREATE INDEX idx_canonical_command_outcomes_partition_seq " +
                            "ON $schema.canonical_command_outcomes(partition_id, stream_sequence) INCLUDE (command_type)"
                    )
                    conn.exec(
                        "CREATE UNIQUE INDEX idx_canonical_command_outcomes_partition_sequence_unique " +
                            "ON $schema.canonical_command_outcomes(partition_id, stream_sequence)"
                    )
                    block(conn, schema)
                } finally {
                    conn.autoCommit = true
                    conn.exec("DROP SCHEMA $schema CASCADE")
                }
            }
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    }

    private fun migrationSql(schema: String): String = Files.readString(
        Path.of("../../scripts/dev/db/migrations/runtime/0062_consolidate_canonical_sequence_index.sql")
    ).replace("runtime.", "$schema.")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
