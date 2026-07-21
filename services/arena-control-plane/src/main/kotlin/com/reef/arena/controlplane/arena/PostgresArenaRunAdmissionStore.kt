package com.reef.arena.controlplane.arena

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/** Durable, immutable Arena eligibility-window decisions and roster snapshots. */
class PostgresArenaRunAdmissionStore(
    private val dataSource: DataSource,
    private val names: PostgresArenaSqlNames = PostgresArenaSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : ArenaRunAdmissionStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(conn, ArenaPostgresSchemaRequirements.runAdmission(names))
                return@use
            }
            createSchema(conn)
        }
    }

    override fun createWindow(window: ArenaAdmissionWindow): ArenaAdmissionWindow = transaction { conn ->
        val normalized = window.normalizedForPostgres()
        conn.prepareStatement(
            """
            INSERT INTO ${names.admissionWindows}(
              window_id, policy_version, scheduled_start, invite_decision_cutoff,
              merge_readiness_cutoff, roster_lock_at, operational_recheck_at,
              run_instantiation_at, display_time_zone, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (window_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, normalized.windowId)
            ps.setString(2, normalized.policyVersion)
            ps.setTimestamp(3, Timestamp.from(normalized.scheduledStart))
            ps.setTimestamp(4, Timestamp.from(normalized.inviteDecisionCutoff))
            ps.setTimestamp(5, Timestamp.from(normalized.mergeReadinessCutoff))
            ps.setTimestamp(6, Timestamp.from(normalized.rosterLockAt))
            ps.setTimestamp(7, Timestamp.from(normalized.operationalRecheckAt))
            ps.setTimestamp(8, Timestamp.from(normalized.runInstantiationAt))
            ps.setString(9, normalized.displayTimeZone)
            ps.setTimestamp(10, Timestamp.from(normalized.createdAt))
            ps.executeUpdate()
        }
        val stored = requireNotNull(window(conn, normalized.windowId)) { "admission window was not persisted" }
        require(stored == normalized) { "admission window is immutable: ${normalized.windowId}" }
        stored
    }

    override fun window(windowId: String): ArenaAdmissionWindow? = connection().use { window(it, windowId) }

    override fun recordDecision(decision: ArenaRunEligibilityDecision): ArenaRunEligibilityDecision = transaction { conn ->
        val normalized = decision.copy(evaluatedAt = decision.evaluatedAt.postgresInstant())
        val inserted = conn.prepareStatement(
            """
            INSERT INTO ${names.eligibilityDecisions}(
              evaluation_id, window_id, bot_id, version_id, outcome, source_hash, artifact_hash,
              config_hash, evaluated_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (evaluation_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, normalized.evaluationId)
            ps.setString(2, normalized.windowId)
            ps.setString(3, normalized.botId)
            ps.setString(4, normalized.versionId)
            ps.setString(5, normalized.outcome.dbValue)
            ps.setString(6, normalized.sourceHash)
            ps.setString(7, normalized.artifactHash)
            ps.setString(8, normalized.configHash)
            ps.setTimestamp(9, Timestamp.from(normalized.evaluatedAt))
            ps.setString(10, normalized.correlationId)
            ps.executeUpdate() == 1
        }
        if (inserted) {
            conn.prepareStatement(
                "INSERT INTO ${names.eligibilityDecisionReasons}(evaluation_id, reason_order, reason_code) VALUES (?, ?, ?)"
            ).use { ps ->
                normalized.reasons.forEachIndexed { index, reason ->
                    ps.setString(1, normalized.evaluationId)
                    ps.setInt(2, index)
                    ps.setString(3, reason.code)
                    ps.addBatch()
                }
                if (normalized.reasons.isNotEmpty()) ps.executeBatch()
            }
        }
        val stored = requireNotNull(decision(conn, normalized.evaluationId)) { "eligibility decision was not persisted" }
        require(stored == normalized) { "eligibility evaluation is immutable: ${normalized.evaluationId}" }
        stored
    }

    override fun decisions(windowId: String): List<ArenaRunEligibilityDecision> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT evaluation_id FROM ${names.eligibilityDecisions}
            WHERE window_id = ?
            ORDER BY evaluated_at, bot_id, version_id, evaluation_id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, windowId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }.map { requireNotNull(decision(conn, it)) }
    }

    override fun lockRoster(snapshot: ArenaRosterSnapshot): ArenaRosterSnapshot = transaction { conn ->
        val normalized = snapshot.copy(lockedAt = snapshot.lockedAt.postgresInstant())
        val inserted = conn.prepareStatement(
            """
            INSERT INTO ${names.rosterSnapshots}(
              snapshot_id, window_id, mode_id, scenario_id, seed_set_hash,
              actor_profile_version, actor_profile_hash, risk_policy_version, risk_policy_hash,
              scoring_policy_version, scoring_policy_hash, economic_policy_version, economic_policy_hash,
              snapshot_hash, max_bots, locked_at, locked_by, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (window_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            val p = normalized.policy
            listOf(
                normalized.snapshotId, normalized.windowId, p.modeId, p.scenarioId, p.seedSetHash,
                p.actorProfileVersion, p.actorProfileHash, p.riskPolicyVersion, p.riskPolicyHash,
                p.scoringPolicyVersion, p.scoringPolicyHash, p.economicPolicyVersion, p.economicPolicyHash,
                normalized.snapshotHash
            ).forEachIndexed { index, value -> ps.setString(index + 1, value) }
            ps.setInt(15, normalized.maxBots)
            ps.setTimestamp(16, Timestamp.from(normalized.lockedAt))
            ps.setString(17, normalized.lockedBy)
            ps.setString(18, normalized.correlationId)
            ps.executeUpdate() == 1
        }
        if (inserted) {
            conn.prepareStatement(
                """
                INSERT INTO ${names.rosterSnapshotEntries}(
                  snapshot_id, bot_order, bot_id, version_id, priority, source_hash,
                  artifact_hash, config_hash, eligibility_evaluation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                normalized.entries.forEach { entry ->
                    ps.setString(1, normalized.snapshotId)
                    ps.setInt(2, entry.botOrder)
                    ps.setString(3, entry.botId)
                    ps.setString(4, entry.versionId)
                    ps.setInt(5, entry.priority)
                    ps.setString(6, entry.sourceHash)
                    ps.setString(7, entry.artifactHash)
                    ps.setString(8, entry.configHash)
                    ps.setString(9, entry.eligibilityEvaluationId)
                    ps.addBatch()
                }
                if (normalized.entries.isNotEmpty()) ps.executeBatch()
            }
        }
        val stored = requireNotNull(roster(conn, normalized.windowId)) { "roster snapshot was not persisted" }
        require(stored == normalized) { "roster snapshot is immutable for window: ${normalized.windowId}" }
        stored
    }

    override fun roster(windowId: String): ArenaRosterSnapshot? = connection().use { roster(it, windowId) }

    override fun recordRemoval(removal: ArenaRosterRemoval): ArenaRosterRemoval = transaction { conn ->
        val normalized = removal.copy(removedAt = removal.removedAt.postgresInstant())
        val sameTarget = removals(conn, normalized.windowId).firstOrNull {
            it.snapshotId == normalized.snapshotId && it.botId == normalized.botId && it.versionId == normalized.versionId
        }
        require(sameTarget == null || sameTarget == normalized) { "roster entry is already removed" }
        conn.prepareStatement(
            """
            INSERT INTO ${names.rosterRemovals}(
              removal_id, window_id, snapshot_id, bot_id, version_id, reason_code,
              detail, removed_at, removed_by, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (removal_id) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, normalized.removalId)
            ps.setString(2, normalized.windowId)
            ps.setString(3, normalized.snapshotId)
            ps.setString(4, normalized.botId)
            ps.setString(5, normalized.versionId)
            ps.setString(6, normalized.reason.code)
            ps.setString(7, normalized.detail)
            ps.setTimestamp(8, Timestamp.from(normalized.removedAt))
            ps.setString(9, normalized.removedBy)
            ps.setString(10, normalized.correlationId)
            ps.executeUpdate()
        }
        val stored = removals(conn, normalized.windowId).firstOrNull { it.removalId == normalized.removalId }
        require(stored == normalized) { "roster removal is immutable: ${normalized.removalId}" }
        stored
    }

    override fun removals(windowId: String): List<ArenaRosterRemoval> = connection().use { removals(it, windowId) }

    private fun window(conn: Connection, windowId: String): ArenaAdmissionWindow? =
        conn.prepareStatement("SELECT * FROM ${names.admissionWindows} WHERE window_id = ?").use { ps ->
            ps.setString(1, windowId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toWindow() else null }
        }

    private fun decision(conn: Connection, evaluationId: String): ArenaRunEligibilityDecision? {
        val reasons = conn.prepareStatement(
            "SELECT reason_code FROM ${names.eligibilityDecisionReasons} WHERE evaluation_id = ? ORDER BY reason_order"
        ).use { ps ->
            ps.setString(1, evaluationId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(ArenaEligibilityReason.fromCode(rs.getString(1))) } }
        }
        return conn.prepareStatement("SELECT * FROM ${names.eligibilityDecisions} WHERE evaluation_id = ?").use { ps ->
            ps.setString(1, evaluationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toDecision(reasons) else null }
        }
    }

    private fun roster(conn: Connection, windowId: String): ArenaRosterSnapshot? {
        val row = conn.prepareStatement("SELECT * FROM ${names.rosterSnapshots} WHERE window_id = ?").use { ps ->
            ps.setString(1, windowId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRoster(emptyList()) else null }
        } ?: return null
        val entries = conn.prepareStatement(
            "SELECT * FROM ${names.rosterSnapshotEntries} WHERE snapshot_id = ? ORDER BY bot_order"
        ).use { ps ->
            ps.setString(1, row.snapshotId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRosterEntry()) } }
        }
        return row.copy(entries = entries)
    }

    private fun removals(conn: Connection, windowId: String): List<ArenaRosterRemoval> =
        conn.prepareStatement(
            "SELECT * FROM ${names.rosterRemovals} WHERE window_id = ? ORDER BY removed_at, bot_id, version_id"
        ).use { ps ->
            ps.setString(1, windowId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRosterRemoval()) } }
        }

    private fun createSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${names.admissionWindows} (
                  window_id TEXT PRIMARY KEY, policy_version TEXT NOT NULL, scheduled_start TIMESTAMPTZ NOT NULL,
                  invite_decision_cutoff TIMESTAMPTZ NOT NULL, merge_readiness_cutoff TIMESTAMPTZ NOT NULL,
                  roster_lock_at TIMESTAMPTZ NOT NULL, operational_recheck_at TIMESTAMPTZ NOT NULL,
                  run_instantiation_at TIMESTAMPTZ NOT NULL, display_time_zone TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${names.eligibilityDecisions} (
                  evaluation_id TEXT PRIMARY KEY, window_id TEXT NOT NULL REFERENCES ${names.admissionWindows}(window_id),
                  bot_id TEXT NOT NULL, version_id TEXT NOT NULL, outcome TEXT NOT NULL,
                  source_hash TEXT NOT NULL, artifact_hash TEXT NOT NULL, config_hash TEXT NOT NULL,
                  evaluated_at TIMESTAMPTZ NOT NULL, correlation_id TEXT NOT NULL
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${names.eligibilityDecisionReasons} (
                  evaluation_id TEXT NOT NULL REFERENCES ${names.eligibilityDecisions}(evaluation_id),
                  reason_order INTEGER NOT NULL, reason_code TEXT NOT NULL, PRIMARY KEY (evaluation_id, reason_order)
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${names.rosterSnapshots} (
                  snapshot_id TEXT PRIMARY KEY, window_id TEXT NOT NULL UNIQUE REFERENCES ${names.admissionWindows}(window_id),
                  mode_id TEXT NOT NULL, scenario_id TEXT NOT NULL, seed_set_hash TEXT NOT NULL,
                  actor_profile_version TEXT NOT NULL, actor_profile_hash TEXT NOT NULL,
                  risk_policy_version TEXT NOT NULL, risk_policy_hash TEXT NOT NULL,
                  scoring_policy_version TEXT NOT NULL, scoring_policy_hash TEXT NOT NULL,
                  economic_policy_version TEXT NOT NULL, economic_policy_hash TEXT NOT NULL,
                  snapshot_hash TEXT NOT NULL, max_bots INTEGER NOT NULL, locked_at TIMESTAMPTZ NOT NULL, locked_by TEXT NOT NULL,
                  correlation_id TEXT NOT NULL
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${names.rosterSnapshotEntries} (
                  snapshot_id TEXT NOT NULL REFERENCES ${names.rosterSnapshots}(snapshot_id), bot_order INTEGER NOT NULL,
                  bot_id TEXT NOT NULL, version_id TEXT NOT NULL, priority INTEGER NOT NULL, source_hash TEXT NOT NULL,
                  artifact_hash TEXT NOT NULL, config_hash TEXT NOT NULL,
                  eligibility_evaluation_id TEXT NOT NULL REFERENCES ${names.eligibilityDecisions}(evaluation_id),
                  PRIMARY KEY (snapshot_id, bot_order), UNIQUE (snapshot_id, bot_id, version_id)
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${names.rosterRemovals} (
                  removal_id TEXT PRIMARY KEY, window_id TEXT NOT NULL REFERENCES ${names.admissionWindows}(window_id),
                  snapshot_id TEXT NOT NULL REFERENCES ${names.rosterSnapshots}(snapshot_id), bot_id TEXT NOT NULL,
                  version_id TEXT NOT NULL, reason_code TEXT NOT NULL, detail TEXT NOT NULL,
                  removed_at TIMESTAMPTZ NOT NULL, removed_by TEXT NOT NULL, correlation_id TEXT NOT NULL,
                  UNIQUE (snapshot_id, bot_id, version_id),
                  FOREIGN KEY (snapshot_id, bot_id, version_id)
                    REFERENCES ${names.rosterSnapshotEntries}(snapshot_id, bot_id, version_id)
                )
            """.trimIndent())
        }
    }

    private fun ResultSet.toWindow() = ArenaAdmissionWindow(
        getString("window_id"), getString("policy_version"), getTimestamp("scheduled_start").toInstant(),
        getTimestamp("invite_decision_cutoff").toInstant(), getTimestamp("merge_readiness_cutoff").toInstant(),
        getTimestamp("roster_lock_at").toInstant(), getTimestamp("operational_recheck_at").toInstant(),
        getTimestamp("run_instantiation_at").toInstant(), getString("display_time_zone"), getTimestamp("created_at").toInstant()
    )

    private fun ResultSet.toDecision(reasons: List<ArenaEligibilityReason>) = ArenaRunEligibilityDecision(
        getString("evaluation_id"), getString("window_id"), getString("bot_id"), getString("version_id"),
        ArenaEligibilityOutcome.fromDb(getString("outcome")), reasons,
        getString("source_hash"), getString("artifact_hash"), getString("config_hash"),
        getTimestamp("evaluated_at").toInstant(),
        getString("correlation_id")
    )

    private fun ResultSet.toRoster(entries: List<ArenaRosterEntry>) = ArenaRosterSnapshot(
        snapshotId = getString("snapshot_id"),
        windowId = getString("window_id"),
        policy = ArenaRosterPolicySnapshot(
            getString("mode_id"), getString("scenario_id"), getString("seed_set_hash"),
            getString("actor_profile_version"), getString("actor_profile_hash"),
            getString("risk_policy_version"), getString("risk_policy_hash"),
            getString("scoring_policy_version"), getString("scoring_policy_hash"),
            getString("economic_policy_version"), getString("economic_policy_hash")
        ),
        maxBots = getInt("max_bots"),
        entries = entries,
        snapshotHash = getString("snapshot_hash"),
        lockedAt = getTimestamp("locked_at").toInstant(),
        lockedBy = getString("locked_by"),
        correlationId = getString("correlation_id")
    )

    private fun ResultSet.toRosterEntry() = ArenaRosterEntry(
        getInt("bot_order"), getString("bot_id"), getString("version_id"), getInt("priority"),
        getString("source_hash"), getString("artifact_hash"), getString("config_hash"),
        getString("eligibility_evaluation_id")
    )

    private fun ResultSet.toRosterRemoval() = ArenaRosterRemoval(
        removalId = getString("removal_id"),
        windowId = getString("window_id"),
        snapshotId = getString("snapshot_id"),
        botId = getString("bot_id"),
        versionId = getString("version_id"),
        reason = ArenaRosterRemovalReason.fromCode(getString("reason_code")),
        detail = getString("detail"),
        removedAt = getTimestamp("removed_at").toInstant(),
        removedBy = getString("removed_by"),
        correlationId = getString("correlation_id")
    )

    private fun connection(): Connection = dataSource.connection

    private inline fun <T> transaction(block: (Connection) -> T): T = connection().use { conn ->
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (ex: Exception) {
            conn.rollback()
            throw ex
        }
    }

    private fun ArenaAdmissionWindow.normalizedForPostgres() = copy(
        scheduledStart = scheduledStart.postgresInstant(),
        inviteDecisionCutoff = inviteDecisionCutoff.postgresInstant(),
        mergeReadinessCutoff = mergeReadinessCutoff.postgresInstant(),
        rosterLockAt = rosterLockAt.postgresInstant(),
        operationalRecheckAt = operationalRecheckAt.postgresInstant(),
        runInstantiationAt = runInstantiationAt.postgresInstant(),
        createdAt = createdAt.postgresInstant()
    )

    private fun Instant.postgresInstant(): Instant = truncatedTo(ChronoUnit.MICROS)
}
