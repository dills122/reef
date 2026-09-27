package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlement.BuiltInPostTradeProfiles
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.PostTradeProfileSelection
import com.reef.platform.domain.PostTradeProfile
import java.sql.Connection
import javax.sql.DataSource

data class SettlementPolicyKey(val runId: String, val venueSessionId: String)

data class SettlementPolicySnapshot(
    val selection: PostTradeProfileSelection,
    val settlementCycle: String,
    val nettingMode: String,
    val ledgerPostingMode: String
)

/** Batch, keyed policy lookup from the control-plane authority, never a run-wide trade scan. */
class SettlementObligationPolicySource(
    private val source: DataSource,
    private val environmentProfileId: String = "",
    private val environmentPolicyVersion: Int = 1
) {
    fun resolve(keys: Set<SettlementPolicyKey>): Map<SettlementPolicyKey, SettlementPolicySnapshot> {
        require(keys.size <= 20_000) { "settlement policy lookup must be bounded" }
        if (keys.isEmpty()) return emptyMap()
        return source.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            val previousIsolation = connection.transactionIsolation
            check(previousAutoCommit) { "settlement policy lookup requires an idle connection" }
            try {
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                connection.autoCommit = false
                val runProfiles = profileAssignments(
                    connection, "runtime.reference_scenario_runs", "scenario_run_id",
                    keys.map { it.runId }.filter { it.isNotBlank() }.toSet()
                )
                val sessionProfiles = profileAssignments(
                    connection, "runtime.reference_venue_sessions", "venue_session_id",
                    keys.map { it.venueSessionId }.filter { it.isNotBlank() }.toSet()
                )
                val neededProfiles = (runProfiles.values + sessionProfiles.values + environmentProfileId)
                    .filter { it.isNotBlank() }.toSet()
                val profiles = profiles(connection, neededProfiles)
                val resolver = PostTradeProfileResolver(
                    profiles = { profiles },
                    activePlatformProfile = { profiles.singleOrNull { it.active } },
                    environmentProfileId = { environmentProfileId },
                    environmentPolicyVersion = { environmentPolicyVersion }
                )
                val definitions = (BuiltInPostTradeProfiles + profiles).associateBy { it.profileId }
                val result = keys.associateWith { key ->
                    val selection = resolver.resolve(
                        scenarioRunProfileId = runProfiles[key.runId].orEmpty(),
                        venueSessionProfileId = sessionProfiles[key.venueSessionId].orEmpty()
                    )
                    val profile = definitions[selection.profileId]
                        ?: error("settlement policy definition is missing for ${selection.profileId}")
                    SettlementPolicySnapshot(selection, profile.settlementCycle,
                        profile.nettingMode, profile.ledgerPostingMode)
                }
                connection.commit()
                result
            } catch (error: Throwable) {
                if (!connection.autoCommit) connection.rollback()
                throw error
            } finally {
                connection.transactionIsolation = previousIsolation
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun profileAssignments(
        connection: Connection, table: String, idColumn: String, ids: Set<String>
    ): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return connection.prepareStatement(
            "SELECT $idColumn, post_trade_profile_id FROM $table WHERE $idColumn = ANY(?)"
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("text", ids.toTypedArray()))
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) check(put(rows.getString(1), rows.getString(2)) == null) {
                        "settlement policy assignment is duplicated"
                    }
                }
            }
        }
    }

    private fun profiles(connection: Connection, ids: Set<String>): List<PostTradeProfile> =
        connection.prepareStatement(
            """SELECT profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode,
                      policy_version, active
               FROM admin.post_trade_profiles WHERE active OR profile_id = ANY(?)"""
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("text", ids.toTypedArray()))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(PostTradeProfile(
                        profileId = rows.getString(1), mode = rows.getString(2),
                        settlementCycle = rows.getString(3), nettingMode = rows.getString(4),
                        ledgerPostingMode = rows.getString(5), policyVersion = rows.getInt(6),
                        active = rows.getBoolean(7)
                    ))
                }
            }
        }
}
