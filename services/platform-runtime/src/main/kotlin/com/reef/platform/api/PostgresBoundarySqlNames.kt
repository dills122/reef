package com.reef.platform.api

data class PostgresBoundarySqlNames(
    private val schema: String = "boundary"
) {
    val schemaName = schemaNameOrDefault(schema, "boundary")

    val idempotencyRecords = qualify("api_idempotency_records")
    val commandCaptures = qualify("api_command_captures")
    val commandCapturesStatusUpdatedIndex = "idx_api_command_captures_status_updated"

    private fun qualify(name: String): String {
        return "$schemaName.$name"
    }

    private fun schemaNameOrDefault(schema: String, defaultSchema: String): String {
        val candidate = schema.trim().ifBlank { defaultSchema }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
