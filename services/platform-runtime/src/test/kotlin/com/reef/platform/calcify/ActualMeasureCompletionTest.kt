package com.reef.platform.calcify

import kotlin.test.*

class ActualMeasureCompletionTest {
    @Test fun unfinishedObserverDoesNotStopAtOfferedCount() {
        val completion=ActualMeasureCompletion()
        assertTrue(completion.awaiting(100))
        completion.accept("finish 80",123L,79)
        assertTrue(completion.awaiting(79));assertFalse(completion.awaiting(80));assertFalse(completion.awaiting(81))
        assertEquals(ActualMeasureCompletion.Finish(80,123L,79),completion.finish())
    }
    @Test fun invalidAndRepeatedCompletionFailClosed() {
        for(line in listOf("finish -1","finish nope","finish 2147483648","finish 1 extra")) {
            val completion=ActualMeasureCompletion();completion.accept(line,1L,0)
            assertNotNull(completion.error());assertNull(completion.finish());assertFalse(completion.awaiting(0))
        }
        val completion=ActualMeasureCompletion();completion.accept("finish 0",1L,0);completion.accept("finish 3",2L,0)
        assertEquals(0,completion.finish()?.expected);assertNotNull(completion.error())
    }
}
