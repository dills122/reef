package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PostgresIdempotencyStoreIntegrationTest {
    @Test
    fun persistedRecordCanBeReadByNewStoreInstanceWhenConfigured() {
        val jdbcUrl = System.getenv("RUNTIME_DB_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_DB_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_DB_PASSWORD_TEST") ?: return

        val storeA = PostgresIdempotencyStore(jdbcUrl, dbUser, dbPassword)
        storeA.save(
            clientId = "client-integration",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-integration",
            result = IdempotencyResult(200, """{"ok":true}"""),
            ttlClass = IdempotencyTtlClass.STANDARD
        )

        val storeB = PostgresIdempotencyStore(jdbcUrl, dbUser, dbPassword)
        val found = storeB.find("client-integration", "/api/v1/orders/submit", "idem-integration")
        assertNotNull(found)
        assertEquals(200, found.status)
    }
}
