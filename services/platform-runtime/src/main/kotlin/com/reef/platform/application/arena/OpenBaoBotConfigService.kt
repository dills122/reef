package com.reef.platform.application.arena

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.reef.platform.api.JsonCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface BotConfigSecretService {
    fun status(ownerIdentity: String, botId: String): OpenBaoBotConfigSnapshot
    fun replaceConfig(ownerIdentity: String, botId: String, rawConfigJson: String, actorId: String): OpenBaoBotConfigWriteResult
    fun deleteConfig(ownerIdentity: String, botId: String)
}

data class OpenBaoBotConfigServiceConfig(
    val baoAddr: String,
    val roleId: String,
    val secretId: String,
    val maxConfigBytes: Int = 65_536
)

data class OpenBaoBotConfigSnapshot(
    val ownerIdentity: String,
    val botId: String,
    val secretPath: String,
    val hasConfig: Boolean,
    val config: JsonNode?,
    val keys: List<String>,
    val updatedAt: String,
    val updatedBy: String,
    val version: Long?
)

data class OpenBaoBotConfigWriteResult(
    val ownerIdentity: String,
    val botId: String,
    val secretPath: String,
    val config: JsonNode,
    val keys: List<String>,
    val updatedAt: String,
    val configSha256: String
)

class OpenBaoBotConfigService(
    private val config: OpenBaoBotConfigServiceConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val now: () -> Instant = { Instant.now() }
) : BotConfigSecretService {
    override fun status(ownerIdentity: String, botId: String): OpenBaoBotConfigSnapshot {
        val owner = secretPathSegment(ownerIdentity)
        val bot = secretPathSegment(botId)
        val token = login()
        val response = request(
            HttpRequest.newBuilder()
                .uri(URI.create("${config.baoAddr}/v1/${dataPath(owner, bot)}"))
                .header("X-Vault-Token", token)
                .GET()
                .build(),
            allowNotFound = true
        )
        if (response.statusCode() == 404) {
            return OpenBaoBotConfigSnapshot(
                ownerIdentity = owner,
                botId = bot,
                secretPath = secretPath(owner, bot),
                hasConfig = false,
                config = null,
                keys = emptyList(),
                updatedAt = "",
                updatedBy = "",
                version = null
            )
        }
        val root = parseResponseObject(response.body())
        val data = root.path("data").path("data")
        val parsedConfig = parseStoredConfig(data)
        return OpenBaoBotConfigSnapshot(
            ownerIdentity = owner,
            botId = bot,
            secretPath = secretPath(owner, bot),
            hasConfig = parsedConfig != null,
            config = parsedConfig,
            keys = parsedConfig?.fieldNames()?.asSequence()?.toList().orEmpty().sorted(),
            updatedAt = data.path("updated_at").asText(""),
            updatedBy = data.path("updated_by").asText(""),
            version = root.path("data").path("metadata").path("version").takeIf { it.isNumber }?.asLong()
        )
    }

    override fun replaceConfig(ownerIdentity: String, botId: String, rawConfigJson: String, actorId: String): OpenBaoBotConfigWriteResult {
        val owner = secretPathSegment(ownerIdentity)
        val bot = secretPathSegment(botId)
        require(rawConfigJson.toByteArray(Charsets.UTF_8).size <= config.maxConfigBytes) {
            "bot config JSON exceeds ${config.maxConfigBytes} bytes"
        }
        val configNode = parseConfigObject(rawConfigJson)
        validateRootKeys(configNode)
        val normalizedConfigJson = mapper.writeValueAsString(configNode)
        val updatedAt = now().toString()
        val payload = mapOf(
            "data" to mapOf(
                "config_schema" to "reef.bot-config.v1",
                "config_json" to normalizedConfigJson,
                "config_sha256" to sha256(normalizedConfigJson),
                "updated_at" to updatedAt,
                "updated_by" to actorId
            )
        )
        val token = login()
        request(
            HttpRequest.newBuilder()
                .uri(URI.create("${config.baoAddr}/v1/${dataPath(owner, bot)}"))
                .header("content-type", "application/json")
                .header("X-Vault-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.writeObject("data" to payload["data"])))
                .build()
        )
        return OpenBaoBotConfigWriteResult(
            ownerIdentity = owner,
            botId = bot,
            secretPath = secretPath(owner, bot),
            config = configNode,
            keys = configNode.fieldNames().asSequence().toList().sorted(),
            updatedAt = updatedAt,
            configSha256 = sha256(normalizedConfigJson)
        )
    }

    override fun deleteConfig(ownerIdentity: String, botId: String) {
        val owner = secretPathSegment(ownerIdentity)
        val bot = secretPathSegment(botId)
        val token = login()
        request(
            HttpRequest.newBuilder()
                .uri(URI.create("${config.baoAddr}/v1/${metadataPath(owner, bot)}"))
                .header("X-Vault-Token", token)
                .DELETE()
                .build(),
            allowNotFound = true
        )
    }

    private fun login(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baoAddr}/v1/auth/approle/login"))
            .header("content-type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JsonCodec.writeObject("role_id" to config.roleId, "secret_id" to config.secretId)
                )
            )
            .build()
        val response = request(request)
        val token = parseResponseObject(response.body()).path("auth").path("client_token").asText("")
        if (token.isBlank()) {
            throw OpenBaoClientException("OpenBao AppRole login response missing auth.client_token")
        }
        return token
    }

    private fun request(request: HttpRequest, allowNotFound: Boolean = false): HttpResponse<String> {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (allowNotFound && response.statusCode() == 404) return response
        if (response.statusCode() !in 200..299) {
            throw OpenBaoClientException("OpenBao request to ${request.uri()} failed (${response.statusCode()}): ${response.body()}")
        }
        return response
    }

    private fun parseResponseObject(body: String): JsonNode {
        return try {
            val node = mapper.readTree(body)
            require(node != null && node.isObject) { "json payload must be an object" }
            node
        } catch (ex: Exception) {
            throw OpenBaoClientException("OpenBao response was invalid JSON: ${ex.message ?: "invalid json payload"}")
        }
    }

    private fun parseStoredConfig(data: JsonNode): JsonNode? {
        val configJson = data.path("config_json").asText("")
        if (configJson.isNotBlank()) {
            return try {
                mapper.readTree(configJson)?.takeIf { it.isObject }
            } catch (_: Exception) {
                null
            }
        }
        return data.takeIf { it.isObject && it.fieldNames().hasNext() }
    }

    private fun dataPath(ownerIdentity: String, botId: String) =
        "secret/data/bots/${secretPathSegment(ownerIdentity)}/${secretPathSegment(botId)}"

    private fun metadataPath(ownerIdentity: String, botId: String) =
        "secret/metadata/bots/${secretPathSegment(ownerIdentity)}/${secretPathSegment(botId)}"
}

class LocalDevBotConfigService(
    private val maxConfigBytes: Int = 65_536,
    private val now: () -> Instant = { Instant.now() }
) : BotConfigSecretService {
    private data class StoredConfig(
        val config: JsonNode,
        val keys: List<String>,
        val updatedAt: String,
        val updatedBy: String,
        val version: Long
    )

    private val configs = ConcurrentHashMap<String, StoredConfig>()

    override fun status(ownerIdentity: String, botId: String): OpenBaoBotConfigSnapshot {
        val owner = secretPathSegment(ownerIdentity)
        val bot = secretPathSegment(botId)
        val stored = configs[key(owner, bot)]
        return OpenBaoBotConfigSnapshot(
            ownerIdentity = owner,
            botId = bot,
            secretPath = secretPath(owner, bot),
            hasConfig = stored != null,
            config = stored?.config,
            keys = stored?.keys.orEmpty(),
            updatedAt = stored?.updatedAt.orEmpty(),
            updatedBy = stored?.updatedBy.orEmpty(),
            version = stored?.version
        )
    }

    override fun replaceConfig(ownerIdentity: String, botId: String, rawConfigJson: String, actorId: String): OpenBaoBotConfigWriteResult {
        val owner = secretPathSegment(ownerIdentity)
        val bot = secretPathSegment(botId)
        require(rawConfigJson.toByteArray(Charsets.UTF_8).size <= maxConfigBytes) {
            "bot config JSON exceeds $maxConfigBytes bytes"
        }
        val configNode = parseConfigObject(rawConfigJson)
        validateRootKeys(configNode)
        val normalizedConfigJson = mapper.writeValueAsString(configNode)
        val updatedAt = now().toString()
        val keys = configNode.fieldNames().asSequence().toList().sorted()
        configs.compute(key(owner, bot)) { _, current ->
            StoredConfig(
                config = configNode,
                keys = keys,
                updatedAt = updatedAt,
                updatedBy = actorId,
                version = (current?.version ?: 0L) + 1L
            )
        }
        return OpenBaoBotConfigWriteResult(
            ownerIdentity = owner,
            botId = bot,
            secretPath = secretPath(owner, bot),
            config = configNode,
            keys = keys,
            updatedAt = updatedAt,
            configSha256 = sha256(normalizedConfigJson)
        )
    }

    override fun deleteConfig(ownerIdentity: String, botId: String) {
        configs.remove(key(secretPathSegment(ownerIdentity), secretPathSegment(botId)))
    }

    private fun key(ownerIdentity: String, botId: String) = "$ownerIdentity/$botId"

    companion object {
        val shared = LocalDevBotConfigService()
    }
}

private val mapper: JsonMapper = JsonMapper.builder().build()
private val secretPathSegmentPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
private val rootKeyPattern = Regex("[A-Za-z0-9_.-]{1,64}")

private fun parseConfigObject(raw: String): JsonNode {
    val node = try {
        mapper.readTree(raw)
    } catch (ex: Exception) {
        throw IllegalArgumentException("bot config must be valid JSON", ex)
    }
    require(node != null && node.isObject) { "bot config must be a JSON object" }
    return node
}

private fun validateRootKeys(configNode: JsonNode) {
    val keys = configNode.fieldNames().asSequence().toList()
    require(keys.size <= 128) { "bot config may contain at most 128 top-level keys" }
    keys.forEach { key ->
        require(rootKeyPattern.matches(key)) {
            "bot config key must match [A-Za-z0-9_.-]{1,64}: $key"
        }
    }
}

private fun secretPath(ownerIdentity: String, botId: String) =
    "secret/bots/${secretPathSegment(ownerIdentity)}/${secretPathSegment(botId)}"

private fun secretPathSegment(value: String): String {
    require(secretPathSegmentPattern.matches(value)) {
        "invalid OpenBao secret path segment: $value"
    }
    return value
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
