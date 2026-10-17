package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.util.UUID
import javax.sql.DataSource

class PostgresBoundaryRejectionLog(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : BoundaryRejectionLog {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryRejections(names.boundaryRejections)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.boundaryRejections} (
                      rejection_id TEXT NOT NULL PRIMARY KEY,
                      rejected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      guardrail_type TEXT NOT NULL,
                      scope_type TEXT NOT NULL DEFAULT '',
                      scope_id TEXT NOT NULL DEFAULT '',
                      status INTEGER NOT NULL,
                      code TEXT NOT NULL,
                      message TEXT NOT NULL,
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      command_type TEXT NOT NULL,
                      command_id TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      actor_id TEXT NOT NULL,
                      participant_id TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      bot_id TEXT NOT NULL,
                      run_id TEXT NOT NULL,
                      venue_session_id TEXT NOT NULL,
                      instrument_id TEXT NOT NULL,
                      order_id TEXT NOT NULL,
                      quantity_units TEXT NOT NULL DEFAULT '',
                      limit_price TEXT NOT NULL DEFAULT '',
                      currency TEXT NOT NULL DEFAULT '',
                      payload_hash TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_boundary_rejections_guardrail_time
                      ON ${names.boundaryRejections}(guardrail_type, rejected_at DESC)
                    """.trimIndent()
                )
            }
        }
    }

    override fun recordRejection(
        guardrailType: String,
        scopeType: String,
        scopeId: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.boundaryRejections}(
                  rejection_id,
                  guardrail_type,
                  scope_type,
                  scope_id,
                  status,
                  code,
                  message,
                  client_id,
                  route,
                  command_type,
                  command_id,
                  idempotency_key,
                  correlation_id,
                  actor_id,
                  participant_id,
                  account_id,
                  bot_id,
                  run_id,
                  venue_session_id,
                  instrument_id,
                  order_id,
                  quantity_units,
                  limit_price,
                  currency,
                  payload_hash
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, guardrailType)
                ps.setString(3, scopeType)
                ps.setString(4, scopeId)
                ps.setInt(5, error.status)
                ps.setString(6, error.code)
                ps.setString(7, error.message)
                ps.setString(8, request.clientId)
                ps.setString(9, request.route)
                ps.setString(10, request.commandType)
                ps.setString(11, request.commandId)
                ps.setString(12, request.idempotencyKey)
                ps.setString(13, request.correlationId)
                ps.setString(14, request.actorId)
                ps.setString(15, request.participantId)
                ps.setString(16, request.accountId)
                ps.setString(17, request.botId)
                ps.setString(18, request.runId)
                ps.setString(19, request.venueSessionId)
                ps.setString(20, request.instrumentId)
                ps.setString(21, request.orderId)
                ps.setString(22, request.quantityUnits)
                ps.setString(23, request.limitPrice)
                ps.setString(24, request.currency)
                ps.setString(25, request.payloadHash)
                ps.executeUpdate()
            }
        }
    }

    private fun connection() = dataSource.connection
}
