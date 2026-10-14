package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Tag
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import kotlin.test.Test

/**
 * Capacity spike for the finance projection (Decision 0: the owner wants a capacity profile aimed at the arena contract's 10,000 booked trades/s,
 * which is about 20,000 decisions/s: a trade is a capture plus a settle). Research only, not product code: run with `-PbrokerTests -PresearchTests`.
 *
 * The product schema caps one binding at 320 sequences (migration 0014), so a capacity run cannot use it. This spike loads the same migration with
 * the two 320 bounds lifted and writes synthetic rows of the same shape (decision per input, obligation on capture, attempt and obligation update and
 * four position deltas on settle) as one uncapped binding, to find what Postgres 16 in Docker (fsync on, synchronous_commit on, local socket) can
 * sustain per write strategy. It omits the ledger, the hash-link and input-digest checks (microseconds per decision) and the Kafka read side.
 *   A. JDBC batches per statement class with reWriteBatchedInserts (the S0 research variant).
 *   B. One set-based statement per table from unnest() arrays.
 *   C. COPY into session staging tables, then one INSERT ... SELECT per table.
 */
@Tag("broker") @Tag("research")
class S0ProjectorCapacitySpikeTest {
    private fun sh(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }; return out
    }

    private class Batch(val trades: IntRange, val accounts: Int) {
        val seq = ArrayList<Long>(); val ns = ArrayList<String>(); val dom = ArrayList<String>(); val aid = ArrayList<String>(); val disp = ArrayList<String>()
        val digest = ArrayList<String>(); val prior = ArrayList<String>(); val sum = ArrayList<String>()
        val execCapture = ArrayList<String>(); val buyer = ArrayList<String>(); val seller = ArrayList<String>()
        val execSettle = ArrayList<String>()
        val posAccount = ArrayList<String>(); val posAsset = ArrayList<String>(); val posAmount = ArrayList<Long>()
        init {
            val net = LinkedHashMap<Pair<String, String>, Long>()
            fun decision(s: Long, kind: String, id: String, disposition: String) {
                seq += s; ns += "n"; dom += kind; aid += id; disp += disposition
                digest += "%064x".format(s); prior += "%064x".format(s - 1); sum += "%064x".format(s + (1L shl 40))
            }
            for (t in trades) {
                val b = "buyer-${t % accounts}"; val s = "seller-${(t * 7) % accounts}"; val e = "e$t"
                decision(2L * t + 1, "cap", "c$t", "CAPTURED"); execCapture += e; buyer += b; seller += s
                decision(2L * t + 2, "rep", "s$t", "SETTLED"); execSettle += e
                net.merge(b to "USD", -1_000_000_000L, Long::plus); net.merge(s to "USD", 1_000_000_000L, Long::plus)
                net.merge(s to "AAPL", -1L, Long::plus); net.merge(b to "AAPL", 1L, Long::plus)
            }
            for ((k, v) in net) { posAccount += k.first; posAsset += k.second; posAmount += v }
        }
        val lastSeq get() = seq.last(); val lastSum get() = sum.last()
    }

    private val run = "r"; private val ven = "v"; private val inst = "AAPL"

    private fun jdbc(c: Connection, d: String, b: Batch) {
        val dec = c.prepareStatement("INSERT INTO boundary.calcify_finance_decision(binding_digest, sequence, action_namespace, action_domain, action_id, disposition, input_digest, prior_chain, checksum) VALUES (?,?,?,?,?,?,?,?,?)")
        for (i in b.seq.indices) { dec.setString(1, d); dec.setLong(2, b.seq[i]); dec.setString(3, b.ns[i]); dec.setString(4, b.dom[i]); dec.setString(5, b.aid[i]); dec.setString(6, b.disp[i]); dec.setString(7, b.digest[i]); dec.setString(8, b.prior[i]); dec.setString(9, b.sum[i]); dec.addBatch() }
        dec.executeBatch()
        val obl = c.prepareStatement("INSERT INTO boundary.calcify_finance_obligation(binding_digest, run_id, venue_session_id, instrument_id, execution_id, buyer_account_id, seller_account_id, quantity, price_nanos, status, cash_residual, share_residual) VALUES (?,?,?,?,?,?,?,1,1000000000,'PENDING',1000000000,1)")
        for (i in b.execCapture.indices) { obl.setString(1, d); obl.setString(2, run); obl.setString(3, ven); obl.setString(4, inst); obl.setString(5, b.execCapture[i]); obl.setString(6, b.buyer[i]); obl.setString(7, b.seller[i]); obl.addBatch() }
        obl.executeBatch()
        val att = c.prepareStatement("INSERT INTO boundary.calcify_finance_attempt(binding_digest, run_id, venue_session_id, instrument_id, execution_id, attempt, disposition) VALUES (?,?,?,?,?,1,'SETTLED')")
        val upd = c.prepareStatement("UPDATE boundary.calcify_finance_obligation SET status = 'SETTLED', cash_residual = 0, share_residual = 0 WHERE binding_digest = ? AND run_id = ? AND venue_session_id = ? AND instrument_id = ? AND execution_id = ? AND status = 'PENDING'")
        for (e in b.execSettle) { att.setString(1, d); att.setString(2, run); att.setString(3, ven); att.setString(4, inst); att.setString(5, e); att.addBatch(); upd.setString(1, d); upd.setString(2, run); upd.setString(3, ven); upd.setString(4, inst); upd.setString(5, e); upd.addBatch() }
        att.executeBatch(); check(upd.executeBatch().all { it == 1 })
        val pos = c.prepareStatement("INSERT INTO boundary.calcify_finance_position(binding_digest, account_id, asset_id, amount) VALUES (?,?,?,?) ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount")
        for (i in b.posAccount.indices) { pos.setString(1, d); pos.setString(2, b.posAccount[i]); pos.setString(3, b.posAsset[i]); pos.setLong(4, b.posAmount[i]); pos.addBatch() }
        pos.executeBatch()
    }

    private fun arr(c: Connection, type: String, v: List<*>) = c.createArrayOf(type, v.toTypedArray())

    private fun unnest(c: Connection, d: String, b: Batch) {
        c.prepareStatement("INSERT INTO boundary.calcify_finance_decision SELECT ?, t.s, t.n, t.d, t.a, t.p, t.g, t.r, t.k FROM unnest(?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[]) AS t(s,n,d,a,p,g,r,k)").use {
            it.setString(1, d); it.setArray(2, arr(c, "bigint", b.seq)); it.setArray(3, arr(c, "text", b.ns)); it.setArray(4, arr(c, "text", b.dom)); it.setArray(5, arr(c, "text", b.aid))
            it.setArray(6, arr(c, "text", b.disp)); it.setArray(7, arr(c, "text", b.digest)); it.setArray(8, arr(c, "text", b.prior)); it.setArray(9, arr(c, "text", b.sum)); it.executeUpdate()
        }
        c.prepareStatement("INSERT INTO boundary.calcify_finance_obligation SELECT ?, ?, ?, ?, t.e, t.b, t.s, 1, 1000000000, 'PENDING', 1000000000, 1 FROM unnest(?::text[], ?::text[], ?::text[]) AS t(e,b,s)").use {
            it.setString(1, d); it.setString(2, run); it.setString(3, ven); it.setString(4, inst); it.setArray(5, arr(c, "text", b.execCapture)); it.setArray(6, arr(c, "text", b.buyer)); it.setArray(7, arr(c, "text", b.seller)); it.executeUpdate()
        }
        c.prepareStatement("INSERT INTO boundary.calcify_finance_attempt SELECT ?, ?, ?, ?, t.e, 1, 'SETTLED' FROM unnest(?::text[]) AS t(e)").use {
            it.setString(1, d); it.setString(2, run); it.setString(3, ven); it.setString(4, inst); it.setArray(5, arr(c, "text", b.execSettle)); it.executeUpdate()
        }
        val n = c.prepareStatement("UPDATE boundary.calcify_finance_obligation o SET status = 'SETTLED', cash_residual = 0, share_residual = 0 FROM unnest(?::text[]) AS t(e) WHERE o.binding_digest = ? AND o.run_id = ? AND o.venue_session_id = ? AND o.instrument_id = ? AND o.execution_id = t.e AND o.status = 'PENDING'").use {
            it.setArray(1, arr(c, "text", b.execSettle)); it.setString(2, d); it.setString(3, run); it.setString(4, ven); it.setString(5, inst); it.executeUpdate()
        }
        check(n == b.execSettle.size) { "settled $n of ${b.execSettle.size}" }
        c.prepareStatement("INSERT INTO boundary.calcify_finance_position SELECT ?, t.a, t.s, t.m FROM unnest(?::text[], ?::text[], ?::bigint[]) AS t(a,s,m) ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount").use {
            it.setString(1, d); it.setArray(2, arr(c, "text", b.posAccount)); it.setArray(3, arr(c, "text", b.posAsset)); it.setArray(4, arr(c, "bigint", b.posAmount)); it.executeUpdate()
        }
    }

    private fun copy(c: Connection, d: String, b: Batch) {
        val cm = c.unwrap(PGConnection::class.java).copyAPI
        fun load(table: String, ddl: String, rows: Sequence<String>) {
            c.createStatement().use { it.execute("CREATE TEMP TABLE $table ($ddl) ON COMMIT DROP") }
            // COPY text format treats tab, newline and backslash specially; the synthetic ids never contain them, and this makes a future change that adds one fail loudly instead of corrupting rows.
            val lines = rows.toList(); require(lines.none { l -> l.contains('\\') || l.contains('\r') || l.contains('\n') }) { "COPY row contains an escape character" }
            cm.copyIn("COPY $table FROM STDIN", java.io.StringReader(lines.joinToString("") { it + "\n" }))
        }
        load("st_dec", "s bigint, n text, d text, a text, p text, g text, r text, k text",
            b.seq.indices.asSequence().map { listOf(b.seq[it], b.ns[it], b.dom[it], b.aid[it], b.disp[it], b.digest[it], b.prior[it], b.sum[it]).joinToString("\t") })
        load("st_obl", "e text, b text, s text", b.execCapture.indices.asSequence().map { "${b.execCapture[it]}\t${b.buyer[it]}\t${b.seller[it]}" })
        load("st_att", "e text", b.execSettle.asSequence())
        load("st_pos", "a text, s text, m bigint", b.posAccount.indices.asSequence().map { "${b.posAccount[it]}\t${b.posAsset[it]}\t${b.posAmount[it]}" })
        c.prepareStatement("INSERT INTO boundary.calcify_finance_decision SELECT ?, s, n, d, a, p, g, r, k FROM st_dec").use { it.setString(1, d); it.executeUpdate() }
        c.prepareStatement("INSERT INTO boundary.calcify_finance_obligation SELECT ?, ?, ?, ?, e, b, s, 1, 1000000000, 'PENDING', 1000000000, 1 FROM st_obl").use { it.setString(1, d); it.setString(2, run); it.setString(3, ven); it.setString(4, inst); it.executeUpdate() }
        c.prepareStatement("INSERT INTO boundary.calcify_finance_attempt SELECT ?, ?, ?, ?, e, 1, 'SETTLED' FROM st_att").use { it.setString(1, d); it.setString(2, run); it.setString(3, ven); it.setString(4, inst); it.executeUpdate() }
        val n = c.prepareStatement("UPDATE boundary.calcify_finance_obligation o SET status = 'SETTLED', cash_residual = 0, share_residual = 0 FROM st_att t WHERE o.binding_digest = ? AND o.run_id = ? AND o.venue_session_id = ? AND o.instrument_id = ? AND o.execution_id = t.e AND o.status = 'PENDING'").use {
            it.setString(1, d); it.setString(2, run); it.setString(3, ven); it.setString(4, inst); it.executeUpdate()
        }
        check(n == b.execSettle.size) { "settled $n of ${b.execSettle.size}" }
        c.prepareStatement("INSERT INTO boundary.calcify_finance_position SELECT ?, a, s, m FROM st_pos ON CONFLICT (binding_digest, account_id, asset_id) DO UPDATE SET amount = boundary.calcify_finance_position.amount + EXCLUDED.amount").use { it.setString(1, d); it.executeUpdate() }
    }

    @Test
    fun capacityByWriteStrategy() {
        val name = "s0-pgcap-${System.nanoTime().toString(36)}"; val port = java.net.ServerSocket(0).use { it.localPort }
        val original = Files.readString(Path.of(System.getenv("S0_MIGRATION") ?: "../../scripts/dev/db/migrations/boundary/0014_calcify_finance_projection.sql"))
        // Deliberate and the point of the spike: a capacity run cannot fit the product's 320-sequence cap, so the two bounds are lifted. Both must be present, so a migration change cannot make this a silent no-op.
        require(original.contains("BETWEEN 0 AND 320") && original.contains("BETWEEN 1 AND 320")) { "migration 0014 no longer carries the 320 bounds this spike lifts" }
        val migration = original.replace("BETWEEN 0 AND 320", ">= 0").replace("BETWEEN 1 AND 320", ">= 1")
        val totalTrades = (System.getenv("S0_CAP_TRADES") ?: "30000").toInt(); val accounts = (System.getenv("S0_CAP_ACCOUNTS") ?: "1000").toInt()
        sh("docker", "run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=s0", "-e", "POSTGRES_DB=reef", "-p", "$port:5432", "postgres:16-alpine", *(System.getenv("S0_CAP_PG_ARGS") ?: "").split(" ").filter { it.isNotBlank() }.toTypedArray())
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (true) { try { sh("docker", "exec", name, "pg_isready", "-U", "postgres", "-d", "reef"); Thread.sleep(1500); sh("docker", "exec", name, "psql", "-U", "postgres", "-d", "reef", "-c", "select 1"); break } catch (e: Exception) { check(System.nanoTime() < deadline) { "postgres not ready" }; Thread.sleep(500) } }
            sh("docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = "CREATE SCHEMA boundary;\n$migration")
            // Diagnostic only: S0_CAP_DDL removes cost sources to find the floor. lean = all foreign keys and the decision action UNIQUE; nobindingfk = the four foreign keys
            // to the checkpoint; noattemptfk = the attempt-to-obligation key; nounique = the decision action UNIQUE.
            val ddlMode = System.getenv("S0_CAP_DDL") ?: ""
            val drop = when (ddlMode) {
                "lean" -> "contype = 'f' OR (contype = 'u' AND conrelid = 'boundary.calcify_finance_decision'::regclass)"
                "nobindingfk" -> "contype = 'f' AND confrelid = 'boundary.calcify_finance_checkpoint'::regclass"
                "noattemptfk" -> "contype = 'f' AND confrelid = 'boundary.calcify_finance_obligation'::regclass"
                "nounique" -> "contype = 'u' AND conrelid = 'boundary.calcify_finance_decision'::regclass"
                else -> null
            }
            if (drop != null) sh("docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", "reef", "-v", "ON_ERROR_STOP=1", stdin = """
                DO ${'$'}${'$'} DECLARE r record; BEGIN
                  FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint WHERE connamespace = 'boundary'::regnamespace AND ($drop) LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname);
                  END LOOP;
                END ${'$'}${'$'};""")
            val ds = PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:$port/reef?reWriteBatchedInserts=true"); user = "postgres"; password = "s0" }
            val log = StringBuilder("\nS0 projector capacity ($totalTrades trades = ${2 * totalTrades} decisions per run, $accounts accounts, uncapped binding, Postgres 16 in Docker, ${System.getenv("S0_CAP_TAG") ?: "default"})\n")
            val wanted = (System.getenv("S0_CAP_VARIANTS") ?: "ABC").toSet()
            val variants = listOf<Pair<String, (Connection, String, Batch) -> Unit>>("A.jdbc-batch" to ::jdbc, "B.unnest" to ::unnest, "C.copy-staging" to ::copy).filter { it.first[0] in wanted }
            val tag = (System.getenv("S0_CAP_TAG") ?: "default")
            for (decisionsPerCall in (System.getenv("S0_CAP_CALLS") ?: "120,1000,5000").split(",").map { it.toInt() }) for ((label, write) in variants) {
                val digest = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").take(64)
                ds.connection.use { c -> c.prepareStatement("INSERT INTO boundary.calcify_finance_checkpoint(binding_digest, owner_epoch, last_sequence, last_chain) VALUES (?, 1, 0, ?)").use { it.setString(1, digest); it.setString(2, "0".repeat(64)); it.executeUpdate() } }
                val perCall = decisionsPerCall / 2; val latencies = ArrayList<Long>(); val t0 = System.nanoTime(); var t = 0; var lastQuarterStart = 0L; var lastQuarterTrades = 0
                while (t < totalTrades) {
                    val b = Batch(t until minOf(t + perCall, totalTrades), accounts)
                    if (lastQuarterStart == 0L && t >= totalTrades * 3 / 4) lastQuarterStart = System.nanoTime()
                    val s = System.nanoTime()
                    ds.connection.use { c ->
                        c.autoCommit = false
                        c.prepareStatement("SELECT last_sequence FROM boundary.calcify_finance_checkpoint WHERE binding_digest = ? FOR UPDATE").use { ps -> ps.setString(1, digest); ps.executeQuery().use { r -> check(r.next()); check(r.getLong(1) == 2L * t) } }
                        write(c, digest, b)
                        c.prepareStatement("UPDATE boundary.calcify_finance_checkpoint SET last_sequence = ?, last_chain = ? WHERE binding_digest = ?").use { it.setLong(1, b.lastSeq); it.setString(2, b.lastSum); it.setString(3, digest); it.executeUpdate() }
                        c.commit()
                    }
                    latencies += System.nanoTime() - s; if (lastQuarterStart != 0L) lastQuarterTrades += b.trades.count(); t = b.trades.last + 1
                }
                val secs = (System.nanoTime() - t0) / 1e9; val rate = 2 * totalTrades / secs; val dbRate = 2 * totalTrades / (latencies.sum() / 1e9)
                val quarter = if (lastQuarterStart != 0L) 2 * lastQuarterTrades / ((System.nanoTime() - lastQuarterStart) / 1e9) else 0.0
                latencies.sort()
                val key = "projectorCapacity.$tag.$label.perCall$decisionsPerCall"
                S0Metrics.record("$key.decisionsPerSec", Math.round(rate)); S0Metrics.record("$key.dbOnlyDecisionsPerSec", Math.round(dbRate)); S0Metrics.record("$key.lastQuarterDecisionsPerSec", Math.round(quarter))
                log.append("%-16s perCall=%-5d %7.0f decisions/s overall, %7.0f db-only (excludes building the rows), %7.0f in the last quarter, call p50 %.1f ms p99 %.1f ms\n".format(label, decisionsPerCall, rate, dbRate, quarter, latencies[latencies.size / 2] / 1e6, latencies[(latencies.size * 99 / 100).coerceAtMost(latencies.size - 1)] / 1e6))
                check(ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM boundary.calcify_finance_decision WHERE binding_digest = '$digest'").use { r -> r.next(); r.getLong(1) } } } == 2L * totalTrades)
            }
            println(log); Files.writeString(S0Metrics.file("s0-projector-capacity.txt"), log)
        } finally { ProcessBuilder("docker", "rm", "-f", "-v", name).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }
    }
}
