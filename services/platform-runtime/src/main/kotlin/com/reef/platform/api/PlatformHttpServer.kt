package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.defaultRuntimePersistence
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors

private data class CommandIntakeBackpressureSnapshot(
    val active: Long,
    val staleProcessing: Long,
    val sampledAtMs: Long
)

enum class PlatformRuntimeRole(
    val configValue: String,
    val publicHttpEnabled: Boolean,
    val backgroundWorkersEnabled: Boolean
) {
    Api("api", publicHttpEnabled = true, backgroundWorkersEnabled = false),
    Worker("worker", publicHttpEnabled = false, backgroundWorkersEnabled = true),
    Projector("projector", publicHttpEnabled = false, backgroundWorkersEnabled = true);

    companion object {
        fun from(raw: String): PlatformRuntimeRole {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.configValue == normalized }
                ?: throw IllegalArgumentException("Unsupported PLATFORM_RUNTIME_ROLE: $raw")
        }

        fun fromEnv(): PlatformRuntimeRole {
            return from(RuntimeEnv.string("PLATFORM_RUNTIME_ROLE", "api"))
        }
    }
}

class PlatformHttpServer(
    private val port: Int = RuntimeEnv.int("PLATFORM_RUNTIME_PORT", 8080),
    private val runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.fromEnv(),
    private val api: PlatformApi = PlatformApi(),
    private val boundary: ExternalApiBoundary,
    private val abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
    private val idempotencyStore: IdempotencyStore,
    private val idempotencyRetentionPolicy: IdempotencyRetentionPolicy,
    private val commandCaptureStore: CommandCaptureStore = NoopCommandCaptureStore(),
    private val commandStatusLookup: CommandStatusLookup? = commandCaptureStore as? CommandStatusLookup,
    private val capturedCommandQueue: CapturedCommandQueue? = commandCaptureStore as? CapturedCommandQueue,
    private val streamCommandIntakeStore: StreamCommandIntakeStore? = null,
    private val streamCommandPublisher: StreamCommandPublisher? = null,
    private val streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
    private val streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
    private val streamCommandMaxStorageUtilization: Double =
        RuntimeEnv.string("STREAM_ACK_MAX_STORAGE_UTILIZATION", "0.95").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.95,
    private val streamCommandWorkerEnabled: Boolean = RuntimeEnv.bool("STREAM_ACK_WORKER_ENABLED", false),
    private val streamCommandWorkerPartitions: String = RuntimeEnv.string("STREAM_ACK_WORKER_PARTITIONS", "0"),
    private val streamCommandWorkerBatchSize: Int = RuntimeEnv.int("STREAM_ACK_WORKER_BATCH_SIZE", 100, min = 1),
    private val streamCommandWorkerPollMs: Long = RuntimeEnv.long("STREAM_ACK_WORKER_POLL_MS", 25L, min = 1L),
    private val streamCommandWorkerFetchTimeoutMs: Long = RuntimeEnv.long("STREAM_ACK_WORKER_FETCH_TIMEOUT_MS", 200L, min = 1L),
    private val streamCommandWorkerDedicatedRuntimePoolEnabled: Boolean =
        RuntimeEnv.bool("STREAM_ACK_WORKER_DEDICATED_RUNTIME_POOL_ENABLED", false),
    private val streamAckProjectorEnabled: Boolean = RuntimeEnv.bool("STREAM_ACK_PROJECTOR_ENABLED", true),
    private val streamAckProjectionName: String = RuntimeEnv.string("STREAM_ACK_PROJECTION_NAME", "runtime-normalized-submit"),
    private val streamAckProjectorPartitions: String = RuntimeEnv.string("STREAM_ACK_PROJECTOR_PARTITIONS", "all"),
    private val streamAckProjectorBatchSize: Int = RuntimeEnv.int("STREAM_ACK_PROJECTOR_BATCH_SIZE", 250, min = 1),
    private val streamAckProjectorPollMs: Long = RuntimeEnv.long("STREAM_ACK_PROJECTOR_POLL_MS", 50L, min = 1L),
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
    private val maxRequestBodyBytes: Int =
        RuntimeEnv.int("PLATFORM_HTTP_MAX_REQUEST_BYTES", 1024 * 1024, min = 1024)
    @Volatile
    private var backpressureSnapshot: CommandIntakeBackpressureSnapshot? = null
    private val backpressureSnapshotLock = Any()

    constructor(
        port: Int = RuntimeEnv.int("PLATFORM_RUNTIME_PORT", 8080),
        api: PlatformApi = PlatformApi(),
        deps: ServerBoundaryDeps = defaultBoundary()
    ) : this(
        port = port,
        api = api,
        boundary = deps.boundary,
        abuseProtectionHook = deps.abuseProtectionHook,
        idempotencyStore = deps.idempotencyStore,
        idempotencyRetentionPolicy = deps.idempotencyRetentionPolicy,
        commandCaptureStore = deps.commandCaptureStore,
        commandStatusLookup = deps.commandStatusLookup,
        capturedCommandQueue = deps.capturedCommandQueue,
        streamCommandIntakeStore = deps.streamCommandIntakeStore,
        streamCommandPublisher = deps.streamCommandPublisher,
        streamCommandHealthCheck = deps.streamCommandHealthCheck,
        streamCommandConfig = deps.streamCommandConfig,
        streamCommandMaxStorageUtilization = deps.streamCommandMaxStorageUtilization,
        commandProcessingMode = deps.commandProcessingMode
    )

    fun start(): HttpServer {
        val backlog = RuntimeEnv.int("PLATFORM_HTTP_BACKLOG", 1024, min = 64)
        val server = HttpServer.create(InetSocketAddress(port), backlog)
        val workerThreads = RuntimeEnv.int("PLATFORM_HTTP_THREADS", 32, min = 4)
        server.executor = Executors.newFixedThreadPool(workerThreads)

        server.createContext("/health") { exchange ->
            writeJson(exchange, 200, api.health())
        }

        server.createContext("/internal/boundary/abuse/stats") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, abuseStatsJson(abuseProtectionHook.stats()))
        }

        server.createContext("/internal/perf/hot-path") { exchange ->
            when (exchange.requestMethod) {
                "GET" -> writeJson(exchange, 200, JsonCodec.writeObject("metrics" to HotPathMetrics.snapshot()))
                "POST" -> {
                    HotPathMetrics.reset()
                    writeJson(exchange, 200, JsonCodec.writeObject("status" to "reset"))
                }
                else -> methodNotAllowed(exchange)
            }
        }

        server.createContext("/internal/perf/db-pools") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, dbPoolStatsJson())
        }

        server.createContext("/internal/commands/async/stats") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, asyncCommandStatsJson())
        }

        server.createContext("/internal/commands/accounting") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, commandAccountingJson(queryValue(exchange, "runId")))
        }

        server.createContext("/internal/stream-ack/health") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, streamCommandHealthJson())
        }

        server.createContext("/internal/stream-ack/worker/stats") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, streamCommandWorkerStatsJson())
        }

        server.createContext("/internal/projector/status") { exchange ->
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            writeJson(exchange, 200, projectorStatusJson())
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
        println("platform-runtime role=${runtimeRole.configValue} listening on :$port")
        return server
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

    private fun methodNotAllowed(exchange: HttpExchange) {
        exchange.sendResponseHeaders(405, -1)
        exchange.close()
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
        val status = api.projectionStatus(streamAckProjectionName, partitions)
        val metrics = CanonicalProjectionMetrics.snapshot()
        return JsonCodec.writeObject(
            "role" to runtimeRole.configValue,
            "status" to if (runtimeRole == PlatformRuntimeRole.Projector && streamAckProjectorEnabled) "running" else "inactive",
            "implementation" to "canonical-submit-projector",
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
        val values = query.split("&")
        for (value in values) {
            val parts = value.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == key) {
                return parts[1]
            }
        }
        return ""
    }

    private fun correlationId(exchange: HttpExchange): String {
        return exchange.requestHeaders["X-Correlation-Id"]?.firstOrNull() ?: ""
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
        operation: (String) -> String
    ) {
        if (exchange.requestMethod != "POST") {
            methodNotAllowed(exchange)
            return
        }

        val violation = boundary.checkWrite(exchange.requestHeaders, route)
        if (violation != null) {
            writeJson(exchange, violation.status, boundary.toErrorJson(violation, correlationId(exchange)))
            return
        }

        val clientId = boundary.clientId(exchange.requestHeaders).orEmpty()
        val idempotencyKey = boundary.idempotencyKey(exchange.requestHeaders).orEmpty()
        val correlationId = correlationId(exchange)
        val body = readRequestBody(exchange) ?: return
        val validationError = PlatformCommandParsers.validateApiV1Command(route, body)
        if (validationError != null) {
            writeJson(exchange, 400, boundary.toErrorJson(BoundaryError(400, "VALIDATION_ERROR", validationError), correlationId))
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

        val abuseViolation = abuseProtectionHook.allow(clientId, route)
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
            writeJson(exchange, cached.status, cached.payload)
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
            writeJson(exchange, 200, payload)
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

    private fun handleStreamAckMutation(
        exchange: HttpExchange,
        route: String,
        clientId: String,
        idempotencyKey: String,
        correlationId: String,
        body: String
    ) {
        val intakeStore = streamCommandIntakeStore
        val publisher = streamCommandPublisher
        if (intakeStore == null || publisher == null) {
            writeJson(exchange, 503, simpleErrorJson("stream command intake unavailable"))
            return
        }

        val envelopeResult = StreamCommandEnvelopeBuilder.fromRequest(
            clientId = clientId,
            route = route,
            idempotencyKey = idempotencyKey,
            body = body,
            config = streamCommandConfig
        )
        val envelope = when (envelopeResult) {
            is EitherBoundaryError.Error -> {
                writeJson(exchange, envelopeResult.error.status, boundary.toErrorJson(envelopeResult.error, correlationId))
                return
            }
            is EitherBoundaryError.Envelope -> envelopeResult.envelope
        }

        val abuseViolation = abuseProtectionHook.allow(clientId, route)
        if (abuseViolation != null) {
            writeJson(exchange, abuseViolation.status, boundary.toErrorJson(abuseViolation, correlationId))
            return
        }

        val streamBackpressure = streamCommandBackpressure()
        if (streamBackpressure != null) {
            writeJson(exchange, streamBackpressure.status, boundary.toErrorJson(streamBackpressure, correlationId))
            return
        }

        val initialReference = envelope.reference(streamCommandConfig.streamName)
        val reservation = try {
            intakeStore.reserve(envelope, initialReference)
        } catch (ex: Exception) {
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "stream_command_intake_unavailable route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId message=${JsonFields.escape(errorMessage)}"
            )
            writeJson(exchange, 503, simpleErrorJson("stream command intake unavailable", errorMessage))
            return
        }

        when (reservation) {
            is StreamCommandReservation.Conflict -> {
                writeJson(
                    exchange,
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
                return
            }
            is StreamCommandReservation.Replay -> {
                if (reservation.reference.streamSequence > 0L) {
                    writeJson(exchange, 202, StreamCommandResponse.acceptedJson(reservation.reference))
                    return
                }
            }
            is StreamCommandReservation.Reserved -> {
            }
        }

        val ack = try {
            publisher.publish(envelope)
        } catch (ex: Exception) {
            val errorMessage = ex.message ?: "unknown"
            System.err.println(
                "stream_command_publish_failed route=$route clientId=$clientId idempotencyKey=$idempotencyKey correlationId=$correlationId subject=${envelope.subject} message=${JsonFields.escape(errorMessage)}"
            )
            writeJson(exchange, 503, simpleErrorJson("stream command publish unavailable", errorMessage))
            return
        }
        val publishedReference = intakeStore.markPublished(envelope.scope, envelope.idempotencyKey, ack.streamSequence)
            ?: envelope.reference(ack.streamName, ack.streamSequence)
        writeJson(exchange, 202, StreamCommandResponse.acceptedJson(publishedReference))
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
        if (status != null && commandProcessingMode == CommandProcessingMode.CapturedAck) {
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

    private fun handleCommandStatusLookup(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            methodNotAllowed(exchange)
            return
        }
        val lookup = commandStatusLookup
        if (lookup == null) {
            writeJson(exchange, 503, simpleErrorJson("command status unavailable"))
            return
        }
        val commandId = exchange.requestURI.path.removePrefix("/api/v1/commands/").trim('/')
        if (commandId.isBlank()) {
            writeJson(exchange, 404, simpleErrorJson("command not found"))
            return
        }
        val status = lookup.findCommandStatus(commandId)
        if (status == null) {
            writeJson(exchange, 404, simpleErrorJson("command not found"))
            return
        }
        writeJson(exchange, 200, CommandStatusResponse.statusJson(status))
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

    private fun asyncCommandStatsJson(): String {
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
        val health = streamCommandHealthCheck?.snapshot() ?: return null
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
        return null
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
            "publishAckLastMs" to snapshot.publishAckLastMs,
            "publishAckMaxMs" to snapshot.publishAckMaxMs,
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
            partitions = partitions,
            batchSize = streamAckProjectorBatchSize,
            pollIntervalMs = streamAckProjectorPollMs,
            workerName = "reef-canonical-projector-$streamAckProjectionName"
        ).start()
    }

    private fun projectorPartitions(): List<Int> {
        return configuredPartitions(streamAckProjectorPartitions)
    }

    private fun streamWorkerPartitions(): List<Int> {
        return configuredPartitions(streamCommandWorkerPartitions)
    }

    private fun configuredPartitions(rawValue: String): List<Int> {
        val raw = rawValue.trim()
        if (raw.equals("all", ignoreCase = true)) {
            return (0 until streamCommandConfig.partitionCount).toList()
        }
        return raw.split(",")
            .mapNotNull { value -> value.trim().toIntOrNull() }
            .filter { it in 0 until streamCommandConfig.partitionCount }
            .distinct()
            .sorted()
    }
}

private const val DEFAULT_BODY_BUFFER_BYTES = 8192
private const val LEGACY_INTERNAL_ROUTE_HEADER = "X-Reef-Internal-Route"

private fun defaultBoundary(): ServerBoundaryDeps {
    val hooks = defaultBoundaryHooks()
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

data class ServerBoundaryDeps(
    val boundary: ExternalApiBoundary,
    val abuseProtectionHook: AbuseProtectionHook,
    val idempotencyStore: IdempotencyStore,
    val idempotencyRetentionPolicy: IdempotencyRetentionPolicy,
    val commandCaptureStore: CommandCaptureStore,
    val commandStatusLookup: CommandStatusLookup? = commandCaptureStore as? CommandStatusLookup,
    val capturedCommandQueue: CapturedCommandQueue? = commandCaptureStore as? CapturedCommandQueue,
    val streamCommandIntakeStore: StreamCommandIntakeStore? = null,
    val streamCommandPublisher: StreamCommandPublisher? = null,
    val streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
    val streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
    val streamCommandMaxStorageUtilization: Double =
        RuntimeEnv.string("STREAM_ACK_MAX_STORAGE_UTILIZATION", "0.95").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.95,
    val commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult
)
