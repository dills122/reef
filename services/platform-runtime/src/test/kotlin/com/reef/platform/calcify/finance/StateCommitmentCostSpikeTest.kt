package com.reef.platform.calcify.finance

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test

private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach(d::update) }.digest()
private fun frame(s: String): ByteArray { val b = s.toByteArray(); return java.nio.ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array() }
private fun entity(tag: String, key: String, value: String) = frame(tag) + frame(key) + frame(value)

/**
 * Research spike for the state-commitment design (docs/work/CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md), review round 1 follow-up.
 * Not product code; excluded from the default run (-PresearchTests). Answers, with numbers, what the reviewers asked:
 *  (1) what each O(n) term of the CURRENT ledger really costs at 10^3..10^5 entities, measured on synthetic states that bypass the pins;
 *  (2) what the exact candidate structures cost for 3- and 7-entity decisions, including an adversarially crowded bucket.
 */
@org.junit.jupiter.api.Tag("research")
class StateCommitmentCostSpikeTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL")
    private val ledger = FinanceLedger(FinanceConfig((0 until 16).map { AccountId("acct-$it") }.toSet(), usd, aapl, AccountId("opening")))

    private fun synthetic(n: Int): FinanceState {
        val executions = HashMap<ExecutionKey, Captured>(); val obligations = HashMap<ExecutionKey, Obligation>()
        val attempts = HashMap<Pair<ExecutionKey, Int>, Disposition>(); val actions = HashMap<ActionKey, ActionRecord>()
        val perClass = (n - 32) / 5   // 3 x perClass + 2 x perClass actions + 32 positions = n entities exactly (round 2 lens C found the earlier 1.25n)
        for (i in 0 until perClass) {
            val k = ExecutionKey("run", "session", "AAPL", "exec-$i")
            executions[k] = Captured(AccountId("acct-${i % 16}"), AccountId("acct-${(i + 1) % 16}"), 3, 100)
            obligations[k] = Obligation(if (i % 2 == 0) ObligationStatus.SETTLED else ObligationStatus.PENDING, if (i % 2 == 0) 0 else 300, if (i % 2 == 0) 0 else 3)
            attempts[k to 1] = Disposition.SETTLED
        }
        for (i in 0 until 2 * perClass) actions[ActionKey("ns", "dom", "action-$i")] = ActionRecord("d".repeat(64), Disposition.CAPTURED)
        val positions = HashMap<Pair<AccountId, AssetId>, Long>()
        for (a in 0 until 16) { positions[AccountId("acct-$a") to usd] = 1000L + a; positions[AccountId("acct-$a") to aapl] = 10L + a }
        return FinanceState(positions, executions, obligations, attempts, actions, (2 * perClass).toLong(), "c".repeat(64), 0)
    }

    private inline fun time(iterations: Int, block: () -> Unit): Long {
        repeat(maxOf(2, iterations / 5)) { block() }
        val s = System.nanoTime(); repeat(iterations) { block() }; return (System.nanoTime() - s) / iterations
    }

    @Test fun currentLedgerTermsAtScale() {
        for (n in listOf(1_000, 10_000, 100_000)) {
            val s = synthetic(n); val it = if (n >= 100_000) 4 else if (n >= 10_000) 20 else 200
            val digest = time(it) { ledger.stateDigest(s) }
            val copies = time(it) { s.executions.toMutableMap(); s.obligations.toMutableMap(); s.attempts.toMutableMap(); s.positions.toMutableMap(); s.actions + (ActionKey("ns", "dom", "new") to ActionRecord("d".repeat(64), Disposition.FUNDED)) }
            val next = s.copy(actions = s.actions + (ActionKey("ns", "dom", "new") to ActionRecord("d".repeat(64), Disposition.FUNDED)))
            val diff = time(it) { FinanceStore.entityWrites(s, next) }
            // The restore path: assemble from per-entity keys (not measured here) is dominated by the checkpoint encode below.
            val checkpoint = time(it) { ledger.checkpoint(s) }
            // The part of checkInvariants that is not bounded by the pins: the per-asset position sums and the obligation x attempt count.
            val invariants = time(maxOf(1, it / 4)) {
                for (asset in listOf(usd, aapl)) s.positions.filterKeys { k -> k.second == asset }.values.fold(BigInteger.ZERO) { a, v -> a + BigInteger.valueOf(v) }
                for ((k, o) in s.obligations) { val settled = s.attempts.count { (key, d) -> key.first == k && d == Disposition.SETTLED }; if (settled < 0 || o.cashResidual < 0) error("x") }
            }
            println("COST-SPIKE current n=$n entities: stateDigest=${digest / 1000}us mapCopies=${copies / 1000}us entityWritesDiff=${diff / 1000}us checkpointEncode=${checkpoint / 1000}us invariantsQuadraticPart=${invariants / 1000}us")
        }
    }

    // ---- candidate structures, exact design ----

    /** Flat 2^D buckets (design rev 1): bucket digest re-hashes all m sorted leaf digests. */
    private class FlatBuckets(val depth: Int) {
        val buckets = HashMap<Int, java.util.TreeMap<String, ByteArray>>()
        val levels = Array(depth + 1) { l -> HashMap<Int, ByteArray>() }
        val zero = Array(depth + 1) { ByteArray(32) }.also { z -> z[0] = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 0, 0, 0, 0)); for (h in 1..depth) z[h] = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(2) + z[h - 1] + z[h - 1]) }
        fun bucketOf(key: String): Int { val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()); return (((h[0].toInt() and 0xff) shl 16) or ((h[1].toInt() and 0xff) shl 8) or (h[2].toInt() and 0xff)) ushr (24 - depth) }
        fun put(changes: Map<String, ByteArray>) {
            val dirty = HashSet<Int>()
            for ((k, v) in changes) { val b = bucketOf(k); buckets.getOrPut(b) { java.util.TreeMap() }[k] = sha(byteArrayOf(0), v); dirty += b }
            for (b in dirty) { val md = MessageDigest.getInstance("SHA-256"); md.update(1); md.update(java.nio.ByteBuffer.allocate(4).putInt(buckets[b]!!.size).array()); buckets[b]!!.values.forEach(md::update); levels[0][b] = md.digest() }
            var cur: Set<Int> = dirty
            for (l in 1..depth) { val next = HashSet<Int>(); cur.forEach { next += it shr 1 }; for (p in next) levels[l][p] = sha(byteArrayOf(2), levels[l - 1][p * 2] ?: zero[l - 1], levels[l - 1][p * 2 + 1] ?: zero[l - 1]); cur = next }
        }
    }

    /** Path-compressed binary trie over the 256-bit key hash: shape is a pure function of the key set; worst case 256 levels, typical log2 n. */
    private class Patricia {
        sealed class N { abstract var hash: ByteArray }
        class Leaf(val key: ByteArray, var leaf: ByteArray) : N() { override var hash = leaf }  // the leaf digest L(e) IS the node hash (round 2: the earlier spike hashed it a second time)
        class Branch(val bit: Int, var left: N, var right: N) : N() { override var hash = ByteArray(0) }
        var root: N? = null
        var lastDepth = 0
        companion object { fun sha0(k: ByteArray, l: ByteArray) = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0) + k + l) }
        private fun bit(k: ByteArray, i: Int) = (k[i ushr 3].toInt() ushr (7 - (i and 7))) and 1
        private fun firstDiff(a: ByteArray, b: ByteArray): Int { for (i in 0 until 256) if (bit(a, i) != bit(b, i)) return i; return -1 }
        private fun rehash(b: Branch) { b.hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(2, (b.bit ushr 8).toByte(), b.bit.toByte()) + b.left.hash + b.right.hash) }
        private fun keyOf(n: N): ByteArray = when (n) { is Leaf -> n.key; is Branch -> keyOf(n.left) }
        fun put(key: ByteArray, leafDigest: ByteArray) { lastDepth = 0; root = insert(root, key, leafDigest) }
        private fun insert(n: N?, key: ByteArray, leaf: ByteArray): N {
            if (n == null) return Leaf(key, leaf)
            lastDepth++
            val any = keyOf(n); val d = firstDiff(any, key)
            if (n is Leaf) { if (d < 0) { n.leaf = leaf; n.hash = leaf; return n }; val nl = Leaf(key, leaf); val b = if (bit(key, d) == 0) Branch(d, nl, n) else Branch(d, n, nl); rehash(b); return b }
            n as Branch
            if (d in 0 until n.bit) { val nl = Leaf(key, leaf); val b = if (bit(key, d) == 0) Branch(d, nl, n) else Branch(d, n, nl); rehash(b); return b }
            if (bit(key, n.bit) == 0) n.left = insert(n.left, key, leaf) else n.right = insert(n.right, key, leaf)
            rehash(n); return n
        }
    }

    /** AdHash over 2^3072 with an explicit, versioned Expand: SHA-256(label || int32(i) || m), i = 0..11. */
    private class Ad {
        val mod = BigInteger.ONE.shiftLeft(3072); var acc = BigInteger.ZERO
        fun expand(m: ByteArray): BigInteger { val out = ByteArray(384); for (i in 0 until 12) System.arraycopy(sha(frame("calcify-finance-adhash-v2"), java.nio.ByteBuffer.allocate(4).putInt(i).array(), m), 0, out, i * 32, 32); return BigInteger(1, out) }
        private fun sha(vararg p: ByteArray) = MessageDigest.getInstance("SHA-256").also { d -> p.forEach(d::update) }.digest()
        fun replace(old: ByteArray?, new: ByteArray) { if (old != null) acc = acc.subtract(expand(old)); acc = acc.add(expand(new)).mod(mod) }
    }

    @Test fun candidateStructuresExactDesign() {
        val rnd = Random(7)
        for (n in listOf(1_000, 10_000, 100_000)) {
            val keys = (0 until n).map { "key-$it" }
            val ent = keys.map { entity("K", it, "v0-" + "x".repeat(40)) }
            val d = Math.ceil(Math.log(n.toDouble()) / Math.log(2.0)).toInt().coerceAtLeast(4)
            val flat = FlatBuckets(d); flat.put(keys.indices.associate { keys[it] to ent[it] })
            val pat = Patricia(); keys.indices.forEach { pat.put(sha(keys[it].toByteArray()), Patricia.sha0(sha(keys[it].toByteArray()), sha(ent[it]))) }
            val ad = Ad(); ent.forEach { ad.replace(null, it) }
            var version = 0
            fun touched(k: Int) = (0 until k).map { rnd.nextInt(n) }.distinct()
            val reps = if (n >= 100_000) 300 else 1000
            for (k in listOf(3, 7)) {
                val flatNs = time(reps) { version++; flat.put(touched(k).associate { keys[it] to entity("K", keys[it], "v$version-" + "x".repeat(40)) }) }
                var depth = 0L
                val patNs = time(reps) { version++; touched(k).forEach { val kh = sha(keys[it].toByteArray()); pat.put(kh, Patricia.sha0(kh, sha(entity("K", keys[it], "v$version-" + "x".repeat(40))))); depth += pat.lastDepth } }
                val adNs = time(reps) { version++; touched(k).forEach { ad.replace(ent[it], entity("K", keys[it], "v$version-" + "x".repeat(40))) } }
                println("COST-SPIKE candidates n=$n D=$d touched=$k: flatBuckets=${flatNs / 1000}us patricia=${patNs / 1000}us (avg depth ${"%.1f".format(depth.toDouble() / ((reps + maxOf(2, reps / 5)) * k))}) adhash3072=${adNs / 1000}us")
            }
        }
    }

    @Test fun adversarialCrowdedBucketVersusGrindedTrie() {
        // An attacker who can choose identifiers grinds colliding bucket prefixes (about 2^D hashes) and fills one flat bucket.
        val d = 17; val flat = FlatBuckets(d); val target = flat.bucketOf("seed")
        val crowded = ArrayList<String>(); var i = 0
        while (crowded.size < 2_000) { val k = "attack-$i"; if (flat.bucketOf(k) == target) crowded += k; i++ }       // ~2^17 trials per hit => 2000 hits ~ 2.6e8 hashes
        flat.put(crowded.associateWith { entity("K", it, "v-" + "x".repeat(40)) })
        var version = 0
        val flatNs = time(200) { version++; val k = crowded[version % crowded.size]; flat.put(mapOf(k to entity("K", k, "v$version"))) }
        // Same attacker against the trie: matching b leading bits of the 256-bit hash costs 2^b hashes, so depth grows only logarithmically in work.
        val pat = Patricia(); var maxDepth = 0
        var tried = 0L; val bitsWanted = 22; val seed = sha("seed".toByteArray()); val ground = ArrayList<String>()
        while (ground.size < 50) { val k = "grind-$tried"; tried++; val h = sha(k.toByteArray()); var m = 0; while (m < bitsWanted && ((h[m ushr 3].toInt() xor seed[m ushr 3].toInt()) ushr (7 - (m and 7))) and 1 == 0) m++; if (m >= bitsWanted - 4) ground += k }
        ground.forEach { pat.put(sha(it.toByteArray()), sha(byteArrayOf(0), it.toByteArray())); maxDepth = maxOf(maxDepth, pat.lastDepth) }
        val patNs = time(200) { version++; val k = ground[version % ground.size]; pat.put(sha(k.toByteArray()), sha(byteArrayOf(0), (k + version).toByteArray())) }
        println("COST-SPIKE adversarial: flat bucket with m=${crowded.size} colliding ids: ${flatNs / 1000}us per touch (vs ~10us normal); patricia with 50 ids sharing >=${bitsWanted - 4} leading hash bits: depth<=$maxDepth, ${patNs / 1000}us per touch after ${tried} trials")
    }
}
