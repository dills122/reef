package com.reef.platform.calcify

import java.sql.DriverManager
import java.sql.SQLException
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Runtime migration 0077: the finance topic roles are accepted, unknown roles still refused. Skipped (not passed) without the runtime test database. */
class CalcifyTopicRoleMigrationTest {
    @Test fun financeTopicRolesAreAcceptedAndUnknownRolesRefused() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST"); assumeTrue(!url.isNullOrBlank(), "runtime test database not provisioned")
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: error("test user missing")
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: error("test password missing")
        DriverManager.getConnection(url, user, password).use { c ->
            c.autoCommit = false
            try {
                val roles = listOf("verified", "output", "COMMAND", "CAPTURE", "OWNERSHIP", "CAPTURE_CHANGELOG", "CONTROL", "FINANCE_DECISIONS", "FINANCE_CHANGELOG")
                for (role in roles) c.prepareStatement("INSERT INTO runtime.calcify_topic_identities(source_generation, role, topic_name, topic_id) VALUES (1, ?, ?, ?)").use {
                    it.setString(1, role); it.setString(2, "migration-test-$role"); it.setString(3, "id-$role"); it.executeUpdate()
                }
                c.createStatement().use { it.execute("SAVEPOINT bad") }
                assertFailsWith<SQLException> {
                    c.prepareStatement("INSERT INTO runtime.calcify_topic_identities(source_generation, role, topic_name, topic_id) VALUES (1, 'SOURCE', 'x', 'y')").use { it.executeUpdate() }
                }
            } finally { c.rollback() }
        }
    }
}
