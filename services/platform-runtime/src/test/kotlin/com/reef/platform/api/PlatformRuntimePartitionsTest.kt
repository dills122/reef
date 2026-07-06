package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformRuntimePartitionsTest {
    @Test
    fun allSelectsEveryConfiguredPartition() {
        assertEquals(listOf(0, 1, 2, 3), configuredRuntimePartitions("all", partitionCount = 4))
        assertEquals(listOf(0, 1), configuredRuntimePartitions(" ALL ", partitionCount = 2))
    }

    @Test
    fun explicitPartitionsAreDedupedSortedAndBounded() {
        assertEquals(listOf(0, 2, 3), configuredRuntimePartitions("3, 2, 9, nope, 2, -1, 0", partitionCount = 4))
    }
}
