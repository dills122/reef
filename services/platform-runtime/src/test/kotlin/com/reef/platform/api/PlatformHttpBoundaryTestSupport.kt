package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.admin.AdminAuthService
import com.reef.platform.application.admin.AdminGitHubOAuthClient
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.GitHubUserIdentity
import com.reef.platform.application.admin.InMemoryAdminAuthStore
import com.reef.platform.application.admin.InMemoryAdminIdentityStore
import com.reef.platform.application.settlement.DefaultPostTradePolicyVersion
import com.reef.platform.application.settlement.DefaultPostTradeProfileId
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.SettlementFactStore
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

abstract class PlatformHttpBoundaryTestSupport {
    protected fun apiReadHeaders(
        clientId: String = "client-1",
        participantId: String = "participant-1"
    ): Map<String, String> {
        return mapOf(
            "X-Client-Id" to clientId,
            "X-Participant-Id" to participantId
        )
    }

    data class HttpResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap()
    )

    protected data class TestAdminAuth(
        val authService: AdminAuthService,
        val identityService: AdminIdentityService
    )

    protected fun testAdminAuth(): TestAdminAuth {
        val identityStore = InMemoryAdminIdentityStore()
        val authStore = InMemoryAdminAuthStore()
        val identityService = AdminIdentityService(identityStore)
        return TestAdminAuth(
            authService = AdminAuthService(
                authStore = authStore,
                identityStore = identityStore,
                tokenFactory = { "tok-${tokenCounter.incrementAndGet()}-abcdefghijklmnopqrstuvwxyz0123456789" },
                tokenIdFactory = { "svc-${tokenCounter.incrementAndGet()}" }
            ),
            identityService = identityService
        )
    }

    protected fun grantTrustedPlatformAdmin(auth: TestAdminAuth, reefUserId: String) {
        auth.identityService.updateTrustState("admin-cli", reefUserId, AdminTrustState.Trusted)
        auth.identityService.assignRole("admin-cli", reefUserId, AdminIdentityService.RolePlatformAdmin)
    }

    protected fun grantTrustedOperator(auth: TestAdminAuth, reefUserId: String) {
        auth.identityService.updateTrustState("admin-cli", reefUserId, AdminTrustState.Trusted)
        auth.identityService.assignRole("admin-cli", reefUserId, AdminIdentityService.RoleOperator)
    }

    protected class FakeAdminGitHubOAuthClient : AdminGitHubOAuthClient {
        override fun authorizationUrl(stateToken: String): String {
            return "https://github.test/oauth?state=$stateToken"
        }

        override fun exchangeCode(code: String): GitHubUserIdentity {
            require(code == "github-code") { "unexpected code" }
            return GitHubUserIdentity(12345, "octo", "Octo User")
        }
    }

    protected class ThrowingAdminGitHubOAuthClient : AdminGitHubOAuthClient {
        override fun authorizationUrl(stateToken: String): String {
            return "https://github.test/oauth?state=$stateToken"
        }

        override fun exchangeCode(code: String): GitHubUserIdentity {
            throw IOException("github unavailable")
        }
    }

    protected companion object {
        val tokenCounter = AtomicInteger(0)
    }

    protected fun testServer(boundary: ExternalApiBoundary = ExternalApiBoundary()): com.sun.net.httpserver.HttpServer {
        return testServerWithGateway(StaticAcceptedEngineGateway(), boundary = boundary)
    }

    protected fun testServerWithGateway(
        gateway: EngineGateway,
        runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.Api,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
        accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
        commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        instrumentPriceCollarCheck: InstrumentPriceCollarCheck = AllowAllInstrumentPriceCollarCheck(),
        instrumentPriceCollarStore: InstrumentPriceCollarStore? = instrumentPriceCollarCheck as? InstrumentPriceCollarStore,
        adminAuthService: AdminAuthService? = null,
        adminIdentityService: AdminIdentityService? = null,
        adminGitHubOAuthClient: AdminGitHubOAuthClient? = null,
        settlementFactStore: SettlementFactStore? = null,
        settlementObligationMaterializer: TradeSettlementObligationMaterializer? = null,
        defaultPostTradeProfileId: String = DefaultPostTradeProfileId,
        defaultPostTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
        postTradeProfileResolver: PostTradeProfileResolver =
            PostTradeProfileResolver.envOnly(defaultPostTradeProfileId, defaultPostTradePolicyVersion),
        scenarioRunPostTradeProfileLookup: (String) -> String? = { null },
        venueSessionPostTradeProfileLookup: (String) -> String? = { null },
        boundaryRejectionLog: BoundaryRejectionLog = NoopBoundaryRejectionLog(),
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        legacyMutationRoutesEnabled: Boolean = true,
        localDevAdminAuthBypass: Boolean = false,
        seedOrderAuthorization: Boolean = true,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        commandIntakeBackpressureSampleMs: Long = 100L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        streamCommandIntakeStore: StreamCommandIntakeStore? = null,
        streamCommandPublisher: StreamCommandPublisher? = null,
        streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
        streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
        streamCommandMaxStorageUtilization: Double = 0.95,
        streamCommandBackpressureSampleMs: Long = 100L,
        streamCommandPublishResponseTimeoutMs: Long = 2_000L,
        venueEventMaterializerEnabled: Boolean = false,
        marketDataProjectorEnabled: Boolean = false,
        orderLifecycleProjectorEnabled: Boolean = false,
        downstreamProjectionInstrumentationEnabled: Boolean = false,
        localDevAdminUiBaseUrl: String? = null,
        runtimePersistence: InMemoryRuntimePersistence = InMemoryRuntimePersistence(),
        productRouteExtensions: List<OptionalProductRouteExtension> = emptyList()
    ): com.sun.net.httpserver.HttpServer {
        val persistence = runtimePersistence
        seedOrderReferenceData(persistence)
        if (seedOrderAuthorization) {
            seedOrderAuthorization(
                persistence,
                "bot-capture-1",
                "bot-1",
                "bot-2"
            )
        }
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        return PlatformHttpServer(
            port = 0,
            runtimeRole = runtimeRole,
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            accountRiskCheck = accountRiskCheck,
            accountRiskControlStore = accountRiskControlStore,
            accountRiskDecisionLog = accountRiskDecisionLog,
            commandCircuitBreakerCheck = commandCircuitBreakerCheck,
            commandCircuitBreakerStore = commandCircuitBreakerStore,
            instrumentPriceCollarCheck = instrumentPriceCollarCheck,
            instrumentPriceCollarStore = instrumentPriceCollarStore,
            adminAuthService = adminAuthService,
            adminIdentityService = adminIdentityService,
            adminGitHubOAuthClient = adminGitHubOAuthClient,
            settlementFactStore = settlementFactStore,
            settlementObligationMaterializer = settlementObligationMaterializer,
            defaultPostTradeProfileId = defaultPostTradeProfileId,
            defaultPostTradePolicyVersion = defaultPostTradePolicyVersion,
            postTradeProfileResolver = postTradeProfileResolver,
            scenarioRunPostTradeProfileLookup = scenarioRunPostTradeProfileLookup,
            venueSessionPostTradeProfileLookup = venueSessionPostTradeProfileLookup,
            boundaryRejectionLog = boundaryRejectionLog,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            streamCommandIntakeStore = streamCommandIntakeStore,
            streamCommandPublisher = streamCommandPublisher,
            streamCommandHealthCheck = streamCommandHealthCheck,
            streamCommandConfig = streamCommandConfig,
            streamCommandMaxStorageUtilization = streamCommandMaxStorageUtilization,
            streamCommandBackpressureSampleMs = streamCommandBackpressureSampleMs,
            streamCommandPublishResponseTimeoutMs = streamCommandPublishResponseTimeoutMs,
            venueEventMaterializerEnabled = venueEventMaterializerEnabled,
            marketDataProjectorEnabled = marketDataProjectorEnabled,
            orderLifecycleProjectorEnabled = orderLifecycleProjectorEnabled,
            downstreamProjectionInstrumentationEnabled = downstreamProjectionInstrumentationEnabled,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
            legacyMutationRoutesEnabled = legacyMutationRoutesEnabled,
            localDevAdminAuthBypass = localDevAdminAuthBypass,
            localDevAdminUiBaseUrl = localDevAdminUiBaseUrl,
            productRouteExtensions = productRouteExtensions
        ).start()
    }

    protected fun testNettyHotPathServerWithGateway(
        gateway: EngineGateway,
        runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.Api,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
        accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
        commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        commandIntakeBackpressureSampleMs: Long = 100L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        streamCommandIntakeStore: StreamCommandIntakeStore? = null,
        streamCommandPublisher: StreamCommandPublisher? = null,
        streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
        streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
        localDevAdminAuthBypass: Boolean = false
    ): RunningPlatformNettyServer {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        seedOrderAuthorization(
            persistence,
            "bot-capture-1",
            "bot-1",
            "bot-2"
        )
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val delegate = PlatformHttpServer(
            port = 0,
            runtimeRole = runtimeRole,
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            accountRiskCheck = accountRiskCheck,
            accountRiskControlStore = accountRiskControlStore,
            accountRiskDecisionLog = accountRiskDecisionLog,
            commandCircuitBreakerCheck = commandCircuitBreakerCheck,
            commandCircuitBreakerStore = commandCircuitBreakerStore,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            streamCommandIntakeStore = streamCommandIntakeStore,
            streamCommandPublisher = streamCommandPublisher,
            streamCommandHealthCheck = streamCommandHealthCheck,
            streamCommandConfig = streamCommandConfig,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
            legacyMutationRoutesEnabled = true,
            localDevAdminAuthBypass = localDevAdminAuthBypass
        )
        return PlatformNettyHotPathServer(
            delegate = delegate,
            port = 0,
            applicationThreads = 4
        ).start()
    }

    protected fun post(port: Int, path: String, headers: Map<String, String>, body: String): HttpResponse {
        val connection = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return HttpResponse(connection.responseCode, text, responseHeaders(connection))
    }

    protected fun get(port: Int, path: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val connection = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return HttpResponse(connection.responseCode, text, responseHeaders(connection))
    }

    protected fun options(port: Int, path: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        return requestWithHeaders("OPTIONS", port, path, headers)
    }

    protected fun requestWithHeaders(
        method: String,
        port: Int,
        path: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$port$path"))
            .method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = java.net.http.HttpClient.newHttpClient().send(
            builder.build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        )
        return HttpResponse(response.statusCode(), response.body(), response.headers().map())
    }

    protected fun apiGet(port: Int, path: String, headers: Map<String, String> = apiReadHeaders()): HttpResponse {
        return get(port, path, headers)
    }

    protected fun responseHeaders(connection: HttpURLConnection): Map<String, List<String>> {
        return connection.headerFields.entries.mapNotNull { (key, value) ->
            key?.let { it to value.toList() }
        }.toMap()
    }

    protected fun responseHeader(response: HttpResponse, name: String): String {
        return response.headers.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            .orEmpty()
    }

    protected fun envLookup(vararg pairs: Pair<String, String>): (String) -> String? {
        val values = pairs.toMap()
        return { key -> values[key] }
    }

    protected fun waitForCommandStatus(
        port: Int,
        commandId: String,
        status: String,
        headers: Map<String, String> = apiReadHeaders()
    ): HttpResponse {
        var last = get(port, "/api/v1/commands/$commandId", headers = headers)
        repeat(50) {
            if (last.status == 200 && last.body.contains("\"status\":\"$status\"")) {
                return last
            }
            Thread.sleep(20)
            last = get(port, "/api/v1/commands/$commandId", headers = headers)
        }
        return last
    }

    protected fun validSubmitBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"$extra
            }
        """.trimIndent()
    }

    protected fun validCancelBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "runId":"run-1",
              "venueSessionId":"session-1",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "reason":"test"$extra
            }
        """.trimIndent()
    }

    protected fun validModifyBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "runId":"run-1",
              "venueSessionId":"session-1",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "quantityUnits":"100",
              "limitPrice":"150250000001"$extra
            }
        """.trimIndent()
    }

    protected fun p2SettlementFactsBody(
        scenarioRunId: String,
        includePostTradeProfile: Boolean = true,
        venueSessionId: String = ""
    ): String {
        val postTradeProfile = if (includePostTradeProfile) {
            """
              "postTradeProfileId":"instant-post-trade-v1",
              "postTradePolicyVersion":2,
            """.trimIndent()
        } else {
            ""
        }
        val venueSession = if (venueSessionId.isNotBlank()) {
            """"venueSessionId":"$venueSessionId","""
        } else {
            ""
        }
        return """
            {
              "scenarioRunId":"$scenarioRunId",
              $venueSession
              $postTradeProfile
              "obligations":[{
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"trade-1",
                "tradeId":"trade-1",
                "buyerParticipantId":"buyer-1",
                "sellerParticipantId":"seller-1",
                "instrumentId":"AAPL",
                "quantity":"100",
                "cashAmount":"15000.00",
                "currency":"USD",
                "state":"OBLIGATION_CREATED",
                "occurredAt":"2026-01-01T00:00:00Z"
              }],
              "instructions":[{
                "settlementInstructionId":"instruction-1",
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"obl-1",
                "instructionType":"DVP",
                "state":"INSTRUCTION_CREATED",
                "occurredAt":"2026-01-01T00:00:00Z"
              }],
              "attempts":[{
                "settlementAttemptId":"attempt-1",
                "settlementObligationId":"obl-1",
                "settlementInstructionId":"instruction-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"instruction-1",
                "attemptNumber":1,
                "state":"ATTEMPT_STARTED",
                "occurredAt":"2026-01-01T00:00:00Z"
              }],
              "breaks":[{
                "settlementBreakId":"break-1",
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"trade-1",
                "reason":"CASH_LEG_FAILED",
                "state":"BROKEN",
                "occurredAt":"2026-01-01T00:00:01Z"
              }],
              "repairs":[{
                "settlementRepairId":"repair-1",
                "settlementBreakId":"break-1",
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"repair-command-1",
                "repairAction":"POST_CASH_LEG_REPAIR",
                "actorType":"USER",
                "actorId":"ops-user-1",
                "occurredAt":"2026-01-01T00:00:02Z"
              }],
              "resolutions":[{
                "settlementResolutionId":"resolution-1",
                "settlementObligationId":"obl-1",
                "settlementBreakId":"break-1",
                "settlementRepairId":"repair-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"repair-command-1",
                "settlementState":"RESOLVED",
                "exceptionState":"RESOLVED",
                "occurredAt":"2026-01-01T00:00:03Z"
              }]
            }
        """.trimIndent()
    }

    protected fun streamRoutingExtra(): String {
        return ",\"runId\":\"run-1\",\"venueSessionId\":\"session-1\",\"clientOrderId\":\"clord-1\",\"botId\":\"bot-1\",\"botVersion\":\"v1\""
    }

    protected fun venueEventBatch(
        batchId: String,
        commandId: String,
        resultStatus: String,
        rejectCode: String = "",
        commandType: String = "SubmitOrder",
        orderId: String = "ord-$commandId",
        resultPayloadJson: String? = null
    ): VenueEventBatchFact {
        val resultPayload = resultPayloadJson ?: if (resultStatus == "rejected") {
            """{"rejected":{"code":"$rejectCode","participantId":"participant-1","orderId":"$orderId"}}"""
        } else {
            """{"accepted":{"eventId":"evt-$commandId","participantId":"participant-1","orderId":"$orderId"}}"""
        }
        return VenueEventBatchFact(
            batchId = batchId,
            shardId = "engine-0",
            partition = 7,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 7001,
            lastSequence = 7001,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = "checksum-$batchId",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = commandId,
                    commandType = commandType,
                    streamSequence = 7001,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-$commandId",
                    instrumentId = "AAPL",
                    orderId = orderId,
                    resultStatus = resultStatus,
                    rejectCode = rejectCode,
                    resultPayloadJson = resultPayload
                )
            )
        )
    }

    protected fun bodyWithoutField(body: String, field: String): String {
        val withoutField = body
            .lines()
            .filterNot { it.trimStart().startsWith("\"$field\":") }
            .joinToString("\n")
        return Regex(""",(\s*})""").replace(withoutField) { match ->
            match.groupValues[1]
        }
    }

    protected fun seedReferenceData(port: Int) {
        post(
            port = port,
            path = "/reference/instruments",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
        )
        post(
            port = port,
            path = "/reference/participants",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"participantId":"participant-1","name":"Participant 1"}"""
        )
        post(
            port = port,
            path = "/reference/accounts",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"accountId":"account-1","participantId":"participant-1"}"""
        )
    }

    protected fun seedOrderRoleBindings(port: Int, vararg actorIds: String) {
        post(
            port = port,
            path = "/auth/roles",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"roleId":"order_trader","permissions":"order.submit,order.cancel,order.modify"}"""
        )
        actorIds.forEach { actorId ->
            post(
                port = port,
                path = "/auth/actor-roles",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"actorId":"$actorId","roleId":"order_trader"}"""
            )
        }
    }

    protected fun seedOrderReferenceData(persistence: InMemoryRuntimePersistence) {
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
    }

    protected fun seedOrderAuthorization(persistence: InMemoryRuntimePersistence, vararg actorIds: String) {
        persistence.saveRole(
            RoleDefinition(
                "order_trader",
                listOf(Permission.ORDER_SUBMIT, Permission.ORDER_CANCEL, Permission.ORDER_MODIFY)
            )
        )
        actorIds.forEach { actorId ->
            persistence.saveActorRoleBinding(ActorRoleBinding(actorId, "order_trader"))
        }
    }

    protected fun persistedOrder(
        orderId: String,
        participantId: String,
        side: String,
        runId: String,
        venueSessionId: String
    ): PersistedOrder {
        return PersistedOrder(
            orderId = orderId,
            engineOrderId = "eng-$orderId",
            instrumentId = "AAPL",
            participantId = participantId,
            accountId = "account-$participantId",
            side = side,
            orderType = "LIMIT",
            quantityUnits = "100",
            limitPrice = "150250000000",
            currency = "USD",
            timeInForce = "DAY",
            acceptedAt = "2026-01-01T00:00:00Z",
            runId = runId,
            venueSessionId = venueSessionId
        )
    }
}
