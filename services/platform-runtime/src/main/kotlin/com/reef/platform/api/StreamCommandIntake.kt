package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsMessage
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.math.max

data class StreamCommandConfig(
    val streamName: String = RuntimeEnv.string("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS"),
    val subjectPrefix: String = RuntimeEnv.string("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1"),
    val partitionCount: Int = RuntimeEnv.int("STREAM_ACK_PARTITION_COUNT", 16, min = 1)
)

data class StreamCommandReference(
    val commandId: String,
    val route: String,
    val subject: String,
    val streamName: String,
    val partition: Int,
    val streamSequence: Long = 0L
)

data class StreamCommandEnvelope(
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val payloadHash: String,
    val commandId: String,
    val commandType: String,
    val runId: String,
    val venueSessionId: String,
    val instrumentId: String,
    val orderId: String,
    val clientOrderId: String,
    val actorId: String,
    val traceId: String,
    val correlationId: String,
    val causationId: String,
    val botId: String,
    val botVersion: String,
    val subject: String,
    val partition: Int,
    val payloadJson: String
) {
    val scope: String = "$clientId|$route"
    val natsMessageId: String = "$scope|$idempotencyKey"

    fun reference(streamName: String, streamSequence: Long = 0L): StreamCommandReference {
        return StreamCommandReference(
            commandId = commandId,
            route = route,
            subject = subject,
            streamName = streamName,
            partition = partition,
            streamSequence = streamSequence
        )
    }
}

sealed class StreamCommandReservation {
    data class Reserved(val reference: StreamCommandReference) : StreamCommandReservation()
    data class Replay(val reference: StreamCommandReference) : StreamCommandReservation()
    data class Conflict(val existingPayloadHash: String) : StreamCommandReservation()
}

interface StreamCommandPublicationMarker {
    fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean
}

interface StreamCommandIntakeStore : StreamCommandPublicationMarker {
    fun reserve(envelope: StreamCommandEnvelope, reference: StreamCommandReference): StreamCommandReservation
    fun markPublished(scope: String, idempotencyKey: String, streamSequence: Long): Boolean
    fun findByCommandId(commandId: String): StreamCommandReference?
}

class InMemoryStreamCommandIntakeStore : StreamCommandIntakeStore {
    private data class Entry(
        val scope: String,
        val idempotencyKey: String,
        val payloadHash: String,
        val reference: StreamCommandReference
    )

    private val byIdempotency = ConcurrentHashMap<String, Entry>()
    private val byCommandId = ConcurrentHashMap<String, String>()

    override fun reserve(envelope: StreamCommandEnvelope, reference: StreamCommandReference): StreamCommandReservation {
        val key = key(envelope.scope, envelope.idempotencyKey)
        synchronized(this) {
            val existing = byIdempotency[key]
            if (existing != null) {
                if (existing.payloadHash != envelope.payloadHash) {
                    return StreamCommandReservation.Conflict(existing.payloadHash)
                }
                return StreamCommandReservation.Replay(existing.reference)
            }
            val commandKey = byCommandId[reference.commandId]
            if (commandKey != null) {
                val existingByCommand = byIdempotency.getValue(commandKey)
                if (existingByCommand.payloadHash != envelope.payloadHash) {
                    return StreamCommandReservation.Conflict(existingByCommand.payloadHash)
                }
                return StreamCommandReservation.Replay(existingByCommand.reference)
            }
            val entry = Entry(envelope.scope, envelope.idempotencyKey, envelope.payloadHash, reference)
            byIdempotency[key] = entry
            byCommandId[reference.commandId] = key
            return StreamCommandReservation.Reserved(reference)
        }
    }

    override fun markPublished(scope: String, idempotencyKey: String, streamSequence: Long): Boolean {
        val key = key(scope, idempotencyKey)
        synchronized(this) {
            val existing = byIdempotency[key] ?: return false
            val published = existing.reference.copy(streamSequence = streamSequence)
            byIdempotency[key] = existing.copy(reference = published)
            return true
        }
    }

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        synchronized(this) {
            val key = byCommandId[commandId] ?: return false
            val existing = byIdempotency[key] ?: return false
            val published = existing.reference.copy(streamSequence = streamSequence)
            byIdempotency[key] = existing.copy(reference = published)
            return true
        }
    }

    override fun findByCommandId(commandId: String): StreamCommandReference? {
        return byCommandId[commandId]?.let { byIdempotency[it]?.reference }
    }

    private fun key(scope: String, idempotencyKey: String): String = "$scope|$idempotencyKey"
}

class PostgresStreamCommandIntakeStore(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : StreamCommandIntakeStore {
    init {
        dataSource.connection.use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryStreamCommandIntake(names.streamCommandIntake)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.streamCommandIntake} (
                      scope TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      payload_hash TEXT NOT NULL,
                      command_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      subject TEXT NOT NULL,
                      stream_name TEXT NOT NULL,
                      partition INTEGER NOT NULL,
                      stream_sequence BIGINT NOT NULL DEFAULT 0,
                      published BOOLEAN NOT NULL DEFAULT FALSE,
                      first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      published_at TIMESTAMPTZ,
                      PRIMARY KEY (scope, idempotency_key),
                      UNIQUE (command_id)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun reserve(envelope: StreamCommandEnvelope, reference: StreamCommandReference): StreamCommandReservation {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.streamCommandIntake}(
                  scope, idempotency_key, payload_hash, command_id, route, subject,
                  stream_name, partition, stream_sequence, published
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, FALSE)
                ON CONFLICT DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, envelope.scope)
                ps.setString(2, envelope.idempotencyKey)
                ps.setString(3, envelope.payloadHash)
                ps.setString(4, reference.commandId)
                ps.setString(5, reference.route)
                ps.setString(6, reference.subject)
                ps.setString(7, reference.streamName)
                ps.setInt(8, reference.partition)
                if (ps.executeUpdate() == 1) {
                    return StreamCommandReservation.Reserved(reference)
                }
            }
        }
        val existing = findByScope(envelope.scope, envelope.idempotencyKey)
            ?: findExistingByCommandId(envelope.commandId)
            ?: return StreamCommandReservation.Conflict("")
        if (existing.payloadHash != envelope.payloadHash) {
            return StreamCommandReservation.Conflict(existing.payloadHash)
        }
        return StreamCommandReservation.Replay(existing.reference)
    }

    override fun markPublished(scope: String, idempotencyKey: String, streamSequence: Long): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.streamCommandIntake}
                SET stream_sequence = CASE WHEN stream_sequence = 0 THEN ? ELSE stream_sequence END,
                    published = TRUE,
                    published_at = COALESCE(published_at, NOW())
                WHERE scope = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, streamSequence)
                ps.setString(2, scope)
                ps.setString(3, idempotencyKey)
                return ps.executeUpdate() > 0
            }
        }
    }

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.streamCommandIntake}
                SET stream_sequence = CASE WHEN stream_sequence = 0 THEN ? ELSE stream_sequence END,
                    published = TRUE,
                    published_at = COALESCE(published_at, NOW())
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, streamSequence)
                ps.setString(2, commandId)
                return ps.executeUpdate() > 0
            }
        }
    }

    override fun findByCommandId(commandId: String): StreamCommandReference? {
        return findExistingByCommandId(commandId)?.reference
    }

    private fun findExistingByCommandId(commandId: String): ExistingStreamReservation? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT payload_hash, command_id, route, subject, stream_name, partition, stream_sequence
                FROM ${names.streamCommandIntake}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return ExistingStreamReservation(
                        payloadHash = rs.getString("payload_hash"),
                        reference = StreamCommandReference(
                            commandId = rs.getString("command_id"),
                            route = rs.getString("route"),
                            subject = rs.getString("subject"),
                            streamName = rs.getString("stream_name"),
                            partition = rs.getInt("partition"),
                            streamSequence = rs.getLong("stream_sequence")
                        )
                    )
                }
            }
        }
    }

    private fun findByScope(scope: String, idempotencyKey: String): ExistingStreamReservation? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT payload_hash, command_id, route, subject, stream_name, partition, stream_sequence
                FROM ${names.streamCommandIntake}
                WHERE scope = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, idempotencyKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return ExistingStreamReservation(
                        payloadHash = rs.getString("payload_hash"),
                        reference = StreamCommandReference(
                            commandId = rs.getString("command_id"),
                            route = rs.getString("route"),
                            subject = rs.getString("subject"),
                            streamName = rs.getString("stream_name"),
                            partition = rs.getInt("partition"),
                            streamSequence = rs.getLong("stream_sequence")
                        )
                    )
                }
            }
        }
    }

    private data class ExistingStreamReservation(
        val payloadHash: String,
        val reference: StreamCommandReference
    )
}

class AsyncStreamCommandPublicationMarker(
    private val delegate: StreamCommandPublicationMarker,
    workerCount: Int = RuntimeEnv.int("STREAM_ACK_MARK_PUBLISHED_WORKERS", 2, min = 1),
    queueCapacity: Int = RuntimeEnv.int("STREAM_ACK_MARK_PUBLISHED_QUEUE_CAPACITY", 100_000, min = 1)
) : StreamCommandPublicationMarker {
    private data class PublishedCommand(val commandId: String, val streamSequence: Long)

    private val queue = LinkedBlockingQueue<PublishedCommand>(queueCapacity)

    init {
        repeat(workerCount) { index ->
            thread(name = "reef-stream-published-marker-$index", isDaemon = true) {
                while (true) {
                    val next = queue.take()
                    try {
                        HotPathMetrics.time("api.streamAck.markPublished.asyncFlush") {
                            delegate.markPublishedByCommandId(next.commandId, next.streamSequence)
                        }
                    } catch (ex: Exception) {
                        System.err.println(
                            "stream_async_mark_published_failed commandId=${next.commandId} streamSequence=${next.streamSequence} message=${ex.message ?: "unknown"}"
                        )
                    }
                }
            }
        }
    }

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        val queued = queue.offer(PublishedCommand(commandId, streamSequence))
        if (queued) return true
        return HotPathMetrics.time("api.streamAck.markPublished.queueFullSync") {
            delegate.markPublishedByCommandId(commandId, streamSequence)
        }
    }
}

data class StreamPublishAck(val streamName: String, val streamSequence: Long)

interface StreamCommandPublisher {
    fun publish(envelope: StreamCommandEnvelope): StreamPublishAck
}

data class StreamCommandHealthSnapshot(
    val available: Boolean,
    val streamName: String,
    val messageCount: Long = 0L,
    val byteCount: Long = 0L,
    val maxBytes: Long = 0L,
    val storageUtilization: Double = 0.0,
    val publishAckLastMs: Long = 0L,
    val publishAckMaxMs: Long = 0L,
    val checkedAt: Instant = Instant.now(),
    val error: String = ""
)

interface StreamCommandHealthCheck {
    fun snapshot(): StreamCommandHealthSnapshot
}

class NatsJetStreamCommandPublisher(
    private val natsUrl: String = RuntimeEnv.string("STREAM_ACK_NATS_URL", "nats://localhost:4222"),
    private val ackTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", 2_000L, min = 1L)),
    private val config: StreamCommandConfig = StreamCommandConfig()
) : StreamCommandPublisher, StreamCommandHealthCheck {
    private val lastPublishAckMs = AtomicLong(0L)
    private val maxPublishAckMs = AtomicLong(0L)
    private val connection by lazy {
        val options = Options.Builder()
            .server(natsUrl)
            .connectionTimeout(ackTimeout)
            .build()
        Nats.connect(options)
    }
    private val jetStream by lazy { connection.jetStream() }

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        val headers = Headers().put("Nats-Msg-Id", envelope.natsMessageId)
        val message = NatsMessage.builder()
            .subject(envelope.subject)
            .headers(headers)
            .data(envelope.payloadJson.toByteArray(Charsets.UTF_8))
            .build()
        val started = System.nanoTime()
        val ack = jetStream.publish(message)
        val elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
        lastPublishAckMs.set(elapsedMs)
        maxPublishAckMs.accumulateAndGet(elapsedMs, ::maxOf)
        return StreamPublishAck(
            streamName = ack.stream ?: config.streamName,
            streamSequence = ack.seqno
        )
    }

    override fun snapshot(): StreamCommandHealthSnapshot {
        return try {
            val info = connection.jetStreamManagement().getStreamInfo(config.streamName)
            val state = info.streamState
            val maxBytes = info.configuration.maxBytes
            val byteCount = state.byteCount
            StreamCommandHealthSnapshot(
                available = true,
                streamName = config.streamName,
                messageCount = state.msgCount,
                byteCount = byteCount,
                maxBytes = maxBytes,
                storageUtilization = storageUtilization(byteCount, maxBytes),
                publishAckLastMs = lastPublishAckMs.get(),
                publishAckMaxMs = maxPublishAckMs.get()
            )
        } catch (ex: Exception) {
            StreamCommandHealthSnapshot(
                available = false,
                streamName = config.streamName,
                publishAckLastMs = lastPublishAckMs.get(),
                publishAckMaxMs = maxPublishAckMs.get(),
                error = ex.message ?: "unknown"
            )
        }
    }

    private fun storageUtilization(byteCount: Long, maxBytes: Long): Double {
        if (maxBytes <= 0L) return 0.0
        return byteCount.toDouble() / maxBytes.toDouble()
    }
}

object StreamCommandIntakeFactory {
    fun defaultStore(): StreamCommandIntakeStore {
        return when (RuntimeEnv.string("STREAM_ACK_INTAKE_STORE", "postgres").trim().lowercase()) {
            "inmemory" -> InMemoryStreamCommandIntakeStore()
            "postgres" -> {
                val jdbcUrl = RuntimeEnv.string("RUNTIME_DB_URL", "jdbc:postgresql://localhost:5432/reef")
                val dbUser = RuntimeEnv.string("RUNTIME_DB_USER", "reef")
                val dbPassword = RuntimeEnv.string("RUNTIME_DB_PASSWORD", "reef")
                PostgresStreamCommandIntakeStore(
                    RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "stream-intake")
                )
            }
            else -> throw IllegalArgumentException("Unsupported STREAM_ACK_INTAKE_STORE")
        }
    }

    fun defaultPublisher(): StreamCommandPublisher {
        return NatsJetStreamCommandPublisher()
    }
}

object StreamCommandEnvelopeBuilder {
    fun fromRequest(
        clientId: String,
        route: String,
        idempotencyKey: String,
        body: String,
        config: StreamCommandConfig = StreamCommandConfig()
    ): EitherBoundaryError {
        val json = JsonCodec.parseObjectOrEmpty(body)
        val commandId = json.string("commandId")
        val commandType = commandType(route)
        val runId = json.string("runId").ifBlank { json.string("scenarioRunId") }
        val venueSessionId = json.string("venueSessionId")
        val instrumentId = json.string("instrumentId")
        val orderId = json.string("orderId")
        val actorId = json.string("actorId")
        val missing = listOf(
            "runId" to runId,
            "venueSessionId" to venueSessionId,
            "instrumentId" to instrumentId,
            "orderId" to orderId,
            "actorId" to actorId,
            "commandId" to commandId
        ).firstOrNull { (_, value) -> value.isBlank() }
        if (missing != null) {
            return EitherBoundaryError.Error(
                BoundaryError(400, "STREAM_ROUTING_METADATA_REQUIRED", "missing required stream routing field: ${missing.first}")
            )
        }
        val invalidToken = listOf(
            "runId" to runId,
            "venueSessionId" to venueSessionId,
            "instrumentId" to instrumentId,
            "commandType" to commandType
        ).firstOrNull { (_, value) -> !isSafeSubjectToken(value) }
        if (invalidToken != null) {
            return EitherBoundaryError.Error(
                BoundaryError(400, "STREAM_ROUTING_METADATA_INVALID", "invalid stream routing field: ${invalidToken.first}")
            )
        }
        val partition = partition(runId, venueSessionId, instrumentId, config.partitionCount)
        val subject = subject(config.subjectPrefix, partition, config.partitionCount, venueSessionId, instrumentId, commandType)
        return EitherBoundaryError.Envelope(
            StreamCommandEnvelope(
                clientId = clientId,
                route = route,
                idempotencyKey = idempotencyKey,
                payloadHash = sha256Hex(body),
                commandId = commandId,
                commandType = commandType,
                runId = runId,
                venueSessionId = venueSessionId,
                instrumentId = instrumentId,
                orderId = orderId,
                clientOrderId = json.string("clientOrderId"),
                actorId = actorId,
                traceId = json.string("traceId"),
                correlationId = json.string("correlationId"),
                causationId = json.string("causationId"),
                botId = json.string("botId"),
                botVersion = json.string("botVersion"),
                subject = subject,
                partition = partition,
                payloadJson = body
            )
        )
    }

    fun partition(runId: String, venueSessionId: String, instrumentId: String, partitionCount: Int): Int {
        val source = "$runId|$venueSessionId|$instrumentId"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val value = ByteBuffer.wrap(digest.copyOfRange(0, 8)).long and Long.MAX_VALUE
        return (value % partitionCount).toInt()
    }

    fun subject(
        subjectPrefix: String,
        partition: Int,
        partitionCount: Int,
        venueSessionId: String,
        instrumentId: String,
        commandType: String
    ): String {
        val width = max(2, (partitionCount - 1).toString().length)
        return listOf(
            subjectPrefix.trim('.'),
            "p${partition.toString().padStart(width, '0')}",
            venueSessionId,
            instrumentId,
            commandType
        ).joinToString(".")
    }

    private fun commandType(route: String): String {
        return when {
            route.endsWith("/orders/submit") -> "SubmitOrder"
            route.endsWith("/orders/cancel") -> "CancelOrder"
            route.endsWith("/orders/modify") -> "ModifyOrder"
            else -> "UnknownCommand"
        }
    }

    private fun isSafeSubjectToken(value: String): Boolean {
        return value.matches(Regex("[A-Za-z0-9_-]+"))
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

sealed class EitherBoundaryError {
    data class Envelope(val envelope: StreamCommandEnvelope) : EitherBoundaryError()
    data class Error(val error: BoundaryError) : EitherBoundaryError()
}

object StreamCommandResponse {
    fun acceptedJson(reference: StreamCommandReference): String {
        return JsonCodec.writeObject(
            "commandId" to reference.commandId,
            "status" to "ACCEPTED",
            "processingMode" to CommandProcessingMode.StreamAck.configValue,
            "stream" to reference.streamName,
            "subject" to reference.subject,
            "partition" to reference.partition,
            "streamSequence" to reference.streamSequence
        )
    }
}
