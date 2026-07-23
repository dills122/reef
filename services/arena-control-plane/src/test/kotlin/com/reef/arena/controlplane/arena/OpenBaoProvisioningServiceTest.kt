package com.reef.arena.controlplane.arena

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OpenBaoProvisioningServiceTest {
    @Test
    fun jwtLoginRejectsMalformedJsonResponse() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/auth/jwt/login") { exchange ->
            val body = """{"auth":"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val service = OpenBaoProvisioningService(
                OpenBaoProvisioningConfig("http://127.0.0.1:${server.address.port}")
            )

            val error = assertFailsWith<OpenBaoClientException> {
                service.provisionBotSecretSlice(
                    githubOidcToken = githubOidcToken(actor = "submitter-1"),
                    submitterIdentity = "submitter-1",
                    botId = "bot-1",
                    secretData = mapOf("key" to "value")
                )
            }

            assertContains(error.message ?: "", "OpenBao jwt login response was invalid JSON")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun jwtLoginFailureDoesNotIncludeOpenBaoResponseBody() {
        val upstreamSecret = "vault-error-internal-token"
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/auth/jwt/login") { exchange ->
            val body = """{"errors":["$upstreamSecret"]}"""
            exchange.sendResponseHeaders(403, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val service = OpenBaoProvisioningService(
                OpenBaoProvisioningConfig("http://127.0.0.1:${server.address.port}")
            )

            val error = assertFailsWith<OpenBaoClientException> {
                service.provisionBotSecretSlice(
                    githubOidcToken = githubOidcToken(actor = "submitter-1"),
                    submitterIdentity = "submitter-1",
                    botId = "bot-1",
                    secretData = emptyMap()
                )
            }

            assertContains(error.message ?: "", "OpenBao request failed with status 403")
            assertFalse(error.message.orEmpty().contains(upstreamSecret))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsSubmitterIdentityThatDoesNotMatchOidcActor() {
        val service = OpenBaoProvisioningService(OpenBaoProvisioningConfig("http://127.0.0.1:1"))

        val error = assertFailsWith<IllegalArgumentException> {
            service.provisionBotSecretSlice(
                githubOidcToken = githubOidcToken(actor = "octocat"),
                submitterIdentity = "attacker",
                botId = "bot-1",
                secretData = mapOf("key" to "value")
            )
        }

        assertContains(error.message ?: "", "submitterIdentity must match GitHub OIDC actor")
    }

    @Test
    fun allowsOnlyExactApprovedForkAdmissionDelegation() {
        val sha = "a".repeat(40)
        val context = approvedForkContext(sha)

        val claims = OpenBaoProvisioningService.requireSubmitterIdentity(
            githubOidcToken(actor = "reviewer", repository = "dills122/reef"),
            "octo",
            context
        )

        assertEquals("reviewer", claims.actor)
        assertFailsWith<IllegalArgumentException> {
            OpenBaoProvisioningService.requireSubmitterIdentity(
                githubOidcToken(actor = "reviewer", repository = "dills122/reef"),
                "octo",
                context.copy(headSha = "b".repeat(40))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenBaoProvisioningService.requireSubmitterIdentity(
                githubOidcToken(actor = "reviewer", repository = "other/reef"),
                "octo",
                context
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenBaoProvisioningService.requireSubmitterIdentity(
                githubOidcToken(actor = "reviewer", repository = "dills122/reef"),
                "attacker",
                context
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenBaoProvisioningService.requireSubmitterIdentity(
                githubOidcToken(actor = "reviewer", repository = "dills122/reef"),
                "octo",
                context.copy(botId = "other-bot")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenBaoProvisioningService.requireSubmitterIdentity(
                githubOidcToken(actor = "reviewer", repository = "dills122/reef"),
                "octo",
                context.copy(
                    admission = context.admission.copy(
                        state = ArenaSubmissionAdmissionState.PendingInviteReview
                    )
                )
            )
        }
    }

    @Test
    fun rejectsSubmitterIdentityWithPathTraversal() {
        val service = OpenBaoProvisioningService(OpenBaoProvisioningConfig("http://127.0.0.1:1"))

        val error = assertFailsWith<IllegalArgumentException> {
            service.provisionBotSecretSlice(
                githubOidcToken = "oidc",
                submitterIdentity = "../../secret/metadata/root",
                botId = "bot-1",
                secretData = mapOf("key" to "value")
            )
        }

        assertContains(error.message ?: "", "invalid OpenBao secret path segment")
    }

    @Test
    fun rejectsBotIdWithSlash() {
        val service = OpenBaoProvisioningService(OpenBaoProvisioningConfig("http://127.0.0.1:1"))

        assertFailsWith<IllegalArgumentException> {
            service.revokeBotSecretSlice(
                githubOidcToken = "oidc",
                submitterIdentity = "submitter-1",
                botId = "bot/../other"
            )
        }
    }

    private fun githubOidcToken(
        actor: String,
        repository: String = "reef/reef",
        audience: String = "reef-bot-submission-ci"
    ): String {
        fun encode(raw: String): String {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        }
        val header = encode("""{"alg":"none","typ":"JWT"}""")
        val payload = encode(
            """
                {
                  "actor": "$actor",
                  "repository": "$repository",
                  "aud": "$audience"
                }
            """.trimIndent()
        )
        return "$header.$payload.signature"
    }

    private fun approvedForkContext(sha: String): ApprovedForkOpenBaoProvisioningContext {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        return ApprovedForkOpenBaoProvisioningContext(
            repository = "dills122/reef",
            pullRequestNumber = 42,
            headSha = sha,
            botId = "bot-1",
            admission = ArenaSubmissionAdmission(
                repository = "dills122/reef",
                pullRequestNumber = 42,
                botId = "bot-1",
                headRepository = "octo/reef",
                headOwnerLogin = "octo",
                githubUserId = 123,
                githubLogin = "octo",
                headSha = sha,
                state = ArenaSubmissionAdmissionState.InviteApproved,
                invitationActor = "user-gh-999",
                invitationReason = "invite verified",
                invitedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
