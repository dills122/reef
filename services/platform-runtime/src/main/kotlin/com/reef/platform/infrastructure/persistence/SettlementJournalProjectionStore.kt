package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.SettlementJournalControlCodec
import java.math.BigDecimal
import java.sql.Connection
import java.util.Base64
import javax.sql.DataSource

data class SettlementProjectionFrontier(
    val eventStream: String,
    val projectorGeneration: String,
    val journalIncarnationId: String,
    val batchSequence: Long,
    val batchDigest: String,
    val observedJournalSequence: Long,
    val observedJournalDigest: String,
    val lagBatches: Long
)

data class SettlementProjectionAccountRead(
    val account: ReferenceAccountKey,
    val balance: BigDecimal?,
    val frontier: SettlementProjectionFrontier
)

data class SettlementProjectionTradeRead(
    val runId: String,
    val tradeId: String,
    val status: SettlementProjectionTrade?,
    val frontier: SettlementProjectionFrontier
)

data class SettlementProjectionApplyReceipt(
    val batchSequence: Long,
    val batchDigest: String,
    val duplicate: Boolean
)

/** Default-off read model. Projector generation is independent of journal owner epoch. */
class SettlementJournalProjectionStore(
    private val dataSource: DataSource,
    private val schema: String = "settlement",
    private val reducer: SettlementJournalProjectionReducer = SettlementJournalProjectionReducer(),
    private val beforeCommit: () -> Unit = {}
) {
    init { require(schema.matches(Regex("[a-z][a-z0-9_]*"))) }

    fun initialize(eventStream: String, generation: String) {
        require(eventStream.isNotBlank() && generation.isNotBlank())
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val head = journalHead(connection, eventStream)
                insertOrigin(connection, eventStream, generation, head.incarnationId)
                check(checkpoint(connection, eventStream, generation, false).incarnationId ==
                    head.incarnationId) { "projection checkpoint incarnation changed" }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    /** Only a locally verified journal envelope may enter this transaction. */
    fun apply(generation: String, batch: SettlementJournalVerifiedBatch): SettlementProjectionApplyReceipt {
        require(generation.isNotBlank())
        SettlementJournalStore(dataSource, schema).replayProposalCopy(batch)
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val head = journalHead(connection, batch.eventStream)
                check(head.incarnationId == batch.incarnationId) {
                    "projection journal incarnation changed"
                }
                insertOrigin(connection, batch.eventStream, generation, head.incarnationId)
                val checkpoint = checkpoint(connection, batch.eventStream, generation, true)
                check(checkpoint.incarnationId == head.incarnationId) {
                    "projection checkpoint incarnation changed"
                }
                check(batch.batchSequence < head.nextSequence) { "projection batch is not committed" }
                val saved = connection.prepareStatement(
                    """SELECT previous_digest, batch_digest, incarnation_id
                       FROM $schema.settlement_journal_batches
                       WHERE event_stream = ? AND batch_sequence = ?"""
                ).use { statement ->
                    statement.setString(1, batch.eventStream)
                    statement.setLong(2, batch.batchSequence)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "projection journal batch disappeared" }
                        Triple(rows.getString(1), rows.getString(2), rows.getString(3))
                    }
                }
                check(saved.first == batch.previousDigest && saved.second == batch.batchDigest &&
                    saved.third == batch.incarnationId) { "projection verified batch differs from primary" }
                if (checkpoint.sequence >= batch.batchSequence) {
                    check(checkpoint.sequence != batch.batchSequence ||
                        checkpoint.digest == batch.batchDigest) {
                        "projection duplicate batch digest changed"
                    }
                    connection.commit()
                    return@use SettlementProjectionApplyReceipt(batch.batchSequence, batch.batchDigest, true)
                }
                check(checkpoint.sequence + 1 == batch.batchSequence &&
                    checkpoint.digest == batch.previousDigest) {
                    "projection batch gap or predecessor digest changed"
                }
                val touched = touchedAccounts(batch)
                val balances = touched.mapNotNull { account ->
                    accountBalance(connection, batch.eventStream, generation, account)?.let { account to it }
                }.toMap()
                val tradeKeys = batch.results.map { it.runId to it.tradeId }.distinct()
                val trades = tradeKeys.mapNotNull { (runId, tradeId) ->
                    trade(connection, batch.eventStream, generation, runId, tradeId)
                        ?.let { (runId to tradeId) to it }
                }.toMap()
                val delta = reducer.reduce(batch, balances, trades)
                delta.balances.forEach { (account, amount) ->
                    upsertBalance(connection, batch.eventStream, generation, account, amount)
                }
                delta.trades.values.forEach { status ->
                    upsertTrade(connection, batch.eventStream, generation, status)
                }
                connection.prepareStatement(
                    """UPDATE $schema.settlement_journal_projection_checkpoints
                       SET last_batch_sequence = ?, last_batch_digest = ?
                       WHERE event_stream = ? AND projector_generation = ?"""
                ).use { statement ->
                    statement.setLong(1, batch.batchSequence)
                    statement.setString(2, batch.batchDigest)
                    statement.setString(3, batch.eventStream)
                    statement.setString(4, generation)
                    check(statement.executeUpdate() == 1)
                }
                beforeCommit()
                connection.commit()
                SettlementProjectionApplyReceipt(batch.batchSequence, batch.batchDigest, false)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    fun readAccount(eventStream: String, generation: String, account: ReferenceAccountKey,
        requireCurrent: Boolean = false): SettlementProjectionAccountRead =
        snapshot(eventStream, generation, requireCurrent) { connection, frontier ->
            SettlementProjectionAccountRead(account,
                accountBalance(connection, eventStream, generation, account), frontier)
        }

    fun readTrade(eventStream: String, generation: String, runId: String, tradeId: String,
        requireCurrent: Boolean = false): SettlementProjectionTradeRead =
        snapshot(eventStream, generation, requireCurrent) { connection, frontier ->
            SettlementProjectionTradeRead(runId, tradeId,
                trade(connection, eventStream, generation, runId, tradeId), frontier)
        }

    private fun <T> snapshot(eventStream: String, generation: String, requireCurrent: Boolean,
        read: (Connection, SettlementProjectionFrontier) -> T): T {
        require(eventStream.isNotBlank() && generation.isNotBlank())
        return dataSource.connection.use { connection ->
            val priorIsolation = connection.transactionIsolation
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val checkpoint = checkpoint(connection, eventStream, generation, false)
                val head = journalHead(connection, eventStream)
                check(checkpoint.incarnationId == head.incarnationId) {
                    "projection journal incarnation changed"
                }
                val observedSequence = head.nextSequence - 1
                val lag = observedSequence - checkpoint.sequence
                check(lag >= 0) { "projection checkpoint exceeds journal" }
                if (lag == 0L) check(checkpoint.digest == head.digest) {
                    "projection digest differs from journal head"
                }
                if (requireCurrent) check(lag == 0L) { "projection is behind journal head" }
                val frontier = SettlementProjectionFrontier(eventStream, generation,
                    checkpoint.incarnationId, checkpoint.sequence, checkpoint.digest,
                    observedSequence, head.digest, lag)
                val value = read(connection, frontier)
                connection.commit()
                value
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.transactionIsolation = priorIsolation
            }
        }
    }

    private data class JournalHead(val nextSequence: Long, val digest: String, val incarnationId: String)
    private data class Checkpoint(val sequence: Long, val digest: String, val incarnationId: String)

    private fun journalHead(connection: Connection, stream: String): JournalHead =
        connection.prepareStatement(
            """SELECT next_batch_sequence, last_batch_digest, incarnation_id
               FROM $schema.settlement_journal_heads WHERE event_stream = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "projection journal head is missing" }
                JournalHead(rows.getLong(1), rows.getString(2), rows.getString(3))
            }
        }

    private fun insertOrigin(connection: Connection, stream: String, generation: String, incarnation: String) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_projection_checkpoints
               (event_stream, projector_generation, journal_incarnation_id, last_batch_digest)
               VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, incarnation)
            statement.setString(4, SettlementJournalStore.ORIGIN_DIGEST)
            statement.executeUpdate()
        }
    }

    private fun checkpoint(connection: Connection, stream: String, generation: String,
        lock: Boolean): Checkpoint =
        connection.prepareStatement(
            """SELECT last_batch_sequence, last_batch_digest, journal_incarnation_id
               FROM $schema.settlement_journal_projection_checkpoints
               WHERE event_stream = ? AND projector_generation = ?${if (lock) " FOR UPDATE" else ""}"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "projection generation is not initialized" }
                Checkpoint(rows.getLong(1), rows.getString(2), rows.getString(3))
            }
        }

    private fun touchedAccounts(batch: SettlementJournalVerifiedBatch): Set<ReferenceAccountKey> =
        buildSet {
            batch.controls.forEach { member ->
                when (val control = SettlementJournalControlCodec.decode(
                    Base64.getDecoder().decode(member.payloadBase64))) {
                    is ReferenceOpening -> add(control.account)
                    is ReferenceFunding -> add(control.account)
                    else -> Unit
                }
            }
            batch.results.forEach { result ->
                add(ReferenceAccountKey(result.runId, result.buyerParticipantId,
                    result.buyerAccountId, "CASH", result.currency))
                add(ReferenceAccountKey(result.runId, result.sellerParticipantId,
                    result.sellerAccountId, "CASH", result.currency))
                add(ReferenceAccountKey(result.runId, result.sellerParticipantId,
                    result.sellerAccountId, "SECURITY", result.instrumentId))
                add(ReferenceAccountKey(result.runId, result.buyerParticipantId,
                    result.buyerAccountId, "SECURITY", result.instrumentId))
            }
        }

    private fun accountBalance(connection: Connection, stream: String, generation: String,
        account: ReferenceAccountKey): BigDecimal? =
        connection.prepareStatement(
            """SELECT amount FROM $schema.settlement_journal_projection_balances
               WHERE event_stream = ? AND projector_generation = ? AND run_id = ?
                 AND participant_id = ? AND account_id = ? AND asset_type = ? AND asset_id = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, account.runId)
            statement.setString(4, account.participantId)
            statement.setString(5, account.accountId)
            statement.setString(6, account.assetType)
            statement.setString(7, account.assetId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getBigDecimal(1) else null }
        }

    private fun trade(connection: Connection, stream: String, generation: String,
        runId: String, tradeId: String): SettlementProjectionTrade? =
        connection.prepareStatement(
            """SELECT attempt_number, outcome, break_reason, result_batch_sequence, result_index
               FROM $schema.settlement_journal_projection_trades
               WHERE event_stream = ? AND projector_generation = ? AND run_id = ? AND trade_id = ?"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, runId)
            statement.setString(4, tradeId)
            statement.executeQuery().use { rows ->
                if (rows.next()) SettlementProjectionTrade(runId, tradeId, rows.getInt(1),
                    rows.getString(2), rows.getString(3), rows.getLong(4), rows.getInt(5))
                else null
            }
        }

    private fun upsertBalance(connection: Connection, stream: String, generation: String,
        account: ReferenceAccountKey, amount: BigDecimal) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_projection_balances
               (event_stream, projector_generation, run_id, participant_id, account_id,
                asset_type, asset_id, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (event_stream, projector_generation, run_id, participant_id,
                            account_id, asset_type, asset_id)
               DO UPDATE SET amount = EXCLUDED.amount"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, account.runId)
            statement.setString(4, account.participantId)
            statement.setString(5, account.accountId)
            statement.setString(6, account.assetType)
            statement.setString(7, account.assetId)
            statement.setBigDecimal(8, amount)
            statement.executeUpdate()
        }
    }

    private fun upsertTrade(connection: Connection, stream: String, generation: String,
        trade: SettlementProjectionTrade) {
        connection.prepareStatement(
            """INSERT INTO $schema.settlement_journal_projection_trades
               (event_stream, projector_generation, run_id, trade_id, attempt_number,
                outcome, break_reason, result_batch_sequence, result_index)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (event_stream, projector_generation, run_id, trade_id)
               DO UPDATE SET attempt_number = EXCLUDED.attempt_number,
                 outcome = EXCLUDED.outcome, break_reason = EXCLUDED.break_reason,
                 result_batch_sequence = EXCLUDED.result_batch_sequence,
                 result_index = EXCLUDED.result_index"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setString(3, trade.runId)
            statement.setString(4, trade.tradeId)
            statement.setInt(5, trade.attemptNumber)
            statement.setString(6, trade.outcome)
            statement.setString(7, trade.breakReason)
            statement.setLong(8, trade.batchSequence)
            statement.setInt(9, trade.resultIndex)
            statement.executeUpdate()
        }
    }
}
