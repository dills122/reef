package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The capacity app's Streams settings that are choices rather than pins. */
class CapacityStreamsTest {
    @Test fun theCapacityAppCompressesWhatItProducesWithLz4() {
        val props = CapacityStreams.properties("capacity-app", "localhost:9092", "/tmp/state", 2)
        assertEquals("lz4", props["producer.compression.type"].toString(), "decisions and changelog records are highly repetitive: lz4 cuts the bytes per decision by more than half for a small CPU cost")
        CapacityStreams.verifyPinned(props, 2)
    }

    @Test fun anOperatorCanChooseAnotherCodecWithoutBreakingThePins() {
        val props = CapacityStreams.properties("capacity-app", "localhost:9092", "/tmp/state", 2).apply { put("producer.compression.type", "zstd") }
        CapacityStreams.verifyPinned(props, 2)
        assertEquals("zstd", props["producer.compression.type"].toString())
    }

    @Test fun theFiniteProfileKeepsItsOwnSettings() {
        assertNull(FinanceTopology.streamsProperties("finite-app", "localhost:9092", "/tmp/state")["producer.compression.type"], "the finite profile is pinned and unchanged")
    }
}
