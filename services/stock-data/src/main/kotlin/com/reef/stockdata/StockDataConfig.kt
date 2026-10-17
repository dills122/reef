package com.reef.stockdata

data class StockDataConfig(
    val persistence: String = "inmemory",
    val postgresJdbcUrl: String = "jdbc:postgresql://localhost:5432/reef?currentSchema=stock_data",
    val postgresUser: String = "reef",
    val postgresPassword: String = "reef",
    val postgresPoolMax: Int = 8,
    val provider: String = "tiingo",
    val httpPort: Int = 8081,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): StockDataConfig = StockDataConfig(
            persistence = (env["STOCK_DATA_PERSISTENCE"] ?: "inmemory").trim().lowercase(),
            postgresJdbcUrl = env["STOCK_DATA_POSTGRES_JDBC_URL"]
                ?: "jdbc:postgresql://localhost:5432/reef?currentSchema=stock_data",
            postgresUser = env["STOCK_DATA_POSTGRES_USER"] ?: "reef",
            postgresPassword = env["STOCK_DATA_POSTGRES_PASSWORD"] ?: "reef",
            postgresPoolMax = env["STOCK_DATA_POSTGRES_POOL_MAX"]?.toIntOrNull() ?: 8,
            provider = (env["STOCK_DATA_PROVIDER"] ?: "tiingo").trim().lowercase(),
            httpPort = env["STOCK_DATA_HTTP_PORT"]?.toIntOrNull() ?: 8081,
        )
    }
}
