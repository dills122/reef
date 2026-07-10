package com.reef.platform.application.analytics

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

data class SimulationRunExportCommand(
    val runId: String,
    val scenarioId: String = "",
    val runKind: String = "",
    val source: String = "",
    val gitSha: String = "",
    val profile: String = "",
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val exportedAt: Instant? = null,
    val status: String = "",
    val attemptedCount: Long = 0L,
    val acceptedCount: Long = 0L,
    val completedCount: Long = 0L,
    val materializedCount: Long = 0L,
    val projectedCount: Long = 0L,
    val failedCount: Long = 0L,
    val p50LatencyMs: Double? = null,
    val p95LatencyMs: Double? = null,
    val p99LatencyMs: Double? = null,
    val artifactManifestJson: String = "[]",
    val summaryJson: String = "{}"
)

data class SimulationRunExportRecord(
    val runId: String,
    val scenarioId: String,
    val runKind: String,
    val source: String,
    val gitSha: String,
    val profile: String,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val exportedAt: Instant,
    val status: String,
    val attemptedCount: Long,
    val acceptedCount: Long,
    val completedCount: Long,
    val materializedCount: Long,
    val projectedCount: Long,
    val failedCount: Long,
    val p50LatencyMs: Double?,
    val p95LatencyMs: Double?,
    val p99LatencyMs: Double?,
    val artifactManifestJson: String,
    val summaryJson: String
)

interface SimulationRunExportStore {
    fun upsert(record: SimulationRunExportRecord): SimulationRunExportRecord
    fun find(runId: String): SimulationRunExportRecord?
    fun list(limit: Int = 50): List<SimulationRunExportRecord>
}

class SimulationRunExportService(
    private val store: SimulationRunExportStore,
    private val botPerformanceStore: BotRunPerformanceSummaryStore? = store as? BotRunPerformanceSummaryStore,
    private val now: () -> Instant = { Instant.now() }
) {
    fun ingest(command: SimulationRunExportCommand): SimulationRunExportRecord {
        require(command.runId.isNotBlank()) { "runId is required" }
        val record = SimulationRunExportRecord(
            runId = command.runId,
            scenarioId = command.scenarioId,
            runKind = command.runKind,
            source = command.source,
            gitSha = command.gitSha,
            profile = command.profile,
            startedAt = command.startedAt,
            completedAt = command.completedAt,
            exportedAt = command.exportedAt ?: now(),
            status = command.status,
            attemptedCount = command.attemptedCount.coerceAtLeast(0L),
            acceptedCount = command.acceptedCount.coerceAtLeast(0L),
            completedCount = command.completedCount.coerceAtLeast(0L),
            materializedCount = command.materializedCount.coerceAtLeast(0L),
            projectedCount = command.projectedCount.coerceAtLeast(0L),
            failedCount = command.failedCount.coerceAtLeast(0L),
            p50LatencyMs = command.p50LatencyMs,
            p95LatencyMs = command.p95LatencyMs,
            p99LatencyMs = command.p99LatencyMs,
            artifactManifestJson = command.artifactManifestJson.ifBlank { "[]" },
            summaryJson = command.summaryJson.ifBlank { "{}" }
        )
        val saved = store.upsert(record)
        botPerformanceStore?.upsertAll(BotRunPerformanceProjection.fromExport(saved, now()))
        return saved
    }

    fun find(runId: String): SimulationRunExportRecord? = store.find(runId)

    fun list(limit: Int = 50): List<SimulationRunExportRecord> = store.list(limit.coerceIn(1, 500))

    fun listBotPerformanceSummaries(
        runId: String = "",
        botId: String = "",
        limit: Int = 50
    ): List<BotRunPerformanceSummaryRecord> {
        return botPerformanceStore?.list(runId, botId, limit.coerceIn(1, 500)).orEmpty()
    }
}

class InMemorySimulationRunExportStore : SimulationRunExportStore, BotRunPerformanceSummaryStore {
    private val records = ConcurrentHashMap<String, SimulationRunExportRecord>()
    private val botPerformanceSummaries = ConcurrentHashMap<Pair<String, String>, BotRunPerformanceSummaryRecord>()

    override fun upsert(record: SimulationRunExportRecord): SimulationRunExportRecord {
        records[record.runId] = record
        return record
    }

    override fun find(runId: String): SimulationRunExportRecord? = records[runId]

    override fun list(limit: Int): List<SimulationRunExportRecord> {
        return records.values
            .sortedWith(compareByDescending<SimulationRunExportRecord> { it.completedAt ?: it.exportedAt }.thenBy { it.runId })
            .take(limit.coerceIn(1, 500))
    }

    override fun upsertAll(records: List<BotRunPerformanceSummaryRecord>): Int {
        records.forEach { record -> botPerformanceSummaries[record.runId to record.botId] = record }
        return records.size
    }

    override fun list(runId: String, botId: String, limit: Int): List<BotRunPerformanceSummaryRecord> {
        return botPerformanceSummaries.values
            .asSequence()
            .filter { runId.isBlank() || it.runId == runId }
            .filter { botId.isBlank() || it.botId == botId }
            .sortedWith(
                compareByDescending<BotRunPerformanceSummaryRecord> { it.completedAt ?: it.exportedAt }
                    .thenBy { it.runId }
                    .thenBy { it.botId }
            )
            .take(limit.coerceIn(1, 500))
            .toList()
    }
}

data class PostgresAnalyticsSqlNames(
    private val schema: String = "analytics"
) {
    val schemaName = schemaOrDefault(schema)
    val simulationRunExports = qualify("simulation_run_exports")
    val runBotPerformanceSummaries = qualify("run_bot_performance_summaries")

    private fun schemaOrDefault(schema: String): String {
        val candidate = schema.trim().ifBlank { "analytics" }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private fun qualify(name: String): String = "$schemaName.$name"

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

class PostgresSimulationRunExportStore(
    private val dataSource: DataSource,
    private val names: PostgresAnalyticsSqlNames = PostgresAnalyticsSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : SimulationRunExportStore, BotRunPerformanceSummaryStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.analyticsRunExports(names.simulationRunExports)
                )
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.analyticsRunBotPerformanceSummaries(names.runBotPerformanceSummaries)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.simulationRunExports} (
                      run_id TEXT PRIMARY KEY,
                      scenario_id TEXT NOT NULL DEFAULT '',
                      run_kind TEXT NOT NULL DEFAULT '',
                      source TEXT NOT NULL DEFAULT '',
                      git_sha TEXT NOT NULL DEFAULT '',
                      profile TEXT NOT NULL DEFAULT '',
                      started_at TIMESTAMPTZ,
                      completed_at TIMESTAMPTZ,
                      exported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      status TEXT NOT NULL DEFAULT '',
                      attempted_count BIGINT NOT NULL DEFAULT 0,
                      accepted_count BIGINT NOT NULL DEFAULT 0,
                      completed_count BIGINT NOT NULL DEFAULT 0,
                      materialized_count BIGINT NOT NULL DEFAULT 0,
                      projected_count BIGINT NOT NULL DEFAULT 0,
                      failed_count BIGINT NOT NULL DEFAULT 0,
                      p50_latency_ms DOUBLE PRECISION,
                      p95_latency_ms DOUBLE PRECISION,
                      p99_latency_ms DOUBLE PRECISION,
                      artifact_manifest JSONB NOT NULL DEFAULT '[]'::jsonb,
                      summary JSONB NOT NULL DEFAULT '{}'::jsonb,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_analytics_run_exports_completed ON ${names.simulationRunExports}(completed_at DESC, exported_at DESC)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_analytics_run_exports_scenario ON ${names.simulationRunExports}(scenario_id, completed_at DESC)"
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runBotPerformanceSummaries} (
                      run_id TEXT NOT NULL,
                      bot_id TEXT NOT NULL,
                      scenario_id TEXT NOT NULL DEFAULT '',
                      profile TEXT NOT NULL DEFAULT '',
                      source TEXT NOT NULL DEFAULT '',
                      completed_at TIMESTAMPTZ,
                      exported_at TIMESTAMPTZ NOT NULL,
                      projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      final_equity DOUBLE PRECISION,
                      realized_pnl DOUBLE PRECISION,
                      max_drawdown DOUBLE PRECISION,
                      fail_count BIGINT NOT NULL DEFAULT 0,
                      command_count BIGINT NOT NULL DEFAULT 0,
                      settlement_score_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
                      source_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      PRIMARY KEY (run_id, bot_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_analytics_bot_perf_recent ON ${names.runBotPerformanceSummaries}(completed_at DESC, exported_at DESC, bot_id)"
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_analytics_bot_perf_bot_recent ON ${names.runBotPerformanceSummaries}(bot_id, completed_at DESC, exported_at DESC)"
                )
            }
        }
    }

    override fun upsert(record: SimulationRunExportRecord): SimulationRunExportRecord {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.simulationRunExports}(
                  run_id, scenario_id, run_kind, source, git_sha, profile, started_at, completed_at, exported_at, status,
                  attempted_count, accepted_count, completed_count, materialized_count, projected_count, failed_count,
                  p50_latency_ms, p95_latency_ms, p99_latency_ms, artifact_manifest, summary, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, now())
                ON CONFLICT (run_id) DO UPDATE SET
                  scenario_id = EXCLUDED.scenario_id,
                  run_kind = EXCLUDED.run_kind,
                  source = EXCLUDED.source,
                  git_sha = EXCLUDED.git_sha,
                  profile = EXCLUDED.profile,
                  started_at = EXCLUDED.started_at,
                  completed_at = EXCLUDED.completed_at,
                  exported_at = EXCLUDED.exported_at,
                  status = EXCLUDED.status,
                  attempted_count = EXCLUDED.attempted_count,
                  accepted_count = EXCLUDED.accepted_count,
                  completed_count = EXCLUDED.completed_count,
                  materialized_count = EXCLUDED.materialized_count,
                  projected_count = EXCLUDED.projected_count,
                  failed_count = EXCLUDED.failed_count,
                  p50_latency_ms = EXCLUDED.p50_latency_ms,
                  p95_latency_ms = EXCLUDED.p95_latency_ms,
                  p99_latency_ms = EXCLUDED.p99_latency_ms,
                  artifact_manifest = EXCLUDED.artifact_manifest,
                  summary = EXCLUDED.summary,
                  updated_at = now()
                """.trimIndent()
            ).use { ps ->
                ps.bind(record)
                ps.executeUpdate()
            }
        }
        return record
    }

    override fun find(runId: String): SimulationRunExportRecord? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, scenario_id, run_kind, source, git_sha, profile, started_at, completed_at, exported_at,
                       status, attempted_count, accepted_count, completed_count, materialized_count, projected_count,
                       failed_count, p50_latency_ms, p95_latency_ms, p99_latency_ms,
                       artifact_manifest::text, summary::text
                FROM ${names.simulationRunExports}
                WHERE run_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs -> return if (rs.next()) rs.toRecord() else null }
            }
        }
    }

    override fun list(limit: Int): List<SimulationRunExportRecord> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, scenario_id, run_kind, source, git_sha, profile, started_at, completed_at, exported_at,
                       status, attempted_count, accepted_count, completed_count, materialized_count, projected_count,
                       failed_count, p50_latency_ms, p95_latency_ms, p99_latency_ms,
                       artifact_manifest::text, summary::text
                FROM ${names.simulationRunExports}
                ORDER BY COALESCE(completed_at, exported_at) DESC, run_id ASC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    val records = mutableListOf<SimulationRunExportRecord>()
                    while (rs.next()) records.add(rs.toRecord())
                    return records
                }
            }
        }
    }

    override fun upsertAll(records: List<BotRunPerformanceSummaryRecord>): Int {
        if (records.isEmpty()) return 0
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.runBotPerformanceSummaries}(
                  run_id, bot_id, scenario_id, profile, source, completed_at, exported_at, projected_at,
                  final_equity, realized_pnl, max_drawdown, fail_count, command_count,
                  settlement_score_summary, source_summary, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, now())
                ON CONFLICT (run_id, bot_id) DO UPDATE SET
                  scenario_id = EXCLUDED.scenario_id,
                  profile = EXCLUDED.profile,
                  source = EXCLUDED.source,
                  completed_at = EXCLUDED.completed_at,
                  exported_at = EXCLUDED.exported_at,
                  projected_at = EXCLUDED.projected_at,
                  final_equity = EXCLUDED.final_equity,
                  realized_pnl = EXCLUDED.realized_pnl,
                  max_drawdown = EXCLUDED.max_drawdown,
                  fail_count = EXCLUDED.fail_count,
                  command_count = EXCLUDED.command_count,
                  settlement_score_summary = EXCLUDED.settlement_score_summary,
                  source_summary = EXCLUDED.source_summary,
                  updated_at = now()
                """.trimIndent()
            ).use { ps ->
                records.forEach { record ->
                    ps.bind(record)
                    ps.addBatch()
                }
                return ps.executeBatch().size
            }
        }
    }

    override fun list(runId: String, botId: String, limit: Int): List<BotRunPerformanceSummaryRecord> {
        val filters = mutableListOf<String>()
        if (runId.isNotBlank()) filters.add("run_id = ?")
        if (botId.isNotBlank()) filters.add("bot_id = ?")
        val where = if (filters.isEmpty()) "" else "WHERE ${filters.joinToString(" AND ")}"
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, bot_id, scenario_id, profile, source, completed_at, exported_at, projected_at,
                       final_equity, realized_pnl, max_drawdown, fail_count, command_count,
                       settlement_score_summary::text, source_summary::text
                FROM ${names.runBotPerformanceSummaries}
                $where
                ORDER BY COALESCE(completed_at, exported_at) DESC, run_id ASC, bot_id ASC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                var index = 1
                if (runId.isNotBlank()) ps.setString(index++, runId)
                if (botId.isNotBlank()) ps.setString(index++, botId)
                ps.setInt(index, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    val records = mutableListOf<BotRunPerformanceSummaryRecord>()
                    while (rs.next()) records.add(rs.toBotPerformanceSummaryRecord())
                    return records
                }
            }
        }
    }

    private fun connection(): Connection = dataSource.connection
}

private fun java.sql.PreparedStatement.bind(record: SimulationRunExportRecord) {
    setString(1, record.runId)
    setString(2, record.scenarioId)
    setString(3, record.runKind)
    setString(4, record.source)
    setString(5, record.gitSha)
    setString(6, record.profile)
    setTimestamp(7, record.startedAt?.let(Timestamp::from))
    setTimestamp(8, record.completedAt?.let(Timestamp::from))
    setTimestamp(9, Timestamp.from(record.exportedAt))
    setString(10, record.status)
    setLong(11, record.attemptedCount)
    setLong(12, record.acceptedCount)
    setLong(13, record.completedCount)
    setLong(14, record.materializedCount)
    setLong(15, record.projectedCount)
    setLong(16, record.failedCount)
    setNullableDouble(17, record.p50LatencyMs)
    setNullableDouble(18, record.p95LatencyMs)
    setNullableDouble(19, record.p99LatencyMs)
    setString(20, record.artifactManifestJson)
    setString(21, record.summaryJson)
}

private fun java.sql.PreparedStatement.setNullableDouble(index: Int, value: Double?) {
    if (value == null) setNull(index, java.sql.Types.DOUBLE) else setDouble(index, value)
}

private fun java.sql.PreparedStatement.bind(record: BotRunPerformanceSummaryRecord) {
    setString(1, record.runId)
    setString(2, record.botId)
    setString(3, record.scenarioId)
    setString(4, record.profile)
    setString(5, record.source)
    setTimestamp(6, record.completedAt?.let(Timestamp::from))
    setTimestamp(7, Timestamp.from(record.exportedAt))
    setTimestamp(8, Timestamp.from(record.projectedAt))
    setNullableDouble(9, record.finalEquity)
    setNullableDouble(10, record.realizedPnl)
    setNullableDouble(11, record.maxDrawdown)
    setLong(12, record.failCount)
    setLong(13, record.commandCount)
    setString(14, record.settlementScoreSummaryJson)
    setString(15, record.sourceSummaryJson)
}

private fun ResultSet.toRecord(): SimulationRunExportRecord {
    return SimulationRunExportRecord(
        runId = getString(1),
        scenarioId = getString(2),
        runKind = getString(3),
        source = getString(4),
        gitSha = getString(5),
        profile = getString(6),
        startedAt = getTimestamp(7)?.toInstant(),
        completedAt = getTimestamp(8)?.toInstant(),
        exportedAt = getTimestamp(9).toInstant(),
        status = getString(10),
        attemptedCount = getLong(11),
        acceptedCount = getLong(12),
        completedCount = getLong(13),
        materializedCount = getLong(14),
        projectedCount = getLong(15),
        failedCount = getLong(16),
        p50LatencyMs = nullableDouble(17),
        p95LatencyMs = nullableDouble(18),
        p99LatencyMs = nullableDouble(19),
        artifactManifestJson = getString(20),
        summaryJson = getString(21)
    )
}

private fun ResultSet.nullableDouble(index: Int): Double? {
    val value = getDouble(index)
    return if (wasNull()) null else value
}

private fun ResultSet.toBotPerformanceSummaryRecord(): BotRunPerformanceSummaryRecord {
    return BotRunPerformanceSummaryRecord(
        runId = getString(1),
        botId = getString(2),
        scenarioId = getString(3),
        profile = getString(4),
        source = getString(5),
        completedAt = getTimestamp(6)?.toInstant(),
        exportedAt = getTimestamp(7).toInstant(),
        projectedAt = getTimestamp(8).toInstant(),
        finalEquity = nullableDouble(9),
        realizedPnl = nullableDouble(10),
        maxDrawdown = nullableDouble(11),
        failCount = getLong(12),
        commandCount = getLong(13),
        settlementScoreSummaryJson = getString(14),
        sourceSummaryJson = getString(15)
    )
}
