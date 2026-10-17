package com.reef.platform.api

import java.util.ServiceLoader

internal fun validateBoundaryDeploymentModes(lookup: (String) -> String? = System::getenv) {
    if (isLocalBoundaryProfile(lookup)) return

    val missingExplicit = listOf(
        "EXTERNAL_API_AUTH_MODE",
        "EXTERNAL_API_RATE_LIMIT_MODE",
        "EXTERNAL_API_IDEMPOTENCY_STORE",
        "PLATFORM_INTERNAL_HTTP_MODE"
    ).filter { lookup(it).isNullOrBlank() }
    require(missingExplicit.isEmpty()) {
        "non-local runtime requires explicit boundary modes: ${missingExplicit.joinToString(",")}"
    }

    val authMode = lookup("EXTERNAL_API_AUTH_MODE").orEmpty().lowercase()
    val rateMode = lookup("EXTERNAL_API_RATE_LIMIT_MODE").orEmpty().lowercase()
    val idempotencyMode = lookup("EXTERNAL_API_IDEMPOTENCY_STORE").orEmpty().lowercase()
    val internalHttpMode = lookup("PLATFORM_INTERNAL_HTTP_MODE").orEmpty().lowercase()

    require(authMode != "allow-all") { "non-local runtime requires EXTERNAL_API_AUTH_MODE other than allow-all" }
    require(rateMode != "allow-all") { "non-local runtime requires EXTERNAL_API_RATE_LIMIT_MODE other than allow-all" }
    require(idempotencyMode == "postgres") { "non-local runtime requires EXTERNAL_API_IDEMPOTENCY_STORE=postgres" }
    require(internalHttpMode in setOf("disabled", "off", "false", "0", "local", "local-only", "loopback")) {
        "non-local runtime must not expose raw internal HTTP routes externally"
    }
    if (authMode == "static-token") {
        val unscopedClients = parseStaticTokenClientConfigs(lookup("EXTERNAL_API_TOKENS"))
            .filterValues { !it.principal.hasOrderMutationScopes() }
            .keys
            .sorted()
        require(unscopedClients.isEmpty()) {
            "non-local runtime requires actor, participant, and account scoped EXTERNAL_API_TOKENS entries for clients: ${unscopedClients.joinToString(",")}"
        }
    }
}

private fun isLocalBoundaryProfile(lookup: (String) -> String?): Boolean {
    val profile = listOf(
        "EXTERNAL_API_DEPLOYMENT_PROFILE",
        "REEF_ENV",
        "REEF_DEPLOYMENT_ENV",
        "DEPLOYMENT_ENV",
        "ENVIRONMENT",
        "APP_ENV",
        "PROFILE"
    ).firstNotNullOfOrNull { key -> lookup(key)?.trim()?.takeIf { it.isNotBlank() } }
        ?.lowercase()
        ?: return true
    return profile in setOf("local", "dev", "development", "test", "ci")
}

fun defaultBoundaryHooks(
    lookup: (String) -> String? = System::getenv,
    accountRiskExtensions: List<AccountRiskCheckExtension> = emptyList()
): BoundaryHooks {
    val commandProcessingMode = CommandProcessingMode.fromEnv(lookup)
    validateBoundaryDeploymentModes(lookup)
    val authMode = (lookup("EXTERNAL_API_AUTH_MODE") ?: "allow-all").lowercase()
    val authHook = when (authMode) {
        "static-token" -> StaticTokenAuthHook(parseStaticTokenClientConfigs(lookup("EXTERNAL_API_TOKENS")))
        else -> AllowAllAuthHook()
    }

    val rateMode = (lookup("EXTERNAL_API_RATE_LIMIT_MODE") ?: "allow-all").lowercase()
    val rateLimitHook = when (rateMode) {
        "fixed-window" -> {
            val max = lookup("EXTERNAL_API_RATE_LIMIT_MAX")?.toIntOrNull() ?: 120
            val window = lookup("EXTERNAL_API_RATE_LIMIT_WINDOW_SECONDS")?.toLongOrNull() ?: 60L
            FixedWindowRateLimitHook(InMemoryRateLimitStore(), max, window)
        }
        else -> AllowAllRateLimitHook()
    }

    val abuseMode = (lookup("EXTERNAL_API_ABUSE_BREAKER_MODE") ?: "off").lowercase()
    val abuseProtectionHook = when (abuseMode) {
        "reject-rate" -> {
            val enabled = envBool(lookup("EXTERNAL_API_ABUSE_BREAKER_ENABLED"), true)
            val rejectRateEnabled = envBool(lookup("EXTERNAL_API_ABUSE_BREAKER_REJECT_RATE_ENABLED"), true)
            if (!enabled || !rejectRateEnabled) {
                AllowAllAbuseProtectionHook()
            } else {
                val maxRejects = lookup("EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS")?.toIntOrNull() ?: 50
                val windowSeconds = lookup("EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS")?.toLongOrNull() ?: 30L
                val blockSeconds = lookup("EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS")?.toLongOrNull() ?: 60L
                val warningOnly = envBool(lookup("EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY"), false)
                val codes = parseRejectCodes(lookup("EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES"))
                val routes = parseTrackedRoutes(lookup("EXTERNAL_API_ABUSE_BREAKER_ROUTES"))
                val routePolicies = parseRoutePolicies(lookup("EXTERNAL_API_ABUSE_BREAKER_ROUTE_POLICIES"))
                RejectRateAbuseProtectionHook(
                    maxRejects = maxRejects,
                    windowSeconds = windowSeconds,
                    blockSeconds = blockSeconds,
                    trackedRejectCodes = codes,
                    trackedRoutes = routes,
                    routePolicies = routePolicies,
                    warningOnly = warningOnly
                )
            }
        }
        else -> AllowAllAbuseProtectionHook()
    }

    val accountRiskMode = (lookup("EXTERNAL_API_ACCOUNT_RISK_CHECK_MODE") ?: "allow-all").lowercase()
    val baseAccountRiskCheck = when (accountRiskMode) {
        "static", "cached-static" -> StaticAccountRiskCheck(
            rejectedAccounts = parseCsvSet(lookup("EXTERNAL_API_ACCOUNT_RISK_REJECT_ACCOUNTS")),
            backpressuredAccounts = parseCsvSet(lookup("EXTERNAL_API_ACCOUNT_RISK_BACKPRESSURE_ACCOUNTS")),
            disabledBots = parseCsvSet(lookup("EXTERNAL_API_ACCOUNT_RISK_DISABLED_BOTS"))
        )
        "postgres", "cached-postgres" -> {
            val cacheTtlMillis = lookup("EXTERNAL_API_ACCOUNT_RISK_CACHE_TTL_MS")?.toLongOrNull() ?: 1_000L
            PostgresAccountRiskCheck(
                dataSource = runtimeDbDataSourceFromEnv("account-risk", lookup),
                cacheTtlMillis = cacheTtlMillis.coerceAtLeast(0L)
            )
        }
        else -> AllowAllAccountRiskCheck()
    }
    val discoveredExtensions = ServiceLoader.load(AccountRiskCheckExtensionProvider::class.java)
        .flatMap { provider -> provider.extensions(lookup) }
    val allAccountRiskExtensions = accountRiskExtensions + discoveredExtensions
    val accountRiskCheck = if (allAccountRiskExtensions.isEmpty()) {
        baseAccountRiskCheck
    } else {
        ChainedAccountRiskCheck(baseAccountRiskCheck, allAccountRiskExtensions)
    }

    val circuitBreakerMode = (lookup("EXTERNAL_API_COMMAND_CIRCUIT_BREAKER_MODE") ?: "allow-all").lowercase()
    val commandCircuitBreakerCheck = when (circuitBreakerMode) {
        "postgres", "cached-postgres" -> {
            val cacheTtlMillis = lookup("EXTERNAL_API_COMMAND_CIRCUIT_BREAKER_CACHE_TTL_MS")?.toLongOrNull() ?: 1_000L
            PostgresCommandCircuitBreakerStore(
                dataSource = runtimeDbDataSourceFromEnv("command-circuit-breaker", lookup),
                cacheTtlMillis = cacheTtlMillis.coerceAtLeast(0L)
            )
        }
        else -> AllowAllCommandCircuitBreakerCheck()
    }

    val instrumentPriceCollarMode = (lookup("EXTERNAL_API_INSTRUMENT_PRICE_COLLAR_MODE") ?: "allow-all").lowercase()
    val instrumentPriceCollarCheck = when (instrumentPriceCollarMode) {
        "postgres", "cached-postgres" -> {
            val cacheTtlMillis = lookup("EXTERNAL_API_INSTRUMENT_PRICE_COLLAR_CACHE_TTL_MS")?.toLongOrNull() ?: 1_000L
            PostgresInstrumentPriceCollarStore(
                dataSource = runtimeDbDataSourceFromEnv("instrument-price-collar", lookup),
                cacheTtlMillis = cacheTtlMillis.coerceAtLeast(0L)
            )
        }
        else -> AllowAllInstrumentPriceCollarCheck()
    }

    val idempotencyMode = (lookup("EXTERNAL_API_IDEMPOTENCY_STORE") ?: "inmemory").lowercase()
    val retentionPolicy = DefaultIdempotencyRetentionPolicy()
    val idempotencyStore = if (idempotencyMode == "postgres") {
        PostgresIdempotencyStore(
            runtimeDbDataSourceFromEnv("idempotency", lookup),
            retentionPolicy
        )
    } else {
        InMemoryIdempotencyStore()
    }

    val rejectionLogMode = (lookup("EXTERNAL_API_BOUNDARY_REJECTION_LOG") ?: "auto").lowercase()
    val boundaryRejectionLog = when {
        rejectionLogMode == "postgres" || (
            rejectionLogMode == "auto" &&
                listOf(accountRiskMode, circuitBreakerMode, instrumentPriceCollarMode).any { it == "postgres" || it == "cached-postgres" }
            ) -> {
            PostgresBoundaryRejectionLog(
                dataSource = runtimeDbDataSourceFromEnv("boundary-rejection-log", lookup)
            )
        }
        else -> NoopBoundaryRejectionLog()
    }

    return BoundaryHooks(
        authHook = authHook,
        rateLimitHook = rateLimitHook,
        abuseProtectionHook = abuseProtectionHook,
        accountRiskCheck = accountRiskCheck,
        commandCircuitBreakerCheck = commandCircuitBreakerCheck,
        instrumentPriceCollarCheck = instrumentPriceCollarCheck,
        boundaryRejectionLog = boundaryRejectionLog,
        idempotencyStore = idempotencyStore,
        idempotencyRetentionPolicy = retentionPolicy,
        commandCaptureStore = defaultCommandCaptureStore(commandProcessingMode, lookup),
        commandProcessingMode = commandProcessingMode
    )
}

fun defaultAccountRiskControlStore(): AccountRiskControlStore {
    return PostgresAccountRiskCheck(
        dataSource = runtimeDbDataSourceFromEnv("account-risk-admin"),
        cacheTtlMillis = 0L
    )
}

fun defaultCommandCircuitBreakerStore(): CommandCircuitBreakerStore {
    return PostgresCommandCircuitBreakerStore(
        dataSource = runtimeDbDataSourceFromEnv("command-circuit-breaker-admin"),
        cacheTtlMillis = 0L
    )
}

fun defaultInstrumentPriceCollarStore(): InstrumentPriceCollarStore {
    return PostgresInstrumentPriceCollarStore(
        dataSource = runtimeDbDataSourceFromEnv("instrument-price-collar-admin"),
        cacheTtlMillis = 0L
    )
}

internal fun parseStaticTokens(raw: String?): Map<String, String> {
    return parseStaticTokenClientConfigs(raw).mapValues { (_, config) -> config.token }
}

internal fun parseStaticTokenClientConfigs(raw: String?): Map<String, StaticTokenClientConfig> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(",")
        .mapNotNull { pair ->
            val parts = pair.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val clientId = parts[0].trim()
            val tokenAndScopes = parseStaticTokenAndScopes(parts[1])
            val token = tokenAndScopes.firstOrNull()?.trim().orEmpty()
            if (clientId.isBlank() || token.isBlank()) return@mapNotNull null
            val scopes = tokenAndScopes.drop(1)
            clientId to StaticTokenClientConfig(
                token = token,
                principal = ExternalApiPrincipal(
                    clientId = clientId,
                    actorIds = parseStaticTokenScope(scopes, setOf("actor", "actors", "actorid", "actorids")),
                    participantIds = parseStaticTokenScope(scopes, setOf("participant", "participants", "participantid", "participantids")),
                    accountIds = parseStaticTokenScope(scopes, setOf("account", "accounts", "accountid", "accountids")),
                    botIds = parseStaticTokenScope(scopes, setOf("bot", "bots", "botid", "botids"))
                )
            )
        }
        .toMap()
}

private fun parseStaticTokenAndScopes(raw: String): List<String> {
    if (raw.contains(";")) return raw.split(";")
    val parts = raw.split(":")
    val firstScopeIndex = parts.indexOfFirst { it.substringBefore("=").trim().isStaticTokenScopeKey() }
    if (firstScopeIndex <= 0) return listOf(raw)
    return listOf(parts.take(firstScopeIndex).joinToString(":")) + parts.drop(firstScopeIndex)
}

private fun parseStaticTokenScope(parts: List<String>, keys: Set<String>): Set<String> {
    return parts.asSequence()
        .mapNotNull { raw ->
            val pair = raw.split("=", limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val key = pair[0].trim().normalizedStaticTokenScopeKey()
            if (key !in keys) return@mapNotNull null
            pair[1]
        }
        .flatMap { value -> value.split("|").asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun String.isStaticTokenScopeKey(): Boolean {
    return normalizedStaticTokenScopeKey() in setOf(
        "actor",
        "actors",
        "actorid",
        "actorids",
        "participant",
        "participants",
        "participantid",
        "participantids",
        "account",
        "accounts",
        "accountid",
        "accountids",
        "bot",
        "bots",
        "botid",
        "botids"
    )
}

private fun String.normalizedStaticTokenScopeKey(): String {
    return trim().lowercase().replace("-", "").replace("_", "")
}

internal fun parseCsvSet(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun parseRejectCodes(raw: String?): Set<String> {
    val fallback = setOf("INVALID_STATE", "NOT_FOUND", "REFERENCE_DATA_ERROR", "VALIDATION_ERROR")
    if (raw.isNullOrBlank()) return fallback
    val values = raw.split(",")
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .toSet()
    return if (values.isEmpty()) fallback else values
}

internal fun parseTrackedRoutes(raw: String?): Set<String> {
    val fallback = setOf(
        "/api/v1/orders/submit",
        "/api/v1/orders/modify",
        "/api/v1/orders/cancel"
    )
    if (raw.isNullOrBlank()) return fallback
    val values = raw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    return if (values.isEmpty()) fallback else values
}

internal fun parseRoutePolicies(raw: String?): Map<String, RejectRatePolicy> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(",")
        .mapNotNull { entry ->
            val item = entry.trim()
            if (item.isBlank()) return@mapNotNull null
            val parts = item.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val route = parts[0].trim()
            val policyParts = parts[1].split("/", limit = 3)
            if (route.isBlank() || policyParts.size != 3) return@mapNotNull null
            val maxRejects = policyParts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val windowSeconds = policyParts[1].trim().toLongOrNull() ?: return@mapNotNull null
            val blockSeconds = policyParts[2].trim().toLongOrNull() ?: return@mapNotNull null
            if (maxRejects <= 0 || windowSeconds <= 0 || blockSeconds <= 0) return@mapNotNull null
            route to RejectRatePolicy(maxRejects, windowSeconds, blockSeconds)
        }
        .toMap()
}

internal fun envBool(raw: String?, defaultValue: Boolean): Boolean {
    if (raw.isNullOrBlank()) return defaultValue
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}
