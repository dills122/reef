package com.reef.stockdata

import kotlin.test.Test
import kotlin.test.assertEquals

class StockDataConfigTest {
    @Test
    fun defaultsApplyWhenEnvIsEmpty() {
        assertEquals(StockDataConfig(), StockDataConfig.fromEnv(emptyMap()))
    }

    @Test
    fun readsOverridesAndNormalizesNames() {
        val config = StockDataConfig.fromEnv(
            mapOf(
                "STOCK_DATA_PERSISTENCE" to " Postgres ",
                "STOCK_DATA_POSTGRES_JDBC_URL" to "jdbc:postgresql://db/reef",
                "STOCK_DATA_POSTGRES_USER" to "u",
                "STOCK_DATA_POSTGRES_PASSWORD" to "p",
                "STOCK_DATA_POSTGRES_POOL_MAX" to "3",
                "STOCK_DATA_PROVIDER" to "FAKE",
                "STOCK_DATA_HTTP_PORT" to "9000",
            )
        )
        assertEquals("postgres", config.persistence)
        assertEquals("jdbc:postgresql://db/reef", config.postgresJdbcUrl)
        assertEquals("u", config.postgresUser)
        assertEquals("p", config.postgresPassword)
        assertEquals(3, config.postgresPoolMax)
        assertEquals("fake", config.provider)
        assertEquals(9000, config.httpPort)
    }

    @Test
    fun invalidNumbersFallBackToDefaults() {
        val config = StockDataConfig.fromEnv(
            mapOf("STOCK_DATA_POSTGRES_POOL_MAX" to "x", "STOCK_DATA_HTTP_PORT" to "y")
        )
        assertEquals(8, config.postgresPoolMax)
        assertEquals(8081, config.httpPort)
    }
}
