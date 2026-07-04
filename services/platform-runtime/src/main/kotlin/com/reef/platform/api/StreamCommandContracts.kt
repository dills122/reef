package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max

data class StreamCommandConfig(
    val streamName: String = RuntimeEnv.string("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS"),
    val subjectPrefix: String = RuntimeEnv.string("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1"),
    val partitionCount: Int = RuntimeEnv.int("STREAM_ACK_PARTITION_COUNT", 16, min = 1)
)

enum class StreamCommandLogProvider(val configValue: String) {
    JetStream("jetstream"),
    Redpanda("redpanda");

    companion object {
        fun fromEnv(): StreamCommandLogProvider {
            return when (val raw = RuntimeEnv.string("STREAM_ACK_LOG_PROVIDER", JetStream.configValue).trim().lowercase()) {
                JetStream.configValue, "nats", "nats-jetstream" -> JetStream
                Redpanda.configValue, "kafka" -> Redpanda
                else -> throw IllegalArgumentException(
                    "Unsupported STREAM_ACK_LOG_PROVIDER '$raw'; expected jetstream or redpanda"
                )
            }
        }
    }
}

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
