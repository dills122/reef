package com.reef.platform.application.admin

data class PostgresAdminAuthSqlNames(
    private val schema: String = "admin"
) {
    val schemaName = safeIdentifier(schema.trim().ifBlank { "admin" })
    val oauthStates = "$schemaName.oauth_states"
    val sessions = "$schemaName.sessions"
    val serviceTokens = "$schemaName.service_tokens"

    private fun safeIdentifier(candidate: String): String {
        require(candidate.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Unsafe Postgres schema name: $candidate"
        }
        return candidate
    }
}
