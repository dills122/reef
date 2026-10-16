package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.BloomFilter
import org.rocksdb.CompressionType
import org.rocksdb.LRUCache
import org.rocksdb.Options
import org.rocksdb.ReadOptions
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions

/**
 * What a read-through to the lane's RocksDB store costs per trade, the number that decides whether settled state can leave the heap: a new trade needs two NEGATIVE lookups
 * (is this execution known? is this action key known?), a redelivery needs a positive one. RocksDB directly (no Kafka Streams layers), keys and values the size of the
 * ledger's entity records, N entries (CAP_KEYS, default 10M), a block cache of CAP_CACHE_MB. Research only (-PresearchTests); prints `ROCKS-COST` lines.
 */
@org.junit.jupiter.api.Tag("research")
class RocksDbLookupCostTest {
    private fun env(name: String, d: Long) = System.getenv(name)?.toLong() ?: d
    private fun key(i: Long) = "e/X/" + "%016x%016x%016x%016x".format(i * 0x9E3779B97F4A7C15uL.toLong(), i * 31 + 7, i xor 0x5555, i * 17).substring(0, 64)
    private val value = ByteArray(96) { (it * 7).toByte() }

    @Test fun lookupCostWithAndWithoutBloomFilters() {
        RocksDB.loadLibrary()
        val keys = env("CAP_KEYS", 10_000_000); val cacheMb = env("CAP_CACHE_MB", 512)
        for (bloom in listOf(false, true)) {
            val dir = Files.createTempDirectory("rocks-cost")
            val cache = LRUCache(cacheMb * 1024 * 1024)
            val table = BlockBasedTableConfig().setBlockCache(cache).setBlockSize(4096).setCacheIndexAndFilterBlocks(true).also { if (bloom) it.setFilterPolicy(BloomFilter(10.0, false)) }
            val options = Options().setCreateIfMissing(true).setTableFormatConfig(table).setCompressionType(CompressionType.NO_COMPRESSION).setWriteBufferSize(64L shl 20).setMaxWriteBufferNumber(3)
            RocksDB.open(options, dir.toString()).use { db ->
                val write = WriteOptions().setDisableWAL(true)
                val t0 = System.nanoTime()
                var i = 0L
                while (i < keys) { WriteBatch().use { b -> repeat(10_000) { if (i < keys) { b.put(key(i).toByteArray(), value); i++ } }; db.write(write, b) } }
                db.flush(org.rocksdb.FlushOptions().setWaitForFlush(true))
                println("ROCKS-COST bloom=$bloom loaded $keys entries in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s, cache ${cacheMb} MB")
                val rnd = Random(1); val read = ReadOptions()
                fun timeIt(label: String, n: Int, op: (Int) -> Unit) { repeat(n / 10) { op(it) }; val s = System.nanoTime(); repeat(n) { op(it) }; println("ROCKS-COST bloom=$bloom $label: ${"%.2f".format((System.nanoTime() - s) / 1000.0 / n)} us/op") }
                // keys are formatted BEFORE the timed loops: String.format costs more than the lookup
                val missing = Array(1 shl 20) { key(keys + 1_000_000_000L + rnd.nextInt(Int.MAX_VALUE)).toByteArray() }
                val present = Array(1 shl 20) { key((rnd.nextDouble() * keys).toLong()).toByteArray() }
                timeIt("negative get (key never written)", 1_000_000) { db.get(read, missing[it and (missing.size - 1)]) }
                timeIt("positive get, random old key", 1_000_000) { db.get(read, present[it and (present.size - 1)]) }
                // the per-trade pattern: 2 negative lookups, then 3 new entries written (execution, obligation/attempt, action) in one batch
                val trades = 500_000
                val newKeys = Array(trades * 3) { key(keys + it).toByteArray() }
                var n = 0
                timeIt("per trade: 2 negative gets + 3 puts", trades) { _ ->
                    db.get(read, missing[(2 * n) and (missing.size - 1)]); db.get(read, missing[(2 * n + 1) and (missing.size - 1)])
                    WriteBatch().use { b -> repeat(3) { b.put(newKeys[(3 * n + it) % newKeys.size], value) }; db.write(write, b) }
                    n++
                }
            }
            cache.close(); options.close(); dir.toFile().deleteRecursively()
        }
    }
}
