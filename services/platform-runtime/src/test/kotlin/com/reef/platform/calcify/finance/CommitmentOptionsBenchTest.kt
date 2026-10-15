package com.reef.platform.calcify.finance

import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag

/**
 * Research harness for docs/work/CALCIFY_STATE_COMMITMENT_INVESTIGATION.md: the state commitment alone, isolated from the rest of the lane.
 *
 * A real [CapacityLedger] runs the lane-cost workload (fund every account, then captured trades = capture + instant settle) once and the entity writes of
 * every recorded decision are kept. Each commitment engine then replays the same writes: an untimed prefix builds the state (and warms the JIT), the timed
 * part is measured with the processing thread's CPU time and allocated bytes (immune to other processes on the machine), engines interleaved, several
 * repeats. Engines that claim to be contract-neutral are checked against the ledger's own `stateDigest` for every decision; contract-changing ones are
 * prototypes and compute a different digest by design.
 *
 * Research only (-PresearchTests). Env: CAP_PRE (trades before the timed part, default 150000), CAP_MEAS (timed trades, default 40000), CAP_REPS (3),
 * CAP_ACCOUNTS (2000), CAP_INSTRUMENTS (8), CAP_ENGINES (comma list of engine names, default all).
 */
@Tag("research")
class CommitmentOptionsBenchTest {
    private fun env(name: String, default: Long) = System.getenv(name)?.toLong() ?: default

    internal class Rec(val sequence: Long, val controls: Int, val writes: List<StateCommitment.Entity>, val stateDigest: String)

    private val usd = AssetId("USD"); private val opening = AccountId("opening")

    /** The workload: the same shape as CapacityLaneCostTest, driven straight into the ledger. Returns the records and the index where the timed part starts. */
    private var lastLedger: CapacityLedger? = null
    private fun workload(preTrades: Int, measTrades: Int, accountCount: Int, instrumentCount: Int, cash: Long = 1_000_000_000_000L): Pair<List<Rec>, Int> {
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }; val securities = (0 until instrumentCount).map { AssetId("I$it") }
        val ledger = CapacityLedger(CapacityConfig(null, usd, securities.toSet(), opening, 100_000_000, 100_000_000, 100_000_000, 100_000_000))
        val out = ArrayList<Rec>(2 * (preTrades + measTrades) + accountCount * (instrumentCount + 1))
        fun run(input: FinanceInput) { val o = ledger.decide(input); val d = o.decision ?: return; out += Rec(d.sequence, ledger.controls, ArrayList(ledger.lastWrites), d.stateDigest) }
        var action = 0L
        fun key() = ActionKey("calcify-finance", "fund", "a${action++}")
        for (a in accounts) run(FinanceInput.Fund(key(), Authority.OPERATOR, a, usd, cash))
        for (a in accounts) for (s in securities) run(FinanceInput.Fund(key(), Authority.OPERATOR, a, s, 1_000_000L))
        val rnd = Random(7)
        fun trade(i: Int) {
            val s = securities[i % instrumentCount]; val execution = ExecutionKey("run", "session", s.value, "e$i")
            val buyer = accounts[rnd.nextInt(accountCount)]; var seller = accounts[rnd.nextInt(accountCount)]
            if (seller == buyer) seller = accounts[(accounts.indexOf(buyer) + 1) % accountCount]
            run(FinanceInput.Capture(ActionKey("calcify-finance", "capture", "e$i"), execution, buyer, seller, 1L + rnd.nextInt(5), 100L + rnd.nextInt(20)))
            run(FinanceInput.Settle(ActionKey("calcify-finance", "settle", "e$i/1"), Authority.OPERATOR, execution, 1))
        }
        for (i in 0 until preTrades) trade(i)
        val timedFrom = out.size
        for (i in 0 until measTrades) trade(preTrades + i)
        lastLedger = ledger
        return out to timedFrom
    }

    /**
     * Restore and derivability checks for the hybrid prototype (H2), on a small workload where cash is scarce so settlements are refused (attempts recorded as
     * INSUFFICIENT_CASH, obligations left pending): (1) every obligation equals what the executions and attempts imply, so O need not be committed; (2) rebuilding the
     * hybrid commitment from an UNORDERED scan of the final entity set (positions plus immutable entities carrying their creation sequence) gives the same digest
     * the incremental engine computed decision by decision.
     */
    @Test fun hybridObligationsAreDerivedAndRestoreFromAnUnorderedScanEqualsIncremental() {
        val (recs, _) = workload(3_000, 0, 40, 3, cash = 3_000L)
        val ledger = lastLedger!!; val state = ledger.snapshot()
        // (1) derived obligations
        for ((k, o) in state.obligations) {
            val x = state.executions.getValue(k)
            val settled = state.attempts.any { (ak, d) -> ak.first == k && d == Disposition.SETTLED }
            val derived = if (settled) Obligation(ObligationStatus.SETTLED, 0, 0) else Obligation(ObligationStatus.PENDING, x.quantity * x.priceNanos, x.quantity)
            assertEquals(derived, o, "obligation of $k")
        }
        assertEquals(state.executions.keys, state.obligations.keys)
        val refused = state.attempts.values.count { it != Disposition.SETTLED }
        println("BENCH hybrid check: ${state.obligations.size} obligations derived exactly; ${state.attempts.size} attempts of which $refused refused (pending obligations: ${state.obligations.values.count { it.status == ObligationStatus.PENDING }})")
        check(refused > 0 && state.obligations.values.any { it.status == ObligationStatus.SETTLED }) { "the workload must exercise both outcomes" }
        // (2) incremental digest per decision, then a from-scratch rebuild from a shuffled scan
        val engine = Hybrid2Engine(mmr = false, perAccount = false)
        var last = ""
        for (r in recs) last = engine.decision(r)
        val createdAt = HashMap<String, Long>(); val immutable = ArrayList<Pair<Long, StateCommitment.Entity>>(); val positions = HashMap<String, StateCommitment.Entity>()
        for (r in recs) for (e in r.writes) {
            val id = e.tag + java.util.HexFormat.of().formatHex(e.keyBytes)
            when (e.tag) { "P" -> positions[id] = e; "X", "A", "K" -> if (createdAt.putIfAbsent(id, r.sequence) == null) immutable += r.sequence to e }
        }
        val scan = immutable.shuffled(Random(3)); val sha = MessageDigest.getInstance("SHA-256")
        val order = mapOf("X" to 0, "A" to 0, "K" to 1)
        var chain = ByteArray(32)
        for ((seq, group) in scan.groupBy { it.first }.toSortedMap()) {
            val buf = java.io.ByteArrayOutputStream()
            for ((_, e) in group.sortedBy { order.getValue(it.second.tag) }) {
                buf.write(e.tag[0].code); buf.write(java.nio.ByteBuffer.allocate(4).putInt(e.keyBytes.size).array()); buf.write(e.keyBytes)
                buf.write(java.nio.ByteBuffer.allocate(4).putInt(e.valueBytes.size).array()); buf.write(e.valueBytes)
            }
            check(seq >= 1); sha.update(0x05); sha.update(chain); sha.update(buf.toByteArray()); chain = sha.digest()
        }
        val tp = StateCommitment.trieRoot(positions.values.shuffled(Random(4)).map { e -> val k = StateCommitment.keyOf(e); k to StateCommitment.leafOf(e, k) })
        sha.update(0x04); sha.update(tp); sha.update(chain)
        val last0 = recs.last()
        assertEquals(StateCommitment.commitment(last0.sequence, last0.controls, sha.digest()), last, "restore from an unordered scan equals the incremental digest")
        println("BENCH hybrid check: restore from an unordered scan of ${scan.size} immutable entities and ${positions.size} positions equals the incremental digest after ${recs.size} decisions")
    }

    // ---- engines ----

    internal interface Engine {
        val name: String
        /** Applies one decision's entity writes and returns the decision's digest (a different digest for contract-changing engines). */
        fun decision(r: Rec): String
        /** Work counters since creation: branch hashes and other hash calls (chain or leaf-side). */
        val branchHashes: Long
        val neutral: Boolean get() = false
    }

    /** What the product does today, reproduced from the pre-change code (per-call MessageDigest, per-call prefix encode) over the linked [StateTrie]. */
    private object Legacy {
        private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest()
        fun keyOf(e: StateCommitment.Entity): ByteArray = sha(Canon.bytes { str("calcify-finance-key-v2"); str(e.tag) }, e.keyBytes)
        fun leafOf(e: StateCommitment.Entity, key: ByteArray): ByteArray = sha(byteArrayOf(0x00), key, sha(byteArrayOf(0x01), e.valueBytes))
    }

    private class LinkedEngine : Engine {
        override val name = "E0 linked trie, legacy leaf (product before)"
        override val neutral = true
        private val trie = StateTrie()
        override val branchHashes get() = trie.branchHashes
        override fun decision(r: Rec): String {
            for (e in r.writes) { val k = Legacy.keyOf(e); trie.put(k, Legacy.leafOf(e, k)) }
            return StateCommitment.commitment(r.sequence, r.controls, trie.root())
        }
    }

    private class FlatLegacyLeafEngine : Engine {
        override val name = "E1 flat trie, legacy leaf"
        override val neutral = true
        private val trie = FlatStateTrie()
        override val branchHashes get() = trie.branchHashes
        override fun decision(r: Rec): String {
            for (e in r.writes) { val k = Legacy.keyOf(e); trie.put(k, Legacy.leafOf(e, k)) }
            return StateCommitment.commitment(r.sequence, r.controls, trie.root())
        }
    }

    private class LinkedFastLeafEngine : Engine {
        override val name = "E2 linked trie, fast leaf"
        override val neutral = true
        private val trie = StateTrie()
        override val branchHashes get() = trie.branchHashes
        override fun decision(r: Rec): String {
            for (e in r.writes) { val k = StateCommitment.keyOf(e); trie.put(k, StateCommitment.leafOf(e, k)) }
            return StateCommitment.commitment(r.sequence, r.controls, trie.root())
        }
    }

    private class FlatFastLeafEngine : Engine {
        override val name = "E3 flat trie, fast leaf (both neutral changes)"
        override val neutral = true
        private val trie = FlatStateTrie()
        override val branchHashes get() = trie.branchHashes
        override fun decision(r: Rec): String {
            for (e in r.writes) { val k = StateCommitment.keyOf(e); trie.put(k, StateCommitment.leafOf(e, k)) }
            return StateCommitment.commitment(r.sequence, r.controls, trie.root())
        }
    }

    /** Diagnostics: where the neutral engine's CPU goes. [mode] 0 = leaf side only (key and leaf hashes), 1 = leaf side + trie puts (no root), 2 = pure SHA-256 of 67 bytes, 82.5 times per decision from a hot buffer. */
    private class DiagEngine(val mode: Int) : Engine {
        override val name = when (mode) { 0 -> "D0 diagnostic: leaf side only"; 1 -> "D1 diagnostic: leaf side + trie puts, no root"; else -> "D2 diagnostic: 82.5 SHA-256 of 67 bytes from a hot buffer" }
        private val trie = FlatStateTrie()
        private val md = MessageDigest.getInstance("SHA-256")
        private val buf = ByteArray(67)
        private var acc = 0
        private var frac = 0.0
        override val branchHashes get() = 0L
        override fun decision(r: Rec): String {
            if (mode == 2) { frac += 82.5; val n = frac.toInt(); frac -= n; repeat(n) { md.update(buf, 0, 67); buf[3] = md.digest()[0] }; return "" }
            for (e in r.writes) { val k = StateCommitment.keyOf(e); val l = StateCommitment.leafOf(e, k); if (mode == 1) trie.put(k, l) else acc += l[0] }
            return if (acc == 12345) "x" else ""
        }
    }

    /** Lagged: the trie is updated every decision's writes but its root (the only expensive part) is taken every [k] decisions; a decision carries the last boundary's commitment. Contract change (design 5.6). */
    private class LaggedEngine(val k: Int, val batchedPuts: Boolean, val sorted: Boolean = false) : Engine {
        override val name = "L$k lagged root every $k decisions" + if (sorted) " (puts deferred to boundary, last write wins, applied in key order)" else if (batchedPuts) " (puts deferred to boundary, last write wins)" else ""
        private val trie = FlatStateTrie()
        override val branchHashes get() = trie.branchHashes
        private var carried = StateCommitment.commitment(0, 0, trie.root())
        private val pending = LinkedHashMap<java.nio.ByteBuffer, ByteArray>()
        override fun decision(r: Rec): String {
            for (e in r.writes) {
                val key = StateCommitment.keyOf(e); val leaf = StateCommitment.leafOf(e, key)
                if (batchedPuts) pending[java.nio.ByteBuffer.wrap(key)] = leaf else trie.put(key, leaf)
            }
            val out = carried
            if (r.sequence % k == 0L) {
                if (batchedPuts) {
                    val entries = if (sorted) pending.entries.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.key.array(), b.key.array()) } else pending.entries.toList()
                    for ((kk, l) in entries) trie.put(kk.array(), l); pending.clear()
                }
                carried = StateCommitment.commitment(r.sequence, r.controls, trie.root())
            }
            return out
        }
    }

    /** SHA-512/256 for every node and leaf hash (one compression for a 67-byte branch instead of two). Same trie shape. Contract change. */
    private class Sha512Engine(val alg: String) : Engine {
        override val name = if (alg == "SHA-256") "S256 control: same code as S512 with SHA-256 (object trie)" else "S512 $alg branch, key and leaf hashes (object trie)"
        private val md = MessageDigest.getInstance(alg)
        private val trie = FlatTrieX(alg)
        override val branchHashes get() = trie.branchHashes
        private val prefix = HashMap<String, ByteArray>()
        private val zero = byteArrayOf(0); private val one = byteArrayOf(1)
        override fun decision(r: Rec): String {
            for (e in r.writes) {
                val p = prefix.getOrPut(e.tag) { Canon.bytes { str("calcify-finance-key-v2"); str(e.tag) } }
                md.update(p); md.update(e.keyBytes); val key = md.digest()
                md.update(one); md.update(e.valueBytes); val inner = md.digest()
                md.update(zero); md.update(key); md.update(inner); val leaf = md.digest()
                trie.put(key, leaf)
            }
            return StateCommitment.commitment(r.sequence, r.controls, trie.root())
        }
    }

    /**
     * Hybrid: write-once entities (execution X, attempt A, action K) are committed by a running hash over their creation records in write order (one hash per
     * decision, no tree, no per-entity key or leaf hash); obligations O are not committed (derivable from X and A); only positions P live in the trie.
     * T = SHA-256(0x04 || trieRoot(P) || creationChain). Contract change; needs a creation sequence per immutable entity for restore.
     */
    private class HybridEngine(val separateO: Boolean) : Engine {
        override val name = if (separateO) "H1 hybrid: creation chain for X,A,K; trie for P and O" else "H2 hybrid: creation chain for X,A,K; trie for P only (O derived)"
        private val trie = FlatStateTrie()
        override val branchHashes get() = trie.branchHashes
        private val md = MessageDigest.getInstance("SHA-256")
        private var chain = ByteArray(32)
        private var buf = ByteArray(1024)
        private fun put(v: Int, at: Int) { buf[at] = (v ushr 24).toByte(); buf[at + 1] = (v ushr 16).toByte(); buf[at + 2] = (v ushr 8).toByte(); buf[at + 3] = v.toByte() }
        override fun decision(r: Rec): String {
            var n = 0
            for (e in r.writes) {
                val inTrie = e.tag == "P" || (separateO && e.tag == "O")
                if (inTrie) { val k = StateCommitment.keyOf(e); trie.put(k, StateCommitment.leafOf(e, k)); continue }
                if (e.tag == "O") continue
                val need = n + 9 + e.keyBytes.size + e.valueBytes.size
                if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
                buf[n++] = e.tag[0].code.toByte(); put(e.keyBytes.size, n); n += 4; System.arraycopy(e.keyBytes, 0, buf, n, e.keyBytes.size); n += e.keyBytes.size
                put(e.valueBytes.size, n); n += 4; System.arraycopy(e.valueBytes, 0, buf, n, e.valueBytes.size); n += e.valueBytes.size
            }
            if (n > 0) { md.update(0x05); md.update(chain); md.update(buf, 0, n); chain = md.digest() }
            md.update(0x04); md.update(trie.root()); md.update(chain)
            return StateCommitment.commitment(r.sequence, r.controls, md.digest())
        }
    }

    /**
     * An append-only Merkle accumulator over leaf hashes (a Merkle mountain range: perfect subtrees "peaks" of decreasing height, merged as leaves arrive; RFC 9162 /
     * Trillian compact-range style frontier). Appending costs the merges (2 per leaf amortized); the root bags the peaks left to right with the prefix folds cached, so
     * only the folds from the first changed peak are recomputed. Gives O(log n) inclusion proofs by position, which a plain hash chain cannot.
     */
    private class Mmr {
        private val md = MessageDigest.getInstance("SHA-256")
        private val height = IntArray(64); private val peak = Array(64) { ByteArray(32) }
        private val bag = Array(64) { ByteArray(32) }
        private var n = 0; private var bagValid = 0
        var hashCalls = 0L; private set
        private fun node(tag: Int, a: ByteArray, b: ByteArray): ByteArray { md.update(tag.toByte()); md.update(a); md.update(b); hashCalls++; return md.digest() }
        fun append(leaf: ByteArray) {
            System.arraycopy(leaf, 0, peak[n], 0, 32); height[n] = 0; n++
            while (n >= 2 && height[n - 1] == height[n - 2]) { val m = node(1, peak[n - 2], peak[n - 1]); n--; System.arraycopy(m, 0, peak[n - 1], 0, 32); height[n - 1]++ }
            if (bagValid > n - 1) bagValid = n - 1
        }
        fun root(): ByteArray {
            if (n == 0) return ByteArray(32)
            if (bagValid < 1) { System.arraycopy(peak[0], 0, bag[0], 0, 32); bagValid = 1 }
            for (i in bagValid until n) System.arraycopy(node(2, bag[i - 1], peak[i]), 0, bag[i], 0, 32)
            bagValid = n
            return bag[n - 1]
        }
    }

    /**
     * Hybrid commitments, variants of [HybridEngine]: the write-once entities (X, A, K; O is derivable from them) go into an append-only log committed either by a running
     * hash chain ([mmr] false) or an [Mmr]; the positions go into the trie either as one leaf per (account, asset) ([perAccount] false, as today) or as one leaf per
     * account holding all that account's positions sorted by asset ([perAccount] true; a settle then touches 2 leaves instead of 4).
     */
    private class Hybrid2Engine(val mmr: Boolean, val perAccount: Boolean) : Engine {
        override val name = "H" + (if (perAccount) "4" else "2") + (if (mmr) "m" else "c") + " hybrid: " + (if (mmr) "MMR" else "hash chain") + " for X,A,K; trie with " + (if (perAccount) "one leaf per account" else "one leaf per position")
        private val trie = FlatStateTrie()
        private val log = Mmr()
        private val md = MessageDigest.getInstance("SHA-256")
        private var chain = ByteArray(32)
        private var chainCalls = 0L
        override val branchHashes get() = trie.branchHashes + log.hashCalls + chainCalls
        private var buf = ByteArray(1024)
        private fun putInt(v: Int, at: Int) { buf[at] = (v ushr 24).toByte(); buf[at + 1] = (v ushr 16).toByte(); buf[at + 2] = (v ushr 8).toByte(); buf[at + 3] = v.toByte() }
        private val accounts = HashMap<String, java.util.TreeMap<String, Long>>()
        private val dirty = LinkedHashSet<String>()
        private val acctKeyPrefix = Canon.bytes { str("calcify-finance-key-v2"); str("B") }
        private fun text(b: ByteArray, at: Int): Pair<String, Int> { val len = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff); return String(b, at + 4, len, Charsets.ISO_8859_1) to at + 4 + len }
        private fun long(b: ByteArray): Long { var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xff); return v }
        override fun decision(r: Rec): String {
            var n = 0
            for (e in r.writes) {
                if (e.tag == "P") {
                    if (!perAccount) { val k = StateCommitment.keyOf(e); trie.put(k, StateCommitment.leafOf(e, k)) }
                    else { val (acct, next) = text(e.keyBytes, 0); val (asset, _) = text(e.keyBytes, next); accounts.getOrPut(acct) { java.util.TreeMap() }[asset] = long(e.valueBytes); dirty += acct }
                    continue
                }
                if (e.tag == "O") continue
                val need = n + 9 + e.keyBytes.size + e.valueBytes.size
                if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
                buf[n++] = e.tag[0].code.toByte(); putInt(e.keyBytes.size, n); n += 4; System.arraycopy(e.keyBytes, 0, buf, n, e.keyBytes.size); n += e.keyBytes.size
                putInt(e.valueBytes.size, n); n += 4; System.arraycopy(e.valueBytes, 0, buf, n, e.valueBytes.size); n += e.valueBytes.size
            }
            if (perAccount) {
                for (acct in dirty) {
                    val ab = acct.toByteArray(Charsets.ISO_8859_1)
                    md.update(acctKeyPrefix); md.update(ab); val key = md.digest()
                    val positions = accounts.getValue(acct); var m = 0; val out = java.io.ByteArrayOutputStream(positions.size * 24)
                    val tmp = ByteArray(8)
                    for ((asset, amount) in positions) { val abytes = asset.toByteArray(Charsets.ISO_8859_1); out.write(byteArrayOf(0, 0, 0, abytes.size.toByte())); out.write(abytes); for (i in 0 until 8) tmp[i] = (amount ushr (56 - 8 * i)).toByte(); out.write(tmp); m++ }
                    md.update(1); md.update(out.toByteArray()); val inner = md.digest()
                    md.update(0); md.update(key); md.update(inner); trie.put(key, md.digest())
                }
                dirty.clear()
            }
            if (n > 0) {
                if (mmr) { md.update(0); md.update(buf, 0, n); log.append(md.digest()) }
                else { md.update(0x05); md.update(chain); md.update(buf, 0, n); chain = md.digest() }
                chainCalls += if (mmr) 1 else 1
            }
            md.update(0x04); md.update(trie.root()); md.update(if (mmr) log.root() else chain)
            return StateCommitment.commitment(r.sequence, r.controls, md.digest())
        }
    }

    /** [FlatStateTrie] over a configurable 32-byte-output hash (test-local copy for the SHA-512/256 prototype). */
    private class FlatTrieX(algorithm: String) {
        private val md = MessageDigest.getInstance(algorithm)
        private val buf = ByteArray(67).also { it[0] = 2 }
        private class Node(val key: ByteArray?, var digest: ByteArray, val index: Int, var left: Node?, var right: Node?, var stale: Boolean)
        private var root: Node? = null
        var branchHashes = 0L; private set
        private fun bit(key: ByteArray, index: Int): Int = (key[index ushr 3].toInt() ushr (7 - (index and 7))) and 1
        fun put(key: ByteArray, leaf: ByteArray) {
            val start = root; if (start == null) { root = Node(key, leaf, 0, null, null, false); return }
            var closest: Node = start; while (closest.key == null) closest = if (bit(key, closest.index) == 0) closest.left!! else closest.right!!
            var d = -1; val ck = closest.key!!
            for (i in 0 until 32) { val x = (key[i].toInt() xor ck[i].toInt()) and 0xff; if (x != 0) { d = i * 8 + Integer.numberOfLeadingZeros(x) - 24; break } }
            if (d < 0) { var n: Node = start; while (n.key == null) { n.stale = true; n = if (bit(key, n.index) == 0) n.left!! else n.right!! }; closest.digest = leaf; return }
            var parent: Node? = null; var parentLeft = false; var cur: Node = start
            while (cur.key == null && cur.index < d) { cur.stale = true; parent = cur; parentLeft = bit(key, cur.index) == 0; cur = if (parentLeft) cur.left!! else cur.right!! }
            val fresh = Node(key, leaf, 0, null, null, false)
            val branch = if (bit(key, d) == 0) Node(null, ByteArray(32), d, fresh, cur, true) else Node(null, ByteArray(32), d, cur, fresh, true)
            when { parent == null -> root = branch; parentLeft -> parent.left = branch; else -> parent.right = branch }
        }
        fun root(): ByteArray { val r = root ?: return StateCommitment.emptyTrie; clean(r); return r.digest }
        private fun clean(n: Node) {
            if (n.key != null || !n.stale) return
            clean(n.left!!); clean(n.right!!)
            buf[1] = (n.index ushr 8).toByte(); buf[2] = n.index.toByte()
            System.arraycopy(n.left!!.digest, 0, buf, 3, 32); System.arraycopy(n.right!!.digest, 0, buf, 35, 32)
            md.update(buf, 0, 67); n.digest = md.digest(); n.stale = false; branchHashes++
        }
    }

    // ---- measurement ----

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    internal class Result(val name: String, val cpuNsPerDecision: Double, val allocBytesPerDecision: Double, val branchPerDecision: Double, val wallNsPerDecision: Double)

    private fun measure(factory: () -> Engine, recs: List<Rec>, timedFrom: Int, verify: Boolean): Result {
        val engine = factory()
        for (i in 0 until timedFrom) { val d = engine.decision(recs[i]); if (verify && engine.neutral) assertEquals(recs[i].stateDigest, d, "${engine.name} at ${recs[i].sequence}") }
        System.gc()
        val id = Thread.currentThread().id
        val b0 = engine.branchHashes; val cpu0 = threads.getCurrentThreadCpuTime(); val a0 = threads.getThreadAllocatedBytes(id); val w0 = System.nanoTime()
        var sink = 0
        for (i in timedFrom until recs.size) { sink += engine.decision(recs[i]).hashCode() }
        val w1 = System.nanoTime(); val cpu1 = threads.getCurrentThreadCpuTime(); val a1 = threads.getThreadAllocatedBytes(id)
        if (sink == 42) println("") // keep the loop alive
        val n = (recs.size - timedFrom).toDouble()
        return Result(engine.name, (cpu1 - cpu0) / n, (a1 - a0) / n, (engine.branchHashes - b0) / n, (w1 - w0) / n)
    }

    /**
     * What a homomorphic multiset hash costs per element on this JVM (the per-entity work of AdHash, LtHash and MuHash, before any bookkeeping): expanding an
     * element to its group element, and adding or multiplying it into the accumulator. One element is touched per entity write, plus one more to remove the old
     * value of an updated entity. Compare with the roughly 4 to 5 microseconds a trie spends per entity write today.
     */
    @Test fun homomorphicElementCost() {
        val sha = MessageDigest.getInstance("SHA-256")
        val rounds = env("CAP_PROBE_ELEMENTS", 100_000).toInt()
        fun cpuUs(block: (Int) -> Unit): Double { repeat(rounds / 5) { block(it) }; val c0 = threads.currentThreadCpuTime; for (i in 0 until rounds) block(i); return (threads.currentThreadCpuTime - c0) / 1000.0 / rounds }
        val element = ByteArray(100) { it.toByte() }
        var sink = 0L
        // LtHash16 geometry (Facebook, Lewi et al. 2019): 1024 lanes of 16 bits = 2048 bytes. Expansion here by SHA-256 in counter mode (64 blocks).
        val lanes = ShortArray(1024); val out2k = ByteArray(2048)
        val ltSha = cpuUs { i ->
            element[0] = i.toByte(); element[1] = (i shr 8).toByte(); element[2] = (i shr 16).toByte()
            for (c in 0 until 64) { sha.update(element); sha.update(c.toByte()); sha.digest(out2k, c * 32, 32) }
            for (l in 0 until 1024) lanes[l] = (lanes[l] + (((out2k[2 * l].toInt() and 0xff) shl 8) or (out2k[2 * l + 1].toInt() and 0xff))).toShort()
            sink += lanes[3]
        }
        // The same expansion by ChaCha20 (JDK intrinsic) keyed with SHA-256 of the element.
        val chacha = javax.crypto.Cipher.getInstance("ChaCha20"); val zeros = ByteArray(2048); val nonce = ByteArray(12)
        val ltChaCha = cpuUs { i ->
            element[0] = i.toByte(); element[1] = (i shr 8).toByte(); element[2] = (i shr 16).toByte()
            val key = sha.digest(element); nonce[0] = i.toByte(); nonce[1] = (i shr 8).toByte(); nonce[2] = (i shr 16).toByte(); nonce[3] = (i shr 24).toByte()
            chacha.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.ChaCha20ParameterSpec(nonce, 1))
            val stream = chacha.update(zeros)
            for (l in 0 until 1024) lanes[l] = (lanes[l] + (((stream[2 * l].toInt() and 0xff) shl 8) or (stream[2 * l + 1].toInt() and 0xff))).toShort()
            sink += lanes[5]
        }
        // MuHash3072 geometry (Bitcoin Core): element expanded to 384 bytes (ChaCha20), multiplied into a 3072-bit accumulator modulo 2^3072 - 1103717.
        val modulus = java.math.BigInteger.ONE.shiftLeft(3072).subtract(java.math.BigInteger.valueOf(1103717))
        var acc = java.math.BigInteger.ONE; val zeros384 = ByteArray(384)
        val mu = cpuUs { i ->
            element[0] = i.toByte(); element[1] = (i shr 8).toByte(); element[2] = (i shr 16).toByte()
            val key = sha.digest(element); nonce[0] = i.toByte(); nonce[1] = (i shr 8).toByte(); nonce[2] = (i shr 16).toByte(); nonce[3] = (i shr 24).toByte()
            chacha.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.ChaCha20ParameterSpec(nonce, 1))
            val x = java.math.BigInteger(1, chacha.update(zeros384))
            acc = acc.multiply(x).mod(modulus)
            sink += acc.bitLength()
        }
        // AdHash over 2^3072 as in design 4.2: 12 SHA-256 blocks per element and one 3072-bit addition.
        var adAcc = java.math.BigInteger.ZERO; val out384 = ByteArray(384); val mod3072 = java.math.BigInteger.ONE.shiftLeft(3072)
        val ad = cpuUs { i ->
            element[0] = i.toByte(); element[1] = (i shr 8).toByte(); element[2] = (i shr 16).toByte()
            for (c in 0 until 12) { sha.update(element); sha.update(c.toByte()); sha.digest(out384, c * 32, 32) }
            adAcc = adAcc.add(java.math.BigInteger(1, out384)).mod(mod3072)
            sink += adAcc.bitLength()
        }
        println("BENCH homomorphic element cost (thread CPU per element): LtHash16 geometry, SHA-256 counter expand: %.2f us; LtHash16 geometry, ChaCha20 expand: %.2f us; MuHash3072 (ChaCha20 384 B + 3072-bit mulmod): %.2f us; AdHash 2^3072 (12 SHA-256 blocks + add): %.2f us   [sink $sink]".format(ltSha, ltChaCha, mu, ad))
        // The same measure for the trie: CPU per entity write at 10^6 entities is in the main table (E3 / 4.8 entity writes per decision).
        val e0 = StateCommitment.Entity("K", ByteArray(60) { 1 }, ByteArray(80) { 2 })
        val leaf = cpuUs { i -> e0.keyBytes[0] = i.toByte(); e0.keyBytes[1] = (i shr 8).toByte(); val k = StateCommitment.keyOf(e0); sink += StateCommitment.leafOf(e0, k)[0] }
        println("BENCH entity key + leaf hash only (no tree): %.2f us per entity".format(leaf))
    }

    @Test fun commitmentOptions() {
        val pre = env("CAP_PRE", 150_000).toInt(); val meas = env("CAP_MEAS", 40_000).toInt(); val reps = env("CAP_REPS", 3).toInt()
        val accounts = env("CAP_ACCOUNTS", 2_000).toInt(); val instruments = env("CAP_INSTRUMENTS", 8).toInt()
        val (recs, timedFrom) = workload(pre, meas, accounts, instruments)
        println("BENCH workload: ${recs.size} decisions, ${recs.sumOf { it.writes.size }} entity writes, timed ${recs.size - timedFrom} decisions (${(recs.size - timedFrom) / 2} trades) after ${timedFrom} decisions; $accounts accounts, $instruments instruments")
        val all: Map<String, () -> Engine> = linkedMapOf(
            "E0" to { LinkedEngine() }, "E1" to { FlatLegacyLeafEngine() }, "E2" to { LinkedFastLeafEngine() }, "E3" to { FlatFastLeafEngine() },
            "D0" to { DiagEngine(0) }, "D1" to { DiagEngine(1) }, "D2" to { DiagEngine(2) },
            "L8" to { LaggedEngine(8, false) }, "L32" to { LaggedEngine(32, false) }, "L32b" to { LaggedEngine(32, true) }, "L256b" to { LaggedEngine(256, true) },
            "L256s" to { LaggedEngine(256, true, true) }, "L4096s" to { LaggedEngine(4096, true, true) }, "L65536s" to { LaggedEngine(65536, true, true) },
            "S256" to { Sha512Engine("SHA-256") }, "S512" to { Sha512Engine("SHA-512/256") }, "H1" to { HybridEngine(true) }, "H2" to { HybridEngine(false) },
            "H2m" to { Hybrid2Engine(true, false) }, "H4c" to { Hybrid2Engine(false, true) }, "H4m" to { Hybrid2Engine(true, true) },
        )
        val wanted = System.getenv("CAP_ENGINES")?.split(",")?.map { it.trim() } ?: all.keys.toList()
        val results = LinkedHashMap<String, MutableList<Result>>()
        repeat(reps) { rep ->
            for (id in wanted) { val r = measure(all.getValue(id), recs, timedFrom, verify = rep == 0); results.getOrPut(id) { ArrayList() } += r
                println("BENCH rep $rep %-5s cpu %.2f us/decision, alloc %.0f B, branch hashes %.1f, wall %.2f us".format(id, r.cpuNsPerDecision / 1000, r.allocBytesPerDecision, r.branchPerDecision, r.wallNsPerDecision / 1000)) }
        }
        println("BENCH ---- summary (thread CPU per decision; min..max over $reps interleaved repeats)")
        val base = results["E0"]?.map { it.cpuNsPerDecision }?.average()
        for ((id, rs) in results) {
            val cpu = rs.map { it.cpuNsPerDecision / 1000 }
            println("BENCH %-5s %-70s cpu %.2f..%.2f us (mean %.2f)%s  alloc %.0f B  branch hashes %.1f".format(id, rs[0].name, cpu.min(), cpu.max(), cpu.average(),
                if (base != null) "  x%.2f vs E0".format(base / 1000 / cpu.average()) else "", rs[0].allocBytesPerDecision, rs[0].branchPerDecision))
        }
    }
}
