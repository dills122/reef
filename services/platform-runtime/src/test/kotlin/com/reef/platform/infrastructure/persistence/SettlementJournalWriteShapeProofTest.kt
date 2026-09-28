package com.reef.platform.infrastructure.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Logged-PostgreSQL write-shape proof, not a settlement implementation or capacity qualification. */
class SettlementJournalWriteShapeProofTest {
    private val originDigest = "0".repeat(64)

    private data class Result(
        val tradeId: String,
        val sourceSequence: Long,
        val buyer: String,
        val seller: String,
        val cash: BigDecimal,
        val quantity: BigDecimal,
        val outcome: String,
        val breakReason: String?,
        val workflow: String
    )

    @Test
    fun scarceWinnerAndDuplicateAppendRemainOrdered() {
        val dataSource = dataSourceOrSkip()
        withProofSchema(dataSource) { connection, schema ->
            val balances = mutableMapOf(
                "buyer-0:CASH" to BigDecimal("400"),
                "seller-0:SECURITY" to BigDecimal.ONE
            )
            val first = result(1)
            val second = result(2)
            val decisions = evaluate(listOf(first, second), balances)
            assertEquals(listOf("SETTLED", "BREAK"), decisions.map { it.outcome })
            assertEquals("SECURITY_LEG_FAILED", decisions[1].breakReason)
            assertEquals(BigDecimal.ZERO, balances.getValue("seller-0:SECURITY"))

            val head = append(connection, schema, "stream", "generation", 1, 1, decisions)
            assertEquals(2L, head)
            assertEquals(2L, rowCount(connection, schema, "results"))
            assertEquals(2L, append(connection, schema, "stream", "generation", 1, 1, decisions))
            assertFailsWith<IllegalStateException> {
                append(connection, schema, "stream", "generation", 2, 0, listOf(result(3)))
            }
            assertFailsWith<IllegalStateException> {
                append(connection, schema, "stream", "generation", 1, 1, decisions.reversed())
            }
            assertFailsWith<IllegalStateException> {
                append(connection, schema, "stream", "generation", 2, 1,
                    listOf(result(3).copy(outcome = "SETTLED"))) {
                    error("injected crash before commit")
                }
            }
            assertEquals(2L, rowCount(connection, schema, "results"))
            assertEquals(2L, readHead(connection, schema, "stream", "generation").first)
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE $schema.heads SET owner_epoch = 2 " +
                    "WHERE event_stream = 'stream'")
            }
            assertFailsWith<IllegalStateException> {
                append(connection, schema, "stream", "generation", 2, 1,
                    listOf(result(3).copy(outcome = "SETTLED")))
            }
            assertEquals(3L, append(connection, schema, "stream", "generation", 2, 2,
                listOf(result(3).copy(outcome = "SETTLED"))))
            verifyChain(connection, schema, "stream", "generation")
            assertEquals(3L, rowCount(connection, schema, "results"))
            assertEquals(3L, readHead(connection, schema, "stream", "generation").first)
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE $schema.results SET cash_amount = 201 " +
                    "WHERE event_stream = 'stream' AND result_index = 0")
            }
            assertFailsWith<IllegalStateException> {
                verifyChain(connection, schema, "stream", "generation")
            }
            assertFailsWith<IllegalStateException> {
                append(connection, schema, "stream", "generation", 1, 2, decisions)
            }
        }
    }

    @Test
    fun loggedHotBatchWriteShape() {
        assumeTrue(System.getenv("SETTLEMENT_JOURNAL_PROOF_BENCH") == "1",
            "opt in to local write-shape measurement")
        val dataSource = dataSourceOrSkip()
        withProofSchema(dataSource) { connection, schema ->
            val trades = (1..640).map { result(it.toLong(), (it - 1) % 80) }
            val samples = mutableListOf<Double>()
            repeat(8) { iteration ->
                val stream = "hot-$iteration"
                seedHead(connection, schema, stream, "generation")
                val balances = mutableMapOf<String, BigDecimal>()
                repeat(80) { account ->
                    balances["buyer-$account:CASH"] = BigDecimal("1600")
                    balances["seller-$account:SECURITY"] = BigDecimal("8")
                }
                var decisions: List<Result> = emptyList()
                val evaluationMs = measureNanoTime { decisions = evaluate(trades, balances) } / 1_000_000.0
                assertEquals(640, decisions.count { it.outcome == "SETTLED" })
                val startLsn = walLsn(connection)
                val appendMs = measureNanoTime {
                    append(connection, schema, stream, "generation", 1, 1, decisions)
                } / 1_000_000.0
                val walBytes = walBytes(connection, startLsn)
                println("journal-proof iteration=$iteration evaluation_ms=$evaluationMs " +
                    "append_ms=$appendMs wal_bytes=$walBytes rows=${decisions.size}")
                if (iteration >= 2) samples += appendMs
            }
            val sorted = samples.sorted()
            println("journal-proof hot640 append_ms_min=${sorted.first()} " +
                "append_ms_p50=${sorted[sorted.size / 2]} append_ms_max=${sorted.last()} " +
                "workload_budget_ms=145.45")
            assertEquals(8L * 640L, rowCount(connection, schema, "results"))
            verifyChain(connection, schema, "hot-7", "generation")
        }
    }

    private fun evaluate(trades: List<Result>, balances: MutableMap<String, BigDecimal>): List<Result> =
        trades.map { trade ->
            val buyerCash = "${trade.buyer}:CASH"
            val sellerSecurity = "${trade.seller}:SECURITY"
            val sellerCash = "${trade.seller}:CASH"
            val buyerSecurity = "${trade.buyer}:SECURITY"
            val cash = balances.getValue(buyerCash)
            val security = balances.getValue(sellerSecurity)
            val cashEnough = cash >= trade.cash
            val securityEnough = security >= trade.quantity
            if (cashEnough && securityEnough) {
                balances[buyerCash] = cash - trade.cash
                balances[sellerSecurity] = security - trade.quantity
                balances[sellerCash] = balances.getOrDefault(sellerCash, BigDecimal.ZERO) + trade.cash
                balances[buyerSecurity] = balances.getOrDefault(buyerSecurity, BigDecimal.ZERO) + trade.quantity
                trade.copy(outcome = "SETTLED")
            } else {
                trade.copy(outcome = "BREAK", breakReason =
                    if (!cashEnough) "CASH_LEG_FAILED" else "SECURITY_LEG_FAILED")
            }
        }

    private fun result(sequence: Long, accountGroup: Int = 0) = Result(
        tradeId = "trade-$sequence", sourceSequence = sequence,
        buyer = "buyer-$accountGroup", seller = "seller-$accountGroup", cash = BigDecimal("200"),
        quantity = BigDecimal.ONE, outcome = "PENDING", breakReason = null,
        workflow = workflow("trade-$sequence")
    )

    private fun workflow(tradeId: String): String {
        val at = "2026-09-26T00:00:01Z"
        val stages = listOf("ALLOCATION_PROPOSED", "CONFIRMATION_GENERATED", "AFFIRMATION_ACCEPTED",
            "CLEARING_SUBMITTED", "CLEARING_ACCEPTED", "NOVATION_RECORDED", "INSTRUCTION_CREATED",
            "ATTEMPT_STARTED", "CASH_LEG", "SECURITY_LEG", "SETTLED")
        return stages.joinToString(prefix = "[", postfix = "]") { kind ->
            val state = if (kind.endsWith("_LEG")) ",\"state\":\"LEG_SUCCEEDED\"" else ""
            "{\"id\":\"$tradeId:$kind:1\",\"kind\":\"$kind\"$state,\"occurredAt\":\"$at\"}"
        }
    }

    private fun append(
        connection: Connection, schema: String, stream: String, generation: String,
        expectedSequence: Long, ownerEpoch: Long, results: List<Result>,
        beforeCommit: () -> Unit = {}
    ): Long {
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val head = connection.prepareStatement(
                "SELECT next_sequence, owner_epoch, last_digest FROM $schema.heads " +
                    "WHERE event_stream = ? AND source_generation = ? FOR UPDATE"
            ).use { statement ->
                statement.setString(1, stream)
                statement.setString(2, generation)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "journal head missing" }
                    Triple(rows.getLong(1), rows.getLong(2), rows.getString(3))
                }
            }
            val (sequence, epoch, previousDigest) = head
            if (sequence > expectedSequence) {
                val stored = connection.prepareStatement(
                    "SELECT previous_digest, result_digest, batch_digest FROM $schema.batches " +
                        "WHERE event_stream = ? AND source_generation = ? AND batch_sequence = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.setLong(3, expectedSequence)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "committed batch missing" }
                        Triple(rows.getString(1), rows.getString(2), rows.getString(3))
                    }
                }
                check(stored.second == resultsDigest(results) &&
                    stored.third == batchDigest(stored.first, expectedSequence, stored.second)) {
                    "duplicate batch differs from committed result"
                }
                verifyChain(connection, schema, stream, generation)
                connection.commit()
                return sequence
            }
            check(sequence == expectedSequence && epoch == ownerEpoch) { "journal owner or order changed" }
            val resultDigest = resultsDigest(results)
            val digest = batchDigest(previousDigest, sequence, resultDigest)
            connection.prepareStatement(
                "INSERT INTO $schema.batches(event_stream, source_generation, batch_sequence, result_count, " +
                    "previous_digest, result_digest, batch_digest) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, stream)
                statement.setString(2, generation)
                statement.setLong(3, sequence)
                statement.setInt(4, results.size)
                statement.setString(5, previousDigest)
                statement.setString(6, resultDigest)
                statement.setString(7, digest)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "INSERT INTO $schema.results(event_stream, source_generation, batch_sequence, result_index, " +
                    "trade_id, source_sequence, buyer_account_id, seller_account_id, cash_amount, " +
                    "quantity_units, outcome, break_reason, workflow_facts) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                results.forEachIndexed { index, result ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.setLong(3, sequence)
                    statement.setInt(4, index)
                    statement.setString(5, result.tradeId)
                    statement.setLong(6, result.sourceSequence)
                    statement.setString(7, result.buyer)
                    statement.setString(8, result.seller)
                    statement.setBigDecimal(9, result.cash)
                    statement.setBigDecimal(10, result.quantity)
                    statement.setString(11, result.outcome)
                    statement.setString(12, result.breakReason)
                    statement.setString(13, result.workflow)
                    statement.addBatch()
                }
                check(statement.executeBatch().size == results.size)
            }
            connection.prepareStatement(
                "UPDATE $schema.heads SET next_sequence = next_sequence + 1, last_digest = ? " +
                    "WHERE event_stream = ? AND source_generation = ? AND next_sequence = ? AND owner_epoch = ?"
            ).use { statement ->
                statement.setString(1, digest)
                statement.setString(2, stream)
                statement.setString(3, generation)
                statement.setLong(4, sequence)
                statement.setLong(5, ownerEpoch)
                check(statement.executeUpdate() == 1)
            }
            beforeCommit()
            connection.commit()
            return sequence + 1
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    private fun seedHead(connection: Connection, schema: String, stream: String, generation: String) {
        connection.prepareStatement(
            "INSERT INTO $schema.heads(event_stream, source_generation) VALUES (?, ?)"
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun readHead(connection: Connection, schema: String, stream: String, generation: String): Triple<Long, Long, String> =
        connection.prepareStatement(
            "SELECT next_sequence, owner_epoch, last_digest FROM $schema.heads " +
                "WHERE event_stream = ? AND source_generation = ?"
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.executeQuery().use { rows ->
                check(rows.next()); Triple(rows.getLong(1), rows.getLong(2), rows.getString(3))
            }
        }

    private fun verifyChain(connection: Connection, schema: String, stream: String, generation: String) {
        var previous = originDigest
        var expected = 1L
        connection.prepareStatement(
            "SELECT batch_sequence, result_count, previous_digest, result_digest, batch_digest " +
                "FROM $schema.batches WHERE event_stream = ? AND source_generation = ? ORDER BY batch_sequence"
        ).use { statement ->
            statement.setString(1, stream)
            statement.setString(2, generation)
            statement.executeQuery().use { batches ->
                while (batches.next()) {
                    val sequence = batches.getLong(1)
                    check(sequence == expected && batches.getString(3) == previous) { "journal chain gap" }
                    val results = connection.prepareStatement(
                        "SELECT result_index, trade_id, source_sequence, buyer_account_id, seller_account_id, " +
                            "cash_amount, quantity_units, outcome, break_reason, workflow_facts " +
                            "FROM $schema.results WHERE event_stream = ? AND source_generation = ? " +
                            "AND batch_sequence = ? ORDER BY result_index"
                    ).use { rowsStatement ->
                        rowsStatement.setString(1, stream)
                        rowsStatement.setString(2, generation)
                        rowsStatement.setLong(3, sequence)
                        rowsStatement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    check(rows.getInt(1) == size) { "journal result order gap" }
                                    add(Result(
                                        rows.getString(2), rows.getLong(3), rows.getString(4), rows.getString(5),
                                        rows.getBigDecimal(6), rows.getBigDecimal(7), rows.getString(8),
                                        rows.getString(9), rows.getString(10)
                                    ))
                                }
                            }
                        }
                    }
                    check(results.size == batches.getInt(2) &&
                        resultsDigest(results) == batches.getString(4)) { "journal result changed" }
                    previous = batchDigest(previous, sequence, batches.getString(4))
                    check(previous == batches.getString(5)) { "journal batch digest changed" }
                    expected++
                }
            }
        }
        val head = readHead(connection, schema, stream, generation)
        check(head.first == expected && head.third == previous) { "journal head disagrees with batches" }
    }

    private fun resultsDigest(results: List<Result>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        results.forEachIndexed { index, result ->
            hashField(hash, index.toString())
            hashField(hash, result.tradeId)
            hashField(hash, result.sourceSequence.toString())
            hashField(hash, result.buyer)
            hashField(hash, result.seller)
            hashField(hash, result.cash.stripTrailingZeros().toPlainString())
            hashField(hash, result.quantity.stripTrailingZeros().toPlainString())
            hashField(hash, result.outcome)
            hashField(hash, result.breakReason)
            hashField(hash, result.workflow)
        }
        return hex(hash.digest())
    }

    private fun batchDigest(previous: String, sequence: Long, resultDigest: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hashField(hash, previous)
        hashField(hash, sequence.toString())
        hashField(hash, resultDigest)
        return hex(hash.digest())
    }

    private fun hashField(hash: MessageDigest, value: String?) {
        if (value == null) {
            hash.update(ByteBuffer.allocate(4).putInt(-1).array())
        } else {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun rowCount(connection: Connection, schema: String, table: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $schema.$table").use { rows ->
                check(rows.next()); rows.getLong(1)
            }
        }

    private fun walLsn(connection: Connection): String = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT pg_current_wal_lsn()").use { rows ->
            check(rows.next()); rows.getString(1)
        }
    }

    private fun walBytes(connection: Connection, from: String): Long = connection.prepareStatement(
        "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)"
    ).use { statement ->
        statement.setString(1, from)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
    }

    private fun withProofSchema(
        dataSource: javax.sql.DataSource,
        block: (Connection, String) -> Unit
    ) {
        val schema = "journal_proof_${UUID.randomUUID().toString().replace("-", "")}".take(45)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("""CREATE TABLE $schema.heads (
                    event_stream TEXT NOT NULL, source_generation TEXT NOT NULL,
                    next_sequence BIGINT NOT NULL DEFAULT 1, owner_epoch BIGINT NOT NULL DEFAULT 1,
                    last_digest TEXT NOT NULL DEFAULT '${originDigest}',
                    PRIMARY KEY (event_stream, source_generation))""")
                statement.execute("""CREATE TABLE $schema.batches (
                    event_stream TEXT NOT NULL, source_generation TEXT NOT NULL,
                    batch_sequence BIGINT NOT NULL, result_count INTEGER NOT NULL,
                    previous_digest TEXT NOT NULL, result_digest TEXT NOT NULL, batch_digest TEXT NOT NULL,
                    PRIMARY KEY (event_stream, source_generation, batch_sequence))""")
                statement.execute("""CREATE TABLE $schema.results (
                    event_stream TEXT NOT NULL, source_generation TEXT NOT NULL,
                    batch_sequence BIGINT NOT NULL, result_index INTEGER NOT NULL,
                    trade_id TEXT NOT NULL, source_sequence BIGINT NOT NULL,
                    buyer_account_id TEXT NOT NULL, seller_account_id TEXT NOT NULL,
                    cash_amount NUMERIC NOT NULL, quantity_units NUMERIC NOT NULL,
                    outcome TEXT NOT NULL CHECK (outcome IN ('SETTLED', 'BREAK')),
                    break_reason TEXT, workflow_facts TEXT NOT NULL,
                    PRIMARY KEY (event_stream, source_generation, batch_sequence, result_index),
                    UNIQUE (event_stream, source_generation, trade_id),
                    FOREIGN KEY (event_stream, source_generation, batch_sequence)
                        REFERENCES $schema.batches(event_stream, source_generation, batch_sequence),
                    CHECK ((outcome = 'BREAK') = (break_reason IS NOT NULL)))""")
            }
            try {
                seedHead(connection, schema, "stream", "generation")
                block(connection, schema)
            } finally {
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun dataSourceOrSkip(): javax.sql.DataSource {
        assumeTrue(System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST") != null &&
            System.getenv("SETTLEMENT_POSTGRES_USER_TEST") != null &&
            System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST") != null,
            "dedicated settlement PostgreSQL test database is required")
        return RuntimeDataSources.dataSource(
            System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST"),
            System.getenv("SETTLEMENT_POSTGRES_USER_TEST"),
            System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST"), "settlement-journal-proof"
        )
    }
}
