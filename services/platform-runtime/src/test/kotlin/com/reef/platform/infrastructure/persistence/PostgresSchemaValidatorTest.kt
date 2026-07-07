package com.reef.platform.infrastructure.persistence

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresSchemaValidatorTest {
    private fun connect(): java.sql.Connection? {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return null
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return null
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return null
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)
    }

    @Test
    fun validatePassesSilentlyWhenAllTablesFunctionsAndColumnsExist() {
        val conn = connect() ?: return
        conn.use {
            PostgresSchemaValidator.validate(it, PostgresSchemaRequirements.runtime(PostgresRuntimeSqlNames()))
        }
    }

    @Test
    fun validateThrowsWithMissingTableName() {
        val conn = connect() ?: return
        conn.use {
            val requirement = PostgresSchemaRequirement(
                tables = listOf(PostgresSchemaObject.parse("runtime.does_not_exist_table"))
            )
            val error = assertFailsWith<IllegalStateException> {
                PostgresSchemaValidator.validate(it, requirement)
            }
            assertTrue(error.message!!.contains("table runtime.does_not_exist_table"))
            assertTrue(error.message!!.contains("make dev-db-migrate"))
        }
    }

    @Test
    fun validateThrowsWithMissingFunctionName() {
        val conn = connect() ?: return
        conn.use {
            val requirement = PostgresSchemaRequirement(
                tables = emptyList(),
                functions = listOf(PostgresSchemaObject.parse("runtime.does_not_exist_function"))
            )
            val error = assertFailsWith<IllegalStateException> {
                PostgresSchemaValidator.validate(it, requirement)
            }
            assertTrue(error.message!!.contains("function runtime.does_not_exist_function"))
        }
    }

    @Test
    fun validateThrowsWithMissingColumnName() {
        val conn = connect() ?: return
        conn.use {
            val table = PostgresSchemaObject.parse("runtime.runtime_events")
            val requirement = PostgresSchemaRequirement(
                tables = emptyList(),
                columns = listOf(PostgresSchemaColumn(table, "does_not_exist_column"))
            )
            val error = assertFailsWith<IllegalStateException> {
                PostgresSchemaValidator.validate(it, requirement)
            }
            assertTrue(error.message!!.contains("column runtime.runtime_events.does_not_exist_column"))
        }
    }

    @Test
    fun validateThrowsWithExpectedTypeSuffixWhenColumnTypeMismatches() {
        val conn = connect() ?: return
        conn.use {
            val table = PostgresSchemaObject.parse("runtime.runtime_events")
            val requirement = PostgresSchemaRequirement(
                tables = emptyList(),
                columns = listOf(PostgresSchemaColumn(table, "event_id", expectedDataType = "integer"))
            )
            val error = assertFailsWith<IllegalStateException> {
                PostgresSchemaValidator.validate(it, requirement)
            }
            assertTrue(error.message!!.contains("column runtime.runtime_events.event_id type integer"))
        }
    }
}
