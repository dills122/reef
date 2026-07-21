package com.reef.arena.controlplane.arena

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PostgresArenaBotRegistryStore(
    private val dataSource: DataSource,
    private val names: PostgresArenaSqlNames = PostgresArenaSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : ArenaBotRegistryStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    ArenaPostgresSchemaRequirements.registry(names)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.bots} (
                      bot_id TEXT PRIMARY KEY,
                      file_name TEXT NOT NULL UNIQUE,
                      name TEXT NOT NULL,
                      publisher TEXT NOT NULL,
                      email TEXT NOT NULL,
                      description TEXT NOT NULL DEFAULT '',
                      version TEXT NOT NULL DEFAULT '',
                      created_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.botVersions} (
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      source_hash TEXT NOT NULL,
                      artifact_hash TEXT NOT NULL,
                      sdk_version TEXT NOT NULL,
                      api_version TEXT NOT NULL,
                      dependency_manifest_hash TEXT NOT NULL,
                      status TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (bot_id, version_id),
                      FOREIGN KEY (bot_id) REFERENCES ${names.bots}(bot_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.qualificationReports} (
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      report_id TEXT NOT NULL PRIMARY KEY,
                      status TEXT NOT NULL,
                      policy_version TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL,
                      FOREIGN KEY (bot_id, version_id) REFERENCES ${names.botVersions}(bot_id, version_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.qualificationReportIssues} (
                      report_id TEXT NOT NULL,
                      issue_order INTEGER NOT NULL,
                      issue TEXT NOT NULL,
                      PRIMARY KEY (report_id, issue_order),
                      FOREIGN KEY (report_id) REFERENCES ${names.qualificationReports}(report_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.operatorDecisions} (
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      decision_order BIGSERIAL PRIMARY KEY,
                      from_status TEXT NOT NULL,
                      to_status TEXT NOT NULL,
                      actor_id TEXT NOT NULL,
                      reason TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      occurred_at TIMESTAMPTZ NOT NULL,
                      FOREIGN KEY (bot_id, version_id) REFERENCES ${names.botVersions}(bot_id, version_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runRecords} (
                      run_id TEXT PRIMARY KEY,
                      mode_id TEXT NOT NULL,
                      scenario_id TEXT NOT NULL,
                      seed BIGINT NOT NULL,
                      policy_version TEXT NOT NULL,
                      admission_window_id TEXT NOT NULL,
                      roster_snapshot_id TEXT NOT NULL,
                      roster_snapshot_hash TEXT NOT NULL CHECK (roster_snapshot_hash ~ '^sha256:[a-f0-9]{64}$'),
                      seed_set_hash TEXT NOT NULL CHECK (seed_set_hash ~ '^sha256:[a-f0-9]{64}$'),
                      actor_profile_version TEXT NOT NULL,
                      actor_profile_hash TEXT NOT NULL CHECK (actor_profile_hash ~ '^sha256:[a-f0-9]{64}$'),
                      risk_policy_hash TEXT NOT NULL CHECK (risk_policy_hash ~ '^sha256:[a-f0-9]{64}$'),
                      policy_envelope_hash TEXT NOT NULL,
                      scoring_policy_version TEXT NOT NULL,
                      scoring_policy_hash TEXT NOT NULL,
                      economic_policy_version TEXT NOT NULL,
                      economic_policy_hash TEXT NOT NULL,
                      status TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL,
                      completed_at TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runBotVersions} (
                      run_id TEXT NOT NULL,
                      bot_order INTEGER NOT NULL,
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      PRIMARY KEY (run_id, bot_order),
                      FOREIGN KEY (run_id) REFERENCES ${names.runRecords}(run_id),
                      FOREIGN KEY (bot_id, version_id) REFERENCES ${names.botVersions}(bot_id, version_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runBotResults} (
                      run_id TEXT NOT NULL,
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      scoring_policy_version TEXT NOT NULL,
                      scoring_policy_hash TEXT NOT NULL,
                      policy_envelope_hash TEXT NOT NULL,
                      final_equity BIGINT NOT NULL,
                      realized_pnl BIGINT NOT NULL,
                      max_drawdown BIGINT NOT NULL,
                      actions_proposed INTEGER NOT NULL,
                      order_actions_proposed INTEGER NOT NULL,
                      data_calls INTEGER NOT NULL,
                      signals_generated INTEGER NOT NULL,
                      disqualified BOOLEAN NOT NULL DEFAULT false,
                      score_eligible BOOLEAN NOT NULL DEFAULT true,
                      public_leaderboard BOOLEAN NOT NULL DEFAULT true,
                      created_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (run_id, bot_id, version_id, scoring_policy_version),
                      FOREIGN KEY (run_id) REFERENCES ${names.runRecords}(run_id),
                      FOREIGN KEY (bot_id, version_id) REFERENCES ${names.botVersions}(bot_id, version_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runEnforcementEvents} (
                      run_id TEXT NOT NULL,
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      decision TEXT NOT NULL,
                      reason_code TEXT NOT NULL,
                      reason TEXT NOT NULL,
                      policy_version TEXT NOT NULL,
                      counters_json TEXT NOT NULL,
                      occurred_at TIMESTAMPTZ NOT NULL,
                      PRIMARY KEY (run_id, bot_id, version_id, decision, reason_code),
                      FOREIGN KEY (run_id) REFERENCES ${names.runRecords}(run_id),
                      FOREIGN KEY (bot_id, version_id) REFERENCES ${names.botVersions}(bot_id, version_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.runtimeConfigDescriptors} (
                      bot_id TEXT NOT NULL,
                      version_id TEXT NOT NULL,
                      config_key TEXT NOT NULL,
                      provider TEXT NOT NULL,
                      secret_path TEXT NOT NULL,
                      required BOOLEAN NOT NULL,
                      description TEXT NOT NULL DEFAULT '',
                      PRIMARY KEY (bot_id, version_id, config_key),
                      FOREIGN KEY (bot_id, version_id) REFERENCES ${names.botVersions}(bot_id, version_id)
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_bot_versions_status ON ${names.botVersions}(status)")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS admission_window_id TEXT NOT NULL DEFAULT 'legacy-unbound'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS roster_snapshot_id TEXT NOT NULL DEFAULT 'legacy-unbound'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS roster_snapshot_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS seed_set_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS actor_profile_version TEXT NOT NULL DEFAULT 'legacy-unbound'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS actor_profile_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS risk_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS policy_envelope_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS scoring_policy_version TEXT NOT NULL DEFAULT 'legacy-unresolved'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS scoring_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS economic_policy_version TEXT NOT NULL DEFAULT 'legacy-unresolved'")
                stmt.execute("ALTER TABLE ${names.runRecords} ADD COLUMN IF NOT EXISTS economic_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runBotResults} ADD COLUMN IF NOT EXISTS scoring_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("ALTER TABLE ${names.runBotResults} ADD COLUMN IF NOT EXISTS policy_envelope_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_runs_status_created ON ${names.runRecords}(status, created_at DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_run_records_roster_binding ON ${names.runRecords}(admission_window_id, roster_snapshot_id, roster_snapshot_hash)")
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_arena_run_bot_results_leaderboard
                    ON ${names.runBotResults}(scoring_policy_version, score_eligible, public_leaderboard, disqualified, final_equity DESC, realized_pnl DESC, max_drawdown ASC)
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_run_enforcement_events_run ON ${names.runEnforcementEvents}(run_id, occurred_at DESC)")
            }
        }
    }

    override fun saveBot(bot: ArenaBot) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.bots}(bot_id, file_name, name, publisher, email, description, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (bot_id) DO UPDATE SET
                  file_name = EXCLUDED.file_name,
                  name = EXCLUDED.name,
                  publisher = EXCLUDED.publisher,
                  email = EXCLUDED.email,
                  description = EXCLUDED.description,
                  version = EXCLUDED.version,
                  created_at = EXCLUDED.created_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, bot.botId)
                ps.setString(2, bot.fileName)
                ps.setString(3, bot.metadata.name)
                ps.setString(4, bot.metadata.publisher)
                ps.setString(5, bot.metadata.email)
                ps.setString(6, bot.metadata.description)
                ps.setString(7, bot.metadata.version)
                ps.setTimestamp(8, Timestamp.from(bot.createdAt))
                ps.executeUpdate()
            }
        }
    }

    override fun bot(botId: String): ArenaBot? {
        return queryBot("bot_id = ?", botId)
    }

    override fun bots(limit: Int): List<ArenaBot> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT bot_id, file_name, name, publisher, email, description, version, created_at
                FROM ${names.bots}
                ORDER BY created_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<ArenaBot>()
                    while (rs.next()) result.add(rs.toArenaBot())
                    return result
                }
            }
        }
    }

    override fun botByFileName(fileName: String): ArenaBot? {
        return queryBot("file_name = ?", fileName)
    }

    override fun runs(limit: Int): List<ArenaRunRecord> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, mode_id, scenario_id, seed, policy_version, admission_window_id,
                       roster_snapshot_id, roster_snapshot_hash, seed_set_hash, actor_profile_version,
                       actor_profile_hash, risk_policy_hash, policy_envelope_hash,
                       scoring_policy_version, scoring_policy_hash, economic_policy_version, economic_policy_hash,
                       status, created_at, completed_at
                FROM ${names.runRecords}
                ORDER BY created_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<ArenaRunRecord>()
                    while (rs.next()) result.add(rs.toRunRecord(loadRunBotVersions(conn, rs.getString("run_id"))))
                    return result
                }
            }
        }
    }

    override fun saveVersion(version: ArenaBotVersion) {
        connection().use { conn ->
            saveVersion(conn, version)
        }
    }

    override fun saveVersionTransition(version: ArenaBotVersion, decision: ArenaOperatorDecision) {
        require(version.botId == decision.botId && version.versionId == decision.versionId) {
            "version transition decision must target the saved version"
        }
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                saveVersion(conn, version)
                saveOperatorDecision(conn, decision)
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun saveVersion(conn: Connection, version: ArenaBotVersion) {
        conn.prepareStatement(
                """
                INSERT INTO ${names.botVersions}(
                  bot_id, version_id, source_hash, artifact_hash, sdk_version, api_version,
                  dependency_manifest_hash, status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (bot_id, version_id) DO UPDATE SET
                  source_hash = EXCLUDED.source_hash,
                  artifact_hash = EXCLUDED.artifact_hash,
                  sdk_version = EXCLUDED.sdk_version,
                  api_version = EXCLUDED.api_version,
                  dependency_manifest_hash = EXCLUDED.dependency_manifest_hash,
                  status = EXCLUDED.status,
                  created_at = EXCLUDED.created_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, version.botId)
                ps.setString(2, version.versionId)
                ps.setString(3, version.sourceHash)
                ps.setString(4, version.artifactHash)
                ps.setString(5, version.sdkVersion)
                ps.setString(6, version.apiVersion)
                ps.setString(7, version.dependencyManifestHash)
                ps.setString(8, version.status.name)
                ps.setTimestamp(9, Timestamp.from(version.createdAt))
                ps.executeUpdate()
            }
    }

    override fun version(botId: String, versionId: String): ArenaBotVersion? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT bot_id, version_id, source_hash, artifact_hash, sdk_version, api_version,
                       dependency_manifest_hash, status, created_at
                FROM ${names.botVersions}
                WHERE bot_id = ? AND version_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, botId)
                ps.setString(2, versionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toArenaBotVersion() else null
                }
            }
        }
    }

    override fun saveQualificationReport(report: ArenaQualificationReport) {
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.qualificationReports}(bot_id, version_id, report_id, status, policy_version, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (report_id) DO UPDATE SET
                      bot_id = EXCLUDED.bot_id,
                      version_id = EXCLUDED.version_id,
                      status = EXCLUDED.status,
                      policy_version = EXCLUDED.policy_version,
                      created_at = EXCLUDED.created_at
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, report.botId)
                    ps.setString(2, report.versionId)
                    ps.setString(3, report.reportId)
                    ps.setString(4, report.status.name)
                    ps.setString(5, report.policyVersion)
                    ps.setTimestamp(6, Timestamp.from(report.createdAt))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM ${names.qualificationReportIssues} WHERE report_id = ?").use { ps ->
                    ps.setString(1, report.reportId)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.qualificationReportIssues}(report_id, issue_order, issue)
                    VALUES (?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    report.issues.forEachIndexed { index, issue ->
                        ps.setString(1, report.reportId)
                        ps.setInt(2, index)
                        ps.setString(3, issue)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun qualificationReports(botId: String, versionId: String): List<ArenaQualificationReport> {
        connection().use { conn ->
            val reports = mutableListOf<ArenaQualificationReport>()
            conn.prepareStatement(
                """
                SELECT bot_id, version_id, report_id, status, policy_version, created_at
                FROM ${names.qualificationReports}
                WHERE bot_id = ? AND version_id = ?
                ORDER BY created_at, report_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, botId)
                ps.setString(2, versionId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) reports.add(rs.toQualificationReport(loadIssues(conn, rs.getString("report_id"))))
                }
            }
            return reports
        }
    }

    override fun saveOperatorDecision(decision: ArenaOperatorDecision) {
        connection().use { conn ->
            saveOperatorDecision(conn, decision)
        }
    }

    private fun saveOperatorDecision(conn: Connection, decision: ArenaOperatorDecision) {
        conn.prepareStatement(
                """
                INSERT INTO ${names.operatorDecisions}(
                  bot_id, version_id, from_status, to_status, actor_id, reason, correlation_id, occurred_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, decision.botId)
                ps.setString(2, decision.versionId)
                ps.setString(3, decision.fromStatus.name)
                ps.setString(4, decision.toStatus.name)
                ps.setString(5, decision.actorId)
                ps.setString(6, decision.reason)
                ps.setString(7, decision.correlationId)
                ps.setTimestamp(8, Timestamp.from(decision.occurredAt))
                ps.executeUpdate()
            }
    }

    override fun operatorDecisions(botId: String, versionId: String): List<ArenaOperatorDecision> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT bot_id, version_id, from_status, to_status, actor_id, reason, correlation_id, occurred_at
                FROM ${names.operatorDecisions}
                WHERE bot_id = ? AND version_id = ?
                ORDER BY decision_order
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, botId)
                ps.setString(2, versionId)
                ps.executeQuery().use { rs ->
                    val decisions = mutableListOf<ArenaOperatorDecision>()
                    while (rs.next()) decisions.add(rs.toOperatorDecision())
                    return decisions
                }
            }
        }
    }

    override fun saveRunRecord(runRecord: ArenaRunRecord) {
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { ps ->
                    ps.setString(1, runRecord.runId)
                    ps.executeQuery().use { rs -> require(rs.next()) }
                }
                val existing = conn.prepareStatement(
                    """
                    SELECT run_id, mode_id, scenario_id, seed, policy_version, admission_window_id,
                           roster_snapshot_id, roster_snapshot_hash, seed_set_hash, actor_profile_version,
                           actor_profile_hash, risk_policy_hash, policy_envelope_hash,
                           scoring_policy_version, scoring_policy_hash, economic_policy_version, economic_policy_hash,
                           status, created_at, completed_at
                    FROM ${names.runRecords}
                    WHERE run_id = ?
                    FOR UPDATE
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, runRecord.runId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.toRunRecord(loadRunBotVersions(conn, runRecord.runId)) else null
                    }
                }
                existing?.let { accepted ->
                    require(accepted.copy(status = runRecord.status, completedAt = runRecord.completedAt) == runRecord) {
                        "accepted arena run policy and composition are immutable"
                    }
                }
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.runRecords}(
                      run_id, mode_id, scenario_id, seed, policy_version, admission_window_id,
                      roster_snapshot_id, roster_snapshot_hash, seed_set_hash, actor_profile_version,
                      actor_profile_hash, risk_policy_hash, policy_envelope_hash,
                      scoring_policy_version, scoring_policy_hash, economic_policy_version, economic_policy_hash,
                      status, created_at, completed_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id) DO UPDATE SET
                      status = EXCLUDED.status,
                      completed_at = EXCLUDED.completed_at
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, runRecord.runId)
                    ps.setString(2, runRecord.modeId)
                    ps.setString(3, runRecord.scenarioId)
                    ps.setLong(4, runRecord.seed)
                    ps.setString(5, runRecord.policyVersion)
                    ps.setString(6, runRecord.admissionWindowId)
                    ps.setString(7, runRecord.rosterSnapshotId)
                    ps.setString(8, runRecord.rosterSnapshotHash)
                    ps.setString(9, runRecord.seedSetHash)
                    ps.setString(10, runRecord.actorProfileVersion)
                    ps.setString(11, runRecord.actorProfileHash)
                    ps.setString(12, runRecord.riskPolicyHash)
                    ps.setString(13, runRecord.policyEnvelopeHash)
                    ps.setString(14, runRecord.scoringPolicyVersion)
                    ps.setString(15, runRecord.scoringPolicyHash)
                    ps.setString(16, runRecord.economicPolicyVersion)
                    ps.setString(17, runRecord.economicPolicyHash)
                    ps.setString(18, runRecord.status.name)
                    ps.setTimestamp(19, Timestamp.from(runRecord.createdAt))
                    ps.setTimestamp(20, runRecord.completedAt?.let { Timestamp.from(it) })
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM ${names.runBotVersions} WHERE run_id = ?").use { ps ->
                    ps.setString(1, runRecord.runId)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.runBotVersions}(run_id, bot_order, bot_id, version_id)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    runRecord.botVersions.forEachIndexed { index, ref ->
                        ps.setString(1, runRecord.runId)
                        ps.setInt(2, index)
                        ps.setString(3, ref.botId)
                        ps.setString(4, ref.versionId)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun runRecord(runId: String): ArenaRunRecord? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, mode_id, scenario_id, seed, policy_version, admission_window_id,
                       roster_snapshot_id, roster_snapshot_hash, seed_set_hash, actor_profile_version,
                       actor_profile_hash, risk_policy_hash, policy_envelope_hash,
                       scoring_policy_version, scoring_policy_hash, economic_policy_version, economic_policy_hash,
                       status, created_at, completed_at
                FROM ${names.runRecords}
                WHERE run_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toRunRecord(loadRunBotVersions(conn, runId)) else null
                }
            }
        }
    }

    override fun saveRunBotResult(result: ArenaRunBotResult) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.runBotResults}(
                  run_id, bot_id, version_id, scoring_policy_version, scoring_policy_hash, policy_envelope_hash,
                  final_equity, realized_pnl,
                  max_drawdown, actions_proposed, order_actions_proposed, data_calls, signals_generated,
                  disqualified, score_eligible, public_leaderboard, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, bot_id, version_id, scoring_policy_version) DO UPDATE SET
                  final_equity = EXCLUDED.final_equity,
                  realized_pnl = EXCLUDED.realized_pnl,
                  max_drawdown = EXCLUDED.max_drawdown,
                  actions_proposed = EXCLUDED.actions_proposed,
                  order_actions_proposed = EXCLUDED.order_actions_proposed,
                  data_calls = EXCLUDED.data_calls,
                  signals_generated = EXCLUDED.signals_generated,
                  disqualified = EXCLUDED.disqualified,
                  score_eligible = EXCLUDED.score_eligible,
                  public_leaderboard = EXCLUDED.public_leaderboard,
                  created_at = EXCLUDED.created_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, result.runId)
                ps.setString(2, result.botId)
                ps.setString(3, result.versionId)
                ps.setString(4, result.scoringPolicyVersion)
                ps.setString(5, result.scoringPolicyHash)
                ps.setString(6, result.policyEnvelopeHash)
                ps.setLong(7, result.finalEquity)
                ps.setLong(8, result.realizedPnl)
                ps.setLong(9, result.maxDrawdown)
                ps.setInt(10, result.actionsProposed)
                ps.setInt(11, result.orderActionsProposed)
                ps.setInt(12, result.dataCalls)
                ps.setInt(13, result.signalsGenerated)
                ps.setBoolean(14, result.disqualified)
                ps.setBoolean(15, result.scoreEligible)
                ps.setBoolean(16, result.publicLeaderboard)
                ps.setTimestamp(17, Timestamp.from(result.createdAt))
                ps.executeUpdate()
            }
        }
    }

    override fun runBotResults(runId: String): List<ArenaRunBotResult> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, bot_id, version_id, scoring_policy_version, scoring_policy_hash, policy_envelope_hash,
                       final_equity, realized_pnl,
                       max_drawdown, actions_proposed, order_actions_proposed, data_calls, signals_generated,
                       disqualified, score_eligible, public_leaderboard, created_at
                FROM ${names.runBotResults}
                WHERE run_id = ?
                ORDER BY bot_id, version_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<ArenaRunBotResult>()
                    while (rs.next()) results.add(rs.toRunBotResult())
                    return results
                }
            }
        }
    }

    override fun saveRunEnforcementEvent(event: ArenaRunEnforcementEvent) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.runEnforcementEvents}(
                  run_id, bot_id, version_id, decision, reason_code, reason,
                  policy_version, counters_json, occurred_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, bot_id, version_id, decision, reason_code) DO UPDATE SET
                  reason = EXCLUDED.reason,
                  policy_version = EXCLUDED.policy_version,
                  counters_json = EXCLUDED.counters_json,
                  occurred_at = EXCLUDED.occurred_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, event.runId)
                ps.setString(2, event.botId)
                ps.setString(3, event.versionId)
                ps.setString(4, event.decision)
                ps.setString(5, event.reasonCode)
                ps.setString(6, event.reason)
                ps.setString(7, event.policyVersion)
                ps.setString(8, event.countersJson)
                ps.setTimestamp(9, Timestamp.from(event.occurredAt))
                ps.executeUpdate()
            }
        }
    }

    override fun runEnforcementEvents(runId: String): List<ArenaRunEnforcementEvent> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, bot_id, version_id, decision, reason_code, reason,
                       policy_version, counters_json, occurred_at
                FROM ${names.runEnforcementEvents}
                WHERE run_id = ?
                ORDER BY occurred_at, bot_id, version_id, decision, reason_code
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    val events = mutableListOf<ArenaRunEnforcementEvent>()
                    while (rs.next()) events.add(rs.toRunEnforcementEvent())
                    return events
                }
            }
        }
    }

    override fun leaderboard(
        modeId: String,
        scoringPolicyVersion: String,
        limit: Int
    ): List<ArenaLeaderboardEntry> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT r.run_id, rb.bot_id, b.name AS bot_name, b.publisher AS owner_handle,
                       rb.version_id, rb.scoring_policy_version, rb.final_equity,
                       rb.realized_pnl, rb.max_drawdown, rb.disqualified
                FROM ${names.runBotResults} rb
                JOIN ${names.runRecords} r ON r.run_id = rb.run_id
                JOIN ${names.bots} b ON b.bot_id = rb.bot_id
                WHERE r.mode_id = ?
                  AND r.status = ?
                  AND r.admission_window_id <> 'legacy-unbound'
                  AND rb.scoring_policy_version = ?
                  AND rb.scoring_policy_version = r.scoring_policy_version
                  AND rb.scoring_policy_hash = r.scoring_policy_hash
                  AND rb.policy_envelope_hash = r.policy_envelope_hash
                  AND rb.score_eligible = true
                  AND rb.public_leaderboard = true
                  AND rb.disqualified = false
                ORDER BY rb.final_equity DESC, rb.realized_pnl DESC, rb.max_drawdown ASC,
                         rb.run_id ASC, rb.bot_id ASC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, modeId)
                ps.setString(2, ArenaRunStatus.Completed.name)
                ps.setString(3, scoringPolicyVersion)
                ps.setInt(4, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    val entries = mutableListOf<ArenaLeaderboardEntry>()
                    while (rs.next()) entries.add(rs.toLeaderboardEntry(entries.size + 1))
                    return entries
                }
            }
        }
    }

    override fun replaceRuntimeConfigDescriptors(
        botId: String,
        versionId: String,
        descriptors: List<ArenaRuntimeConfigDescriptor>
    ) {
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "DELETE FROM ${names.runtimeConfigDescriptors} WHERE bot_id = ? AND version_id = ?"
                ).use { ps ->
                    ps.setString(1, botId)
                    ps.setString(2, versionId)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.runtimeConfigDescriptors}(
                      bot_id, version_id, config_key, provider, secret_path, required, description
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    descriptors.forEach { descriptor ->
                        ps.setString(1, descriptor.botId)
                        ps.setString(2, descriptor.versionId)
                        ps.setString(3, descriptor.key)
                        ps.setString(4, descriptor.provider.name)
                        ps.setString(5, descriptor.secretPath)
                        ps.setBoolean(6, descriptor.required)
                        ps.setString(7, descriptor.description)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun runtimeConfigDescriptors(botId: String, versionId: String): List<ArenaRuntimeConfigDescriptor> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT bot_id, version_id, config_key, provider, secret_path, required, description
                FROM ${names.runtimeConfigDescriptors}
                WHERE bot_id = ? AND version_id = ?
                ORDER BY config_key
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, botId)
                ps.setString(2, versionId)
                ps.executeQuery().use { rs ->
                    val descriptors = mutableListOf<ArenaRuntimeConfigDescriptor>()
                    while (rs.next()) descriptors.add(rs.toRuntimeConfigDescriptor())
                    return descriptors
                }
            }
        }
    }

    private fun queryBot(whereClause: String, value: String): ArenaBot? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT bot_id, file_name, name, publisher, email, description, version, created_at
                FROM ${names.bots}
                WHERE $whereClause
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toArenaBot() else null
                }
            }
        }
    }

    private fun loadIssues(conn: Connection, reportId: String): List<String> {
        conn.prepareStatement(
            """
            SELECT issue
            FROM ${names.qualificationReportIssues}
            WHERE report_id = ?
            ORDER BY issue_order
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, reportId)
            ps.executeQuery().use { rs ->
                val issues = mutableListOf<String>()
                while (rs.next()) issues.add(rs.getString("issue"))
                return issues
            }
        }
    }

    private fun loadRunBotVersions(conn: Connection, runId: String): List<ArenaRunBotVersionRef> {
        conn.prepareStatement(
            """
            SELECT bot_id, version_id
            FROM ${names.runBotVersions}
            WHERE run_id = ?
            ORDER BY bot_order
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                val refs = mutableListOf<ArenaRunBotVersionRef>()
                while (rs.next()) refs.add(ArenaRunBotVersionRef(rs.getString("bot_id"), rs.getString("version_id")))
                return refs
            }
        }
    }

    private fun connection(): Connection = dataSource.connection

    private fun ResultSet.toArenaBot(): ArenaBot {
        return ArenaBot(
            botId = getString("bot_id"),
            fileName = getString("file_name"),
            metadata = ArenaBotMetadata(
                name = getString("name"),
                publisher = getString("publisher"),
                email = getString("email"),
                description = getString("description"),
                version = getString("version")
            ),
            createdAt = instant("created_at")
        )
    }

    private fun ResultSet.toArenaBotVersion(): ArenaBotVersion {
        return ArenaBotVersion(
            botId = getString("bot_id"),
            versionId = getString("version_id"),
            sourceHash = getString("source_hash"),
            artifactHash = getString("artifact_hash"),
            sdkVersion = getString("sdk_version"),
            apiVersion = getString("api_version"),
            dependencyManifestHash = getString("dependency_manifest_hash"),
            status = ArenaBotVersionStatus.valueOf(getString("status")),
            createdAt = instant("created_at")
        )
    }

    private fun ResultSet.toQualificationReport(issues: List<String>): ArenaQualificationReport {
        return ArenaQualificationReport(
            botId = getString("bot_id"),
            versionId = getString("version_id"),
            reportId = getString("report_id"),
            status = ArenaQualificationStatus.valueOf(getString("status")),
            issues = issues,
            policyVersion = getString("policy_version"),
            createdAt = instant("created_at")
        )
    }

    private fun ResultSet.toOperatorDecision(): ArenaOperatorDecision {
        return ArenaOperatorDecision(
            botId = getString("bot_id"),
            versionId = getString("version_id"),
            fromStatus = ArenaBotVersionStatus.valueOf(getString("from_status")),
            toStatus = ArenaBotVersionStatus.valueOf(getString("to_status")),
            actorId = getString("actor_id"),
            reason = getString("reason"),
            correlationId = getString("correlation_id"),
            occurredAt = instant("occurred_at")
        )
    }

    private fun ResultSet.toRunRecord(botVersions: List<ArenaRunBotVersionRef>): ArenaRunRecord {
        return ArenaRunRecord(
            runId = getString("run_id"),
            modeId = getString("mode_id"),
            scenarioId = getString("scenario_id"),
            seed = getLong("seed"),
            policyVersion = getString("policy_version"),
            admissionWindowId = getString("admission_window_id"),
            rosterSnapshotId = getString("roster_snapshot_id"),
            rosterSnapshotHash = getString("roster_snapshot_hash"),
            seedSetHash = getString("seed_set_hash"),
            actorProfileVersion = getString("actor_profile_version"),
            actorProfileHash = getString("actor_profile_hash"),
            riskPolicyHash = getString("risk_policy_hash"),
            policyEnvelopeHash = getString("policy_envelope_hash"),
            scoringPolicyVersion = getString("scoring_policy_version"),
            scoringPolicyHash = getString("scoring_policy_hash"),
            economicPolicyVersion = getString("economic_policy_version"),
            economicPolicyHash = getString("economic_policy_hash"),
            botVersions = botVersions,
            status = ArenaRunStatus.valueOf(getString("status")),
            createdAt = instant("created_at"),
            completedAt = nullableInstant("completed_at")
        )
    }

    private fun ResultSet.toRunBotResult(): ArenaRunBotResult {
        return ArenaRunBotResult(
            runId = getString("run_id"),
            botId = getString("bot_id"),
            versionId = getString("version_id"),
            scoringPolicyVersion = getString("scoring_policy_version"),
            scoringPolicyHash = getString("scoring_policy_hash"),
            policyEnvelopeHash = getString("policy_envelope_hash"),
            finalEquity = getLong("final_equity"),
            realizedPnl = getLong("realized_pnl"),
            maxDrawdown = getLong("max_drawdown"),
            actionsProposed = getInt("actions_proposed"),
            orderActionsProposed = getInt("order_actions_proposed"),
            dataCalls = getInt("data_calls"),
            signalsGenerated = getInt("signals_generated"),
            disqualified = getBoolean("disqualified"),
            scoreEligible = getBoolean("score_eligible"),
            publicLeaderboard = getBoolean("public_leaderboard"),
            createdAt = instant("created_at")
        )
    }

    private fun ResultSet.toRunEnforcementEvent(): ArenaRunEnforcementEvent {
        return ArenaRunEnforcementEvent(
            runId = getString("run_id"),
            botId = getString("bot_id"),
            versionId = getString("version_id"),
            decision = getString("decision"),
            reasonCode = getString("reason_code"),
            reason = getString("reason"),
            policyVersion = getString("policy_version"),
            countersJson = getString("counters_json"),
            occurredAt = instant("occurred_at")
        )
    }

    private fun ResultSet.toLeaderboardEntry(rank: Int): ArenaLeaderboardEntry {
        return ArenaLeaderboardEntry(
            rank = rank,
            runId = getString("run_id"),
            botId = getString("bot_id"),
            botName = getString("bot_name"),
            ownerHandle = getString("owner_handle"),
            versionId = getString("version_id"),
            scoringPolicyVersion = getString("scoring_policy_version"),
            finalEquity = getLong("final_equity"),
            realizedPnl = getLong("realized_pnl"),
            maxDrawdown = getLong("max_drawdown"),
            disqualified = getBoolean("disqualified")
        )
    }

    private fun ResultSet.toRuntimeConfigDescriptor(): ArenaRuntimeConfigDescriptor {
        return ArenaRuntimeConfigDescriptor(
            botId = getString("bot_id"),
            versionId = getString("version_id"),
            key = getString("config_key"),
            provider = ArenaRuntimeConfigProvider.valueOf(getString("provider")),
            secretPath = getString("secret_path"),
            required = getBoolean("required"),
            description = getString("description")
        )
    }

    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

    private fun ResultSet.nullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
