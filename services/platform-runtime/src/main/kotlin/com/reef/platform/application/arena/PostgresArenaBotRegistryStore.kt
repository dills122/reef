package com.reef.platform.application.arena

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
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
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_bot_versions_status ON ${names.botVersions}(status)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_arena_runs_status_created ON ${names.runRecords}(status, created_at DESC)")
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

    override fun botByFileName(fileName: String): ArenaBot? {
        return queryBot("file_name = ?", fileName)
    }

    override fun saveVersion(version: ArenaBotVersion) {
        connection().use { conn ->
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
                conn.autoCommit = true
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
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.runRecords}(
                      run_id, mode_id, scenario_id, seed, policy_version, status, created_at, completed_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id) DO UPDATE SET
                      mode_id = EXCLUDED.mode_id,
                      scenario_id = EXCLUDED.scenario_id,
                      seed = EXCLUDED.seed,
                      policy_version = EXCLUDED.policy_version,
                      status = EXCLUDED.status,
                      created_at = EXCLUDED.created_at,
                      completed_at = EXCLUDED.completed_at
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, runRecord.runId)
                    ps.setString(2, runRecord.modeId)
                    ps.setString(3, runRecord.scenarioId)
                    ps.setLong(4, runRecord.seed)
                    ps.setString(5, runRecord.policyVersion)
                    ps.setString(6, runRecord.status.name)
                    ps.setTimestamp(7, Timestamp.from(runRecord.createdAt))
                    ps.setTimestamp(8, runRecord.completedAt?.let { Timestamp.from(it) })
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
                conn.autoCommit = true
            }
        }
    }

    override fun runRecord(runId: String): ArenaRunRecord? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT run_id, mode_id, scenario_id, seed, policy_version, status, created_at, completed_at
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
            botVersions = botVersions,
            status = ArenaRunStatus.valueOf(getString("status")),
            createdAt = instant("created_at"),
            completedAt = nullableInstant("completed_at")
        )
    }

    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

    private fun ResultSet.nullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
