package com.reef.platform.calcify

import com.reef.platform.api.JsonCodec
import java.security.MessageDigest
import kotlin.test.*

class CalcifyVerifiedVisibilityObserverTest {
    @Test fun histogramPercentileCeilingsAndOverflowRemainUpperBounds() {
        val h=CalcifyVerifiedVisibilityObserver.MillisecondLatencyHistogram(10)
        assertNull(h.upper(50));listOf(0L,1L,1000000L,1000001L).forEach(h::add)
        assertEquals(1L,h.upper(50));assertEquals(2L,h.upper(95));assertEquals(2L,h.upper(99))
        h.add(20000001L);assertEquals(21L,h.upper(99));assertEquals(1L,h.overflow)
        assertFailsWith<IllegalArgumentException> {h.add(-1)}
    }
    @Test fun timingIntegrityRequiredWithoutSemanticAcceptedAtFallback() {
        val finished="2026-10-02T06:00:00.123456789Z";val payload="abc"
        val timing="reef-venue-batch-timing-v1\n$payload\n$finished"
        val checksum=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(timing.toByteArray()))
        val valid=JsonCodec.parseObject(JsonCodec.writeObject("payloadChecksum" to payload,"workFinishedAt" to finished,"timingChecksum" to checksum))
        assertEquals(1790920800123456789L,CalcifyVerifiedVisibilityObserver.matchingWorkFinishedNanos(valid))
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.matchingWorkFinishedNanos(JsonCodec.parseObject(JsonCodec.writeObject("payloadChecksum" to payload,"workFinishedAt" to finished,"timingChecksum" to "bad")))}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.matchingWorkFinishedNanos(JsonCodec.parseObject("{}"))}
    }
    @Test fun producerCreateTimeAllowsOnlyItsSubMillisecondTruncation() {
        assertEquals(-999999L,CalcifyVerifiedVisibilityObserver.producerWorkDeltaNanos(10,10999999))
        assertEquals(1000000L,CalcifyVerifiedVisibilityObserver.producerWorkDeltaNanos(11,10000000))
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.producerWorkDeltaNanos(10,11000000)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.producerWorkDeltaNanos(-1,0)}
    }
    @Test fun seededMakerBaselineRequiresExactAcceptedCommandsAndZeroTrades() {
        CalcifyVerifiedVisibilityObserver.validateBaseline(0,64,0,64,64,0,0)
        CalcifyVerifiedVisibilityObserver.validateBaseline(1,2,1,2,2,1,1)
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateBaseline(0,64,0,63,63,0,0)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateBaseline(0,64,0,64,63,0,0)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateBaseline(0,64,1,64,64,0,0)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateBaseline(0,64,0,64,64,1,0)}
    }
    @Test fun seededBaselineRejectsHiddenSourceAndAnyStageOutput() {
        val source=mapOf(0 to 65L);val zero=mapOf(0 to 0L)
        CalcifyVerifiedVisibilityObserver.validateZeroTradeEndpoints(source,source,zero,zero,zero,zero)
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateZeroTradeEndpoints(source,mapOf(0 to 66L),zero,zero,zero,zero)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateZeroTradeEndpoints(source,source,zero,mapOf(0 to 1L),zero,zero)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.validateZeroTradeEndpoints(source,source,zero,zero,mapOf(0 to 1L),zero)}
    }
    @Test fun batchEntryRequiresRealOrderedWallClockWithoutFallback() {
        val entry=JsonCodec.parseObject("{\"createdAt\":\"2026-10-02T06:00:00Z\"}")
        assertEquals(1790920800000000000L,CalcifyVerifiedVisibilityObserver.batchEntryNanos(entry,1790920800001000000L))
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.batchEntryNanos(entry,1790920799999999999L)}
        assertFailsWith<IllegalArgumentException> {CalcifyVerifiedVisibilityObserver.batchEntryNanos(JsonCodec.parseObject("{}"),1790920800000000000L)}
    }
}
