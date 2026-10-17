package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.validQuoteCurrency
import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.RuntimeOrderIdentity
import com.reef.platform.domain.Account
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.IntradayBar
import com.reef.platform.domain.NonLifecycleRejectCodes
import com.reef.platform.domain.OwnExecutionView
import com.reef.platform.domain.OwnOrderView
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PublicTradeTapeEntry
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.VenueSessionPostTradeProfile
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

data class ProjectionPersistenceRetryStats(
    val retryAttempts: Long,
    val retryExhausted: Long,
    val lastRetryAt: String,
    val lastRetrySqlState: String,
    val lastRetryError: String
)

object ProjectionPersistenceRetryMetrics {
    private val retryAttempts = AtomicLong(0)
    private val retryExhausted = AtomicLong(0)
    private val lastRetryAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastRetrySqlState: String = ""
    @Volatile
    private var lastRetryError: String = ""

    fun recordRetry(sqlState: String, error: String) {
        retryAttempts.incrementAndGet()
        recordLastRetry(sqlState, error)
    }

    fun recordRetryExhausted(sqlState: String, error: String) {
        retryExhausted.incrementAndGet()
        recordLastRetry(sqlState, error)
    }

    fun snapshot(): ProjectionPersistenceRetryStats {
        return ProjectionPersistenceRetryStats(
            retryAttempts = retryAttempts.get(),
            retryExhausted = retryExhausted.get(),
            lastRetryAt = instantString(lastRetryAtEpochMs.get()),
            lastRetrySqlState = lastRetrySqlState,
            lastRetryError = lastRetryError
        )
    }

    fun resetForTests() {
        retryAttempts.set(0)
        retryExhausted.set(0)
        lastRetryAtEpochMs.set(0)
        lastRetrySqlState = ""
        lastRetryError = ""
    }

    private fun recordLastRetry(sqlState: String, error: String) {
        lastRetryAtEpochMs.set(System.currentTimeMillis())
        lastRetrySqlState = sqlState
        lastRetryError = error
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

class PostgresRuntimePersistence(
    private val dataSource: DataSource,
    private val names: PostgresRuntimeSqlNames = PostgresRuntimeSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val projectionDataSource: DataSource = dataSource,
    private val envLookup: (String) -> String? = { key -> System.getenv(key) }
) : RuntimePersistence {
    private val intradayBarIntervalText = mapOf(
        "1m" to "1 minute",
        "5m" to "5 minutes",
        "15m" to "15 minutes",
        "1h" to "1 hour"
    )

    private val streamAckCanonicalEventRowsEnabled: Boolean =
        RuntimeEnv.bool("STREAM_ACK_CANONICAL_EVENT_ROWS_ENABLED", true)
    private val streamAckCanonicalQueryIndexesEnabled: Boolean =
        RuntimeEnv.bool("STREAM_ACK_CANONICAL_QUERY_INDEXES_ENABLED", true)
    private val projectorDbRetryAttempts: Int =
        RuntimeEnv.int("STREAM_ACK_PROJECTOR_DB_RETRY_ATTEMPTS", 3, min = 1, lookup = envLookup)
    private val projectorDbRetryBackoffMs: Long =
        RuntimeEnv.long("STREAM_ACK_PROJECTOR_DB_RETRY_BACKOFF_MS", 25L, min = 0L, lookup = envLookup)
    private val projectorDbRetryHorizonMs: Long =
        RuntimeEnv.long("STREAM_ACK_PROJECTOR_DB_RETRY_HORIZON_MS", 60_000L, min = 1L, lookup = envLookup)

    @Volatile
    private var commandPayloadSideTableAvailableCache: Boolean? = null

    private data class ProjectionCandidate(
        val partitionId: Int,
        val partitionSequence: Long,
        val resultPayload: String,
        val partitionRow: Int = 0
    )

    private data class CommandProjectionCandidate(
        val partitionId: Int,
        val partitionSequence: Long,
        val outcome: CanonicalCommandOutcome,
        val commandPayloadJson: String = "{}",
        val partitionRow: Int = 0
    )

    private data class ProjectionBatchClaimResult(
        val isNew: Boolean,
        val storedResultCount: Long?
    )

    private data class ClaimedProjectionTransactionResult(
        val resultCount: Long,
        val appliedNow: Boolean
    )

    private companion object {
        const val StreamSequencePartitionShift = 48
        val projectorRowsBeforeWatermarkFailureInjected = AtomicBoolean(false)
        const val OrderSelectColumns =
            "order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, " +
                "quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id"
    }

    init {
        PostgresRuntimeCompatBootstrap.run(
            dataSource = dataSource,
            projectionDataSource = projectionDataSource,
            names = names,
            bootstrapMode = bootstrapMode,
            streamAckCanonicalQueryIndexesEnabled = streamAckCanonicalQueryIndexesEnabled,
            streamAckCanonicalEventRowsEnabled = streamAckCanonicalEventRowsEnabled
        )
    }

    override fun saveSubmitResult(commandId: String, result: SubmitOrderResult) {
        val accepted = result.accepted
        val rejected = result.rejected
        val resultType = if (accepted != null) "accepted" else "rejected"
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.submitResults}(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at, cancelled, matching_facts, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (command_id) DO UPDATE SET
                  result_type = EXCLUDED.result_type,
                  event_id = EXCLUDED.event_id,
                  order_id = EXCLUDED.order_id,
                  engine_order_id = EXCLUDED.engine_order_id,
                  code = EXCLUDED.code,
                  reason = EXCLUDED.reason,
                  occurred_at = EXCLUDED.occurred_at,
                  cancelled = EXCLUDED.cancelled,
                  matching_facts = EXCLUDED.matching_facts,
                  run_id = EXCLUDED.run_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setString(2, resultType)
                ps.setString(3, accepted?.eventId ?: rejected?.eventId.orEmpty())
                ps.setString(4, accepted?.orderId ?: rejected?.orderId.orEmpty())
                ps.setString(5, accepted?.engineOrderId.orEmpty())
                ps.setString(6, rejected?.code.orEmpty())
                ps.setString(7, rejected?.reason.orEmpty())
                ps.setString(8, accepted?.occurredAt ?: rejected?.occurredAt.orEmpty())
                ps.setString(9, result.cancelled?.toJsonObject())
                ps.setString(10, result.matchingFactsJson())
                ps.setString(11, result.executions.firstOrNull()?.runId ?: result.trades.firstOrNull()?.runId.orEmpty())
                ps.executeUpdate()
            }
        }
    }

    override fun submitResult(commandId: String): SubmitOrderResult? {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                "SELECT run_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at, cancelled::TEXT AS cancellation_json, matching_facts::TEXT AS matching_facts_json FROM ${names.submitResults} WHERE command_id = ?"
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return canonicalSubmitResult(commandId)
                    val resultType = rs.getString("result_type")
                    val orderId = rs.getString("order_id")
                    return if (resultType == "accepted") {
                        SubmitOrderResult(
                            cancelled = rs.getString("cancellation_json")?.let { cancellationFromResultPayload("{\"cancelled\":$it}") },
                            accepted = EngineOrderAccepted(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                engineOrderId = rs.getString("engine_order_id"),
                                occurredAt = rs.getString("occurred_at")
                            ),
                            executions = rs.getString("matching_facts_json")?.let { executionsFromResultPayload(it).map { fact -> fact.copy(runId = rs.getString("run_id")) } } ?: executionsForOrder(RuntimeOrderIdentity(rs.getString("run_id"), orderId)),
                            trades = rs.getString("matching_facts_json")?.let { tradesFromResultPayload(it).map { fact -> fact.copy(runId = rs.getString("run_id")) } } ?: tradesForOrder(RuntimeOrderIdentity(rs.getString("run_id"), orderId))
                        )
                    } else {
                        SubmitOrderResult(
                            rejected = EngineOrderRejected(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                code = rs.getString("code"),
                                reason = rs.getString("reason"),
                                occurredAt = rs.getString("occurred_at")
                            )
                        )
                    }
                }
            }
        }
    }

    private fun canonicalSubmitResult(commandId: String): SubmitOrderResult? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  run_id,
                  result_payload->>'resultType' AS result_type,
                  result_payload->>'eventId' AS event_id,
                  result_payload->>'orderId' AS order_id,
                  result_payload->>'engineOrderId' AS engine_order_id,
                  result_payload->>'code' AS code,
                  result_payload->>'reason' AS reason,
                  result_payload->>'occurredAt' AS occurred_at,
                  (result_payload->'cancelled')::TEXT AS cancellation_json,
                  result_payload::TEXT AS matching_facts_json
                FROM ${names.canonicalCommandResults}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val resultType = rs.getString("result_type")
                    val orderId = rs.getString("order_id")
                    return if (resultType == "accepted") {
                        SubmitOrderResult(
                            cancelled = rs.getString("cancellation_json")?.let { cancellationFromResultPayload("{\"cancelled\":$it}") },
                            accepted = EngineOrderAccepted(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                engineOrderId = rs.getString("engine_order_id"),
                                occurredAt = rs.getString("occurred_at")
                            ),
                            executions = rs.getString("matching_facts_json")?.let { executionsFromResultPayload(it).map { fact -> fact.copy(runId = rs.getString("run_id")) } } ?: executionsForOrder(RuntimeOrderIdentity(rs.getString("run_id"), orderId)),
                            trades = rs.getString("matching_facts_json")?.let { tradesFromResultPayload(it).map { fact -> fact.copy(runId = rs.getString("run_id")) } } ?: tradesForOrder(RuntimeOrderIdentity(rs.getString("run_id"), orderId))
                        )
                    } else {
                        SubmitOrderResult(
                            rejected = EngineOrderRejected(
                                eventId = rs.getString("event_id"),
                                orderId = orderId,
                                code = rs.getString("code"),
                                reason = rs.getString("reason"),
                                occurredAt = rs.getString("occurred_at")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun saveInstrument(instrument: Instrument) {
        require(validQuoteCurrency(instrument.quoteCurrency)) { "invalid instrument quote currency" }
        connection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO ${names.referenceInstruments}(instrument_id, symbol, quote_currency) VALUES (?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET symbol = EXCLUDED.symbol
                WHERE ${names.referenceInstruments}.quote_currency = EXCLUDED.quote_currency
            """.trimIndent()).use { ps ->
                ps.setString(1, instrument.instrumentId)
                ps.setString(2, instrument.symbol)
                ps.setString(3, instrument.quoteCurrency)
                require(ps.executeUpdate() == 1) { "instrument quote currency is immutable" }
            }
        }
    }

    override fun saveParticipant(participant: Participant) {
        upsert("${names.referenceParticipants}", "participant_id", participant.participantId, participant.name)
    }

    override fun saveAccount(account: Account) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.referenceAccounts}(account_id, participant_id)
                VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET participant_id = EXCLUDED.participant_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, account.accountId)
                ps.setString(2, account.participantId)
                ps.executeUpdate()
            }
        }
    }

    override fun saveRole(role: RoleDefinition) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.authRoles}(role_id, permissions)
                VALUES (?, ?)
                ON CONFLICT (role_id) DO UPDATE SET permissions = EXCLUDED.permissions
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, role.roleId)
                ps.setString(2, role.permissions.joinToString(","))
                ps.executeUpdate()
            }
        }
    }

    override fun saveActorRoleBinding(binding: ActorRoleBinding) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.authActorRoles}(actor_id, role_id)
                VALUES (?, ?)
                ON CONFLICT (actor_id, role_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, binding.actorId)
                ps.setString(2, binding.roleId)
                ps.executeUpdate()
            }
        }
    }

    override fun savePostTradeProfile(profile: PostTradeProfile) {
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                if (profile.active) {
                    conn.prepareStatement("UPDATE ${names.adminPostTradeProfiles} SET active = false WHERE active").use { it.executeUpdate() }
                }
                conn.prepareStatement(
                    """
                    INSERT INTO ${names.adminPostTradeProfiles}(
                      profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode, policy_version, active, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (profile_id) DO UPDATE SET
                      mode = EXCLUDED.mode,
                      settlement_cycle = EXCLUDED.settlement_cycle,
                      netting_mode = EXCLUDED.netting_mode,
                      ledger_posting_mode = EXCLUDED.ledger_posting_mode,
                      policy_version = EXCLUDED.policy_version,
                      active = EXCLUDED.active,
                      updated_at = now()
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, profile.profileId)
                    ps.setString(2, profile.mode)
                    ps.setString(3, profile.settlementCycle)
                    ps.setString(4, profile.nettingMode)
                    ps.setString(5, profile.ledgerPostingMode)
                    ps.setInt(6, profile.policyVersion)
                    ps.setBoolean(7, profile.active)
                    ps.executeUpdate()
                }
                if (!profile.active && !hasActivePostTradeProfile(conn)) {
                    conn.prepareStatement(
                        "UPDATE ${names.adminPostTradeProfiles} SET active = true WHERE profile_id = ?"
                    ).use { ps ->
                        ps.setString(1, profile.profileId)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun postTradeProfiles(): List<PostTradeProfile> {
        return queryList(
            """
            SELECT profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode, policy_version, active
            FROM ${names.adminPostTradeProfiles}
            ORDER BY profile_id
            """.trimIndent()
        ) {
            toPostTradeProfile()
        }
    }

    override fun activePostTradeProfile(): PostTradeProfile {
        return postTradeProfiles().firstOrNull { it.active }
            ?: throw IllegalArgumentException("no active post-trade profile")
    }

    override fun activatePostTradeProfile(profileId: String): PostTradeProfile {
        connection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val profile = queryPostTradeProfile(conn, profileId)
                    ?: throw IllegalArgumentException("unknown post-trade profile '$profileId'")
                conn.prepareStatement("UPDATE ${names.adminPostTradeProfiles} SET active = false WHERE active").use { it.executeUpdate() }
                conn.prepareStatement(
                    "UPDATE ${names.adminPostTradeProfiles} SET active = true, updated_at = now() WHERE profile_id = ?"
                ).use { ps ->
                    ps.setString(1, profileId)
                    ps.executeUpdate()
                }
                conn.commit()
                return profile.copy(active = true)
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun saveScenarioRunPostTradeProfile(config: ScenarioRunPostTradeProfile) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.referenceScenarioRuns}(scenario_run_id, post_trade_profile_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (scenario_run_id) DO UPDATE SET
                  post_trade_profile_id = EXCLUDED.post_trade_profile_id,
                  updated_at = now()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, config.scenarioRunId)
                ps.setString(2, config.postTradeProfileId)
                ps.executeUpdate()
            }
        }
    }

    override fun scenarioRunPostTradeProfileId(scenarioRunId: String): String? {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT post_trade_profile_id FROM ${names.referenceScenarioRuns} WHERE scenario_run_id = ?"
            ).use { ps ->
                ps.setString(1, scenarioRunId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("post_trade_profile_id") else null
                }
            }
        }
    }

    override fun scenarioRunPostTradeProfiles(): List<ScenarioRunPostTradeProfile> {
        return queryList(
            """
            SELECT scenario_run_id, post_trade_profile_id
            FROM ${names.referenceScenarioRuns}
            ORDER BY scenario_run_id
            """.trimIndent()
        ) {
            ScenarioRunPostTradeProfile(
                scenarioRunId = getString("scenario_run_id"),
                postTradeProfileId = getString("post_trade_profile_id")
            )
        }
    }

    override fun saveVenueSessionPostTradeProfile(config: VenueSessionPostTradeProfile) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.referenceVenueSessions}(venue_session_id, post_trade_profile_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (venue_session_id) DO UPDATE SET
                  post_trade_profile_id = EXCLUDED.post_trade_profile_id,
                  updated_at = now()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, config.venueSessionId)
                ps.setString(2, config.postTradeProfileId)
                ps.executeUpdate()
            }
        }
    }

    override fun venueSessionPostTradeProfileId(venueSessionId: String): String? {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT post_trade_profile_id FROM ${names.referenceVenueSessions} WHERE venue_session_id = ?"
            ).use { ps ->
                ps.setString(1, venueSessionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("post_trade_profile_id") else null
                }
            }
        }
    }

    override fun venueSessionPostTradeProfiles(): List<VenueSessionPostTradeProfile> {
        return queryList(
            """
            SELECT venue_session_id, post_trade_profile_id
            FROM ${names.referenceVenueSessions}
            ORDER BY venue_session_id
            """.trimIndent()
        ) {
            VenueSessionPostTradeProfile(
                venueSessionId = getString("venue_session_id"),
                postTradeProfileId = getString("post_trade_profile_id")
            )
        }
    }

    private fun queryPostTradeProfile(conn: Connection, profileId: String): PostTradeProfile? {
        conn.prepareStatement(
            """
            SELECT profile_id, mode, settlement_cycle, netting_mode, ledger_posting_mode, policy_version, active
            FROM ${names.adminPostTradeProfiles}
            WHERE profile_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, profileId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toPostTradeProfile() else null
            }
        }
    }

    private fun hasActivePostTradeProfile(conn: Connection): Boolean {
        conn.prepareStatement("SELECT 1 FROM ${names.adminPostTradeProfiles} WHERE active LIMIT 1").use { ps ->
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun ResultSet.toPostTradeProfile(): PostTradeProfile {
        return PostTradeProfile(
            profileId = getString("profile_id"),
            mode = getString("mode"),
            settlementCycle = getString("settlement_cycle"),
            nettingMode = getString("netting_mode"),
            ledgerPostingMode = getString("ledger_posting_mode"),
            policyVersion = getInt("policy_version"),
            active = getBoolean("active")
        )
    }

    override fun instruments(): List<Instrument> = queryList("SELECT instrument_id, symbol, quote_currency FROM ${names.referenceInstruments}") {
        Instrument(getString("instrument_id"), getString("symbol"), getString("quote_currency"))
    }

    override fun participants(): List<Participant> = queryList("SELECT participant_id, name FROM ${names.referenceParticipants}") {
        Participant(getString("participant_id"), getString("name"))
    }

    override fun accounts(): List<Account> = queryList("SELECT account_id, participant_id FROM ${names.referenceAccounts}") {
        Account(getString("account_id"), getString("participant_id"))
    }

    override fun roles(): List<RoleDefinition> = queryList("SELECT role_id, permissions FROM ${names.authRoles}") {
        RoleDefinition(
            roleId = getString("role_id"),
            permissions = getString("permissions").split(",").filter { it.isNotBlank() }
        )
    }

    override fun actorRoleBindings(actorId: String): List<ActorRoleBinding> {
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT actor_id, role_id FROM ${names.authActorRoles} WHERE actor_id = ?"
            ).use { ps ->
                ps.setString(1, actorId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ActorRoleBinding>()
                    while (rs.next()) {
                        out.add(
                            ActorRoleBinding(
                                actorId = rs.getString("actor_id"),
                                roleId = rs.getString("role_id")
                            )
                        )
                    }
                    return out
                }
            }
        }
    }

    override fun hasInstrument(instrumentId: String): Boolean = exists("${names.referenceInstruments}", "instrument_id", instrumentId)

    override fun hasParticipant(participantId: String): Boolean = exists("${names.referenceParticipants}", "participant_id", participantId)

    override fun hasAccount(accountId: String): Boolean = exists("${names.referenceAccounts}", "account_id", accountId)

    override fun validateReferenceData(instrumentId: String, participantId: String, accountId: String): ReferenceDataValidation {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT instrument_exists, participant_exists, account_exists, account_belongs_to_participant, i.quote_currency
                FROM ${names.validateReferenceDataFunction}(?, ?, ?)
                LEFT JOIN ${names.referenceInstruments} i ON i.instrument_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.setString(2, participantId)
                ps.setString(3, accountId)
                ps.setString(4, instrumentId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return ReferenceDataValidation(
                        instrumentExists = rs.getBoolean("instrument_exists"),
                        participantExists = rs.getBoolean("participant_exists"),
                        accountExists = rs.getBoolean("account_exists"),
                        accountBelongsToParticipant = rs.getBoolean("account_belongs_to_participant"),
                        instrumentQuoteCurrency = rs.getString("quote_currency")
                    )
                }
            }
        }
    }

    override fun persistSubmitOutcome(outcome: PersistableSubmitOutcome) {
        persistSubmitOutcomes(listOf(outcome))
    }

    override fun persistSubmitOutcome(
        commandId: String,
        result: SubmitOrderResult,
        acceptedOrder: PersistedOrder?,
        lifecycleEvents: List<RuntimeEvent>
    ) {
        // Keep sync-result writes on the same scope- and role-preserving path as projector batches.
        persistSubmitOutcomes(
            listOf(
                PersistableSubmitOutcome(
                    commandId = commandId,
                    result = result,
                    acceptedOrder = acceptedOrder,
                    lifecycleEvents = lifecycleEvents
                )
            )
        )
    }

    override fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {
        if (outcomes.isEmpty()) return
        projectionConnection().use { conn ->
            persistSubmitOutcomePayloads(conn, outcomes.toJsonArray { it.toJsonObject() }, outcomes.size)
        }
    }

    override fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        if (outcomes.isEmpty()) return
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                "SELECT set_config('reef.stream_ack_canonical_event_rows_enabled', ?, FALSE)"
            ).use { ps ->
                ps.setString(1, streamAckCanonicalEventRowsEnabled.toString())
                ps.execute()
            }
            conn.prepareStatement(
                """
                SELECT ${names.appendCanonicalSubmitOutcomesFunction}(?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, outcomes.toJsonArray { it.toJsonObject() })
                ps.executeQuery().use { rs ->
                    rs.next()
                    check(rs.getLong(1) == outcomes.size.toLong()) {
                        "Appended canonical submit outcome count did not match requested batch size"
                    }
                }
            }
        }
    }

    override fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int>): Long {
        if (batchSize <= 0) return 0
        if (projectionStoreSeparated()) {
            return projectCanonicalSubmitOutcomesAcrossStores(projectionName, batchSize, partitions)
        }
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectCanonicalSubmitOutcomesFunction}(?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setInt(2, batchSize)
                ps.setArray(3, conn.createArrayOf("integer", partitions.toTypedArray()))
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String
    ): Long {
        return projectCanonicalCommandOutcomes(projectionName, batchSize, partitions, includeFills, eventStream, ProjectionStage.Full)
    }

    override fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String,
        projectionStage: ProjectionStage
    ): Long {
        if (batchSize <= 0) return 0
        if (projectionStoreSeparated() || projectionStage != ProjectionStage.Full || bootstrapMode == PostgresBootstrapMode.Compat) {
            return projectCanonicalCommandOutcomesAcrossStores(projectionName, batchSize, partitions, includeFills, eventStream, projectionStage)
        }
        return HotPathMetrics.time("projector.venueEventBatch.projectionSql") {
            canonicalConnection().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT ${names.projectCanonicalCommandOutcomesFunction}(?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, projectionName)
                    ps.setInt(2, batchSize)
                    ps.setArray(3, conn.createArrayOf("integer", partitions.toTypedArray()))
                    ps.setBoolean(4, includeFills)
                    ps.setString(5, eventStream.trim().ifBlank { null })
                    ps.setLong(6, projectorDbRetryHorizonMs)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        }
    }

    private fun projectCanonicalSubmitOutcomesAcrossStores(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>
    ): Long {
        val ownedPartitions = ownedProjectionPartitions(partitions)
        if (ownedPartitions.isEmpty()) return 0
        val candidates = HotPathMetrics.time("projector.canonicalSubmit.canonicalRead") {
            val watermarks = projectionWatermarkMap(projectionName, ownedPartitions)
            val perPartitionLimit = ((batchSize + ownedPartitions.size - 1) / ownedPartitions.size).coerceAtLeast(1)
            ownedPartitions
                .flatMap { partitionId ->
                    canonicalProjectionCandidates(
                        partitionId = partitionId,
                        afterPartitionSequence = watermarks[partitionId] ?: 0L,
                        limit = perPartitionLimit
                    ).mapIndexed { index, candidate -> candidate.copy(partitionRow = index + 1) }
                }
                .sortedWith(
                    compareBy<ProjectionCandidate> { it.partitionRow }
                        .thenBy { it.partitionId }
                        .thenBy { it.partitionSequence }
                )
                .take(batchSize)
        }
        if (candidates.isEmpty()) return 0

        return executeProjectionTransactionWithRetry(projectionName, "projector.canonicalSubmit") { conn ->
            val payloadJson = HotPathMetrics.time("projector.canonicalSubmit.transform") {
                candidates.joinToString(prefix = "[", postfix = "]") { it.resultPayload }
            }
            HotPathMetrics.time("projector.canonicalSubmit.projectionSql") {
                persistSubmitOutcomePayloads(conn, payloadJson, candidates.size)
            }
            maybeFailAfterProjectorRowsBeforeWatermark()
            HotPathMetrics.time("projector.canonicalSubmit.watermark") {
                updateProjectionWatermarks(conn, projectionName, candidates)
            }
            candidates.size.toLong()
        }
    }

    private fun projectCanonicalCommandOutcomesAcrossStores(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String,
        projectionStage: ProjectionStage = ProjectionStage.Full
    ): Long {
        val effectiveBatchSize = batchSize.coerceAtMost(CanonicalCommandOutcomeProjectionMaxBatchSize)
        val scopedEventStream = eventStream.trim()
        val ownedPartitions = ownedCommandProjectionPartitions(partitions, scopedEventStream)
        if (ownedPartitions.isEmpty()) return 0
        val retryDeadline = projectionRetryDeadline()
        val candidates = HotPathMetrics.time("projector.venueEventBatch.canonicalRead") {
            val watermarks = projectionWatermarkMap(projectionName, ownedPartitions)
            val perPartitionLimit = ((effectiveBatchSize + ownedPartitions.size - 1) / ownedPartitions.size).coerceAtLeast(1)
            val includeCommandPayload = commandPayloadSideTableAvailable()
            ownedPartitions
                .flatMap { partitionId ->
                    contiguousCommandProjectionCandidates(
                        partitionId = partitionId,
                        afterPartitionSequence = watermarks[partitionId] ?: 0L,
                        candidates = canonicalCommandProjectionCandidates(
                            partitionId = partitionId,
                            afterPartitionSequence = watermarks[partitionId] ?: 0L,
                            limit = perPartitionLimit,
                            includeCommandPayload = includeCommandPayload,
                            eventStream = scopedEventStream
                        )
                    ).mapIndexed { index, candidate -> candidate.copy(partitionRow = index + 1) }
                }
                .sortedWith(
                    compareBy<CommandProjectionCandidate> { it.partitionRow }
                        .thenBy { it.partitionId }
                        .thenBy { it.partitionSequence }
                )
                .take(effectiveBatchSize)
        }
        if (candidates.isEmpty()) return 0

        val identityCandidates = candidates.map { candidate ->
            ProjectionBatchIdentityCandidate(
                partitionId = candidate.partitionId,
                streamSequence = candidate.partitionSequence,
                commandId = candidate.outcome.commandId,
                canonicalBatchId = candidate.outcome.batchId,
                commandType = candidate.outcome.commandType,
                payloadHash = candidate.outcome.payloadHash
            )
        }
        val batchIdentity = ProjectionBatchIdentityV1.digest(
            projectionName = projectionName,
            eventStream = scopedEventStream,
            projectionStage = projectionStage,
            includeFills = includeFills,
            candidates = identityCandidates
        )
        val identityCandidatesJson = projectionBatchIdentityCandidatesJson(identityCandidates)

        val transactionResult = executeClaimedProjectionTransactionWithRetry(
            projectionName = projectionName,
            metricPrefix = "projector.venueEventBatch",
            retryDeadline = retryDeadline
        ) { conn ->
            cleanupProjectionBatchClaims(conn)
            val claim = claimProjectionBatch(
                conn = conn,
                batchIdentity = batchIdentity,
                projectionName = projectionName,
                eventStream = scopedEventStream,
                projectionStage = projectionStage,
                includeFills = includeFills,
                identityCandidatesJson = identityCandidatesJson,
                retryDeadline = retryDeadline,
                retryHorizonMs = projectorDbRetryHorizonMs
            )
            if (!claim.isNew) {
                return@executeClaimedProjectionTransactionWithRetry ClaimedProjectionTransactionResult(
                    resultCount = checkNotNull(claim.storedResultCount) {
                        "Completed projection batch claim must contain its stored result"
                    },
                    appliedNow = false
                )
            }

            val payloadJson = HotPathMetrics.time("projector.venueEventBatch.transform") {
                candidates.toJsonArray {
                    it.outcome.toPersistableSubmitOutcome(it.commandPayloadJson, includeFills).toJsonObject()
                }
            }
            HotPathMetrics.time("projector.venueEventBatch.projectionSql") {
                persistSubmitOutcomePayloads(conn, payloadJson, candidates.size, projectionStage)
            }
            maybeFailAfterProjectorRowsBeforeWatermark()
            HotPathMetrics.time("projector.venueEventBatch.watermark") {
                updateProjectionWatermarks(
                    conn,
                    projectionName,
                    candidates.map {
                        ProjectionCandidate(
                            partitionId = it.partitionId,
                            partitionSequence = it.partitionSequence,
                            resultPayload = ""
                        )
                    }
                )
            }
            val resultCount = candidates.size.toLong()
            completeProjectionBatch(conn, batchIdentity, resultCount)
            ClaimedProjectionTransactionResult(resultCount = resultCount, appliedNow = true)
        }
        return if (transactionResult.appliedNow) transactionResult.resultCount else 0L
    }

    private fun projectionBatchIdentityCandidatesJson(candidates: List<ProjectionBatchIdentityCandidate>): String {
        return JsonCodec.writeArray(
            candidates.map { candidate ->
                mapOf(
                    "partitionId" to candidate.partitionId,
                    "streamSequence" to candidate.streamSequence,
                    "commandId" to candidate.commandId,
                    "canonicalBatchId" to candidate.canonicalBatchId,
                    "commandType" to candidate.commandType,
                    "payloadHash" to candidate.payloadHash
                )
            }
        )
    }

    private fun projectionRetryDeadline(): Instant {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                "SELECT clock_timestamp() + (? * INTERVAL '1 millisecond')"
            ).use { ps ->
                ps.setLong(1, projectorDbRetryHorizonMs)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getTimestamp(1).toInstant()
                }
            }
        }
    }

    private fun assertProjectionRetryWithinHorizon(conn: Connection, retryDeadline: Instant) {
        conn.prepareStatement("SELECT clock_timestamp() <= ?").use { ps ->
            ps.setTimestamp(1, Timestamp.from(retryDeadline))
            ps.executeQuery().use { rs ->
                rs.next()
                check(rs.getBoolean(1)) {
                    "Projection retry horizon expired before batch authority could be re-established"
                }
            }
        }
    }

    private fun cleanupProjectionBatchClaims(conn: Connection) {
        conn.prepareStatement("SELECT ${names.cleanupProjectionBatchClaimsFunction}(1000)").use { ps ->
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun claimProjectionBatch(
        conn: Connection,
        batchIdentity: String,
        projectionName: String,
        eventStream: String,
        projectionStage: ProjectionStage,
        includeFills: Boolean,
        identityCandidatesJson: String,
        retryDeadline: Instant,
        retryHorizonMs: Long
    ): ProjectionBatchClaimResult {
        conn.prepareStatement(
            """
            SELECT is_new, stored_result_count
            FROM ${names.claimProjectionBatchV1Function}(?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, batchIdentity)
            ps.setString(2, projectionName)
            ps.setString(3, eventStream)
            ps.setString(4, projectionStage.configValue)
            ps.setBoolean(5, includeFills)
            ps.setString(6, identityCandidatesJson)
            ps.setTimestamp(7, Timestamp.from(retryDeadline))
            ps.setLong(8, retryHorizonMs)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "Projection batch claim did not return a result" }
                val storedResult = rs.getLong("stored_result_count").let { value ->
                    if (rs.wasNull()) null else value
                }
                return ProjectionBatchClaimResult(
                    isNew = rs.getBoolean("is_new"),
                    storedResultCount = storedResult
                )
            }
        }
    }

    private fun completeProjectionBatch(conn: Connection, batchIdentity: String, resultCount: Long) {
        conn.prepareStatement(
            "SELECT ${names.completeProjectionBatchV1Function}(?, ?)"
        ).use { ps ->
            ps.setString(1, batchIdentity)
            ps.setLong(2, resultCount)
            ps.executeQuery().use { rs ->
                check(rs.next() && rs.getLong(1) == resultCount) {
                    "Completed projection batch count did not match the applied result"
                }
            }
        }
    }

    private fun executeClaimedProjectionTransactionWithRetry(
        projectionName: String,
        metricPrefix: String,
        retryDeadline: Instant,
        operation: (Connection) -> ClaimedProjectionTransactionResult
    ): ClaimedProjectionTransactionResult {
        return executeProjectionTransactionWithRetry(projectionName, metricPrefix) { conn ->
            assertProjectionRetryWithinHorizon(conn, retryDeadline)
            operation(conn)
        }
    }

    private fun <T> executeProjectionTransactionWithRetry(
        projectionName: String,
        metricPrefix: String,
        operation: (Connection) -> T
    ): T {
        var attempt = 1
        while (true) {
            projectionConnection().use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val result = operation(conn)
                    HotPathMetrics.time("$metricPrefix.commit") {
                        conn.commit()
                    }
                    return result
                } catch (ex: Exception) {
                    rollbackQuietly(conn)
                    val sqlState = retryableProjectionSqlState(ex)
                    if (sqlState != null && attempt < projectorDbRetryAttempts) {
                        ProjectionPersistenceRetryMetrics.recordRetry(sqlState, ex.message ?: ex::class.simpleName ?: "unknown")
                    } else {
                        if (sqlState != null) {
                            ProjectionPersistenceRetryMetrics.recordRetryExhausted(sqlState, ex.message ?: ex::class.simpleName ?: "unknown")
                        }
                        recordProjectionFailure(conn, projectionName, projectionFailureMessage(ex))
                        conn.commit()
                        throw ex
                    }
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
            sleepBeforeProjectionRetry(attempt)
            attempt += 1
        }
    }

    private fun sleepBeforeProjectionRetry(attempt: Int) {
        if (projectorDbRetryBackoffMs <= 0L) return
        Thread.sleep(projectorDbRetryBackoffMs * attempt.coerceAtLeast(1))
    }

    private fun rollbackQuietly(conn: Connection) {
        try {
            conn.rollback()
        } catch (_: Exception) {
        }
    }

    private fun retryableProjectionSqlState(error: Throwable): String? {
        val sqlState = sqlState(error) ?: return null
        return when (sqlState) {
            "40001", "40P01", "55P03" -> sqlState
            else -> null
        }
    }

    private fun sqlState(error: Throwable?): String? {
        var current = error
        while (current != null) {
            if (current is SQLException) {
                val state = current.sqlState
                if (!state.isNullOrBlank()) return state
            }
            current = current.cause
        }
        return null
    }

    private fun projectionFailureMessage(error: Throwable): String {
        val state = sqlState(error)
        val message = error.message ?: error::class.simpleName ?: "unknown"
        return if (state.isNullOrBlank()) message else "SQLSTATE $state: $message"
    }

    private fun maybeFailAfterProjectorRowsBeforeWatermark() {
        if (!RuntimeEnv.bool("STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE", false, envLookup)) return
        val internalMode = RuntimeEnv.string("PLATFORM_INTERNAL_HTTP_MODE", "local", envLookup)
        require(internalMode == "enabled") {
            "STREAM_ACK_PROJECTOR_TEST_FAIL_AFTER_ROWS_ONCE requires PLATFORM_INTERNAL_HTTP_MODE=enabled"
        }
        if (!projectorRowsBeforeWatermarkFailureInjected.compareAndSet(false, true)) return
        error("injected projector rollback after read-model rows before watermark")
    }

    private fun ownedProjectionPartitions(partitions: List<Int>): List<Int> {
        if (partitions.isNotEmpty()) return partitions.distinct().sorted()
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT partition_id
                FROM ${names.canonicalCommandResults}
                ORDER BY partition_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Int>()
                    while (rs.next()) out.add(rs.getInt("partition_id"))
                    return out
                }
            }
        }
    }

    private fun ownedCommandProjectionPartitions(partitions: List<Int>, eventStream: String = ""): List<Int> {
        if (partitions.isNotEmpty()) return partitions.distinct().sorted()
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT partition_id
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                  AND (? = '' OR event_stream = ?)
                ORDER BY partition_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, eventStream)
                ps.setString(2, eventStream)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Int>()
                    while (rs.next()) out.add(rs.getInt("partition_id"))
                    return out
                }
            }
        }
    }

    private fun projectionWatermarkMap(projectionName: String, partitions: List<Int>): Map<Int, Long> {
        if (partitions.isEmpty()) return emptyMap()
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT partition_id, last_partition_seq
                FROM ${names.projectionWatermarks}
                WHERE projection_name = ?
                  AND partition_id = ANY(?::INTEGER[])
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setArray(2, conn.createArrayOf("integer", partitions.toTypedArray()))
                ps.executeQuery().use { rs ->
                    val out = mutableMapOf<Int, Long>()
                    while (rs.next()) out[rs.getInt("partition_id")] = rs.getLong("last_partition_seq")
                    return out
                }
            }
        }
    }

    private fun canonicalProjectionCandidates(
        partitionId: Int,
        afterPartitionSequence: Long,
        limit: Int
    ): List<ProjectionCandidate> {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT partition_id, partition_seq, result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandResults}
                WHERE partition_id = ?
                  AND partition_seq > ?
                ORDER BY partition_seq
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, partitionId)
                ps.setLong(2, afterPartitionSequence)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ProjectionCandidate>()
                    while (rs.next()) {
                        out.add(
                            ProjectionCandidate(
                                partitionId = rs.getInt("partition_id"),
                                partitionSequence = rs.getLong("partition_seq"),
                                resultPayload = rs.getString("result_payload")
                            )
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun canonicalCommandProjectionCandidates(
        partitionId: Int,
        afterPartitionSequence: Long,
        limit: Int,
        includeCommandPayload: Boolean,
        eventStream: String = ""
    ): List<CommandProjectionCandidate> {
        canonicalConnection().use { conn ->
            val payloadSelect = if (includeCommandPayload) {
                "COALESCE(payloads.payload_json::TEXT, '{}') AS command_payload"
            } else {
                "'{}' AS command_payload"
            }
            val payloadJoin = if (includeCommandPayload) {
                """
                LEFT JOIN command_log.command_payloads payloads
                  ON payloads.command_id = canonical.command_id
                """.trimIndent()
            } else {
                ""
            }
            conn.prepareStatement(
                """
                SELECT
                  canonical.command_id,
                  canonical.batch_id,
                  canonical.shard_id,
                  canonical.partition_id,
                  canonical.command_stream,
                  canonical.event_stream,
                  canonical.stream_sequence,
                  canonical.delivered_count,
                  canonical.command_type,
                  canonical.payload_hash,
                  canonical.instrument_id,
                  canonical.order_id,
                  canonical.result_status,
                  canonical.reject_code,
                  canonical.result_payload::TEXT AS result_payload,
                  $payloadSelect
                FROM ${names.canonicalCommandOutcomes} canonical
                $payloadJoin
                WHERE canonical.partition_id = ?
                  AND canonical.stream_sequence > ?
                  AND canonical.command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                  AND (? = '' OR canonical.event_stream = ?)
                ORDER BY stream_sequence
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, partitionId)
                ps.setLong(2, afterPartitionSequence)
                ps.setString(3, eventStream)
                ps.setString(4, eventStream)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<CommandProjectionCandidate>()
                    while (rs.next()) {
                        val outcome = CanonicalCommandOutcome(
                            commandId = rs.getString("command_id"),
                            batchId = rs.getString("batch_id"),
                            shardId = rs.getString("shard_id"),
                            partition = rs.getInt("partition_id"),
                            commandStream = rs.getString("command_stream"),
                            eventStream = rs.getString("event_stream"),
                            streamSequence = rs.getLong("stream_sequence"),
                            deliveredCount = rs.getLong("delivered_count"),
                            commandType = rs.getString("command_type"),
                            payloadHash = rs.getString("payload_hash"),
                            instrumentId = rs.getString("instrument_id"),
                            orderId = rs.getString("order_id"),
                            resultStatus = rs.getString("result_status"),
                            rejectCode = rs.getString("reject_code"),
                            resultPayloadJson = rs.getString("result_payload")
                        )
                        out.add(
                            CommandProjectionCandidate(
                                partitionId = outcome.partition,
                                partitionSequence = outcome.streamSequence,
                                outcome = outcome,
                                commandPayloadJson = rs.getString("command_payload")
                            )
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun contiguousCommandProjectionCandidates(
        partitionId: Int,
        afterPartitionSequence: Long,
        candidates: List<CommandProjectionCandidate>
    ): List<CommandProjectionCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val firstSequence = candidates.first().partitionSequence
        if (!usesEncodedPartitionSequence(partitionId, firstSequence)) {
            return candidates
        }
        var expected = if (afterPartitionSequence > 0L) {
            afterPartitionSequence + 1L
        } else {
            encodedPartitionBase(partitionId) + 1L
        }
        val out = mutableListOf<CommandProjectionCandidate>()
        for (candidate in candidates.sortedBy { it.partitionSequence }) {
            when {
                candidate.partitionSequence < expected -> continue
                candidate.partitionSequence == expected -> {
                    out.add(candidate)
                    expected += 1L
                }
                else -> break
            }
        }
        return out
    }

    private fun usesEncodedPartitionSequence(partitionId: Int, streamSequence: Long): Boolean {
        if (partitionId == 0) return true
        return (streamSequence ushr StreamSequencePartitionShift).toInt() == partitionId
    }

    private fun encodedPartitionBase(partitionId: Int): Long {
        return partitionId.toLong() shl StreamSequencePartitionShift
    }

    private fun commandPayloadSideTableAvailable(): Boolean {
        commandPayloadSideTableAvailableCache?.let { return it }
        synchronized(this) {
            commandPayloadSideTableAvailableCache?.let { return it }
            val available = canonicalConnection().use { conn ->
                conn.prepareStatement("SELECT to_regclass('command_log.command_payloads') IS NOT NULL").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }
            commandPayloadSideTableAvailableCache = available
            return available
        }
    }

    private fun persistSubmitOutcomePayloads(
        conn: Connection,
        payloadJson: String,
        expectedCount: Int,
        projectionStage: ProjectionStage = ProjectionStage.Full
    ) {
        val sql = if (projectionStage == ProjectionStage.Full) {
            "SELECT ${names.persistSubmitOutcomesFunction}(?::jsonb)"
        } else {
            "SELECT ${names.persistSubmitOutcomesFunction}(?::jsonb, ?)"
        }
        conn.prepareStatement(
            sql
        ).use { ps ->
            ps.setString(1, payloadJson)
            if (projectionStage != ProjectionStage.Full) {
                ps.setString(2, projectionStage.configValue)
            }
            ps.executeQuery().use { rs ->
                rs.next()
                check(rs.getLong(1) == expectedCount.toLong()) {
                    "Persisted submit outcome count did not match requested batch size"
                }
            }
        }
    }

    private fun updateProjectionWatermarks(
        conn: Connection,
        projectionName: String,
        candidates: List<ProjectionCandidate>
    ) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.projectionWatermarks}(
              projection_name,
              partition_id,
              last_partition_seq,
              last_projected_at,
              updated_at,
              last_error
            )
            VALUES (?, ?, ?, now(), now(), '')
            ON CONFLICT (projection_name, partition_id) DO UPDATE SET
              last_partition_seq = GREATEST(
                ${names.projectionWatermarks}.last_partition_seq,
                EXCLUDED.last_partition_seq
              ),
              last_projected_at = EXCLUDED.last_projected_at,
              updated_at = EXCLUDED.updated_at,
              last_error = ''
            """.trimIndent()
        ).use { ps ->
            candidates
                .groupBy { it.partitionId }
                .forEach { (partitionId, partitionCandidates) ->
                    ps.setString(1, projectionName)
                    ps.setInt(2, partitionId)
                    ps.setLong(3, partitionCandidates.maxOf { it.partitionSequence })
                    ps.addBatch()
                }
            ps.executeBatch()
        }
    }

    private fun recordProjectionFailure(conn: Connection, projectionName: String, error: String) {
        conn.prepareStatement(
            """
            INSERT INTO ${names.projectionWatermarks}(
              projection_name,
              partition_id,
              last_partition_seq,
              last_projected_at,
              updated_at,
              last_error
            )
            VALUES (?, -1, 0, NULL, now(), ?)
            ON CONFLICT (projection_name, partition_id) DO UPDATE SET
              updated_at = EXCLUDED.updated_at,
              last_error = EXCLUDED.last_error
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, projectionName)
            ps.setString(2, error)
            ps.executeUpdate()
        }
    }

    override fun projectionStatus(projectionName: String, partitions: List<Int>, source: String): ProjectionStatus =
        projectionStatusInternal(projectionName, partitions, source, includeProjectedCount = true)

    override fun projectionStatusWithoutCount(projectionName: String, partitions: List<Int>, source: String): ProjectionStatus =
        projectionStatusInternal(projectionName, partitions, source, includeProjectedCount = false)

    override fun projectionLag(projectionName: String, partitions: List<Int>, source: String): ProjectionLag {
        val status = projectionStatusInternal(projectionName, partitions, source, includeProjectedCount = false)
        return ProjectionLag(status.projectionName, status.lag)
    }

    override fun projectionLagUpTo(
        projectionName: String,
        partitions: List<Int>,
        source: String,
        threshold: Long
    ): ProjectionLag {
        if (threshold <= 0L) return projectionLag(projectionName, partitions, source)
        // Read watermark first. New canonical commits between stores can only make this
        // conservative; they cannot hide backlog behind a newer watermark.
        val watermarks = projectionWatermarkRows(projectionName, partitions)
        val watermarkPartitions = watermarks.keys.filter { it >= 0 }.sorted()
        val watermarkSequences = watermarkPartitions.map { watermarks.getValue(it).lastPartitionSequence }
        canonicalConnection().use { conn ->
            val canonicalRowsSql = canonicalProjectionRowsSql(source)
            conn.prepareStatement(
                """
                WITH RECURSIVE watermark_partitions AS (
                  SELECT * FROM unnest(?::INTEGER[], ?::BIGINT[]) AS watermark(partition_id, last_partition_seq)
                ),
                canonical_rows AS NOT MATERIALIZED ($canonicalRowsSql),
                discovered(partition_id) AS (
                  SELECT MIN(partition_id) FROM canonical_rows WHERE cardinality(?::INTEGER[]) = 0
                  UNION ALL
                  SELECT (SELECT MIN(canonical.partition_id) FROM canonical_rows canonical
                          WHERE canonical.partition_id > previous.partition_id)
                  FROM discovered previous WHERE previous.partition_id IS NOT NULL
                ),
                partition_ids AS (
                  SELECT partition_id FROM discovered WHERE partition_id IS NOT NULL
                  UNION
                  SELECT unnest(?::INTEGER[])
                )
                SELECT LEAST(COALESCE(SUM(backlog.lag), 0), ?::BIGINT) AS lag
                FROM partition_ids ids
                LEFT JOIN watermark_partitions watermark ON watermark.partition_id = ids.partition_id
                CROSS JOIN LATERAL (
                  SELECT COUNT(*) AS lag FROM (
                    SELECT 1 FROM canonical_rows canonical
                    WHERE canonical.partition_id = ids.partition_id
                      AND canonical.partition_seq > COALESCE(watermark.last_partition_seq, 0)
                    LIMIT ?
                  ) bounded
                ) backlog
                """.trimIndent()
            ).use { ps ->
                ps.setArray(1, conn.createArrayOf("integer", watermarkPartitions.toTypedArray()))
                ps.setArray(2, conn.createArrayOf("bigint", watermarkSequences.toTypedArray()))
                val requested = conn.createArrayOf("integer", partitions.toTypedArray())
                ps.setArray(3, requested)
                ps.setArray(4, requested)
                ps.setLong(5, threshold)
                ps.setLong(6, threshold)
                ps.executeQuery().use { rs ->
                    rs.next()
                    val boundedLag = rs.getLong("lag").coerceAtMost(threshold)
                    return ProjectionLag(projectionName, boundedLag, isLowerBound = boundedLag >= threshold)
                }
            }
        }
    }

    private fun projectionStatusInternal(
        projectionName: String,
        partitions: List<Int>,
        source: String,
        includeProjectedCount: Boolean
    ): ProjectionStatus {
        if (projectionStoreSeparated()) {
            return projectionStatusAcrossStores(projectionName, partitions, source, includeProjectedCount)
        }
        canonicalConnection().use { conn ->
            val canonicalRowsSql = canonicalProjectionRowsSql(source)
            val watermarks = conn.prepareStatement(
                """
                WITH requested_partitions AS (
                  SELECT unnest(?::INTEGER[]) AS partition_id
                ),
                watermark_partitions AS (
                  SELECT
                    projection_name,
                    partition_id,
                    last_partition_seq,
                    updated_at,
                    last_error
                  FROM ${names.projectionWatermarks}
                  WHERE projection_name = ?
                    AND (
                      cardinality(?::INTEGER[]) = 0
                      OR partition_id IN (SELECT partition_id FROM requested_partitions)
                      OR partition_id = -1
                    )
                ),
                canonical_rows AS (
                  $canonicalRowsSql
                ),
                canonical_partitions AS (
                  SELECT
                    canonical.partition_id,
                    MAX(canonical.partition_seq) AS canonical_max_partition_seq,
                    COUNT(*) FILTER (
                      WHERE canonical.partition_seq > COALESCE(watermark_partitions.last_partition_seq, 0)
                    ) AS lag
                  FROM canonical_rows canonical
                  LEFT JOIN watermark_partitions
                    ON watermark_partitions.partition_id = canonical.partition_id
                  WHERE cardinality(?::INTEGER[]) = 0 OR canonical.partition_id IN (SELECT partition_id FROM requested_partitions)
                  GROUP BY canonical.partition_id
                ),
                owned_partitions AS (
                  SELECT partition_id FROM requested_partitions
                  UNION
                  SELECT partition_id FROM canonical_partitions
                  UNION
                  SELECT partition_id FROM watermark_partitions WHERE partition_id >= 0
                )
                SELECT
                  COALESCE(watermark_partitions.projection_name, ?) AS projection_name,
                  owned_partitions.partition_id,
                  COALESCE(watermark_partitions.last_partition_seq, 0) AS last_partition_seq,
                  COALESCE(canonical_partitions.canonical_max_partition_seq, 0) AS canonical_max_partition_seq,
                  COALESCE(canonical_partitions.lag, 0) AS lag,
                  COALESCE(watermark_partitions.updated_at::TEXT, '') AS updated_at,
                  COALESCE(watermark_partitions.last_error, '') AS last_error
                FROM owned_partitions
                LEFT JOIN canonical_partitions
                  ON canonical_partitions.partition_id = owned_partitions.partition_id
                LEFT JOIN watermark_partitions
                  ON watermark_partitions.partition_id = owned_partitions.partition_id
                UNION ALL
                SELECT
                  watermark_partitions.projection_name,
                  watermark_partitions.partition_id,
                  watermark_partitions.last_partition_seq,
                  0,
                  0,
                  watermark_partitions.updated_at::TEXT,
                  watermark_partitions.last_error
                FROM watermark_partitions
                WHERE watermark_partitions.partition_id = -1
                ORDER BY partition_id
                """.trimIndent()
            ).use { ps ->
                val partitionArray = conn.createArrayOf("integer", partitions.toTypedArray())
                ps.setArray(1, partitionArray)
                ps.setString(2, projectionName)
                ps.setArray(3, partitionArray)
                ps.setArray(4, partitionArray)
                ps.setString(5, projectionName)
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<ProjectionWatermark>()
                    while (rs.next()) {
                        rows.add(
                            ProjectionWatermark(
                                projectionName = rs.getString("projection_name"),
                                partitionId = rs.getInt("partition_id"),
                                lastPartitionSequence = rs.getLong("last_partition_seq"),
                                canonicalMaxPartitionSequence = rs.getLong("canonical_max_partition_seq"),
                                lag = rs.getLong("lag"),
                                updatedAt = rs.getString("updated_at"),
                                lastError = rs.getString("last_error")
                            )
                        )
                    }
                    rows
                }
            }
            val projectedCount = if (includeProjectedCount) {
                conn.prepareStatement("SELECT COUNT(*) FROM ${names.submitResults}").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            } else {
                0L
            }
            return ProjectionStatus(
                projectionName = projectionName,
                projectedCount = projectedCount,
                lag = watermarks.sumOf { it.lag },
                watermarks = watermarks
            )
        }
    }

    override fun committedProjectionFrontier(projectionName: String, partitions: List<Int>): CommittedProjectionFrontier {
        // One committed projection-store snapshot; no canonical history or submit-result count.
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT pg_postmaster_start_time()::TEXT AS database_generation,
                       watermark.partition_id, watermark.last_partition_seq, watermark.last_error
                FROM (SELECT 1) singleton
                LEFT JOIN ${names.projectionWatermarks} watermark
                  ON watermark.projection_name = ?
                  AND (cardinality(?::INTEGER[]) = 0 OR watermark.partition_id = ANY(?::INTEGER[])
                       OR watermark.partition_id = -1)
                ORDER BY watermark.partition_id
                """.trimIndent()
            ).use { ps ->
                val partitionArray = conn.createArrayOf("integer", partitions.toTypedArray())
                ps.setString(1, projectionName)
                ps.setArray(2, partitionArray)
                ps.setArray(3, partitionArray)
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<CommittedProjectionWatermark>()
                    var generation = ""
                    while (rs.next()) {
                        generation = rs.getString("database_generation")
                        if (rs.getObject("partition_id") != null) {
                            rows.add(CommittedProjectionWatermark(rs.getInt("partition_id"), rs.getLong("last_partition_seq"), rs.getString("last_error")))
                        }
                    }
                    return CommittedProjectionFrontier(projectionName, partitions.toList(), rows, generation)
                }
            }
        }
    }

    override fun projectionDirtyQueueStats(): ProjectionDirtyQueueStats {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  (SELECT COUNT(*) FROM ${names.orderLifecycleDirty}) AS order_lifecycle_pending,
                  COALESCE((SELECT MIN(dirtied_at)::TEXT FROM ${names.orderLifecycleDirty}), '') AS order_lifecycle_oldest_dirtied_at,
                  (SELECT COUNT(*) FROM ${names.marketDataSnapshotDirty}) AS market_data_pending,
                  COALESCE((SELECT MIN(dirtied_at)::TEXT FROM ${names.marketDataSnapshotDirty}), '') AS market_data_oldest_dirtied_at,
                  to_char(
                    clock_timestamp() AT TIME ZONE 'UTC',
                    'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                  ) AS database_snapshot_at,
                  pg_postmaster_start_time()::TEXT AS database_generation
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    return ProjectionDirtyQueueStats(
                        orderLifecyclePending = rs.getLong("order_lifecycle_pending"),
                        orderLifecycleOldestDirtiedAt = rs.getString("order_lifecycle_oldest_dirtied_at"),
                        marketDataPending = rs.getLong("market_data_pending"),
                        marketDataOldestDirtiedAt = rs.getString("market_data_oldest_dirtied_at"),
                        databaseSnapshotAt = rs.getString("database_snapshot_at"),
                        databaseGeneration = rs.getString("database_generation")
                    )
                }
            }
        }
    }

    override fun projectionDirtyQueueMarkerStats(): ProjectionDirtyQueueStats {
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  COALESCE((SELECT MIN(dirtied_at)::TEXT FROM ${names.orderLifecycleDirty}), '') AS order_lifecycle_oldest_dirtied_at,
                  COALESCE((SELECT MIN(dirtied_at)::TEXT FROM ${names.marketDataSnapshotDirty}), '') AS market_data_oldest_dirtied_at,
                  to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS database_snapshot_at,
                  pg_postmaster_start_time()::TEXT AS database_generation
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    val lifecycleOldest = rs.getString("order_lifecycle_oldest_dirtied_at")
                    val marketOldest = rs.getString("market_data_oldest_dirtied_at")
                    // MIN is null exactly when its NOT NULL dirtied_at queue is empty.
                    // These are occupancy sentinels for coverage, never exposed as exact counts.
                    return ProjectionDirtyQueueStats(
                        orderLifecyclePending = if (lifecycleOldest.isEmpty()) 0 else 1,
                        orderLifecycleOldestDirtiedAt = lifecycleOldest,
                        marketDataPending = if (marketOldest.isEmpty()) 0 else 1,
                        marketDataOldestDirtiedAt = marketOldest,
                        databaseSnapshotAt = rs.getString("database_snapshot_at"),
                        databaseGeneration = rs.getString("database_generation")
                    )
                }
            }
        }
    }

    private fun projectionStatusWatermarks(
        projectionName: String,
        partitions: List<Int>,
        source: String
    ): List<ProjectionWatermark> {
        val watermarkRows = projectionWatermarkRows(projectionName, partitions)
        val canonicalStatsByPartition = canonicalPartitionStats(partitions, watermarkRows, source)
        val partitionIds = (partitions + canonicalStatsByPartition.keys + watermarkRows.keys)
            .filter { it >= 0 }
            .distinct()
            .sorted()
        return partitionIds.map { partitionId ->
            val watermark = watermarkRows[partitionId]
            val canonicalStats = canonicalStatsByPartition[partitionId]
            val projected = watermark?.lastPartitionSequence ?: 0L
            ProjectionWatermark(
                projectionName = watermark?.projectionName ?: projectionName,
                partitionId = partitionId,
                lastPartitionSequence = projected,
                canonicalMaxPartitionSequence = canonicalStats?.maxPartitionSequence ?: 0L,
                lag = canonicalStats?.backlogCount ?: 0L,
                updatedAt = watermark?.updatedAt.orEmpty(),
                lastError = watermark?.lastError.orEmpty()
            )
        } + watermarkRows.values.filter { it.partitionId == -1 }
    }

    private fun projectionStatusAcrossStores(
        projectionName: String,
        partitions: List<Int>,
        source: String,
        includeProjectedCount: Boolean
    ): ProjectionStatus {
        val watermarks = projectionStatusWatermarks(projectionName, partitions, source)
        val projectedCount = if (includeProjectedCount) {
            projectionConnection().use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM ${names.submitResults}").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        } else {
            0L
        }
        return ProjectionStatus(
            projectionName = projectionName,
            projectedCount = projectedCount,
            lag = watermarks.sumOf { it.lag },
            watermarks = watermarks.sortedBy { it.partitionId }
        )
    }

    override fun materializeVenueEventBatch(batch: VenueEventBatchFact): Long {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.materializeVenueEventBatchFunction}(?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, batch.toJsonObject())
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun materializeVenueEventBatches(batches: List<VenueEventBatchFact>): Long {
        if (batches.isEmpty()) return 0
        if (batches.size == 1) return materializeVenueEventBatch(batches.single())
        val payload = batches.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJsonObject() }
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT COALESCE(SUM(materialized_count), 0)
                FROM (
                  SELECT ${names.materializeVenueEventBatchFunction}(batch) AS materialized_count
                  FROM jsonb_array_elements(?::jsonb) WITH ORDINALITY AS batches(batch, ordinality)
                  ORDER BY ordinality
                ) ordered_batches
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, payload)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun canonicalCommandOutcome(commandId: String): CanonicalCommandOutcome? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  command_id,
                  batch_id,
                  shard_id,
                  partition_id,
                  command_stream,
                  event_stream,
                  stream_sequence,
                  delivered_count,
                  command_type,
                  payload_hash,
                  instrument_id,
                  order_id,
                  result_status,
                  reject_code,
                  result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return CanonicalCommandOutcome(
                        commandId = rs.getString("command_id"),
                        batchId = rs.getString("batch_id"),
                        shardId = rs.getString("shard_id"),
                        partition = rs.getInt("partition_id"),
                        commandStream = rs.getString("command_stream"),
                        eventStream = rs.getString("event_stream"),
                        streamSequence = rs.getLong("stream_sequence"),
                        deliveredCount = rs.getLong("delivered_count"),
                        commandType = rs.getString("command_type"),
                        payloadHash = rs.getString("payload_hash"),
                        instrumentId = rs.getString("instrument_id"),
                        orderId = rs.getString("order_id"),
                        resultStatus = rs.getString("result_status"),
                        rejectCode = rs.getString("reject_code"),
                        resultPayloadJson = rs.getString("result_payload")
                    )
                }
            }
        }
    }

    override fun canonicalCommandResult(commandId: String): CanonicalCommandResult? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  command_id,
                  partition_id,
                  stream_name,
                  stream_seq,
                  command_type,
                  payload_hash,
                  instrument_id,
                  result_status,
                  reject_code,
                  engine_shard_id,
                  result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandResults}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return CanonicalCommandResult(
                        commandId = rs.getString("command_id"),
                        partition = rs.getInt("partition_id"),
                        commandStream = rs.getString("stream_name"),
                        streamSequence = rs.getLong("stream_seq"),
                        commandType = rs.getString("command_type"),
                        payloadHash = rs.getString("payload_hash"),
                        instrumentId = rs.getString("instrument_id"),
                        resultStatus = rs.getString("result_status"),
                        rejectCode = rs.getString("reject_code"),
                        engineShardId = rs.getString("engine_shard_id"),
                        resultPayloadJson = rs.getString("result_payload")
                    )
                }
            }
        }
    }

    override fun venueEventBatchCommandReference(commandId: String): VenueEventBatchCommandReference? {
        canonicalConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  command_id,
                  batch_id,
                  shard_id,
                  partition_id,
                  command_stream,
                  event_stream,
                  stream_sequence,
                  delivered_count,
                  command_type,
                  payload_hash,
                  instrument_id,
                  order_id,
                  result_status,
                  reject_code,
                  result_payload::TEXT AS result_payload
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_id = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return VenueEventBatchCommandReference(
                        commandId = rs.getString("command_id"),
                        batchId = rs.getString("batch_id"),
                        shardId = rs.getString("shard_id"),
                        partition = rs.getInt("partition_id"),
                        commandStream = rs.getString("command_stream"),
                        eventStream = rs.getString("event_stream"),
                        streamSequence = rs.getLong("stream_sequence"),
                        deliveredCount = rs.getLong("delivered_count"),
                        commandType = rs.getString("command_type"),
                        payloadHash = rs.getString("payload_hash"),
                        instrumentId = rs.getString("instrument_id"),
                        orderId = rs.getString("order_id"),
                        resultStatus = rs.getString("result_status"),
                        rejectCode = rs.getString("reject_code"),
                        resultPayloadJson = rs.getString("result_payload")
                    )
                }
            }
        }
    }

    override fun rebuildOrderLifecycleState(): Long {
        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM ${names.orderLifecycleState}").use { ps ->
                    ps.executeUpdate()
                }
                val inserted = conn.prepareStatement(
                    """
                    WITH execution_totals AS (
                      SELECT
                        run_id, order_id,
                        SUM(quantity_units::NUMERIC) AS filled_quantity_units
                      FROM ${names.executions}
                      WHERE quantity_units ~ '^[0-9]+(\.[0-9]+)?$'
                      GROUP BY run_id, order_id
                    ),
                    latest_modify AS (
                      SELECT DISTINCT ON (run_id, order_id)
                        run_id, order_id,
                        COALESCE(NULLIF(modify_quantity_units, ''), '') AS modified_quantity_units,
                        COALESCE(NULLIF(modify_limit_price, ''), '') AS modified_limit_price,
                        occurred_at
                      FROM ${names.runtimeEvents}
                      WHERE event_type = 'OrderModified'
                      ORDER BY run_id, order_id,
                        occurred_at_ts DESC NULLS LAST,
                        occurred_at DESC,
                        sequence_number DESC,
                        event_id_uuid DESC NULLS LAST,
                        event_id DESC
                    ),
                    order_event_state AS (
                      SELECT
                        run_id, order_id,
                        BOOL_OR(event_type = 'OrderCancelled') AS cancelled,
                        BOOL_OR(event_type = 'OrderRejected') AS rejected,
                        COALESCE(MAX(NULLIF(occurred_at, '')), '') AS last_event_at
                      FROM ${names.runtimeEvents}
                      GROUP BY run_id, order_id
                    ),
                    shaped AS (
                      SELECT
                        orders.run_id, orders.order_id,
                        orders.engine_order_id,
                        orders.instrument_id,
                        orders.participant_id,
                        orders.account_id,
                        orders.side,
                        orders.order_type,
                        orders.quantity_units AS original_quantity_units,
                        COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) AS current_quantity_units,
                        COALESCE(NULLIF(latest_modify.modified_limit_price, ''), orders.limit_price) AS current_limit_price,
                        orders.currency,
                        orders.time_in_force,
                        orders.accepted_at,
                        COALESCE(execution_totals.filled_quantity_units, 0) AS filled_quantity_units,
                        COALESCE(order_event_state.cancelled, FALSE) AS cancelled,
                        COALESCE(order_event_state.rejected, FALSE) AS rejected,
                        COALESCE(NULLIF(order_event_state.last_event_at, ''), orders.accepted_at) AS last_event_at
                      FROM ${names.orders} orders
                      LEFT JOIN execution_totals ON execution_totals.run_id = orders.run_id AND execution_totals.order_id = orders.order_id
                      LEFT JOIN latest_modify ON latest_modify.run_id = orders.run_id AND latest_modify.order_id = orders.order_id
                      LEFT JOIN order_event_state ON order_event_state.run_id = orders.run_id AND order_event_state.order_id = orders.order_id
                      WHERE COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) ~ '^[0-9]+(\.[0-9]+)?$'
                    ),
                    calculated AS (
                      SELECT
                        *,
                        GREATEST(current_quantity_units::NUMERIC - filled_quantity_units, 0) AS remaining_quantity_units,
                        CASE
                          WHEN current_limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN current_limit_price::NUMERIC
                          ELSE NULL
                        END AS current_limit_price_num
                      FROM shaped
                    )
                    INSERT INTO ${names.orderLifecycleState}(
                      run_id, order_id,
                      engine_order_id,
                      instrument_id,
                      participant_id,
                      account_id,
                      side,
                      order_type,
                      original_quantity_units,
                      remaining_quantity_units,
                      filled_quantity_units,
                      limit_price,
                      currency,
                      time_in_force,
                      status,
                      accepted_at,
                      last_event_at,
                      updated_at,
                      original_quantity_units_num,
                      remaining_quantity_units_num,
                      filled_quantity_units_num,
                      limit_price_num
                    )
                    SELECT
                      run_id, order_id,
                      engine_order_id,
                      instrument_id,
                      participant_id,
                      account_id,
                      side,
                      order_type,
                      original_quantity_units,
                      CASE WHEN cancelled OR rejected THEN '0' ELSE remaining_quantity_units::TEXT END,
                      filled_quantity_units::TEXT,
                      current_limit_price,
                      currency,
                      time_in_force,
                      CASE
                        WHEN rejected THEN 'REJECTED'
                        WHEN cancelled THEN 'CANCELLED'
                        WHEN current_quantity_units::NUMERIC > 0 AND remaining_quantity_units = 0 THEN 'FILLED'
                        WHEN filled_quantity_units > 0 THEN 'PARTIALLY_FILLED'
                        ELSE 'OPEN'
                      END,
                      accepted_at,
                      last_event_at,
                      now(),
                      original_quantity_units::NUMERIC,
                      CASE WHEN cancelled OR rejected THEN 0 ELSE remaining_quantity_units END,
                      filled_quantity_units,
                      current_limit_price_num
                    FROM calculated
                    ORDER BY order_id
                    """.trimIndent()
                ).use { ps ->
                    ps.executeUpdate().toLong()
                }
                conn.commit()
                return inserted
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun projectOrderLifecycleState(batchSize: Int): Long {
        if (batchSize <= 0) return 0
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectOrderLifecycleStateFunction}(?)
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, batchSize)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun orderLifecycleState(identity: RuntimeOrderIdentity): OrderLifecycleState? {
        return projectionQueryList(
            """
            SELECT
              order_id,
              engine_order_id,
              instrument_id,
              participant_id,
              account_id,
              side,
              order_type,
              original_quantity_units,
              remaining_quantity_units,
              filled_quantity_units,
              limit_price,
              currency,
              time_in_force,
              status,
              accepted_at,
              last_event_at,
              updated_at::TEXT AS updated_at
            FROM ${names.orderLifecycleState}
            WHERE run_id = ? AND order_id = ?
            """.trimIndent(),
            identity.runId, identity.orderId
        ) {
            OrderLifecycleState(
                orderId = getString("order_id"),
                engineOrderId = getString("engine_order_id"),
                instrumentId = getString("instrument_id"),
                participantId = getString("participant_id"),
                accountId = getString("account_id"),
                side = getString("side"),
                orderType = getString("order_type"),
                originalQuantityUnits = getString("original_quantity_units"),
                remainingQuantityUnits = getString("remaining_quantity_units"),
                filledQuantityUnits = getString("filled_quantity_units"),
                limitPrice = getString("limit_price"),
                currency = getString("currency"),
                timeInForce = getString("time_in_force"),
                status = getString("status"),
                acceptedAt = getString("accepted_at"),
                lastEventAt = getString("last_event_at"),
                updatedAt = getString("updated_at")
            )
        }.firstOrNull()
    }

    override fun orderLifecycleState(orderId: String): OrderLifecycleState? {
        return projectionQueryList(
            """
            SELECT
              order_id,
              engine_order_id,
              instrument_id,
              participant_id,
              account_id,
              side,
              order_type,
              original_quantity_units,
              remaining_quantity_units,
              filled_quantity_units,
              limit_price,
              currency,
              time_in_force,
              status,
              accepted_at,
              last_event_at,
              updated_at::TEXT AS updated_at
            FROM ${names.orderLifecycleState}
            WHERE order_id = ?
            """.trimIndent(),
            orderId
        ) {
            OrderLifecycleState(
                orderId = getString("order_id"),
                engineOrderId = getString("engine_order_id"),
                instrumentId = getString("instrument_id"),
                participantId = getString("participant_id"),
                accountId = getString("account_id"),
                side = getString("side"),
                orderType = getString("order_type"),
                originalQuantityUnits = getString("original_quantity_units"),
                remainingQuantityUnits = getString("remaining_quantity_units"),
                filledQuantityUnits = getString("filled_quantity_units"),
                limitPrice = getString("limit_price"),
                currency = getString("currency"),
                timeInForce = getString("time_in_force"),
                status = getString("status"),
                acceptedAt = getString("accepted_at"),
                lastEventAt = getString("last_event_at"),
                updatedAt = getString("updated_at")
            )
        }.singleOrNull()
    }

    override fun refreshMarketDataSnapshots(projectionName: String, sourceProjectionName: String): Long {
        // See marketDataDepthSnapshot: order_lifecycle_state is already kept
        // current by the incremental OrderLifecycleProjectionWorker, so a
        // synchronous full-venue rebuild here is redundant.
        val sourceStatus = projectionStatusInternal(
            sourceProjectionName, emptyList(), "venue-event-batch", includeProjectedCount = false
        )
        val lastPartitionSequence = sourceStatus.watermarks
            .filter { it.partitionId >= 0 }
            .maxOfOrNull { it.lastPartitionSequence } ?: 0L
        projectionConnection().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM ${names.marketDataSnapshots} WHERE projection_name = ?").use { ps ->
                    ps.setString(1, projectionName)
                    ps.executeUpdate()
                }
                val inserted = conn.prepareStatement(
                    """
                    WITH priced_orders AS (
                      SELECT
                        instrument_id,
                        side,
                        currency,
                        limit_price_num AS price_num,
                        remaining_quantity_units_num AS quantity_num
                      FROM ${names.orderLifecycleState}
                      WHERE order_type = 'LIMIT'
                        AND status IN ('OPEN', 'PARTIALLY_FILLED')
                        AND limit_price_num IS NOT NULL
                        AND remaining_quantity_units_num > 0
                    ),
                    bid_prices AS (
                      SELECT instrument_id, MAX(price_num) AS best_bid_price
                      FROM priced_orders
                      WHERE side = 'BUY'
                      GROUP BY instrument_id
                    ),
                    ask_prices AS (
                      SELECT instrument_id, MIN(price_num) AS best_ask_price
                      FROM priced_orders
                      WHERE side = 'SELL'
                      GROUP BY instrument_id
                    ),
                    bid_totals AS (
                      SELECT priced.instrument_id, SUM(priced.quantity_num) AS best_bid_quantity
                      FROM priced_orders priced
                      JOIN bid_prices best
                        ON best.instrument_id = priced.instrument_id
                       AND best.best_bid_price = priced.price_num
                      WHERE priced.side = 'BUY'
                      GROUP BY priced.instrument_id
                    ),
                    ask_totals AS (
                      SELECT priced.instrument_id, SUM(priced.quantity_num) AS best_ask_quantity
                      FROM priced_orders priced
                      JOIN ask_prices best
                        ON best.instrument_id = priced.instrument_id
                       AND best.best_ask_price = priced.price_num
                      WHERE priced.side = 'SELL'
                      GROUP BY priced.instrument_id
                    ),
                    instruments AS (
                      SELECT instrument_id, MAX(currency) AS currency
                      FROM priced_orders
                      GROUP BY instrument_id
                    )
                    INSERT INTO ${names.marketDataSnapshots}(
                      projection_name,
                      source_projection_name,
                      instrument_id,
                      best_bid_price,
                      best_bid_quantity,
                      best_ask_price,
                      best_ask_quantity,
                      currency,
                      last_partition_seq,
                      lag,
                      updated_at,
                      best_bid_price_num,
                      best_bid_quantity_num,
                      best_ask_price_num,
                      best_ask_quantity_num
                    )
                    SELECT
                      ?,
                      ?,
                      instruments.instrument_id,
                      COALESCE(bid_prices.best_bid_price::TEXT, ''),
                      COALESCE(bid_totals.best_bid_quantity::TEXT, ''),
                      COALESCE(ask_prices.best_ask_price::TEXT, ''),
                      COALESCE(ask_totals.best_ask_quantity::TEXT, ''),
                      COALESCE(instruments.currency, ''),
                      ?,
                      ?,
                      now(),
                      bid_prices.best_bid_price,
                      bid_totals.best_bid_quantity,
                      ask_prices.best_ask_price,
                      ask_totals.best_ask_quantity
                    FROM instruments
                    LEFT JOIN bid_prices ON bid_prices.instrument_id = instruments.instrument_id
                    LEFT JOIN bid_totals ON bid_totals.instrument_id = instruments.instrument_id
                    LEFT JOIN ask_prices ON ask_prices.instrument_id = instruments.instrument_id
                    LEFT JOIN ask_totals ON ask_totals.instrument_id = instruments.instrument_id
                    ORDER BY instruments.instrument_id
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, projectionName)
                    ps.setString(2, sourceProjectionName)
                    ps.setLong(3, lastPartitionSequence)
                    ps.setLong(4, sourceStatus.lag)
                    ps.executeUpdate().toLong()
                }
                conn.commit()
                return inserted
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun projectMarketDataSnapshots(
        projectionName: String,
        sourceProjectionName: String,
        batchSize: Int,
        projectLifecycleFirst: Boolean
    ): Long {
        if (batchSize <= 0) return 0
        if (projectLifecycleFirst) projectOrderLifecycleState(batchSize)
        // Market metadata needs the source frontier and lag, not a global result count.
        val sourceWatermarks = projectionStatusWatermarks(sourceProjectionName, emptyList(), "venue-event-batch")
        val lastPartitionSequence = sourceWatermarks
            .filter { it.partitionId >= 0 }
            .maxOfOrNull { it.lastPartitionSequence } ?: 0L
        projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT ${names.projectMarketDataSnapshotsFunction}(?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, projectionName)
                ps.setString(2, sourceProjectionName)
                ps.setLong(3, lastPartitionSequence)
                ps.setLong(4, sourceWatermarks.sumOf { it.lag })
                ps.setInt(5, batchSize)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun marketDataSnapshot(instrumentId: String, projectionName: String): MarketDataSnapshot? {
        return projectionQueryList(
            """
            SELECT
              projection_name,
              source_projection_name,
              instrument_id,
              best_bid_price,
              best_bid_quantity,
              best_ask_price,
              best_ask_quantity,
              currency,
              last_partition_seq,
              lag,
              updated_at::TEXT AS updated_at
            FROM ${names.marketDataSnapshots}
            WHERE projection_name = ? AND instrument_id = ?
            """.trimIndent(),
            projectionName,
            instrumentId
        ) {
            MarketDataSnapshot(
                projectionName = getString("projection_name"),
                sourceProjectionName = getString("source_projection_name"),
                instrumentId = getString("instrument_id"),
                bestBidPrice = getString("best_bid_price"),
                bestBidQuantity = getString("best_bid_quantity"),
                bestAskPrice = getString("best_ask_price"),
                bestAskQuantity = getString("best_ask_quantity"),
                currency = getString("currency"),
                lastPartitionSequence = getLong("last_partition_seq"),
                lag = getLong("lag"),
                updatedAt = getString("updated_at")
            )
        }.firstOrNull()
    }

    override fun marketDataDepthSnapshot(
        instrumentId: String,
        levels: Int,
        projectionName: String,
        sourceProjectionName: String
    ): MarketDataDepthSnapshot? {
        val boundedLevels = levels.coerceIn(1, 50)
        // order_lifecycle_state is kept current by the incremental
        // OrderLifecycleProjectionWorker (250ms cadence). Do not force a
        // synchronous full-venue rebuildOrderLifecycleState() here: it was
        // redundant with that worker and, worse, scaled with total venue
        // order history rather than the single requested instrument -
        // every depth-book read paid for a full DELETE+rebuild across every
        // order/execution/event in the system. Callers that need a forced
        // full rebuild use the dedicated rebuildOrderLifecycleState route.
        val sourceStatus = projectionStatusInternal(
            sourceProjectionName, emptyList(), "venue-event-batch", includeProjectedCount = false
        )
        val lastPartitionSequence = sourceStatus.watermarks
            .filter { it.partitionId >= 0 }
            .maxOfOrNull { it.lastPartitionSequence } ?: 0L
        val bidLevels = depthLevels(instrumentId, "BUY", "DESC", boundedLevels)
        val askLevels = depthLevels(instrumentId, "SELL", "ASC", boundedLevels)
        if (bidLevels.isEmpty() && askLevels.isEmpty()) return null
        val currency = projectionConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT COALESCE(MAX(currency), '') AS currency
                FROM ${names.orderLifecycleState}
                WHERE instrument_id = ?
                  AND status IN ('OPEN', 'PARTIALLY_FILLED')
                  AND remaining_quantity_units_num > 0
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, instrumentId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("currency")
                }
            }
        }
        return MarketDataDepthSnapshot(
            projectionName = projectionName,
            sourceProjectionName = sourceProjectionName,
            instrumentId = instrumentId,
            bidLevels = bidLevels,
            askLevels = askLevels,
            currency = currency,
            levels = boundedLevels,
            lastPartitionSequence = lastPartitionSequence,
            lag = sourceStatus.lag,
            updatedAt = java.time.Instant.now().toString()
        )
    }

    private fun depthLevels(
        instrumentId: String,
        side: String,
        direction: String,
        levels: Int
    ): List<MarketDataDepthLevel> {
        require(direction == "ASC" || direction == "DESC") { "unsupported depth sort direction" }
        return projectionQueryList(
            """
            SELECT
              price_num::TEXT AS price,
              SUM(remaining_quantity_units_num)::TEXT AS quantity
            FROM (
              SELECT
                limit_price_num AS price_num,
                remaining_quantity_units_num
              FROM ${names.orderLifecycleState}
              WHERE instrument_id = ?
                AND side = ?
                AND order_type = 'LIMIT'
                AND status IN ('OPEN', 'PARTIALLY_FILLED')
                AND limit_price_num IS NOT NULL
                AND remaining_quantity_units_num > 0
            ) priced
            GROUP BY price_num
            ORDER BY price_num $direction
            LIMIT ?::INTEGER
            """.trimIndent(),
            instrumentId,
            side,
            levels.toString()
        ) {
            MarketDataDepthLevel(
                price = getString("price"),
                quantity = getString("quantity")
            )
        }
    }

    private data class CanonicalPartitionStats(val maxPartitionSequence: Long, val backlogCount: Long)

    private fun canonicalPartitionStats(
        partitions: List<Int>,
        watermarks: Map<Int, ProjectionWatermark>,
        source: String
    ): Map<Int, CanonicalPartitionStats> {
        canonicalConnection().use { conn ->
            val canonicalRowsSql = canonicalProjectionRowsSql(source)
            val watermarkPartitions = watermarks.keys.filter { it >= 0 }.sorted()
            val watermarkSequences = watermarkPartitions.map { watermarks.getValue(it).lastPartitionSequence }
            conn.prepareStatement(
                """
                WITH RECURSIVE watermark_partitions AS (
                  SELECT *
                  FROM unnest(?::INTEGER[], ?::BIGINT[]) AS watermark(partition_id, last_partition_seq)
                ),
                canonical_rows AS NOT MATERIALIZED (
                  $canonicalRowsSql
                ),
                partition_ids(partition_id) AS (
                  SELECT MIN(partition_id) FROM canonical_rows
                  UNION ALL
                  SELECT (
                    SELECT MIN(canonical.partition_id) FROM canonical_rows canonical
                    WHERE canonical.partition_id > previous.partition_id
                  )
                  FROM partition_ids previous
                  WHERE previous.partition_id IS NOT NULL
                )
                SELECT
                  partition_ids.partition_id,
                  (SELECT MAX(canonical.partition_seq) FROM canonical_rows canonical
                   WHERE canonical.partition_id = partition_ids.partition_id) AS canonical_max_partition_seq,
                  (SELECT COUNT(*) FROM canonical_rows canonical
                   WHERE canonical.partition_id = partition_ids.partition_id
                     AND canonical.partition_seq > COALESCE(watermark_partitions.last_partition_seq, 0)) AS lag
                FROM partition_ids
                LEFT JOIN watermark_partitions ON watermark_partitions.partition_id = partition_ids.partition_id
                WHERE partition_ids.partition_id IS NOT NULL
                  AND (cardinality(?::INTEGER[]) = 0 OR partition_ids.partition_id = ANY(?::INTEGER[]))
                """.trimIndent()
            ).use { ps ->
                val partitionArray = conn.createArrayOf("integer", partitions.toTypedArray())
                ps.setArray(1, conn.createArrayOf("integer", watermarkPartitions.toTypedArray()))
                ps.setArray(2, conn.createArrayOf("bigint", watermarkSequences.toTypedArray()))
                ps.setArray(3, partitionArray)
                ps.setArray(4, partitionArray)
                ps.executeQuery().use { rs ->
                    val out = mutableMapOf<Int, CanonicalPartitionStats>()
                    while (rs.next()) {
                        out[rs.getInt("partition_id")] = CanonicalPartitionStats(
                            maxPartitionSequence = rs.getLong("canonical_max_partition_seq"),
                            backlogCount = rs.getLong("lag")
                        )
                    }
                    return out
                }
            }
        }
    }

    private fun canonicalProjectionRowsSql(source: String): String {
        return when (source.trim().lowercase()) {
            "venue-event-batch", "event-batch", "venue-events", "canonical-command-outcomes" ->
                """
                SELECT partition_id, stream_sequence AS partition_seq
                FROM ${names.canonicalCommandOutcomes}
                WHERE command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
                """.trimIndent()
            else ->
                """
                SELECT partition_id, partition_seq
                FROM ${names.canonicalCommandResults}
                """.trimIndent()
        }
    }

    private fun projectionWatermarkRows(
        projectionName: String,
        partitions: List<Int>
    ): Map<Int, ProjectionWatermark> {
        projectionConnection().use { conn ->
            val sql = if (partitions.isEmpty()) {
                """
                SELECT projection_name, partition_id, last_partition_seq, updated_at::TEXT AS updated_at, last_error
                FROM ${names.projectionWatermarks}
                WHERE projection_name = ?
                """.trimIndent()
            } else {
                """
                SELECT projection_name, partition_id, last_partition_seq, updated_at::TEXT AS updated_at, last_error
                FROM ${names.projectionWatermarks}
                WHERE projection_name = ?
                  AND (partition_id = ANY(?::INTEGER[]) OR partition_id = -1)
                """.trimIndent()
            }
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, projectionName)
                if (partitions.isNotEmpty()) {
                    ps.setArray(2, conn.createArrayOf("integer", partitions.toTypedArray()))
                }
                ps.executeQuery().use { rs ->
                    val out = mutableMapOf<Int, ProjectionWatermark>()
                    while (rs.next()) {
                        val partitionId = rs.getInt("partition_id")
                        out[partitionId] = ProjectionWatermark(
                            projectionName = rs.getString("projection_name"),
                            partitionId = partitionId,
                            lastPartitionSequence = rs.getLong("last_partition_seq"),
                            canonicalMaxPartitionSequence = 0,
                            lag = 0,
                            updatedAt = rs.getString("updated_at"),
                            lastError = rs.getString("last_error")
                        )
                    }
                    return out
                }
            }
        }
    }

    override fun saveAcceptedOrder(order: PersistedOrder) {
        projectionTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.orders}(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, order_id) DO UPDATE SET
                  engine_order_id = EXCLUDED.engine_order_id,
                  instrument_id = EXCLUDED.instrument_id,
                  participant_id = EXCLUDED.participant_id,
                  account_id = EXCLUDED.account_id,
                  side = EXCLUDED.side,
                  order_type = EXCLUDED.order_type,
                  quantity_units = EXCLUDED.quantity_units,
                  limit_price = EXCLUDED.limit_price,
                  currency = EXCLUDED.currency,
                  time_in_force = EXCLUDED.time_in_force,
                  accepted_at = EXCLUDED.accepted_at,
                  client_order_id = EXCLUDED.client_order_id,
                  run_id = EXCLUDED.run_id,
                  venue_session_id = EXCLUDED.venue_session_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, order.orderId)
                ps.setString(2, order.engineOrderId)
                ps.setString(3, order.instrumentId)
                ps.setString(4, order.participantId)
                ps.setString(5, order.accountId)
                ps.setString(6, order.side)
                ps.setString(7, order.orderType)
                ps.setString(8, order.quantityUnits)
                ps.setString(9, order.limitPrice)
                ps.setString(10, order.currency)
                ps.setString(11, order.timeInForce)
                ps.setString(12, order.acceptedAt)
                ps.setString(13, order.clientOrderId)
                ps.setString(14, order.runId)
                ps.setString(15, order.venueSessionId)
                ps.executeUpdate()
            }
            markOrderLifecycleDirty(conn, listOf(RuntimeOrderIdentity(order.runId, order.orderId)))
        }
    }

    override fun saveExecutions(executions: List<ExecutionCreated>) {
        if (executions.isEmpty()) return
        projectionTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.executions}(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at, liquidity_role, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET
                  event_id = ${names.rejectExecutionReplayConflictFunction}(EXCLUDED.event_id)
                WHERE ROW(
                  ${names.executions}.execution_id,
                  ${names.executions}.order_id,
                  ${names.executions}.instrument_id,
                  ${names.executions}.quantity_units,
                  ${names.executions}.execution_price,
                  ${names.executions}.currency,
                  ${names.executions}.run_id,
                  ${names.executions}.occurred_at,
                  ${names.executions}.liquidity_role
                ) IS DISTINCT FROM ROW(
                  EXCLUDED.execution_id,
                  EXCLUDED.order_id,
                  EXCLUDED.instrument_id,
                  EXCLUDED.quantity_units,
                  EXCLUDED.execution_price,
                  EXCLUDED.currency,
                  EXCLUDED.run_id,
                  EXCLUDED.occurred_at,
                  EXCLUDED.liquidity_role
                )
                """.trimIndent()
            ).use { ps ->
                executions.forEach { execution ->
                    ps.setString(1, execution.eventId)
                    ps.setString(2, execution.executionId)
                    ps.setString(3, execution.orderId)
                    ps.setString(4, execution.instrumentId)
                    ps.setString(5, execution.quantityUnits)
                    ps.setString(6, execution.executionPrice)
                    ps.setString(7, execution.currency)
                    ps.setString(8, execution.occurredAt)
                    ps.setString(9, execution.liquidityRole)
                    ps.setString(10, execution.runId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            markOrderLifecycleDirty(conn, executions.map { RuntimeOrderIdentity(it.runId, it.orderId) })
        }
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        if (trades.isEmpty()) return
        projectionTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.trades}(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET
                  event_id = ${names.rejectTradeReplayConflictFunction}(EXCLUDED.event_id)
                WHERE ROW(
                  ${names.trades}.trade_id,
                  ${names.trades}.execution_id,
                  ${names.trades}.buy_order_id,
                  ${names.trades}.sell_order_id,
                  ${names.trades}.instrument_id,
                  ${names.trades}.quantity_units,
                  ${names.trades}.price,
                  ${names.trades}.currency,
                  ${names.trades}.run_id,
                  ${names.trades}.occurred_at
                ) IS DISTINCT FROM ROW(
                  EXCLUDED.trade_id,
                  EXCLUDED.execution_id,
                  EXCLUDED.buy_order_id,
                  EXCLUDED.sell_order_id,
                  EXCLUDED.instrument_id,
                  EXCLUDED.quantity_units,
                  EXCLUDED.price,
                  EXCLUDED.currency,
                  EXCLUDED.run_id,
                  EXCLUDED.occurred_at
                )
                """.trimIndent()
            ).use { ps ->
                trades.forEach { trade ->
                    ps.setString(1, trade.eventId)
                    ps.setString(2, trade.tradeId)
                    ps.setString(3, trade.executionId)
                    ps.setString(4, trade.buyOrderId)
                    ps.setString(5, trade.sellOrderId)
                    ps.setString(6, trade.instrumentId)
                    ps.setString(7, trade.quantityUnits)
                    ps.setString(8, trade.price)
                    ps.setString(9, trade.currency)
                    ps.setString(10, trade.occurredAt)
                    ps.setString(11, trade.runId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            markOrderLifecycleDirty(conn, trades.flatMap { listOf(RuntimeOrderIdentity(it.runId, it.buyOrderId), RuntimeOrderIdentity(it.runId, it.sellOrderId)) })
        }
    }

    override fun saveEvent(event: RuntimeEvent) {
        saveEvents(listOf(event))
    }

    override fun saveEvents(events: List<RuntimeEvent>) {
        if (events.isEmpty()) return
        require(events.map { it.eventId }.toSet().size == events.size) { "duplicate runtime event ID in batch" }
        val payload = "[{\"events\":${events.toJsonArray { it.toJsonObject() }}}]"
        projectionTransaction { conn ->
            conn.prepareStatement("SELECT ${names.persistSubmitOutcomeTimelineStageFunction}(?::jsonb)").use { ps ->
                ps.setString(1, payload)
                ps.executeQuery().close()
            }
        }
    }

    private fun <T> projectionTransaction(action: (Connection) -> T): T = projectionConnection().use { conn ->
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = action(conn)
            conn.commit()
            result
        } catch (error: Exception) {
            conn.rollback()
            throw error
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    private fun markOrderLifecycleDirty(conn: Connection, identities: List<RuntimeOrderIdentity>) {
        conn.prepareStatement(
            "INSERT INTO ${names.orderLifecycleDirty} AS dirty(run_id, order_id) VALUES (?, ?) " +
                "ON CONFLICT (run_id, order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at WHERE FALSE"
        ).use { ps ->
            identities.distinct().sortedWith(compareBy<RuntimeOrderIdentity> { it.runId }.thenBy { it.orderId }).forEach {
                ps.setString(1, it.runId)
                ps.setString(2, it.orderId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun RuntimeEvent.modifyFacts(): RuntimeEventModifyFacts {
        if (eventType != "OrderModified") return RuntimeEventModifyFacts()
        val payload = JsonCodec.parseLegacyObjectOrEmpty(payloadJson)
        return RuntimeEventModifyFacts(
            quantityUnits = payload.string("quantityUnits"),
            limitPrice = payload.string("limitPrice")
        )
    }

    private data class RuntimeEventModifyFacts(
        val quantityUnits: String = "",
        val limitPrice: String = ""
    )

    override fun acceptedOrder(identity: RuntimeOrderIdentity): PersistedOrder? = projectionQueryList(
        "SELECT $OrderSelectColumns FROM ${names.orders} WHERE run_id = ? AND order_id = ?",
        identity.runId, identity.orderId
    ) { toPersistedOrder() }.singleOrNull()

    override fun acceptedOrdersByIdentity(identities: Set<RuntimeOrderIdentity>): Map<RuntimeOrderIdentity, PersistedOrder> {
        if (identities.isEmpty()) return emptyMap()
        val predicates = identities.joinToString(" OR ") { "(run_id = ? AND order_id = ?)" }
        val params = identities.flatMap { listOf(it.runId, it.orderId) }.toTypedArray()
        return projectionQueryList("SELECT $OrderSelectColumns FROM ${names.orders} WHERE $predicates", *params) {
            toPersistedOrder()
        }.associateBy { RuntimeOrderIdentity(it.runId, it.orderId) }
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? = projectionQueryList(
        "SELECT $OrderSelectColumns FROM ${names.orders} WHERE order_id = ? LIMIT 2", orderId
    ) { toPersistedOrder() }.singleOrNull()

    override fun acceptedOrders(orderIds: Set<String>): Map<String, PersistedOrder> {
        if (orderIds.isEmpty()) return emptyMap()
        val placeholders = orderIds.joinToString(",") { "?" }
        return projectionQueryList(
            "SELECT $OrderSelectColumns FROM ${names.orders} WHERE order_id IN ($placeholders)",
            *orderIds.toTypedArray()
        ) { toPersistedOrder() }.groupBy { it.orderId }.mapNotNull { (id, rows) -> rows.singleOrNull()?.let { id to it } }.toMap()
    }

    override fun findOrderByClientOrderId(participantId: String, clientOrderId: String, runId: String?): PersistedOrder? {
        val runFilter = if (runId == null) "" else "AND run_id = ?"
        val params = listOfNotNull(participantId, clientOrderId, runId).toTypedArray()
        val candidates = projectionQueryList(
            "SELECT $OrderSelectColumns FROM ${names.orders} WHERE participant_id = ? AND client_order_id = ? $runFilter " +
                "ORDER BY accepted_at_ts DESC NULLS LAST, accepted_at DESC, order_id DESC", *params
        ) { toPersistedOrder() }
        return if (candidates.map { it.runId }.distinct().size > 1) null else candidates.firstOrNull()
    }

    override fun acceptedOrders(): List<PersistedOrder> = projectionQueryList(
        """
        SELECT $OrderSelectColumns
        FROM ${names.orders} ORDER BY accepted_at_ts NULLS LAST, accepted_at, order_id
        """.trimIndent()
    ) {
        toPersistedOrder()
    }

    private fun java.sql.ResultSet.toPersistedOrder(): PersistedOrder {
        return PersistedOrder(
            orderId = getString("order_id"),
            engineOrderId = getString("engine_order_id"),
            instrumentId = getString("instrument_id"),
            participantId = getString("participant_id"),
            accountId = getString("account_id"),
            side = getString("side"),
            orderType = getString("order_type"),
            quantityUnits = getString("quantity_units"),
            limitPrice = getString("limit_price"),
            currency = getString("currency"),
            timeInForce = getString("time_in_force"),
            acceptedAt = getString("accepted_at"),
            clientOrderId = getString("client_order_id"),
            runId = getString("run_id"),
            venueSessionId = getString("venue_session_id")
        )
    }

    override fun ordersForParticipant(
        participantId: String,
        openOnly: Boolean,
        instrumentId: String,
        limit: Int
    ): List<OwnOrderView> {
        val statusFilter = if (openOnly) "AND ols.status IN ('OPEN', 'PARTIALLY_FILLED')" else ""
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND ols.instrument_id = ?"
        val boundedLimit = limit.coerceIn(0, 500)
        val limitClause = if (boundedLimit > 0) "LIMIT ?::integer" else ""
        val params = buildList {
            add(participantId)
            if (instrumentId.isNotBlank()) add(instrumentId)
            if (boundedLimit > 0) add(boundedLimit.toString())
        }
        val lifecycleOrders = projectionQueryList(
            """
            SELECT ols.order_id, ols.instrument_id, ols.side, ols.original_quantity_units, ols.remaining_quantity_units, ols.limit_price, ols.status
            FROM ${names.orderLifecycleState} ols
            WHERE ols.participant_id = ?
            $instrumentFilter
            $statusFilter
            ORDER BY ols.accepted_at
            $limitClause
            """.trimIndent(),
            *params.toTypedArray()
        ) {
            OwnOrderView(
                orderId = getString("order_id"),
                instrumentId = getString("instrument_id"),
                side = getString("side"),
                quantityUnits = getString("original_quantity_units"),
                remainingQuantityUnits = getString("remaining_quantity_units"),
                limitPrice = getString("limit_price"),
                status = getString("status")
            )
        }
        if (lifecycleOrders.isNotEmpty()) return lifecycleOrders
        return acceptedOrdersForParticipantFallback(participantId, instrumentId, boundedLimit)
    }

    private fun acceptedOrdersForParticipantFallback(
        participantId: String,
        instrumentId: String,
        boundedLimit: Int
    ): List<OwnOrderView> {
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND instrument_id = ?"
        val limitClause = if (boundedLimit > 0) "LIMIT ?::integer" else ""
        val params = buildList {
            add(participantId)
            if (instrumentId.isNotBlank()) add(instrumentId)
            if (boundedLimit > 0) add(boundedLimit.toString())
        }
        val sql = """
            SELECT order_id, instrument_id, side, quantity_units, limit_price
            FROM ${names.orders}
            WHERE participant_id = ?
            $instrumentFilter
            ORDER BY accepted_at_ts DESC NULLS LAST, accepted_at DESC, order_id DESC
            $limitClause
            """.trimIndent()
        val mapOrder: java.sql.ResultSet.() -> OwnOrderView = {
            OwnOrderView(
                orderId = getString("order_id"),
                instrumentId = getString("instrument_id"),
                side = getString("side"),
                quantityUnits = getString("quantity_units"),
                remainingQuantityUnits = getString("quantity_units"),
                limitPrice = getString("limit_price"),
                status = "OPEN"
            )
        }
        val projectionOrders = projectionQueryList(sql, *params.toTypedArray(), map = mapOrder)
        if (projectionOrders.isNotEmpty() || !projectionStoreSeparated()) return projectionOrders
        return queryList(sql, *params.toTypedArray(), map = mapOrder)
    }

    override fun executionsForParticipant(
        participantId: String,
        instrumentId: String,
        runId: String,
        limit: Int
    ): List<OwnExecutionView> {
        val instrumentFilter = if (instrumentId.isBlank()) "" else "AND e.instrument_id = ?"
        val runFilter = if (runId.isBlank()) "" else "AND o.run_id = ?"
        val boundedLimit = limit.coerceIn(0, 500)
        val limitClause = if (boundedLimit > 0) "LIMIT ?::integer" else ""
        val params = buildList {
            add(participantId)
            if (instrumentId.isNotBlank()) add(instrumentId)
            if (runId.isNotBlank()) add(runId)
            if (boundedLimit > 0) add(boundedLimit.toString())
        }
        return projectionQueryList(
            """
            SELECT e.execution_id, e.order_id, e.instrument_id, o.side, e.quantity_units, e.execution_price, e.currency, e.occurred_at, e.liquidity_role
            FROM ${names.executions} e
            JOIN ${names.orders} o ON o.run_id = e.run_id AND o.order_id = e.order_id
            WHERE o.participant_id = ?
            $instrumentFilter
            $runFilter
            ORDER BY e.occurred_at_ts NULLS LAST, e.occurred_at, e.execution_id
            $limitClause
            """.trimIndent(),
            *params.toTypedArray()
        ) {
            OwnExecutionView(
                executionId = getString("execution_id"),
                orderId = getString("order_id"),
                instrumentId = getString("instrument_id"),
                side = getString("side"),
                quantityUnits = getString("quantity_units"),
                executionPrice = getString("execution_price"),
                currency = getString("currency"),
                occurredAt = getString("occurred_at"),
                liquidityRole = getString("liquidity_role")
            )
        }
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> = projectionQueryList(
        "SELECT run_id, event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at, liquidity_role FROM ${names.executions} WHERE order_id = ? ORDER BY occurred_at_ts NULLS LAST, occurred_at, event_id",
        orderId
    ) {
        ExecutionCreated(
            eventId = getString("event_id"),
            executionId = getString("execution_id"),
            orderId = getString("order_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            executionPrice = getString("execution_price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at"),
            liquidityRole = getString("liquidity_role"),
            runId = getString("run_id")
        )
    }

    override fun trades(): List<TradeCreated> = projectionQueryList(
        "SELECT run_id, event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM ${names.trades} ORDER BY occurred_at_ts NULLS LAST, occurred_at, event_id"
    ) {
        toTradeCreated()
    }

    override fun recentTrades(limit: Int): List<TradeCreated> = projectionQueryList(
        "SELECT run_id, event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at FROM ${names.trades} ORDER BY occurred_at_ts DESC NULLS LAST, occurred_at DESC, event_id DESC LIMIT ?::integer",
        limit.coerceIn(0, 500).toString()
    ) {
        toTradeCreated()
    }.asReversed()

    override fun tradesForOrder(orderId: String): List<TradeCreated> = projectionQueryList(
        """
        SELECT run_id, event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at
        FROM ${names.trades} WHERE buy_order_id = ? OR sell_order_id = ? ORDER BY occurred_at_ts NULLS LAST, occurred_at, event_id
        """.trimIndent(),
        orderId,
        orderId
    ) {
        toTradeCreated()
    }

    override fun tradesForSettlementMaterialization(scenarioRunId: String, venueSessionId: String): List<TradeCreated> {
        val venueSessionFilter = if (venueSessionId.isBlank()) {
            ""
        } else {
            """
            AND (
                COALESCE(NULLIF(buy_order.venue_session_id, ''), NULLIF(sell_order.venue_session_id, ''), ?) = ?
                AND (
                    buy_order.venue_session_id = ''
                    OR sell_order.venue_session_id = ''
                    OR buy_order.venue_session_id = sell_order.venue_session_id
                )
            )
            """.trimIndent()
        }
        val params = buildList {
            add(scenarioRunId)
            add(scenarioRunId)
            if (venueSessionId.isNotBlank()) {
                add(venueSessionId)
                add(venueSessionId)
            }
        }
        return projectionQueryList(
            """
            SELECT trade.run_id, trade.event_id, trade.trade_id, trade.execution_id, trade.buy_order_id, trade.sell_order_id,
                   trade.instrument_id, trade.quantity_units, trade.price, trade.currency, trade.occurred_at
            FROM ${names.trades} trade
            JOIN ${names.orders} buy_order ON buy_order.run_id = trade.run_id AND buy_order.order_id = trade.buy_order_id
            JOIN ${names.orders} sell_order ON sell_order.run_id = trade.run_id AND sell_order.order_id = trade.sell_order_id
            WHERE COALESCE(NULLIF(buy_order.run_id, ''), NULLIF(sell_order.run_id, ''), ?) = ?
              AND (
                  buy_order.run_id = ''
                  OR sell_order.run_id = ''
                  OR buy_order.run_id = sell_order.run_id
              )
              $venueSessionFilter
            ORDER BY trade.occurred_at_ts NULLS LAST, trade.occurred_at, trade.event_id
            """.trimIndent(),
            *params.toTypedArray()
        ) {
            toTradeCreated()
        }
    }

    private fun java.sql.ResultSet.toTradeCreated(): TradeCreated {
        return TradeCreated(
            eventId = getString("event_id"),
            tradeId = getString("trade_id"),
            executionId = getString("execution_id"),
            buyOrderId = getString("buy_order_id"),
            sellOrderId = getString("sell_order_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            price = getString("price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at"),
            runId = getString("run_id")
        )
    }

    override fun tradeTape(instrumentId: String, limit: Int, beforeSequence: Long?): List<PublicTradeTapeEntry> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return if (beforeSequence != null) {
            projectionQueryList(
                """
                SELECT sequence, trade_id, instrument_id, quantity_units, price, currency, occurred_at
                FROM ${names.trades}
                WHERE instrument_id = ? AND sequence < ?::bigint
                ORDER BY sequence DESC
                LIMIT ?::integer
                """.trimIndent(),
                instrumentId,
                beforeSequence.toString(),
                effectiveLimit.toString()
            ) { toPublicTradeTapeEntry() }
        } else {
            projectionQueryList(
                """
                SELECT sequence, trade_id, instrument_id, quantity_units, price, currency, occurred_at
                FROM ${names.trades}
                WHERE instrument_id = ?
                ORDER BY sequence DESC
                LIMIT ?::integer
                """.trimIndent(),
                instrumentId,
                effectiveLimit.toString()
            ) { toPublicTradeTapeEntry() }
        }
    }

    override fun intradayBars(instrumentId: String, interval: String, start: String, end: String): List<IntradayBar> {
        val intervalText = intradayBarIntervalText[interval] ?: return emptyList()
        return projectionQueryList(
            """
            WITH bucketed AS (
              SELECT
                date_bin(?::interval, occurred_at_ts, TIMESTAMPTZ '2000-01-01') AS bucket_start,
                price_num,
                quantity_units_num AS qty_num,
                sequence
              FROM ${names.trades}
              WHERE instrument_id = ?
                AND occurred_at_ts >= ?::timestamptz
                AND occurred_at_ts < ?::timestamptz
                AND price_num IS NOT NULL
                AND quantity_units_num IS NOT NULL
            )
            SELECT
              bucket_start::text AS bucket_start,
              (bucket_start + ?::interval)::text AS bucket_end,
              (array_agg(price_num ORDER BY sequence ASC))[1] AS open,
              MAX(price_num) AS high,
              MIN(price_num) AS low,
              (array_agg(price_num ORDER BY sequence DESC))[1] AS close,
              SUM(qty_num) AS volume
            FROM bucketed
            GROUP BY bucket_start
            ORDER BY bucket_start
            """.trimIndent(),
            intervalText,
            instrumentId,
            start,
            end,
            intervalText
        ) {
            IntradayBar(
                instrumentId = instrumentId,
                start = getString("bucket_start"),
                end = getString("bucket_end"),
                open = getString("open"),
                high = getString("high"),
                low = getString("low"),
                close = getString("close"),
                volume = getString("volume")
            )
        }
    }

    private fun java.sql.ResultSet.toPublicTradeTapeEntry(): PublicTradeTapeEntry {
        return PublicTradeTapeEntry(
            sequence = getLong("sequence"),
            tradeId = getString("trade_id"),
            instrumentId = getString("instrument_id"),
            quantityUnits = getString("quantity_units"),
            price = getString("price"),
            currency = getString("currency"),
            occurredAt = getString("occurred_at")
        )
    }

    override fun eventsForOrder(orderId: String): List<RuntimeEvent> = queryEvents(
        """
        $runtimeEventSelect
        WHERE events.order_id = ?
        ORDER BY events.trace_id, events.sequence_number
        """.trimIndent(),
        orderId
    )

    override fun eventsForTrace(traceId: String): List<RuntimeEvent> = queryEvents(
        """
        $runtimeEventSelect
        WHERE events.trace_id = ?
        ORDER BY events.sequence_number
        """.trimIndent(),
        traceId
    )

    override fun events(): List<RuntimeEvent> = queryEvents(
        """
        $runtimeEventSelect
        ORDER BY events.trace_id, events.sequence_number
        """.trimIndent()
    )

    override fun recentEvents(limit: Int): List<RuntimeEvent> = queryEvents(
        """
        $runtimeEventSelect
        ORDER BY events.occurred_at_ts DESC NULLS LAST, events.event_id_uuid DESC NULLS LAST, events.event_id DESC
        LIMIT ?::integer
        """.trimIndent(),
        limit.coerceIn(0, 500).toString()
    ).asReversed()

    private val runtimeEventSelect: String
        get() = """
            SELECT
              events.event_id,
              events.event_type,
              events.run_id,
              events.order_id,
              events.trace_id,
              events.causation_id,
              events.correlation_id,
              events.actor_id,
              events.producer,
              events.schema_version,
              events.sequence_number,
              COALESCE(payloads.payload_json, events.payload_json)::TEXT AS payload_json,
              events.occurred_at
            FROM ${names.runtimeEvents} events
            LEFT JOIN ${names.runtimeEventPayloads} payloads ON payloads.event_id = events.event_id
        """.trimIndent()

    private fun queryEvents(sql: String, vararg params: String): List<RuntimeEvent> = projectionQueryList(sql, *params) {
        RuntimeEvent(
            eventId = getString("event_id"),
            eventType = getString("event_type"),
            orderId = getString("order_id"),
            traceId = getString("trace_id"),
            causationId = getString("causation_id"),
            correlationId = getString("correlation_id"),
            producer = getString("producer"),
            schemaVersion = getString("schema_version"),
            sequenceNumber = getLong("sequence_number"),
            occurredAt = getString("occurred_at"),
            actorId = getString("actor_id"),
            payloadJson = getString("payload_json"),
            runId = getString("run_id")
        )
    }

    private fun upsert(table: String, idColumn: String, idValue: String, secondValue: String) {
        val secondColumn = if (idColumn == "instrument_id") "symbol" else "name"
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO $table($idColumn, $secondColumn)
                VALUES (?, ?)
                ON CONFLICT ($idColumn) DO UPDATE SET $secondColumn = EXCLUDED.$secondColumn
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, idValue)
                ps.setString(2, secondValue)
                ps.executeUpdate()
            }
        }
    }

    private fun exists(table: String, idColumn: String, id: String): Boolean {
        connection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM $table WHERE $idColumn = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    private fun <T> queryList(
        sql: String,
        vararg params: String,
        connectionSupplier: () -> Connection = ::canonicalConnection,
        map: java.sql.ResultSet.() -> T
    ): List<T> {
        connectionSupplier().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { idx, value -> ps.setString(idx + 1, value) }
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<T>()
                    while (rs.next()) rows.add(rs.map())
                    return rows
                }
            }
        }
    }

    private fun <T> projectionQueryList(sql: String, vararg params: String, map: java.sql.ResultSet.() -> T): List<T> =
        queryList(sql, *params, connectionSupplier = ::projectionConnection, map = map)

    private fun lifecycleEventType(commandType: String, rejected: Boolean): String {
        if (rejected) return "OrderRejected"
        return when (commandType) {
            "CancelOrder" -> "OrderCancelled"
            "ModifyOrder" -> "OrderModified"
            else -> "OrderAccepted"
        }
    }

    private fun jsonString(json: String, key: String): String {
        val document = JsonCodec.parseLegacyObjectOrEmpty(json)
        return document.string(key)
            .ifBlank { document.obj("accepted").string(key) }
            .ifBlank { document.obj("rejected").string(key) }
    }

    private fun projectionStoreSeparated(): Boolean = projectionDataSource !== dataSource

    private fun connection(): Connection = canonicalConnection()

    private fun canonicalConnection(): Connection = dataSource.connection

    private fun projectionConnection(): Connection = projectionDataSource.connection
}
