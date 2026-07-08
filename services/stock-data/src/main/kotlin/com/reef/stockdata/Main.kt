package com.reef.stockdata

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

fun defaultSeedSnapshotRepository(): SeedSnapshotRepository {
    val persistence = (System.getenv("STOCK_DATA_PERSISTENCE") ?: "inmemory").trim().lowercase()
    return when (persistence) {
        "inmemory", "memory" -> InMemorySeedSnapshotRepository()
        "postgres" -> {
            val config = HikariConfig().apply {
                jdbcUrl = System.getenv("STOCK_DATA_POSTGRES_JDBC_URL")
                    ?: "jdbc:postgresql://localhost:5432/reef?currentSchema=stock_data"
                username = System.getenv("STOCK_DATA_POSTGRES_USER") ?: "reef"
                password = System.getenv("STOCK_DATA_POSTGRES_PASSWORD") ?: "reef"
                poolName = "reef-stock-data"
                maximumPoolSize = System.getenv("STOCK_DATA_POSTGRES_POOL_MAX")?.toIntOrNull() ?: 8
            }
            PostgresSeedSnapshotRepository(HikariDataSource(config))
        }
        else -> throw IllegalArgumentException("Unsupported STOCK_DATA_PERSISTENCE: $persistence")
    }
}

fun defaultStockDataProvider(): StockDataProvider {
    val providerName = (System.getenv("STOCK_DATA_PROVIDER") ?: "tiingo").trim().lowercase()
    return when (providerName) {
        "fake" -> FakeStockDataProvider()
        "tiingo" -> {
            val config = TiingoConfig.fromEnv()
            TiingoStockDataProvider(config, JdkTiingoHttpClient(config))
        }
        else -> throw IllegalArgumentException("Unsupported STOCK_DATA_PROVIDER: $providerName")
    }
}

fun main() {
    val workflow = SeedWorkflow(defaultStockDataProvider(), defaultSeedSnapshotRepository())
    val port = System.getenv("STOCK_DATA_HTTP_PORT")?.toIntOrNull() ?: 8081
    StockDataHttpServer(workflow, port).start()
    println("stock-data service listening on :$port")
}
