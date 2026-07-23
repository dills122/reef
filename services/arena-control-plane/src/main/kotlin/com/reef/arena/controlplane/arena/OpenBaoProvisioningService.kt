package com.reef.arena.controlplane.arena

import com.reef.platform.api.JsonCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

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

data class GitHubActionsOidcClaims(
    val actor: String,
    val repository: String,
    val audience: String
)

data class ApprovedForkOpenBaoProvisioningContext(
    val repository: String,
    val pullRequestNumber: Long,
    val headSha: String,
    val botId: String,
    val admission: ArenaSubmissionAdmission
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
        secretData: Map<String, String>,
        approvedForkContext: ApprovedForkOpenBaoProvisioningContext? = null
    ) {
        secretPathSegment(submitterIdentity)
        secretPathSegment(botId)
        requireSubmitterIdentity(githubOidcToken, submitterIdentity, approvedForkContext)
        val baoToken = exchangeJwtForToken(githubOidcToken)
        writeSecret(baoToken, submitterIdentity, botId, secretData)
    }

    fun revokeBotSecretSlice(
        githubOidcToken: String,
        submitterIdentity: String,
        botId: String,
        approvedForkContext: ApprovedForkOpenBaoProvisioningContext? = null
    ) {
        secretPathSegment(submitterIdentity)
        secretPathSegment(botId)
        requireSubmitterIdentity(githubOidcToken, submitterIdentity, approvedForkContext)
        val baoToken = exchangeJwtForToken(githubOidcToken)
        deleteSecret(baoToken, submitterIdentity, botId)
    }

    companion object {
        fun requireSubmitterIdentity(
            githubOidcToken: String,
            submitterIdentity: String,
            approvedForkContext: ApprovedForkOpenBaoProvisioningContext? = null
        ): GitHubActionsOidcClaims {
            val claims = githubActionsOidcClaims(githubOidcToken)
            require(
                claims.actor.equals(submitterIdentity, ignoreCase = true) ||
                    approvedForkContext?.matches(claims, submitterIdentity) == true
            ) {
                "submitterIdentity must match GitHub OIDC actor or an exact approved fork admission"
            }
            return claims
        }

        private fun ApprovedForkOpenBaoProvisioningContext.matches(
            claims: GitHubActionsOidcClaims,
            submitterIdentity: String
        ): Boolean = admission.state == ArenaSubmissionAdmissionState.InviteApproved &&
            !admission.headRepository.equals(admission.repository, ignoreCase = true) &&
            admission.invitationActor?.isNotBlank() == true &&
            admission.invitedAt != null &&
            claims.repository.equals(repository, ignoreCase = true) &&
            admission.repository.equals(repository, ignoreCase = true) &&
            admission.pullRequestNumber == pullRequestNumber &&
            admission.headSha.equals(headSha, ignoreCase = true) &&
            admission.botId == botId &&
            admission.githubLogin.equals(submitterIdentity, ignoreCase = true) &&
            admission.headOwnerLogin.equals(submitterIdentity, ignoreCase = true) &&
            admission.headRepository.substringBefore("/").equals(admission.headOwnerLogin, ignoreCase = true)

        fun githubActionsOidcClaims(jwt: String): GitHubActionsOidcClaims {
            val segments = jwt.split(".")
            require(segments.size >= 2) { "invalid GitHub OIDC token" }
            val payload = try {
                String(Base64.getUrlDecoder().decode(segments[1]))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("invalid GitHub OIDC token payload")
            }
            val json = try {
                JsonCodec.parseObject(payload)
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("invalid GitHub OIDC token payload: ${ex.message ?: "invalid json payload"}")
            }
            val actor = json.string("actor")
            val repository = json.string("repository")
            val audience = json.string("aud")
            require(actor.isNotBlank()) { "GitHub OIDC token missing actor claim" }
            require(repository.isNotBlank()) { "GitHub OIDC token missing repository claim" }
            require(audience.isNotBlank()) { "GitHub OIDC token missing aud claim" }
            return GitHubActionsOidcClaims(actor = actor, repository = repository, audience = audience)
        }

        // Rejects "/", "..", and anything else that could escape the intended
        // secret/data/bots/<submitter>/<bot> prefix scoped by the jwt role's policy.
        private val secretPathSegmentPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
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
        } catch (_: IllegalArgumentException) {
            throw OpenBaoClientException("OpenBao jwt login response was invalid JSON")
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
        val response = execute(request)
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw OpenBaoClientException("OpenBao secret delete failed with status ${response.statusCode()}")
        }
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        val response = execute(request)
        if (response.statusCode() !in 200..299) {
            throw OpenBaoClientException("OpenBao request failed with status ${response.statusCode()}")
        }
        return response
    }

    private fun execute(request: HttpRequest): HttpResponse<String> = try {
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (_: Exception) {
        throw OpenBaoClientException("OpenBao request failed")
    }

    private fun dataPath(submitterIdentity: String, botId: String) =
        "secret/data/bots/${secretPathSegment(submitterIdentity)}/${secretPathSegment(botId)}"

    private fun metadataPath(submitterIdentity: String, botId: String) =
        "secret/metadata/bots/${secretPathSegment(submitterIdentity)}/${secretPathSegment(botId)}"

    private fun secretPathSegment(value: String): String {
        require(secretPathSegmentPattern.matches(value)) {
            "invalid OpenBao secret path segment: $value"
        }
        return value
    }

}
