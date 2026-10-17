package com.reef.stockdata

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

fun defaultSeedSnapshotRepository(config: StockDataConfig = StockDataConfig.fromEnv()): SeedSnapshotRepository {
    return when (config.persistence) {
        "inmemory", "memory" -> InMemorySeedSnapshotRepository()
        "postgres" -> {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.postgresJdbcUrl
                username = config.postgresUser
                password = config.postgresPassword
                poolName = "reef-stock-data"
                maximumPoolSize = config.postgresPoolMax
            }
            PostgresSeedSnapshotRepository(HikariDataSource(hikari))
        }
        else -> throw IllegalArgumentException("Unsupported STOCK_DATA_PERSISTENCE: ${config.persistence}")
    }
}

fun defaultStockDataProvider(config: StockDataConfig = StockDataConfig.fromEnv()): StockDataProvider {
    return when (config.provider) {
        "fake" -> FakeStockDataProvider()
        "tiingo" -> {
            val tiingo = TiingoConfig.fromEnv()
            TiingoStockDataProvider(tiingo, JdkTiingoHttpClient(tiingo))
        }
        else -> throw IllegalArgumentException("Unsupported STOCK_DATA_PROVIDER: ${config.provider}")
    }
}

fun main() {
    val config = StockDataConfig.fromEnv()
    val workflow = SeedWorkflow(defaultStockDataProvider(config), defaultSeedSnapshotRepository(config))
    StockDataHttpServer(workflow, config.httpPort).start()
    println("stock-data service listening on :${config.httpPort}")
}
