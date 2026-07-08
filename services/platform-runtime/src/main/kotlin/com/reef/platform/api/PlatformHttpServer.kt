package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.admin.AdminActor
import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.application.admin.ArenaBotRegistrationCommand
import com.reef.platform.application.admin.ArenaBotVersionRegistrationCommand
import com.reef.platform.application.admin.ArenaBotVersionDecisionCommand
import com.reef.platform.application.admin.ArenaRunEnforcementEventIngestionCommand
import com.reef.platform.application.admin.ArenaRunBotResultIngestionCommand
import com.reef.platform.application.admin.ArenaRunRegistrationCommand
import com.reef.platform.application.admin.ArenaRunStatusCommand
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
import com.reef.platform.application.settlement.SettlementAttemptStartedFact
import com.reef.platform.application.settlement.SettlementBreakOpenedFact
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
import java.net.InetAddress
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
    val tokenFamily: String
)

internal fun adminGatewayRouteFor(path: String): AdminGatewayRoute? = when (path) {
    "/admin/v1/arena/bots" -> AdminGatewayRoute("/internal/admin/arena/bots", "arena")
    "/admin/v1/arena/bots/openbao-provision" ->
        AdminGatewayRoute("/internal/admin/arena/bots/openbao-provision", "arena")
    "/admin/v1/analytics/run-exports" -> AdminGatewayRoute("/internal/admin/analytics/run-exports", "analytics")
    "/admin/v1/risk/account-controls" -> AdminGatewayRoute("/internal/admin/account-risk/controls", "admin")
    "/admin/v1/risk/circuit-breakers" -> AdminGatewayRoute("/internal/admin/circuit-breakers", "admin")
    "/admin/v1/risk/price-collars" -> AdminGatewayRoute("/internal/admin/price-collars", "admin")
    else -> null
}

private data class PreparedApiV1Mutation(
    val route: String,
    val clientId: String,
    val idempotencyKey: String,
    val correlationId: String,
    val body: String
)

private sealed class PreparedApiV1MutationResult {
    data class Prepared(val request: PreparedApiV1Mutation) : PreparedApiV1MutationResult()
    data class Rejected(val response: PlatformHotPathResponse) : PreparedApiV1MutationResult()
}

private data class AdminRequestPrincipal(
    val actorId: String,
    val correlationId: String,
    val occurredAt: String
)

private val adminRequestPrincipal = ThreadLocal<AdminRequestPrincipal?>()

private enum class InternalHttpExposureMode {
    Disabled,
    LocalOnly,
    Enabled;

    companion object {
        fun fromEnv(): InternalHttpExposureMode {
            return when (RuntimeEnv.string("PLATFORM_INTERNAL_HTTP_MODE", "local").trim().lowercase()) {
                "disabled", "off", "false", "0" -> Disabled
                "enabled", "all", "raw-external" -> Enabled
                else -> LocalOnly
            }
        }
    }
}

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
    private val legacyMutationRoutesEnabled: Boolean = RuntimeEnv.bool("PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED", false)
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
    private val diagnosticRoutes: PlatformDiagnosticRoutes by lazy {
        PlatformDiagnosticRoutes(
            healthJson = { api.health() },
            readinessJson = { readinessJson() },
            abuseStatsJson = { abuseStatsJson(abuseProtectionHook.stats()) },
            accountRiskControlsJson = { accountRiskControlsJson() },
            accountRiskDecisionsJson = { limit -> accountRiskDecisionsJson(limit) },
            commandCircuitBreakersJson = { commandCircuitBreakersJson() },
            instrumentPriceCollarsJson = { instrumentPriceCollarsJson() },
            setAccountRiskControlJson = { body -> setAccountRiskControlResponse(body) },
            setCommandCircuitBreakerJson = { body -> setCommandCircuitBreakerResponse(body) },
            setInstrumentPriceCollarJson = { body -> setInstrumentPriceCollarResponse(body) },
            registerArenaBotJson = { body -> registerArenaBotResponse(body) },
            arenaBotJson = { query -> arenaBotResponse(query) },
            registerArenaBotVersionJson = { body -> registerArenaBotVersionResponse(body) },
            arenaBotVersionJson = { query -> arenaBotVersionResponse(query) },
            transitionArenaBotVersionJson = { body -> transitionArenaBotVersionResponse(body) },
            arenaQualificationReportsJson = { query -> arenaQualificationReportsResponse(query) },
            arenaOperatorDecisionsJson = { query -> arenaOperatorDecisionsResponse(query) },
            arenaRuntimeConfigDescriptorsJson = { query -> arenaRuntimeConfigDescriptorsResponse(query) },
            arenaRunJson = { query -> arenaRunResponse(query) },
            registerArenaRunJson = { body -> registerArenaRunResponse(body) },
            updateArenaRunStatusJson = { body -> updateArenaRunStatusResponse(body) },
            arenaRunBotResultsJson = { query -> arenaRunBotResultsResponse(query) },
            recordArenaRunBotResultJson = { body -> recordArenaRunBotResultResponse(body) },
            arenaRunEnforcementEventsJson = { query -> arenaRunEnforcementEventsResponse(query) },
            recordArenaRunEnforcementEventJson = { body -> recordArenaRunEnforcementEventResponse(body) },
            arenaLeaderboardJson = { query -> arenaLeaderboardResponse(query) },
            arenaBotOpenBaoProvisionJson = { body -> arenaBotOpenBaoProvisionResponse(body) },
            analyticsRunExportsJson = { query -> analyticsRunExportsResponse(query) },
            recordAnalyticsRunExportJson = { body -> recordAnalyticsRunExportResponse(body) },
            dbPoolStatsJson = { dbPoolStatsJson() },
            asyncCommandStatsJson = { asyncCommandStatsJson() },
            commandAccountingJson = { runId -> commandAccountingJson(runId) },
            streamCommandHealthJson = { streamCommandHealthJson() },
            streamCommandWorkerStatsJson = { streamCommandWorkerStatsJson() },
            venueEventMaterializerStatsJson = { venueEventMaterializerStatsJson() },
            projectorStatusJson = { projectorStatusJson() },
            marketDataProjectorStatsJson = { marketDataProjectorStatusJson() },
            orderLifecycleProjectorStatsJson = { orderLifecycleProjectorStatusJson() }
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

        server.createContext("/internal/admin/settlement/facts") { exchange ->
            if (!allowInternalHttpRoute(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val body = readRequestBody(exchange) ?: return@createContext
            withAdminRequestPrincipal(exchange) {
                writeHotPathResponse(exchange, appendSettlementFactsResponse(body))
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
                writeHotPathResponse(exchange, postCashSettlementRepairResponse(body))
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
                writeHotPathResponse(exchange, postSecuritySettlementRepairResponse(body))
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
                writeHotPathResponse(exchange, forceSettleResponse(body))
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
                writeHotPathResponse(exchange, reverseSettlementLedgerEntryResponse(body))
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
                writeHotPathResponse(exchange, materializeSettlementObligationsResponse(body))
            }
        }

        for (path in listOf(
            "/admin/v1/arena/bots",
            "/admin/v1/arena/bots/openbao-provision",
            "/admin/v1/analytics/run-exports",
            "/admin/v1/risk/account-controls",
            "/admin/v1/risk/circuit-breakers",
            "/admin/v1/risk/price-collars"
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
            val response = api.order(orderId)
            val status = if (response.contains("\"error\":\"order not found\"")) 404 else 200
            writeJson(exchange, status, response)
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
            val response = api.marketDataSnapshot(instrumentId, projectionName)
            val status = if (response.contains("\"error\":\"market data snapshot not found\"")) 404 else 200
            writeJson(exchange, status, response)
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
            writeHotPathResponse(exchange, settlementFactsResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/obligations/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/obligations/").trimEnd('/')
            writeHotPathResponse(exchange, settlementObligationsResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/ledger/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/ledger/").trimEnd('/')
            writeHotPathResponse(exchange, settlementLedgerResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/proof/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/proof/").trimEnd('/')
            writeHotPathResponse(exchange, settlementProofResponse(scenarioRunId))
        }

        server.createContext("/api/v1/settlement/score/") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val scenarioRunId = exchange.requestURI.path.removePrefix("/api/v1/settlement/score/").trimEnd('/')
            writeHotPathResponse(exchange, settlementScoreResponse(exchange, scenarioRunId))
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
            val response = api.marketDataDepthSnapshot(instrumentId, levels, projectionName, sourceProjectionName)
            val status = if (response.contains("\"error\":\"market data depth not found\"")) 404 else 200
            writeJson(exchange, status, response)
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
            val response = api.intradayBars(instrumentId, interval, start, end)
            val status = if (response.contains("\"error\":\"unsupported interval\"")) 400 else 200
            writeJson(exchange, status, response)
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
            val limit = queryValue(exchange, "limit").toIntOrNull() ?: 0
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
            val limit = queryValue(exchange, "limit").toIntOrNull() ?: 0
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
            val limit = queryValue(exchange, "limit").toIntOrNull() ?: 0
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
            startStreamCommandWorkers()
        }
        if (runtimeRole == PlatformRuntimeRole.Projector && streamAckProjectorEnabled && commandProcessingMode == CommandProcessingMode.StreamAck) {
            startCanonicalProjector()
        }
        if (venueEventMaterializerShouldStart()) {
            startVenueEventMaterializer()
        }
        if (marketDataProjectorShouldStart()) {
            startMarketDataProjector()
        }
        if (orderLifecycleProjectorShouldStart()) {
            startOrderLifecycleProjector()
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

    private fun streamCommandWorkerApi(): PlatformApi {
        if (!streamCommandWorkerDedicatedRuntimePoolEnabled) return api
        return PlatformApi(
            OrderApplicationService(
                runtimePersistence = defaultRuntimePersistence("stream-runtime")
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
        diagnosticRoutes.paths.forEach { path ->
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
                    val response = diagnosticRoutes.handle(
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

    private fun allowInternalHttpRoute(exchange: HttpExchange): Boolean {
        return when (internalHttpExposureMode) {
            InternalHttpExposureMode.Disabled -> {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                false
            }
            InternalHttpExposureMode.LocalOnly -> {
                if (isLoopback(exchange.remoteAddress.address)) {
                    true
                } else {
                    writeHotPathResponse(
                        exchange,
                        PlatformHotPathResponse(
                            403,
                            JsonCodec.writeObject(
                                "error" to "internal HTTP route requires loopback access",
                                "mode" to "local"
                            )
                        )
                    )
                    false
                }
            }
            InternalHttpExposureMode.Enabled -> true
        }
    }

    private fun isLoopback(address: InetAddress?): Boolean {
        return address?.isLoopbackAddress ?: false
    }

    private fun isLoopback(address: String?): Boolean {
        if (address.isNullOrBlank()) return true
        return try {
            InetAddress.getByName(address).isLoopbackAddress
        } catch (_: Exception) {
            false
        }
    }

    private fun withAdminRequestPrincipal(exchange: HttpExchange, block: () -> Unit) {
        withAdminRequestPrincipal(adminPrincipal(exchange.requestHeaders), block)
    }

    private fun <T> withAdminRequestPrincipal(principal: AdminRequestPrincipal, block: () -> T): T {
        val prior = adminRequestPrincipal.get()
        adminRequestPrincipal.set(principal)
        try {
            return block()
        } finally {
            adminRequestPrincipal.set(prior)
        }
    }

    private fun adminPrincipal(exchange: HttpExchange): AdminRequestPrincipal {
        return adminPrincipal(exchange.requestHeaders)
    }

    private fun adminPrincipal(headers: Headers): AdminRequestPrincipal {
        return AdminRequestPrincipal(
            actorId = headerValue(headers, "X-Reef-Actor-Id").ifBlank { "admin-cli" },
            correlationId = headerValue(headers, "X-Correlation-Id").ifBlank {
                headerValue(headers, "X-Reef-Correlation-Id").ifBlank { "internal-admin" }
            },
            occurredAt = headerValue(headers, "X-Reef-Occurred-At").ifBlank { Instant.now().toString() }
        )
    }

    private fun headerValue(exchange: HttpExchange, name: String): String {
        return headerValue(exchange.requestHeaders, name)
    }

    private fun headerValue(headers: Headers, name: String): String {
        return headers[name]?.firstOrNull().orEmpty()
    }

    private fun currentAdminPrincipal(): AdminRequestPrincipal {
        return adminRequestPrincipal.get() ?: AdminRequestPrincipal(
            actorId = "admin-cli",
            correlationId = "internal-admin",
            occurredAt = Instant.now().toString()
        )
    }

    private fun handleAdminGatewayRoute(exchange: HttpExchange) {
        val route = adminGatewayRouteFor(exchange.requestURI.path)
        if (route == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        if (!authorizeAdminGateway(exchange, route.tokenFamily)) return
        val body = if (exchange.requestMethod == "POST") {
            readRequestBody(exchange) ?: return
        } else {
            ""
        }
        withAdminRequestPrincipal(exchange) {
            val response = diagnosticRoutes.handle(
                method = exchange.requestMethod,
                path = route.internalPath,
                query = exchange.requestURI.query,
                body = body
            ) ?: PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "admin route not found"))
            writeHotPathResponse(exchange, response)
        }
    }

    private fun authorizeAdminGateway(exchange: HttpExchange, tokenFamily: String): Boolean {
        val envName = when (tokenFamily) {
            "analytics" -> "ANALYTICS_EXPORT_API_TOKEN"
            "admin" -> "ADMIN_API_TOKEN"
            else -> "ARENA_ADMIN_API_TOKEN"
        }
        val token = RuntimeEnv.string(envName, "")
        if (token.isBlank()) {
            writeHotPathResponse(exchange, PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "$envName is not configured")))
            return false
        }
        val expected = "Bearer $token"
        if (headerValue(exchange, "Authorization") != expected) {
            writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized")))
            return false
        }
        return true
    }

    internal fun handleHotPathRequest(request: PlatformHotPathRequest): PlatformHotPathResponse? {
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
            diagnosticRoutes.handle(request.method, request.path, request.query, request.body)
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
                val response = api.marketDataSnapshot(
                    instrumentId,
                    queryValue(request.query, "projectionName").ifBlank { "market-data-top-of-book" }
                )
                PlatformHotPathResponse(
                    status = if (response.contains("\"error\":\"market data snapshot not found\"")) 404 else 200,
                    body = response
                )
            }
            request.path.startsWith("/api/v1/market-data/depth/") && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/market-data/depth/{instrumentId}")
                if (readError != null) {
                    return readError
                }
                val instrumentId = request.path.removePrefix("/api/v1/market-data/depth/").trimEnd('/')
                val response = api.marketDataDepthSnapshot(
                    instrumentId = instrumentId,
                    levels = queryValue(request.query, "levels").toIntOrNull() ?: 5,
                    projectionName = queryValue(request.query, "projectionName").ifBlank { "market-data-depth" },
                    sourceProjectionName = queryValue(request.query, "sourceProjectionName").ifBlank { "runtime-normalized-venue-outcomes" }
                )
                PlatformHotPathResponse(
                    status = if (response.contains("\"error\":\"market data depth not found\"")) 404 else 200,
                    body = response
                )
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
            request.path.startsWith("/api/v1/market-data/bars/") && request.method == "GET" -> {
                val readError = apiV1ReadErrorResponse(request, "/api/v1/market-data/bars/{instrumentId}")
                if (readError != null) {
                    return readError
                }
                val instrumentId = request.path.removePrefix("/api/v1/market-data/bars/").trimEnd('/')
                val response = api.intradayBars(
                    instrumentId = instrumentId,
                    interval = queryValue(request.query, "interval"),
                    start = queryValue(request.query, "start"),
                    end = queryValue(request.query, "end")
                )
                PlatformHotPathResponse(
                    status = if (response.contains("\"error\":\"unsupported interval\"")) 400 else 200,
                    body = response
                )
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
                limit = queryValue(request.query, "limit").toIntOrNull() ?: 0
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
                limit = queryValue(request.query, "limit").toIntOrNull() ?: 0
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
                .thenApply { it as PlatformHotPathResponse? }
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

    private fun projectorStatusJson(): String {
        val partitions = projectorPartitions()
        val status = api.projectionStatus(streamAckProjectionName, partitions, streamAckProjectionSource.configValue)
        val metrics = CanonicalProjectionMetrics.snapshot()
        return JsonCodec.writeObject(
            "role" to runtimeRole.configValue,
            "status" to if (runtimeRole == PlatformRuntimeRole.Projector && streamAckProjectorEnabled) "running" else "inactive",
            "implementation" to "canonical-submit-projector",
            "source" to streamAckProjectionSource.configValue,
            "eventStream" to streamAckProjectionEventStream,
            "projectionName" to status.projectionName,
            "partitions" to partitions,
            "projectedCount" to status.projectedCount,
            "lag" to status.lag,
            "metrics" to mapOf(
                "projected" to metrics.projected,
                "failed" to metrics.failed,
                "emptyPolls" to metrics.emptyPolls,
                "lastProjectedAt" to metrics.lastProjectedAt,
                "lastFailedAt" to metrics.lastFailedAt,
                "lastError" to metrics.lastError
            ),
            "watermarks" to status.watermarks.map { watermark ->
                mapOf(
                    "projectionName" to watermark.projectionName,
                    "partition" to watermark.partitionId,
                    "lastPartitionSequence" to watermark.lastPartitionSequence,
                    "canonicalMaxPartitionSequence" to watermark.canonicalMaxPartitionSequence,
                    "lag" to watermark.lag,
                    "updatedAt" to watermark.updatedAt,
                    "lastError" to watermark.lastError
                )
            }
        )
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
        val validationError = HotPathMetrics.time("api.command.validate") {
            PlatformCommandParsers.validateApiV1Command(route, body)
        }
        if (validationError != null) {
            writeJson(exchange, 400, boundary.toErrorJson(BoundaryError(400, "VALIDATION_ERROR", validationError), correlationId))
            return
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

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, null)
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

        val riskRequest = accountRiskRequest(clientId, route, idempotencyKey, correlationId, body, null)
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
        val validationError = HotPathMetrics.time("api.command.validate") {
            PlatformCommandParsers.validateApiV1Command(route, body)
        }
        if (validationError != null) {
            return PreparedApiV1MutationResult.Rejected(
                PlatformHotPathResponse(
                    400,
                    boundary.toErrorJson(BoundaryError(400, "VALIDATION_ERROR", validationError), correlationId)
                )
            )
        }

        return PreparedApiV1MutationResult.Prepared(
            PreparedApiV1Mutation(
                route = route,
                clientId = clientId,
                idempotencyKey = idempotencyKey,
                correlationId = correlationId,
                body = body
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
        envelope: StreamCommandEnvelope?
    ): AccountRiskCheckRequest {
        val json = JsonCodec.parseObjectOrEmpty(body)
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

    private fun abuseStatsJson(stats: AbuseProtectionStats): String {
        return JsonCodec.writeObject(
            "mode" to stats.mode,
            "enabled" to stats.enabled,
            "warningOnly" to stats.warningOnly,
            "maxRejects" to stats.maxRejects,
            "windowSeconds" to stats.windowSeconds,
            "blockSeconds" to stats.blockSeconds,
            "trackedRejectCodes" to stats.trackedRejectCodes.sorted(),
            "trackedRoutes" to stats.trackedRoutes.sorted(),
            "routePolicyOverrides" to stats.routePolicyOverrides.toSortedMap(),
            "trips" to stats.trips,
            "blocks" to stats.blocks,
            "releases" to stats.releases,
            "activeBlockedClients" to stats.activeBlockedClients
        )
    }

    private fun accountRiskControlsJson(): String {
        val controls = accountRiskControlStore?.listControls().orEmpty()
        return JsonCodec.writeObject(
            "controls" to controls.map { control ->
                mapOf(
                    "scopeType" to control.scopeType,
                    "scopeId" to control.scopeId,
                    "decision" to control.decision.name,
                    "reason" to control.reason,
                    "maxQuantityUnits" to control.maxQuantityUnits,
                    "maxNotional" to control.maxNotional,
                    "currency" to control.currency,
                    "updatedAt" to control.updatedAt
                )
            },
            "controlsCount" to controls.size
        )
    }

    private fun accountRiskDecisionsJson(limit: Int): String {
        val boundedLimit = limit.coerceIn(1, 500)
        val decisions = accountRiskDecisionLog?.recentDecisions(boundedLimit).orEmpty()
        return JsonCodec.writeObject(
            "decisions" to decisions.map { decision ->
                mapOf(
                    "decisionId" to decision.decisionId,
                    "decidedAt" to decision.decidedAt,
                    "decision" to decision.decision.name,
                    "code" to decision.code,
                    "message" to decision.message,
                    "clientId" to decision.clientId,
                    "route" to decision.route,
                    "commandType" to decision.commandType,
                    "commandId" to decision.commandId,
                    "correlationId" to decision.correlationId,
                    "actorId" to decision.actorId,
                    "participantId" to decision.participantId,
                    "accountId" to decision.accountId,
                    "botId" to decision.botId,
                    "venueSessionId" to decision.venueSessionId,
                    "instrumentId" to decision.instrumentId,
                    "orderId" to decision.orderId,
                    "quantityUnits" to decision.quantityUnits,
                    "limitPrice" to decision.limitPrice,
                    "currency" to decision.currency
                )
            },
            "decisionsCount" to decisions.size,
            "limit" to boundedLimit
        )
    }

    private fun commandCircuitBreakersJson(): String {
        val breakers = commandCircuitBreakerStore?.listBreakers().orEmpty()
        return JsonCodec.writeObject(
            "breakers" to breakers.map { breaker ->
                mapOf(
                    "scopeType" to breaker.scopeType,
                    "scopeId" to breaker.scopeId,
                    "tripped" to breaker.tripped,
                    "reason" to breaker.reason,
                    "updatedAt" to breaker.updatedAt
                )
            },
            "breakersCount" to breakers.size
        )
    }

    private fun instrumentPriceCollarsJson(): String {
        val collars = instrumentPriceCollarStore?.listCollars().orEmpty()
        return JsonCodec.writeObject(
            "collars" to collars.map { collar ->
                mapOf(
                    "instrumentId" to collar.instrumentId,
                    "minPrice" to collar.minPrice,
                    "maxPrice" to collar.maxPrice,
                    "currency" to collar.currency,
                    "reason" to collar.reason,
                    "updatedAt" to collar.updatedAt
                )
            },
            "collarsCount" to collars.size
        )
    }

    private fun setAccountRiskControlResponse(body: String): PlatformHotPathResponse {
        val store = accountRiskControlStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "account risk control store unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val scopeType = normalizeAccountRiskScope(json.string("scopeType").ifBlank { json.string("scope") })
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid account risk scope"))
        val scopeId = json.string("scopeId").ifBlank { json.string("id") }
        if (scopeId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scopeId is required"))
        }
        val decision = normalizeAccountRiskDecision(json.string("decision"))
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid account risk decision"))
        val reason = json.string("reason")
        val maxQuantityUnits = json.string("maxQuantityUnits")
        if (maxQuantityUnits.isNotBlank() && maxQuantityUnits.toBigDecimalOrNull() == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid maxQuantityUnits"))
        }
        val maxNotional = json.string("maxNotional")
        if (maxNotional.isNotBlank() && maxNotional.toBigDecimalOrNull() == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid maxNotional"))
        }
        val currency = json.string("currency").uppercase()
        val principal = currentAdminPrincipal()
        val actorId = principal.actorId
        val correlationId = principal.correlationId
        val previous = store.listControls().firstOrNull { it.scopeType == scopeType && it.scopeId == scopeId }

        store.upsertControl(scopeType, scopeId, decision, reason, maxQuantityUnits, maxNotional, currency)
        val current = store.listControls().firstOrNull { it.scopeType == scopeType && it.scopeId == scopeId }
            ?: AccountRiskControl(scopeType, scopeId, decision, reason, maxQuantityUnits, maxNotional, currency)
        recordProtectiveControlAudit(
            actorId = actorId,
            correlationId = correlationId,
            eventType = "AccountRiskControlChanged",
            targetId = "$scopeType:$scopeId",
            payload = mapOf(
                "controlType" to "account-risk",
                "scopeType" to scopeType,
                "scopeId" to scopeId,
                "previousDecision" to previous?.decision?.name.orEmpty(),
                "previousReason" to previous?.reason.orEmpty(),
                "previousMaxQuantityUnits" to previous?.maxQuantityUnits.orEmpty(),
                "previousMaxNotional" to previous?.maxNotional.orEmpty(),
                "previousCurrency" to previous?.currency.orEmpty(),
                "decision" to decision.name,
                "reason" to reason,
                "maxQuantityUnits" to maxQuantityUnits,
                "maxNotional" to maxNotional,
                "currency" to currency
            )
        )

        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "control" to mapOf(
                    "scopeType" to current.scopeType,
                    "scopeId" to current.scopeId,
                    "decision" to current.decision.name,
                    "reason" to current.reason,
                    "maxQuantityUnits" to current.maxQuantityUnits,
                    "maxNotional" to current.maxNotional,
                    "currency" to current.currency,
                    "updatedAt" to current.updatedAt
                )
            )
        )
    }

    private fun transitionArenaBotVersionResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val botId = json.string("botId")
        val versionId = json.string("versionId")
        val status = normalizeArenaBotVersionStatus(json.string("status"))
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid arena bot version status"))
        val reason = json.string("reason").ifBlank { "operator transition" }
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        val actor = arenaAdminActor()
        return try {
            val updated = service.transitionArenaBotVersion(
                actor,
                ArenaBotVersionDecisionCommand(
                    botId = botId,
                    versionId = versionId,
                    status = status,
                    reason = reason
                )
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to updated.botId,
                    "versionId" to updated.versionId,
                    "botVersionStatus" to updated.status.name
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena transition")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena transition failed")))
        }
    }

    private fun registerArenaBotResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val actor = arenaAdminActor(json)
        return try {
            val bot = service.registerArenaBot(
                actor,
                ArenaBotRegistrationCommand(
                    botId = json.string("botId"),
                    fileName = json.string("fileName"),
                    name = json.string("name"),
                    publisher = json.string("publisher"),
                    email = json.string("email"),
                    description = json.string("description"),
                    version = json.string("version")
                )
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to bot.botId,
                    "fileName" to bot.fileName
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena bot")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot registration failed")))
        }
    }

    private fun registerArenaBotVersionResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val actor = arenaAdminActor(json)
        return try {
            val version = service.registerArenaBotVersion(
                actor,
                ArenaBotVersionRegistrationCommand(
                    botId = json.string("botId"),
                    versionId = json.string("versionId"),
                    sourceHash = json.string("sourceHash"),
                    artifactHash = json.string("artifactHash"),
                    sdkVersion = json.string("sdkVersion"),
                    apiVersion = json.string("apiVersion"),
                    dependencyManifestHash = json.string("dependencyManifestHash")
                )
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to version.botId,
                    "versionId" to version.versionId,
                    "botVersionStatus" to version.status.name
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena bot version")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot version registration failed")))
        }
    }

    private fun arenaBotOpenBaoProvisionResponse(body: String): PlatformHotPathResponse {
        if (arenaAdminService == null) {
            return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        }
        val json = JsonCodec.parseObjectOrEmpty(body)
        val githubOidcToken = json.string("githubOidcToken")
        val submitterIdentity = json.string("submitterIdentity")
        val botId = json.string("botId")
        val flow = json.string("flow")
        if (githubOidcToken.isBlank() || submitterIdentity.isBlank() || botId.isBlank()) {
            return PlatformHotPathResponse(
                400,
                JsonCodec.writeObject("error" to "githubOidcToken, submitterIdentity, and botId are required")
            )
        }
        if (flow !in setOf("add", "update", "remove")) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "flow must be add, update, or remove"))
        }
        val baoAddr = RuntimeEnv.string("BAO_ADDR", "")
            .ifBlank { return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "BAO_ADDR is not configured")) }
        val service = OpenBaoProvisioningService(OpenBaoProvisioningConfig(baoAddr = baoAddr))
        return try {
            when (flow) {
                "remove" -> service.revokeBotSecretSlice(githubOidcToken, submitterIdentity, botId)
                "update" -> Unit // existing slice is reused; no new provisioning
                else -> service.provisionBotSecretSlice(githubOidcToken, submitterIdentity, botId, emptyMap())
            }
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "botId" to botId, "flow" to flow)
            )
        } catch (ex: OpenBaoClientException) {
            PlatformHotPathResponse(502, JsonCodec.writeObject("error" to (ex.message ?: "OpenBao provisioning failed")))
        }
    }

    private fun arenaBotResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        if (botId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId is required"))
        }
        val bot = service.arenaBot(arenaAdminActor(query), botId)
            ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot not found"))
        return PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "bot" to arenaBotJson(bot)))
    }

    private fun arenaBotVersionResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        val version = service.arenaBotVersion(arenaAdminActor(query), botId, versionId)
            ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot version not found"))
        return PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "version" to arenaBotVersionJson(version)))
    }

    private fun arenaQualificationReportsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        val reports = service.arenaQualificationReports(arenaAdminActor(query), botId, versionId)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject("status" to "ok", "reports" to reports.map { arenaQualificationReportJson(it) })
        )
    }

    private fun arenaOperatorDecisionsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        val decisions = service.arenaOperatorDecisions(arenaAdminActor(query), botId, versionId)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject("status" to "ok", "decisions" to decisions.map { arenaOperatorDecisionJson(it) })
        )
    }

    private fun arenaRuntimeConfigDescriptorsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        val descriptors = service.arenaRuntimeConfigDescriptors(arenaAdminActor(query), botId, versionId)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject("status" to "ok", "descriptors" to descriptors.map { arenaRuntimeConfigDescriptorJson(it) })
        )
    }

    private fun arenaRunResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        val run = service.arenaRun(arenaAdminActor(query), runId)
            ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena run not found"))
        return PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "run" to arenaRunJson(run)))
    }

    private fun registerArenaRunResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        return try {
            val run = service.registerArenaRun(
                arenaAdminActor(json),
                ArenaRunRegistrationCommand(
                    runId = json.string("runId"),
                    modeId = json.string("modeId"),
                    scenarioId = json.string("scenarioId"),
                    seed = json.requiredLong("seed"),
                    policyVersion = json.string("policyVersion"),
                    botVersions = json.objectDocuments("botVersions").map { ref ->
                        ArenaRunBotVersionRef(ref.string("botId"), ref.string("versionId"))
                    }
                )
            )
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "run" to arenaRunJson(run)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run registration failed")))
        }
    }

    private fun updateArenaRunStatusResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val runId = json.string("runId")
        val status = normalizeArenaRunStatus(json.string("status"))
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid arena run status"))
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        return try {
            val run = service.updateArenaRunStatus(arenaAdminActor(json), ArenaRunStatusCommand(runId, status))
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "run" to arenaRunJson(run)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run transition")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run transition failed")))
        }
    }

    private fun arenaRunBotResultsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        return try {
            val results = service.arenaRunBotResults(arenaAdminActor(query), runId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "results" to results.map { arenaRunBotResultJson(it) })
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run bot results query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run bot results query failed")))
        }
    }

    private fun recordArenaRunBotResultResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        return try {
            val command = ArenaRunBotResultIngestionCommand(
                runId = json.string("runId"),
                botId = json.string("botId"),
                versionId = json.string("versionId"),
                scoringPolicyVersion = json.string("scoringPolicyVersion"),
                finalEquity = json.requiredLong("finalEquity"),
                realizedPnl = json.requiredLong("realizedPnl"),
                maxDrawdown = json.requiredLong("maxDrawdown"),
                actionsProposed = json.requiredInt("actionsProposed"),
                orderActionsProposed = json.requiredInt("orderActionsProposed"),
                dataCalls = json.requiredInt("dataCalls"),
                signalsGenerated = json.requiredInt("signalsGenerated"),
                disqualified = json.boolean("disqualified"),
                scoreEligible = json.booleanOrDefault("scoreEligible", true),
                publicLeaderboard = json.booleanOrDefault("publicLeaderboard", true)
            )
            val result = service.recordArenaRunBotResult(arenaAdminActor(json), command)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "result" to arenaRunBotResultJson(result))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run bot result")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run bot result ingestion failed")))
        }
    }

    private fun arenaRunEnforcementEventsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        return try {
            val events = service.arenaRunEnforcementEvents(arenaAdminActor(query), runId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "events" to events.map { arenaRunEnforcementEventJson(it) })
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run enforcement query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run enforcement query failed")))
        }
    }

    private fun recordArenaRunEnforcementEventResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        return try {
            val command = ArenaRunEnforcementEventIngestionCommand(
                runId = json.string("runId"),
                botId = json.string("botId"),
                versionId = json.string("versionId"),
                decision = json.string("decision"),
                reasonCode = json.string("reasonCode"),
                reason = json.string("reason"),
                policyVersion = json.string("policyVersion"),
                countersJson = json.string("countersJson")
            )
            val event = service.recordArenaRunEnforcementEvent(arenaAdminActor(json), command)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "event" to arenaRunEnforcementEventJson(event))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run enforcement event")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run enforcement event ingestion failed")))
        }
    }

    private fun arenaLeaderboardResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val modeId = queryValue(query, "modeId")
        val scoringPolicyVersion = queryValue(query, "scoringPolicyVersion")
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        if (modeId.isBlank() || scoringPolicyVersion.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "modeId and scoringPolicyVersion are required"))
        }
        val entries = service.arenaLeaderboard(arenaAdminActor(query), modeId, scoringPolicyVersion, limit)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject("status" to "ok", "entries" to entries.map { arenaLeaderboardEntryJson(it) })
        )
    }

    private fun recordAnalyticsRunExportResponse(body: String): PlatformHotPathResponse {
        val service = analyticsRunExportService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "analytics run export service unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val counts = json.obj("counts")
        val latency = json.obj("latencyMs")
        return try {
            val record = service.ingest(
                SimulationRunExportCommand(
                    runId = json.string("runId"),
                    scenarioId = json.string("scenarioId"),
                    runKind = json.string("runKind"),
                    source = json.string("source"),
                    gitSha = json.string("gitSha"),
                    profile = json.string("profile"),
                    startedAt = instantOrNull(json.string("startedAt")),
                    completedAt = instantOrNull(json.string("completedAt")),
                    exportedAt = instantOrNull(json.string("exportedAt")),
                    status = json.string("status"),
                    attemptedCount = longValue(json, counts, "attemptedCount", "attempted"),
                    acceptedCount = longValue(json, counts, "acceptedCount", "accepted"),
                    completedCount = longValue(json, counts, "completedCount", "completed"),
                    materializedCount = longValue(json, counts, "materializedCount", "materialized"),
                    projectedCount = longValue(json, counts, "projectedCount", "projected"),
                    failedCount = longValue(json, counts, "failedCount", "failed"),
                    p50LatencyMs = doubleValue(json, latency, "p50LatencyMs", "p50"),
                    p95LatencyMs = doubleValue(json, latency, "p95LatencyMs", "p95"),
                    p99LatencyMs = doubleValue(json, latency, "p99LatencyMs", "p99"),
                    artifactManifestJson = normalizedRawJson(json.raw("artifactManifest").ifBlank { json.raw("artifacts") }, "[]"),
                    summaryJson = normalizedRawJson(json.raw("summary"), "{}")
                )
            )
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "export" to analyticsRunExportJson(record)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid analytics run export")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "analytics run export ingestion failed")))
        }
    }

    private fun analyticsRunExportsResponse(query: String?): PlatformHotPathResponse {
        val service = analyticsRunExportService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "analytics run export service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isNotBlank()) {
            val record = service.find(runId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "analytics run export not found"))
            return PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "export" to analyticsRunExportJson(record)))
        }
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        val records = service.list(limit)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "exports" to records.map { analyticsRunExportJson(it) },
                "exportsCount" to records.size,
                "limit" to limit.coerceIn(1, 500)
            )
        )
    }

    private fun analyticsRunExportJson(record: SimulationRunExportRecord): Map<String, Any?> {
        return mapOf(
            "runId" to record.runId,
            "scenarioId" to record.scenarioId,
            "runKind" to record.runKind,
            "source" to record.source,
            "gitSha" to record.gitSha,
            "profile" to record.profile,
            "startedAt" to record.startedAt?.toString(),
            "completedAt" to record.completedAt?.toString(),
            "exportedAt" to record.exportedAt.toString(),
            "status" to record.status,
            "counts" to mapOf(
                "attempted" to record.attemptedCount,
                "accepted" to record.acceptedCount,
                "completed" to record.completedCount,
                "materialized" to record.materializedCount,
                "projected" to record.projectedCount,
                "failed" to record.failedCount
            ),
            "latencyMs" to mapOf(
                "p50" to record.p50LatencyMs,
                "p95" to record.p95LatencyMs,
                "p99" to record.p99LatencyMs
            ),
            "artifacts" to JsonCodec.rawJsonOrText(record.artifactManifestJson),
            "summary" to JsonCodec.rawJsonOrText(record.summaryJson)
        )
    }

    private fun appendSettlementFactsResponse(body: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        return try {
            val facts = parseSettlementFactBundle(JsonCodec.parseObject(body))
            store.appendFacts(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "facts" to settlementFactBundleJson(facts))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement facts")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "settlement fact append failed")))
        }
    }

    private fun postCashSettlementRepairResponse(body: String): PlatformHotPathResponse {
        return postSettlementRepairResponse(
            body = body,
            repairKind = "cash",
            expectedBreakReason = SettlementBreakOpenedReason,
            repairAction = SettlementRepairPostedActionCash,
            assetType = SettlementLedgerEntryTypeCash,
            defaultParticipantId = { it.buyerParticipantId },
            defaultAssetId = { it.currency },
            defaultQuantity = { it.cashAmount }
        )
    }

    private fun postSecuritySettlementRepairResponse(body: String): PlatformHotPathResponse {
        return postSettlementRepairResponse(
            body = body,
            repairKind = "security",
            expectedBreakReason = SettlementBreakOpenedReasonSecurity,
            repairAction = SettlementRepairPostedActionSecurity,
            assetType = SettlementLedgerEntryTypeSecurity,
            defaultParticipantId = { it.sellerParticipantId },
            defaultAssetId = { it.instrumentId },
            defaultQuantity = { it.quantity }
        )
    }

    private fun postSettlementRepairResponse(
        body: String,
        repairKind: String,
        expectedBreakReason: String,
        repairAction: String,
        assetType: String,
        defaultParticipantId: (SettlementObligationCreatedFact) -> String,
        defaultAssetId: (SettlementObligationCreatedFact) -> String,
        defaultQuantity: (SettlementObligationCreatedFact) -> String
    ): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        return try {
            val json = JsonCodec.parseObject(body)
            val scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") }
            require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
            val settlementBreakId = json.string("settlementBreakId")
            require(settlementBreakId.isNotBlank()) { "settlementBreakId is required" }
            val accountId = json.string("accountId")
            require(accountId.isNotBlank()) { "accountId is required" }
            val existing = store.factsByScenarioRunId(scenarioRunId)
            val breakFact = existing.breaks.firstOrNull { it.settlementBreakId == settlementBreakId }
                ?: throw IllegalArgumentException("settlementBreakId not found")
            require(breakFact.reason == expectedBreakReason) { "settlementBreakId is not a $repairKind break" }
            val obligation = existing.obligations.firstOrNull {
                it.settlementObligationId == breakFact.settlementObligationId
            } ?: throw IllegalArgumentException("settlement obligation not found")
            val principal = currentAdminPrincipal()
            val occurredAtRaw = json.string("occurredAt")
            require(occurredAtRaw.isNotBlank()) { "occurredAt is required" }
            val occurredAt = instantFrom(occurredAtRaw, "occurredAt")
            val actorId = json.string("actorId").ifBlank { principal.actorId }
            require(actorId.isNotBlank()) { "actorId is required" }
            val participantId = json.string("participantId").ifBlank { defaultParticipantId(obligation) }
            val assetId = json.string("assetId").ifBlank { defaultAssetId(obligation) }
            val quantity = json.string("quantity").ifBlank { defaultQuantity(obligation) }
            require(quantity.isNotBlank()) { "quantity is required" }
            val repairId = json.string("settlementRepairId").ifBlank { "repair-$settlementBreakId-$repairKind" }
            val resourcePositionId = json.string("resourcePositionId").ifBlank {
                "resource-$settlementBreakId-$repairKind-repair"
            }
            val correlationId = json.string("correlationId").ifBlank { principal.correlationId }
            val causationId = json.string("causationId").ifBlank { settlementBreakId }
            val facts = SettlementFactBundle(
                scenarioRunId = scenarioRunId,
                resourcePositions = listOf(
                    SettlementResourcePositionFact(
                        resourcePositionId = resourcePositionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = causationId,
                        participantId = participantId,
                        accountId = accountId,
                        assetType = assetType,
                        assetId = assetId,
                        quantity = quantity,
                        occurredAt = occurredAt
                    )
                ),
                repairs = listOf(
                    SettlementRepairPostedFact(
                        settlementRepairId = repairId,
                        settlementBreakId = settlementBreakId,
                        settlementObligationId = obligation.settlementObligationId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = causationId,
                        repairAction = repairAction,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                )
            )
            store.appendFacts(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "facts" to settlementFactBundleJson(facts))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement repair")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "settlement repair failed")))
        }
    }

    private fun forceSettleResponse(body: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        val materializer = settlementObligationMaterializer
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement materializer unavailable"))
        return try {
            val json = JsonCodec.parseObject(body)
            val scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") }
            require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
            val settlementBreakId = json.string("settlementBreakId")
            require(settlementBreakId.isNotBlank()) { "settlementBreakId is required" }
            val accountId = json.string("accountId")
            require(accountId.isNotBlank()) { "accountId is required" }
            val reasonNote = json.string("reasonNote")
            require(reasonNote.isNotBlank()) { "reasonNote is required" }
            val occurredAtRaw = json.string("occurredAt")
            require(occurredAtRaw.isNotBlank()) { "occurredAt is required" }
            val occurredAt = instantFrom(occurredAtRaw, "occurredAt")
            val existing = store.factsByScenarioRunId(scenarioRunId)
            val breakFact = existing.breaks.firstOrNull { it.settlementBreakId == settlementBreakId }
                ?: throw IllegalArgumentException("settlementBreakId not found")
            val obligation = existing.obligations.firstOrNull { it.settlementObligationId == breakFact.settlementObligationId }
                ?: throw IllegalArgumentException("settlement obligation not found")
            val principal = currentAdminPrincipal()
            val actorId = json.string("actorId").ifBlank { principal.actorId }
            require(actorId.isNotBlank()) { "actorId is required" }
            val isCashBreak = breakFact.reason == SettlementBreakOpenedReason
            val repairKind = if (isCashBreak) "cash" else "security"
            val resourceAssetType = if (isCashBreak) SettlementLedgerEntryTypeCash else SettlementLedgerEntryTypeSecurity
            val resourceParticipantId = json.string("participantId").ifBlank {
                if (isCashBreak) obligation.buyerParticipantId else obligation.sellerParticipantId
            }
            val resourceAssetId = json.string("assetId").ifBlank {
                if (isCashBreak) obligation.currency else obligation.instrumentId
            }
            val resourceQuantity = json.string("quantity").ifBlank {
                if (isCashBreak) obligation.cashAmount else obligation.quantity
            }
            val correlationId = json.string("correlationId").ifBlank { principal.correlationId }
            val operatorActionId = json.string("settlementOperatorActionId").ifBlank {
                "operator-force-settle-$settlementBreakId"
            }
            val repairId = json.string("settlementRepairId").ifBlank { "repair-$settlementBreakId-force-settle" }
            val resourcePositionId = json.string("resourcePositionId").ifBlank {
                "resource-$settlementBreakId-force-settle"
            }
            val auditFacts = SettlementFactBundle(
                scenarioRunId = scenarioRunId,
                operatorActions = listOf(
                    SettlementOperatorActionFact(
                        settlementOperatorActionId = operatorActionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = settlementBreakId,
                        action = SettlementOperatorActionForceSettle,
                        targetId = settlementBreakId,
                        reasonNote = reasonNote,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                ),
                resourcePositions = listOf(
                    SettlementResourcePositionFact(
                        resourcePositionId = resourcePositionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        participantId = resourceParticipantId,
                        accountId = accountId,
                        assetType = resourceAssetType,
                        assetId = resourceAssetId,
                        quantity = resourceQuantity,
                        occurredAt = occurredAt
                    )
                ),
                repairs = listOf(
                    SettlementRepairPostedFact(
                        settlementRepairId = repairId,
                        settlementBreakId = settlementBreakId,
                        settlementObligationId = obligation.settlementObligationId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = breakFact.postTradeProfileId,
                        postTradePolicyVersion = breakFact.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        repairAction = if (isCashBreak) SettlementRepairPostedActionCash else SettlementRepairPostedActionSecurity,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                )
            )
            store.appendFacts(auditFacts)
            val result = materializer.materialize(scenarioRunId = scenarioRunId, venueSessionId = json.string("venueSessionId"))
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "repairKind" to repairKind,
                    "facts" to settlementFactBundleJson(auditFacts),
                    "materialization" to mapOf(
                        "scenarioRunId" to result.scenarioRunId,
                        "materializedAttempts" to result.materializedAttempts,
                        "materializedLedgerEntries" to result.materializedLedgerEntries,
                        "materializedSettlements" to result.materializedSettlements,
                        "materializedResolutions" to result.materializedResolutions
                    )
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid force settle")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "force settle failed")))
        }
    }

    private fun reverseSettlementLedgerEntryResponse(body: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        return try {
            val json = JsonCodec.parseObject(body)
            val scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") }
            require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
            val targetLedgerEntryId = json.string("ledgerEntryId").ifBlank { json.string("targetLedgerEntryId") }
            require(targetLedgerEntryId.isNotBlank()) { "ledgerEntryId is required" }
            val reasonNote = json.string("reasonNote")
            require(reasonNote.isNotBlank()) { "reasonNote is required" }
            val occurredAtRaw = json.string("occurredAt")
            require(occurredAtRaw.isNotBlank()) { "occurredAt is required" }
            val occurredAt = instantFrom(occurredAtRaw, "occurredAt")
            val existing = store.factsByScenarioRunId(scenarioRunId)
            val target = existing.ledgerEntries.firstOrNull { it.ledgerEntryId == targetLedgerEntryId }
                ?: throw IllegalArgumentException("ledgerEntryId not found")
            val obligation = existing.obligations.firstOrNull { it.settlementObligationId == target.settlementObligationId }
                ?: throw IllegalArgumentException("settlement obligation not found")
            val nextAttemptNumber = existing.attempts
                .filter { it.settlementObligationId == target.settlementObligationId }
                .maxOfOrNull { it.attemptNumber }
                ?.plus(1)
                ?: 1
            val principal = currentAdminPrincipal()
            val actorId = json.string("actorId").ifBlank { principal.actorId }
            require(actorId.isNotBlank()) { "actorId is required" }
            val correlationId = json.string("correlationId").ifBlank { principal.correlationId }
            val operatorActionId = json.string("settlementOperatorActionId").ifBlank {
                "operator-reverse-ledger-$targetLedgerEntryId"
            }
            val instructionId = json.string("settlementInstructionId").ifBlank {
                "settlement-instruction-reversal-$targetLedgerEntryId"
            }
            val attemptId = json.string("settlementAttemptId").ifBlank {
                "settlement-attempt-reversal-$targetLedgerEntryId"
            }
            val reversalLedgerEntryId = json.string("reversalLedgerEntryId").ifBlank {
                "settlement-ledger-reversal-$targetLedgerEntryId"
            }
            val facts = SettlementFactBundle(
                scenarioRunId = scenarioRunId,
                operatorActions = listOf(
                    SettlementOperatorActionFact(
                        settlementOperatorActionId = operatorActionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = targetLedgerEntryId,
                        action = SettlementOperatorActionReverseLedgerEntry,
                        targetId = targetLedgerEntryId,
                        reasonNote = reasonNote,
                        actorId = actorId,
                        occurredAt = occurredAt
                    )
                ),
                instructions = listOf(
                    SettlementInstructionCreatedFact(
                        settlementInstructionId = instructionId,
                        settlementObligationId = target.settlementObligationId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        occurredAt = occurredAt
                    )
                ),
                attempts = listOf(
                    SettlementAttemptStartedFact(
                        settlementAttemptId = attemptId,
                        settlementObligationId = target.settlementObligationId,
                        settlementInstructionId = instructionId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = instructionId,
                        attemptNumber = nextAttemptNumber,
                        occurredAt = occurredAt
                    )
                ),
                ledgerEntries = listOf(
                    SettlementLedgerEntryFact(
                        ledgerEntryId = reversalLedgerEntryId,
                        settlementObligationId = target.settlementObligationId,
                        settlementInstructionId = instructionId,
                        settlementAttemptId = attemptId,
                        scenarioRunId = scenarioRunId,
                        postTradeProfileId = target.postTradeProfileId,
                        postTradePolicyVersion = target.postTradePolicyVersion,
                        correlationId = correlationId,
                        causationId = operatorActionId,
                        participantId = target.participantId,
                        accountId = target.accountId,
                        assetType = target.assetType,
                        assetId = target.assetId,
                        direction = oppositeLedgerDirection(target.direction),
                        quantity = target.quantity,
                        occurredAt = occurredAt
                    )
                )
            )
            require(obligation.postTradeProfileId == target.postTradeProfileId) { "ledger entry profile must match obligation" }
            store.appendFacts(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "facts" to settlementFactBundleJson(facts))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid ledger reversal")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "ledger reversal failed")))
        }
    }

    private fun oppositeLedgerDirection(direction: String): String {
        return when (direction) {
            SettlementLedgerDirectionDebit -> SettlementLedgerDirectionCredit
            SettlementLedgerDirectionCredit -> SettlementLedgerDirectionDebit
            else -> throw IllegalArgumentException("ledger entry direction must be DEBIT or CREDIT")
        }
    }

    private fun materializeSettlementObligationsResponse(body: String): PlatformHotPathResponse {
        val materializer = settlementObligationMaterializer
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement materializer unavailable"))
        return try {
            val json = JsonCodec.parseObject(body)
            val result = materializer.materialize(
                scenarioRunId = json.string("scenarioRunId").ifBlank { json.string("runId") },
                venueSessionId = json.string("venueSessionId")
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "scenarioRunId" to result.scenarioRunId,
                    "scannedTrades" to result.scannedTrades,
                    "materializedObligations" to result.materializedObligations,
                    "materializedInstructions" to result.materializedInstructions,
                    "materializedAttempts" to result.materializedAttempts,
                    "materializedLegOutcomes" to result.materializedLegOutcomes,
                    "materializedLedgerEntries" to result.materializedLedgerEntries,
                    "materializedSettlements" to result.materializedSettlements,
                    "materializedBreaks" to result.materializedBreaks,
                    "materializedResolutions" to result.materializedResolutions,
                    "skippedTrades" to result.skippedTrades
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement materialization request")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "settlement materialization failed")))
        }
    }

    private fun settlementFactsResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            PlatformHotPathResponse(200, settlementFactBundleBody(store.factsByScenarioRunId(scenarioRunId)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement fact query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement fact query failed")))
        }
    }

    private fun settlementObligationsResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val facts = store.factsByScenarioRunId(scenarioRunId)
            val obligations = SettlementObligationProjection.project(facts)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "scenarioRunId" to scenarioRunId,
                    "obligationsCount" to obligations.size,
                    "obligations" to obligations.map { settlementObligationViewJson(it) }
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement obligation query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement obligation query failed")))
        }
    }

    private fun settlementLedgerResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementLedgerProjection.project(store.factsByScenarioRunId(scenarioRunId))
            PlatformHotPathResponse(200, settlementLedgerProjectionBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement ledger query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement ledger query failed")))
        }
    }

    private fun settlementProofResponse(scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementScenarioProofProjection.project(store.factsByScenarioRunId(scenarioRunId))
            PlatformHotPathResponse(200, settlementScenarioProofBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement proof query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement proof query failed")))
        }
    }

    private fun settlementScoreResponse(exchange: HttpExchange, scenarioRunId: String): PlatformHotPathResponse {
        val store = settlementFactStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "settlement fact store unavailable"))
        if (scenarioRunId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scenarioRunId is required"))
        }
        return try {
            val projection = SettlementScoreProjection.project(
                facts = store.factsByScenarioRunId(scenarioRunId),
                options = SettlementScoreProjectionOptions(
                    asOf = queryValue(exchange, "asOf").takeIf { it.isNotBlank() }?.let { instantFrom(it, "asOf") },
                    agedFailAfterSeconds = queryValue(exchange, "agedFailAfterSeconds")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            val parsed = it.toLongOrNull()
                            require(parsed != null && parsed >= 0L) { "agedFailAfterSeconds must be a non-negative integer" }
                            parsed
                        }
                        ?: com.reef.platform.application.settlement.SettlementScoreDefaultAgedFailAfterSeconds
                )
            )
            PlatformHotPathResponse(200, settlementScoreProjectionBody(projection))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid settlement score query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(503, JsonCodec.writeObject("error" to (ex.message ?: "settlement score query failed")))
        }
    }

    private fun parseSettlementFactBundle(json: JsonDocument): SettlementFactBundle {
        val scenarioRunId = json.string("scenarioRunId")
        val venueSessionId = json.string("venueSessionId")
        val scenarioRunProfileId = json.string("postTradeProfileId").ifBlank {
            scenarioRunId.takeIf { it.isNotBlank() }
                ?.let { scenarioRunPostTradeProfileLookup(it) }
                .orEmpty()
        }
        val selection = postTradeProfileResolver.resolve(
            scenarioRunProfileId = scenarioRunProfileId,
            venueSessionProfileId = venueSessionId.takeIf { it.isNotBlank() }
                ?.let { venueSessionPostTradeProfileLookup(it) }
                .orEmpty()
        )
        val postTradeProfileId = selection.profileId
        val postTradePolicyVersion = positiveIntOrDefault(
            json = json,
            key = "postTradePolicyVersion",
            defaultValue = selection.policyVersion
        )
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            resourcePositions = json.objectDocuments("resourcePositions").map {
                resourcePositionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            obligations = json.objectDocuments("obligations").map {
                obligationFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            instructions = json.objectDocuments("instructions").map {
                instructionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            attempts = json.objectDocuments("attempts").map {
                attemptFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            legOutcomes = json.objectDocuments("legOutcomes").map {
                legOutcomeFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            ledgerEntries = json.objectDocuments("ledgerEntries").map {
                ledgerEntryFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            settlements = json.objectDocuments("settlements").map {
                settlementFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            breaks = json.objectDocuments("breaks").map {
                breakFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            repairs = json.objectDocuments("repairs").map {
                repairFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            resolutions = json.objectDocuments("resolutions").map {
                resolutionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            },
            operatorActions = json.objectDocuments("operatorActions").map {
                operatorActionFact(it, scenarioRunId, postTradeProfileId, postTradePolicyVersion)
            }
        )
    }

    private fun resourcePositionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementResourcePositionFact {
        return SettlementResourcePositionFact(
            resourcePositionId = json.string("resourcePositionId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            participantId = json.string("participantId"),
            accountId = json.string("accountId"),
            assetType = json.string("assetType"),
            assetId = json.string("assetId"),
            quantity = json.string("quantity"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun obligationFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementObligationCreatedFact {
        return SettlementObligationCreatedFact(
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            tradeId = json.string("tradeId"),
            buyerParticipantId = json.string("buyerParticipantId"),
            sellerParticipantId = json.string("sellerParticipantId"),
            instrumentId = json.string("instrumentId"),
            quantity = json.string("quantity"),
            cashAmount = json.string("cashAmount"),
            currency = json.string("currency"),
            state = json.string("state").ifBlank { "OBLIGATION_CREATED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun attemptFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementAttemptStartedFact {
        return SettlementAttemptStartedFact(
            settlementAttemptId = json.string("settlementAttemptId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            attemptNumber = positiveIntOrDefault(json, "attemptNumber", 1),
            state = json.string("state").ifBlank { "ATTEMPT_STARTED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun instructionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementInstructionCreatedFact {
        return SettlementInstructionCreatedFact(
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            instructionType = json.string("instructionType").ifBlank { "DVP" },
            state = json.string("state").ifBlank { "INSTRUCTION_CREATED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun legOutcomeFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementLegOutcomeFact {
        return SettlementLegOutcomeFact(
            settlementLegOutcomeId = json.string("settlementLegOutcomeId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementAttemptId = json.string("settlementAttemptId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            legType = json.string("legType"),
            state = json.string("state").ifBlank { "LEG_SUCCEEDED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun ledgerEntryFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementLedgerEntryFact {
        return SettlementLedgerEntryFact(
            ledgerEntryId = json.string("ledgerEntryId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementAttemptId = json.string("settlementAttemptId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            participantId = json.string("participantId"),
            accountId = json.string("accountId"),
            assetType = json.string("assetType"),
            assetId = json.string("assetId"),
            direction = json.string("direction"),
            quantity = json.string("quantity"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun settlementFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementSettledFact {
        return SettlementSettledFact(
            settlementId = json.string("settlementId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementInstructionId = json.string("settlementInstructionId"),
            settlementAttemptId = json.string("settlementAttemptId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            settlementState = json.string("settlementState").ifBlank { "SETTLED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun breakFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementBreakOpenedFact {
        return SettlementBreakOpenedFact(
            settlementBreakId = json.string("settlementBreakId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            reason = json.string("reason").ifBlank { "CASH_LEG_FAILED" },
            state = json.string("state").ifBlank { "BROKEN" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun repairFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementRepairPostedFact {
        return SettlementRepairPostedFact(
            settlementRepairId = json.string("settlementRepairId"),
            settlementBreakId = json.string("settlementBreakId"),
            settlementObligationId = json.string("settlementObligationId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            repairAction = json.string("repairAction").ifBlank { SettlementRepairPostedActionCash },
            actorType = json.string("actorType").ifBlank { "USER" },
            actorId = json.string("actorId"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun resolutionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementResolvedFact {
        return SettlementResolvedFact(
            settlementResolutionId = json.string("settlementResolutionId"),
            settlementObligationId = json.string("settlementObligationId"),
            settlementBreakId = json.string("settlementBreakId"),
            settlementRepairId = json.string("settlementRepairId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            settlementState = json.string("settlementState").ifBlank { "RESOLVED" },
            exceptionState = json.string("exceptionState").ifBlank { "RESOLVED" },
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun operatorActionFact(
        json: JsonDocument,
        scenarioRunId: String,
        defaultPostTradeProfileId: String,
        defaultPostTradePolicyVersion: Int
    ): SettlementOperatorActionFact {
        return SettlementOperatorActionFact(
            settlementOperatorActionId = json.string("settlementOperatorActionId"),
            scenarioRunId = json.string("scenarioRunId").ifBlank { scenarioRunId },
            postTradeProfileId = json.string("postTradeProfileId").ifBlank { defaultPostTradeProfileId },
            postTradePolicyVersion = positiveIntOrDefault(json, "postTradePolicyVersion", defaultPostTradePolicyVersion),
            correlationId = json.string("correlationId"),
            causationId = json.string("causationId"),
            action = json.string("action"),
            targetId = json.string("targetId"),
            reasonNote = json.string("reasonNote"),
            actorType = json.string("actorType").ifBlank { "USER" },
            actorId = json.string("actorId"),
            occurredAt = requiredInstant(json, "occurredAt")
        )
    }

    private fun positiveIntOrDefault(json: JsonDocument, key: String, defaultValue: Int): Int {
        val raw = json.string(key)
        if (raw.isBlank()) return defaultValue
        val parsed = raw.toIntOrNull()
        require(parsed != null && parsed > 0) { "$key must be a positive integer" }
        return parsed
    }

    private fun requiredInstant(json: JsonDocument, key: String): Instant {
        return instantFrom(json.string(key), key)
    }

    private fun instantFrom(value: String, key: String): Instant {
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("$key must be RFC3339")
        }
    }

    private fun settlementFactBundleJson(facts: SettlementFactBundle): Map<String, Any?> {
        return mapOf(
            "scenarioRunId" to facts.scenarioRunId,
            "resourcePositions" to facts.resourcePositions.map {
                mapOf(
                    "resourcePositionId" to it.resourcePositionId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "participantId" to it.participantId,
                    "accountId" to it.accountId,
                    "assetType" to it.assetType,
                    "assetId" to it.assetId,
                    "quantity" to it.quantity,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "obligations" to facts.obligations.map {
                mapOf(
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "tradeId" to it.tradeId,
                    "buyerParticipantId" to it.buyerParticipantId,
                    "sellerParticipantId" to it.sellerParticipantId,
                    "instrumentId" to it.instrumentId,
                    "quantity" to it.quantity,
                    "cashAmount" to it.cashAmount,
                    "currency" to it.currency,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "instructions" to facts.instructions.map {
                mapOf(
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "instructionType" to it.instructionType,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "attempts" to facts.attempts.map {
                mapOf(
                    "settlementAttemptId" to it.settlementAttemptId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "attemptNumber" to it.attemptNumber,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "legOutcomes" to facts.legOutcomes.map {
                mapOf(
                    "settlementLegOutcomeId" to it.settlementLegOutcomeId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementAttemptId" to it.settlementAttemptId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "legType" to it.legType,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "ledgerEntries" to facts.ledgerEntries.map {
                mapOf(
                    "ledgerEntryId" to it.ledgerEntryId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementAttemptId" to it.settlementAttemptId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "participantId" to it.participantId,
                    "accountId" to it.accountId,
                    "assetType" to it.assetType,
                    "assetId" to it.assetId,
                    "direction" to it.direction,
                    "quantity" to it.quantity,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "settlements" to facts.settlements.map {
                mapOf(
                    "settlementId" to it.settlementId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementInstructionId" to it.settlementInstructionId,
                    "settlementAttemptId" to it.settlementAttemptId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "settlementState" to it.settlementState,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "breaks" to facts.breaks.map {
                mapOf(
                    "settlementBreakId" to it.settlementBreakId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "reason" to it.reason,
                    "state" to it.state,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "repairs" to facts.repairs.map {
                mapOf(
                    "settlementRepairId" to it.settlementRepairId,
                    "settlementBreakId" to it.settlementBreakId,
                    "settlementObligationId" to it.settlementObligationId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "repairAction" to it.repairAction,
                    "actorType" to it.actorType,
                    "actorId" to it.actorId,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "resolutions" to facts.resolutions.map {
                mapOf(
                    "settlementResolutionId" to it.settlementResolutionId,
                    "settlementObligationId" to it.settlementObligationId,
                    "settlementBreakId" to it.settlementBreakId,
                    "settlementRepairId" to it.settlementRepairId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "settlementState" to it.settlementState,
                    "exceptionState" to it.exceptionState,
                    "occurredAt" to it.occurredAt.toString()
                )
            },
            "operatorActions" to facts.operatorActions.map {
                mapOf(
                    "settlementOperatorActionId" to it.settlementOperatorActionId,
                    "scenarioRunId" to it.scenarioRunId,
                    "postTradeProfileId" to it.postTradeProfileId,
                    "postTradePolicyVersion" to it.postTradePolicyVersion,
                    "correlationId" to it.correlationId,
                    "causationId" to it.causationId,
                    "action" to it.action,
                    "targetId" to it.targetId,
                    "reasonNote" to it.reasonNote,
                    "actorType" to it.actorType,
                    "actorId" to it.actorId,
                    "occurredAt" to it.occurredAt.toString()
                )
            }
        )
    }

    private fun settlementFactBundleBody(facts: SettlementFactBundle): String {
        return jsonObjectBody(settlementFactBundleJson(facts))
    }

    private fun settlementLedgerProjectionBody(projection: SettlementLedgerProjectionView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to projection.scenarioRunId,
                "balancesCount" to projection.balances.size,
                "settlementProofsCount" to projection.settlementProofs.size,
                "balances" to projection.balances.map {
                    mapOf(
                        "scenarioRunId" to it.scenarioRunId,
                        "participantId" to it.participantId,
                        "accountId" to it.accountId,
                        "assetType" to it.assetType,
                        "assetId" to it.assetId,
                        "openingQuantity" to it.openingQuantity,
                        "debitQuantity" to it.debitQuantity,
                        "creditQuantity" to it.creditQuantity,
                        "netQuantity" to it.netQuantity,
                        "availableQuantity" to it.availableQuantity,
                        "ledgerEntryCount" to it.ledgerEntryCount,
                        "updatedAt" to it.updatedAt.toString()
                    )
                },
                "settlementProofs" to projection.settlementProofs.map {
                    mapOf(
                        "settlementId" to it.settlementId,
                        "settlementObligationId" to it.settlementObligationId,
                        "settlementInstructionId" to it.settlementInstructionId,
                        "settlementAttemptId" to it.settlementAttemptId,
                        "scenarioRunId" to it.scenarioRunId,
                        "postTradeProfileId" to it.postTradeProfileId,
                        "postTradePolicyVersion" to it.postTradePolicyVersion,
                        "settlementState" to it.settlementState,
                        "proofState" to it.proofState,
                        "cashDebitQuantity" to it.cashDebitQuantity,
                        "cashCreditQuantity" to it.cashCreditQuantity,
                        "securityDebitQuantity" to it.securityDebitQuantity,
                        "securityCreditQuantity" to it.securityCreditQuantity,
                        "cashBalanced" to it.cashBalanced,
                        "securityBalanced" to it.securityBalanced,
                        "legOutcomeCount" to it.legOutcomeCount,
                        "ledgerEntryCount" to it.ledgerEntryCount,
                        "updatedAt" to it.updatedAt.toString()
                    )
                }
            )
        )
    }

    private fun settlementScenarioProofBody(proof: SettlementScenarioProofView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to proof.scenarioRunId,
                "proofStatus" to proof.proofStatus,
                "checksumAlgorithm" to proof.checksumAlgorithm,
                "checksum" to proof.checksum,
                "factsCount" to proof.factsCount,
                "obligationsCount" to proof.obligationsCount,
                "instructionsCount" to proof.instructionsCount,
                "attemptsCount" to proof.attemptsCount,
                "legOutcomesCount" to proof.legOutcomesCount,
                "ledgerEntriesCount" to proof.ledgerEntriesCount,
                "settlementsCount" to proof.settlementsCount,
                "breaksCount" to proof.breaksCount,
                "repairsCount" to proof.repairsCount,
                "resolutionsCount" to proof.resolutionsCount,
                "operatorActionsCount" to proof.operatorActionsCount,
                "profilePolicies" to proof.profilePolicies.map {
                    mapOf(
                        "postTradeProfileId" to it.postTradeProfileId,
                        "postTradePolicyVersion" to it.postTradePolicyVersion,
                        "factCount" to it.factCount
                    )
                },
                "causationGapsCount" to proof.causationGaps.size,
                "causationGaps" to proof.causationGaps.map {
                    mapOf(
                        "factType" to it.factType,
                        "factId" to it.factId,
                        "missingReferenceType" to it.missingReferenceType,
                        "missingReferenceId" to it.missingReferenceId
                    )
                },
                "obligations" to proof.obligations.map {
                    mapOf(
                        "settlementObligationId" to it.settlementObligationId,
                        "tradeId" to it.tradeId,
                        "buyerParticipantId" to it.buyerParticipantId,
                        "sellerParticipantId" to it.sellerParticipantId,
                        "instrumentId" to it.instrumentId,
                        "quantity" to it.quantity,
                        "cashAmount" to it.cashAmount,
                        "currency" to it.currency,
                        "settlementInstructionIds" to it.settlementInstructionIds,
                        "settlementAttemptIds" to it.settlementAttemptIds,
                        "ledgerEntryIds" to it.ledgerEntryIds,
                        "settlementIds" to it.settlementIds,
                        "settlementBreakIds" to it.settlementBreakIds,
                        "settlementRepairIds" to it.settlementRepairIds,
                        "settlementResolutionIds" to it.settlementResolutionIds
                    )
                },
                "balances" to proof.balances.map {
                    mapOf(
                        "participantId" to it.participantId,
                        "accountId" to it.accountId,
                        "assetType" to it.assetType,
                        "assetId" to it.assetId,
                        "availableQuantity" to it.availableQuantity,
                        "ledgerEntryCount" to it.ledgerEntryCount
                    )
                },
                "settlementProofs" to proof.settlementProofs.map {
                    mapOf(
                        "settlementId" to it.settlementId,
                        "settlementObligationId" to it.settlementObligationId,
                        "settlementAttemptId" to it.settlementAttemptId,
                        "cashBalanced" to it.cashBalanced,
                        "securityBalanced" to it.securityBalanced,
                        "ledgerEntryCount" to it.ledgerEntryCount
                    )
                },
                "updatedAt" to proof.updatedAt.toString()
            )
        )
    }

    private fun settlementScoreProjectionBody(projection: SettlementScoreProjectionView): String {
        return jsonObjectBody(
            mapOf(
                "scenarioRunId" to projection.scenarioRunId,
                "asOf" to projection.asOf.toString(),
                "agedFailAfterSeconds" to projection.agedFailAfterSeconds,
                "participantsCount" to projection.participants.size,
                "participants" to projection.participants.map {
                    mapOf(
                        "scenarioRunId" to it.scenarioRunId,
                        "participantId" to it.participantId,
                        "cashBalances" to it.cashBalances.map { balance ->
                            mapOf("assetId" to balance.assetId, "availableQuantity" to balance.availableQuantity)
                        },
                        "securityBalances" to it.securityBalances.map { balance ->
                            mapOf("assetId" to balance.assetId, "availableQuantity" to balance.availableQuantity)
                        },
                        "pendingValue" to it.pendingValue,
                        "haircutAdjustedPendingValue" to it.haircutAdjustedPendingValue,
                        "blockedUnsettledValue" to it.blockedUnsettledValue,
                        "scorePenaltyPoints" to it.scorePenaltyPoints,
                        "settledObligationCount" to it.settledObligationCount,
                        "pendingObligationCount" to it.pendingObligationCount,
                        "failedObligationCount" to it.failedObligationCount,
                        "agedFailCount" to it.agedFailCount,
                        "openBreakCount" to it.openBreakCount,
                        "repairPendingCount" to it.repairPendingCount,
                        "updatedAt" to it.updatedAt.toString()
                    )
                },
                "updatedAt" to projection.updatedAt.toString()
            )
        )
    }

    private fun settlementObligationViewJson(view: SettlementObligationView): Map<String, Any?> {
        return mapOf(
            "settlementObligationId" to view.settlementObligationId,
            "scenarioRunId" to view.scenarioRunId,
            "postTradeProfileId" to view.postTradeProfileId,
            "postTradePolicyVersion" to view.postTradePolicyVersion,
            "tradeId" to view.tradeId,
            "buyerParticipantId" to view.buyerParticipantId,
            "sellerParticipantId" to view.sellerParticipantId,
            "instrumentId" to view.instrumentId,
            "quantity" to view.quantity,
            "cashAmount" to view.cashAmount,
            "currency" to view.currency,
            "obligationState" to view.obligationState,
            "settlementState" to view.settlementState,
            "exceptionState" to view.exceptionState,
            "settlementInstructionId" to view.settlementInstructionId,
            "settlementAttemptId" to view.settlementAttemptId,
            "settlementAttemptNumber" to view.settlementAttemptNumber,
            "settlementId" to view.settlementId,
            "cashLegState" to view.cashLegState,
            "securityLegState" to view.securityLegState,
            "ledgerEntryCount" to view.ledgerEntryCount,
            "settlementBreakId" to view.settlementBreakId,
            "settlementRepairId" to view.settlementRepairId,
            "settlementResolutionId" to view.settlementResolutionId,
            "occurredAt" to view.occurredAt.toString(),
            "updatedAt" to view.updatedAt.toString()
        )
    }

    private fun jsonObjectBody(fields: Map<String, Any?>): String {
        return JsonCodec.writeObject(*fields.map { it.key to it.value }.toTypedArray())
    }

    private fun longValue(root: JsonDocument, nested: JsonDocument, rootKey: String, nestedKey: String): Long {
        return root.string(rootKey).ifBlank { nested.string(nestedKey) }.toLongOrNull() ?: 0L
    }

    private fun doubleValue(root: JsonDocument, nested: JsonDocument, rootKey: String, nestedKey: String): Double? {
        return root.string(rootKey).ifBlank { nested.string(nestedKey) }.toDoubleOrNull()
    }

    private fun instantOrNull(value: String): java.time.Instant? {
        if (value.isBlank()) return null
        return java.time.Instant.parse(value)
    }

    private fun normalizedRawJson(raw: String, fallback: String): String {
        return if (raw.isBlank()) fallback else JsonCodec.writeNode(JsonCodec.rawJsonOrText(raw))
    }

    private fun arenaAdminActor(json: JsonDocument): AdminActor {
        return arenaAdminActor()
    }

    private fun arenaAdminActor(): AdminActor {
        val principal = currentAdminPrincipal()
        return AdminActor(
            actorId = principal.actorId,
            correlationId = principal.correlationId,
            occurredAt = principal.occurredAt
        )
    }

    private fun arenaAdminActor(query: String?): AdminActor {
        return arenaAdminActor()
    }

    private fun arenaBotJson(bot: ArenaBot): Map<String, Any?> {
        return mapOf(
            "botId" to bot.botId,
            "fileName" to bot.fileName,
            "metadata" to mapOf(
                "name" to bot.metadata.name,
                "publisher" to bot.metadata.publisher,
                "email" to bot.metadata.email,
                "description" to bot.metadata.description,
                "version" to bot.metadata.version
            ),
            "createdAt" to bot.createdAt.toString()
        )
    }

    private fun arenaBotVersionJson(version: ArenaBotVersion): Map<String, Any?> {
        return mapOf(
            "botId" to version.botId,
            "versionId" to version.versionId,
            "sourceHash" to version.sourceHash,
            "artifactHash" to version.artifactHash,
            "sdkVersion" to version.sdkVersion,
            "apiVersion" to version.apiVersion,
            "dependencyManifestHash" to version.dependencyManifestHash,
            "status" to version.status.name,
            "createdAt" to version.createdAt.toString()
        )
    }

    private fun arenaQualificationReportJson(report: ArenaQualificationReport): Map<String, Any?> {
        return mapOf(
            "botId" to report.botId,
            "versionId" to report.versionId,
            "reportId" to report.reportId,
            "status" to report.status.name,
            "issues" to report.issues,
            "policyVersion" to report.policyVersion,
            "createdAt" to report.createdAt.toString()
        )
    }

    private fun arenaOperatorDecisionJson(decision: ArenaOperatorDecision): Map<String, Any?> {
        return mapOf(
            "botId" to decision.botId,
            "versionId" to decision.versionId,
            "fromStatus" to decision.fromStatus.name,
            "toStatus" to decision.toStatus.name,
            "actorId" to decision.actorId,
            "reason" to decision.reason,
            "correlationId" to decision.correlationId,
            "occurredAt" to decision.occurredAt.toString()
        )
    }

    private fun arenaRuntimeConfigDescriptorJson(descriptor: ArenaRuntimeConfigDescriptor): Map<String, Any?> {
        return mapOf(
            "botId" to descriptor.botId,
            "versionId" to descriptor.versionId,
            "key" to descriptor.key,
            "provider" to descriptor.provider.name,
            "secretPath" to descriptor.secretPath,
            "required" to descriptor.required,
            "description" to descriptor.description
        )
    }

    private fun arenaRunJson(run: ArenaRunRecord): Map<String, Any?> {
        return mapOf(
            "runId" to run.runId,
            "modeId" to run.modeId,
            "scenarioId" to run.scenarioId,
            "seed" to run.seed,
            "policyVersion" to run.policyVersion,
            "botVersions" to run.botVersions.map {
                mapOf("botId" to it.botId, "versionId" to it.versionId)
            },
            "status" to run.status.name,
            "createdAt" to run.createdAt.toString(),
            "completedAt" to run.completedAt?.toString()
        )
    }

    private fun arenaRunBotResultJson(result: ArenaRunBotResult): Map<String, Any?> {
        return mapOf(
            "runId" to result.runId,
            "botId" to result.botId,
            "versionId" to result.versionId,
            "scoringPolicyVersion" to result.scoringPolicyVersion,
            "finalEquity" to result.finalEquity,
            "realizedPnl" to result.realizedPnl,
            "maxDrawdown" to result.maxDrawdown,
            "actionsProposed" to result.actionsProposed,
            "orderActionsProposed" to result.orderActionsProposed,
            "dataCalls" to result.dataCalls,
            "signalsGenerated" to result.signalsGenerated,
            "disqualified" to result.disqualified,
            "scoreEligible" to result.scoreEligible,
            "publicLeaderboard" to result.publicLeaderboard,
            "createdAt" to result.createdAt.toString()
        )
    }

    private fun arenaRunEnforcementEventJson(event: ArenaRunEnforcementEvent): Map<String, Any?> {
        return mapOf(
            "runId" to event.runId,
            "botId" to event.botId,
            "versionId" to event.versionId,
            "decision" to event.decision,
            "reasonCode" to event.reasonCode,
            "reason" to event.reason,
            "policyVersion" to event.policyVersion,
            "countersJson" to event.countersJson,
            "occurredAt" to event.occurredAt.toString()
        )
    }

    private fun arenaLeaderboardEntryJson(entry: ArenaLeaderboardEntry): Map<String, Any?> {
        return mapOf(
            "rank" to entry.rank,
            "runId" to entry.runId,
            "botId" to entry.botId,
            "versionId" to entry.versionId,
            "scoringPolicyVersion" to entry.scoringPolicyVersion,
            "finalEquity" to entry.finalEquity,
            "realizedPnl" to entry.realizedPnl,
            "maxDrawdown" to entry.maxDrawdown,
            "disqualified" to entry.disqualified
        )
    }

    private fun setCommandCircuitBreakerResponse(body: String): PlatformHotPathResponse {
        val store = commandCircuitBreakerStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "command circuit breaker store unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val scopeType = normalizeCircuitBreakerScope(json.string("scopeType").ifBlank { json.string("scope") })
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid circuit breaker scope"))
        val scopeId = if (scopeType == "GLOBAL") {
            json.string("scopeId").ifBlank { "*" }
        } else {
            json.string("scopeId").ifBlank { json.string("id") }
        }
        if (scopeId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "scopeId is required"))
        }
        val tripped = normalizeBreakerTripState(json.string("tripped").ifBlank { json.string("action") })
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid circuit breaker action"))
        val reason = json.string("reason")
        val principal = currentAdminPrincipal()
        val actorId = principal.actorId
        val correlationId = principal.correlationId
        val normalizedScopeId = if (scopeType == "GLOBAL") "*" else scopeId
        val previous = store.listBreakers().firstOrNull { it.scopeType == scopeType && it.scopeId == normalizedScopeId }

        store.setBreaker(scopeType, normalizedScopeId, tripped, reason)
        val current = store.listBreakers().firstOrNull { it.scopeType == scopeType && it.scopeId == normalizedScopeId }
            ?: CommandCircuitBreakerState(scopeType, normalizedScopeId, tripped, reason)
        recordProtectiveControlAudit(
            actorId = actorId,
            correlationId = correlationId,
            eventType = "CommandCircuitBreakerChanged",
            targetId = "$scopeType:$normalizedScopeId",
            payload = mapOf(
                "controlType" to "command-circuit-breaker",
                "scopeType" to scopeType,
                "scopeId" to normalizedScopeId,
                "previousTripped" to (previous?.tripped ?: false),
                "previousReason" to previous?.reason.orEmpty(),
                "tripped" to tripped,
                "reason" to reason
            )
        )

        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "breaker" to mapOf(
                    "scopeType" to current.scopeType,
                    "scopeId" to current.scopeId,
                    "tripped" to current.tripped,
                    "reason" to current.reason,
                    "updatedAt" to current.updatedAt
                )
            )
        )
    }

    private fun setInstrumentPriceCollarResponse(body: String): PlatformHotPathResponse {
        val store = instrumentPriceCollarStore
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "instrument price collar store unavailable"))
        val json = JsonCodec.parseObjectOrEmpty(body)
        val instrumentId = json.string("instrumentId").ifBlank { json.string("id") }
        if (instrumentId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "instrumentId is required"))
        }
        val minPrice = json.string("minPrice")
        val maxPrice = json.string("maxPrice")
        val parsedMin = minPrice.toBigDecimalOrNull()
        val parsedMax = maxPrice.toBigDecimalOrNull()
        if (minPrice.isNotBlank() && parsedMin == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid minPrice"))
        }
        if (maxPrice.isNotBlank() && parsedMax == null) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid maxPrice"))
        }
        if (parsedMin != null && parsedMax != null && parsedMax < parsedMin) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "maxPrice must be greater than or equal to minPrice"))
        }
        val currency = json.string("currency").uppercase()
        val reason = json.string("reason")
        val principal = currentAdminPrincipal()
        val actorId = principal.actorId
        val correlationId = principal.correlationId
        val previous = store.listCollars().firstOrNull { it.instrumentId == instrumentId }

        store.setCollar(instrumentId, minPrice, maxPrice, currency, reason)
        val current = store.listCollars().firstOrNull { it.instrumentId == instrumentId }
            ?: InstrumentPriceCollarState(instrumentId, minPrice, maxPrice, currency, reason)
        recordProtectiveControlAudit(
            actorId = actorId,
            correlationId = correlationId,
            eventType = "InstrumentPriceCollarChanged",
            targetId = instrumentId,
            payload = mapOf(
                "controlType" to "instrument-price-collar",
                "instrumentId" to instrumentId,
                "previousMinPrice" to previous?.minPrice.orEmpty(),
                "previousMaxPrice" to previous?.maxPrice.orEmpty(),
                "previousCurrency" to previous?.currency.orEmpty(),
                "previousReason" to previous?.reason.orEmpty(),
                "minPrice" to minPrice,
                "maxPrice" to maxPrice,
                "currency" to currency,
                "reason" to reason
            )
        )

        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "collar" to mapOf(
                    "instrumentId" to current.instrumentId,
                    "minPrice" to current.minPrice,
                    "maxPrice" to current.maxPrice,
                    "currency" to current.currency,
                    "reason" to current.reason,
                    "updatedAt" to current.updatedAt
                )
            )
        )
    }

    private fun recordProtectiveControlAudit(
        actorId: String,
        correlationId: String,
        eventType: String,
        targetId: String,
        payload: Map<String, Any?>
    ) {
        try {
            api.recordAdminEvent(actorId, correlationId, eventType, targetId, payload)
        } catch (ex: Exception) {
            System.err.println(
                "protective_control_audit_failed eventType=$eventType targetId=$targetId message=${JsonFields.escape(ex.message ?: "unknown")}"
            )
        }
    }

    private fun normalizeAccountRiskScope(value: String): String? {
        return when (value.trim().lowercase()) {
            "account" -> "ACCOUNT"
            "bot" -> "BOT"
            else -> null
        }
    }

    private fun normalizeAccountRiskDecision(value: String): AccountRiskDecision? {
        return when (value.trim().lowercase()) {
            "allow" -> AccountRiskDecision.ALLOW
            "reject" -> AccountRiskDecision.REJECT
            "backpressure" -> AccountRiskDecision.BACKPRESSURE
            "disabled-bot", "disabled_bot" -> AccountRiskDecision.DISABLED_BOT
            else -> null
        }
    }

    private fun normalizeArenaBotVersionStatus(value: String): ArenaBotVersionStatus? {
        return when (value.trim().lowercase()) {
            "draft" -> ArenaBotVersionStatus.Draft
            "submitted" -> ArenaBotVersionStatus.Submitted
            "checks-passed", "checks_passed" -> ArenaBotVersionStatus.ChecksPassed
            "approved" -> ArenaBotVersionStatus.Approved
            "active" -> ArenaBotVersionStatus.Active
            "suspended", "freeze", "frozen" -> ArenaBotVersionStatus.Suspended
            "quarantined", "quarantine" -> ArenaBotVersionStatus.Quarantined
            "banned", "ban" -> ArenaBotVersionStatus.Banned
            "archived", "archive" -> ArenaBotVersionStatus.Archived
            else -> null
        }
    }

    private fun normalizeArenaRunStatus(value: String): ArenaRunStatus? {
        return when (value.trim().lowercase()) {
            "planned" -> ArenaRunStatus.Planned
            "running" -> ArenaRunStatus.Running
            "completed", "complete" -> ArenaRunStatus.Completed
            "failed", "fail" -> ArenaRunStatus.Failed
            "cancelled", "canceled", "cancel" -> ArenaRunStatus.Cancelled
            else -> null
        }
    }

    private fun normalizeCircuitBreakerScope(value: String): String? {
        return when (value.trim().lowercase()) {
            "global" -> "GLOBAL"
            "venue-session", "venue_session" -> "VENUE_SESSION"
            "instrument" -> "INSTRUMENT"
            else -> null
        }
    }

    private fun normalizeBreakerTripState(value: String): Boolean? {
        return when (value.trim().lowercase()) {
            "true", "trip", "tripped", "on" -> true
            "false", "reset", "clear", "off" -> false
            else -> null
        }
    }

    private fun asyncCommandStatsJson(): String {
        val acceptedAsyncStats = acceptedAsyncCommandIntake?.stats()
        val queueCounts = capturedCommandQueue
            ?.statusCounts()
            ?.mapKeys { (status, _) -> status.name }
            ?: emptyMap()
        val counts = CommandLogStatus.values().associate { status ->
            status.name to (queueCounts[status.name] ?: 0L)
        }
        val metrics = AsyncCommandProcessorMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to asyncCommandWorkerEnabled,
            "processingMode" to commandProcessingMode.configValue,
            "workerThreads" to asyncCommandWorkerThreads,
            "batchSize" to asyncCommandWorkerBatchSize,
            "pollIntervalMs" to asyncCommandWorkerPollMs,
            "acceptedAsync" to if (acceptedAsyncStats == null) {
                mapOf("enabled" to false)
            } else {
                mapOf(
                    "enabled" to acceptedAsyncStats.enabled,
                    "laneCount" to acceptedAsyncStats.laneCount,
                    "activeLaneCount" to acceptedAsyncStats.activeLaneCount,
                    "queueCapacityPerLane" to acceptedAsyncStats.queueCapacityPerLane,
                    "inFlightPerLane" to acceptedAsyncStats.inFlightPerLane,
                    "queued" to acceptedAsyncStats.queued,
                    "maxLaneDepth" to acceptedAsyncStats.maxLaneDepth,
                    "received" to acceptedAsyncStats.received,
                    "duplicates" to acceptedAsyncStats.duplicates,
                    "backpressured" to acceptedAsyncStats.backpressured,
                    "processing" to acceptedAsyncStats.processing,
                    "completed" to acceptedAsyncStats.completed,
                    "failed" to acceptedAsyncStats.failed,
                    "retainedStatuses" to acceptedAsyncStats.retainedStatuses,
                    "retentionMaxRecords" to acceptedAsyncStats.retentionMaxRecords,
                    "retentionTtlMs" to acceptedAsyncStats.retentionTtlMs,
                    "retentionEvicted" to acceptedAsyncStats.retentionEvicted,
                    "lastReceivedAt" to acceptedAsyncStats.lastReceivedAt,
                    "lastCompletedAt" to acceptedAsyncStats.lastCompletedAt,
                    "lastFailedAt" to acceptedAsyncStats.lastFailedAt,
                    "lanes" to acceptedAsyncStats.lanes.map { lane ->
                        mapOf(
                            "lane" to lane.lane,
                            "queued" to lane.queued,
                            "received" to lane.received,
                            "backpressured" to lane.backpressured,
                            "processing" to lane.processing,
                            "completed" to lane.completed,
                            "failed" to lane.failed
                        )
                    }
                )
            },
            "intakeBackpressure" to mapOf(
                "maxActiveCommands" to commandIntakeMaxActive,
                "maxStaleProcessing" to commandIntakeMaxStaleProcessing,
                "sampleMs" to commandIntakeBackpressureSampleMs
            ),
            "queue" to counts,
            "metrics" to mapOf(
                "claimed" to metrics.claimed,
                "completed" to metrics.completed,
                "failed" to metrics.failed,
                "emptyPolls" to metrics.emptyPolls,
                "lastClaimedAt" to metrics.lastClaimedAt,
                "lastCompletedAt" to metrics.lastCompletedAt,
                "lastFailedAt" to metrics.lastFailedAt
            )
        )
    }

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

    private fun commandAccountingJson(runId: String): String {
        val snapshot = capturedCommandQueue?.accountingSnapshot(runId)
        if (snapshot == null) {
            return JsonCodec.writeObject(
                "available" to false,
                "runId" to runId,
                "error" to "captured command queue unavailable"
            )
        }
        return JsonCodec.writeObject(
            "available" to true,
            "runId" to snapshot.runId,
            "accepted" to snapshot.accepted,
            "received" to snapshot.received,
            "processing" to snapshot.processing,
            "completed" to snapshot.completed,
            "failed" to snapshot.failed,
            "active" to snapshot.active,
            "terminal" to snapshot.terminal,
            "accountingGap" to snapshot.accountingGap,
            "staleProcessing" to snapshot.staleProcessing
        )
    }

    private fun dbPoolStatsJson(): String {
        return JsonCodec.writeObject(
            "pools" to RuntimeDataSources.snapshots().map { snapshot ->
                mapOf(
                    "key" to snapshot.key,
                    "poolName" to snapshot.poolName,
                    "jdbcUrl" to snapshot.jdbcUrl,
                    "username" to snapshot.username,
                    "maximumPoolSize" to snapshot.maximumPoolSize,
                    "minimumIdle" to snapshot.minimumIdle,
                    "activeConnections" to snapshot.activeConnections,
                    "idleConnections" to snapshot.idleConnections,
                    "totalConnections" to snapshot.totalConnections,
                    "threadsAwaitingConnection" to snapshot.threadsAwaitingConnection
                )
            }
        )
    }

    private fun streamCommandHealthJson(): String {
        val snapshot = streamCommandHealthCheck?.snapshot()
        if (snapshot == null) {
            return JsonCodec.writeObject(
                "available" to false,
                "processingMode" to commandProcessingMode.configValue,
                "stream" to streamCommandConfig.streamName,
                "error" to "stream command health unavailable"
            )
        }
        return JsonCodec.writeObject(
            "available" to snapshot.available,
            "processingMode" to commandProcessingMode.configValue,
            "stream" to snapshot.streamName,
            "messages" to snapshot.messageCount,
            "bytes" to snapshot.byteCount,
            "maxBytes" to snapshot.maxBytes,
            "storageUtilization" to snapshot.storageUtilization,
            "maxStorageUtilization" to streamCommandMaxStorageUtilization,
            "backpressureSampleMs" to streamCommandBackpressureSampleMs,
            "drainBackpressure" to mapOf(
                "policy" to streamCommandDrainBackpressurePolicy.configValue,
                "maxWorkerStreamLag" to streamCommandMaxWorkerStreamLag,
                "maxProjectorLag" to streamCommandMaxProjectorLag,
                "sampleMs" to streamCommandDrainBackpressureSampleMs,
                "workerDurables" to streamCommandBackpressureWorkerDurableNames()
            ),
            "markPublishedMode" to streamCommandMarkPublishedMode,
            "publishMode" to snapshot.publishMode,
            "publishInFlight" to snapshot.publishInFlight,
            "publishMaxInFlight" to snapshot.publishMaxInFlight,
            "publishQueueDepth" to snapshot.publishQueueDepth,
            "publishMaxQueueDepth" to snapshot.publishMaxQueueDepth,
            "publishLaneCount" to snapshot.publishLaneCount,
            "publishAccepted" to snapshot.publishAccepted,
            "publishCompleted" to snapshot.publishCompleted,
            "publishFailed" to snapshot.publishFailed,
            "publishRejected" to snapshot.publishRejected,
            "publishQueueWaitLastMs" to snapshot.publishQueueWaitLastMs,
            "publishQueueWaitMaxMs" to snapshot.publishQueueWaitMaxMs,
            "publishSlotWaitLastMs" to snapshot.publishSlotWaitLastMs,
            "publishSlotWaitMaxMs" to snapshot.publishSlotWaitMaxMs,
            "publishDelegateAckLastMs" to snapshot.publishDelegateAckLastMs,
            "publishDelegateAckMaxMs" to snapshot.publishDelegateAckMaxMs,
            "publishPipelineTotalLastMs" to snapshot.publishPipelineTotalLastMs,
            "publishPipelineTotalMaxMs" to snapshot.publishPipelineTotalMaxMs,
            "publishLanes" to snapshot.publishLaneSnapshots.map {
                mapOf(
                    "partition" to it.partition,
                    "accepted" to it.accepted,
                    "completed" to it.completed,
                    "failed" to it.failed,
                    "rejected" to it.rejected,
                    "queueDepth" to it.queueDepth,
                    "maxQueueDepthObserved" to it.maxQueueDepthObserved,
                    "inFlight" to it.inFlight,
                    "maxInFlightObserved" to it.maxInFlightObserved,
                    "queueWaitLastMs" to it.queueWaitLastMs,
                    "queueWaitMaxMs" to it.queueWaitMaxMs,
                    "slotWaitLastMs" to it.slotWaitLastMs,
                    "slotWaitMaxMs" to it.slotWaitMaxMs,
                    "delegateAckLastMs" to it.delegateAckLastMs,
                    "delegateAckMaxMs" to it.delegateAckMaxMs,
                    "totalLastMs" to it.totalLastMs,
                    "totalMaxMs" to it.totalMaxMs
                )
            },
            "publishAckLastMs" to snapshot.publishAckLastMs,
            "publishAckMaxMs" to snapshot.publishAckMaxMs,
            "producerMetrics" to snapshot.producerMetrics,
            "checkedAt" to snapshot.checkedAt.toString(),
            "error" to snapshot.error
        )
    }

    private fun streamCommandWorkerStatsJson(): String {
        val stats = StreamCommandWorkerMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to streamCommandWorkerEnabled,
            "processingMode" to commandProcessingMode.configValue,
            "partitions" to streamWorkerPartitions(),
            "batchSize" to streamCommandWorkerBatchSize,
            "pollIntervalMs" to streamCommandWorkerPollMs,
            "fetchTimeoutMs" to streamCommandWorkerFetchTimeoutMs,
            "dedicatedRuntimePoolEnabled" to streamCommandWorkerDedicatedRuntimePoolEnabled,
            "metrics" to mapOf(
                "fetched" to stats.fetched,
                "completed" to stats.completed,
                "failed" to stats.failed,
                "ackFailed" to stats.ackFailed,
                "unsupported" to stats.unsupported,
                "emptyPolls" to stats.emptyPolls,
                "lastFetchedAt" to stats.lastFetchedAt,
                "lastCompletedAt" to stats.lastCompletedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastAckFailedAt" to stats.lastAckFailedAt,
                "lastError" to stats.lastError
            ),
            "partitionMetrics" to stats.partitions.map { partition ->
                mapOf(
                    "partition" to partition.partition,
                    "fetched" to partition.fetched,
                    "completed" to partition.completed,
                    "failed" to partition.failed,
                    "ackFailed" to partition.ackFailed,
                    "unsupported" to partition.unsupported,
                    "localInFlight" to partition.localInFlight,
                    "maxDeliveredCount" to partition.maxDeliveredCount,
                    "lastFetchedStreamSequence" to partition.lastFetchedStreamSequence,
                    "lastCompletedStreamSequence" to partition.lastCompletedStreamSequence,
                    "lastFetchedAt" to partition.lastFetchedAt,
                    "lastCompletedAt" to partition.lastCompletedAt,
                    "lastFailedAt" to partition.lastFailedAt,
                    "lastAckFailedAt" to partition.lastAckFailedAt,
                    "oldestLocalInFlightAt" to partition.oldestLocalInFlightAt,
                    "oldestLocalInFlightAgeMs" to partition.oldestLocalInFlightAgeMs,
                    "lastError" to partition.lastError
                )
            },
            "consumerMetrics" to stats.consumers.map { consumer ->
                mapOf(
                    "partition" to consumer.partition,
                    "durableName" to consumer.durableName,
                    "filterSubject" to consumer.filterSubject,
                    "pending" to consumer.pending,
                    "waiting" to consumer.waiting,
                    "ackPending" to consumer.ackPending,
                    "redelivered" to consumer.redelivered,
                    "deliveredConsumerSequence" to consumer.deliveredConsumerSequence,
                    "deliveredStreamSequence" to consumer.deliveredStreamSequence,
                    "ackFloorConsumerSequence" to consumer.ackFloorConsumerSequence,
                    "ackFloorStreamSequence" to consumer.ackFloorStreamSequence,
                    "streamLastSequence" to consumer.streamLastSequence,
                    "streamLag" to consumer.streamLag,
                    "lastActiveAt" to consumer.lastActiveAt,
                    "sampledAt" to consumer.sampledAt,
                    "error" to consumer.error
                )
            }
        )
    }

    private fun venueEventMaterializerStatsJson(): String {
        val stats = VenueEventBatchMaterializerMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to venueEventMaterializerShouldStart(),
            "role" to runtimeRole.configValue,
            "processingMode" to commandProcessingMode.configValue,
            "batchSize" to venueEventMaterializerBatchSize,
            "pollIntervalMs" to venueEventMaterializerPollMs,
            "fetchTimeoutMs" to venueEventMaterializerFetchTimeoutMs,
            "source" to "kafka",
            "metrics" to mapOf(
                "fetched" to stats.fetched,
                "materialized" to stats.materialized,
                "materializedOutcomes" to stats.materializedOutcomes,
                "failed" to stats.failed,
                "ackFailed" to stats.ackFailed,
                "unsupported" to stats.unsupported,
                "emptyPolls" to stats.emptyPolls,
                "lastFetchedStreamSequence" to stats.lastFetchedStreamSequence,
                "lastMaterializedStreamSequence" to stats.lastMaterializedStreamSequence,
                "lastMaterializedBatchId" to stats.lastMaterializedBatchId,
                "lastMaterializedPartition" to stats.lastMaterializedPartition,
                "lastMaterializedFirstSequence" to stats.lastMaterializedFirstSequence,
                "lastMaterializedLastSequence" to stats.lastMaterializedLastSequence,
                "materializerLag" to stats.materializerLag,
                "lastMaterializedAt" to stats.lastMaterializedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastError" to stats.lastError
            )
        )
    }

    private fun marketDataProjectorStatusJson(): String {
        val stats = MarketDataProjectionMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to marketDataProjectorShouldStart(),
            "role" to runtimeRole.configValue,
            "projectionName" to marketDataProjectorProjectionName,
            "sourceProjectionName" to marketDataProjectorSourceProjectionName,
            "pollIntervalMs" to marketDataProjectorPollMs,
            "batchSize" to marketDataProjectorBatchSize,
            "metrics" to mapOf(
                "cycles" to stats.cycles,
                "processedRows" to stats.processedRows,
                "failed" to stats.failed,
                "lastProcessedAt" to stats.lastProcessedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastError" to stats.lastError
            )
        )
    }

    private fun orderLifecycleProjectorStatusJson(): String {
        val stats = OrderLifecycleProjectionMetrics.snapshot()
        return JsonCodec.writeObject(
            "enabled" to orderLifecycleProjectorShouldStart(),
            "role" to runtimeRole.configValue,
            "pollIntervalMs" to orderLifecycleProjectorPollMs,
            "batchSize" to orderLifecycleProjectorBatchSize,
            "metrics" to mapOf(
                "cycles" to stats.cycles,
                "processedRows" to stats.processedRows,
                "failed" to stats.failed,
                "lastProcessedAt" to stats.lastProcessedAt,
                "lastFailedAt" to stats.lastFailedAt,
                "lastError" to stats.lastError
            )
        )
    }

    private fun startStreamCommandWorkers() {
        val partitions = streamWorkerPartitions()
        if (partitions.isEmpty()) {
            System.err.println("stream_command_worker_unavailable reason=no_partitions_configured")
            return
        }
        val workerApi = streamCommandWorkerApi()
        partitions.forEach { partition ->
            val source = StreamCommandWorkerFactory.sourceForPartition(streamCommandConfig, partition)
            if (source is StreamCommandTelemetrySource) {
                StreamCommandWorkerMetrics.registerConsumerTelemetry(partition, source)
            }
            StreamCommandWorker(
                source = source,
                api = workerApi,
                publicationMarker = streamCommandIntakeStore,
                partition = partition,
                batchSize = streamCommandWorkerBatchSize,
                pollIntervalMs = streamCommandWorkerPollMs,
                fetchTimeout = java.time.Duration.ofMillis(streamCommandWorkerFetchTimeoutMs),
                workerName = "reef-stream-command-worker-p$partition"
            ).start()
        }
    }

    private fun startCanonicalProjector() {
        val partitions = projectorPartitions()
        if (partitions.isEmpty()) {
            System.err.println("canonical_projector_unavailable reason=no_partitions_configured")
            return
        }
        CanonicalProjectionWorker(
            api = api,
            projectionName = streamAckProjectionName,
            projectionSource = streamAckProjectionSource,
            eventStream = streamAckProjectionEventStream,
            partitions = partitions,
            batchSize = streamAckProjectorBatchSize,
            pollIntervalMs = streamAckProjectorPollMs,
            workerName = "reef-canonical-projector-$streamAckProjectionName"
        ).start()
    }

    private fun marketDataProjectorShouldStart(): Boolean {
        return runtimeRole.backgroundWorkersEnabled && marketDataProjectorEnabled
    }

    private fun startMarketDataProjector() {
        MarketDataProjectionWorker(
            api = api,
            projectionName = marketDataProjectorProjectionName,
            sourceProjectionName = marketDataProjectorSourceProjectionName,
            pollIntervalMs = marketDataProjectorPollMs,
            batchSize = marketDataProjectorBatchSize,
            workerName = "reef-market-data-projector-$marketDataProjectorProjectionName"
        ).start()
    }

    private fun orderLifecycleProjectorShouldStart(): Boolean {
        return runtimeRole.backgroundWorkersEnabled && orderLifecycleProjectorEnabled
    }

    private fun startOrderLifecycleProjector() {
        OrderLifecycleProjectionWorker(
            api = api,
            pollIntervalMs = orderLifecycleProjectorPollMs,
            batchSize = orderLifecycleProjectorBatchSize,
            workerName = "reef-order-lifecycle-projector"
        ).start()
    }

    private fun venueEventMaterializerShouldStart(): Boolean {
        return commandProcessingMode == CommandProcessingMode.StreamAck &&
            runtimeRole == PlatformRuntimeRole.Materializer &&
            venueEventMaterializerEnabled
    }

    private fun startVenueEventMaterializer() {
        val provider = StreamCommandLogProvider.fromEnv()
        if (provider != StreamCommandLogProvider.Redpanda) {
            System.err.println("venue_event_materializer_unavailable reason=unsupported_log_provider provider=${provider.configValue}")
            return
        }
        VenueEventBatchMaterializer(
            source = venueEventBatchSourceWithLocalFaultHooks(KafkaVenueEventBatchSource()),
            api = api,
            batchSize = venueEventMaterializerBatchSize,
            pollIntervalMs = venueEventMaterializerPollMs,
            fetchTimeout = java.time.Duration.ofMillis(venueEventMaterializerFetchTimeoutMs),
            workerName = "reef-venue-event-batch-materializer"
        ).start()
    }

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

    private fun projectorPartitions(): List<Int> {
        return configuredRuntimePartitions(streamAckProjectorPartitions, streamCommandConfig.partitionCount)
    }

    private fun streamWorkerPartitions(): List<Int> {
        return configuredRuntimePartitions(streamCommandWorkerPartitions, streamCommandConfig.partitionCount)
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

private fun defaultBoundary(): ServerBoundaryDeps {
    val hooks = defaultBoundaryHooks()
    PlatformRuntimeProfileValidator.requireValidProfile(PlatformRuntimeProfileConfig.fromEnv())
    val runtimePersistence = defaultRuntimePersistence("post-trade-profile-resolver")
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
        arenaAdminService = defaultArenaAdminService(hooks),
        analyticsRunExportService = defaultAnalyticsRunExportService(),
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

private fun defaultArenaAdminService(hooks: BoundaryHooks): AdminApplicationService? {
    if (!RuntimeEnv.bool("PLATFORM_ARENA_ADMIN_ENABLED", false)) return null
    val jdbcUrl = RuntimeEnv.string("ARENA_POSTGRES_JDBC_URL", "")
        .ifBlank { error("ARENA_POSTGRES_JDBC_URL is required when PLATFORM_ARENA_ADMIN_ENABLED=true") }
    val dbUser = RuntimeEnv.string("ARENA_POSTGRES_USER", "reef")
    val dbPassword = RuntimeEnv.string("ARENA_POSTGRES_PASSWORD", "reef")
    return AdminApplicationService(
        runtimePersistence = defaultRuntimePersistence(),
        arenaRegistryStore = PostgresArenaBotRegistryStore(
            dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "arena-admin")
        ),
        accountRiskControlStore = hooks.accountRiskCheck as? AccountRiskControlStore
    )
}

private fun defaultAnalyticsRunExportService(): SimulationRunExportService? {
    val jdbcUrl = RuntimeEnv.string("ANALYTICS_POSTGRES_JDBC_URL", "")
    val enabled = RuntimeEnv.bool("PLATFORM_ANALYTICS_EXPORT_ENABLED", jdbcUrl.isNotBlank())
    if (!enabled) return null
    val store = if (jdbcUrl.isBlank()) {
        InMemorySimulationRunExportStore()
    } else {
        val dbUser = RuntimeEnv.string("ANALYTICS_POSTGRES_USER", "reef")
        val dbPassword = RuntimeEnv.string("ANALYTICS_POSTGRES_PASSWORD", "reef")
        val schema = RuntimeEnv.string("ANALYTICS_POSTGRES_SCHEMA", "analytics")
        PostgresSimulationRunExportStore(
            dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "analytics-run-export"),
            names = PostgresAnalyticsSqlNames(schema)
        )
    }
    return SimulationRunExportService(store)
}

private fun defaultSettlementFactStore(): SettlementFactStore? {
    val explicitJdbcUrl = RuntimeEnv.string("SETTLEMENT_POSTGRES_JDBC_URL", "")
    val runtimeJdbcUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
    val jdbcUrl = explicitJdbcUrl.ifBlank { runtimeJdbcUrl }
    val enabled = RuntimeEnv.bool("PLATFORM_SETTLEMENT_FACTS_ENABLED", jdbcUrl.isNotBlank())
    if (!enabled) return null
    if (jdbcUrl.isBlank()) return InMemorySettlementFactStore()
    val dbUser = RuntimeEnv.string("SETTLEMENT_POSTGRES_USER", RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef"))
    val dbPassword = RuntimeEnv.string("SETTLEMENT_POSTGRES_PASSWORD", RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef"))
    val schema = RuntimeEnv.string("SETTLEMENT_POSTGRES_SCHEMA", "settlement")
    return PostgresSettlementFactStore(
        dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "settlement-facts"),
        names = PostgresSettlementSqlNames(schema)
    )
}

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
