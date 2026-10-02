package com.reef.platform.calcify

import org.apache.kafka.common.TopicPartition
import kotlin.test.*

class ResolverConsumerGateTest {
    private val tp=TopicPartition("REEF_VERIFIED_COMMITMENTS_V1",0)

    @Test fun newConsumerGroupSeeksToBeginning() {
        val seeks=ResolverConsumerGate.seekTargets(mapOf(tp to 10L),mapOf(tp to 100L),emptyMap())
        assertEquals(mapOf(tp to 10L),seeks)
    }

    @Test fun committedOffsetWithinRetainedRangeNeedsNoSeek() {
        val seeks=ResolverConsumerGate.seekTargets(mapOf(tp to 10L),mapOf(tp to 100L),mapOf(tp to 42L))
        assertTrue(seeks.isEmpty())
    }

    @Test fun committedOffsetAtExactBoundsNeedsNoSeek() {
        assertTrue(ResolverConsumerGate.seekTargets(mapOf(tp to 10L),mapOf(tp to 100L),mapOf(tp to 10L)).isEmpty())
        assertTrue(ResolverConsumerGate.seekTargets(mapOf(tp to 10L),mapOf(tp to 100L),mapOf(tp to 100L)).isEmpty())
    }

    @Test fun committedOffsetBelowRetainedBeginningFailsClosed() {
        val ex=assertFailsWith<IllegalStateException> {
            ResolverConsumerGate.seekTargets(mapOf(tp to 50L),mapOf(tp to 100L),mapOf(tp to 10L))
        }
        assertTrue(ex.message!!.contains("retention lost"))
    }

    @Test fun committedOffsetAboveEndFailsClosed() {
        assertFailsWith<IllegalStateException> {
            ResolverConsumerGate.seekTargets(mapOf(tp to 10L),mapOf(tp to 100L),mapOf(tp to 101L))
        }
    }

    @Test fun multiplePartitionsEvaluatedIndependently() {
        val tp2=TopicPartition(tp.topic(),1)
        val seeks=ResolverConsumerGate.seekTargets(
            mapOf(tp to 0L,tp2 to 50L),
            mapOf(tp to 10L,tp2 to 100L),
            mapOf(tp to 5L),
        )
        assertEquals(mapOf(tp2 to 50L),seeks)
    }
}
