package com.reef.stockdata

import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.Collections
import javax.sql.DataSource

class PostgresSeedSnapshotRepository(private val dataSource: DataSource) : SeedSnapshotRepository {

    override fun find(gameSeedId: String): StockSeedSnapshotBatch? {
        dataSource.connection.use { conn ->
            return find(conn, gameSeedId)
        }
    }

    override fun createOrExisting(batch: StockSeedSnapshotBatch): StockSeedSnapshotBatch {
        val candidate = batch.canonicalCandidate()
        dataSource.connection.use { conn ->
            // READ COMMITTED gives the conflict loser a fresh snapshot after the winner commits.
            conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            conn.autoCommit = false
            try {
                if (claimBatch(conn, candidate)) {
                    insertSnapshots(conn, candidate)
                }
                val winner = checkNotNull(find(conn, candidate.gameSeedId)) { "Seed batch missing after publication" }
                conn.commit()
                return winner
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun claimBatch(conn: Connection, candidate: StockSeedSnapshotBatch): Boolean {
        return conn.prepareStatement(
            """
            INSERT INTO stock_data.seed_snapshot_batches (game_seed_id, as_of, batch_seed_hash)
            VALUES (?, ?, ?)
            ON CONFLICT (game_seed_id) DO NOTHING
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, candidate.gameSeedId)
            stmt.setTimestamp(2, Timestamp.from(candidate.asOf))
            stmt.setString(3, candidate.batchSeedHash)
            stmt.executeUpdate() == 1
        }
    }

    private fun insertSnapshots(conn: Connection, candidate: StockSeedSnapshotBatch) {
        conn.prepareStatement(
            """
            INSERT INTO stock_data.seed_snapshots (
                game_seed_id, symbol, provider, source_type, as_of, source_timestamp,
                retrieved_at, currency, price, open, high, low, previous_close, volume,
                raw_provider_payload_hash, selection_reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            for (snapshot in candidate.snapshots) {
                stmt.setString(1, snapshot.gameSeedId)
                stmt.setString(2, snapshot.symbol)
                stmt.setString(3, snapshot.provider)
                stmt.setString(4, snapshot.sourceType.wireValue)
                stmt.setTimestamp(5, Timestamp.from(snapshot.asOf))
                stmt.setTimestamp(6, Timestamp.from(snapshot.sourceTimestamp))
                stmt.setTimestamp(7, Timestamp.from(snapshot.retrievedAt))
                stmt.setString(8, snapshot.currency)
                stmt.setBigDecimal(9, snapshot.price)
                stmt.setNullableBigDecimal(10, snapshot.open)
                stmt.setNullableBigDecimal(11, snapshot.high)
                stmt.setNullableBigDecimal(12, snapshot.low)
                stmt.setNullableBigDecimal(13, snapshot.previousClose)
                if (snapshot.volume != null) stmt.setLong(14, snapshot.volume) else stmt.setNull(14, Types.BIGINT)
                stmt.setString(15, snapshot.rawProviderPayloadHash)
                stmt.setString(16, snapshot.selectionReason)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun find(conn: Connection, gameSeedId: String): StockSeedSnapshotBatch? {
        val asOf = findBatchAsOf(conn, gameSeedId) ?: return null
        return StockSeedSnapshotBatch(gameSeedId, asOf, findSnapshots(conn, gameSeedId))
    }

    private fun findBatchAsOf(conn: Connection, gameSeedId: String): Instant? {
        conn.prepareStatement(
            "SELECT as_of FROM stock_data.seed_snapshot_batches WHERE game_seed_id = ?",
        ).use { stmt ->
            stmt.setString(1, gameSeedId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return rs.getTimestamp("as_of").toInstant()
            }
        }
    }

    private fun findSnapshots(conn: Connection, gameSeedId: String): List<StockSeedSnapshot> {
        conn.prepareStatement(
            """
            SELECT symbol, provider, source_type, as_of, source_timestamp, retrieved_at, currency,
                   price, open, high, low, previous_close, volume, raw_provider_payload_hash, selection_reason
            FROM stock_data.seed_snapshots
            WHERE game_seed_id = ?
            ORDER BY symbol
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, gameSeedId)
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<StockSeedSnapshot>()
                while (rs.next()) {
                    results += rs.toSnapshot(gameSeedId)
                }
                return Collections.unmodifiableList(results)
            }
        }
    }

    private fun ResultSet.toSnapshot(gameSeedId: String): StockSeedSnapshot = StockSeedSnapshot(
        gameSeedId = gameSeedId,
        symbol = getString("symbol"),
        provider = getString("provider"),
        sourceType = SourceType.entries.first { it.wireValue == getString("source_type") },
        asOf = getTimestamp("as_of").toInstant(),
        sourceTimestamp = getTimestamp("source_timestamp").toInstant(),
        retrievedAt = getTimestamp("retrieved_at").toInstant(),
        currency = getString("currency"),
        price = getBigDecimal("price"),
        open = getNullableBigDecimal("open"),
        high = getNullableBigDecimal("high"),
        low = getNullableBigDecimal("low"),
        previousClose = getNullableBigDecimal("previous_close"),
        volume = getLong("volume").takeUnless { wasNull() },
        rawProviderPayloadHash = getString("raw_provider_payload_hash"),
        selectionReason = getString("selection_reason"),
    )

    private fun ResultSet.getNullableBigDecimal(column: String): BigDecimal? =
        getBigDecimal(column).takeUnless { wasNull() }

    private fun java.sql.PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
        if (value != null) setBigDecimal(index, value) else setNull(index, Types.NUMERIC)
    }
}
