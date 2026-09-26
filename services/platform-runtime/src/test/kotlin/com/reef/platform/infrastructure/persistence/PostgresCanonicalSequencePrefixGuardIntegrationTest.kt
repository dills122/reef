package com.reef.platform.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresCanonicalSequencePrefixGuardIntegrationTest {
    @Test
    fun duplicateAfterGapAndMixedNamespaceCannotAdvanceSelectedPrefix() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(url, user, password, "prefix-guard-test")
        val schema = "prefix_guard_${UUID.randomUUID().toString().replace("-", "")}"
        val base = 281474976710656L
        try {
            dataSource.connection.use { conn ->
                conn.exec("CREATE SCHEMA $schema")
                conn.exec("CREATE TABLE $schema.canonical_command_outcomes(partition_id INTEGER,stream_sequence BIGINT,command_id TEXT,batch_id TEXT,command_type TEXT,payload_hash TEXT,event_stream TEXT)")
                conn.exec("CREATE TABLE $schema.projection_watermarks(projection_name TEXT,partition_id INTEGER,last_partition_seq BIGINT)")
                conn.exec("CREATE TABLE $schema.captured_members(members JSONB)")
                conn.exec("CREATE FUNCTION $schema.runtime_cleanup_projection_batch_claims(INTEGER) RETURNS BIGINT LANGUAGE SQL AS \$\$ SELECT 0::BIGINT \$\$")
                conn.exec("CREATE FUNCTION $schema.runtime_projection_batch_identity_v1(TEXT,TEXT,TEXT,BOOLEAN,JSONB) RETURNS TEXT LANGUAGE SQL AS \$\$ SELECT 'test'::TEXT \$\$")
                conn.exec(
                    """
                    CREATE FUNCTION $schema.runtime_claim_projection_batch_v1(
                      TEXT,TEXT,TEXT,TEXT,BOOLEAN,p_candidates JSONB,TIMESTAMPTZ,BIGINT
                    ) RETURNS TABLE(is_new BOOLEAN,stored_result_count BIGINT)
                    LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                      INSERT INTO $schema.captured_members VALUES (p_candidates);
                      RETURN QUERY SELECT FALSE, 0::BIGINT;
                    END
                    ${'$'}${'$'}
                    """.trimIndent()
                )
                conn.exec(
                    Files.readString(Path.of("../../scripts/dev/db/migrations/runtime/0067_bounded_canonical_selection.sql"))
                        .replace("runtime.", "$schema.")
                )

                val encoded = base + 2
                conn.exec("INSERT INTO $schema.canonical_command_outcomes VALUES (1,1,'legacy','legacy-batch','SubmitOrder','hash','events'),(1,$encoded,'encoded','encoded-batch','SubmitOrder','hash','events')")
                assertEquals(0L, conn.project(schema, "mixed"))
                assertEquals(
                    listOf("legacy"),
                    conn.capturedCommandIds(schema),
                    "an unencoded row cannot make the first encoded row look contiguous"
                )

                conn.exec("TRUNCATE $schema.captured_members,$schema.canonical_command_outcomes")
                conn.exec("INSERT INTO $schema.projection_watermarks VALUES ('duplicate',1,${base + 1})")
                conn.exec("INSERT INTO $schema.canonical_command_outcomes VALUES (1,${base + 3},'dup-a','batch-a','SubmitOrder','hash','events'),(1,${base + 3},'dup-b','batch-b','SubmitOrder','hash','events')")
                assertEquals(0L, conn.project(schema, "duplicate"))
                assertEquals(emptyList(), conn.capturedCommandIds(schema))
                conn.createStatement().use { statement ->
                    statement.executeQuery("SELECT last_partition_seq FROM $schema.projection_watermarks WHERE projection_name='duplicate'").use { rs ->
                        rs.next()
                        assertEquals(base + 1, rs.getLong(1))
                    }
                }
            }
        } finally {
            dataSource.connection.use { it.exec("DROP SCHEMA IF EXISTS $schema CASCADE") }
            (dataSource as? AutoCloseable)?.close()
        }
    }

    private fun Connection.project(schema: String, projection: String): Long =
        prepareStatement("SELECT $schema.runtime_project_canonical_command_outcomes(?,10,ARRAY[1],TRUE,'events',60000)").use { ps ->
            ps.setString(1, projection)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

    private fun Connection.capturedCommandIds(schema: String): List<String> =
        createStatement().use { statement ->
            statement.executeQuery("SELECT member->>'commandId' FROM $schema.captured_members, jsonb_array_elements(members) member").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
