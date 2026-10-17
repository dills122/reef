package com.reef.platform.calcify.finance

import com.reef.platform.calcify.StreamsLifecycleKv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.apache.kafka.streams.processor.api.MockProcessorContext

/** The Streams store orders keys by their UTF-8 bytes, like RocksDB; a prefix scan must cover every key with the prefix however its next character sorts. */
class StreamsLifecycleKvScanTest {
    private fun kvOf(keys: List<String>): StreamsLifecycleKv {
        val config = CapacityProcessorConfig("b".repeat(64), CapacityConfig(null, AssetId("USD"), setOf(AssetId("I")), AccountId("opening"), 10, 10, 10, 10), "C", "K", "D", "scan-test")
        val context = MockProcessorContext<String, ByteArray>()
        val store = CapacityTopology.storeBuilder(config, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        keys.forEach { store.put(it, byteArrayOf(1)) }
        return StreamsLifecycleKv(store)
    }

    @Test fun aPrefixScanCoversKeysWhoseNextCharacterSortsPastTheOldUpperBound() {
        val keys = listOf("e/O/00", "e/O/￿x", "e/O/😀", "e/O0", "e/P/00", "e/", "d")
        val kv = kvOf(keys)
        assertEquals(setOf("e/O/00", "e/O/￿x", "e/O/😀"), kv.scan("e/O/").map { it.first }.toSet())
        assertEquals(setOf("e/O/00", "e/O/￿x", "e/O/😀"), kv.scanStreaming("e/O/") { s -> s.map { it.first }.toSet() })
        assertEquals(setOf("e/O/00", "e/O/￿x", "e/O/😀", "e/O0", "e/P/00", "e/"), kv.scan("e/").map { it.first }.toSet())
    }

    @Test fun anEvictingRestoreRefusesAStrayKeyThatSortsPastItsClassScan() {
        val cfg = CapacityConfig(null, AssetId("USD"), setOf(AssetId("I")), AccountId("opening"), 1000, 1000, 1000, 100)
        val binding = "b".repeat(64); val ledger = CapacityLedger(cfg)
        val d = ledger.decide(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("a"), AssetId("USD"), 10)).decision!!
        val writes = CapacityStore.writes(ledger, d, binding)
        for (stray in listOf("e/O/￿x", "e/P/😀", "e/K/￿", "e/Z/00", "e0")) for (evict in listOf(false, true)) {
            val kv = kvOf(emptyList()).also { it.apply(writes) }
            assertNotNull(CapacityStore.restore(cfg, kv, binding, evictSettled = evict), "the clean store restores")
            kv.apply(mapOf(stray to byteArrayOf(9)))
            if (stray == "e0") { assertNotNull(CapacityStore.restore(cfg, kv, binding, evictSettled = evict), "a key outside the e/ range is not an entity key"); continue }
            assertFailsWith<IllegalArgumentException>("stray key $stray, evict=$evict") { CapacityStore.restore(cfg, kv, binding, evictSettled = evict) }
        }
    }
}
