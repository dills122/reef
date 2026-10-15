package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.SQLException
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Explicit opt-in suite: absent a provisioned Postgres the tests are reported skipped, never passed. */
class FinanceProjectorPostgresTest {
    private val buyer = AccountId("buyer-account"); private val seller = AccountId("seller-account")
    private val usd = AssetId("USD"); private val aapl = AssetId("fixture-base-AAPL")
    private val ledger = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("opening")))

    private fun dataSource(): PGSimpleDataSource {
        val prefix = "CALCIFY_FINITE_POSTGRES_TEST_"
        val url = System.getenv(prefix + "JDBC_URL")
        assumeTrue(!url.isNullOrBlank(), "isolated finite Postgres test fixture not provisioned")
        val user = System.getenv(prefix + "PROJECTOR_USER") ?: error("projector test user missing")
        val file = Path.of(System.getenv(prefix + "PROJECTOR_PASSWORD_FILE") ?: error("private projector password file missing"))
        require(Files.getPosixFilePermissions(file).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "private test secret permissions" }
        return PGSimpleDataSource().apply { setUrl(url); this.user = user; password = Files.readString(file).trimEnd('\n', '\r') }
    }
    private fun digest() = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)

    private fun key(i: Int) = ExecutionKey("p3-run", "p3-session", "AAPL", "e$i")
    private fun run(seed: Long, steps: Int = 80): Pair<List<Pair<Decision, FinanceInput>>, FinanceState> {
        val rnd = Random(seed); var state = ledger.genesis(); val out = ArrayList<Pair<Decision, FinanceInput>>()
        repeat(steps) { n ->
            val input = when (rnd.nextInt(4)) {
                0 -> FinanceInput.Capture(ActionKey("n", "cap", "c$n"), key(rnd.nextInt(6)), if (rnd.nextBoolean()) buyer else seller, if (rnd.nextBoolean()) seller else buyer, 1L + rnd.nextInt(4), 1_000_000_000L * rnd.nextInt(3))
                1 -> FinanceInput.Fund(ActionKey("n", "ctl", "f$n"), Authority.OPERATOR, if (rnd.nextBoolean()) buyer else seller, if (rnd.nextBoolean()) usd else aapl, 1L + rnd.nextInt(2_000_000_000) * 10L)
                else -> FinanceInput.Settle(ActionKey("n", "rep", "s$n"), Authority.OPERATOR, key(rnd.nextInt(6)), 1 + rnd.nextInt(3))
            }
            val o = ledger.decide(state, input); state = o.state
            o.decision?.let { out += it to input }
        }
        return out to state
    }

    private fun rows(ds: PGSimpleDataSource, sql: String, binding: String): List<List<String>> = ds.connection.use { c ->
        c.prepareStatement(sql).use { ps -> ps.setString(1, binding); ps.executeQuery().use { r ->
            val n = r.metaData.columnCount; generateSequence { if (r.next()) (1..n).map { r.getString(it) } else null }.toList() } }
    }

    private fun assertProjectionEquals(ds: PGSimpleDataSource, binding: String, state: FinanceState, label: String) {
        assertEquals(state.positions.filterValues { it != 0L }.map { listOf(it.key.first.value, it.key.second.value, it.value.toString()) }.sortedBy { it.joinToString("|") },
            rows(ds, "SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ? AND amount <> 0", binding).sortedBy { it.joinToString("|") }, "$label positions")
        assertEquals(state.obligations.map { (k, o) -> val e = state.executions.getValue(k)
            listOf(k.runId, k.venueSessionId, k.instrumentId, k.executionId, e.buyer.value, e.seller.value, e.quantity.toString(), e.priceNanos.toString(), o.status.name, o.cashResidual.toString(), o.shareResidual.toString()) }
            .sortedBy { it.joinToString("|") },
            rows(ds, "SELECT run_id, venue_session_id, instrument_id, execution_id, buyer_account_id, seller_account_id, quantity, price_nanos, status, cash_residual, share_residual FROM boundary.calcify_finance_obligation WHERE binding_digest = ?", binding)
                .sortedBy { it.joinToString("|") }, "$label obligations")
        assertEquals(state.attempts.map { (k, v) -> listOf(k.first.runId, k.first.venueSessionId, k.first.instrumentId, k.first.executionId, k.second.toString(), v.name) }.sortedBy { it.joinToString("|") },
            rows(ds, "SELECT run_id, venue_session_id, instrument_id, execution_id, attempt, disposition FROM boundary.calcify_finance_attempt WHERE binding_digest = ?", binding).sortedBy { it.joinToString("|") }, "$label attempts")
        assertEquals(listOf(listOf(state.sequence.toString(), state.chain)), rows(ds, "SELECT last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?", binding), "$label checkpoint")
        assertEquals(state.sequence.toString(), rows(ds, "SELECT count(*) FROM boundary.calcify_finance_decision WHERE binding_digest = ?", binding).single().single(), "$label decision rows")
    }

    @Test fun projectionEqualsTheLedgerStateUnderRandomBatchingAndRedelivery() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        for (seed in 1L..40L) {
            val (records, state) = run(seed)
            assertTrue(records.size > 5, "seed=$seed produced no work")
            val binding = digest(); projector.open(binding, 1)
            val rnd = Random(seed); var i = 0
            while (i < records.size) {
                val n = 1 + rnd.nextInt(7); val batch = records.subList(i, minOf(records.size, i + n))
                projector.apply(binding, 1, batch)
                if (rnd.nextInt(3) == 0) assertEquals(0, projector.apply(binding, 1, batch), "seed=$seed redelivery after commit applies nothing")
                i += n
            }
            assertProjectionEquals(ds, binding, state, "seed=$seed")
        }
    }

    @Test fun staleEpochIsRefusedAndWritesNothing() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val (records, _) = run(3); val binding = digest()
        projector.open(binding, 2)
        assertFailsWith<StaleProjectionEpoch> { projector.apply(binding, 1, records.take(3)) }
        assertEquals("0", rows(ds, "SELECT count(*) FROM boundary.calcify_finance_decision WHERE binding_digest = ?", binding).single().single())
        assertEquals(3, projector.apply(binding, 3, records.take(3)))                          // a newer owner advances the fence
        assertFailsWith<StaleProjectionEpoch> { projector.apply(binding, 2, records.drop(3).take(2)) }
    }

    @Test fun aBadBatchRollsBackCompletelyAndLeavesTheCheckpointUntouched() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val (records, _) = run(5); val binding = digest(); projector.open(binding, 1)
        projector.apply(binding, 1, records.take(2))
        val before = rows(ds, "SELECT last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?", binding)
        assertFailsWith<IllegalArgumentException> { projector.apply(binding, 1, listOf(records[2], records[4])) }       // gap after a valid record
        assertFailsWith<IllegalArgumentException> { projector.apply(binding, 1, listOf(records[2], records[3].first to records[2].second)) }
        assertFailsWith<IllegalArgumentException> { projector.apply(binding, 1, listOf(records[1], records[0])) }                        // out of order  // wrong input
        val tampered = records[2].first.copy(checksum = "0".repeat(64))
        assertFailsWith<IllegalArgumentException> { projector.apply(binding, 1, listOf(tampered to records[2].second)) }
        val replayedDifferent = records[1].first.copy(checksum = "1".repeat(64))
        assertFailsWith<IllegalArgumentException> { projector.apply(binding, 1, listOf(replayedDifferent to records[1].second)) }
        assertEquals(before, rows(ds, "SELECT last_sequence, last_chain FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?", binding))
        assertEquals("2", rows(ds, "SELECT count(*) FROM boundary.calcify_finance_decision WHERE binding_digest = ?", binding).single().single())
        assertEquals(records.size - 2, projector.apply(binding, 1, records.drop(2)))                                  // the good suffix still applies
    }

    @Test fun databaseConstraintsRefuseWritesTheProjectorShouldNeverMake() {
        val ds = dataSource(); val binding = digest(); FinanceProjector(ledger, ds).open(binding, 1)
        ds.connection.use { c ->
            fun bad(sql: String) = assertFailsWith<java.sql.SQLException>(sql) { c.createStatement().use { it.execute(sql.replace("BINDING", binding)) } }
            bad("INSERT INTO boundary.calcify_finance_obligation VALUES ('BINDING','r','s','i','e','b','s',0,1,'PENDING',0,0)")        // quantity must be positive
            bad("INSERT INTO boundary.calcify_finance_obligation VALUES ('BINDING','r','s','i','e','b','s',1,1,'SETTLED',5,0)")        // settled residuals must be zero
            bad("INSERT INTO boundary.calcify_finance_attempt VALUES ('BINDING','r','s','i','e',1,'SETTLED')")                          // attempt without obligation
            bad("INSERT INTO boundary.calcify_finance_position VALUES ('" + "0".repeat(64) + "','a','USD',1)")                          // unknown binding
        }
    }

    @Test fun runnerRefusesAProjectionThatIsAheadOfOrForkedFromTheDecisionLog() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val (records, _) = run(11); val (fork, _) = run(12)
        assertTrue(records.size >= 4 && fork.size >= 4 && records[1].first.checksum != fork[1].first.checksum)
        val binding = digest(); projector.open(binding, 1)
        fun runner(log: List<Pair<Decision, FinanceInput>>) = FinanceProjectionRunner({ log }, projector, binding, 1)
        assertEquals(records.size, runner(records).runOnce())
        assertEquals(0, runner(records).runOnce(), "a log equal to the projection is a clean no-op")
        assertFailsWith<FinanceProjectionDiverged>("projection ahead of a truncated log") { runner(records.take(records.size - 2)).runOnce() }
        assertFailsWith<FinanceProjectionDiverged>("projection on a different chain than the log") { runner(fork).runOnce() }
        assertFailsWith<FinanceProjectionDiverged>("a log with a gap") { runner(records.filterIndexed { i, _ -> i != 1 }).runOnce() }
        val unopened = digest()
        assertFailsWith<FinanceProjectionDiverged>("never opened") { FinanceProjectionRunner({ records }, projector, unopened, 1).runOnce() }
    }

    /** A log that hands out only records it has not handed out before, `chunk` at a time, and counts what it delivered. */
    private class TailSource(private val log: List<Pair<Decision, FinanceInput>>, private val chunk: Int) {
        var delivered = 0; private set
        var calls = 0; private set
        val next: () -> List<Pair<Decision, FinanceInput>> = { calls++; val out = log.drop(delivered).take(chunk); delivered += out.size; out }
    }

    @Test fun aTailingRunnerProjectsEachRecordOnceFromChunksAndEqualsTheLedger() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val (records, state) = run(21, steps = 160); assertTrue(records.size >= 15)
        val binding = digest(); projector.open(binding, 1)
        val source = TailSource(records, chunk = 7)
        val runner = FinanceProjectionRunner.tailing(source.next, projector, binding, 1, maxApply = 5)
        assertEquals(records.size, runner.runOnce(), "the first pass streams the whole log and applies it")
        assertEquals(records.size, source.delivered, "every record was read exactly once")
        assertProjectionEquals(ds, binding, state, "tailing")
        val callsAfterFirstPass = source.calls
        assertEquals(0, runner.runOnce(), "a caught-up pass is a no-op"); assertEquals(callsAfterFirstPass + 1, source.calls, "and costs one empty read, not a re-read of the log")
        assertEquals(records.size, source.delivered)
    }

    @Test fun aTailingRunnerAppliesOnlyWhatIsNewAfterARestartAndAfterMoreDecisions() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val (records, state) = run(22, steps = 100); val half = records.size / 2; assertTrue(half >= 10)
        val binding = digest(); projector.open(binding, 1)
        assertEquals(half, FinanceProjectionRunner.tailing(TailSource(records.take(half), 9).next, projector, binding, 1).runOnce())
        val restarted = TailSource(records, 9)                       // a new process reads the log from the start again
        val runner = FinanceProjectionRunner.tailing(restarted.next, projector, binding, 1)
        assertEquals(records.size - half, runner.runOnce(), "only the records after the stored progress are applied")
        assertProjectionEquals(ds, binding, state, "restarted tailing")
    }

    @Test fun aTailingRunnerRefusesAProjectionAheadOfOrForkedFromTheLogAndALogWithAGap() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val (records, _) = run(11); val (fork, _) = run(12)
        assertTrue(records.size >= 4 && fork.size >= 4 && records[1].first.checksum != fork[1].first.checksum)
        val binding = digest(); projector.open(binding, 1)
        fun runner(log: List<Pair<Decision, FinanceInput>>) = FinanceProjectionRunner.tailing(TailSource(log, 5).next, projector, binding, 1)
        assertEquals(records.size, runner(records).runOnce())
        assertEquals(0, runner(records).runOnce(), "a log equal to the projection is a clean no-op")
        assertFailsWith<FinanceProjectionDiverged>("projection ahead of a truncated log") { runner(records.take(records.size - 2)).runOnce() }
        assertFailsWith<FinanceProjectionDiverged>("projection on a different chain than the log") { runner(fork).runOnce() }
        assertFailsWith<FinanceProjectionDiverged>("a log with a gap") { runner(records.filterIndexed { i, _ -> i != 1 }).runOnce() }
        assertFailsWith<FinanceProjectionDiverged>("never opened") { FinanceProjectionRunner.tailing(TailSource(records, 5).next, projector, digest(), 1).runOnce() }
    }

    @Test fun aTailingRunnerKeepsRecordsItReadButCouldNotWriteAndAppliesThemOnTheNextPass() {
        val real = dataSource(); var failNextWrite = false
        val flaky = object : javax.sql.DataSource by real {
            override fun getConnection(): java.sql.Connection = if (failNextWrite) { failNextWrite = false; throw java.sql.SQLException("injected transient failure") } else real.connection
        }
        val projector = FinanceProjector(ledger, flaky)
        val (records, state) = run(23, steps = 120); assertTrue(records.size >= 10)
        val binding = digest(); projector.open(binding, 1)
        val source = TailSource(records, chunk = 1_000)
        // The pass reads the checkpoint (a connection), then reads the log, then writes (another connection): fail that write.
        val reading = source.next; var armed = false
        val armedRunner = FinanceProjectionRunner.tailing({ val r = reading(); if (!armed) { armed = true; failNextWrite = true }; r }, projector, binding, 1)
        assertFailsWith<java.sql.SQLException> { armedRunner.runOnce() }
        assertEquals(records.size, source.delivered, "the records were consumed from the source")
        assertEquals(records.size, armedRunner.runOnce(), "and are applied by the next pass although the source has nothing new")
        assertProjectionEquals(real, binding, state, "after a transient failure")
    }

    private fun hex64() = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)
    private fun insertRawDecisions(ds: PGSimpleDataSource, binding: String, from: Int, to: Int) = ds.connection.use { c ->
        c.prepareStatement("INSERT INTO boundary.calcify_finance_decision(binding_digest, sequence, action_namespace, action_domain, action_id, disposition, input_digest, prior_chain, checksum) VALUES (?, ?, 'n', 'd', ?, 'FUNDED', ?, ?, ?)").use { ps ->
            for (i in from..to) { ps.setString(1, binding); ps.setLong(2, i.toLong()); ps.setString(3, "raw-$i"); ps.setString(4, hex64()); ps.setString(5, hex64()); ps.setString(6, hex64()); ps.addBatch() }
            ps.executeBatch()
        }
    }
    private fun sqlState(block: () -> Unit): String? = try { block(); null } catch (e: java.sql.SQLException) { e.sqlState } catch (e: java.sql.BatchUpdateException) { e.sqlState }

    @Test fun aBindingKeepsTheSequenceBoundItWasCreatedWithAndTheDatabaseEnforcesIt() {
        val ds = dataSource(); val projector = FinanceProjector(ledger, ds)
        val finite = digest(); projector.open(finite, 1)
        insertRawDecisions(ds, finite, 1, 320)
        assertEquals("23514", sqlState { insertRawDecisions(ds, finite, 321, 321) }, "a finite binding refuses row 321 as migration 0014 did")
        val capacity = digest(); projector.open(capacity, 1, sequenceBound = 400)
        insertRawDecisions(ds, capacity, 1, 400)
        assertEquals("23514", sqlState { insertRawDecisions(ds, capacity, 401, 401) }, "and a capacity binding refuses its own bound plus one")
        ds.connection.use { c ->
            fun update(sql: String, binding: String) = sqlState { c.prepareStatement(sql).use { ps -> ps.setString(1, binding); ps.executeUpdate() } }
            assertEquals("23514", update("UPDATE boundary.calcify_finance_checkpoint SET last_sequence = 401 WHERE binding_digest = ?", capacity), "the checkpoint cannot pass the bound")
            assertEquals("23514", update("UPDATE boundary.calcify_finance_checkpoint SET sequence_bound = 1000 WHERE binding_digest = ?", capacity), "the bound cannot be changed afterwards")
            assertEquals("23514", update("UPDATE boundary.calcify_finance_checkpoint SET sequence_bound = 320 WHERE binding_digest = ?", capacity), "not even lowered")
        }
        assertFailsWith<IllegalStateException>("an existing binding reopened with a different bound") { projector.open(capacity, 1, sequenceBound = 320) }
        assertFailsWith<IllegalStateException>("a claim that names a different bound") { projector.claim(capacity, sequenceBound = 500) }
        assertEquals(2L, projector.claim(capacity, sequenceBound = 400), "a claim that names the stored bound raises the epoch as before")
        assertFailsWith<IllegalArgumentException>("a bound below the finite one") { projector.open(digest(), 1, sequenceBound = 100) }
        assertFailsWith<IllegalArgumentException>("a bound above the ceiling") { projector.open(digest(), 1, sequenceBound = 2_000_000_000L) }
    }

    @Test fun decisionDigestsKeepALengthGuardAfterTheHexChecksWereReplaced() {
        val ds = dataSource(); val binding = digest(); FinanceProjector(ledger, ds).open(binding, 1)
        fun insert(input: String, prior: String, checksum: String, action: String, sequence: Long = 1) = sqlState { ds.connection.use { c ->
            c.prepareStatement("INSERT INTO boundary.calcify_finance_decision(binding_digest, sequence, action_namespace, action_domain, action_id, disposition, input_digest, prior_chain, checksum) VALUES (?, ?, 'n', 'd', ?, 'FUNDED', ?, ?, ?)").use { ps ->
                ps.setString(1, binding); ps.setLong(2, sequence); ps.setString(3, action); ps.setString(4, input); ps.setString(5, prior); ps.setString(6, checksum); ps.executeUpdate()
            }
        } }
        assertEquals("23514", insert(hex64().drop(1), hex64(), hex64(), "a1"), "a short input digest")
        assertEquals("23514", insert(hex64(), hex64() + "0", hex64(), "a2"), "a long prior chain")
        assertEquals("23514", insert(hex64(), hex64(), "", "a3"), "an empty checksum")
        assertEquals(null, insert(hex64(), hex64(), hex64(), "a4"), "sixty-four characters pass")
        assertEquals(null, insert("z".repeat(64), hex64(), hex64(), "a5", sequence = 2), "hex spelling is no longer a database concern (migration 0017): the projector's in-memory verification establishes it, the database keeps the length")
    }

    /** The projector is the only writer of the projection and must hold nothing else (docs/work/CALCIFY_FINITE_P3_DB_ROLES.md, O4 projection role). */
    @Test fun theProjectorRoleCannotDeleteTruncateOrTouchTheIngressTablesAndFunctions() {
        dataSource().connection.use { c ->
            for (sql in listOf("DELETE FROM boundary.calcify_finance_decision", "TRUNCATE boundary.calcify_finance_obligation", "DELETE FROM boundary.calcify_finance_position")) {
                val e = assertFailsWith<SQLException>(sql) { c.createStatement().use { it.execute(sql) } }
                assertEquals("42501", e.sqlState, "expected insufficient_privilege for $sql: ${e.message}")
            }
            for (table in listOf("calcify_finite_bindings", "calcify_finite_runs", "calcify_finite_attempts", "calcify_finite_publications")) {
                val e = assertFailsWith<SQLException>(table) { c.createStatement().use { it.executeQuery("SELECT 1 FROM boundary.$table").close() } }
                assertEquals("42501", e.sqlState, "the projector must not read $table")
            }
            val canExecute = c.createStatement().use { st ->
                st.executeQuery("SELECT p.proname, has_function_privilege(current_user, p.oid, 'EXECUTE') FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                    "WHERE n.nspname = 'boundary' AND p.proname LIKE 'calcify_finite_%'").use { rs -> buildList { while (rs.next()) if (rs.getBoolean(2)) add(rs.getString(1)) } }
            }
            assertTrue(canExecute.isEmpty(), "the projector holds EXECUTE on finite registrar/runtime functions: $canExecute")
        }
    }

    /** Owner-epoch design option B: the process claims the epoch; an older claimant is fenced on its next write. */
    @Test fun claimingRaisesTheOwnerEpochAndFencesTheOlderClaimant() {
        val projector = FinanceProjector(ledger, dataSource()); val binding = digest()
        assertEquals(null, projector.checkpoint(binding))
        assertEquals(1L, projector.claim(binding), "the first claim creates the checkpoint at genesis with epoch 1")
        assertEquals(0L to ledger.genesisChain, projector.checkpoint(binding))
        val records = run(7, 60).first
        assertTrue(records.size >= 8, "the schedule yields enough recorded decisions")
        assertEquals(5, projector.apply(binding, 1, records.take(5)), "the first claimant projects five decisions")
        assertEquals(2L, projector.claim(binding), "a later claim raises the epoch by one")
        assertEquals(3L, projector.claim(binding))
        val stale = assertFailsWith<StaleProjectionEpoch>("the instance holding epoch 1 is fenced") { projector.apply(binding, 1, records) }
        assertTrue(stale.message!!.contains("stale"))
        assertEquals(5L, projector.checkpoint(binding)!!.first, "the fenced write changed nothing")
        assertEquals(records.size - 5, projector.apply(binding, 3, records), "the newest claimant projects the rest")
        assertEquals(records.size.toLong(), projector.checkpoint(binding)!!.first)
    }

    @Test fun concurrentClaimsAreSerializedIntoDistinctEpochs() {
        val binding = digest(); val results = java.util.Collections.synchronizedList(ArrayList<Long>())
        // Build the projectors on the test thread: dataSource() skips the test (assumeTrue) when the fixture is absent, and that must not happen inside a worker thread.
        val projectors = (0 until 6).map { FinanceProjector(ledger, dataSource()) }
        val threads = projectors.map { p -> Thread { results += p.claim(binding) }.also { it.start() } }
        threads.forEach { it.join() }
        assertEquals((1L..6L).toList(), results.sorted(), "six simultaneous claims get six distinct, consecutive epochs")
    }
}
