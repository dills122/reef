package com.reef.platform.infrastructure.persistence

data class PostgresRuntimeSqlNames(
    private val runtimeSchema: String = "runtime",
    private val authSchema: String = "auth",
    private val adminSchema: String = "admin"
) {
    val runtimeSchemaName = schemaOrDefault(runtimeSchema, "runtime")
    val authSchemaName = schemaOrDefault(authSchema, "auth")
    val adminSchemaName = schemaOrDefault(adminSchema, "admin")

    val referenceInstruments = qualify(runtimeSchemaName, "reference_instruments")
    val referenceParticipants = qualify(runtimeSchemaName, "reference_participants")
    val referenceAccounts = qualify(runtimeSchemaName, "reference_accounts")
    val referenceScenarioRuns = qualify(runtimeSchemaName, "reference_scenario_runs")
    val referenceVenueSessions = qualify(runtimeSchemaName, "reference_venue_sessions")
    val orders = qualify(runtimeSchemaName, "orders")
    val executions = qualify(runtimeSchemaName, "executions")
    val trades = qualify(runtimeSchemaName, "trades")
    val tradesArchive = qualify(runtimeSchemaName, "trades_archive")
    val tradesArchiveDefault = qualify(runtimeSchemaName, "trades_archive_default")
    val runtimeEvents = qualify(runtimeSchemaName, "runtime_events")
    val runtimeEventsArchive = qualify(runtimeSchemaName, "runtime_events_archive")
    val runtimeEventsArchiveDefault = qualify(runtimeSchemaName, "runtime_events_archive_default")
    val runtimeTraceSequences = qualify(runtimeSchemaName, "runtime_trace_sequences")
    val submitResults = qualify(runtimeSchemaName, "submit_results")
    val orderLifecycleState = qualify(runtimeSchemaName, "order_lifecycle_state")
    val orderLifecycleDirty = qualify(runtimeSchemaName, "order_lifecycle_dirty")
    val marketDataSnapshotDirty = qualify(runtimeSchemaName, "market_data_snapshot_dirty")
    val marketDataSnapshots = qualify(runtimeSchemaName, "market_data_snapshots")
    val canonicalCommandResults = qualify(runtimeSchemaName, "canonical_command_results")
    val canonicalVenueEvents = qualify(runtimeSchemaName, "canonical_venue_events")
    val canonicalVenueEventBatches = qualify(runtimeSchemaName, "canonical_venue_event_batches")
    val canonicalVenueEventBatchesArchive = qualify(runtimeSchemaName, "canonical_venue_event_batches_archive")
    val canonicalVenueEventBatchesArchiveDefault = qualify(runtimeSchemaName, "canonical_venue_event_batches_archive_default")
    val canonicalCommandOutcomes = qualify(runtimeSchemaName, "canonical_command_outcomes")
    val canonicalCommandOutcomesArchive = qualify(runtimeSchemaName, "canonical_command_outcomes_archive")
    val canonicalCommandOutcomesArchiveDefault = qualify(runtimeSchemaName, "canonical_command_outcomes_archive_default")
    val projectionWatermarks = qualify(runtimeSchemaName, "projection_watermarks")
    val validateReferenceDataFunction = qualify(runtimeSchemaName, "runtime_validate_reference_data")
    val persistSubmitOutcomeFunction = qualify(runtimeSchemaName, "runtime_persist_submit_outcome")
    val persistSubmitOutcomesFunction = qualify(runtimeSchemaName, "runtime_persist_submit_outcomes")
    val persistSubmitOutcomeStatusStageFunction = qualify(runtimeSchemaName, "runtime_persist_submit_outcome_status_stage")
    val persistSubmitOutcomeTimelineStageFunction = qualify(runtimeSchemaName, "runtime_persist_submit_outcome_timeline_stage")
    val appendCanonicalSubmitOutcomesFunction = qualify(runtimeSchemaName, "runtime_append_canonical_submit_outcomes")
    val projectCanonicalSubmitOutcomesFunction = qualify(runtimeSchemaName, "runtime_project_canonical_submit_outcomes")
    val projectCanonicalCommandOutcomesFunction = qualify(runtimeSchemaName, "runtime_project_canonical_command_outcomes")
    val materializeVenueEventBatchFunction = qualify(runtimeSchemaName, "runtime_materialize_venue_event_batch")
    val projectOrderLifecycleStateFunction = qualify(runtimeSchemaName, "runtime_project_order_lifecycle_state")
    val projectMarketDataSnapshotsFunction = qualify(runtimeSchemaName, "runtime_project_market_data_snapshots")

    val authRoles = qualify(authSchemaName, "auth_roles")
    val authActorRoles = qualify(authSchemaName, "auth_actor_roles")
    val adminPostTradeProfiles = qualify(adminSchemaName, "post_trade_profiles")

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
