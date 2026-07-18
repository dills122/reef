package com.reef.platform.application.admin

data class PostgresAdminIdentitySqlNames(
    private val schema: String = "admin"
) {
    val schemaName = safeIdentifier(schema.trim().ifBlank { "admin" })
    val users = "$schemaName.users"
    val roles = "$schemaName.roles"
    val userRoles = "$schemaName.user_roles"
    val auditEvents = "$schemaName.audit_events"

    private fun safeIdentifier(candidate: String): String {
        require(candidate.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Unsafe Postgres schema name: $candidate"
        }
        return candidate
    }
}
