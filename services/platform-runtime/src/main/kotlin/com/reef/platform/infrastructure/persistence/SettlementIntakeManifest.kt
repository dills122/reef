package com.reef.platform.infrastructure.persistence

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection

data class SettlementIntakeTrade(
    val streamSequence: Long,
    val effectOrdinal: Int,
    val tradeId: String,
    val eventId: String,
    val executionId: String,
    val buyOrderId: String,
    val sellOrderId: String,
    val runId: String,
    val venueSessionId: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val buyerAccountId: String,
    val sellerAccountId: String,
    val instrumentId: String,
    val quantityUnits: String,
    val price: String,
    val currency: String,
    val occurredAt: String
) {
    val policyKey get() = SettlementPolicyKey(runId, venueSessionId)

    fun cashAmount(): BigInteger {
        val quantity = quantityUnits.toBigIntegerOrNull()
            ?: throw IllegalArgumentException("settlement trade quantity must be an integer")
        val unitPrice = price.toBigIntegerOrNull()
            ?: throw IllegalArgumentException("settlement trade price must be an integer")
        require(quantity > BigInteger.ZERO && unitPrice >= BigInteger.ZERO) {
            "settlement trade quantity or price is invalid"
        }
        return quantity.multiply(unitPrice)
    }
}

/** Per-source-position trade membership proof committed with intake receipts. */
internal object SettlementIntakeManifest {
    val emptyDigest: String = digest(emptyList())

    fun digest(trades: List<SettlementIntakeTrade>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        field("reef.settlement.intake-trades.v1")
        trades.forEach { trade ->
            listOf(trade.streamSequence.toString(), trade.effectOrdinal.toString(), trade.tradeId,
                trade.eventId, trade.executionId, trade.buyOrderId, trade.sellOrderId,
                trade.runId, trade.venueSessionId, trade.buyerParticipantId,
                trade.sellerParticipantId, trade.buyerAccountId, trade.sellerAccountId,
                trade.instrumentId, trade.quantityUnits, trade.price, trade.currency,
                trade.occurredAt).forEach(::field)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun verify(
        connection: Connection, eventStream: String, generation: String, partition: Int,
        fromExclusive: Long, throughInclusive: Long, trades: List<SettlementIntakeTrade>
    ) {
        check(throughInclusive > fromExclusive && throughInclusive - fromExclusive <= 5000) {
            "settlement intake manifest range is invalid"
        }
        check(trades.zipWithNext().all { (left, right) ->
            left.streamSequence < right.streamSequence ||
                left.streamSequence == right.streamSequence && left.effectOrdinal < right.effectOrdinal
        }) { "settlement intake trades are unordered" }
        check(trades.all { it.streamSequence > fromExclusive && it.streamSequence <= throughInclusive }) {
            "settlement intake trade is outside manifest range"
        }
        connection.prepareStatement(
            """SELECT from_exclusive_sequence, through_inclusive_sequence, source_member_count, source_digest
               FROM settlement.canonical_intake_coverage
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND through_inclusive_sequence > ? AND from_exclusive_sequence < ?
               ORDER BY through_inclusive_sequence LIMIT 5001"""
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, generation)
            statement.setInt(3, partition)
            statement.setLong(4, fromExclusive)
            statement.setLong(5, throughInclusive)
            statement.executeQuery().use { rows ->
                var coveredThrough = fromExclusive
                while (coveredThrough < throughInclusive && rows.next()) {
                    val start = rows.getLong(1)
                    val end = rows.getLong(2)
                    check(start <= coveredThrough && end > coveredThrough &&
                        rows.getInt(3).toLong() == end - start && !rows.getString(4).isNullOrBlank()) {
                        "settlement intake source coverage is missing or invalid"
                    }
                    coveredThrough = end
                }
                check(coveredThrough >= throughInclusive) { "settlement intake source coverage has a gap" }
            }
        }
        val bySequence = trades.groupBy { it.streamSequence }
        connection.prepareStatement(
            """SELECT stream_sequence, trade_count, trade_digest
               FROM settlement.canonical_intake_receipts
               WHERE event_stream = ? AND source_generation = ? AND partition_id = ?
                 AND stream_sequence > ? AND stream_sequence <= ? ORDER BY stream_sequence"""
        ).use { statement ->
            statement.setString(1, eventStream)
            statement.setString(2, generation)
            statement.setInt(3, partition)
            statement.setLong(4, fromExclusive)
            statement.setLong(5, throughInclusive)
            statement.executeQuery().use { rows ->
                var sequence = fromExclusive + 1
                while (sequence <= throughInclusive) {
                    val members = bySequence[sequence].orEmpty()
                    val memberDigest = if (members.isEmpty()) emptyDigest else digest(members)
                    check(rows.next() && rows.getLong(1) == sequence &&
                        rows.getObject(2) != null && rows.getInt(2) == members.size &&
                        rows.getString(3) == memberDigest) {
                        "settlement intake trade membership is missing or mismatched at $sequence"
                    }
                    sequence++
                }
                check(!rows.next()) { "settlement intake has extra receipts in manifest range" }
            }
        }
    }
}
