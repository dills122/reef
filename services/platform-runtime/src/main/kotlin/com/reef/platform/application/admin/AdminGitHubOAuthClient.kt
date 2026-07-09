package com.reef.platform.application.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

interface AdminGitHubOAuthClient {
    fun authorizationUrl(stateToken: String): String
    fun exchangeCode(code: String): GitHubUserIdentity
}

class ConfiguredAdminGitHubOAuthClient(
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
    private val authorizationUrl: String = "https://github.com/login/oauth/authorize",
    private val tokenUrl: String = "https://github.com/login/oauth/access_token",
    private val userUrl: String = "https://api.github.com/user",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) : AdminGitHubOAuthClient {
    private val mapper = JsonMapper.builder().build()

    init {
        require(clientId.isNotBlank()) { "GitHub OAuth clientId is required" }
        require(clientSecret.isNotBlank()) { "GitHub OAuth clientSecret is required" }
        require(redirectUri.startsWith("https://") || redirectUri.startsWith("http://localhost")) {
            "GitHub OAuth redirectUri must be https or localhost"
        }
    }

    override fun authorizationUrl(stateToken: String): String {
        val state = AdminAuthTokenCodec.requireToken(stateToken)
        return authorizationUrl + "?" + form(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to "read:user",
            "state" to state
        )
    }

    override fun exchangeCode(code: String): GitHubUserIdentity {
        val normalizedCode = code.trim()
        require(normalizedCode.isNotBlank()) { "GitHub OAuth code is required" }
        val tokenBody = form(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "code" to normalizedCode,
            "redirect_uri" to redirectUri
        )
        val tokenResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(tokenResponse.statusCode() in 200..299) { "GitHub OAuth token exchange failed" }
        val accessToken = mapper.readTree(tokenResponse.body()).text("access_token")
        require(accessToken.isNotBlank()) { "GitHub OAuth token exchange returned no access token" }

        val userResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(userUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(userResponse.statusCode() in 200..299) { "GitHub user lookup failed" }
        val user = mapper.readTree(userResponse.body())
        return GitHubUserIdentity(
            githubUserId = user.long("id"),
            githubLogin = user.text("login"),
            displayName = user.text("name")
        )
    }

    private fun form(vararg fields: Pair<String, String>): String {
        return fields.joinToString("&") { (key, value) ->
            "${url(key)}=${url(value)}"
        }
    }

    private fun url(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun JsonNode.text(key: String): String {
        val value = get(key) ?: return ""
        if (value.isNull) return ""
        return value.asText("")
    }

    private fun JsonNode.long(key: String): Long {
        val value = get(key) ?: throw IllegalArgumentException("GitHub user response missing $key")
        return value.asLong(0L)
    }
}
