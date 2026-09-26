package com.reef.platform.application.postmatch

/** Kafka venue sequence stores the partition in its high 16 bits and offset + 1 below it. */
object CanonicalStreamPosition {
    private const val PARTITION_SHIFT = 48
    private const val MAX_PARTITION = 32767

    fun origin(partitionId: Int): Long {
        require(partitionId in 0..MAX_PARTITION) { "canonical partition is outside signed stream sequence range" }
        return partitionId.toLong() shl PARTITION_SHIFT
    }
}
