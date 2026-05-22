package com.reef.platform.api

import com.sun.net.httpserver.Headers

data class BoundaryError(
    val status: Int,
    val code: String,
    val message: String
)

interface AuthHook {
    fun authorize(clientId: String, token: String?): BoundaryError?
}

interface RateLimitHook {
    fun allow(clientId: String, route: String): BoundaryError?
}

interface IdempotencyPolicy {
    fun validate(clientId: String, route: String, idempotencyKey: String?): BoundaryError?
}

interface IdempotencyStore {
    fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult?
    fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult)
}

data class IdempotencyResult(
    val status: Int,
    val payload: String
)

class AllowAllAuthHook : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? = null
}

class AllowAllRateLimitHook : RateLimitHook {
    override fun allow(clientId: String, route: String): BoundaryError? = null
}

class RequiredIdempotencyPolicy : IdempotencyPolicy {
    override fun validate(clientId: String, route: String, idempotencyKey: String?): BoundaryError? {
        if (idempotencyKey.isNullOrBlank()) {
            return BoundaryError(
                status = 400,
                code = "IDEMPOTENCY_KEY_REQUIRED",
                message = "idempotency key header is required for mutating requests"
            )
        }
        return null
    }
}

class InMemoryIdempotencyStore : IdempotencyStore {
    private val results = java.util.concurrent.ConcurrentHashMap<String, IdempotencyResult>()

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        return results["$clientId|$route|$idempotencyKey"]
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult) {
        results.putIfAbsent("$clientId|$route|$idempotencyKey", result)
    }
}

class ExternalApiBoundary(
    private val authHook: AuthHook = AllowAllAuthHook(),
    private val rateLimitHook: RateLimitHook = AllowAllRateLimitHook(),
    private val idempotencyPolicy: IdempotencyPolicy = RequiredIdempotencyPolicy()
) {
    fun checkWrite(headers: Headers, route: String): BoundaryError? {
        val clientId = clientId(headers)
            ?: return BoundaryError(401, "CLIENT_ID_REQUIRED", "missing X-Client-Id header")

        val authError = authHook.authorize(clientId, headers.firstValue("Authorization"))
        if (authError != null) return authError

        val rateLimitError = rateLimitHook.allow(clientId, route)
        if (rateLimitError != null) return rateLimitError

        return idempotencyPolicy.validate(clientId, route, idempotencyKey(headers))
    }

    fun toErrorJson(error: BoundaryError, correlationId: String): String {
        return """
            {
              "code":"${JsonFields.escape(error.code)}",
              "message":"${JsonFields.escape(error.message)}",
              "correlationId":"${JsonFields.escape(correlationId)}"
            }
        """.trimIndent()
    }

    fun clientId(headers: Headers): String? = headers.firstValue("X-Client-Id")
    fun idempotencyKey(headers: Headers): String? = headers.firstValue("Idempotency-Key")
}

private fun Headers.firstValue(name: String): String? {
    return this[name]?.firstOrNull()
}
