package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Research: how fast the PRODUCT [FinanceProjector] applies decisions to Postgres, at several batch sizes, on a schema with the finite profile's 320-sequence
 * bound lifted (as the capacity spikes do; the product migration carries the bound). Decisions come from the real [FinanceLedger]; the projection is checked
 * against the ledger after each run. Run with `-PbrokerTests -PresearchTests`; starts and removes its own Postgres 16 container.
 *   PROJ_BATCHES   comma-separated batch sizes (default 1,10,120)       PROJ_BINDINGS  bindings per batch size (default 40)       PROJ_TRADES  trades per binding (default 100; the ledger admits at most 128 captures and 8 controls per run)
 *   PROJ_PG        "keys" (product constraints, default) or "nobindingfk" (without the four foreign keys to the checkpoint)
 */
@Tag("broker") @Tag("research")
class FinanceProjectorThroughputSpikeTest {
    private val buyer = AccountId("buyer-account"); private val seller = AccountId("seller-account")
    private val usd = AssetId("USD"); private val aapl = AssetId("fixture-base-AAPL")
    private val ledger = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("opening")))

    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }; return out
    }

    /** One binding's decisions: funding, then `trades` captures each followed by its settle. */
    private fun decisions(trades: Int): Pair<List<Pair<Decision, FinanceInput>>, FinanceState> {
        var state = ledger.genesis(); val out = ArrayList<Pair<Decision, FinanceInput>>()
        fun step(input: FinanceInput) { val o = ledger.decide(state, input); state = o.state; out += checkNotNull(o.decision) to input }
        repeat(6) { step(FinanceInput.Fund(ActionKey("n", "ctl", "fu$it"), Authority.OPERATOR, buyer, usd, 20_000_000_000L)) }
        step(FinanceInput.Fund(ActionKey("n", "ctl", "fa"), Authority.OPERATOR, seller, aapl, trades.toLong() + 10))
        for (t in 0 until trades) {
            val key = ExecutionKey("p3-run", "p3-session", "AAPL", "e$t")
            step(FinanceInput.Capture(ActionKey("n", "cap", "c$t"), key, buyer, seller, 1L, 1_000_000_000L))
            step(FinanceInput.Settle(ActionKey("n", "rep", "s$t"), Authority.OPERATOR, key, 1))
        }
        return out to state
    }

    @Test fun productProjectorThroughput() {
        val batches = (System.getenv("PROJ_BATCHES") ?: "1,10,120").split(",").map { it.trim().toInt() }
        val bindingsPer = (System.getenv("PROJ_BINDINGS") ?: "40").toInt(); val trades = (System.getenv("PROJ_TRADES") ?: "100").toInt(); val pgMode = System.getenv("PROJ_PG") ?: "keys"
        val original = Files.readString(Path.of(System.getenv("S0_MIGRATION") ?: "../../scripts/dev/db/migrations/boundary/0014_calcify_finance_projection.sql"))
        require(original.contains("BETWEEN 0 AND 320") && original.contains("BETWEEN 1 AND 320")) { "migration 0014 no longer carries the 320 bounds this spike lifts" }
        val migration = original.replace("BETWEEN 0 AND 320", ">= 0").replace("BETWEEN 1 AND 320", ">= 1")
        val pgName = "proj-pg-${System.nanoTime().toString(36)}"; val pgPort = java.net.ServerSocket(0).use { it.localPort }
        sh("docker", "run", "-d", "--rm", "--name", pgName, "-e", "POSTGRES_PASSWORD=s0", "-e", "POSTGRES_DB=reef", "-p", "$pgPort:5432", "postgres:16-alpine")
        val log = StringBuilder()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", pgName, "pg_isready", "-U", "postgres", "-d", "reef"); Thread.sleep(1500); sh("docker", "exec", pgName, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(500) } }
            sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migration")
            if (pgMode == "nobindingfk") sh("docker", "exec", "-i", pgName, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = """
                DO ${'$'}${'$'} DECLARE r record; BEGIN
                  FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint WHERE connamespace = 'boundary'::regnamespace AND contype = 'f' AND confrelid = 'boundary.calcify_finance_checkpoint'::regclass LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname);
                  END LOOP;
                END ${'$'}${'$'};""")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$pgPort/reef"); user = "postgres"; password = "s0" }
            val (decisionList, finalState) = decisions(trades)
            val perBinding = decisionList.size
            log.append("projector throughput: product FinanceProjector, schema ${if (pgMode == "keys") "with product constraints (320 bound lifted)" else "without the four binding foreign keys"}, $bindingsPer bindings x $perBinding decisions per batch size\n")
            val projector = FinanceProjector(ledger, ds)
            val warm = UUID.randomUUID().toString().replace("-", "").padEnd(64, '0').take(64); projector.open(warm, 1); decisionList.chunked(120).forEach { projector.apply(warm, 1, it) }
            for (batch in batches) {
                val digests = (0 until bindingsPer).map { (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64) }
                digests.forEach { projector.open(it, 1) }
                val t0 = System.nanoTime()
                for (d in digests) decisionList.chunked(batch).forEach { projector.apply(d, 1, it) }
                val secs = (System.nanoTime() - t0) / 1e9; val total = bindingsPer.toLong() * perBinding
                S0Metrics.record("projectorProduct.$pgMode.batch$batch.decisionsPerSec", Math.round(total / secs))
                log.append("  batch %d: %.0f decisions/s (%d decisions in %.1f s)\n".format(batch, total / secs, total, secs))
                ds.connection.use { c ->
                    val last = digests.last()
                    val positions = HashMap<Pair<String, String>, Long>(); c.prepareStatement("SELECT account_id, asset_id, amount FROM boundary.calcify_finance_position WHERE binding_digest = ?").use { ps -> ps.setString(1, last); ps.executeQuery().use { r -> while (r.next()) positions[r.getString(1) to r.getString(2)] = r.getLong(3) } }
                    assertEquals(finalState.positions.filterValues { it != 0L }.mapKeys { it.key.first.value to it.key.second.value }, positions.filterValues { it != 0L }, "projected positions equal the ledger's")
                    assertEquals(perBinding.toLong(), c.prepareStatement("SELECT last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ?").use { ps -> ps.setString(1, last); ps.executeQuery().use { r -> r.next(); r.getLong(1) } })
                }
            }
            println(log); Files.writeString(S0Metrics.file("projector-product-throughput.txt"), log)
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", pgName).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }
}
