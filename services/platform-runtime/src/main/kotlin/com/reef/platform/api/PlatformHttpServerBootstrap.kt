package com.reef.platform.api

import com.reef.platform.application.admin.AdminAuthService
import com.reef.platform.application.admin.AdminGitHubOAuthClient
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.ConfiguredAdminGitHubOAuthClient
import com.reef.platform.application.admin.PostgresAdminAuthStore
import com.reef.platform.application.admin.PostgresAdminIdentityStore
import com.reef.platform.application.defaultRuntimePersistence
import com.reef.platform.application.settlement.DefaultPostTradePolicyVersion
import com.reef.platform.application.settlement.InMemorySettlementFactStore
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.PostgresSettlementFactStore
import com.reef.platform.application.settlement.PostgresSettlementSqlNames
import com.reef.platform.application.settlement.SettlementFactStore
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import javax.sql.DataSource

/**
 * Composition root for [PlatformHttpServer]'s default (non-test) dependency graph:
 * reads env config and wires up the admin auth/arena/analytics/settlement Postgres
 * stores. Kept separate from request-handling code per docs/steering/kotlin.md.
 *
 * [postgresDataSourceFromEnv] dedupes the "env var triad -> DataSource" shape shared
 * by all four `default*()` functions below, but each site still resolves its own
 * fallback semantics explicitly (required vs. optional, RUNTIME_POSTGRES_* fallback
 * vs. none) rather than forcing one policy — those genuinely differ per store and
 * collapsing them silently would be the kind of drift the steering doc warns about.
 * The wider RUNTIME_DB_URL vs. RUNTIME_POSTGRES_JDBC_URL split, and the equivalent
 * duplication in ExternalApiBoundary.kt/CommandCaptureStore.kt, are not addressed
 * here — that's a larger cross-file follow-up, not part of this god-file split.
 */
private fun postgresDataSourceFromEnv(
    jdbcUrl: String,
    userKey: String,
    userFallback: String,
    passwordKey: String,
    passwordFallback: String,
    poolName: String
): DataSource {
    val dbUser = RuntimeEnv.string(userKey, userFallback)
    val dbPassword = RuntimeEnv.string(passwordKey, passwordFallback)
    return RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, poolName)
}

internal fun defaultBoundary(): ServerBoundaryDeps {
    val hooks = defaultBoundaryHooks()
    PlatformRuntimeProfileValidator.requireValidProfile(PlatformRuntimeProfileConfig.fromEnv())
    val runtimePersistence = defaultRuntimePersistence("post-trade-profile-resolver")
    val adminHttpAuth = defaultAdminHttpAuth()
    val postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(
        runtimePersistence = runtimePersistence,
        environmentProfileId = { RuntimeEnv.string("POST_TRADE_PROFILE", "") },
        environmentPolicyVersion = { RuntimeEnv.int("POST_TRADE_POLICY_VERSION", DefaultPostTradePolicyVersion, min = 1) }
    )
    val settlementFactStore = defaultSettlementFactStore()
    val streamPublisher = if (hooks.commandProcessingMode == CommandProcessingMode.StreamAck) {
        StreamCommandIntakeFactory.defaultPublisher()
    } else {
        null
    }
    return ServerBoundaryDeps(
        boundary = ExternalApiBoundary(
            authHook = hooks.authHook,
            rateLimitHook = hooks.rateLimitHook
        ),
        abuseProtectionHook = hooks.abuseProtectionHook,
        accountRiskCheck = hooks.accountRiskCheck,
        accountRiskControlStore = hooks.accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog = hooks.accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck = hooks.commandCircuitBreakerCheck,
        commandCircuitBreakerStore = hooks.commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        instrumentPriceCollarCheck = hooks.instrumentPriceCollarCheck,
        instrumentPriceCollarStore = hooks.instrumentPriceCollarCheck as? InstrumentPriceCollarStore,
        adminAuthService = adminHttpAuth?.authService,
        adminIdentityService = adminHttpAuth?.identityService,
        adminGitHubOAuthClient = adminHttpAuth?.githubOAuthClient,
        settlementFactStore = settlementFactStore,
        settlementObligationMaterializer = settlementFactStore?.let {
            TradeSettlementObligationMaterializer(
                runtimePersistence = runtimePersistence,
                settlementFactStore = it,
                postTradeProfileResolver = postTradeProfileResolver
            )
        },
        postTradeProfileResolver = postTradeProfileResolver,
        scenarioRunPostTradeProfileLookup = { scenarioRunId ->
            runtimePersistence.scenarioRunPostTradeProfileId(scenarioRunId)
        },
        venueSessionPostTradeProfileLookup = { venueSessionId ->
            runtimePersistence.venueSessionPostTradeProfileId(venueSessionId)
        },
        boundaryRejectionLog = hooks.boundaryRejectionLog,
        idempotencyStore = hooks.idempotencyStore,
        idempotencyRetentionPolicy = hooks.idempotencyRetentionPolicy,
        commandCaptureStore = hooks.commandCaptureStore,
        commandStatusLookup = hooks.commandCaptureStore as? CommandStatusLookup,
        capturedCommandQueue = hooks.commandCaptureStore as? CapturedCommandQueue,
        streamCommandIntakeStore = if (hooks.commandProcessingMode == CommandProcessingMode.StreamAck) {
            StreamCommandIntakeFactory.defaultStore()
        } else {
            null
        },
        streamCommandPublisher = streamPublisher,
        streamCommandHealthCheck = streamPublisher as? StreamCommandHealthCheck,
        streamCommandConfig = StreamCommandConfig(),
        commandProcessingMode = hooks.commandProcessingMode
    )
}

private data class AdminHttpAuthDefaults(
    val authService: AdminAuthService,
    val identityService: AdminIdentityService,
    val githubOAuthClient: AdminGitHubOAuthClient
)

private fun defaultAdminHttpAuth(): AdminHttpAuthDefaults? {
    if (!RuntimeEnv.bool("PLATFORM_ADMIN_AUTH_ENABLED", false)) return null
    val jdbcUrl = RuntimeEnv.string("ADMIN_POSTGRES_JDBC_URL", RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", ""))
        .ifBlank { error("ADMIN_POSTGRES_JDBC_URL or RUNTIME_POSTGRES_JDBC_URL is required when PLATFORM_ADMIN_AUTH_ENABLED=true") }
    val dataSource = postgresDataSourceFromEnv(
        jdbcUrl = jdbcUrl,
        userKey = "ADMIN_POSTGRES_USER",
        userFallback = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef"),
        passwordKey = "ADMIN_POSTGRES_PASSWORD",
        passwordFallback = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef"),
        poolName = "admin-auth"
    )
    val identityStore = PostgresAdminIdentityStore(dataSource)
    val authStore = PostgresAdminAuthStore(dataSource)
    val identityService = AdminIdentityService(identityStore)
    val authService = AdminAuthService(authStore = authStore, identityStore = identityStore)
    val githubClient = ConfiguredAdminGitHubOAuthClient(
        clientId = RuntimeEnv.string("GITHUB_OAUTH_CLIENT_ID", "")
            .ifBlank { error("GITHUB_OAUTH_CLIENT_ID is required when PLATFORM_ADMIN_AUTH_ENABLED=true") },
        clientSecret = RuntimeEnv.string("GITHUB_OAUTH_CLIENT_SECRET", "")
            .ifBlank { error("GITHUB_OAUTH_CLIENT_SECRET is required when PLATFORM_ADMIN_AUTH_ENABLED=true") },
        redirectUri = RuntimeEnv.string("GITHUB_OAUTH_REDIRECT_URI", "")
            .ifBlank { error("GITHUB_OAUTH_REDIRECT_URI is required when PLATFORM_ADMIN_AUTH_ENABLED=true") }
    )
    return AdminHttpAuthDefaults(
        authService = authService,
        identityService = identityService,
        githubOAuthClient = githubClient
    )
}

private fun defaultSettlementFactStore(): SettlementFactStore? {
    val explicitJdbcUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
    val runtimeJdbcUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
    val jdbcUrl = explicitJdbcUrl.ifBlank { runtimeJdbcUrl }
    val enabled = RuntimeEnv.bool("PLATFORM_SETTLEMENT_FACTS_ENABLED", jdbcUrl.isNotBlank())
    if (!enabled) return null
    if (jdbcUrl.isBlank()) return InMemorySettlementFactStore()
    val schema = RuntimeEnv.string("SETTLEMENT_POSTGRES_SCHEMA", "settlement")
    return PostgresSettlementFactStore(
        dataSource = postgresDataSourceFromEnv(
            jdbcUrl = jdbcUrl,
            userKey = "SETTLEMENT_POSTGRES_USER",
            userFallback = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef"),
            passwordKey = "SETTLEMENT_POSTGRES_PASSWORD",
            passwordFallback = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef"),
            poolName = "settlement-facts"
        ),
        names = PostgresSettlementSqlNames(schema)
    )
}
