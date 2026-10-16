package com.reef.platform.calcify.finance

import java.security.MessageDigest

/**
 * [StateTrie] with the same shape, the same hashes and the same API, stored in flat chunked arrays instead of linked heap objects (design section 4.1 is
 * unchanged: this is a contract-neutral layout change, proven by differential test to give the same digest as [StateTrie] and [StateCommitment.trieRoot]).
 *
 * Why. Measured (docs/work/CALCIFY_STATE_COMMITMENT_INVESTIGATION.md): of the roughly 20 us a decision spends on the commitment at 10^6 entities, only about 6 us
 * is SHA-256; the rest is memory latency. A linked trie chases a Branch object, its two children and their separate digest arrays on the way down (`put`) and again
 * when it re-hashes (`root`), and every hash allocates. Here
 *  - a leaf is its 32-byte key in a chunk of keys;
 *  - a branch is four ints (left, right, bit index, stale flag) in a chunk of branch metadata, and **its two child digests sit next to each other in 64 bytes of a
 *    parallel chunk, which is exactly the 64-byte tail of the branch's hash input**. A node's own digest therefore lives in its parent's slot (or in `rootDigest`
 *    for the root), so re-hashing a branch reads one record, not a record plus a sibling object plus a sibling digest array;
 *  - a child reference is `>= 0` for a branch id and `< 0` for `~leafId`;
 *  - the descent of `put` also touches each visited branch's digest record, so those cache misses are issued in parallel with the dependent pointer chase instead
 *    of one after the other when `root` runs;
 *  - a branch hash assembles its 67 input bytes in one reused buffer and writes the digest straight into its slot: no allocation per hash.
 * Chunks (64 Ki nodes each) keep growth O(chunk) with no large array copy, so a lane never pauses to grow the trie.
 *
 * Invariant: if a branch is not stale, the digest in its parent's slot (or `rootDigest`) is its current digest; a stale branch's slot is garbage until `root` re-hashes it.
 *
 * Not thread-safe: one writer.
 */
internal class FlatStateTrie {
    companion object {
        private const val CHUNK_BITS = 16
        private const val CHUNK = 1 shl CHUNK_BITS
        private const val MASK = CHUNK - 1
        private const val NO_ROOT = Int.MIN_VALUE
        private const val SNAPSHOT_MAGIC = 0x46535431   // "FST1"

        private fun validRef(ref: Int, branches: Int, leaves: Int) = if (ref >= 0) ref < branches else ref.inv() < leaves

        /**
         * The trie [writeTo] wrote, refused if any byte of it changed: the snapshot ends in the SHA-256 of everything before it, which is checked after the last array is
         * read, so what is returned is exactly what was written. That is integrity against corruption and truncation, not authenticity: whoever can rewrite the file can
         * rewrite the trailer. The digest [root] returns is the one stored in the snapshot (nothing is re-hashed), so a caller that restores from a snapshot must still bind
         * it to the state it was taken at (compare the commitment with the head decision's, see [CapacityStore]) and must never take a snapshot from outside the lane's own
         * private state directory. The tree shape is checked in full ([checkShape]); the digests are not.
         */
        fun readFrom(source: java.io.DataInputStream): FlatStateTrie {
            val sum = MessageDigest.getInstance("SHA-256")
            val input = java.io.DataInputStream(java.security.DigestInputStream(source, sum))
            require(input.readInt() == SNAPSHOT_MAGIC) { "not a trie snapshot" }
            val leaves = input.readInt(); val branches = input.readInt(); val root = input.readInt()
            require(leaves >= 0 && branches >= 0 && (if (leaves == 0) branches == 0 else branches == leaves - 1)) { "corrupt trie snapshot counts" }
            require(if (leaves == 0) root == NO_ROOT else if (leaves == 1) root == -1 else root in 0 until branches) { "corrupt trie snapshot root" }
            val t = FlatStateTrie()
            input.readFully(t.rootDigest)
            t.leafCount = leaves; t.branchCount = branches; t.rootRef = root
            t.keyChunks = arrayOfNulls(maxOf(16, Integer.highestOneBit(maxOf(1, (leaves + MASK) ushr CHUNK_BITS)) * 2))
            t.metaChunks = arrayOfNulls(t.keyChunks.size); t.digChunks = arrayOfNulls(t.keyChunks.size)
            var left = leaves; var c = 0
            while (left > 0) { val n = minOf(left, CHUNK); t.keyChunks[c] = ByteArray(CHUNK * 32).also { input.readFully(it, 0, n * 32) }; left -= n; c++ }
            left = branches; c = 0
            val staging = ByteArray(CHUNK * 16)
            while (left > 0) {
                val n = minOf(left, CHUNK)
                input.readFully(staging, 0, n * 16)
                val meta = IntArray(CHUNK * 4); java.nio.ByteBuffer.wrap(staging, 0, n * 16).asIntBuffer().get(meta, 0, n * 4)
                for (i in 0 until n) {
                    val o = i shl 2
                    require(meta[o + 3] == 0 && meta[o + 2] in 0 until 256 && validRef(meta[o], branches, leaves) && validRef(meta[o + 1], branches, leaves)) { "corrupt trie snapshot node" }
                }
                t.metaChunks[c] = meta
                t.digChunks[c] = ByteArray(CHUNK * 64).also { input.readFully(it, 0, n * 64) }
                left -= n; c++
            }
            val computed = sum.digest(); val stored = ByteArray(32); source.readFully(stored)
            require(MessageDigest.isEqual(computed, stored)) { "trie snapshot checksum mismatch" }
            t.checkShape()
            return t
        }
    }

    private var keyChunks = arrayOfNulls<ByteArray>(16)     // 32 bytes per leaf: its key
    private var metaChunks = arrayOfNulls<IntArray>(16)     // 4 ints per branch: left, right, bit index, stale
    private var digChunks = arrayOfNulls<ByteArray>(16)     // 64 bytes per branch: digest of the left child, digest of the right child
    private var leafCount = 0
    private var branchCount = 0
    private var rootRef = NO_ROOT
    private val rootDigest = ByteArray(32)
    private val md = MessageDigest.getInstance("SHA-256")
    private val buf = ByteArray(67).also { it[0] = 0x02 }
    private var touched = 0                                 // sink for the cache-warming reads, so they are not optimised away

    val size: Int get() = leafCount

    /** Branch hashes computed so far; same meaning as [StateTrie.branchHashes]. */
    var branchHashes = 0L
        private set

    private fun bit(key: ByteArray, index: Int): Int = (key[index ushr 3].toInt() ushr (7 - (index and 7))) and 1

    private fun newLeaf(key: ByteArray): Int {
        val id = leafCount++
        val c = id ushr CHUNK_BITS
        if (c >= keyChunks.size) keyChunks = keyChunks.copyOf(keyChunks.size * 2)
        val chunk = keyChunks[c] ?: ByteArray(CHUNK * 32).also { keyChunks[c] = it }
        System.arraycopy(key, 0, chunk, (id and MASK) shl 5, 32)
        return id.inv()
    }

    private fun newBranch(index: Int, left: Int, right: Int): Int {
        val id = branchCount++
        val c = id ushr CHUNK_BITS
        if (c >= metaChunks.size) { metaChunks = metaChunks.copyOf(metaChunks.size * 2); digChunks = digChunks.copyOf(digChunks.size * 2) }
        val meta = metaChunks[c] ?: IntArray(CHUNK * 4).also { metaChunks[c] = it }
        if (digChunks[c] == null) digChunks[c] = ByteArray(CHUNK * 64)
        val o = (id and MASK) shl 2
        meta[o] = left; meta[o + 1] = right; meta[o + 2] = index; meta[o + 3] = 1
        return id
    }

    private fun meta(branch: Int) = metaChunks[branch ushr CHUNK_BITS]!!
    private fun metaOff(branch: Int) = (branch and MASK) shl 2
    private fun digs(branch: Int) = digChunks[branch ushr CHUNK_BITS]!!
    private fun digOff(branch: Int) = (branch and MASK) shl 6
    private fun keyChunk(ref: Int) = keyChunks[ref.inv() ushr CHUNK_BITS]!!
    private fun keyOff(ref: Int) = (ref.inv() and MASK) shl 5

    private fun firstDifferingBit(key: ByteArray, ref: Int): Int {
        val chunk = keyChunk(ref); val off = keyOff(ref)
        for (i in 0 until 32) { val x = (key[i].toInt() xor chunk[off + i].toInt()) and 0xff; if (x != 0) return i * 8 + Integer.numberOfLeadingZeros(x) - 24 }
        return -1
    }

    /** Follows [key]'s bits to the closest leaf, touching each branch's digest record on the way so its cache line is already in flight when `root` needs it. */
    private fun closestLeaf(key: ByteArray): Int {
        var r = rootRef; var t = 0
        while (r >= 0) {
            val m = meta(r); val o = metaOff(r)
            t += digs(r)[digOff(r)].toInt()
            r = if (bit(key, m[o + 2]) == 0) m[o] else m[o + 1]
        }
        touched = t
        return r
    }

    /** The leaf digest stored for [key], or null. */
    fun get(key: ByteArray): ByteArray? {
        require(key.size == 32) { "keys are 32 bytes" }
        if (rootRef == NO_ROOT) return null
        var r = rootRef; var from = rootDigest; var at = 0
        while (r >= 0) {
            val m = meta(r); val o = metaOff(r)
            val right = bit(key, m[o + 2]) != 0
            from = digs(r); at = digOff(r) + (if (right) 32 else 0)
            r = if (right) m[o + 1] else m[o]
        }
        return if (firstDifferingBit(key, r) < 0) from.copyOfRange(at, at + 32) else null
    }

    /** Inserts the pair, or replaces the leaf digest of an existing key; the branches on the path are marked stale. */
    fun put(key: ByteArray, leaf: ByteArray) {
        require(key.size == 32 && leaf.size == 32) { "keys and leaf digests are 32 bytes" }
        if (rootRef == NO_ROOT) { rootRef = newLeaf(key); System.arraycopy(leaf, 0, rootDigest, 0, 32); return }
        val d = firstDifferingBit(key, closestLeaf(key))
        // Descend again (the path is hot now), marking the branches stale, to the first branch whose index is not below d (for a new key) or to the leaf (for the same key).
        var parent = -1; var parentRight = false; var cur = rootRef
        while (cur >= 0) {
            val m = meta(cur); val o = metaOff(cur)
            if (d >= 0 && m[o + 2] >= d) break
            m[o + 3] = 1
            parent = cur; parentRight = bit(key, m[o + 2]) != 0
            cur = if (parentRight) m[o + 1] else m[o]
        }
        if (d < 0) {                                         // the same key: replace the leaf digest where its parent keeps it
            if (parent < 0) System.arraycopy(leaf, 0, rootDigest, 0, 32)
            else System.arraycopy(leaf, 0, digs(parent), digOff(parent) + (if (parentRight) 32 else 0), 32)
            return
        }
        // a new key: the branch at bit d takes the fresh leaf on one side and the subtree [cur] on the other; [cur]'s digest moves with it
        val fresh = newLeaf(key)
        val freshRight = bit(key, d) != 0
        val branch = if (freshRight) newBranch(d, cur, fresh) else newBranch(d, fresh, cur)
        val bd = digs(branch); val bo = digOff(branch)
        System.arraycopy(leaf, 0, bd, bo + (if (freshRight) 32 else 0), 32)
        val curAt = bo + (if (freshRight) 0 else 32)
        if (parent < 0) System.arraycopy(rootDigest, 0, bd, curAt, 32)
        else System.arraycopy(digs(parent), digOff(parent) + (if (parentRight) 32 else 0), bd, curAt, 32)
        if (parent < 0) rootRef = branch else meta(parent)[metaOff(parent) + (if (parentRight) 1 else 0)] = branch
    }

    /** The trie digest `T`: the root's digest, or `SHA-256(0x03)` for the empty set. Re-hashes only the stale branches. */
    fun root(): ByteArray {
        val r = rootRef
        if (r == NO_ROOT) return StateCommitment.emptyTrie
        if (r >= 0) clean(r, rootDigest, 0)
        return rootDigest.copyOf()
    }

    /** Ensures the digest of [branch], kept at [dest]`[at]`, is current, re-hashing stale branches below it. */
    private fun clean(branch: Int, dest: ByteArray, at: Int) {
        val m = meta(branch); val o = metaOff(branch)
        if (m[o + 3] == 0) return
        val left = m[o]; val right = m[o + 1]; val index = m[o + 2]
        val bd = digs(branch); val bo = digOff(branch)
        if (left >= 0) clean(left, bd, bo)
        if (right >= 0) clean(right, bd, bo + 32)
        buf[1] = (index ushr 8).toByte(); buf[2] = index.toByte()
        System.arraycopy(bd, bo, buf, 3, 64)
        md.update(buf, 0, 67)
        md.digest(dest, at, 32)
        branchHashes++
        m[o + 3] = 0
    }

    /**
     * Writes the trie as raw arrays (about 112 bytes per entity): the counts, the root, and the used part of each chunk, then the SHA-256 of all of it (see [readFrom]). First
     * makes every digest current, so no stale flag is stored and the snapshot is exactly what [root] would return.
     */
    fun writeTo(target: java.io.DataOutputStream) {
        root()
        val sum = MessageDigest.getInstance("SHA-256")
        val out = java.io.DataOutputStream(java.security.DigestOutputStream(target, sum))
        out.writeInt(SNAPSHOT_MAGIC); out.writeInt(leafCount); out.writeInt(branchCount); out.writeInt(rootRef); out.write(rootDigest)
        var left = leafCount; var c = 0
        while (left > 0) { val n = minOf(left, CHUNK); out.write(keyChunks[c]!!, 0, n * 32); left -= n; c++ }
        left = branchCount; c = 0
        val staging = java.nio.ByteBuffer.allocate(CHUNK * 16)
        while (left > 0) {
            val n = minOf(left, CHUNK)
            staging.clear(); staging.asIntBuffer().put(metaChunks[c]!!, 0, n * 4)
            out.write(staging.array(), 0, n * 16); out.write(digChunks[c]!!, 0, n * 64)
            left -= n; c++
        }
        out.flush()
        target.write(sum.digest())
    }

    /**
     * Requires the arrays to be one Patricia trie, as [put] builds them: every branch and leaf reached exactly once from the root, bit indices strictly increasing on every
     * path, and at each branch the leaves on its left and right first differ at its bit, left 0 and right 1 (checked on one leaf per side, which with the same check below
     * makes every leaf's key agree with its path). O(n) and no hashing. The bits of a key past its last branch are constrained by nothing but the checksum, and whether the
     * digests are the committed ones is the caller's check of [root] against the head decision.
     */
    private fun checkShape() {
        if (rootRef < 0) return                              // empty, or one leaf
        val seenBranches = java.util.BitSet(branchCount); val seenLeaves = java.util.BitSet(leafCount)
        /** The leftmost leaf under [ref]. */
        fun walk(ref: Int, above: Int): Int {
            if (ref < 0) { require(!seenLeaves[ref.inv()]) { "corrupt trie snapshot: a leaf is reached twice" }; seenLeaves.set(ref.inv()); return ref }
            require(!seenBranches[ref]) { "corrupt trie snapshot: a branch is reached twice" }; seenBranches.set(ref)
            val m = meta(ref); val o = metaOff(ref); val index = m[o + 2]
            require(index > above) { "corrupt trie snapshot: bit indices do not increase" }
            val left = walk(m[o], index); val right = walk(m[o + 1], index)
            val a = keyChunk(left); val ao = keyOff(left); val b = keyChunk(right); val bo = keyOff(right)
            var d = -1
            for (i in 0 until 32) { val x = (a[ao + i].toInt() xor b[bo + i].toInt()) and 0xff; if (x != 0) { d = i * 8 + Integer.numberOfLeadingZeros(x) - 24; break } }
            require(d == index && (a[ao + (index ushr 3)].toInt() ushr (7 - (index and 7))) and 1 == 0) { "corrupt trie snapshot: a branch does not split its leaves at its bit" }
            return left
        }
        walk(rootRef, -1)
        require(seenBranches.cardinality() == branchCount && seenLeaves.cardinality() == leafCount) { "corrupt trie snapshot: unreachable nodes" }
    }

    /** The depth (number of branches) on the path to [key]'s leaf or its closest leaf; a test aid. */
    fun depthOf(key: ByteArray): Int { var r = rootRef; var depth = 0; while (r >= 0) { depth++; val m = meta(r); val o = metaOff(r); r = if (bit(key, m[o + 2]) == 0) m[o] else m[o + 1] }; return depth }
}
