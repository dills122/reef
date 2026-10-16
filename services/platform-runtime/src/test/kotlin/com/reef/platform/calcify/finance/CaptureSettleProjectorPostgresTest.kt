package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource

/**
 * The projector against a real Postgres for decision mode `capture-settle-v1`: random scripts (funding, shortfalls, overflow, redelivery, operator retries) are decided by a
 * two-step ledger and by a capture-settle ledger, both decision logs are projected under their own bindings with random batching and redelivery, and the position,
 * obligation and attempt tables must be identical (and equal to the ledger) while the combined projection holds one decision row per booked trade. Needs Docker
 * (Postgres 16 in a throwaway container with a random port, removed afterwards); tagged `broker`.
 */
@Tag("broker")
class CaptureSettleProjectorPostgresTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val msft = AssetId("MSFT"); private val opening = AccountId("opening")
    private fun config(mode: DecisionMode) = CapacityConfig(null, usd, setOf(aapl, msft), opening, 100_000, 100_000, 100_000, 100_000, mode)

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.toList()} failed: $out" }; return out
    }

    private fun withPostgres(body: (PGSimpleDataSource) -> Unit) {
        val name = "cap-cs-pg-${System.nanoTime().toString(36)}"; val port = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=cap", "-e", "POSTGRES_DB=reef", "-p", "$port:5432", "postgres:16-alpine")
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", name, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(1000) } }
            val migrations = listOf("0014_calcify_finance_projection.sql", "0016_calcify_finance_sequence_bound.sql", "0017_calcify_finance_decision_digest_checks.sql").joinToString("\n") { Files.readString(Path.of("../../scripts/dev/db/migrations/boundary/$it")) }
            sh("docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migrations")
            body(PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$port/reef"); user = "postgres"; password = "cap" })
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", name).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }

    private data class Trade(val id: String, val instrument: String, val buyer: AccountId, val seller: AccountId, val qty: Long, val px: Long) {
        val execution get() = ExecutionKey("run", "session", instrument, id)
        fun capture() = FinanceInput.Capture(ActionKey("calcify-finance", "capture", id), execution, buyer, seller, qty, px)
        fun firstSettle() = FinanceInput.Settle(ActionKey("calcify-finance", "settle", "$id/1"), Authority.OPERATOR, execution, 1)
        fun combined() = CapacityCaptureMapping.captureSettle(capture())
    }

    private class Logs(val two: List<Pair<Decision, FinanceInput>>, val one: List<Pair<Decision, FinanceInput>>, val twoState: FinanceState, val oneState: FinanceState)

    /** Funding first (so most trades have means), then a mix of trades, redeliveries, conflicts and operator retries. */
    private fun script(seed: Long): Logs {
        val rnd = Random(seed); val two = CapacityLedger(CapacityConfig(null, usd, setOf(aapl, msft), opening, 100_000, 100_000, 100_000, 100_000)); val one = CapacityLedger(CapacityConfig(null, usd, setOf(aapl, msft), opening, 100_000, 100_000, 100_000, 100_000, DecisionMode.CAPTURE_SETTLE))
        val a = ArrayList<Pair<Decision, FinanceInput>>(); val b = ArrayList<Pair<Decision, FinanceInput>>()
        fun both(i: FinanceInput) { two.decide(i).decision?.let { a += it to i }; one.decide(i).decision?.let { b += it to i } }
        var k = 0; val sent = HashMap<String, Trade>()
        for (acct in 0 until 5) for (asset in listOf(usd, aapl, msft)) if (rnd.nextInt(5) != 0) both(FinanceInput.Fund(ActionKey("n", "f", "f${k++}"), Authority.OPERATOR, AccountId("acct-$acct"), asset, 20L + rnd.nextInt(300)))
        repeat(220) {
            val roll = rnd.nextInt(100)
            when {
                roll < 10 -> both(FinanceInput.Fund(ActionKey("n", "f", "f${k++}"), Authority.OPERATOR, AccountId("acct-${rnd.nextInt(5)}"), listOf(usd, aapl, msft)[rnd.nextInt(3)], 1L + rnd.nextInt(400)))
                roll < 85 -> {
                    val id = "e${rnd.nextInt(60)}"; val prior = sent[id]
                    val t = if (prior != null && rnd.nextBoolean()) prior else if (prior != null && rnd.nextInt(5) == 0) prior.copy(px = prior.px + 1)
                    else Trade(id, if (rnd.nextBoolean()) "AAPL" else "MSFT", AccountId("acct-${rnd.nextInt(5)}"), AccountId("acct-${rnd.nextInt(5)}"), 1L + rnd.nextInt(4), 1L + rnd.nextInt(9))
                    sent.putIfAbsent(id, t)
                    val c = two.decide(t.capture()); c.decision?.let { a += it to t.capture(); two.decide(t.firstSettle()).decision?.let { s -> a += s to t.firstSettle() } }
                    one.decide(t.combined()).decision?.let { b += it to t.combined() }
                }
                else -> {
                    val t = sent.values.toList().let { if (it.isEmpty()) null else it[rnd.nextInt(it.size)] }
                    if (t != null) both(FinanceInput.Settle(ActionKey("n", "d", "r${k++}"), Authority.OPERATOR, t.execution, 2 + rnd.nextInt(2)))
                }
            }
        }
        return Logs(a, b, two.snapshot(), one.snapshot())
    }

    private fun rows(ds: PGSimpleDataSource, sql: String, binding: String): List<List<String>> = ds.connection.use { c ->
        c.prepareStatement(sql).use { ps -> ps.setString(1, binding); ps.executeQuery().use { r ->
            val n = r.metaData.columnCount; generateSequence { if (r.next()) (1..n).map { r.getString(it) } else null }.toList().sortedBy { it.joinToString("|") } } }
    }
    private fun tables(ds: PGSimpleDataSource, binding: String) = listOf(
        rows(ds, "SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ? AND amount <> 0", binding),
        rows(ds, "SELECT run_id, venue_session_id, instrument_id, execution_id, buyer_account_id, seller_account_id, quantity, price_nanos, status, cash_residual, share_residual FROM boundary.calcify_finance_obligation WHERE binding_digest = ?", binding),
        rows(ds, "SELECT run_id, venue_session_id, instrument_id, execution_id, attempt, disposition FROM boundary.calcify_finance_attempt WHERE binding_digest = ?", binding),
    )
    private fun digest() = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)

    private fun project(ds: PGSimpleDataSource, mode: DecisionMode, records: List<Pair<Decision, FinanceInput>>, rnd: Random): String {
        val projector = FinanceProjector(CapacityChain(config(mode)), ds); val binding = digest(); projector.open(binding, 1, sequenceBound = 100_000)
        var i = 0
        while (i < records.size) {
            val batch = records.subList(i, minOf(records.size, i + 1 + rnd.nextInt(9)))
            projector.apply(binding, 1, batch)
            if (rnd.nextInt(3) == 0) assertEquals(0, projector.apply(binding, 1, batch), "redelivery after commit applies nothing")
            i += batch.size
        }
        return binding
    }

    @Test fun theCombinedProjectionHasTheTwoStepTablesAndOneDecisionRowPerBookedTrade() {
        withPostgres { ds ->
            var shortfalls = 0; var pendingForever = 0; var settledAtOnce = 0
            for (seed in 1L..25L) {
                val logs = script(seed); val rnd = Random(seed)
                assertTrue(logs.one.size > 20, "seed $seed produced work")
                val twoBinding = project(ds, DecisionMode.TWO_STEP, logs.two, rnd); val oneBinding = project(ds, DecisionMode.CAPTURE_SETTLE, logs.one, rnd)
                assertEquals(tables(ds, twoBinding), tables(ds, oneBinding), "seed $seed: positions, obligations and attempts are identical to the two-step projection")
                // and equal to the ledger the log came from
                val s = logs.oneState
                assertEquals(s.positions.filterValues { it != 0L }.map { listOf(it.key.first.value, it.key.second.value, it.value.toString()) }.sortedBy { it.joinToString("|") }, tables(ds, oneBinding)[0], "seed $seed positions")
                assertEquals(s.obligations.map { (k, o) -> val e = s.executions.getValue(k); listOf(k.runId, k.venueSessionId, k.instrumentId, k.executionId, e.buyer.value, e.seller.value, e.quantity.toString(), e.priceNanos.toString(), o.status.name, o.cashResidual.toString(), o.shareResidual.toString()) }
                    .sortedBy { it.joinToString("|") }, tables(ds, oneBinding)[1], "seed $seed obligations")
                val decisionRows = { b: String -> rows(ds, "SELECT count(*) FROM boundary.calcify_finance_decision WHERE binding_digest = ?", b).single().single().toLong() }
                assertEquals(logs.one.size.toLong(), decisionRows(oneBinding)); assertEquals(logs.two.size.toLong(), decisionRows(twoBinding))
                assertEquals(listOf(listOf(logs.one.size.toString(), logs.one.last().first.checksum)), rows(ds, "SELECT last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?", oneBinding))
                val combinedTrades = logs.one.count { it.second is FinanceInput.CaptureSettle }
                val captures = logs.two.count { it.second is FinanceInput.Capture }
                assertEquals(captures, combinedTrades, "the same trades were booked"); assertEquals(logs.two.size - 2 * captures, logs.one.size - combinedTrades, "the two-step log has one more decision per booked trade (funds and retries are equal)")
                shortfalls += logs.one.count { it.second is FinanceInput.CaptureSettle && it.first.disposition != Disposition.SETTLED }
                pendingForever += s.obligations.values.count { it.status == ObligationStatus.PENDING }
                settledAtOnce += logs.one.count { it.second is FinanceInput.CaptureSettle && it.first.disposition == Disposition.SETTLED }
            }
            assertTrue(shortfalls > 20 && settledAtOnce > 100, "the scripts exercised shortfalls ($shortfalls) and settle-at-once trades ($settledAtOnce); pending at the end: $pendingForever")
        }
    }

    @Test fun aSettleOfAShortfallTradeInTheSameBatchOrALaterOneUpdatesTheRowTheCombinedDecisionInserted() {
        withPostgres { ds ->
            val cfg = config(DecisionMode.CAPTURE_SETTLE); val l = CapacityLedger(cfg); val log = ArrayList<Pair<Decision, FinanceInput>>()
            fun d(i: FinanceInput) { l.decide(i).decision?.let { log += it to i } }
            val t = Trade("e1", "AAPL", AccountId("buyer"), AccountId("seller"), 3, 10)
            d(FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, AccountId("seller"), aapl, 10)); d(FinanceInput.Fund(ActionKey("n", "f", "2"), Authority.OPERATOR, AccountId("buyer"), usd, 5))
            d(t.combined())                                                                                   // INSUFFICIENT_CASH: pending, attempt 1
            d(FinanceInput.Fund(ActionKey("n", "f", "3"), Authority.OPERATOR, AccountId("buyer"), usd, 100))
            d(FinanceInput.Settle(ActionKey("n", "r", "2"), Authority.OPERATOR, t.execution, 2))
            assertEquals(listOf(Disposition.FUNDED, Disposition.FUNDED, Disposition.INSUFFICIENT_CASH, Disposition.FUNDED, Disposition.SETTLED), log.map { it.first.disposition })
            for (split in listOf(5, 3, 1)) {                                                                    // all in one batch, then the settle in a later batch, then every record alone
                val projector = FinanceProjector(CapacityChain(cfg), ds); val binding = digest(); projector.open(binding, 1, sequenceBound = 1_000)
                log.chunked(split).forEach { projector.apply(binding, 1, it) }
                assertEquals(listOf(listOf("run", "session", "AAPL", "e1", "buyer", "seller", "3", "10", "SETTLED", "0", "0")), tables(ds, binding)[1], "split $split")
                assertEquals(listOf(listOf("run", "session", "AAPL", "e1", "1", "INSUFFICIENT_CASH"), listOf("run", "session", "AAPL", "e1", "2", "SETTLED")), tables(ds, binding)[2], "split $split")
            }
        }
    }

    @Test fun aCaptureSettleDecisionThatIsNotASettleOutcomeIsRefusedAndRollsTheBatchBack() {
        withPostgres { ds ->
            val cfg = config(DecisionMode.CAPTURE_SETTLE); val l = CapacityLedger(cfg)
            val funds = listOf(FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, AccountId("seller"), aapl, 10), FinanceInput.Fund(ActionKey("n", "f", "2"), Authority.OPERATOR, AccountId("buyer"), usd, 500))
            val log = funds.map { f -> l.decide(f).decision!! to (f as FinanceInput) }
            val t = Trade("e1", "AAPL", AccountId("buyer"), AccountId("seller"), 3, 10); val real = l.decide(t.combined()).decision!!
            // a well-chained decision whose disposition is a capture, not a settle outcome: the chain check passes, the projection mapping must not
            val forged = real.copy(disposition = Disposition.CAPTURED).let { it.copy(checksum = CapacityLedger.checksumOf(it.sequence, it.action, it.inputDigest, it.disposition, it.legs, it.priorChain, it.stateDigest)) }
            val projector = FinanceProjector(CapacityChain(cfg), ds); val binding = digest(); projector.open(binding, 1, sequenceBound = 1_000)
            projector.apply(binding, 1, log)
            assertFailsWith<IllegalStateException> { projector.apply(binding, 1, listOf(forged to t.combined())) }
            assertEquals(listOf(listOf("2")), rows(ds, "SELECT count(*) FROM boundary.calcify_finance_decision WHERE binding_digest = ?", binding))
            assertEquals(emptyList(), tables(ds, binding)[1], "nothing of the refused batch was written")
            assertEquals(1, projector.apply(binding, 1, listOf(real to t.combined())), "the genuine decision still applies")
        }
    }
}
