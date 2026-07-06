package com.reef.platform.application.arena

import com.reef.platform.api.JsonCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Server-side client for the bot-submission CI provisioning flow (D-046,
 * BOT_ARENA_PLAN.md "Resolved Slice: Bot Submission Workflow And OpenBao
 * Provisioning"). Exchanges a short-lived GitHub Actions OIDC token for an
 * OpenBao token via the auth/jwt backend (separate from the AppRole backend
 * used by runtime reads - see configure-openbao.sh), then writes/deletes a
 * per-submitter/per-bot KV v2 secret slice. Never receives an OpenBao root
 * or AppRole credential; the jwt role's policy is scoped to create, update,
 * and delete under the secret/data/bots/ prefix only.
 */
class OpenBaoClientException(message: String) : RuntimeException(message)

data class OpenBaoProvisioningConfig(
    val baoAddr: String,
    val jwtRole: String = "reef-bot-submission-ci"
)

class OpenBaoProvisioningService(
    private val config: OpenBaoProvisioningConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) {
    fun provisionBotSecretSlice(
        githubOidcToken: String,
        submitterIdentity: String,
        botId: String,
        secretData: Map<String, String>
    ) {
        val baoToken = exchangeJwtForToken(githubOidcToken)
        writeSecret(baoToken, submitterIdentity, botId, secretData)
    }

    fun revokeBotSecretSlice(githubOidcToken: String, submitterIdentity: String, botId: String) {
        val baoToken = exchangeJwtForToken(githubOidcToken)
        deleteSecret(baoToken, submitterIdentity, botId)
    }

    private fun exchangeJwtForToken(jwt: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baoAddr}/v1/auth/jwt/login"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.writeObject("role" to config.jwtRole, "jwt" to jwt)))
            .build()
        val response = send(request)
        val json = try {
            JsonCodec.parseObject(response.body())
        } catch (ex: IllegalArgumentException) {
            throw OpenBaoClientException("OpenBao jwt login response was invalid JSON: ${ex.message ?: "invalid json payload"}")
        }
        val clientToken = json.obj("auth").string("client_token")
        if (clientToken.isBlank()) {
            throw OpenBaoClientException("OpenBao jwt login response missing auth.client_token")
        }
        return clientToken
    }

    private fun writeSecret(baoToken: String, submitterIdentity: String, botId: String, data: Map<String, String>) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baoAddr}/v1/${dataPath(submitterIdentity, botId)}"))
            .header("content-type", "application/json")
            .header("X-Vault-Token", baoToken)
            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.writeObject("data" to data)))
            .build()
        send(request)
    }

    private fun deleteSecret(baoToken: String, submitterIdentity: String, botId: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.baoAddr}/v1/${metadataPath(submitterIdentity, botId)}"))
            .header("X-Vault-Token", baoToken)
            .DELETE()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw OpenBaoClientException("OpenBao secret delete failed (${response.statusCode()}): ${response.body()}")
        }
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw OpenBaoClientException("OpenBao request to ${request.uri()} failed (${response.statusCode()}): ${response.body()}")
        }
        return response
    }

    private fun dataPath(submitterIdentity: String, botId: String) = "secret/data/bots/$submitterIdentity/$botId"

    private fun metadataPath(submitterIdentity: String, botId: String) =
        "secret/metadata/bots/$submitterIdentity/$botId"
}
