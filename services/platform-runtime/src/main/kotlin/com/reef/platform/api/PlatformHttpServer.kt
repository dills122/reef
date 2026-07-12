package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.admin.AdminActor
import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.application.admin.AdminAuthService
import com.reef.platform.application.admin.AdminBotOwnershipCommand
import com.reef.platform.application.admin.AdminGitHubOAuthClient
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.platform.application.admin.ArenaBotRegistrationCommand
import com.reef.platform.application.admin.ArenaBotVersionRegistrationCommand
import com.reef.platform.application.admin.ArenaBotVersionDecisionCommand
import com.reef.platform.application.admin.ArenaRunEnforcementEventIngestionCommand
import com.reef.platform.application.admin.ArenaRunBotResultIngestionCommand
import com.reef.platform.application.admin.ArenaRunRegistrationCommand
import com.reef.platform.application.admin.ArenaRunStatusCommand
import com.reef.platform.application.admin.ConfiguredAdminGitHubOAuthClient
import com.reef.platform.application.admin.GitHubUserIdentity
import com.reef.platform.application.admin.PostgresAdminAuthStore
import com.reef.platform.application.admin.PostgresAdminIdentityStore
import com.reef.platform.application.analytics.BotRunPerformanceSummaryRecord
import com.reef.platform.application.analytics.InMemorySimulationRunExportStore
import com.reef.platform.application.analytics.PostgresAnalyticsSqlNames
import com.reef.platform.application.analytics.PostgresSimulationRunExportStore
import com.reef.platform.application.analytics.SimulationRunExportCommand
import com.reef.platform.application.analytics.SimulationRunExportRecord
import com.reef.platform.application.analytics.SimulationRunExportService
import com.reef.platform.application.arena.ArenaBot
import com.reef.platform.application.arena.ArenaBotVersion
import com.reef.platform.application.arena.ArenaBotVersionStatus
import com.reef.platform.application.arena.ArenaLeaderboardEntry
import com.reef.platform.application.arena.ArenaOperatorDecision
import com.reef.platform.application.arena.ArenaQualificationReport
import com.reef.platform.application.arena.ArenaRunBotResult
import com.reef.platform.application.arena.ArenaRunEnforcementEvent
import com.reef.platform.application.arena.ArenaRunBotVersionRef
import com.reef.platform.application.arena.ArenaRunRecord
import com.reef.platform.application.arena.ArenaRunStatus
import com.reef.platform.application.arena.ArenaRuntimeConfigDescriptor
import com.reef.platform.application.arena.OpenBaoBotConfigService
import com.reef.platform.application.arena.OpenBaoBotConfigServiceConfig
import com.reef.platform.application.arena.OpenBaoClientException
import com.reef.platform.application.arena.OpenBaoProvisioningConfig
import com.reef.platform.application.arena.OpenBaoProvisioningService
import com.reef.platform.application.arena.PostgresArenaBotRegistryStore
import com.reef.platform.application.settlement.DefaultPostTradePolicyVersion
import com.reef.platform.application.settlement.DefaultPostTradeProfileId
import com.reef.platform.application.settlement.InMemorySettlementFactStore
import com.reef.platform.application.settlement.PostgresSettlementFactStore
import com.reef.platform.application.settlement.PostgresSettlementSqlNames
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.SettlementBreakOpenedReason
import com.reef.platform.application.settlement.SettlementBreakOpenedReasonSecurity
import com.reef.platform.application.settlement.SettlementAffirmationAcceptedFact
import com.reef.platform.application.settlement.SettlementAllocationProposedFact
import com.reef.platform.application.settlement.SettlementAttemptStartedFact
import com.reef.platform.application.settlement.SettlementBreakOpenedFact
import com.reef.platform.application.settlement.SettlementConfirmationGeneratedFact
import com.reef.platform.application.settlement.SettlementFactBundle
import com.reef.platform.application.settlement.SettlementFactStore
import com.reef.platform.application.settlement.SettlementInstructionCreatedFact
import com.reef.platform.application.settlement.SettlementLedgerDirectionCredit
import com.reef.platform.application.settlement.SettlementLedgerDirectionDebit
import com.reef.platform.application.settlement.SettlementLedgerEntryFact
import com.reef.platform.application.settlement.SettlementLedgerEntryTypeCash
import com.reef.platform.application.settlement.SettlementLedgerEntryTypeSecurity
import com.reef.platform.application.settlement.SettlementLedgerProjection
import com.reef.platform.application.settlement.SettlementLedgerProjectionView
import com.reef.platform.application.settlement.SettlementLegOutcomeFact
import com.reef.platform.application.settlement.SettlementObligationCreatedFact
import com.reef.platform.application.settlement.SettlementObligationProjection
import com.reef.platform.application.settlement.SettlementObligationView
import com.reef.platform.application.settlement.SettlementOperatorActionFact
import com.reef.platform.application.settlement.SettlementOperatorActionForceSettle
import com.reef.platform.application.settlement.SettlementOperatorActionReverseLedgerEntry
import com.reef.platform.application.settlement.SettlementRepairPostedActionCash
import com.reef.platform.application.settlement.SettlementRepairPostedActionSecurity
import com.reef.platform.application.settlement.SettlementRepairPostedFact
import com.reef.platform.application.settlement.SettlementResolvedFact
import com.reef.platform.application.settlement.SettlementResourcePositionFact
import com.reef.platform.application.settlement.SettlementScenarioProofProjection
import com.reef.platform.application.settlement.SettlementScenarioProofView
import com.reef.platform.application.settlement.SettlementScoreProjection
import com.reef.platform.application.settlement.SettlementScoreProjectionOptions
import com.reef.platform.application.settlement.SettlementScoreProjectionView
import com.reef.platform.application.settlement.SettlementSettledFact
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.reef.platform.application.defaultRuntimePersistence
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.ProjectionStatus
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private data class CommandIntakeBackpressureSnapshot(
    val active: Long,
    val staleProcessing: Long,
    val sampledAtMs: Long
)

private data class CachedStreamCommandHealthSnapshot(
    val sampledAtMs: Long,
    val snapshot: StreamCommandHealthSnapshot
)

private data class CachedStreamCommandDrainBackpressureSnapshot(
    val sampledAtMs: Long,
    val snapshot: StreamCommandDrainBackpressureSnapshot
)

internal data class AdminGatewayRoute(
    val internalPath: String,
    val fallbackTokenFamily: String,
    val serviceTokenFamilies: Set<AdminServiceTokenFamily>
)

internal fun adminGatewayRouteFor(path: String, method: String = "POST"): AdminGatewayRoute? = when (path) {
    "/admin/v1/arena/bots" -> AdminGatewayRoute(
        "/internal/admin/arena/bots",
        "arena",
        setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/arena/bots/openbao-provision" ->
        AdminGatewayRoute(
            "/internal/admin/arena/bots/openbao-provision",
            "arena",
            setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        )
    "/admin/v1/arena/bots/ownership" -> AdminGatewayRoute(
        "/internal/admin/arena/bots/ownership",
        "arena",
        setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/arena/bots/config" -> AdminGatewayRoute(
        "/internal/admin/arena/bots/config",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/arena/bot-versions" -> AdminGatewayRoute(
        "/internal/admin/arena/bot-versions",
        "arena",
        setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/arena/bot-versions/transition" -> AdminGatewayRoute(
        "/internal/admin/arena/bot-versions/transition",
        "arena",
        setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/arena/runs" -> if (method in setOf("GET", "POST")) {
        AdminGatewayRoute(
            "/internal/admin/arena/runs",
            "arena",
            setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        )
    } else {
        null
    }
    "/admin/v1/arena/runs/status" -> if (method == "POST") {
        AdminGatewayRoute(
            "/internal/admin/arena/runs/status",
            "arena",
            setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        )
    } else {
        null
    }
    "/admin/v1/arena/run-bot-results" -> if (method in setOf("GET", "POST")) {
        AdminGatewayRoute(
            "/internal/admin/arena/run-bot-results",
            "arena",
            setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        )
    } else {
        null
    }
    "/admin/v1/arena/run-enforcement-events" -> if (method in setOf("GET", "POST")) {
        AdminGatewayRoute(
            "/internal/admin/arena/run-enforcement-events",
            "arena",
            setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        )
    } else {
        null
    }
    "/admin/v1/arena/leaderboard" -> if (method == "GET") {
        AdminGatewayRoute(
            "/internal/admin/arena/leaderboard",
            "arena",
            setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        )
    } else {
        null
    }
    "/admin/v1/analytics/run-exports" -> AdminGatewayRoute(
        "/internal/admin/analytics/run-exports",
        "analytics",
        setOf(AdminServiceTokenFamily.Sim, AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/analytics/run-bot-summaries" -> AdminGatewayRoute(
        "/internal/admin/analytics/run-bot-summaries",
        "analytics",
        setOf(AdminServiceTokenFamily.Sim, AdminServiceTokenFamily.Admin)
    )
    // GET reads the boundary's read-only mirror; POST writes through the admin
    // mutation path. Same public path, two different internal targets.
    "/admin/v1/risk/account-controls" -> AdminGatewayRoute(
        if (method == "GET") "/internal/boundary/account-risk/controls" else "/internal/admin/account-risk/controls",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/risk/circuit-breakers" -> AdminGatewayRoute(
        if (method == "GET") "/internal/boundary/circuit-breakers" else "/internal/admin/circuit-breakers",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/risk/price-collars" -> AdminGatewayRoute(
        if (method == "GET") "/internal/boundary/price-collars" else "/internal/admin/price-collars",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/settlement/facts" -> AdminGatewayRoute(
        "/internal/admin/settlement/facts",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/settlement/repairs/cash" -> AdminGatewayRoute(
        "/internal/admin/settlement/repairs/cash",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/settlement/repairs/security" -> AdminGatewayRoute(
        "/internal/admin/settlement/repairs/security",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/settlement/force-settle" -> AdminGatewayRoute(
        "/internal/admin/settlement/force-settle",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/settlement/reverse-ledger-entry" -> AdminGatewayRoute(
        "/internal/admin/settlement/reverse-ledger-entry",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    "/admin/v1/settlement/obligations/materialize" -> AdminGatewayRoute(
        "/internal/admin/settlement/obligations/materialize",
        "admin",
        setOf(AdminServiceTokenFamily.Admin)
    )
    else -> null
}

private data class PreparedApiV1Mutation(
    val route: String,
    val clientId: String,
    val idempotencyKey: String,
    val correlationId: String,
    val body: String,
    val parsedBody: JsonDocument
)

private sealed class PreparedApiV1MutationResult {
    data class Prepared(val request: PreparedApiV1Mutation) : PreparedApiV1MutationResult()
    data class Rejected(val response: PlatformHotPathResponse) : PreparedApiV1MutationResult()
}

// AdminRequestPrincipal, InternalHttpExposureMode, and admin session/cookie/OAuth
// handling live in AdminSessionAuth.kt (see adminSessionAuth field below).
private val localDeploymentProfiles = setOf("", "local", "dev", "development", "test", "ci")

// adminBotConfigPrivilegedRoles moved to ArenaAdminGateway.kt (its only user).

internal val apiV1OrderMutationRoutes = setOf(
    "/api/v1/orders/submit",
    "/api/v1/orders/modify",
    "/api/v1/orders/cancel"
)

private fun streamCommandPublicationMarker(
    store: StreamCommandIntakeStore?,
    mode: String,
    runtimeRole: PlatformRuntimeRole
): StreamCommandPublicationMarker? {
    val marker = store ?: return null
    return when (mode.trim().lowercase()) {
        "async" -> if (runtimeRole == PlatformRuntimeRole.Api) AsyncStreamCommandPublicationMarker(marker) else marker
        "worker", "defer-to-worker", "worker-only" -> if (runtimeRole == PlatformRuntimeRole.Api) null else marker
        "none", "disabled" -> null
        else -> marker
    }
}

class PlatformHttpServer(
    private val port: Int = RuntimeEnv.int("PLATFORM_RUNTIME_PORT", 8080),
    private val runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.fromEnv(),
    private val api: PlatformApi = PlatformApi(),
    private val boundary: ExternalApiBoundary,
    private val abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
    private val accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
    private val accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
    private val accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
    private val commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
    private val commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
    private val instrumentPriceCollarCheck: InstrumentPriceCollarCheck = AllowAllInstrumentPriceCollarCheck(),
    private val instrumentPriceCollarStore: InstrumentPriceCollarStore? = instrumentPriceCollarCheck as? InstrumentPriceCollarStore,
    private val arenaAdminService: AdminApplicationService? = null,
    private val adminAuthService: AdminAuthService? = null,
    private val adminIdentityService: AdminIdentityService? = null,
    private val adminGitHubOAuthClient: AdminGitHubOAuthClient? = null,
    private val analyticsRunExportService: SimulationRunExportService? = null,
    private val settlementFactStore: SettlementFactStore? = null,
    private val settlementObligationMaterializer: TradeSettlementObligationMaterializer? = null,
    private val defaultPostTradeProfileId: String =
        RuntimeEnv.string("POST_TRADE_PROFILE", DefaultPostTradeProfileId).trim().ifBlank { DefaultPostTradeProfileId },
    private val defaultPostTradePolicyVersion: Int =
        RuntimeEnv.int("POST_TRADE_POLICY_VERSION", DefaultPostTradePolicyVersion, min = 1),
    private val postTradeProfileResolver: PostTradeProfileResolver =
        PostTradeProfileResolver.envOnly(defaultPostTradeProfileId, defaultPostTradePolicyVersion),
    private val scenarioRunPostTradeProfileLookup: (String) -> String? = { null },
    private val venueSessionPostTradeProfileLookup: (String) -> String? = { null },
    private val boundaryRejectionLog: BoundaryRejectionLog = NoopBoundaryRejectionLog(),
    private val idempotencyStore: IdempotencyStore,
    private val idempotencyRetentionPolicy: IdempotencyRetentionPolicy,
    private val commandCaptureStore: CommandCaptureStore = NoopCommandCaptureStore(),
    private val commandStatusLookup: CommandStatusLookup? = commandCaptureStore as? CommandStatusLookup,
    private val capturedCommandQueue: CapturedCommandQueue? = commandCaptureStore as? CapturedCommandQueue,
    private val streamCommandIntakeStore: StreamCommandIntakeStore? = null,
    private val streamCommandPublisher: StreamCommandPublisher? = null,
    private val streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
    private val streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
    private val streamCommandMarkPublishedMode: String = RuntimeEnv.string("STREAM_ACK_MARK_PUBLISHED_MODE", "sync"),
    private val streamCommandMaxStorageUtilization: Double =
        RuntimeEnv.string("STREAM_ACK_MAX_STORAGE_UTILIZATION", "0.95").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.95,
    private val streamCommandBackpressureSampleMs: Long =
        RuntimeEnv.long("STREAM_ACK_BACKPRESSURE_SAMPLE_MS", 100L, min = 0L),
    private val streamCommandMaxWorkerStreamLag: Long =
        RuntimeEnv.long("STREAM_ACK_MAX_WORKER_STREAM_LAG", 0L, min = 0L),
    private val streamCommandMaxProjectorLag: Long =
        RuntimeEnv.long("STREAM_ACK_MAX_PROJECTOR_LAG", 0L, min = 0L),
    private val streamCommandDrainBackpressurePolicy: StreamCommandDrainBackpressurePolicy =
        StreamCommandDrainBackpressurePolicy.fromConfig(
            RuntimeEnv.string(
                "STREAM_ACK_DRAIN_BACKPRESSURE_POLICY",
                StreamCommandDrainBackpressurePolicy.ControlRoomFresh.configValue
            )
        ),
    private val streamCommandDrainBackpressureSampleMs: Long =
        RuntimeEnv.long("STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS", 500L, min = 0L),
    private val streamCommandBackpressureWorkerDurables: String =
        RuntimeEnv.string("STREAM_ACK_BACKPRESSURE_WORKER_DURABLES", ""),
    private val streamCommandPublishResponseTimeoutMs: Long =
        RuntimeEnv.long(
            "STREAM_ACK_PUBLISH_RESPONSE_TIMEOUT_MS",
            RuntimeEnv.long("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", 2_000L, min = 1L),
            min = 1L
        ),
    private val streamCommandWorkerEnabled: Boolean = RuntimeEnv.bool("STREAM_ACK_WORKER_ENABLED", false),
    private val streamCommandWorkerPartitions: String = RuntimeEnv.string("STREAM_ACK_WORKER_PARTITIONS", "0"),
    private val streamCommandWorkerBatchSize: Int = RuntimeEnv.int("STREAM_ACK_WORKER_BATCH_SIZE", 100, min = 1),
    private val streamCommandWorkerPollMs: Long = RuntimeEnv.long("STREAM_ACK_WORKER_POLL_MS", 25L, min = 1L),
    private val streamCommandWorkerFetchTimeoutMs: Long = RuntimeEnv.long("STREAM_ACK_WORKER_FETCH_TIMEOUT_MS", 200L, min = 1L),
    private val streamCommandWorkerDedicatedRuntimePoolEnabled: Boolean =
        RuntimeEnv.bool("STREAM_ACK_WORKER_DEDICATED_RUNTIME_POOL_ENABLED", false),
    private val streamAckProjectorEnabled: Boolean = RuntimeEnv.bool("STREAM_ACK_PROJECTOR_ENABLED", true),
    private val streamAckProjectionName: String = RuntimeEnv.string("STREAM_ACK_PROJECTION_NAME", "runtime-normalized-submit"),
    private val streamAckProjectionSource: CanonicalProjectionSource =
        CanonicalProjectionSource.fromConfig(RuntimeEnv.string("STREAM_ACK_PROJECTION_SOURCE", CanonicalProjectionSource.CanonicalSubmit.configValue)),
    private val streamAckProjectionEventStream: String = RuntimeEnv.string("STREAM_ACK_PROJECTION_EVENT_STREAM", ""),
    private val streamAckProjectorPartitions: String = RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", "all"),
    private val streamAckProjectorBatchSize: Int = RuntimeEnv.int("STREAM_ACK_PROJECTOR_BATCH_SIZE", 250, min = 1),
    private val streamAckProjectorPollMs: Long = RuntimeEnv.long("STREAM_ACK_PROJECTOR_POLL_MS", 50L, min = 1L),
    private val venueEventMaterializerEnabled: Boolean = RuntimeEnv.bool("VENUE_EVENT_MATERIALIZER_ENABLED", false),
    private val venueEventMaterializerBatchSize: Int = RuntimeEnv.int("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", 100, min = 1),
    private val venueEventMaterializerPollMs: Long = RuntimeEnv.long("VENUE_EVENT_MATERIALIZER_POLL_MS", 25L, min = 1L),
    private val venueEventMaterializerFetchTimeoutMs: Long =
        RuntimeEnv.long("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", 200L, min = 1L),
    private val marketDataProjectorEnabled: Boolean = RuntimeEnv.bool("MARKET_DATA_PROJECTOR_ENABLED", false),
    private val marketDataProjectorProjectionName: String =
        RuntimeEnv.string("MARKET_DATA_PROJECTOR_PROJECTION_NAME", "market-data-top-of-book"),
    private val marketDataProjectorSourceProjectionName: String =
        RuntimeEnv.string("MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME", "runtime-normalized-venue-outcomes"),
    private val marketDataProjectorPollMs: Long =
        RuntimeEnv.long("MARKET_DATA_PROJECTOR_POLL_MS", 250L, min = 1L),
    private val marketDataProjectorBatchSize: Int =
        RuntimeEnv.int("MARKET_DATA_PROJECTOR_BATCH_SIZE", 500, min = 1),
    private val orderLifecycleProjectorEnabled: Boolean = RuntimeEnv.bool("ORDER_LIFECYCLE_PROJECTOR_ENABLED", false),
    private val orderLifecycleProjectorPollMs: Long =
        RuntimeEnv.long("ORDER_LIFECYCLE_PROJECTOR_POLL_MS", 250L, min = 1L),
    private val orderLifecycleProjectorBatchSize: Int =
        RuntimeEnv.int("ORDER_LIFECYCLE_PROJECTOR_BATCH_SIZE", 500, min = 1),
    private val commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
    private val asyncCommandWorkerEnabled: Boolean = RuntimeEnv.bool("EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED", false),
    private val asyncCommandWorkerThreads: Int = RuntimeEnv.int("EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS", 1, min = 1),
    private val asyncCommandWorkerBatchSize: Int = RuntimeEnv.int("EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE", 100, min = 1),
    private val asyncCommandWorkerPollMs: Long = RuntimeEnv.long("EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS", 25L),
    private val asyncCommandWorkerDedicatedRuntimePoolEnabled: Boolean =
        RuntimeEnv.bool("EXTERNAL_API_COMMAND_ASYNC_WORKER_DEDICATED_RUNTIME_POOL_ENABLED", false),
    private val commandIntakeMaxActive: Long = RuntimeEnv.long("EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS", 0L, min = 0L),
    private val commandIntakeMaxStaleProcessing: Long = RuntimeEnv.long("EXTERNAL_API_COMMAND_INTAKE_MAX_STALE_PROCESSING", 0L, min = 0L),
    private val commandIntakeBackpressureSampleMs: Long =
        RuntimeEnv.long("EXTERNAL_API_COMMAND_INTAKE_BACKPRESSURE_SAMPLE_MS", 100L, min = 0L),
    private val legacyMutationRoutesEnabled: Boolean = RuntimeEnv.bool("PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED", false),
    private val localDevAdminAuthBypass: Boolean = localDevAdminAuthBypassEnabled()
) {
    companion object {
        private val streamPublishTimeoutExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "reef-stream-publish-timeout").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }
        private val streamPublishFailureLogCount = AtomicLong(0L)
        private val streamPublishFailureLastLogMs = AtomicLong(0L)
    }

    private val maxRequestBodyBytes: Int =
        RuntimeEnv.int("PLATFORM_HTTP_MAX_REQUEST_BYTES", 1024 * 1024, min = 1024)
    private val internalHttpExposureMode: InternalHttpExposureMode = InternalHttpExposureMode.fromEnv()
    private val adminSessionCookieName: String =
        RuntimeEnv.string("ADMIN_SESSION_COOKIE_NAME", "reef_admin_session").trim().ifBlank { "reef_admin_session" }
    private val adminSessionCookieSecure: Boolean = RuntimeEnv.bool("ADMIN_SESSION_COOKIE_SECURE", true)
    private val adminSessionAuth = AdminSessionAuth(
        arenaAdminService = arenaAdminService,
        adminAuthService = adminAuthService,
        adminIdentityService = adminIdentityService,
        adminGitHubOAuthClient = adminGitHubOAuthClient,
        adminSessionCookieName = adminSessionCookieName,
        adminSessionCookieSecure = adminSessionCookieSecure,
        localDevAdminAuthBypass = localDevAdminAuthBypass,
        internalHttpExposureMode = internalHttpExposureMode
    )
    private val settlementAdminGateway = SettlementAdminGateway(
        settlementFactStore = settlementFactStore,
        settlementObligationMaterializer = settlementObligationMaterializer,
        postTradeProfileResolver = postTradeProfileResolver,
        scenarioRunPostTradeProfileLookup = scenarioRunPostTradeProfileLookup,
        venueSessionPostTradeProfileLookup = venueSessionPostTradeProfileLookup,
        adminSessionAuth = adminSessionAuth
    )
    private val arenaAdminGateway = ArenaAdminGateway(
        arenaAdminService = arenaAdminService,
        adminIdentityService = adminIdentityService,
        analyticsRunExportService = analyticsRunExportService,
        adminSessionAuth = adminSessionAuth
    )
    private val riskGuardrailGateway = RiskGuardrailGateway(
        api = api,
        accountRiskControlStore = accountRiskControlStore,
        accountRiskDecisionLog = accountRiskDecisionLog,
        commandCircuitBreakerStore = commandCircuitBreakerStore,
        instrumentPriceCollarStore = instrumentPriceCollarStore,
        adminSessionAuth = adminSessionAuth
    )
    @Volatile
    private var backpressureSnapshot: CommandIntakeBackpressureSnapshot? = null
    private val backpressureSnapshotLock = Any()
    @Volatile
    private var streamBackpressureSnapshot: CachedStreamCommandHealthSnapshot? = null
    private val streamBackpressureSnapshotLock = Any()
    @Volatile
    private var streamDrainBackpressureSnapshot: CachedStreamCommandDrainBackpressureSnapshot? = null
    private val streamDrainBackpressureSamplerStarted = AtomicBoolean(false)
    private val streamCommandPublicationMarker: StreamCommandPublicationMarker? =
        streamCommandPublicationMarker(streamCommandIntakeStore, streamCommandMarkPublishedMode, runtimeRole)
    private val acceptedAsyncCommandIntake: AcceptedAsyncCommandIntake? =
        if (runtimeRole.publicHttpEnabled && commandProcessingMode == CommandProcessingMode.AcceptedAsync) {
            AcceptedAsyncCommandIntake(api)
        } else {
            null
        }
    private val runtimeLoopStarter = RuntimeLoopStarter(
        api = api,
        runtimeRole = runtimeRole,
        commandProcessingMode = commandProcessingMode,
        streamCommandConfig = streamCommandConfig,
        streamCommandIntakeStore = streamCommandIntakeStore,
        streamCommandWorkerBatchSize = streamCommandWorkerBatchSize,
        streamCommandWorkerPollMs = streamCommandWorkerPollMs,
        streamCommandWorkerFetchTimeoutMs = streamCommandWorkerFetchTimeoutMs,
        streamCommandWorkerDedicatedRuntimePoolEnabled = streamCommandWorkerDedicatedRuntimePoolEnabled,
        streamCommandWorkerPartitions = streamCommandWorkerPartitions,
        streamAckProjectorPartitions = streamAckProjectorPartitions,
        streamAckProjectionName = streamAckProjectionName,
        streamAckProjectionSource = streamAckProjectionSource,
        streamAckProjectionEventStream = streamAckProjectionEventStream,
        streamAckProjectorBatchSize = streamAckProjectorBatchSize,
        streamAckProjectorPollMs = streamAckProjectorPollMs,
        marketDataProjectorEnabled = marketDataProjectorEnabled,
        marketDataProjectorProjectionName = marketDataProjectorProjectionName,
        marketDataProjectorSourceProjectionName = marketDataProjectorSourceProjectionName,
        marketDataProjectorPollMs = marketDataProjectorPollMs,
        marketDataProjectorBatchSize = marketDataProjectorBatchSize,
        orderLifecycleProjectorEnabled = orderLifecycleProjectorEnabled,
        orderLifecycleProjectorPollMs = orderLifecycleProjectorPollMs,
        orderLifecycleProjectorBatchSize = orderLifecycleProjectorBatchSize,
        venueEventMaterializerEnabled = venueEventMaterializerEnabled,
        venueEventMaterializerBatchSize = venueEventMaterializerBatchSize,
        venueEventMaterializerPollMs = venueEventMaterializerPollMs,
        venueEventMaterializerFetchTimeoutMs = venueEventMaterializerFetchTimeoutMs
    )
    private val diagnosticsGateway = DiagnosticsGateway(
        runtimeRole = runtimeRole,
        commandProcessingMode = commandProcessingMode,
        acceptedAsyncCommandIntake = acceptedAsyncCommandIntake,
        capturedCommandQueue = capturedCommandQueue,
        asyncCommandWorkerEnabled = asyncCommandWorkerEnabled,
        asyncCommandWorkerThreads = asyncCommandWorkerThreads,
        asyncCommandWorkerBatchSize = asyncCommandWorkerBatchSize,
        asyncCommandWorkerPollMs = asyncCommandWorkerPollMs,
        commandIntakeMaxActive = commandIntakeMaxActive,
        commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
        commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
        streamCommandHealthCheck = streamCommandHealthCheck,
        streamCommandConfig = streamCommandConfig,
        streamCommandMaxStorageUtilization = streamCommandMaxStorageUtilization,
        streamCommandBackpressureSampleMs = streamCommandBackpressureSampleMs,
        streamCommandDrainBackpressurePolicy = streamCommandDrainBackpressurePolicy,
        streamCommandMaxWorkerStreamLag = streamCommandMaxWorkerStreamLag,
        streamCommandMaxProjectorLag = streamCommandMaxProjectorLag,
        streamCommandDrainBackpressureSampleMs = streamCommandDrainBackpressureSampleMs,
        streamCommandMarkPublishedMode = streamCommandMarkPublishedMode,
        streamCommandBackpressureWorkerDurables = streamCommandBackpressureWorkerDurables,
        streamCommandWorkerEnabled = streamCommandWorkerEnabled,
        streamCommandWorkerBatchSize = streamCommandWorkerBatchSize,
        streamCommandWorkerPollMs = streamCommandWorkerPollMs,
        streamCommandWorkerFetchTimeoutMs = streamCommandWorkerFetchTimeoutMs,
        streamCommandWorkerDedicatedRuntimePoolEnabled = streamCommandWorkerDedicatedRuntimePoolEnabled,
        venueEventMaterializerShouldStart = { runtimeLoopStarter.venueEventMaterializerShouldStart() },
        venueEventMaterializerBatchSize = venueEventMaterializerBatchSize,
        venueEventMaterializerPollMs = venueEventMaterializerPollMs,
        venueEventMaterializerFetchTimeoutMs = venueEventMaterializerFetchTimeoutMs,
        marketDataProjectorShouldStart = { runtimeLoopStarter.marketDataProjectorShouldStart() },
        marketDataProjectorProjectionName = marketDataProjectorProjectionName,
        marketDataProjectorSourceProjectionName = marketDataProjectorSourceProjectionName,
        marketDataProjectorPollMs = marketDataProjectorPollMs,
        marketDataProjectorBatchSize = marketDataProjectorBatchSize,
        orderLifecycleProjectorShouldStart = { runtimeLoopStarter.orderLifecycleProjectorShouldStart() },
        orderLifecycleProjectorPollMs = orderLifecycleProjectorPollMs,
        orderLifecycleProjectorBatchSize = orderLifecycleProjectorBatchSize,
        streamWorkerPartitions = { runtimeLoopStarter.streamWorkerPartitions() },
        api = api,
        streamAckProjectorEnabled = streamAckProjectorEnabled,
        streamAckProjectionName = streamAckProjectionName,
        streamAckProjectionSource = streamAckProjectionSource,
        streamAckProjectionEventStream = streamAckProjectionEventStream,
        projectorPartitions = { runtimeLoopStarter.projectorPartitions() }
    )
    private val streamCommandDrainBackpressureSampler: StreamCommandDrainBackpressureSampler? by lazy {
        buildStreamCommandDrainBackpressureSampler()
    }
    private val streamIngressSubmitHandler: StreamIngressSubmitHandler by lazy {
        StreamIngressSubmitHandler(
            maxRequestBodyBytes = maxRequestBodyBytes,
            commandProcessingMode = commandProcessingMode
        ) { route, clientId, idempotencyKey, correlationId, body ->
            handleStreamAckMutationResponse(route, clientId, idempotencyKey, correlationId, body)
        }
    }
    private val legacySetupRoutes: PlatformLegacySetupRoutes by lazy {
        PlatformLegacySetupRoutes(
            maxRequestBodyBytes = maxRequestBodyBytes,
            legacyMutationRoutesEnabled = legacyMutationRoutesEnabled,
            createInstrument = { body -> api.createInstrument(body) },
            instruments = { api.instruments() },
            createParticipant = { body -> api.createParticipant(body) },
            participants = { api.participants() },
            createAccount = { body -> api.createAccount(body) },
            accounts = { api.accounts() },
            createRole = { body -> api.createRole(body) },
            roles = { api.roles() },
            assignRole = { body -> api.assignRole(body) },
            actorRoles = { actorId -> api.actorRoles(actorId) }
        )
    }
    private val adminDataRoutes: PlatformAdminDataRoutes by lazy {
        PlatformAdminDataRoutes(
            arenaAdminGateway = arenaAdminGateway,
            settlementAdminGateway = settlementAdminGateway,
            healthJson = { api.health() },
            readinessJson = { readinessJson() },
            abuseStatsJson = { riskGuardrailGateway.abuseStatsJson(abuseProtectionHook.stats()) },
            accountRiskControlsJson = { riskGuardrailGateway.accountRiskControlsJson() },
            accountRiskDecisionsJson = { limit -> riskGuardrailGateway.accountRiskDecisionsJson(limit) },
            commandCircuitBreakersJson = { riskGuardrailGateway.commandCircuitBreakersJson() },
            instrumentPriceCollarsJson = { riskGuardrailGateway.instrumentPriceCollarsJson() },
            setAccountRiskControlJson = { body -> riskGuardrailGateway.setAccountRiskControlResponse(body) },
            setCommandCircuitBreakerJson = { body -> riskGuardrailGateway.setCommandCircuitBreakerResponse(body) },
            setInstrumentPriceCollarJson = { body -> riskGuardrailGateway.setInstrumentPriceCollarResponse(body) },
            dbPoolStatsJson = { diagnosticsGateway.dbPoolStatsJson() },
            asyncCommandStatsJson = { diagnosticsGateway.asyncCommandStatsJson() },
            commandAccountingJson = { runId -> diagnosticsGateway.commandAccountingJson(runId) },
            streamCommandHealthJson = { diagnosticsGateway.streamCommandHealthJson() },
            streamCommandWorkerStatsJson = { diagnosticsGateway.streamCommandWorkerStatsJson() },
            venueEventMaterializerStatsJson = { diagnosticsGateway.venueEventMaterializerStatsJson() },
            projectorStatusJson = { diagnosticsGateway.projectorStatusJson() },
            marketDataProjectorStatsJson = { diagnosticsGateway.marketDataProjectorStatusJson() },
            orderLifecycleProjectorStatsJson = { diagnosticsGateway.orderLifecycleProjectorStatusJson() }
        )
    }

    constructor(
        port: Int = RuntimeEnv.int("PLATFORM_RUNTIME_PORT", 8080),
        api: PlatformApi = PlatformApi(),
        deps: ServerBoundaryDeps = defaultBoundary()
    ) : this(
        port = port,
        api = api,
        boundary = deps.boundary,
        abuseProtectionHook = deps.abuseProtectionHook,
        accountRiskCheck = deps.accountRiskCheck,
        accountRiskControlStore = deps.accountRiskControlStore,
        accountRiskDecisionLog = deps.accountRiskDecisionLog,
        commandCircuitBreakerCheck = deps.commandCircuitBreakerCheck,
        commandCircuitBreakerStore = deps.commandCircuitBreakerStore,
        instrumentPriceCollarCheck = deps.instrumentPriceCollarCheck,
        instrumentPriceCollarStore = deps.instrumentPriceCollarStore,
        arenaAdminService = deps.arenaAdminService,
        adminAuthService = deps.adminAuthService,
        adminIdentityService = deps.adminIdentityService,
        adminGitHubOAuthClient = deps.adminGitHubOAuthClient,
        analyticsRunExportService = deps.analyticsRunExportService,
        settlementFactStore = deps.settlementFactStore,
        settlementObligationMaterializer = deps.settlementObligationMaterializer,
        postTradeProfileResolver = deps.postTradeProfileResolver,
        scenarioRunPostTradeProfileLookup = deps.scenarioRunPostTradeProfileLookup,
        venueSessionPostTradeProfileLookup = deps.venueSessionPostTradeProfileLookup,
        boundaryRejectionLog = deps.boundaryRejectionLog,
        idempotencyStore = deps.idempotencyStore,
        idempotencyRetentionPolicy = deps.idempotencyRetentionPolicy,
        commandCaptureStore = deps.commandCaptureStore,
        commandStatusLookup = deps.commandStatusLookup,
        capturedCommandQueue = deps.capturedCommandQueue,
        streamCommandIntakeStore = deps.streamCommandIntakeStore,
        streamCommandPublisher = deps.streamCommandPublisher,
        streamCommandHealthCheck = deps.streamCommandHealthCheck,
        streamCommandConfig = deps.streamCommandConfig,
        streamCommandMarkPublishedMode = deps.streamCommandMarkPublishedMode,
        streamCommandMaxStorageUtilization = deps.streamCommandMaxStorageUtilization,
        streamCommandBackpressureSampleMs = deps.streamCommandBackpressureSampleMs,
        streamCommandMaxWorkerStreamLag = deps.streamCommandMaxWorkerStreamLag,
        streamCommandMaxProjectorLag = deps.streamCommandMaxProjectorLag,
        streamCommandDrainBackpressurePolicy = deps.streamCommandDrainBackpressurePolicy,
        streamCommandDrainBackpressureSampleMs = deps.streamCommandDrainBackpressureSampleMs,
        streamCommandBackpressureWorkerDurables = deps.streamCommandBackpressureWorkerDurables,
        commandProcessingMode = deps.commandProcessingMode
    )

    fun start(): HttpServer {
        val backlog = RuntimeEnv.int("PLATFORM_HTTP_BACKLOG", 1024, min = 64)
        val server = HttpServer.create(InetSocketAddress(port), backlog)
        val workerThreads = RuntimeEnv.int("PLATFORM_HTTP_THREADS", 32, min = 4)
        server.executor = Executors.newFixedThreadPool(workerThreads)

        registerDiagnosticRoutes(server)
        registerAdminAuthRoutes(server)

        server.createContext("/internal/admin/settlement/facts") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, settlementAdminGateway.appendSettlementFactsResponse(body))
            }
        }

        server.createContext("/internal/admin/settlement/repairs/cash") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, settlementAdminGateway.postCashSettlementRepairResponse(body))
            }
        }

        server.createContext("/internal/admin/settlement/repairs/security") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, settlementAdminGateway.postSecuritySettlementRepairResponse(body))
            }
        }

        server.createContext("/internal/admin/settlement/force-settle") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, settlementAdminGateway.forceSettleResponse(body))
            }
        }

        server.createContext("/internal/admin/settlement/reverse-ledger-entry") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, settlementAdminGateway.reverseSettlementLedgerEntryResponse(body))
            }
        }

        server.createContext("/internal/admin/settlement/obligations/materialize") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, settlementAdminGateway.materializeSettlementObligationsResponse(body))
            }
        }

        for (path in listOf(
            "/admin/v1/arena/bots",
            "/admin/v1/arena/bots/openbao-provision",
            "/admin/v1/arena/bots/ownership",
            "/admin/v1/arena/bots/config",
            "/admin/v1/arena/bot-versions",
            "/admin/v1/arena/bot-versions/transition",
            "/admin/v1/arena/runs",
            "/admin/v1/arena/runs/status",
            "/admin/v1/arena/run-bot-results",
            "/admin/v1/arena/run-enforcement-events",
            "/admin/v1/arena/leaderboard",
            "/admin/v1/analytics/run-exports",
            "/admin/v1/analytics/run-bot-summaries",
            "/admin/v1/risk/account-controls",
            "/admin/v1/risk/circuit-breakers",
            "/admin/v1/risk/price-collars",
            "/admin/v1/settlement/facts",
            "/admin/v1/settlement/repairs/cash",
            "/admin/v1/settlement/repairs/security",
            "/admin/v1/settlement/force-settle",
            "/admin/v1/settlement/reverse-ledger-entry",
            "/admin/v1/settlement/obligations/materialize"
        )) {
            server.createContext(path) { exchange ->
                handleAdminGatewayRoute(exchange)
            }
        }

        if (runtimeRole.publicHttpEnabled) {
        server.createContext("/api/v1/commands/") { exchange ->
            handleCommandStatusLookup(exchange)
        }

        server.createContext("/orders/submit") { exchange ->
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowLegacyMutationRoute(exchange)) return@createContext
            try {
                val body = readRequestBody(exchange) ?: return@createContext
                writeJson(exchange, 200, api.submitOrder(body))
            } catch (ex: Exception) {
                writeJson(exchange, 503, runtimeUnavailableJson(ex))
            }
        }

        server.createContext("/api/v1/orders/submit") { exchange ->
            handleApiV1Mutation(exchange, "/api/v1/orders/submit") { body ->
                api.submitOrder(body)
            }
        }

        server.createContext("/reference/instruments") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    val body = readRequestBody(exchange) ?: return@createContext
                    writeJson(exchange, 200, api.createInstrument(body))
                }
                "GET" -> writeJson(exchange, 200, api.instruments())
                else -> methodNotAllowed(exchange)
            }
        }

        server.createContext("/reference/participants") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    val body = readRequestBody(exchange) ?: return@createContext
                    writeJson(exchange, 200, api.createParticipant(body))
                }
                "GET" -> writeJson(exchange, 200, api.participants())
                else -> methodNotAllowed(exchange)
            }
        }

        server.createContext("/reference/accounts") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    val body = readRequestBody(exchange) ?: return@createContext
                    writeJson(exchange, 200, api.createAccount(body))
                }
                "GET" -> writeJson(exchange, 200, api.accounts())
                else -> methodNotAllowed(exchange)
            }
        }

        server.createContext("/auth/roles") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    val body = readRequestBody(exchange) ?: return@createContext
                    writeJson(exchange, 200, api.createRole(body))
                }
                "GET" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    writeJson(exchange, 200, api.roles())
                }
                else -> methodNotAllowed(exchange)
            }
        }

        server.createContext("/auth/actor-roles") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    val body = readRequestBody(exchange) ?: return@createContext
                    writeJson(exchange, 200, api.assignRole(body))
                }
                "GET" -> {
                    if (!allowLegacyMutationRoute(exchange)) return@createContext
                    val actorId = queryValue(exchange, "actorId")
                    writeJson(exchange, 200, api.actorRoles(actorId))
                }
                else -> methodNotAllowed(exchange)
            }
        }

        server.createContext("/orders/cancel") { exchange ->
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowLegacyMutationRoute(exchange)) return@createContext
            try {
                val body = readRequestBody(exchange) ?: return@createContext
                writeJson(exchange, 200, api.cancelOrder(body))
            } catch (ex: Exception) {
                writeJson(exchange, 503, runtimeUnavailableJson(ex))
            }
        }

        server.createContext("/api/v1/orders/cancel") { exchange ->
            handleApiV1Mutation(exchange, "/api/v1/orders/cancel") { body ->
                api.cancelOrder(body)
            }
        }

        server.createContext("/api/v1/orders/cancel-by-client-order") { exchange ->
            handleCancelByClientOrder(exchange)
        }

        server.createContext("/orders/modify") { exchange ->
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowLegacyMutationRoute(exchange)) return@createContext
            try {
                val body = readRequestBody(exchange) ?: return@createContext
                writeJson(exchange, 200, api.modifyOrder(body))
            } catch (ex: Exception) {
                writeJson(exchange, 503, runtimeUnavailableJson(ex))
            }
        }

        server.createContext("/api/v1/orders/modify") { exchange ->
            handleApiV1Mutation(exchange, "/api/v1/orders/modify") { body ->
                api.modifyOrder(body)
            }
        }

        server.createContext("/orders/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }

            val path = exchange.requestURI.path.removePrefix("/orders/")
            if (path.endsWith("/events")) {
                val orderId = path.removeSuffix("/events").trimEnd('/')
                writeJson(exchange, 200, api.orderEvents(orderId))
                return@createContext
            }

            val orderId = path.trimEnd('/')
            val result = api.orderWithStatus(orderId)
            writeJson(exchange, if (result.found) 200 else 404, result.body)
        }

        server.createContext("/orders") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, api.orders())
        }

        server.createContext("/api/v1/orders/lifecycle-state") { exchange ->
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, api.rebuildOrderLifecycleState())
        }

        server.createContext("/api/v1/market-data/snapshots/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowApiV1Read(exchange, "/api/v1/market-data/snapshots/{instrumentId}")) {
                return@createContext
            }
            val instrumentId = exchange.requestURI.path.removePrefix("/api/v1/market-data/snapshots/").trimEnd('/')
            val projectionName = queryValue(exchange, "projectionName").ifBlank { "market-data-top-of-book" }
            val result = api.marketDataSnapshotWithStatus(instrumentId, projectionName)
            writeJson(exchange, if (result.found) 200 else 404, result.body)
        }

        server.createContext("/api/v1/market-data/snapshots") { exchange ->
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val projectionName = queryValue(exchange, "projectionName").ifBlank { "market-data-top-of-book" }
            val sourceProjectionName = queryValue(exchange, "sourceProjectionName").ifBlank { "runtime-normalized-venue-outcomes" }
            writeJson(exchange, 200, api.refreshMarketDataSnapshots(projectionName, sourceProjectionName))
        }

        server.createContext("/api/v1/data/availability") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowApiV1Read(exchange, "/api/v1/data/availability")) {
                return@createContext
            }
            val venueProjectionName = queryValue(exchange, "venueProjectionName").ifBlank { "runtime-normalized-venue-outcomes" }
            val marketDataProjectionName = queryValue(exchange, "marketDataProjectionName").ifBlank { "market-data-top-of-book" }
            val source = queryValue(exchange, "source").ifBlank { "venue-event-batch" }
            writeJson(exchange, 200, api.dataAvailability(venueProjectionName, marketDataProjectionName, source))
        }

        server.createContext("/api/v1/settlement/facts/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/facts/").trimEnd('/')
            writeHotPathResponse(exchange, settlementAdminGateway.settlementFactsResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/obligations/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/obligations/").trimEnd('/')
            writeHotPathResponse(exchange, settlementAdminGateway.settlementObligationsResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/ledger/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/ledger/").trimEnd('/')
            writeHotPathResponse(exchange, settlementAdminGateway.settlementLedgerResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/proof/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/proof/").trimEnd('/')
            writeHotPathResponse(exchange, settlementAdminGateway.settlementProofResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/score/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/score/").trimEnd('/')
            writeHotPathResponse(exchange, settlementAdminGateway.settlementScoreResponse(exchange, scenarioRunId))
        }

        server.createContext("/api/v1/market-data/depth/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowApiV1Read(exchange, "/api/v1/market-data/depth/{instrumentId}")) {
                return@createContext
            }
            val instrumentId = exchange.requestURI.path.removePrefix("/api/v1/market-data/depth/").trimEnd('/')
            val levels = queryValue(exchange, "levels").toIntOrNull() ?: 5
            val projectionName = queryValue(exchange, "projectionName").ifBlank { "market-data-depth" }
            val sourceProjectionName = queryValue(exchange, "sourceProjectionName").ifBlank { "runtime-normalized-venue-outcomes" }
            val result = api.marketDataDepthSnapshotWithStatus(instrumentId, levels, projectionName, sourceProjectionName)
            writeJson(exchange, if (result.found) 200 else 404, result.body)
        }

        server.createContext("/api/v1/market-data/trades/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowApiV1Read(exchange, "/api/v1/market-data/trades/{instrumentId}")) {
                return@createContext
            }
            val instrumentId = exchange.requestURI.path.removePrefix("/api/v1/market-data/trades/").trimEnd('/')
            val limit = queryValue(exchange, "limit").toIntOrNull() ?: 50
            val beforeSequence = queryValue(exchange, "before").toLongOrNull()
            writeJson(exchange, 200, api.tradeTape(instrumentId, limit, beforeSequence))
        }

        server.createContext("/api/v1/arena/leaderboard") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowApiV1Read(exchange, "/api/v1/arena/leaderboard")) {
                return@createContext
            }
            val response = arenaAdminGateway.arenaLeaderboardPublicResponse(exchange.requestURI.rawQuery)
            writeJson(exchange, response.status, response.body)
        }

        server.createContext("/api/v1/market-data/bars/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (!allowApiV1Read(exchange, "/api/v1/market-data/bars/{instrumentId}")) {
                return@createContext
            }
            val instrumentId = exchange.requestURI.path.removePrefix("/api/v1/market-data/bars/").trimEnd('/')
            val interval = queryValue(exchange, "interval")
            val start = queryValue(exchange, "start")
            val end = queryValue(exchange, "end")
            val result = api.intradayBarsWithStatus(instrumentId, interval, start, end)
            writeJson(exchange, if (result.found) 200 else 400, result.body)
        }

        server.createContext("/api/v1/orders/current") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val participantId = queryValue(exchange, "participantId")
            val boundaryError = boundary.checkParticipantRead(exchange.requestHeaders, "/api/v1/orders/current", participantId)
            if (boundaryError != null) {
                writeJson(exchange, boundaryError.status, boundary.toErrorJson(boundaryError, correlationId(exchange.requestHeaders)))
                return@createContext
            }
            val instrumentId = queryValue(exchange, "instrumentId")
            val limit = boundedQueryLimit(queryValue(exchange, "limit"), defaultValue = 50)
            writeJson(exchange, 200, api.ownOrders(participantId, openOnly = true, instrumentId = instrumentId, limit = limit))
        }

        server.createContext("/api/v1/orders/history") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val participantId = queryValue(exchange, "participantId")
            val boundaryError = boundary.checkParticipantRead(exchange.requestHeaders, "/api/v1/orders/history", participantId)
            if (boundaryError != null) {
                writeJson(exchange, boundaryError.status, boundary.toErrorJson(boundaryError, correlationId(exchange.requestHeaders)))
                return@createContext
            }
            val instrumentId = queryValue(exchange, "instrumentId")
            val limit = boundedQueryLimit(queryValue(exchange, "limit"), defaultValue = 50)
            writeJson(exchange, 200, api.ownOrders(participantId, openOnly = false, instrumentId = instrumentId, limit = limit))
        }

        server.createContext("/api/v1/orders/fills") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val participantId = queryValue(exchange, "participantId")
            val boundaryError = boundary.checkParticipantRead(exchange.requestHeaders, "/api/v1/orders/fills", participantId)
            if (boundaryError != null) {
                writeJson(exchange, boundaryError.status, boundary.toErrorJson(boundaryError, correlationId(exchange.requestHeaders)))
                return@createContext
            }
            val instrumentId = queryValue(exchange, "instrumentId")
            val limit = boundedQueryLimit(queryValue(exchange, "limit"), defaultValue = 50)
            writeJson(exchange, 200, api.ownExecutions(participantId, instrumentId = instrumentId, limit = limit))
        }

        server.createContext("/trades") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val limit = queryLimit(exchange, 0)
            if (limit > 0) {
                writeJson(exchange, 200, api.recentTrades(limit))
                return@createContext
            }
            writeJson(exchange, 200, api.trades())
        }

        server.createContext("/events") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val limit = queryLimit(exchange, 0)
            if (limit > 0) {
                writeJson(exchange, 200, api.recentEvents(limit))
                return@createContext
            }
            writeJson(exchange, 200, api.events())
        }

        server.createContext("/traces/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }

            val path = exchange.requestURI.path.removePrefix("/traces/")
            if (!path.endsWith("/events")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }

            val traceId = path.removeSuffix("/events").trimEnd('/')
            writeJson(exchange, 200, api.traceEvents(traceId))
        }
        }

        server.start()
        startRuntimeLoops()
        println("platform-runtime role=${runtimeRole.configValue} listening on :$port")
        return server
    }

    internal fun startRuntimeLoops() {
        if (runtimeRole.backgroundWorkersEnabled && asyncCommandWorkerEnabled && commandProcessingMode == CommandProcessingMode.CapturedAck) {
            val queue = capturedCommandQueue
            if (queue == null) {
                System.err.println("async_command_worker_unavailable reason=missing_captured_command_queue")
            } else {
                val workerApi = asyncCommandWorkerApi()
                (1..asyncCommandWorkerThreads).forEach { index ->
                    AsyncCommandProcessor(
                        queue = queue,
                        api = workerApi,
                        batchSize = asyncCommandWorkerBatchSize,
                        pollIntervalMs = asyncCommandWorkerPollMs,
                        workerName = "reef-async-command-processor-$index"
                    ).start()
                }
            }
        }
        if (runtimeRole.backgroundWorkersEnabled && streamCommandWorkerEnabled && commandProcessingMode == CommandProcessingMode.StreamAck) {
            runtimeLoopStarter.startStreamCommandWorkers()
        }
        if (runtimeRole == PlatformRuntimeRole.Projector && streamAckProjectorEnabled && commandProcessingMode == CommandProcessingMode.StreamAck) {
            runtimeLoopStarter.startCanonicalProjector()
        }
        if (runtimeLoopStarter.venueEventMaterializerShouldStart()) {
            runtimeLoopStarter.startVenueEventMaterializer()
        }
        if (runtimeLoopStarter.marketDataProjectorShouldStart()) {
            runtimeLoopStarter.startMarketDataProjector()
        }
        if (runtimeLoopStarter.orderLifecycleProjectorShouldStart()) {
            runtimeLoopStarter.startOrderLifecycleProjector()
        }
        if (runtimeRole == PlatformRuntimeRole.Api && commandProcessingMode == CommandProcessingMode.StreamAck) {
            startStreamCommandDrainBackpressureSampler()
        }
        if (runtimeRole == PlatformRuntimeRole.Api && commandProcessingMode == CommandProcessingMode.AcceptedAsync) {
            acceptedAsyncCommandIntake?.start()
        }
    }

    private fun asyncCommandWorkerApi(): PlatformApi {
        if (!asyncCommandWorkerDedicatedRuntimePoolEnabled) return api
        return PlatformApi(
            OrderApplicationService(
                runtimePersistence = defaultRuntimePersistence("async-runtime")
            )
        )
    }


    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun writeHotPathResponse(exchange: HttpExchange, response: PlatformHotPathResponse) {
        if (response.body.isEmpty() && response.contentType == null) {
            exchange.sendResponseHeaders(response.status, -1)
            exchange.close()
            return
        }
        val bytes = response.body.toByteArray()
        response.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun readinessJson(): String {
        val dbPoolSnapshots = RuntimeDataSources.snapshots()
        val dbPoolsReady = dbPoolSnapshots.all { it.threadsAwaitingConnection == 0 }
        val streamSnapshot = try {
            streamCommandHealthCheck?.snapshot()
        } catch (ex: Exception) {
            System.err.println("readiness_stream_health_check_failed message=${ex.message ?: "unknown"}")
            null
        }
        val streamAckRequired = commandProcessingMode == CommandProcessingMode.StreamAck
        val streamPipelineConfigured = !streamAckRequired ||
            (streamCommandIntakeStore != null && streamCommandPublisher != null && streamCommandHealthCheck != null)
        val streamHealthReady = if (streamCommandHealthCheck == null) {
            !streamAckRequired
        } else {
            streamSnapshot?.available == true
        }
        val streamReady = streamPipelineConfigured && streamHealthReady
        val status = if (dbPoolsReady && streamReady) "ok" else "degraded"
        return JsonCodec.writeObject(
            "status" to status,
            "role" to runtimeRole.configValue,
            "internalHttpMode" to internalHttpExposureMode.name.lowercase(),
            "pipeline" to mapOf(
                "commandProcessingMode" to commandProcessingMode.configValue,
                "streamAckRequired" to streamAckRequired,
                "streamPipelineConfigured" to streamPipelineConfigured,
                "streamCommandStream" to streamCommandConfig.streamName,
                "streamSubjectPrefix" to streamCommandConfig.subjectPrefix,
                "streamPartitionCount" to streamCommandConfig.partitionCount,
                "streamMarkPublishedMode" to streamCommandMarkPublishedMode,
                "streamWorkerEnabled" to streamCommandWorkerEnabled,
                "streamWorkerPartitions" to streamCommandWorkerPartitions,
                "venueEventMaterializerEnabled" to venueEventMaterializerEnabled,
                "venueEventMaterializerBatchSize" to venueEventMaterializerBatchSize,
                "streamAckProjectorEnabled" to streamAckProjectorEnabled,
                "streamAckProjectionName" to streamAckProjectionName,
                "streamAckProjectionSource" to streamAckProjectionSource.configValue,
                "streamAckProjectionEventStream" to streamAckProjectionEventStream,
                "streamAckProjectorPartitions" to streamAckProjectorPartitions,
                "marketDataProjectorEnabled" to marketDataProjectorEnabled,
                "orderLifecycleProjectorEnabled" to orderLifecycleProjectorEnabled,
                "commandStatusSources" to listOf(
                    "canonical_outcome",
                    "event_batch",
                    "command_log",
                    "stream_reference"
                )
            ),
            "dependencies" to mapOf(
                "dbPoolsReady" to dbPoolsReady,
                "dbPoolCount" to dbPoolSnapshots.size,
                "dbPoolWaiters" to dbPoolSnapshots.sumOf { it.threadsAwaitingConnection },
                "streamReady" to streamReady,
                "streamPipelineConfigured" to streamPipelineConfigured,
                "streamHealthReady" to streamHealthReady,
                "streamAvailable" to (streamSnapshot?.available ?: false),
                "streamHealthError" to (streamSnapshot?.error ?: ""),
                "accountRiskControlStore" to (accountRiskControlStore != null),
                "commandCircuitBreakerStore" to (commandCircuitBreakerStore != null),
                "instrumentPriceCollarStore" to (instrumentPriceCollarStore != null),
                "arenaAdminService" to (arenaAdminService != null),
                "analyticsRunExportService" to (analyticsRunExportService != null),
                "settlementFactStore" to (settlementFactStore != null),
                "commandStatusLookup" to (commandStatusLookup != null),
                "streamCommandIntakeStore" to (streamCommandIntakeStore != null),
                "streamCommandPublisher" to (streamCommandPublisher != null),
                "streamCommandHealthCheck" to (streamCommandHealthCheck != null)
            )
        )
    }

    private fun methodNotAllowed(exchange: HttpExchange) {
        exchange.sendResponseHeaders(405, -1)
        exchange.close()
    }

    private fun registerDiagnosticRoutes(server: HttpServer) {
        adminDataRoutes.paths.forEach { path ->
            server.createContext(path) { exchange ->
                if (exchange.requestURI.path.startsWith("/internal/") && !allowInternalHttpRoute(exchange)) {
                    return@createContext
                }
                val body = if (exchange.requestMethod == "POST") {
                    readRequestBody(exchange) ?: return@createContext
                } else {
                    ""
                }
                withAdminRequestPrincipal(exchange) {
                    val response = adminDataRoutes.handle(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        query = exchange.requestURI.query,
                        body = body
                    )
                    if (response == null) {
                        exchange.sendResponseHeaders(404, -1)
                        exchange.close()
                    } else {
                        writeHotPathResponse(exchange, response)
                    }
                }
            }
        }
    }

    // Delegates to AdminSessionAuth (see field above); kept as same-named forwarding
    // wrappers so the ~15 call sites elsewhere in this class stay untouched.
    private fun registerAdminAuthRoutes(server: HttpServer) = adminSessionAuth.register(server)

    private fun allowInternalHttpRoute(exchange: HttpExchange): Boolean = adminSessionAuth.allowInternalHttpRoute(exchange)

    private fun isLoopback(address: String?): Boolean = adminSessionAuth.isLoopback(address)

    private fun withAdminRequestPrincipal(exchange: HttpExchange, block: () -> Unit) {
        adminSessionAuth.withPrincipal(exchange, block)
    }

    private fun <T> withAdminRequestPrincipal(principal: AdminRequestPrincipal, block: () -> T): T {
        return adminSessionAuth.withPrincipal(principal, block)
    }

    private fun adminPrincipal(headers: Headers): AdminRequestPrincipal = adminSessionAuth.principal(headers)

    private fun currentAdminPrincipal(): AdminRequestPrincipal = adminSessionAuth.currentPrincipal()

    private fun headerValue(exchange: HttpExchange, name: String): String {
        return headerValue(exchange.requestHeaders, name)
    }

    private fun headerValue(headers: Headers, name: String): String {
        return headers[name]?.firstOrNull().orEmpty()
    }

    private fun handleAdminGatewayRoute(exchange: HttpExchange) {
        val route = adminGatewayRouteFor(exchange.requestURI.path, exchange.requestMethod)
        if (route == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        val principal = authorizeAdminGateway(exchange, route) ?: return
        val body = if (exchange.requestMethod in setOf("POST", "PUT", "PATCH")) {
            readRequestBody(exchange) ?: return
        } else {
            ""
        }
        withAdminRequestPrincipal(principal) {
            val response = settlementAdminGatewayResponse(exchange.requestMethod, route.internalPath, body)
                ?: adminDataRoutes.handle(
                    method = exchange.requestMethod,
                    path = route.internalPath,
                    query = exchange.requestURI.query,
                    body = body
                )
                ?: PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "admin route not found"))
            writeHotPathResponse(exchange, response)
        }
    }

    private fun handleAdminGatewayRequest(request: PlatformHotPathRequest): PlatformHotPathResponse {
        val route = adminGatewayRouteFor(request.path, request.method)
            ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "admin route not found"))
        val principal = authorizeAdminGateway(request, route)
            ?: return unauthorizedAdminGatewayResponse(route)
        val body = if (request.method in setOf("POST", "PUT", "PATCH")) request.body else ""
        return withAdminRequestPrincipal(principal) {
            settlementAdminGatewayResponse(request.method, route.internalPath, body)
                ?: adminDataRoutes.handle(
                    method = request.method,
                    path = route.internalPath,
                    query = request.query,
                    body = body
                )
                ?: PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "admin route not found"))
        }
    }

    private fun settlementAdminGatewayResponse(
        method: String,
        path: String,
        body: String
    ): PlatformHotPathResponse? {
        if (!path.startsWith("/internal/admin/settlement/")) return null
        if (method != "POST") return methodNotAllowedResponse()
        return when (path) {
            "/internal/admin/settlement/facts" -> settlementAdminGateway.appendSettlementFactsResponse(body)
            "/internal/admin/settlement/repairs/cash" -> settlementAdminGateway.postCashSettlementRepairResponse(body)
            "/internal/admin/settlement/repairs/security" -> settlementAdminGateway.postSecuritySettlementRepairResponse(body)
            "/internal/admin/settlement/force-settle" -> settlementAdminGateway.forceSettleResponse(body)
            "/internal/admin/settlement/reverse-ledger-entry" -> settlementAdminGateway.reverseSettlementLedgerEntryResponse(body)
            "/internal/admin/settlement/obligations/materialize" -> settlementAdminGateway.materializeSettlementObligationsResponse(body)
            else -> null
        }
    }

    private fun authorizeAdminGateway(exchange: HttpExchange, route: AdminGatewayRoute): AdminRequestPrincipal? =
        adminSessionAuth.authorizeGateway(exchange, route)

    private fun authorizeAdminGateway(request: PlatformHotPathRequest, route: AdminGatewayRoute): AdminRequestPrincipal? =
        adminSessionAuth.authorizeGateway(request, route)

    private fun unauthorizedAdminGatewayResponse(route: AdminGatewayRoute): PlatformHotPathResponse =
        adminSessionAuth.unauthorizedGatewayResponse(route)

    internal fun handleHotPathRequest(request: PlatformHotPathRequest): PlatformHotPathResponse? {
        if (request.path.startsWith("/admin/v1/")) {
            return handleAdminGatewayRequest(request)
        }
        if (request.path.startsWith("/internal/")) {
            when (internalHttpExposureMode) {
                InternalHttpExposureMode.Disabled -> return PlatformHotPathResponse(404, "")
                InternalHttpExposureMode.LocalOnly -> if (!isLoopback(request.remoteAddress)) {
                    return PlatformHotPathResponse(
                        403,
                        JsonCodec.writeObject(
                            "error" to "internal HTTP route requires loopback access",
                            "mode" to "local"
                        )
                    )
                }
                InternalHttpExposureMode.Enabled -> Unit
            }
        }
        val diagnosticResponse = withAdminRequestPrincipal(adminPrincipal(request.headers)) {
            adminDataRoutes.handle(request.method, request.path, request.query, request.body)
        }
        return diagnosticResponse ?: when {
            request.path.startsWith("/api/v1/commands/") -> commandStatusLookupResponse(request)
            request.path == "/api/v1/orders/submit" ->
                handleApiV1MutationResponse(request, "/api/v1/orders/submit") { body -> api.submitOrder(body) }
            request.path == "/api/v1/orders/modify" ->
                handleApiV1MutationResponse(request, "/api/v1/orders/modify") { body -> api.modifyOrder(body) }
            request.path == "/api/v1/orders/cancel" ->
                handleApiV1MutationResponse(request, "/api/v1/orders/cancel") { body -> api.cancelOrder(body) }
            request.path == "/api/v1/orders/lifecycle-state" && request.method == "POST" ->
                PlatformHotPathResponse(status = 200, body = api.rebuildOrderLifecycleState())
            request.path == "/api/v1/market-data/snapshots" && request.method == "POST" ->
                PlatformHotPathResponse(
                    status = 200,
                    body = api.refreshMarketDataSnapshots(
                        queryValue(request.query, "projectionName").ifBlank { "market-data-top-of-book" },
                        queryValue(request.query, "sourceProjectionName").ifBlank { "runtime-normalized-venue-outcomes" }
                    )
                )
            request.path == "/api/v1/data/availability" && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/data/availability")
                if (readError != null) readError else PlatformHotPathResponse(
                    status = 200,
                    body = api.dataAvailability(
                        venueProjectionName = queryValue(request.query, "venueProjectionName").ifBlank { "runtime-normalized-venue-outcomes" },
                        marketDataProjectionName = queryValue(request.query, "marketDataProjectionName").ifBlank { "market-data-top-of-book" },
                        source = queryValue(request.query, "source").ifBlank { "venue-event-batch" }
                    )
                )
            }
            request.path.startsWith("/api/v1/market-data/snapshots/") && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/market-data/snapshots/{instrumentId}")
                if (readError != null) {
                    return readError
                }
                val instrumentId = request.path.removePrefix("/api/v1/market-data/snapshots/").trimEnd('/')
                val result = api.marketDataSnapshotWithStatus(
                    instrumentId,
                    queryValue(request.query, "projectionName").ifBlank { "market-data-top-of-book" }
                )
                PlatformHotPathResponse(status = if (result.found) 200 else 404, body = result.body)
            }
            request.path.startsWith("/api/v1/market-data/depth/") && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/market-data/depth/{instrumentId}")
                if (readError != null) {
                    return readError
                }
                val instrumentId = request.path.removePrefix("/api/v1/market-data/depth/").trimEnd('/')
                val result = api.marketDataDepthSnapshotWithStatus(
                    instrumentId = instrumentId,
                    levels = queryValue(request.query, "levels").toIntOrNull() ?: 5,
                    projectionName = queryValue(request.query, "projectionName").ifBlank { "market-data-depth" },
                    sourceProjectionName = queryValue(request.query, "sourceProjectionName").ifBlank { "runtime-normalized-venue-outcomes" }
                )
                PlatformHotPathResponse(status = if (result.found) 200 else 404, body = result.body)
            }
            request.path.startsWith("/api/v1/market-data/trades/") && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/market-data/trades/{instrumentId}")
                if (readError != null) {
                    return readError
                }
                val instrumentId = request.path.removePrefix("/api/v1/market-data/trades/").trimEnd('/')
                val response = api.tradeTape(
                    instrumentId = instrumentId,
                    limit = queryValue(request.query, "limit").toIntOrNull() ?: 50,
                    beforeSequence = queryValue(request.query, "before").toLongOrNull()
                )
                PlatformHotPathResponse(status = 200, body = response)
            }
            request.path == "/api/v1/arena/leaderboard" && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/arena/leaderboard")
                readError ?: arenaAdminGateway.arenaLeaderboardPublicResponse(request.query)
            }
            request.path.startsWith("/api/v1/market-data/bars/") && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/market-data/bars/{instrumentId}")
                if (readError != null) {
                    return readError
                }
                val instrumentId = request.path.removePrefix("/api/v1/market-data/bars/").trimEnd('/')
                val result = api.intradayBarsWithStatus(
                    instrumentId = instrumentId,
                    interval = queryValue(request.query, "interval"),
                    start = queryValue(request.query, "start"),
                    end = queryValue(request.query, "end")
                )
                PlatformHotPathResponse(status = if (result.found) 200 else 400, body = result.body)
            }
            request.path == "/api/v1/orders/current" && request.method == "GET" ->
                readOrdersResponse(request, "/api/v1/orders/current", openOnly = true)
            request.path == "/api/v1/orders/history" && request.method == "GET" ->
                readOrdersResponse(request, "/api/v1/orders/history", openOnly = false)
            request.path == "/api/v1/orders/fills" && request.method == "GET" ->
                readOrderFillsResponse(request, "/api/v1/orders/fills")
            else -> legacySetupRoutes.handle(request)
        }
    }

    private fun readOrdersResponse(request: PlatformHotPathRequest, route: String, openOnly: Boolean): PlatformHotPathResponse {
        val participantId = queryValue(request.query, "participantId")
        val boundaryError = boundary.checkParticipantRead(request.headers, route, participantId)
        if (boundaryError != null) {
            return PlatformHotPathResponse(
                boundaryError.status,
                boundary.toErrorJson(boundaryError, correlationId(request.headers))
            )
        }
        return PlatformHotPathResponse(
            status = 200,
            body = api.ownOrders(
                participantId = participantId,
                openOnly = openOnly,
                instrumentId = queryValue(request.query, "instrumentId"),
                limit = boundedQueryLimit(queryValue(request.query, "limit"), defaultValue = 50)
            )
        )
    }

    private fun readOrderFillsResponse(request: PlatformHotPathRequest, route: String): PlatformHotPathResponse {
        val participantId = queryValue(request.query, "participantId")
        val boundaryError = boundary.checkParticipantRead(request.headers, route, participantId)
        if (boundaryError != null) {
            return PlatformHotPathResponse(
                boundaryError.status,
                boundary.toErrorJson(boundaryError, correlationId(request.headers))
            )
        }
        return PlatformHotPathResponse(
            status = 200,
            body = api.ownExecutions(
                participantId = participantId,
                instrumentId = queryValue(request.query, "instrumentId"),
                limit = boundedQueryLimit(queryValue(request.query, "limit"), defaultValue = 50)
            )
        )
    }

    internal fun handleHotPathRequestAsync(request: PlatformHotPathRequest): CompletableFuture<PlatformHotPathResponse?> {
        if (
            request.path in apiV1OrderMutationRoutes &&
            request.method == "POST" &&
            commandProcessingMode == CommandProcessingMode.StreamAck
        ) {
            return handleApiV1MutationResponseAsync(request, request.path)
                .thenApply<PlatformHotPathResponse?> { it }
        }
        return CompletableFuture.completedFuture(handleHotPathRequest(request))
    }

    internal fun handleStreamIngressSubmitAsync(body: String): CompletableFuture<PlatformHotPathResponse> {
        return streamIngressSubmitHandler.handle(body)
    }

    private fun simpleErrorJson(error: String, message: String? = null): String {
        return if (message == null) {
            JsonCodec.writeObject("error" to error)
        } else {
            JsonCodec.writeObject("error" to error, "message" to message)
        }
    }


    private fun runtimeUnavailableJson(ex: Exception): String {
        return simpleErrorJson("runtime unavailable", ex.message ?: "unknown")
    }

    private fun readRequestBody(exchange: HttpExchange): String? {
        val buffer = ByteArray(DEFAULT_BODY_BUFFER_BYTES)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = exchange.requestBody.read(buffer)
            if (read < 0) {
                return out.toString(Charsets.UTF_8.name())
            }
            total += read
            if (total > maxRequestBodyBytes) {
                writeJson(exchange, 413, JsonCodec.writeObject("error" to "request body too large", "maxBytes" to maxRequestBodyBytes))
                return null
            }
            out.write(buffer, 0, read)
        }
    }

    private fun queryLimit(exchange: HttpExchange, defaultValue: Int): Int {
        return queryValue(exchange, "limit").toIntOrNull() ?: defaultValue
    }

    private fun queryValue(exchange: HttpExchange, key: String): String {
        val query = exchange.requestURI.query ?: return ""
        return queryValue(query, key)
    }

    private fun correlationId(exchange: HttpExchange): String {
        return exchange.requestHeaders["X-Correlation-Id"]?.firstOrNull() ?: ""
    }

    private fun correlationId(headers: Headers): String {
        return headers["X-Correlation-Id"]?.firstOrNull() ?: ""
    }

    private fun allowApiV1Read(exchange: HttpExchange, route: String): Boolean {
        val boundaryError = boundary.checkRead(exchange.requestHeaders, route)
        if (boundaryError != null) {
            writeJson(exchange, boundaryError.status, boundary.toErrorJson(boundaryError, correlationId(exchange.requestHeaders)))
            return false
        }
        return true
    }

    private fun apiV1ReadErrorResponse(request: PlatformHotPathRequest, route: String): PlatformHotPathResponse? {
        val boundaryError = boundary.checkRead(request.headers, route) ?: return null
        return PlatformHotPathResponse(
            boundaryError.status,
            boundary.toErrorJson(boundaryError, correlationId(request.headers))
        )
    }

    private fun commandStatusReadScopeError(headers: Headers, status: CommandStatusView): BoundaryError? {
        val clientId = boundary.clientId(headers).orEmpty()
        if (status.clientId.isNotBlank() && status.clientId != clientId) {
            return BoundaryError(403, "OBJECT_AUTH_DENIED", "command scope does not match authenticated client")
        }
        if (status.participantId.isBlank()) {
            return null
        }
        val principalParticipant = headerValue(headers, "X-Participant-Id")
        if (principalParticipant.isBlank()) {
            return BoundaryError(403, "OBJECT_AUTH_REQUIRED", "missing X-Participant-Id header")
        }
        if (status.participantId != principalParticipant) {
            return BoundaryError(403, "OBJECT_AUTH_DENIED", "command participant does not match authenticated principal")
        }
        return null
    }

    private fun allowLegacyMutationRoute(exchange: HttpExchange): Boolean {
        if (!legacyMutationRoutesEnabled) {
            writeJson(exchange, 403, simpleErrorJson("legacy mutation route disabled"))
            return false
        }
        val internalMarker = exchange.requestHeaders[LEGACY_INTERNAL_ROUTE_HEADER]?.firstOrNull()
        if (internalMarker != "true") {
            writeJson(
                exchange,
                403,
                JsonCodec.writeObject(
                    "error" to "legacy mutation route requires internal marker",
                    "header" to LEGACY_INTERNAL_ROUTE_HEADER
                )
            )
            return false
        }
        return true
    }

    private fun handleApiV1Mutation(
        exchange: HttpExchange,
        route: String,
        presetBody: String? = null,
        operation: (String) -> String
    ) {
        val started = System.nanoTime()
        try {
            handleApiV1MutationMeasured(exchange, route, presetBody, operation)
        } finally {
            HotPathMetrics.record("api.mutation.total", System.nanoTime() - started)
        }
    }

    private fun handleApiV1MutationMeasured(
        exchange: HttpExchange,
        route: String,
        presetBody: String? = null,
        operation: (String) -> String
    ) {
        if (exchange.requestMethod != "POST") {
            methodNotAllowed(exchange)
            return
        }

        val violation = HotPathMetrics.time("api.boundary.checkWrite") {
            boundary.checkWrite(exchange.requestHeaders, route)
        }
        if (violation != null) {
            writeJson(exchange, violation.status, boundary.toErrorJson(violation, correlationId(exchange)))
            return
        }

        val clientId = boundary.clientId(exchange.requestHeaders).orEmpty()
        val idempotencyKey = boundary.idempotencyKey(exchange.requestHeaders).orEmpty()
        val correlationId = correlationId(exchange)
        val body = presetBody ?: (HotPathMetrics.time("api.readRequestBody") {
            readRequestBody(exchange)
        } ?: return)
        val validation = HotPathMetrics.time("api.command.validate") {
            PlatformCommandParsers.parseAndValidateApiV1Command(route, body)
        }
        val parsedBody = when (validation) {
            is ApiV1CommandValidation.Invalid -> {
                writeJson(exchange, 400, boundary.toErrorJson(BoundaryError(400, "VALIDATION_ERROR", validation.error), correlationId))
                return
            }
            is ApiV1CommandValidation.Valid -> validation.json
        }

        if (commandProcessingMode == CommandProcessingMode.AcceptedAsync && route == "/api/v1/orders/submit") {
            handleAcceptedAsyncMutation(exchange, route, clientId, idempotencyKey, correlationId, body)
            return
        }

        if (commandProcessingMode == CommandProcessingMode.StreamAck) {
            handleStreamAckMutation(exchange, route, clientId, idempotencyKey, correlationId, body)
            return
        }

        if (commandProcessingMode == CommandProcessingMode.CapturedAck) {
            val existingStatus = commandStatusLookup?.findCommandStatus(clientId, route, idempotencyKey)
            if (existingStatus != null) {
                writeDuplicateCommandReservation(exchange, clientId, route, idempotencyKey, existingStatus, correlationId)
                return
            }
            val backpressure = commandIntakeBackpressure()
            if (backpressure != null) {
                writeJson(exchange, backpressure.status, boundary.toErrorJson(backpressure, correlationId))
                return
            }
        }

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, null, parsedBody)
        val breakerViolation = HotPathMetrics.time("api.commandCircuitBreaker.check") {
            commandCircuitBreakerViolation(riskRequest)
        }
        if (breakerViolation != null) {
            recordGuardrailRejection("command-circuit-breaker", riskRequest, breakerViolation)
            writeJson(exchange, breakerViolation.status, boundary.toErrorJson(breakerViolation, correlationId))
            return
        }

        val collarViolation = HotPathMetrics.time("api.instrumentPriceCollar.check") {
            instrumentPriceCollarViolation(riskRequest)
        }
        if (collarViolation != null) {
            recordGuardrailRejection("instrument-price-collar", riskRequest, collarViolation)
            writeJson(exchange, collarViolation.status, boundary.toErrorJson(collarViolation, correlationId))
            return
        }

        val riskViolation = HotPathMetrics.time("api.accountRisk.check") {
            accountRiskViolation(riskRequest)
        }
        if (riskViolation != null) {
            writeJson(exchange, riskViolation.status, boundary.toErrorJson(riskViolation, correlationId))
            return
        }

        val reservation = try {
            HotPathMetrics.time("api.commandCapture.reserve") {
                commandCaptureStore.reserveReceived(clientId, route, idempotencyKey, correlationId, body)
            }
        } catch (ex: Exception) {
            val errorClass = ex::class.simpleName ?: "Exception"
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "command_capture_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId errorClass=$errorClass message=${JsonFields.escape(errorMessage)}"
            )
            writeJson(
                exchange,
                503,
                simpleErrorJson("command capture unavailable", errorMessage)
            )
            return
        }
        if (!reservation.accepted) {
            writeDuplicateCommandReservation(exchange, clientId, route, idempotencyKey, reservation.existingCommandStatus, correlationId)
            return
        }

        if (
            commandProcessingMode in setOf(CommandProcessingMode.CapturedAck, CommandProcessingMode.CapturedSyncEngine) &&
            commandStatusLookup == null
        ) {
            HotPathMetrics.time("api.commandCapture.markFailed") {
                commandCaptureStore.markFailed(
                    clientId,
                    route,
                    idempotencyKey,
                    503,
                    "COMMAND_STATUS_UNAVAILABLE",
                    "command status lookup is required for ${commandProcessingMode.configValue}"
                )
            }
            writeJson(exchange, 503, simpleErrorJson("command status unavailable"))
            return
        }

        val abuseViolation = HotPathMetrics.time("api.abuse.allow") {
            abuseProtectionHook.allow(clientId, route)
        }
        if (abuseViolation != null) {
            HotPathMetrics.time("api.commandCapture.markFailed") {
                commandCaptureStore.markFailed(clientId, route, idempotencyKey, abuseViolation.status, abuseViolation.code, abuseViolation.message)
            }
            writeJson(exchange, abuseViolation.status, boundary.toErrorJson(abuseViolation, correlationId))
            return
        }

        if (commandProcessingMode == CommandProcessingMode.CapturedAck) {
            val status = commandStatusLookup?.findCommandStatus(clientId, route, idempotencyKey)
            if (status == null) {
                HotPathMetrics.time("api.commandCapture.markFailed") {
                    commandCaptureStore.markFailed(
                        clientId,
                        route,
                        idempotencyKey,
                        503,
                        "COMMAND_STATUS_UNAVAILABLE",
                        "captured command status not found"
                    )
                }
                writeJson(exchange, 503, simpleErrorJson("command status unavailable"))
                return
            }
            val payload = CommandStatusResponse.acceptedJson(status)
            writeJson(exchange, 202, payload)
            return
        }

        val cached = HotPathMetrics.time("api.idempotency.find") {
            idempotencyStore.find(clientId, route, idempotencyKey)
        }
        if (cached != null) {
            HotPathMetrics.time("api.commandCapture.markCompleted") {
                commandCaptureStore.markCompleted(clientId, route, idempotencyKey, cached.status, cached.payload)
            }
            HotPathMetrics.time("api.writeResponse") {
                writeJson(exchange, cached.status, cached.payload)
            }
            return
        }

        if (commandProcessingMode == CommandProcessingMode.CapturedSyncEngine) {
            HotPathMetrics.time("api.commandCapture.markProcessing") {
                commandCaptureStore.markProcessing(clientId, route, idempotencyKey)
            }
        }

        try {
            val payload = HotPathMetrics.time("api.operation") {
                operation(body)
            }
            abuseProtectionHook.observe(clientId, route, 200, rejectCode(payload))
            HotPathMetrics.time("api.idempotency.save") {
                rememberIdempotentResult(exchange, route, 200, payload)
            }
            HotPathMetrics.time("api.commandCapture.markCompleted") {
                commandCaptureStore.markCompleted(clientId, route, idempotencyKey, 200, payload)
            }
            HotPathMetrics.time("api.writeResponse") {
                writeJson(exchange, 200, payload)
            }
        } catch (ex: Exception) {
            val errorClass = ex::class.simpleName ?: "Exception"
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "runtime_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId errorClass=$errorClass message=${JsonFields.escape(errorMessage)}"
            )
            HotPathMetrics.time("api.commandCapture.markFailed") {
                commandCaptureStore.markFailed(clientId, route, idempotencyKey, 503, errorClass, errorMessage)
            }
            writeJson(exchange, 503, simpleErrorJson("runtime unavailable", errorMessage))
        }
    }

    private fun handleApiV1MutationResponse(
        request: PlatformHotPathRequest,
        route: String,
        operation: (String) -> String
    ): PlatformHotPathResponse {
        val started = System.nanoTime()
        try {
            return handleApiV1MutationMeasuredResponse(request, route, operation)
        } finally {
            HotPathMetrics.record("api.mutation.total", System.nanoTime() - started)
        }
    }

    private fun handleApiV1MutationMeasuredResponse(
        request: PlatformHotPathRequest,
        route: String,
        operation: (String) -> String
    ): PlatformHotPathResponse {
        val prepared = when (val result = prepareApiV1MutationRequest(request, route)) {
            is PreparedApiV1MutationResult.Prepared -> result.request
            is PreparedApiV1MutationResult.Rejected -> return result.response
        }
        val clientId = prepared.clientId
        val idempotencyKey = prepared.idempotencyKey
        val correlationId = prepared.correlationId
        val body = prepared.body

        if (commandProcessingMode == CommandProcessingMode.AcceptedAsync && route == "/api/v1/orders/submit") {
            return handleAcceptedAsyncMutationResponse(route, clientId, idempotencyKey, correlationId, body)
        }

        if (commandProcessingMode == CommandProcessingMode.StreamAck) {
            return handleStreamAckMutationResponse(route, clientId, idempotencyKey, correlationId, body)
                .get()
        }

        if (commandProcessingMode == CommandProcessingMode.CapturedAck) {
            val existingStatus = commandStatusLookup?.findCommandStatus(clientId, route, idempotencyKey)
            if (existingStatus != null) {
                return duplicateCommandReservationResponse(clientId, route, idempotencyKey, existingStatus, correlationId)
            }
            val backpressure = commandIntakeBackpressure()
            if (backpressure != null) {
                return PlatformHotPathResponse(backpressure.status, boundary.toErrorJson(backpressure, correlationId))
            }
        }

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, null, prepared.parsedBody)
        val breakerViolation = HotPathMetrics.time("api.commandCircuitBreaker.check") {
            commandCircuitBreakerViolation(riskRequest)
        }
        if (breakerViolation != null) {
            recordGuardrailRejection("command-circuit-breaker", riskRequest, breakerViolation)
            return PlatformHotPathResponse(breakerViolation.status, boundary.toErrorJson(breakerViolation, correlationId))
        }

        val collarViolation = HotPathMetrics.time("api.instrumentPriceCollar.check") {
            instrumentPriceCollarViolation(riskRequest)
        }
        if (collarViolation != null) {
            recordGuardrailRejection("instrument-price-collar", riskRequest, collarViolation)
            return PlatformHotPathResponse(collarViolation.status, boundary.toErrorJson(collarViolation, correlationId))
        }

        val riskViolation = HotPathMetrics.time("api.accountRisk.check") {
            accountRiskViolation(riskRequest)
        }
        if (riskViolation != null) {
            return PlatformHotPathResponse(riskViolation.status, boundary.toErrorJson(riskViolation, correlationId))
        }

        val reservation = try {
            HotPathMetrics.time("api.commandCapture.reserve") {
                commandCaptureStore.reserveReceived(clientId, route, idempotencyKey, correlationId, body)
            }
        } catch (ex: Exception) {
            val errorClass = ex::class.simpleName ?: "Exception"
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "command_capture_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId errorClass=$errorClass message=${JsonFields.escape(errorMessage)}"
            )
            return PlatformHotPathResponse(503, simpleErrorJson("command capture unavailable", errorMessage))
        }
        if (!reservation.accepted) {
            return duplicateCommandReservationResponse(clientId, route, idempotencyKey, reservation.existingCommandStatus, correlationId)
        }

        if (
            commandProcessingMode in setOf(CommandProcessingMode.CapturedAck, CommandProcessingMode.CapturedSyncEngine) &&
            commandStatusLookup == null
        ) {
            HotPathMetrics.time("api.commandCapture.markFailed") {
                commandCaptureStore.markFailed(
                    clientId,
                    route,
                    idempotencyKey,
                    503,
                    "COMMAND_STATUS_UNAVAILABLE",
                    "command status lookup is required for ${commandProcessingMode.configValue}"
                )
            }
            return PlatformHotPathResponse(503, simpleErrorJson("command status unavailable"))
        }

        val abuseViolation = HotPathMetrics.time("api.abuse.allow") {
            abuseProtectionHook.allow(clientId, route)
        }
        if (abuseViolation != null) {
            HotPathMetrics.time("api.commandCapture.markFailed") {
                commandCaptureStore.markFailed(clientId, route, idempotencyKey, abuseViolation.status, abuseViolation.code, abuseViolation.message)
            }
            return PlatformHotPathResponse(abuseViolation.status, boundary.toErrorJson(abuseViolation, correlationId))
        }

        if (commandProcessingMode == CommandProcessingMode.CapturedAck) {
            val status = commandStatusLookup?.findCommandStatus(clientId, route, idempotencyKey)
            if (status == null) {
                HotPathMetrics.time("api.commandCapture.markFailed") {
                    commandCaptureStore.markFailed(
                        clientId,
                        route,
                        idempotencyKey,
                        503,
                        "COMMAND_STATUS_UNAVAILABLE",
                        "captured command status not found"
                    )
                }
                return PlatformHotPathResponse(503, simpleErrorJson("command status unavailable"))
            }
            return PlatformHotPathResponse(202, CommandStatusResponse.acceptedJson(status))
        }

        val cached = HotPathMetrics.time("api.idempotency.find") {
            idempotencyStore.find(clientId, route, idempotencyKey)
        }
        if (cached != null) {
            HotPathMetrics.time("api.commandCapture.markCompleted") {
                commandCaptureStore.markCompleted(clientId, route, idempotencyKey, cached.status, cached.payload)
            }
            return PlatformHotPathResponse(cached.status, cached.payload)
        }

        if (commandProcessingMode == CommandProcessingMode.CapturedSyncEngine) {
            HotPathMetrics.time("api.commandCapture.markProcessing") {
                commandCaptureStore.markProcessing(clientId, route, idempotencyKey)
            }
        }

        return try {
            val payload = HotPathMetrics.time("api.operation") {
                operation(body)
            }
            abuseProtectionHook.observe(clientId, route, 200, rejectCode(payload))
            HotPathMetrics.time("api.idempotency.save") {
                idempotencyStore.save(
                    clientId,
                    route,
                    idempotencyKey,
                    IdempotencyResult(200, payload),
                    idempotencyRetentionPolicy.ttlFor(route)
                )
            }
            HotPathMetrics.time("api.commandCapture.markCompleted") {
                commandCaptureStore.markCompleted(clientId, route, idempotencyKey, 200, payload)
            }
            PlatformHotPathResponse(200, payload)
        } catch (ex: Exception) {
            val errorClass = ex::class.simpleName ?: "Exception"
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "runtime_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId errorClass=$errorClass message=${JsonFields.escape(errorMessage)}"
            )
            HotPathMetrics.time("api.commandCapture.markFailed") {
                commandCaptureStore.markFailed(clientId, route, idempotencyKey, 503, errorClass, errorMessage)
            }
            PlatformHotPathResponse(503, simpleErrorJson("runtime unavailable", errorMessage))
        }
    }

    private fun handleApiV1MutationResponseAsync(
        request: PlatformHotPathRequest,
        route: String
    ): CompletableFuture<PlatformHotPathResponse> {
        val started = System.nanoTime()
        return try {
            handleApiV1MutationMeasuredResponseAsync(request, route)
                .whenComplete { _, _ -> HotPathMetrics.record("api.mutation.total", System.nanoTime() - started) }
        } catch (ex: Exception) {
            HotPathMetrics.record("api.mutation.total", System.nanoTime() - started)
            CompletableFuture.failedFuture(ex)
        }
    }

    private fun handleApiV1MutationMeasuredResponseAsync(
        request: PlatformHotPathRequest,
        route: String
    ): CompletableFuture<PlatformHotPathResponse> {
        val prepared = when (val result = prepareApiV1MutationRequest(request, route)) {
            is PreparedApiV1MutationResult.Prepared -> result.request
            is PreparedApiV1MutationResult.Rejected -> return CompletableFuture.completedFuture(result.response)
        }
        return handleStreamAckMutationResponse(
            prepared.route,
            prepared.clientId,
            prepared.idempotencyKey,
            prepared.correlationId,
            prepared.body
        )
    }

    private fun prepareApiV1MutationRequest(
        request: PlatformHotPathRequest,
        route: String
    ): PreparedApiV1MutationResult {
        if (request.method != "POST") {
            return PreparedApiV1MutationResult.Rejected(methodNotAllowedResponse())
        }
        if (request.body.toByteArray().size > maxRequestBodyBytes) {
            return PreparedApiV1MutationResult.Rejected(
                PlatformHotPathResponse(
                    413,
                    JsonCodec.writeObject("error" to "request body too large", "maxBytes" to maxRequestBodyBytes)
                )
            )
        }

        val violation = HotPathMetrics.time("api.boundary.checkWrite") {
            boundary.checkWrite(request.headers, route)
        }
        val correlationId = correlationId(request.headers)
        if (violation != null) {
            return PreparedApiV1MutationResult.Rejected(
                PlatformHotPathResponse(violation.status, boundary.toErrorJson(violation, correlationId))
            )
        }

        val clientId = boundary.clientId(request.headers).orEmpty()
        val idempotencyKey = boundary.idempotencyKey(request.headers).orEmpty()
        val body = request.body
        val validation = HotPathMetrics.time("api.command.validate") {
            PlatformCommandParsers.parseAndValidateApiV1Command(route, body)
        }
        val parsedBody = when (validation) {
            is ApiV1CommandValidation.Invalid -> {
                return PreparedApiV1MutationResult.Rejected(
                    PlatformHotPathResponse(
                        400,
                        boundary.toErrorJson(BoundaryError(400, "VALIDATION_ERROR", validation.error), correlationId)
                    )
                )
            }
            is ApiV1CommandValidation.Valid -> validation.json
        }

        return PreparedApiV1MutationResult.Prepared(
            PreparedApiV1Mutation(
                route = route,
                clientId = clientId,
                idempotencyKey = idempotencyKey,
                correlationId = correlationId,
                body = body,
                parsedBody = parsedBody
            )
        )
    }

    private fun handleStreamAckMutation(
        exchange: HttpExchange,
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ) {
        try {
            val response = handleStreamAckMutationResponse(route, clientId, idempotencyKey, correlationId, body).get()
            HotPathMetrics.time("api.streamAck.writeResponse") {
                writeJson(exchange, response.status, response.body)
            }
        } catch (ex: Exception) {
            writeJson(exchange, 503, simpleErrorJson("stream command publish unavailable", rootMessage(ex)))
        }
    }

    private fun handleStreamAckMutationResponse(
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ): CompletableFuture<PlatformHotPathResponse> {
        val started = System.nanoTime()
        val intakeStore = streamCommandIntakeStore
        val publisher = streamCommandPublisher
        if (intakeStore == null || publisher == null) {
            return completedStreamAckResponse(started, PlatformHotPathResponse(503, simpleErrorJson("stream command intake unavailable")))
        }

        val envelopeResult = HotPathMetrics.time("api.streamAck.envelope") {
            StreamCommandEnvelopeBuilder.fromRequest(
                clientId = clientId,
                route = route,
                idempotencyKey = idempotencyKey,
                body = body,
                config = streamCommandConfig
            )
        }
        val envelope = when (envelopeResult) {
            is EitherBoundaryError.Error -> {
                return completedStreamAckResponse(
                    started,
                    PlatformHotPathResponse(envelopeResult.error.status, boundary.toErrorJson(envelopeResult.error, correlationId))
                )
            }
            is EitherBoundaryError.Envelope -> envelopeResult.envelope
        }

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, envelope)
        val breakerViolation = HotPathMetrics.time("api.streamAck.commandCircuitBreaker") {
            commandCircuitBreakerViolation(riskRequest)
        }
        if (breakerViolation != null) {
            recordGuardrailRejection("command-circuit-breaker", riskRequest, breakerViolation)
            return completedStreamAckResponse(
                started,
                PlatformHotPathResponse(breakerViolation.status, boundary.toErrorJson(breakerViolation, correlationId))
            )
        }

        val collarViolation = HotPathMetrics.time("api.streamAck.instrumentPriceCollar") {
            instrumentPriceCollarViolation(riskRequest)
        }
        if (collarViolation != null) {
            recordGuardrailRejection("instrument-price-collar", riskRequest, collarViolation)
            return completedStreamAckResponse(
                started,
                PlatformHotPathResponse(collarViolation.status, boundary.toErrorJson(collarViolation, correlationId))
            )
        }

        val riskViolation = HotPathMetrics.time("api.streamAck.accountRisk") {
            accountRiskViolation(riskRequest)
        }
        if (riskViolation != null) {
            return completedStreamAckResponse(
                started,
                PlatformHotPathResponse(riskViolation.status, boundary.toErrorJson(riskViolation, correlationId))
            )
        }

        val abuseViolation = HotPathMetrics.time("api.streamAck.abuse") {
            abuseProtectionHook.allow(clientId, route)
        }
        if (abuseViolation != null) {
            return completedStreamAckResponse(
                started,
                PlatformHotPathResponse(abuseViolation.status, boundary.toErrorJson(abuseViolation, correlationId))
            )
        }

        val streamBackpressure = HotPathMetrics.time("api.streamAck.backpressure") {
            streamCommandBackpressure()
        }
        if (streamBackpressure != null) {
            return completedStreamAckResponse(
                started,
                PlatformHotPathResponse(streamBackpressure.status, boundary.toErrorJson(streamBackpressure, correlationId))
            )
        }

        val initialReference = envelope.reference(streamCommandConfig.streamName)
        val reservation = try {
            HotPathMetrics.time("api.streamAck.reserve") {
                intakeStore.reserve(envelope, initialReference)
            }
        } catch (ex: Exception) {
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "stream_command_intake_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId message=${JsonFields.escape(errorMessage)}"
            )
            return completedStreamAckResponse(
                started,
                PlatformHotPathResponse(503, simpleErrorJson("stream command intake unavailable", errorMessage))
            )
        }

        when (reservation) {
            is StreamCommandReservation.Conflict -> {
                return completedStreamAckResponse(
                    started,
                    PlatformHotPathResponse(
                        409,
                        boundary.toErrorJson(
                            BoundaryError(
                                409,
                                "IDEMPOTENCY_PAYLOAD_CONFLICT",
                                "idempotency key was already used with a different command payload"
                            ),
                            correlationId
                        )
                    )
                )
            }
            is StreamCommandReservation.Replay -> {
                if (reservation.reference.streamSequence > 0L) {
                    return completedStreamAckResponse(
                        started,
                        PlatformHotPathResponse(202, StreamCommandResponse.acceptedJson(reservation.reference))
                    )
                }
            }
            is StreamCommandReservation.Reserved -> {
            }
        }

        val publishStarted = System.nanoTime()
        val publishFuture = publishStreamCommand(publisher, envelope)
        val responseTimedOut = AtomicBoolean(false)
        val responseFuture = publishResponseFuture(publishFuture, responseTimedOut)
        publishFuture.whenComplete { ack, failure ->
            if (failure == null && responseTimedOut.get()) {
                markStreamCommandPublished(envelope.commandId, ack.streamSequence)
            }
        }
        return responseFuture.handle { ack, failure ->
            HotPathMetrics.record("api.streamAck.publishAck", System.nanoTime() - publishStarted)
            if (failure != null) {
                val cause = rootCause(failure)
                val errorMessage = cause.message ?: "unknown"
                logStreamCommandPublishFailure(route, clientId, idempotencyKey, correlationId, envelope.subject, errorMessage)
                val status = if (cause is StreamCommandPublishBackpressureException) 429 else 503
                val error = if (status == 429) "stream command publish backpressure" else "stream command publish unavailable"
                PlatformHotPathResponse(status, simpleErrorJson(error, errorMessage))
            } else {
                markStreamCommandPublished(envelope.commandId, ack.streamSequence)
                val publishedReference = envelope.reference(ack.streamName, ack.streamSequence)
                PlatformHotPathResponse(202, StreamCommandResponse.acceptedJson(publishedReference))
            }
        }.whenComplete { _, _ ->
            HotPathMetrics.record("api.streamAck.total", System.nanoTime() - started)
        }
    }

    private fun publishResponseFuture(
        publishFuture: CompletableFuture<StreamPublishAck>,
        responseTimedOut: AtomicBoolean
    ): CompletableFuture<StreamPublishAck> {
        if (streamCommandPublishResponseTimeoutMs <= 0L) return publishFuture
        val bounded = CompletableFuture<StreamPublishAck>()
        val timeoutTask = streamPublishTimeoutExecutor.schedule({
            responseTimedOut.set(true)
            bounded.completeExceptionally(
                StreamCommandPublishTimeoutException("timed out waiting ${streamCommandPublishResponseTimeoutMs}ms for stream command publish ack")
            )
        }, streamCommandPublishResponseTimeoutMs, TimeUnit.MILLISECONDS)
        publishFuture.whenComplete { ack, failure ->
            timeoutTask.cancel(false)
            if (failure != null) {
                bounded.completeExceptionally(failure)
            } else {
                bounded.complete(ack)
            }
        }
        return bounded
    }

    private fun logStreamCommandPublishFailure(
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        subject: String,
        errorMessage: String
    ) {
        val count = streamPublishFailureLogCount.incrementAndGet()
        val now = System.currentTimeMillis()
        val previous = streamPublishFailureLastLogMs.get()
        val shouldLog = count <= 10L ||
            count % 1_000L == 0L ||
            (now - previous >= 1_000L && streamPublishFailureLastLogMs.compareAndSet(previous, now))
        if (!shouldLog) return
        System.err.println(
            "stream_command_publish_failed count=$count route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId subject=$subject message=${JsonFields.escape(errorMessage)}"
        )
    }

    private fun publishStreamCommand(
        publisher: StreamCommandPublisher,
        envelope: StreamCommandEnvelope
    ): CompletableFuture<StreamPublishAck> {
        return if (publisher is AsyncStreamCommandPublisher) {
            publisher.publishAsync(envelope)
        } else {
            try {
                CompletableFuture.completedFuture(publisher.publish(envelope))
            } catch (ex: Exception) {
                CompletableFuture.failedFuture(ex)
            }
        }
    }

    private fun completedStreamAckResponse(
        started: Long,
        response: PlatformHotPathResponse
    ): CompletableFuture<PlatformHotPathResponse> {
        HotPathMetrics.record("api.streamAck.total", System.nanoTime() - started)
        return CompletableFuture.completedFuture(response)
    }

    private fun accountRiskViolation(request: AccountRiskCheckRequest): BoundaryError? {
        return accountRiskCheck.evaluate(request).toBoundaryError()
    }

    private fun commandCircuitBreakerViolation(request: AccountRiskCheckRequest): BoundaryError? {
        return commandCircuitBreakerCheck.evaluate(
            CommandCircuitBreakerRequest(
                clientId = request.clientId,
                route = request.route,
                commandType = request.commandType,
                commandId = request.commandId,
                correlationId = request.correlationId,
                venueSessionId = request.venueSessionId,
                instrumentId = request.instrumentId
            )
        )
    }

    private fun instrumentPriceCollarViolation(request: AccountRiskCheckRequest): BoundaryError? {
        return instrumentPriceCollarCheck.evaluate(
            InstrumentPriceCollarRequest(
                clientId = request.clientId,
                route = request.route,
                commandType = request.commandType,
                commandId = request.commandId,
                correlationId = request.correlationId,
                instrumentId = request.instrumentId,
                limitPrice = request.limitPrice,
                currency = request.currency
            )
        )
    }

    private fun recordGuardrailRejection(
        guardrailType: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ) {
        try {
            val scope = rejectionScope(guardrailType, request, error)
            boundaryRejectionLog.recordRejection(
                guardrailType = guardrailType,
                scopeType = scope.first,
                scopeId = scope.second,
                request = request,
                error = error
            )
        } catch (ex: Exception) {
            System.err.println(
                "boundary_rejection_audit_failed guardrailType=$guardrailType route=${request.route} clientId=${request.clientId} commandId=${JsonFields.escape(request.commandId)} message=${JsonFields.escape(ex.message ?: "unknown")}"
            )
        }
    }

    private fun rejectionScope(
        guardrailType: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ): Pair<String, String> {
        if (guardrailType == "instrument-price-collar") {
            return "INSTRUMENT" to request.instrumentId
        }
        val match = Regex("for ([A-Z_]+):([^:]+)(:|$)").find(error.message)
        if (match != null) {
            return match.groupValues[1] to match.groupValues[2]
        }
        return when {
            request.instrumentId.isNotBlank() -> "INSTRUMENT" to request.instrumentId
            request.venueSessionId.isNotBlank() -> "VENUE_SESSION" to request.venueSessionId
            else -> "GLOBAL" to "*"
        }
    }

    private fun accountRiskRequest(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        body: String,
        envelope: StreamCommandEnvelope?,
        preParsedJson: JsonDocument? = null
    ): AccountRiskCheckRequest {
        val json = preParsedJson ?: JsonCodec.parseObjectOrEmpty(body)
        val commandType = envelope?.commandType ?: commandType(route)
        return AccountRiskCheckRequest(
            clientId = clientId,
            route = route,
            commandType = commandType,
            commandId = envelope?.commandId ?: json.string("commandId"),
            idempotencyKey = idempotencyKey,
            correlationId = correlationId,
            actorId = envelope?.actorId ?: json.string("actorId"),
            participantId = json.string("participantId"),
            accountId = json.string("accountId"),
            botId = envelope?.botId ?: json.string("botId"),
            botVersion = envelope?.botVersion ?: json.string("botVersion"),
            runId = envelope?.runId ?: json.string("runId").ifBlank { json.string("scenarioRunId") },
            venueSessionId = envelope?.venueSessionId ?: json.string("venueSessionId"),
            instrumentId = envelope?.instrumentId ?: json.string("instrumentId"),
            orderId = envelope?.orderId ?: json.string("orderId"),
            quantityUnits = json.string("quantityUnits"),
            limitPrice = json.string("limitPrice"),
            currency = json.string("currency"),
            payloadHash = envelope?.payloadHash ?: sha256Hex(body)
        )
    }

    private fun commandType(route: String): String {
        return when {
            route.endsWith("/orders/submit") -> "SubmitOrder"
            route.endsWith("/orders/cancel") -> "CancelOrder"
            route.endsWith("/orders/modify") -> "ModifyOrder"
            else -> "UnknownCommand"
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun handleAcceptedAsyncMutation(
        exchange: HttpExchange,
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ) {
        val intake = acceptedAsyncCommandIntake
        if (intake == null) {
            writeJson(exchange, 503, simpleErrorJson("accepted async intake unavailable"))
            return
        }

        val existing = intake.findCommandStatus(clientId, route, idempotencyKey)
        if (existing != null) {
            writeDuplicateCommandReservation(exchange, clientId, route, idempotencyKey, existing, correlationId)
            return
        }

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, null)
        val breakerViolation = HotPathMetrics.time("api.acceptedAsync.commandCircuitBreaker") {
            commandCircuitBreakerViolation(riskRequest)
        }
        if (breakerViolation != null) {
            recordGuardrailRejection("command-circuit-breaker", riskRequest, breakerViolation)
            writeJson(exchange, breakerViolation.status, boundary.toErrorJson(breakerViolation, correlationId))
            return
        }

        val collarViolation = HotPathMetrics.time("api.acceptedAsync.instrumentPriceCollar") {
            instrumentPriceCollarViolation(riskRequest)
        }
        if (collarViolation != null) {
            recordGuardrailRejection("instrument-price-collar", riskRequest, collarViolation)
            writeJson(exchange, collarViolation.status, boundary.toErrorJson(collarViolation, correlationId))
            return
        }

        val riskViolation = HotPathMetrics.time("api.acceptedAsync.accountRisk") {
            accountRiskViolation(riskRequest)
        }
        if (riskViolation != null) {
            writeJson(exchange, riskViolation.status, boundary.toErrorJson(riskViolation, correlationId))
            return
        }

        val abuseViolation = HotPathMetrics.time("api.abuse.allow") {
            abuseProtectionHook.allow(clientId, route)
        }
        if (abuseViolation != null) {
            writeJson(exchange, abuseViolation.status, boundary.toErrorJson(abuseViolation, correlationId))
            return
        }

        val receipt = try {
            HotPathMetrics.time("api.acceptedAsync.reserve") {
                intake.enqueueSubmit(clientId, route, idempotencyKey, correlationId, body)
            }
        } catch (ex: Exception) {
            val errorClass = ex::class.simpleName ?: "Exception"
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "accepted_async_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId errorClass=$errorClass message=${JsonFields.escape(errorMessage)}"
            )
            writeJson(exchange, 503, simpleErrorJson("accepted async unavailable", errorMessage))
            return
        }

        if (receipt.duplicate) {
            writeDuplicateCommandReservation(exchange, clientId, route, idempotencyKey, receipt.status, correlationId)
            return
        }
        if (receipt.backpressure) {
            writeJson(
                exchange,
                429,
                boundary.toErrorJson(
                    BoundaryError(429, "COMMAND_INTAKE_BACKPRESSURE", "accepted async lane queue is full"),
                    correlationId
                )
            )
            return
        }

        val status = receipt.status
        if (!receipt.accepted || status == null) {
            writeJson(exchange, 503, simpleErrorJson("accepted async command not accepted"))
            return
        }

        writeJson(exchange, 202, CommandStatusResponse.acceptedJson(status))
    }

    private fun handleAcceptedAsyncMutationResponse(
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ): PlatformHotPathResponse {
        val intake = acceptedAsyncCommandIntake
            ?: return PlatformHotPathResponse(503, simpleErrorJson("accepted async intake unavailable"))

        val existing = intake.findCommandStatus(clientId, route, idempotencyKey)
        if (existing != null) {
            return duplicateCommandReservationResponse(clientId, route, idempotencyKey, existing, correlationId)
        }

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, null)
        val breakerViolation = HotPathMetrics.time("api.acceptedAsync.commandCircuitBreaker") {
            commandCircuitBreakerViolation(riskRequest)
        }
        if (breakerViolation != null) {
            recordGuardrailRejection("command-circuit-breaker", riskRequest, breakerViolation)
            return PlatformHotPathResponse(breakerViolation.status, boundary.toErrorJson(breakerViolation, correlationId))
        }

        val collarViolation = HotPathMetrics.time("api.acceptedAsync.instrumentPriceCollar") {
            instrumentPriceCollarViolation(riskRequest)
        }
        if (collarViolation != null) {
            recordGuardrailRejection("instrument-price-collar", riskRequest, collarViolation)
            return PlatformHotPathResponse(collarViolation.status, boundary.toErrorJson(collarViolation, correlationId))
        }

        val riskViolation = HotPathMetrics.time("api.acceptedAsync.accountRisk") {
            accountRiskViolation(riskRequest)
        }
        if (riskViolation != null) {
            return PlatformHotPathResponse(riskViolation.status, boundary.toErrorJson(riskViolation, correlationId))
        }

        val abuseViolation = HotPathMetrics.time("api.abuse.allow") {
            abuseProtectionHook.allow(clientId, route)
        }
        if (abuseViolation != null) {
            return PlatformHotPathResponse(abuseViolation.status, boundary.toErrorJson(abuseViolation, correlationId))
        }

        val receipt = try {
            HotPathMetrics.time("api.acceptedAsync.reserve") {
                intake.enqueueSubmit(clientId, route, idempotencyKey, correlationId, body)
            }
        } catch (ex: Exception) {
            val errorClass = ex::class.simpleName ?: "Exception"
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "accepted_async_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId errorClass=$errorClass message=${JsonFields.escape(errorMessage)}"
            )
            return PlatformHotPathResponse(503, simpleErrorJson("accepted async unavailable", errorMessage))
        }

        if (receipt.duplicate) {
            return duplicateCommandReservationResponse(clientId, route, idempotencyKey, receipt.status, correlationId)
        }
        if (receipt.backpressure) {
            return PlatformHotPathResponse(
                429,
                boundary.toErrorJson(
                    BoundaryError(429, "COMMAND_INTAKE_BACKPRESSURE", "accepted async lane queue is full"),
                    correlationId
                )
            )
        }

        val status = receipt.status
        if (!receipt.accepted || status == null) {
            return PlatformHotPathResponse(503, simpleErrorJson("accepted async command not accepted"))
        }

        return PlatformHotPathResponse(202, CommandStatusResponse.acceptedJson(status))
    }

    private fun markStreamCommandPublished(commandId: String, streamSequence: Long) {
        val marker = streamCommandPublicationMarker ?: return
        if (streamCommandMarkPublishedMode.trim().lowercase() == "async") {
            HotPathMetrics.time("api.streamAck.enqueuePublished") {
                marker.markPublishedByCommandId(commandId, streamSequence)
            }
            return
        }
        HotPathMetrics.time("api.streamAck.markPublished") {
            marker.markPublishedByCommandId(commandId, streamSequence)
        }
    }

    private fun writeDuplicateCommandReservation(
        exchange: HttpExchange,
        clientId: String,
        route: String,
        idempotencyKey: String,
        status: CommandStatusView?,
        correlationId: String
    ) {
        if (status?.status == CommandLogStatus.COMPLETED && status.responseStatus > 0) {
            idempotencyStore.save(
                clientId,
                route,
                idempotencyKey,
                IdempotencyResult(status.responseStatus, status.responsePayloadJson),
                idempotencyRetentionPolicy.ttlFor(route)
            )
            writeJson(exchange, status.responseStatus, status.responsePayloadJson)
            return
        }
        if (status != null && commandProcessingMode in setOf(CommandProcessingMode.CapturedAck, CommandProcessingMode.AcceptedAsync)) {
            val payload = CommandStatusResponse.acceptedJson(status)
            writeJson(exchange, 202, payload)
            return
        }
        val cached = idempotencyStore.find(clientId, route, idempotencyKey)
        if (cached != null) {
            writeJson(exchange, cached.status, cached.payload)
            return
        }
        writeJson(
            exchange,
            409,
            boundary.toErrorJson(
                BoundaryError(
                    409,
                    "COMMAND_ALREADY_IN_PROGRESS",
                    "command is already received or processing"
                ),
                correlationId
            )
        )
    }

    private fun duplicateCommandReservationResponse(
        clientId: String,
        route: String,
        idempotencyKey: String,
        status: CommandStatusView?,
        correlationId: String
    ): PlatformHotPathResponse {
        if (status?.status == CommandLogStatus.COMPLETED && status.responseStatus > 0) {
            idempotencyStore.save(
                clientId,
                route,
                idempotencyKey,
                IdempotencyResult(status.responseStatus, status.responsePayloadJson),
                idempotencyRetentionPolicy.ttlFor(route)
            )
            return PlatformHotPathResponse(status.responseStatus, status.responsePayloadJson)
        }
        if (status != null && commandProcessingMode in setOf(CommandProcessingMode.CapturedAck, CommandProcessingMode.AcceptedAsync)) {
            return PlatformHotPathResponse(202, CommandStatusResponse.acceptedJson(status))
        }
        val cached = idempotencyStore.find(clientId, route, idempotencyKey)
        if (cached != null) {
            return PlatformHotPathResponse(cached.status, cached.payload)
        }
        return PlatformHotPathResponse(
            409,
            boundary.toErrorJson(
                BoundaryError(
                    409,
                    "COMMAND_ALREADY_IN_PROGRESS",
                    "command is already received or processing"
                ),
                correlationId
            )
        )
    }

    private fun handleCancelByClientOrder(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            methodNotAllowed(exchange)
            return
        }
        val requestBody = readRequestBody(exchange) ?: return
        val request = JsonCodec.parseObjectOrEmpty(requestBody)
        val correlationId = correlationId(exchange)
        val participantId = request.string("participantId")
        val clientOrderId = request.string("clientOrderId")
        if (participantId.isBlank() || clientOrderId.isBlank()) {
            val missing = if (participantId.isBlank()) "participantId" else "clientOrderId"
            writeJson(
                exchange,
                400,
                boundary.toErrorJson(
                    BoundaryError(400, "VALIDATION_ERROR", "missing required field: $missing"),
                    correlationId
                )
            )
            return
        }
        val resolved = api.findOrderByClientOrderId(participantId, clientOrderId)
        if (resolved == null || resolved.runId.isBlank() || resolved.venueSessionId.isBlank()) {
            writeJson(exchange, 404, simpleErrorJson("client order not found"))
            return
        }
        val synthesizedCancelBody = JsonCodec.writeObject(
            "commandId" to request.string("commandId"),
            "traceId" to request.string("traceId"),
            "causationId" to request.string("causationId"),
            "correlationId" to request.string("correlationId"),
            "actorId" to request.string("actorId"),
            "occurredAt" to request.string("occurredAt").ifBlank { java.time.Instant.now().toString() },
            "orderId" to resolved.orderId,
            "reason" to request.string("reason"),
            "runId" to resolved.runId,
            "venueSessionId" to resolved.venueSessionId,
            "instrumentId" to resolved.instrumentId,
            "clientOrderId" to clientOrderId
        )
        handleApiV1Mutation(exchange, "/api/v1/orders/cancel", presetBody = synthesizedCancelBody) { body ->
            api.cancelOrder(body)
        }
    }

    private fun handleCommandStatusLookup(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            methodNotAllowed(exchange)
            return
        }
        if (!allowApiV1Read(exchange, "/api/v1/commands/{commandId}")) {
            return
        }
        val commandId = exchange.requestURI.path.removePrefix("/api/v1/commands/").trim('/')
        if (commandId.isBlank()) {
            writeJson(exchange, 404, simpleErrorJson("command not found"))
            return
        }
        val status = commandStatus(commandId)
        if (status == null) {
            writeJson(exchange, 404, simpleErrorJson("command not found"))
            return
        }
        val scopeError = commandStatusReadScopeError(exchange.requestHeaders, status)
        if (scopeError != null) {
            writeJson(exchange, scopeError.status, boundary.toErrorJson(scopeError, correlationId(exchange.requestHeaders)))
            return
        }
        writeJson(exchange, 200, CommandStatusResponse.statusJson(status))
    }

    private fun commandStatusLookupResponse(request: PlatformHotPathRequest): PlatformHotPathResponse {
        if (request.method != "GET") {
            return methodNotAllowedResponse()
        }
        val readError = apiV1ReadErrorResponse(request, "/api/v1/commands/{commandId}")
        if (readError != null) {
            return readError
        }
        val commandId = request.path.removePrefix("/api/v1/commands/").trim('/')
        if (commandId.isBlank()) {
            return PlatformHotPathResponse(404, simpleErrorJson("command not found"))
        }
        val status = commandStatus(commandId)
            ?: return PlatformHotPathResponse(404, simpleErrorJson("command not found"))
        val scopeError = commandStatusReadScopeError(request.headers, status)
        if (scopeError != null) {
            return PlatformHotPathResponse(
                scopeError.status,
                boundary.toErrorJson(scopeError, correlationId(request.headers))
            )
        }
        return PlatformHotPathResponse(200, CommandStatusResponse.statusJson(status))
    }

    private fun commandStatus(commandId: String): CommandStatusView? {
        val lookup = acceptedAsyncCommandIntake ?: commandStatusLookup
        val capturedStatus = lookup?.findCommandStatus(commandId)
        try {
            api.canonicalCommandOutcome(commandId)?.let { outcome ->
                return outcome.toStatusView().withReadScopeFrom(capturedStatus)
            }
            api.canonicalCommandResult(commandId)?.let { result ->
                return result.toStatusView().withReadScopeFrom(capturedStatus)
            }
            api.venueEventBatchCommandReference(commandId)?.let { reference ->
                return reference.toStatusView().withReadScopeFrom(capturedStatus)
            }
        } catch (ex: Exception) {
            System.err.println("canonical_command_status_lookup_failed commandId=$commandId message=${ex.message ?: "unknown"}")
        }
        capturedStatus?.let { return it }
        if (commandProcessingMode == CommandProcessingMode.StreamAck) {
            streamCommandIntakeStore?.findByCommandId(commandId)?.let { reference ->
                if (reference.streamSequence > 0L) {
                    return reference.toStatusView()
                }
            }
        }
        return null
    }

    private fun CommandStatusView.withReadScopeFrom(fallback: CommandStatusView?): CommandStatusView {
        if (fallback == null) return this
        return copy(
            clientId = clientId.ifBlank { fallback.clientId },
            route = route.ifBlank { fallback.route },
            idempotencyKey = idempotencyKey.ifBlank { fallback.idempotencyKey },
            participantId = participantId.ifBlank { fallback.participantId }
        )
    }

    private fun rememberIdempotentResult(exchange: HttpExchange, route: String, status: Int, payload: String) {
        val clientId = boundary.clientId(exchange.requestHeaders) ?: return
        val idempotencyKey = boundary.idempotencyKey(exchange.requestHeaders) ?: return
        idempotencyStore.save(
            clientId,
            route,
            idempotencyKey,
            IdempotencyResult(status, payload),
            idempotencyRetentionPolicy.ttlFor(route)
        )
    }

    private fun rejectCode(payload: String): String? {
        if (!payload.contains("\"rejected\"")) return null
        return JsonCodec.parseObjectOrEmpty(payload).obj("rejected").string("code").ifBlank { null }
    }

    // Account-risk/circuit-breaker/price-collar admin routes and protective-control
    // audit logging live in RiskGuardrailGateway.kt (see field above). The inline
    // hot-path violation checks stay here — they move with OrderCommandDispatcher.


    // asyncCommandStatsJson lives in DiagnosticsGateway.kt (see field above).


    private fun commandIntakeBackpressure(): BoundaryError? {
        if (commandIntakeMaxActive <= 0L && commandIntakeMaxStaleProcessing <= 0L) {
            return null
        }
        val queue = capturedCommandQueue ?: return null
        val snapshot = commandIntakeBackpressureSnapshot(queue)
        if (commandIntakeMaxStaleProcessing > 0L && snapshot.staleProcessing >= commandIntakeMaxStaleProcessing) {
            return BoundaryError(
                429,
                "COMMAND_INTAKE_BACKPRESSURE",
                "command intake rejected because stale processing count is ${snapshot.staleProcessing}"
            )
        }
        if (commandIntakeMaxActive > 0L && snapshot.active >= commandIntakeMaxActive) {
            return BoundaryError(
                429,
                "COMMAND_INTAKE_BACKPRESSURE",
                "command intake rejected because active command queue depth is ${snapshot.active}"
            )
        }
        return null
    }

    private fun streamCommandBackpressure(): BoundaryError? {
        val health = streamCommandBackpressureSnapshot() ?: return null
        if (!health.available) {
            return BoundaryError(
                503,
                "STREAM_COMMAND_STREAM_UNAVAILABLE",
                "stream command intake rejected because ${health.streamName} is unavailable"
            )
        }
        if (
            streamCommandMaxStorageUtilization > 0.0 &&
            health.maxBytes > 0L &&
            health.storageUtilization >= streamCommandMaxStorageUtilization
        ) {
            return BoundaryError(
                429,
                "STREAM_COMMAND_BACKPRESSURE",
                "stream command intake rejected because storage utilization is ${health.storageUtilization}"
            )
        }
        val drainBackpressure = streamCommandDrainBackpressure()
        if (drainBackpressure != null) {
            return drainBackpressure
        }
        return null
    }

    private fun streamCommandDrainBackpressure(): BoundaryError? {
        val projectorLagCanGate =
            streamCommandDrainBackpressurePolicy == StreamCommandDrainBackpressurePolicy.ControlRoomFresh
        if (streamCommandMaxWorkerStreamLag <= 0L && (!projectorLagCanGate || streamCommandMaxProjectorLag <= 0L)) {
            return null
        }
        val snapshot = streamCommandDrainBackpressureSnapshot() ?: return null
        return snapshot.backpressure(
            maxWorkerStreamLag = streamCommandMaxWorkerStreamLag,
            maxProjectorLag = streamCommandMaxProjectorLag,
            policy = streamCommandDrainBackpressurePolicy
        )
    }

    private fun streamCommandDrainBackpressureSnapshot(): StreamCommandDrainBackpressureSnapshot? {
        return streamDrainBackpressureSnapshot?.snapshot
    }

    private fun streamCommandBackpressureSnapshot(): StreamCommandHealthSnapshot? {
        val healthCheck = streamCommandHealthCheck ?: return null
        val now = System.currentTimeMillis()
        streamBackpressureSnapshot?.let { cached ->
            if (streamCommandBackpressureSampleMs > 0L && now - cached.sampledAtMs < streamCommandBackpressureSampleMs) {
                return cached.snapshot
            }
        }
        return synchronized(streamBackpressureSnapshotLock) {
            val lockedNow = System.currentTimeMillis()
            streamBackpressureSnapshot?.let { cached ->
                if (streamCommandBackpressureSampleMs > 0L && lockedNow - cached.sampledAtMs < streamCommandBackpressureSampleMs) {
                    return@synchronized cached.snapshot
                }
            }
            val sampled = HotPathMetrics.time("api.streamAck.backpressureSnapshot") {
                healthCheck.snapshot()
            }
            streamBackpressureSnapshot = CachedStreamCommandHealthSnapshot(lockedNow, sampled)
            sampled
        }
    }

    private fun buildStreamCommandDrainBackpressureSampler(): StreamCommandDrainBackpressureSampler? {
        val projectorLagCanGate =
            streamCommandDrainBackpressurePolicy == StreamCommandDrainBackpressurePolicy.ControlRoomFresh
        if (streamCommandMaxWorkerStreamLag <= 0L && (!projectorLagCanGate || streamCommandMaxProjectorLag <= 0L)) {
            return null
        }
        val workerSources = if (streamCommandMaxWorkerStreamLag > 0L) {
            streamCommandBackpressureWorkerDurableNames().mapIndexedNotNull { index, durableName ->
                val partition = partitionFromDurableName(durableName) ?: index
                StreamCommandWorkerFactory.sourceForPartition(streamCommandConfig, partition, durableName)
                    as? StreamCommandTelemetrySource
            }
        } else {
            emptyList()
        }
        val projectionStatusProvider: (() -> ProjectionStatus?)? = if (projectorLagCanGate && streamCommandMaxProjectorLag > 0L) {
            { api.projectionStatus(streamAckProjectionName, emptyList(), streamAckProjectionSource.configValue) }
        } else {
            null
        }
        if (workerSources.isEmpty() && projectionStatusProvider == null) {
            return null
        }
        return StreamCommandDrainBackpressureSampler(workerSources, projectionStatusProvider)
    }

    private fun streamCommandBackpressureWorkerDurableNames(): List<String> {
        return streamCommandBackpressureWorkerDurables
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun partitionFromDurableName(durableName: String): Int? {
        val suffix = Regex("""-p(\d+)$""").find(durableName) ?: return null
        return suffix.groupValues[1].toIntOrNull()
    }

    private fun commandIntakeBackpressureSnapshot(queue: CapturedCommandQueue): CommandIntakeBackpressureSnapshot {
        val now = System.currentTimeMillis()
        backpressureSnapshot?.let { cached ->
            if (commandIntakeBackpressureSampleMs > 0L && now - cached.sampledAtMs < commandIntakeBackpressureSampleMs) {
                return cached
            }
        }
        return synchronized(backpressureSnapshotLock) {
            val lockedNow = System.currentTimeMillis()
            backpressureSnapshot?.let { cached ->
                if (commandIntakeBackpressureSampleMs > 0L && lockedNow - cached.sampledAtMs < commandIntakeBackpressureSampleMs) {
                    return@synchronized cached
                }
            }
            val sampled = HotPathMetrics.time("api.commandIntake.backpressureSnapshot") {
                if (commandIntakeMaxStaleProcessing > 0L) {
                    val accounting = queue.accountingSnapshot()
                    CommandIntakeBackpressureSnapshot(
                        active = accounting.active,
                        staleProcessing = accounting.staleProcessing,
                        sampledAtMs = lockedNow
                    )
                } else {
                    val counts = queue.statusCounts()
                    CommandIntakeBackpressureSnapshot(
                        active = counts.getOrDefault(CommandLogStatus.RECEIVED, 0L) +
                            counts.getOrDefault(CommandLogStatus.PROCESSING, 0L),
                        staleProcessing = 0L,
                        sampledAtMs = lockedNow
                    )
                }
            }
            backpressureSnapshot = sampled
            sampled
        }
    }

    // commandAccountingJson/dbPoolStatsJson/streamCommandHealthJson/streamCommandWorkerStatsJson/
    // venueEventMaterializerStatsJson/marketDataProjectorStatusJson/orderLifecycleProjectorStatusJson
    // live in DiagnosticsGateway.kt; startStreamCommandWorkers/startCanonicalProjector/
    // startMarketDataProjector/startOrderLifecycleProjector/startVenueEventMaterializer/
    // projectorPartitions/streamWorkerPartitions live in RuntimeLoopStarter.kt (see fields above).


    private fun startStreamCommandDrainBackpressureSampler() {
        val sampler = streamCommandDrainBackpressureSampler ?: return
        if (!streamDrainBackpressureSamplerStarted.compareAndSet(false, true)) return
        thread(name = "reef-stream-drain-backpressure-sampler", isDaemon = true) {
            while (true) {
                try {
                    val sampled = HotPathMetrics.time("streamDrainBackpressure.sample") {
                        sampler.snapshot()
                    }
                    streamDrainBackpressureSnapshot = CachedStreamCommandDrainBackpressureSnapshot(
                        sampledAtMs = System.currentTimeMillis(),
                        snapshot = sampled
                    )
                } catch (ex: Exception) {
                    System.err.println(
                        "stream_drain_backpressure_sample_failed message=${ex.message ?: "unknown"}"
                    )
                }
                Thread.sleep(streamCommandDrainBackpressureSampleMs.coerceAtLeast(50L))
            }
        }
    }

}

private const val DEFAULT_BODY_BUFFER_BYTES = 8192

internal fun rootCause(failure: Throwable): Throwable {
    var current = failure
    while (
        current.cause != null &&
        (current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException)
    ) {
        current = current.cause ?: return current
    }
    return current
}

internal fun rootMessage(failure: Throwable): String {
    return rootCause(failure).message ?: "unknown"
}

// Unlike queryLimit, this never lets an absent, non-positive, or
// caller-supplied huge limit reach a downstream query unbounded: some
// internal domain signatures (e.g. ordersForParticipant/
// executionsForParticipant) treat limit=0 as "no LIMIT clause at all",
// which is safe for trusted internal callers but not for a value taken
// directly off an external request's query string.
internal fun boundedQueryLimit(raw: String, defaultValue: Int, max: Int = 500): Int {
    val parsed = raw.toIntOrNull()
    if (parsed == null || parsed <= 0) return defaultValue
    return parsed.coerceAtMost(max)
}

// defaultBoundary/defaultAdminHttpAuth/defaultArenaAdminService/
// defaultAnalyticsRunExportService/defaultSettlementFactStore (composition-root
// wiring) live in PlatformHttpServerBootstrap.kt.

internal fun JsonDocument.requiredLong(key: String): Long {
    return string(key).toLongOrNull() ?: throw IllegalArgumentException("$key must be an integer")
}

internal fun JsonDocument.requiredInt(key: String): Int {
    return string(key).toIntOrNull() ?: throw IllegalArgumentException("$key must be an integer")
}

internal fun JsonDocument.boolean(key: String): Boolean {
    return string(key).equals("true", ignoreCase = true)
}

internal fun JsonDocument.booleanOrDefault(key: String, fallback: Boolean): Boolean {
    return if (has(key)) boolean(key) else fallback
}

data class ServerBoundaryDeps(
    val boundary: ExternalApiBoundary,
    val abuseProtectionHook: AbuseProtectionHook,
    val accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
    val accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
    val accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
    val commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
    val commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
    val instrumentPriceCollarCheck: InstrumentPriceCollarCheck = AllowAllInstrumentPriceCollarCheck(),
    val instrumentPriceCollarStore: InstrumentPriceCollarStore? = instrumentPriceCollarCheck as? InstrumentPriceCollarStore,
    val arenaAdminService: AdminApplicationService? = null,
    val adminAuthService: AdminAuthService? = null,
    val adminIdentityService: AdminIdentityService? = null,
    val adminGitHubOAuthClient: AdminGitHubOAuthClient? = null,
    val analyticsRunExportService: SimulationRunExportService? = null,
    val settlementFactStore: SettlementFactStore? = null,
    val settlementObligationMaterializer: TradeSettlementObligationMaterializer? = null,
    val postTradeProfileResolver: PostTradeProfileResolver =
        PostTradeProfileResolver.envOnly(
            RuntimeEnv.string("POST_TRADE_PROFILE", DefaultPostTradeProfileId).trim().ifBlank { DefaultPostTradeProfileId },
            RuntimeEnv.int("POST_TRADE_POLICY_VERSION", DefaultPostTradePolicyVersion, min = 1)
        ),
    val scenarioRunPostTradeProfileLookup: (String) -> String? = { null },
    val venueSessionPostTradeProfileLookup: (String) -> String? = { null },
    val boundaryRejectionLog: BoundaryRejectionLog = NoopBoundaryRejectionLog(),
    val idempotencyStore: IdempotencyStore,
    val idempotencyRetentionPolicy: IdempotencyRetentionPolicy,
    val commandCaptureStore: CommandCaptureStore,
    val commandStatusLookup: CommandStatusLookup? = commandCaptureStore as? CommandStatusLookup,
    val capturedCommandQueue: CapturedCommandQueue? = commandCaptureStore as? CapturedCommandQueue,
    val streamCommandIntakeStore: StreamCommandIntakeStore? = null,
    val streamCommandPublisher: StreamCommandPublisher? = null,
    val streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
    val streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
    val streamCommandMarkPublishedMode: String = RuntimeEnv.string("STREAM_ACK_MARK_PUBLISHED_MODE", "sync"),
    val streamCommandMaxStorageUtilization: Double =
        RuntimeEnv.string("STREAM_ACK_MAX_STORAGE_UTILIZATION", "0.95").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.95,
    val streamCommandBackpressureSampleMs: Long =
        RuntimeEnv.long("STREAM_ACK_BACKPRESSURE_SAMPLE_MS", 100L, min = 0L),
    val streamCommandMaxWorkerStreamLag: Long =
        RuntimeEnv.long("STREAM_ACK_MAX_WORKER_STREAM_LAG", 0L, min = 0L),
    val streamCommandMaxProjectorLag: Long =
        RuntimeEnv.long("STREAM_ACK_MAX_PROJECTOR_LAG", 0L, min = 0L),
    val streamCommandDrainBackpressurePolicy: StreamCommandDrainBackpressurePolicy =
        StreamCommandDrainBackpressurePolicy.fromConfig(
            RuntimeEnv.string(
                "STREAM_ACK_DRAIN_BACKPRESSURE_POLICY",
                StreamCommandDrainBackpressurePolicy.ControlRoomFresh.configValue
            )
        ),
    val streamCommandDrainBackpressureSampleMs: Long =
        RuntimeEnv.long("STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS", 500L, min = 0L),
    val streamCommandBackpressureWorkerDurables: String =
        RuntimeEnv.string("STREAM_ACK_BACKPRESSURE_WORKER_DURABLES", ""),
    val commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult
)

internal fun localDevAdminAuthBypassEnabled(lookup: (String) -> String? = { key -> System.getenv(key) }): Boolean {
    if (!RuntimeEnv.bool("LOCAL_DEV_ADMIN_AUTH_BYPASS", false, lookup)) return false
    val profile = listOf(
        "PLATFORM_RUNTIME_PROFILE",
        "REEF_ENV",
        "REEF_DEPLOYMENT_ENV",
        "DEPLOYMENT_ENV",
        "ENVIRONMENT",
        "APP_ENV",
        "PROFILE"
    ).firstNotNullOfOrNull { key -> lookup(key)?.trim()?.takeIf { it.isNotBlank() } }
        ?.lowercase()
        .orEmpty()
    return profile in localDeploymentProfiles
}
