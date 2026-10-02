package com.reef.platform.calcify

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CalcifySourceRegistrationTest {
    @Test
    fun verifyTopicBindsOnFirstSightThenRequiresExactMatch() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val role = "verified"
        val name = "topic-${System.currentTimeMillis()}"
        val id = "uuid-${System.currentTimeMillis()}"
        try {
            // First sight binds; replay with the identical name+id is a no-op.
            CalcifySourceRegistration.verifyTopic(url, user, password, 1, role, name, id)
            CalcifySourceRegistration.verifyTopic(url, user, password, 1, role, name, id)

            // Recreation under the same name with a different broker topic ID
            // (the exact P1-B failure mode) must fail closed, not silently pass.
            assertFailsWith<IllegalArgumentException> {
                CalcifySourceRegistration.verifyTopic(url, user, password, 1, role, name, "different-uuid")
            }
        } finally {
            DriverManager.getConnection(url, user, password).use { db ->
                db.prepareStatement(
                    "DELETE FROM runtime.calcify_topic_identities WHERE source_generation = ? AND role = ? AND topic_name = ?"
                ).use {
                    it.setInt(1, 1)
                    it.setString(2, role)
                    it.setString(3, name)
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun verifyTopicDistinguishesRolesIndependently() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val suffix = System.currentTimeMillis()
        try {
            // Same generation, different roles, independently bound - one
            // role's identity must not collide with or block the other's.
            CalcifySourceRegistration.verifyTopic(url, user, password, 1, "verified", "verified-topic-$suffix", "verified-uuid-$suffix")
            CalcifySourceRegistration.verifyTopic(url, user, password, 1, "output", "output-topic-$suffix", "output-uuid-$suffix")
            CalcifySourceRegistration.verifyTopic(url, user, password, 1, "verified", "verified-topic-$suffix", "verified-uuid-$suffix")
        } finally {
            DriverManager.getConnection(url, user, password).use { db ->
                db.prepareStatement(
                    "DELETE FROM runtime.calcify_topic_identities WHERE source_generation = ? AND topic_name LIKE ?"
                ).use {
                    it.setInt(1, 1)
                    it.setString(2, "%-topic-$suffix")
                    it.executeUpdate()
                }
            }
        }
    }
}
