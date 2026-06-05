package com.reef.platform.infrastructure.persistence

data class PostgresRuntimeSqlNames(
    private val runtimeSchema: String = "runtime",
    private val authSchema: String = "auth"
) {
    val runtimeSchemaName = schemaOrDefault(runtimeSchema, "runtime")
    val authSchemaName = schemaOrDefault(authSchema, "auth")

    val referenceInstruments = qualify(runtimeSchemaName, "reference_instruments")
    val referenceParticipants = qualify(runtimeSchemaName, "reference_participants")
    val referenceAccounts = qualify(runtimeSchemaName, "reference_accounts")
    val orders = qualify(runtimeSchemaName, "orders")
    val executions = qualify(runtimeSchemaName, "executions")
    val trades = qualify(runtimeSchemaName, "trades")
    val runtimeEvents = qualify(runtimeSchemaName, "runtime_events")
    val runtimeTraceSequences = qualify(runtimeSchemaName, "runtime_trace_sequences")
    val submitResults = qualify(runtimeSchemaName, "submit_results")
    val validateReferenceDataFunction = qualify(runtimeSchemaName, "runtime_validate_reference_data")
    val persistSubmitOutcomeFunction = qualify(runtimeSchemaName, "runtime_persist_submit_outcome")

    val authRoles = qualify(authSchemaName, "auth_roles")
    val authActorRoles = qualify(authSchemaName, "auth_actor_roles")

    private fun schemaOrDefault(schema: String, defaultSchema: String): String {
        val candidate = schema.trim().ifBlank { defaultSchema }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private fun qualify(schema: String, name: String): String {
        return "$schema.$name"
    }

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
