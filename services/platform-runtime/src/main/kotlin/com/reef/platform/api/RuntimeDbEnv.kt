package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import javax.sql.DataSource

/**
 * Shared "RUNTIME_DB_URL/RUNTIME_DB_USER/RUNTIME_DB_PASSWORD -> DataSource" resolution.
 * Dedupes the identical triad previously repeated across ExternalApiBoundary.kt (8x),
 * CommandCaptureStore.kt (2x), and StreamCommandIntake.kt (1x).
 *
 * Distinct from `postgresDataSourceFromEnv` in PlatformHttpServerBootstrap.kt, which
 * resolves store-specific env var keys (ADMIN_POSTGRES_*, SETTLEMENT_POSTGRES_*, etc.)
 * with their own fallback policies rather than this fixed RUNTIME_DB_* triad — the two
 * helpers serve different (and both real) shapes of duplication, so they are kept
 * separate rather than forced into one signature.
 */
internal fun runtimeDbDataSourceFromEnv(
    poolName: String,
    lookup: (String) -> String? = { key -> System.getenv(key) }
): DataSource {
    val jdbcUrl = lookup("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val dbUser = lookup("RUNTIME_DB_USER") ?: "reef"
    val dbPassword = lookup("RUNTIME_DB_PASSWORD") ?: "reef"
    return RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, poolName)
}
