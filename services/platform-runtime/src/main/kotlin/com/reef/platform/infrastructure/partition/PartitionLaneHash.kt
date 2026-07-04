package com.reef.platform.infrastructure.partition

object PartitionLaneHash {
    fun laneFor(key: String, laneCount: Int): Int {
        require(laneCount > 0) { "laneCount must be positive" }
        if (laneCount == 1) return 0

        val base = key.hashCode()
        var bestLane = 0
        var bestScore = score(base, 0)
        for (lane in 1 until laneCount) {
            val candidate = score(base, lane)
            if (Integer.compareUnsigned(candidate, bestScore) > 0) {
                bestLane = lane
                bestScore = candidate
            }
        }
        return bestLane
    }

    private fun score(base: Int, lane: Int): Int {
        val ordinal = lane + 1
        return mix(base + ordinal * LANE_ADD xor (ordinal * LANE_XOR) xor SCORE_SALT)
    }

    private fun mix(value: Int): Int {
        var mixed = value
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7feb352d
        mixed = mixed xor (mixed ushr 15)
        mixed *= -0x7b935975
        mixed = mixed xor (mixed ushr 16)
        return mixed
    }

    private const val LANE_ADD = 483_794_952
    private const val LANE_XOR = -1_916_038_502
    private const val SCORE_SALT = 1_143_093_162
}
