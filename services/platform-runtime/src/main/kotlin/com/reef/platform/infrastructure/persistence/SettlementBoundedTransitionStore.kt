package com.reef.platform.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

data class SettlementTransitionObligation(
    val streamSequence: Long,
    val effectOrdinal: Int,
    val tradeId: String,
    val eventId: String,
    val runId: String,
    val venueSessionId: String,
    val profileId: String,
    val policyVersion: Int,
    val mode: String,
    val settlementCycle: String,
    val nettingMode: String,
    val ledgerPostingMode: String,
    val selectionSource: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val buyerAccountId: String,
    val sellerAccountId: String,
    val instrumentId: String,
    val quantityUnits: BigDecimal,
    val cashAmount: BigDecimal,
    val currency: String,
    val occurredAt: Instant,
    val status: String
)

class SettlementTransitionWindow internal constructor(
    val eventStream: String,
    val sourceGeneration: String,
    val partitionId: Int,
    val fromExclusiveSequence: Long,
    val throughInclusiveSequence: Long,
    val obligations: List<SettlementTransitionObligation>,
    val obligationDigest: String
)

class SettlementTransitionWindowTooLarge : IllegalArgumentException("settlement transition obligation window exceeds configured bound")

enum class SettlementExecutionReadiness { NONE, BLOCKED, READY }

/** Diagnostic only: SQL call time includes counter row-lock wait and execution. */
class SettlementAdmissionTiming {
    var counterCallNanos: Long = 0
        internal set
    var predecessorCount: Int = 0
        internal set
}

/** Shadow-only bounded settlement transition over committed obligations. */
class SettlementBoundedTransitionStore(
    private val dataSource: DataSource,
    private val afterLedgerInsert: () -> Unit = {}
) {
    private val mapper = ObjectMapper()
    private val intakeStore = SettlementBoundedObligationStore(dataSource)

    private data class AccountKey(
        val runId: String, val participantId: String, val accountId: String,
        val assetType: String, val assetId: String
    )

    private data class AccountBalance(
        val opening: BigDecimal, val positionCount: Long, val originalDelta: BigDecimal,
        val version: Long, var delta: BigDecimal
    ) {
        fun available(): BigDecimal = opening + delta
    }

    private data class LedgerLeg(
        val kind: String, val key: AccountKey, val direction: String, val quantity: BigDecimal
    )

    private data class TransitionDecision(
        val obligation: SettlementTransitionObligation,
        val outcome: String,
        val breakReason: String?,
        val workflow: String,
        val ledgerLegs: List<LedgerLeg>
    )

    private data class AccountPosting(val tradeId: String, val leg: LedgerLeg)

    private fun postings(decisions: List<TransitionDecision>): Map<AccountKey, List<AccountPosting>> =
        decisions.flatMap { decision ->
            decision.ledgerLegs.map { AccountPosting(decision.obligation.tradeId, it) }
        }.groupBy { it.leg.key }

    private fun postingDigest(postings: List<AccountPosting>): String = sha256(mapper.writeValueAsString(
        postings.map { posting ->
            listOf(posting.tradeId, posting.leg.kind, posting.leg.direction,
                posting.leg.quantity.stripTrailingZeros().toPlainString())
        }
    ))

    fun readNextWindow(
        eventStream: String, partitionId: Int, sourceGeneration: String,
        maxSourcePositions: Int = 100, maxObligations: Int = 1000
    ): SettlementTransitionWindow? = readWindow(eventStream, partitionId, sourceGeneration,
        maxSourcePositions, maxObligations, "canonical_transition_frontiers")

    fun readNextAdmissionWindow(
        eventStream: String, partitionId: Int, sourceGeneration: String,
        maxSourcePositions: Int = 100, maxObligations: Int = 1000
    ): SettlementTransitionWindow? = readWindow(eventStream, partitionId, sourceGeneration,
        maxSourcePositions, maxObligations, "canonical_transition_admission_frontiers")

    private fun readWindow(
        eventStream: String, partitionId: Int, sourceGeneration: String,
        maxSourcePositions: Int, maxObligations: Int, frontierTable: String
    ): SettlementTransitionWindow? {
        require(eventStream.isNotBlank() && sourceGeneration.isNotBlank() && partitionId in 0..32767)
        require(maxSourcePositions in 1..5000 && maxObligations in 1..20_000)
        return dataSource.connection.use { connection ->
            val upstream = frontier(connection, "canonical_obligation_frontiers", eventStream, partitionId)
                ?: return@use null
            check(upstream.first == sourceGeneration) { "settlement obligation source generation changed" }
            val own = frontier(connection, frontierTable, eventStream, partitionId)
            check(own == null || own.first == sourceGeneration) { "settlement transition source generation changed" }
            val origin = CanonicalStreamPosition.origin(partitionId)
            val from = own?.second ?: origin
            check(from >= origin && from <= upstream.second) { "settlement transition frontier exceeds obligations" }
            if (from == upstream.second) return@use null
            val through = minOf(from + maxSourcePositions, upstream.second)
            val obligations = readObligations(connection, eventStream, partitionId, sourceGeneration,
                from, through, maxObligations + 1)
            if (obligations.size > maxObligations) throw SettlementTransitionWindowTooLarge()
            verifySource(connection, eventStream, sourceGeneration, partitionId, from, through, obligations)
            SettlementTransitionWindow(eventStream, sourceGeneration, partitionId, from, through,
                obligations, digest(obligations))
        }
    }

    fun readNextAdmittedWindow(
        eventStream: String, partitionId: Int, sourceGeneration: String
    ): SettlementTransitionWindow? = dataSource.connection.use { connection ->
        val completed = frontier(connection, "canonical_transition_frontiers", eventStream, partitionId)
        check(completed == null || completed.first == sourceGeneration) {
            "settlement transition source generation changed"
        }
        val from = completed?.second ?: CanonicalStreamPosition.origin(partitionId)
        val admitted = frontier(connection, "canonical_transition_admission_frontiers", eventStream, partitionId)
        check(admitted == null || admitted.first == sourceGeneration) {
            "settlement admission source generation changed"
        }
        check(admitted == null || admitted.second >= from) {
            "settlement admission frontier trails completed transition"
        }
        connection.prepareStatement(
            """SELECT through_inclusive_sequence, obligation_count, obligation_digest
               FROM settlement.canonical_transition_admissions
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND from_exclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, sourceGeneration)
            statement.setInt(3, partitionId)
            statement.setLong(4, from)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    check(admitted == null || admitted.second == from) {
                        "settlement admitted window is missing before admission frontier"
                    }
                    return@use null
                }
                val through = rows.getLong(1)
                val count = rows.getInt(2)
                val savedDigest = rows.getString(3)
                check(!rows.next() && count in 0..20_000 && through > from && through - from <= 5000) {
                    "settlement admitted window is invalid"
                }
                val obligations = readObligations(connection, eventStream, partitionId, sourceGeneration,
                    from, through, count + 1)
                check(obligations.size == count && digest(obligations) == savedDigest) {
                    "settlement admitted obligations changed"
                }
                verifySource(connection, eventStream, sourceGeneration, partitionId, from, through, obligations)
                SettlementTransitionWindow(eventStream, sourceGeneration, partitionId, from, through,
                    obligations, savedDigest)
            }
        }
    }

    /** Advisory gate: avoid replaying full source/account proofs until the durable predecessors complete. */
    fun nextExecutionReadiness(
        eventStream: String, partitionId: Int, sourceGeneration: String
    ): SettlementExecutionReadiness = dataSource.connection.use { connection ->
        require(eventStream.isNotBlank() && sourceGeneration.isNotBlank() && partitionId in 0..32767)
        val completed = frontier(connection, "canonical_transition_frontiers", eventStream, partitionId)
        check(completed == null || completed.first == sourceGeneration) {
            "settlement transition source generation changed"
        }
        val admitted = frontier(connection, "canonical_transition_admission_frontiers", eventStream, partitionId)
        check(admitted == null || admitted.first == sourceGeneration) {
            "settlement admission source generation changed"
        }
        // Mirror readNextAdmittedWindow: execution cannot skip an earlier admitted window.
        val from = completed?.second ?: CanonicalStreamPosition.origin(partitionId)
        check(admitted == null || admitted.second >= from) {
            "settlement admission frontier trails completed transition"
        }
        val rank = connection.prepareStatement(
            """SELECT admission_rank FROM settlement.canonical_transition_admissions
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND from_exclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, sourceGeneration)
            statement.setInt(3, partitionId)
            statement.setLong(4, from)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else rows.getLong(1).also { check(!rows.next()) }
            }
        }
        if (rank == null) {
            check(admitted == null || admitted.second == from) {
                "settlement admitted window is missing before admission frontier"
            }
            SettlementExecutionReadiness.NONE
        } else if (dependenciesComplete(connection, eventStream, sourceGeneration, rank)) {
            SettlementExecutionReadiness.READY
        } else SettlementExecutionReadiness.BLOCKED
    }

    private fun frontier(connection: Connection, table: String, stream: String, partition: Int): Pair<String, Long>? =
        connection.prepareStatement(
            "SELECT source_generation, last_stream_sequence FROM settlement.$table WHERE event_stream = ? AND partition_id = ?"
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getLong(2) else null
            }
        }

    private fun readObligations(
        connection: Connection, stream: String, partition: Int, generation: String,
        from: Long, through: Long, limit: Int
    ): List<SettlementTransitionObligation> = connection.prepareStatement(
        """SELECT stream_sequence, effect_ordinal, trade_id, event_id, run_id, venue_session_id,
                  post_trade_profile_id, post_trade_policy_version, post_trade_mode,
                  settlement_cycle, netting_mode, ledger_posting_mode, selection_source,
                  buyer_participant_id, seller_participant_id, buyer_account_id, seller_account_id,
                  instrument_id, quantity_units, cash_amount, currency, occurred_at, status
           FROM settlement.canonical_settlement_obligations
           WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
             AND stream_sequence > ? AND stream_sequence <= ?
           ORDER BY stream_sequence, effect_ordinal LIMIT ?"""
    ).use { statement ->
        statement.setString(1, stream)
        statement.setString(2, generation)
        statement.setInt(3, partition)
        statement.setLong(4, from)
        statement.setLong(5, through)
        statement.setInt(6, limit)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(SettlementTransitionObligation(
                    streamSequence = rows.getLong(1), effectOrdinal = rows.getInt(2),
                    tradeId = rows.getString(3), eventId = rows.getString(4),
                    runId = rows.getString(5), venueSessionId = rows.getString(6),
                    profileId = rows.getString(7), policyVersion = rows.getInt(8),
                    mode = rows.getString(9), settlementCycle = rows.getString(10),
                    nettingMode = rows.getString(11), ledgerPostingMode = rows.getString(12),
                    selectionSource = rows.getString(13), buyerParticipantId = rows.getString(14),
                    sellerParticipantId = rows.getString(15), buyerAccountId = rows.getString(16),
                    sellerAccountId = rows.getString(17), instrumentId = rows.getString(18),
                    quantityUnits = rows.getBigDecimal(19), cashAmount = rows.getBigDecimal(20),
                    currency = rows.getString(21), occurredAt = rows.getTimestamp(22).toInstant(),
                    status = rows.getString(23)
                ))
            }
        }
    }

    private fun verifySource(
        connection: Connection, stream: String, generation: String, partition: Int,
        from: Long, through: Long, obligations: List<SettlementTransitionObligation>
    ) {
        val trades = intakeStore.readTrades(connection, stream, partition, generation,
            from, through, obligations.size + 1)
        SettlementIntakeManifest.verify(connection, stream, generation, partition, from, through, trades)
        verifyObligationCoverage(connection, stream, generation, partition, from, through)
        check(trades.size == obligations.size) { "settlement obligations do not cover exact intake trades" }
        trades.zip(obligations).forEach { (trade, obligation) ->
            check(trade.streamSequence == obligation.streamSequence &&
                trade.effectOrdinal == obligation.effectOrdinal && trade.tradeId == obligation.tradeId &&
                trade.eventId == obligation.eventId && trade.runId == obligation.runId &&
                trade.venueSessionId == obligation.venueSessionId &&
                trade.buyerParticipantId == obligation.buyerParticipantId &&
                trade.sellerParticipantId == obligation.sellerParticipantId &&
                trade.buyerAccountId == obligation.buyerAccountId &&
                trade.sellerAccountId == obligation.sellerAccountId &&
                trade.instrumentId == obligation.instrumentId &&
                BigDecimal(trade.quantityUnits).compareTo(obligation.quantityUnits) == 0 &&
                BigDecimal(trade.cashAmount()).compareTo(obligation.cashAmount) == 0 &&
                trade.currency == obligation.currency &&
                Instant.parse(trade.occurredAt).truncatedTo(ChronoUnit.MICROS) == obligation.occurredAt) {
                "settlement obligation differs from canonical intake trade ${trade.tradeId}"
            }
        }
        connection.prepareStatement(
            """SELECT COUNT(*) FROM settlement.canonical_settlement_obligations o
               LEFT JOIN settlement.canonical_policy_bindings b
                 ON b.event_stream = o.event_stream AND b.source_generation = o.source_generation
                AND b.run_id = o.run_id AND b.venue_session_id = o.venue_session_id
               WHERE o.event_stream = ? AND o.source_generation = ? AND o.partition_id = ?
                 AND o.stream_sequence > ? AND o.stream_sequence <= ?
                 AND (b.run_id IS NULL OR b.post_trade_profile_id <> o.post_trade_profile_id
                      OR b.post_trade_policy_version <> o.post_trade_policy_version
                      OR b.post_trade_mode <> o.post_trade_mode OR b.settlement_cycle <> o.settlement_cycle
                      OR b.netting_mode <> o.netting_mode OR b.ledger_posting_mode <> o.ledger_posting_mode
                      OR b.selection_source <> o.selection_source)"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.setInt(3, partition)
            statement.setLong(4, from)
            statement.setLong(5, through)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getLong(1) == 0L) { "settlement obligation policy binding differs" }
            }
        }
    }

    private fun verifyObligationCoverage(
        connection: Connection, stream: String, generation: String, partition: Int,
        from: Long, through: Long
    ) {
        connection.prepareStatement(
            """SELECT source_generation, from_exclusive_sequence, through_inclusive_sequence,
                      trade_count, trade_digest
               FROM settlement.canonical_obligation_coverage
               WHERE event_stream = ? AND partition_id = ?
                 AND through_inclusive_sequence > ? AND from_exclusive_sequence < ?
               ORDER BY through_inclusive_sequence LIMIT 5001"""
        ).use { statement ->
            statement.setString(1, stream)
            statement.setInt(2, partition)
            statement.setLong(3, from)
            statement.setLong(4, through)
            statement.executeQuery().use { rows ->
                var coveredThrough = from
                while (coveredThrough < through && rows.next()) {
                    val sourceGeneration = rows.getString(1)
                    val start = rows.getLong(2)
                    val end = rows.getLong(3)
                    val expectedCount = rows.getInt(4)
                    val expectedDigest = rows.getString(5)
                    check(sourceGeneration == generation &&
                        (if (coveredThrough == from) start <= from else start == coveredThrough) &&
                        end > coveredThrough &&
                        end - start <= 5000 && expectedCount in 0..20_000) {
                        "settlement obligation coverage is missing or invalid"
                    }
                    val coveredTrades = intakeStore.readTrades(connection, stream, partition, generation,
                        start, end, 20_001)
                    check(coveredTrades.size == expectedCount &&
                        SettlementIntakeManifest.digest(coveredTrades) == expectedDigest) {
                        "settlement obligation coverage digest differs from intake"
                    }
                    coveredThrough = end
                }
                check(coveredThrough >= through) { "settlement obligation coverage has a gap" }
                check(!rows.next()) { "settlement obligation coverage overlaps the transition window" }
            }
        }
    }

    private fun digest(obligations: List<SettlementTransitionObligation>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        field("reef.settlement.transition-obligations.v1")
        obligations.forEach { o ->
            listOf(o.streamSequence.toString(), o.effectOrdinal.toString(), o.tradeId,
                o.eventId, o.runId, o.venueSessionId, o.profileId, o.policyVersion.toString(),
                o.mode, o.settlementCycle, o.nettingMode, o.ledgerPostingMode, o.selectionSource,
                o.buyerParticipantId, o.sellerParticipantId, o.buyerAccountId, o.sellerAccountId,
                o.instrumentId, o.quantityUnits.stripTrailingZeros().toPlainString(),
                o.cashAmount.stripTrailingZeros().toPlainString(), o.currency, o.occurredAt.toString()
            ).forEach(::field)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun openingDigest(connection: Connection, obligations: List<SettlementTransitionObligation>): String {
        val runs = obligations.asSequence().filter { it.mode == "instant-post-trade" }
            .map { it.runId }.distinct().sorted().toList()
        val positioned = connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM settlement.canonical_resource_openings WHERE run_id = ?)"
        ).use { statement ->
            runs.map { run ->
                statement.setString(1, run)
                run to statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
            }
        }
        val keys = accountKeys(obligations)
        val openings: List<List<Any?>> = if (keys.isEmpty()) emptyList() else connection.prepareStatement(
            """SELECT requested.run_id, requested.participant_id, requested.account_id,
                      requested.asset_type, requested.asset_id, opening.quantity, opening.position_count
               FROM jsonb_to_recordset(?::jsonb) AS requested(
                 run_id text, participant_id text, account_id text, asset_type text, asset_id text)
               LEFT JOIN settlement.canonical_resource_openings opening
                 ON opening.run_id = requested.run_id AND opening.participant_id = requested.participant_id
                AND opening.account_id = requested.account_id AND opening.asset_type = requested.asset_type
                AND opening.asset_id = requested.asset_id
               ORDER BY requested.run_id, requested.participant_id, requested.account_id,
                        requested.asset_type, requested.asset_id"""
        ).use { statement ->
            statement.setString(1, accountKeyJson(keys))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val quantity = rows.getBigDecimal(6)?.stripTrailingZeros()?.toPlainString()
                        val count = rows.getLong(7).let { if (rows.wasNull()) null else it }
                        add(listOf(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), quantity, count))
                    }
                }
            }
        }
        check(openings.size == keys.size) { "settlement admission opening key set is incomplete" }
        return sha256(mapper.writeValueAsString(listOf(positioned, openings)))
    }

    /** Records a total post-trade order before any balance-dependent decision. */
    fun admit(
        window: SettlementTransitionWindow, timing: SettlementAdmissionTiming? = null
    ): PostMatchApplyResult = dataSource.connection.use { connection ->
        require(window.fromExclusiveSequence >= CanonicalStreamPosition.origin(window.partitionId) &&
            window.throughInclusiveSequence > window.fromExclusiveSequence &&
            window.throughInclusiveSequence - window.fromExclusiveSequence <= 5000)
        check(digest(window.obligations) == window.obligationDigest) {
            "settlement admission input digest changed"
        }
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_transition_admission_frontiers(
                     event_stream, partition_id, source_generation, last_stream_sequence)
                   VALUES (?, ?, ?, ?) ON CONFLICT (event_stream, partition_id) DO NOTHING"""
            ).use { statement ->
                statement.setString(1, window.eventStream)
                statement.setInt(2, window.partitionId)
                statement.setString(3, window.sourceGeneration)
                statement.setLong(4, CanonicalStreamPosition.origin(window.partitionId))
                statement.executeUpdate()
            }
            val frontier = connection.prepareStatement(
                """SELECT source_generation, last_stream_sequence, last_rank
                   FROM settlement.canonical_transition_admission_frontiers
                   WHERE event_stream = ? AND partition_id = ? FOR UPDATE"""
            ).use { statement ->
                statement.setString(1, window.eventStream)
                statement.setInt(2, window.partitionId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "settlement admission frontier is absent" }
                    val rank = rows.getLong(3).let { if (rows.wasNull()) null else it }
                    Triple(rows.getString(1), rows.getLong(2), rank)
                }
            }
            check(frontier.first == window.sourceGeneration) { "settlement admission source generation changed" }
            val upstream = frontier(connection, "canonical_obligation_frontiers", window.eventStream, window.partitionId)
                ?: error("settlement obligation frontier is absent")
            check(upstream.first == window.sourceGeneration && upstream.second >= window.throughInclusiveSequence) {
                "settlement admission exceeds committed obligations"
            }
            val current = readObligations(connection, window.eventStream, window.partitionId,
                window.sourceGeneration, window.fromExclusiveSequence, window.throughInclusiveSequence,
                window.obligations.size + 1)
            check(current.size == window.obligations.size && digest(current) == window.obligationDigest) {
                "settlement obligations changed before admission commit"
            }
            verifySource(connection, window.eventStream, window.sourceGeneration, window.partitionId,
                window.fromExclusiveSequence, window.throughInclusiveSequence, current)
            val accountKeys = accountKeys(current)
            val accountJson = accountKeyJson(accountKeys)
            val accountDigest = sha256(accountJson)
            val openingDigest = openingDigest(connection, current)
            val result = if (frontier.second == window.fromExclusiveSequence) {
                check(current.all { it.status == "PENDING" }) { "settlement obligation already transitioned" }
                connection.prepareStatement(
                    """INSERT INTO settlement.canonical_transition_admission_counter(event_stream, source_generation)
                       VALUES (?, ?) ON CONFLICT DO NOTHING"""
                ).use { statement ->
                    statement.setString(1, window.eventStream)
                    statement.setString(2, window.sourceGeneration)
                    statement.executeUpdate()
                }
                val counterStarted = System.nanoTime()
                val rank = try {
                    connection.prepareStatement(
                        """UPDATE settlement.canonical_transition_admission_counter
                           SET next_rank = next_rank + 1
                           WHERE event_stream = ? AND source_generation = ?
                           RETURNING next_rank - 1"""
                    ).use { statement ->
                        statement.setString(1, window.eventStream)
                        statement.setString(2, window.sourceGeneration)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "settlement admission counter is absent" }
                            rows.getLong(1).also { check(it > 0 && !rows.next()) }
                        }
                    }
                } finally { timing?.counterCallNanos = System.nanoTime() - counterStarted }
                val predecessors = mutableSetOf<Long>()
                frontier.third?.let(predecessors::add)
                val priorAccounts: List<Pair<AccountKey, Long?>> = if (accountKeys.isEmpty()) emptyList()
                    else connection.prepareStatement(
                        """SELECT requested.run_id, requested.participant_id, requested.account_id,
                                  requested.asset_type, requested.asset_id, prior.admission_rank
                           FROM jsonb_to_recordset(?::jsonb) AS requested(
                             run_id text, participant_id text, account_id text, asset_type text, asset_id text)
                           LEFT JOIN LATERAL (
                             SELECT admission_rank
                             FROM settlement.canonical_transition_admission_accounts account
                             WHERE account.event_stream = ? AND account.source_generation = ?
                               AND account.run_id = requested.run_id
                               AND account.participant_id = requested.participant_id
                               AND account.account_id = requested.account_id
                               AND account.asset_type = requested.asset_type
                               AND account.asset_id = requested.asset_id
                             ORDER BY admission_rank DESC LIMIT 1
                           ) prior ON true
                           ORDER BY requested.run_id, requested.participant_id, requested.account_id,
                                    requested.asset_type, requested.asset_id"""
                    ).use { statement ->
                        statement.setString(1, accountJson)
                        statement.setString(2, window.eventStream)
                        statement.setString(3, window.sourceGeneration)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    val key = AccountKey(rows.getString(1), rows.getString(2), rows.getString(3),
                                        rows.getString(4), rows.getString(5))
                                    val prior = rows.getLong(6).let { if (rows.wasNull()) null else it }
                                    add(key to prior)
                                }
                            }
                        }
                    }
                check(priorAccounts.map { it.first } == accountKeys) {
                    "settlement admission account set is incomplete"
                }
                predecessors.addAll(priorAccounts.mapNotNull { it.second })
                timing?.predecessorCount = predecessors.size
                val dependencyDigest = sha256(predecessors.sorted().joinToString(","))
                connection.prepareStatement(
                    """INSERT INTO settlement.canonical_transition_admissions(
                         event_stream, source_generation, admission_rank, partition_id,
                         from_exclusive_sequence, through_inclusive_sequence,
                         obligation_count, obligation_digest, account_set_digest,
                         opening_digest, dependency_digest)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    statement.setString(1, window.eventStream)
                    statement.setString(2, window.sourceGeneration)
                    statement.setLong(3, rank)
                    statement.setInt(4, window.partitionId)
                    statement.setLong(5, window.fromExclusiveSequence)
                    statement.setLong(6, window.throughInclusiveSequence)
                    statement.setInt(7, current.size)
                    statement.setString(8, window.obligationDigest)
                    statement.setString(9, accountDigest)
                    statement.setString(10, openingDigest)
                    statement.setString(11, dependencyDigest)
                    check(statement.executeUpdate() == 1)
                }
                connection.prepareStatement(
                    """INSERT INTO settlement.canonical_transition_dependencies(
                         event_stream, source_generation, admission_rank, predecessor_rank)
                       VALUES (?, ?, ?, ?)"""
                ).use { statement ->
                    predecessors.sorted().forEach { predecessor ->
                        check(predecessor < rank) { "settlement admission predecessor is out of order" }
                        statement.setString(1, window.eventStream)
                        statement.setString(2, window.sourceGeneration)
                        statement.setLong(3, rank)
                        statement.setLong(4, predecessor)
                        statement.addBatch()
                    }
                    check(statement.executeBatch().size == predecessors.size)
                }
                connection.prepareStatement(
                    """INSERT INTO settlement.canonical_transition_admission_accounts(
                         event_stream, source_generation, admission_rank, run_id,
                         participant_id, account_id, asset_type, asset_id, predecessor_rank)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    priorAccounts.forEach { (key, predecessor) ->
                        statement.setString(1, window.eventStream)
                        statement.setString(2, window.sourceGeneration)
                        statement.setLong(3, rank)
                        statement.setString(4, key.runId)
                        statement.setString(5, key.participantId)
                        statement.setString(6, key.accountId)
                        statement.setString(7, key.assetType)
                        statement.setString(8, key.assetId)
                        if (predecessor == null) statement.setNull(9, java.sql.Types.BIGINT)
                        else statement.setLong(9, predecessor)
                        statement.addBatch()
                    }
                    check(statement.executeBatch().size == priorAccounts.size)
                }
                connection.prepareStatement(
                    """UPDATE settlement.canonical_transition_admission_frontiers
                       SET last_stream_sequence = ?, last_rank = ?
                       WHERE event_stream = ? AND partition_id = ? AND source_generation = ?
                         AND last_stream_sequence = ?"""
                ).use { statement ->
                    statement.setLong(1, window.throughInclusiveSequence)
                    statement.setLong(2, rank)
                    statement.setString(3, window.eventStream)
                    statement.setInt(4, window.partitionId)
                    statement.setString(5, window.sourceGeneration)
                    statement.setLong(6, window.fromExclusiveSequence)
                    check(statement.executeUpdate() == 1) { "settlement admission frontier changed" }
                }
                PostMatchApplyResult.APPLIED
            } else {
                check(window.throughInclusiveSequence <= frontier.second) {
                    "settlement admission window overlaps or skips committed frontier"
                }
                check(admissionRank(connection, window, accountDigest, openingDigest) != null) {
                    "settlement admission differs on replay"
                }
                PostMatchApplyResult.DUPLICATE
            }
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun admissionRank(connection: Connection, window: SettlementTransitionWindow,
                              accountDigest: String, openingDigest: String): Long? {
        val saved = connection.prepareStatement(
            """SELECT admission_rank, from_exclusive_sequence, obligation_count,
                      obligation_digest, account_set_digest, opening_digest, dependency_digest
               FROM settlement.canonical_transition_admissions
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else {
                    val rank = rows.getLong(1)
                    val valid = rows.getLong(2) == window.fromExclusiveSequence &&
                        rows.getInt(3) == window.obligations.size &&
                        rows.getString(4) == window.obligationDigest &&
                        rows.getString(5) == accountDigest &&
                        rows.getString(6) == openingDigest
                    val dependencyDigest = rows.getString(7)
                    check(valid && !rows.next()) { "settlement admission differs from source window" }
                    rank to dependencyDigest
                }
            }
        } ?: return null
        val predecessors = connection.prepareStatement(
            """SELECT predecessor_rank FROM settlement.canonical_transition_dependencies
               WHERE event_stream = ? AND source_generation = ? AND admission_rank = ?
               ORDER BY predecessor_rank"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setLong(3, saved.first)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1)) }
            }
        }
        check(saved.second == sha256(predecessors.joinToString(","))) {
            "settlement admission dependencies differ on replay"
        }
        val accounts = connection.prepareStatement(
            """SELECT current.run_id, current.participant_id, current.account_id,
                      current.asset_type, current.asset_id, current.predecessor_rank,
                      prior.admission_rank
               FROM settlement.canonical_transition_admission_accounts current
               LEFT JOIN LATERAL (
                 SELECT previous.admission_rank
                 FROM settlement.canonical_transition_admission_accounts previous
                 WHERE previous.event_stream = current.event_stream
                   AND previous.source_generation = current.source_generation
                   AND previous.run_id = current.run_id
                   AND previous.participant_id = current.participant_id
                   AND previous.account_id = current.account_id
                   AND previous.asset_type = current.asset_type
                   AND previous.asset_id = current.asset_id
                   AND previous.admission_rank < current.admission_rank
                 ORDER BY previous.admission_rank DESC LIMIT 1
               ) prior ON true
               WHERE current.event_stream = ? AND current.source_generation = ?
                 AND current.admission_rank = ?
               ORDER BY current.run_id, current.participant_id, current.account_id,
                        current.asset_type, current.asset_id"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setLong(3, saved.first)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val key = AccountKey(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5))
                        val recorded = rows.getLong(6).let { if (rows.wasNull()) null else it }
                        val actual = rows.getLong(7).let { if (rows.wasNull()) null else it }
                        check(recorded == actual) {
                            "settlement admission account predecessor differs from ranked history"
                        }
                        add(key to actual)
                    }
                }
            }
        }
        check(accounts.map { it.first } == accountKeys(window.obligations)) {
            "settlement admission account membership differs on replay"
        }
        val partitionPredecessor = if (window.fromExclusiveSequence ==
            CanonicalStreamPosition.origin(window.partitionId)) null else connection.prepareStatement(
            """SELECT admission_rank FROM settlement.canonical_transition_admissions
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "settlement admission partition predecessor is missing" }
                rows.getLong(1).also { check(!rows.next()) }
            }
        }
        val expected = accounts.mapNotNull { it.second }.toMutableSet()
        partitionPredecessor?.let(expected::add)
        check(expected.sorted() == predecessors && expected.all { it < saved.first }) {
            "settlement admission dependencies differ from ranked history"
        }
        return saved.first
    }

    /** Returns null while an earlier admitted window touches the same account or partition. */
    fun apply(window: SettlementTransitionWindow): PostMatchApplyResult? = dataSource.connection.use { connection ->
        require(window.fromExclusiveSequence >= CanonicalStreamPosition.origin(window.partitionId) &&
            window.throughInclusiveSequence > window.fromExclusiveSequence &&
            window.throughInclusiveSequence - window.fromExclusiveSequence <= 5000)
        check(digest(window.obligations) == window.obligationDigest) {
            "settlement transition input digest changed"
        }
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """INSERT INTO settlement.canonical_transition_frontiers(
                     event_stream, partition_id, source_generation, last_stream_sequence)
                   VALUES (?, ?, ?, ?) ON CONFLICT (event_stream, partition_id) DO NOTHING"""
            ).use { statement ->
                statement.setString(1, window.eventStream)
                statement.setInt(2, window.partitionId)
                statement.setString(3, window.sourceGeneration)
                statement.setLong(4, CanonicalStreamPosition.origin(window.partitionId))
                statement.executeUpdate()
            }
            val frontier = connection.prepareStatement(
                """SELECT source_generation, last_stream_sequence
                   FROM settlement.canonical_transition_frontiers
                   WHERE event_stream = ? AND partition_id = ? FOR UPDATE"""
            ).use { statement ->
                statement.setString(1, window.eventStream)
                statement.setInt(2, window.partitionId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "settlement transition frontier is absent" }
                    rows.getString(1) to rows.getLong(2)
                }
            }
            check(frontier.first == window.sourceGeneration) { "settlement transition source generation changed" }
            val upstream = frontier(connection, "canonical_obligation_frontiers", window.eventStream, window.partitionId)
                ?: error("settlement obligation frontier is absent")
            check(upstream.first == window.sourceGeneration && upstream.second >= window.throughInclusiveSequence) {
                "settlement transition exceeds committed obligations"
            }
            val current = readObligations(connection, window.eventStream, window.partitionId,
                window.sourceGeneration, window.fromExclusiveSequence, window.throughInclusiveSequence,
                window.obligations.size + 1)
            check(digest(current) == window.obligationDigest && current.size == window.obligations.size) {
                "settlement obligations changed before transition commit"
            }
            verifySource(connection, window.eventStream, window.sourceGeneration, window.partitionId,
                window.fromExclusiveSequence, window.throughInclusiveSequence, current)
            val stableOpeningDigest = openingDigest(connection, current)
            val rank = admissionRank(connection, window, sha256(accountKeyJson(accountKeys(current))),
                stableOpeningDigest)
                ?: error("settlement transition has no durable admission")
            val result = when {
                frontier.second == window.fromExclusiveSequence -> {
                    check(current.all { it.status == "PENDING" }) { "settlement obligation already transitioned" }
                    if (!dependenciesComplete(connection, window.eventStream, window.sourceGeneration, rank)) null else {
                        applyNew(connection, window, current)
                        check(openingDigest(connection, current) == stableOpeningDigest) {
                            "settlement resource opening changed during transition"
                        }
                        insertCoverage(connection, window)
                        connection.prepareStatement(
                            """INSERT INTO settlement.canonical_transition_admission_completions(
                                 event_stream, source_generation, admission_rank, obligation_digest)
                               VALUES (?, ?, ?, ?)"""
                        ).use { statement ->
                            statement.setString(1, window.eventStream)
                            statement.setString(2, window.sourceGeneration)
                            statement.setLong(3, rank)
                            statement.setString(4, window.obligationDigest)
                            check(statement.executeUpdate() == 1)
                        }
                        connection.prepareStatement(
                            """UPDATE settlement.canonical_transition_frontiers
                               SET last_stream_sequence = ?, updated_at = now()
                               WHERE event_stream = ? AND partition_id = ? AND source_generation = ?
                                 AND last_stream_sequence = ?"""
                        ).use { statement ->
                            statement.setLong(1, window.throughInclusiveSequence)
                            statement.setString(2, window.eventStream)
                            statement.setInt(3, window.partitionId)
                            statement.setString(4, window.sourceGeneration)
                            statement.setLong(5, window.fromExclusiveSequence)
                            check(statement.executeUpdate() == 1) { "settlement transition frontier changed" }
                        }
                        PostMatchApplyResult.APPLIED
                    }
                }
                window.throughInclusiveSequence <= frontier.second -> {
                    check(completionExists(connection, window, rank)) {
                        "settlement admission completion is missing on replay"
                    }
                    seedAndLockAccounts(connection, window, accountKeys(current), seed = false)
                    verifyReplay(connection, window, current)
                    PostMatchApplyResult.DUPLICATE
                }
                else -> error("settlement transition window overlaps or skips committed frontier")
            }
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun dependenciesComplete(connection: Connection, eventStream: String, sourceGeneration: String, rank: Long): Boolean =
        connection.prepareStatement(
            """SELECT COUNT(*) FILTER (WHERE completion.admission_rank IS NULL),
                      COUNT(*) FILTER (WHERE completion.admission_rank IS NOT NULL
                                         AND completion.obligation_digest <> prior.obligation_digest)
               FROM settlement.canonical_transition_dependencies dependency
               JOIN settlement.canonical_transition_admissions prior
                 ON prior.event_stream = dependency.event_stream
                AND prior.source_generation = dependency.source_generation
                AND prior.admission_rank = dependency.predecessor_rank
               LEFT JOIN settlement.canonical_transition_admission_completions completion
                 ON completion.event_stream = dependency.event_stream
                AND completion.source_generation = dependency.source_generation
                AND completion.admission_rank = dependency.predecessor_rank
               WHERE dependency.event_stream = ? AND dependency.source_generation = ?
                 AND dependency.admission_rank = ?"""
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, sourceGeneration)
            statement.setLong(3, rank)
            statement.executeQuery().use { rows ->
                check(rows.next())
                val missing = rows.getLong(1)
                val corrupt = rows.getLong(2)
                check(corrupt == 0L) { "settlement predecessor completion differs from admission" }
                missing == 0L
            }
        }

    private fun completionExists(connection: Connection, window: SettlementTransitionWindow, rank: Long): Boolean =
        connection.prepareStatement(
            """SELECT obligation_digest FROM settlement.canonical_transition_admission_completions
               WHERE event_stream = ? AND source_generation = ? AND admission_rank = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setLong(3, rank)
            statement.executeQuery().use { rows ->
                rows.next() && rows.getString(1) == window.obligationDigest && !rows.next()
            }
        }

    private fun accountKeys(obligations: List<SettlementTransitionObligation>): List<AccountKey> =
        obligations.asSequence().filter { it.mode == "instant-post-trade" }.flatMap { o ->
            sequenceOf(
                AccountKey(o.runId, o.buyerParticipantId, o.buyerAccountId, "CASH", o.currency),
                AccountKey(o.runId, o.sellerParticipantId, o.sellerAccountId, "CASH", o.currency),
                AccountKey(o.runId, o.sellerParticipantId, o.sellerAccountId, "SECURITY", o.instrumentId),
                AccountKey(o.runId, o.buyerParticipantId, o.buyerAccountId, "SECURITY", o.instrumentId)
            )
        }.distinct().sortedWith(compareBy<AccountKey> { it.runId }.thenBy { it.participantId }
            .thenBy { it.accountId }.thenBy { it.assetType }.thenBy { it.assetId }).toList()

    private fun accountKeyJson(keys: List<AccountKey>): String = mapper.writeValueAsString(keys.map { key ->
        mapOf("run_id" to key.runId, "participant_id" to key.participantId,
            "account_id" to key.accountId, "asset_type" to key.assetType, "asset_id" to key.assetId)
    })

    private fun seedAndLockAccounts(
        connection: Connection, window: SettlementTransitionWindow,
        keys: List<AccountKey>, seed: Boolean = true
    ): MutableMap<AccountKey, AccountBalance> {
        if (keys.isEmpty()) return mutableMapOf()
        val json = accountKeyJson(keys)
        val recordset = """jsonb_to_recordset(?::jsonb) AS requested(
            run_id text, participant_id text, account_id text, asset_type text, asset_id text)"""
        if (seed) connection.prepareStatement(
            """INSERT INTO settlement.canonical_account_state(
                 event_stream, source_generation, run_id, participant_id, account_id,
                 asset_type, asset_id, opening_quantity, opening_position_count)
               SELECT ?, ?, requested.run_id, requested.participant_id, requested.account_id,
                      requested.asset_type, requested.asset_id,
                      COALESCE(opening.quantity, 0), COALESCE(opening.position_count, 0)
               FROM $recordset
               LEFT JOIN settlement.canonical_resource_openings opening
                 ON opening.run_id = requested.run_id AND opening.participant_id = requested.participant_id
                AND opening.account_id = requested.account_id AND opening.asset_type = requested.asset_type
                AND opening.asset_id = requested.asset_id
               ORDER BY requested.run_id, requested.participant_id, requested.account_id,
                        requested.asset_type, requested.asset_id
               ON CONFLICT DO NOTHING"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setString(3, json)
            statement.executeUpdate()
        }
        val balances = connection.prepareStatement(
            """SELECT state.run_id, state.participant_id, state.account_id, state.asset_type, state.asset_id,
                      state.opening_quantity, state.opening_position_count, state.ledger_delta,
                      state.account_version
               FROM $recordset
               JOIN settlement.canonical_account_state state
                 ON state.event_stream = ? AND state.source_generation = ?
                AND state.run_id = requested.run_id AND state.participant_id = requested.participant_id
                AND state.account_id = requested.account_id AND state.asset_type = requested.asset_type
                AND state.asset_id = requested.asset_id
               ORDER BY state.run_id, state.participant_id, state.account_id, state.asset_type, state.asset_id
               FOR UPDATE OF state"""
        ).use { statement ->
            statement.setString(1, json)
            statement.setString(2, window.eventStream)
            statement.setString(3, window.sourceGeneration)
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val key = AccountKey(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5))
                        val opening = rows.getBigDecimal(6)
                        val count = rows.getLong(7)
                        val delta = rows.getBigDecimal(8)
                        val version = rows.getLong(9)
                        put(key, AccountBalance(opening, count, delta, version, delta))
                    }
                }.also { check(it.size == keys.size) { "settlement account lock set is incomplete" } }.toMutableMap()
            }
        }
        connection.prepareStatement(
            """SELECT state.run_id, state.participant_id, state.account_id, state.asset_type, state.asset_id,
                      COALESCE(opening.quantity, 0), COALESCE(opening.position_count, 0),
                      checkpoint.after_delta
               FROM $recordset
               JOIN settlement.canonical_account_state state
                 ON state.event_stream = ? AND state.source_generation = ?
                AND state.run_id = requested.run_id AND state.participant_id = requested.participant_id
                AND state.account_id = requested.account_id AND state.asset_type = requested.asset_type
                AND state.asset_id = requested.asset_id
               LEFT JOIN settlement.canonical_resource_openings opening
                 ON opening.run_id = requested.run_id AND opening.participant_id = requested.participant_id
                AND opening.account_id = requested.account_id AND opening.asset_type = requested.asset_type
                AND opening.asset_id = requested.asset_id
               LEFT JOIN settlement.canonical_account_checkpoints checkpoint
                 ON checkpoint.event_stream = state.event_stream
                AND checkpoint.source_generation = state.source_generation
                AND checkpoint.run_id = state.run_id AND checkpoint.participant_id = state.participant_id
                AND checkpoint.account_id = state.account_id AND checkpoint.asset_type = state.asset_type
                AND checkpoint.asset_id = state.asset_id AND checkpoint.account_version = state.account_version"""
        ).use { statement ->
            statement.setString(1, json)
            statement.setString(2, window.eventStream)
            statement.setString(3, window.sourceGeneration)
            statement.executeQuery().use { rows ->
                var verified = 0
                while (rows.next()) {
                    val key = AccountKey(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5))
                    val balance = balances[key] ?: error("settlement account proof key is missing")
                    check(balance.opening.compareTo(rows.getBigDecimal(6)) == 0 &&
                        balance.positionCount == rows.getLong(7)) {
                        "settlement resource opening changed for $key"
                    }
                    val checkpointDelta = rows.getBigDecimal(8)
                    check(if (balance.version == 0L)
                        balance.delta.compareTo(BigDecimal.ZERO) == 0 && checkpointDelta == null
                    else checkpointDelta != null && balance.delta.compareTo(checkpointDelta) == 0) {
                        "settlement account state differs from latest posting checkpoint for $key"
                    }
                    verified++
                }
                check(verified == keys.size) { "settlement account proof set is incomplete" }
            }
        }
        return balances
    }

    private fun positionedRuns(connection: Connection, obligations: List<SettlementTransitionObligation>): Set<String> {
        val runs = obligations.asSequence().filter { it.mode == "instant-post-trade" }.map { it.runId }.toSet()
        if (runs.isEmpty()) return emptySet()
        return connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM settlement.canonical_resource_openings WHERE run_id = ?)"
        ).use { statement ->
            runs.filterTo(mutableSetOf()) { run ->
                statement.setString(1, run)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
    }

    private fun legs(o: SettlementTransitionObligation): List<LedgerLeg> = listOf(
        LedgerLeg("BUYER_CASH_DEBIT", AccountKey(o.runId, o.buyerParticipantId, o.buyerAccountId,
            "CASH", o.currency), "DEBIT", o.cashAmount),
        LedgerLeg("SELLER_CASH_CREDIT", AccountKey(o.runId, o.sellerParticipantId, o.sellerAccountId,
            "CASH", o.currency), "CREDIT", o.cashAmount),
        LedgerLeg("SELLER_SECURITY_DEBIT", AccountKey(o.runId, o.sellerParticipantId, o.sellerAccountId,
            "SECURITY", o.instrumentId), "DEBIT", o.quantityUnits),
        LedgerLeg("BUYER_SECURITY_CREDIT", AccountKey(o.runId, o.buyerParticipantId, o.buyerAccountId,
            "SECURITY", o.instrumentId), "CREDIT", o.quantityUnits)
    )

    private fun applyNew(
        connection: Connection, window: SettlementTransitionWindow,
        obligations: List<SettlementTransitionObligation>
    ) {
        check(obligations.all { it.mode == "instant-post-trade" || it.mode == "ops-realistic" }) {
            "settlement profile mode is unsupported"
        }
        val keys = accountKeys(obligations)
        val balances = seedAndLockAccounts(connection, window, keys)
        val positioned = positionedRuns(connection, obligations)
        val decisions = obligations.mapNotNull { o ->
            if (o.mode == "ops-realistic") return@mapNotNull null
            val tradeLegs = legs(o)
            val cashAvailable = o.runId !in positioned ||
                balances.getValue(tradeLegs[0].key).available() >= o.cashAmount
            val securityAvailable = o.runId !in positioned ||
                balances.getValue(tradeLegs[2].key).available() >= o.quantityUnits
            val settled = cashAvailable && securityAvailable
            val outcome = if (settled) "SETTLED" else "BREAK"
            val reason = when {
                !cashAvailable -> "CASH_LEG_FAILED"
                !securityAvailable -> "SECURITY_LEG_FAILED"
                else -> null
            }
            val workflow = workflow(o, settled, cashAvailable, securityAvailable)
            if (settled) tradeLegs.forEach { leg ->
                val account = balances.getValue(leg.key)
                account.delta = if (leg.direction == "DEBIT") account.delta - leg.quantity
                    else account.delta + leg.quantity
            }
            TransitionDecision(o, outcome, reason, workflow, if (settled) tradeLegs else emptyList())
        }
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_transition_attempts(
                 event_stream, source_generation, trade_id, attempt_number, partition_id,
                 stream_sequence, run_id, post_trade_profile_id, post_trade_policy_version,
                 outcome, break_reason, workflow_facts, workflow_digest, occurred_at)
               VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            decisions.forEach { decision ->
                val o = decision.obligation
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, o.tradeId)
                statement.setInt(4, window.partitionId)
                statement.setLong(5, o.streamSequence)
                statement.setString(6, o.runId)
                statement.setString(7, o.profileId)
                statement.setInt(8, o.policyVersion)
                statement.setString(9, decision.outcome)
                statement.setString(10, decision.breakReason)
                statement.setString(11, decision.workflow)
                statement.setString(12, sha256(decision.workflow))
                statement.setObject(13, OffsetDateTime.ofInstant(o.occurredAt, ZoneOffset.UTC))
                statement.addBatch()
            }
            check(statement.executeBatch().size == decisions.size) { "settlement attempt batch was incomplete" }
        }
        insertLedger(connection, window, decisions)
        afterLedgerInsert()
        val postingProofs = postings(decisions)
        connection.prepareStatement(
            """UPDATE settlement.canonical_settlement_obligations SET status = ?
               WHERE event_stream = ? AND source_generation = ? AND trade_id = ? AND status = 'PENDING'"""
        ).use { statement ->
            decisions.forEach { decision ->
                statement.setString(1, decision.outcome)
                statement.setString(2, window.eventStream)
                statement.setString(3, window.sourceGeneration)
                statement.setString(4, decision.obligation.tradeId)
                statement.addBatch()
            }
            check(statement.executeBatch().all { it == 1 }) { "settlement obligation status changed" }
        }
        connection.prepareStatement(
            """UPDATE settlement.canonical_account_state
               SET ledger_delta = ?, account_version = account_version + 1, updated_at = now()
               WHERE event_stream = ? AND source_generation = ? AND run_id = ?
                 AND participant_id = ? AND account_id = ? AND asset_type = ? AND asset_id = ?
                 AND ledger_delta = ? AND account_version = ?"""
        ).use { statement ->
            var updated = 0
            balances.forEach { (key, balance) ->
                if (balance.delta.compareTo(balance.originalDelta) == 0) return@forEach
                statement.setBigDecimal(1, balance.delta)
                statement.setString(2, window.eventStream)
                statement.setString(3, window.sourceGeneration)
                statement.setString(4, key.runId)
                statement.setString(5, key.participantId)
                statement.setString(6, key.accountId)
                statement.setString(7, key.assetType)
                statement.setString(8, key.assetId)
                statement.setBigDecimal(9, balance.originalDelta)
                statement.setLong(10, balance.version)
                statement.addBatch()
                updated++
            }
            val counts = statement.executeBatch()
            check(counts.size == updated && counts.all { it == 1 }) {
                "settlement account balance changed"
            }
        }
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_account_checkpoints(
                 event_stream, source_generation, run_id, participant_id, account_id,
                 asset_type, asset_id, account_version, partition_id,
                 from_exclusive_sequence, through_inclusive_sequence,
                 before_delta, after_delta, posting_digest)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            var inserted = 0
            balances.forEach { (key, balance) ->
                if (balance.delta.compareTo(balance.originalDelta) == 0) return@forEach
                val accountPostings = postingProofs[key]
                    ?: error("settlement account changed without ledger postings")
                statement.setString(1, window.eventStream)
                statement.setString(2, window.sourceGeneration)
                statement.setString(3, key.runId)
                statement.setString(4, key.participantId)
                statement.setString(5, key.accountId)
                statement.setString(6, key.assetType)
                statement.setString(7, key.assetId)
                statement.setLong(8, balance.version + 1)
                statement.setInt(9, window.partitionId)
                statement.setLong(10, window.fromExclusiveSequence)
                statement.setLong(11, window.throughInclusiveSequence)
                statement.setBigDecimal(12, balance.originalDelta)
                statement.setBigDecimal(13, balance.delta)
                statement.setString(14, postingDigest(accountPostings))
                statement.addBatch()
                inserted++
            }
            check(statement.executeBatch().size == inserted) { "settlement account checkpoint batch was incomplete" }
        }
    }

    private fun workflow(
        o: SettlementTransitionObligation, settled: Boolean,
        cashAvailable: Boolean, securityAvailable: Boolean
    ): String {
        val kinds = listOf("ALLOCATION_PROPOSED", "CONFIRMATION_GENERATED", "AFFIRMATION_ACCEPTED",
            "CLEARING_SUBMITTED", "CLEARING_ACCEPTED", "NOVATION_RECORDED",
            "INSTRUCTION_CREATED", "ATTEMPT_STARTED")
        val events = kinds.map { kind -> mapOf("id" to "${o.tradeId}:$kind:1", "kind" to kind,
            "occurredAt" to o.occurredAt.toString()) }.toMutableList()
        events += mapOf("id" to "${o.tradeId}:CASH_LEG:1", "kind" to "CASH_LEG",
            "state" to if (cashAvailable) "LEG_SUCCEEDED" else "LEG_FAILED",
            "occurredAt" to o.occurredAt.toString())
        events += mapOf("id" to "${o.tradeId}:SECURITY_LEG:1", "kind" to "SECURITY_LEG",
            "state" to if (securityAvailable) "LEG_SUCCEEDED" else "LEG_FAILED",
            "occurredAt" to o.occurredAt.toString())
        events += mapOf("id" to "${o.tradeId}:${if (settled) "SETTLED" else "BREAK_OPENED"}:1",
            "kind" to if (settled) "SETTLED" else "BREAK_OPENED",
            "occurredAt" to o.occurredAt.toString())
        return mapper.writeValueAsString(events)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun insertLedger(
        connection: Connection, window: SettlementTransitionWindow,
        decisions: List<TransitionDecision>
    ) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_transition_ledger_entries(
                 event_stream, source_generation, trade_id, attempt_number, entry_kind,
                 run_id, participant_id, account_id, asset_type, asset_id, direction,
                 quantity, occurred_at)
               VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            decisions.forEach { decision ->
                val o = decision.obligation
                decision.ledgerLegs.forEach { leg ->
                    statement.setString(1, window.eventStream)
                    statement.setString(2, window.sourceGeneration)
                    statement.setString(3, o.tradeId)
                    statement.setString(4, leg.kind)
                    statement.setString(5, o.runId)
                    statement.setString(6, leg.key.participantId)
                    statement.setString(7, leg.key.accountId)
                    statement.setString(8, leg.key.assetType)
                    statement.setString(9, leg.key.assetId)
                    statement.setString(10, leg.direction)
                    statement.setBigDecimal(11, leg.quantity)
                    statement.setObject(12, OffsetDateTime.ofInstant(o.occurredAt, ZoneOffset.UTC))
                    statement.addBatch()
                }
            }
            check(statement.executeBatch().size == decisions.sumOf { it.ledgerLegs.size }) {
                "settlement ledger leg batch was incomplete"
            }
        }
    }

    private fun insertCoverage(connection: Connection, window: SettlementTransitionWindow) {
        connection.prepareStatement(
            """INSERT INTO settlement.canonical_transition_coverage(
                 event_stream, partition_id, source_generation, from_exclusive_sequence,
                 through_inclusive_sequence, obligation_count, obligation_digest)
               VALUES (?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setString(3, window.sourceGeneration)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.setInt(6, window.obligations.size)
            statement.setString(7, window.obligationDigest)
            statement.executeUpdate()
        }
    }

    private fun verifyReplay(
        connection: Connection, window: SettlementTransitionWindow,
        obligations: List<SettlementTransitionObligation>
    ) {
        connection.prepareStatement(
            """SELECT source_generation, from_exclusive_sequence, obligation_count, obligation_digest
               FROM settlement.canonical_transition_coverage
               WHERE event_stream = ? AND partition_id = ? AND through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setInt(2, window.partitionId)
            statement.setLong(3, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getString(1) == window.sourceGeneration &&
                    rows.getLong(2) == window.fromExclusiveSequence &&
                    rows.getInt(3) == obligations.size && rows.getString(4) == window.obligationDigest &&
                    !rows.next()) { "settlement transition coverage differs on replay" }
            }
        }
        obligations.forEach { o ->
            if (o.mode == "ops-realistic") {
                check(o.status == "PENDING") { "realistic obligation unexpectedly transitioned" }
            } else {
                check(o.status == "SETTLED" || o.status == "BREAK") { "instant obligation is not transitioned" }
                connection.prepareStatement(
                    """SELECT outcome, break_reason, workflow_facts, workflow_digest,
                              partition_id, stream_sequence, run_id, post_trade_profile_id,
                              post_trade_policy_version, occurred_at
                       FROM settlement.canonical_transition_attempts
                       WHERE event_stream = ? AND source_generation = ? AND trade_id = ? AND attempt_number = 1"""
                ).use { statement ->
                    statement.setString(1, window.eventStream)
                    statement.setString(2, window.sourceGeneration)
                    statement.setString(3, o.tradeId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "settlement transition attempt is missing on replay" }
                        val savedWorkflow = rows.getString(3)
                        val facts = mapper.readTree(savedWorkflow)
                        check(facts.isArray) { "settlement transition workflow is malformed" }
                        val cashState = facts.firstOrNull { it.path("kind").asText() == "CASH_LEG" }
                            ?.path("state")?.asText()
                        val securityState = facts.firstOrNull { it.path("kind").asText() == "SECURITY_LEG" }
                            ?.path("state")?.asText()
                        check(cashState in setOf("LEG_SUCCEEDED", "LEG_FAILED") &&
                            securityState in setOf("LEG_SUCCEEDED", "LEG_FAILED")) {
                            "settlement transition leg outcomes are malformed"
                        }
                        val cashAvailable = cashState == "LEG_SUCCEEDED"
                        val securityAvailable = securityState == "LEG_SUCCEEDED"
                        val settled = cashAvailable && securityAvailable
                        val reason = when {
                            !cashAvailable -> "CASH_LEG_FAILED"
                            !securityAvailable -> "SECURITY_LEG_FAILED"
                            else -> null
                        }
                        check(rows.getString(1) == o.status && (o.status == "SETTLED") == settled &&
                            rows.getString(2) == reason &&
                            savedWorkflow == workflow(o, settled, cashAvailable, securityAvailable) &&
                            sha256(savedWorkflow) == rows.getString(4) &&
                            rows.getInt(5) == window.partitionId && rows.getLong(6) == o.streamSequence &&
                            rows.getString(7) == o.runId && rows.getString(8) == o.profileId &&
                            rows.getInt(9) == o.policyVersion &&
                            rows.getTimestamp(10).toInstant() == o.occurredAt && !rows.next()) {
                            "settlement transition attempt differs on replay"
                        }
                    }
                }
                connection.prepareStatement(
                    """SELECT entry_kind, run_id, participant_id, account_id, asset_type,
                              asset_id, direction, quantity, occurred_at
                       FROM settlement.canonical_transition_ledger_entries
                       WHERE event_stream = ? AND source_generation = ? AND trade_id = ? AND attempt_number = 1"""
                ).use { statement ->
                    statement.setString(1, window.eventStream)
                    statement.setString(2, window.sourceGeneration)
                    statement.setString(3, o.tradeId)
                    statement.executeQuery().use { rows ->
                        val expected = if (o.status == "SETTLED") legs(o).associateBy { it.kind }.toMutableMap()
                            else mutableMapOf()
                        while (rows.next()) {
                            val leg = expected.remove(rows.getString(1))
                                ?: error("settlement transition has an extra ledger entry on replay")
                            check(rows.getString(2) == o.runId && rows.getString(3) == leg.key.participantId &&
                                rows.getString(4) == leg.key.accountId && rows.getString(5) == leg.key.assetType &&
                                rows.getString(6) == leg.key.assetId && rows.getString(7) == leg.direction &&
                                rows.getBigDecimal(8).compareTo(leg.quantity) == 0 &&
                                rows.getTimestamp(9).toInstant() == o.occurredAt) {
                                "settlement transition ledger entry differs on replay"
                            }
                        }
                        check(expected.isEmpty()) { "settlement transition ledger entries are missing on replay" }
                    }
                }
            }
        }
        val expectedPostings = obligations.asSequence().filter { it.status == "SETTLED" }
            .flatMap { o -> legs(o).asSequence().map { AccountPosting(o.tradeId, it) } }
            .groupBy { it.leg.key }
        val expectedCheckpoints = expectedPostings.mapNotNull { (key, accountPostings) ->
            val delta = accountPostings.fold(BigDecimal.ZERO) { total, posting ->
                if (posting.leg.direction == "DEBIT") total - posting.leg.quantity
                    else total + posting.leg.quantity
            }
            if (delta.compareTo(BigDecimal.ZERO) == 0) null
                else key to (delta to postingDigest(accountPostings))
        }.toMap().toMutableMap()
        connection.prepareStatement(
            """SELECT c.run_id, c.participant_id, c.account_id, c.asset_type, c.asset_id,
                      c.before_delta, c.after_delta, c.posting_digest, c.account_version,
                      previous.after_delta, following.before_delta,
                      state.account_version, state.ledger_delta
               FROM settlement.canonical_account_checkpoints c
               JOIN settlement.canonical_account_state state
                 USING (event_stream, source_generation, run_id, participant_id, account_id, asset_type, asset_id)
               LEFT JOIN settlement.canonical_account_checkpoints previous
                 ON (previous.event_stream, previous.source_generation, previous.run_id,
                     previous.participant_id, previous.account_id, previous.asset_type, previous.asset_id) =
                    (c.event_stream, c.source_generation, c.run_id,
                     c.participant_id, c.account_id, c.asset_type, c.asset_id)
                AND previous.account_version = c.account_version - 1
               LEFT JOIN settlement.canonical_account_checkpoints following
                 ON (following.event_stream, following.source_generation, following.run_id,
                     following.participant_id, following.account_id, following.asset_type, following.asset_id) =
                    (c.event_stream, c.source_generation, c.run_id,
                     c.participant_id, c.account_id, c.asset_type, c.asset_id)
                AND following.account_version = c.account_version + 1
               WHERE c.event_stream = ? AND c.source_generation = ? AND c.partition_id = ?
                 AND c.from_exclusive_sequence = ? AND c.through_inclusive_sequence = ?"""
        ).use { statement ->
            statement.setString(1, window.eventStream)
            statement.setString(2, window.sourceGeneration)
            statement.setInt(3, window.partitionId)
            statement.setLong(4, window.fromExclusiveSequence)
            statement.setLong(5, window.throughInclusiveSequence)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val key = AccountKey(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5))
                    val expected = expectedCheckpoints.remove(key)
                        ?: error("settlement transition has an extra account checkpoint on replay")
                    check(rows.getBigDecimal(7).subtract(rows.getBigDecimal(6)).compareTo(expected.first) == 0 &&
                        rows.getString(8) == expected.second) {
                        "settlement transition account checkpoint differs from ledger on replay"
                    }
                    val version = rows.getLong(9)
                    val previousDelta = rows.getBigDecimal(10)
                    val followingDelta = rows.getBigDecimal(11)
                    val latestVersion = rows.getLong(12)
                    check(version in 1..latestVersion &&
                        (if (version == 1L) previousDelta == null &&
                            rows.getBigDecimal(6).compareTo(BigDecimal.ZERO) == 0
                        else previousDelta != null && rows.getBigDecimal(6).compareTo(previousDelta) == 0) &&
                        (if (version == latestVersion) followingDelta == null &&
                            rows.getBigDecimal(7).compareTo(rows.getBigDecimal(13)) == 0
                        else followingDelta != null && rows.getBigDecimal(7).compareTo(followingDelta) == 0)) {
                        "settlement transition account checkpoint chain differs on replay"
                    }
                }
                check(expectedCheckpoints.isEmpty()) {
                    "settlement transition account checkpoints are missing on replay"
                }
            }
        }
    }
}
