package com.reef.stockdata

data class TiingoConfig(
    val apiToken: String,
    val baseUrl: String = "https://api.tiingo.com",
    val currentMaxAgeSeconds: Long = 900,
    val providerTimeoutMs: Long = 2500,
    val providerMaxRetries: Int = 2,
    val allowStaleCache: Boolean = false,
) {
    companion object {
        /** Reads the "Configuration" block from docs/STOCK_DATA_SEEDING_PLAN.md. */
        fun fromEnv(env: Map<String, String> = System.getenv()): TiingoConfig = TiingoConfig(
            apiToken = env["TIINGO_API_TOKEN"] ?: "",
            currentMaxAgeSeconds = env["STOCK_DATA_CURRENT_MAX_AGE_SECONDS"]?.toLongOrNull() ?: 900,
            providerTimeoutMs = env["STOCK_DATA_PROVIDER_TIMEOUT_MS"]?.toLongOrNull() ?: 2500,
            providerMaxRetries = env["STOCK_DATA_PROVIDER_MAX_RETRIES"]?.toIntOrNull() ?: 2,
            allowStaleCache = env["STOCK_DATA_ALLOW_STALE_CACHE"]?.toBooleanStrictOrNull() ?: false,
        )
    }
}
