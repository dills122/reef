package com.reef.platform.infrastructure.persistence

import java.lang.reflect.Proxy
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertContains

class ProjectionBatchClaimBootstrapTest {
    @Test
    fun compatContractRejectsCompletedCountsThatDifferFromClaimedMembership() {
        val executedSql = mutableListOf<String>()
        val statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java)
        ) { _, method, args ->
            if (method.name == "execute") {
                executedSql += args?.firstOrNull() as String
                true
            } else {
                null
            }
        } as Statement

        ProjectionBatchClaimBootstrap.install(statement, PostgresRuntimeSqlNames())

        val contractSql = executedSql.joinToString("\n")
        assertContains(contractSql, "result_count IS NULL OR result_count = candidate_count")
        assertContains(contractSql, "p_result_count IS DISTINCT FROM expected_count")
        assertContains(
            contractSql,
            "existing_claim.result_count IS DISTINCT FROM existing_claim.candidate_count"
        )
    }
}
