package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The paced-soak harness's pure parts (pacing schedule, sustained-lag rule, verdict, accounting, funding size, generator, CSV shape). No Docker. */
class CapacitySoakSupportTest {
    private val ms = 1_000_000L
    private val sec = 1_000_000_000L

    // ---- pacing ----

    @Test fun theScheduleOwesRateTimesElapsedTimeAndNothingBeforeTheStart() {
        val p = SchedulePacer(10_000, 5 * sec)
        assertEquals(0, p.due(5 * sec - 1)); assertEquals(1, p.due(5 * sec))
        assertEquals(10_001, p.due(6 * sec)); assertEquals(10_000L * 60 + 1, p.due(65 * sec))
        assertEquals(5 * sec, p.scheduledAt(0)); assertEquals(5 * sec + 100_000, p.scheduledAt(1))
    }

    @Test fun anOnTimeSenderWakingEveryMillisecondSendsAtMostAMillisecondOfTradesPerBatch() {
        val rate = 10_000L; val p = SchedulePacer(rate, 0); var sent = 0L; var biggest = 0
        var now = 0L
        while (now < 5 * sec) {
            val n = p.nextBatch(sent, now, 10_000); biggest = maxOf(biggest, n); sent += n
            now += ms          // the harness parks for at most a millisecond
        }
        assertTrue(biggest <= rate / 1000 + 1, "batch of $biggest trades is more than 1 ms of the schedule")
        assertEquals(p.due(now - ms) , sent, "an on-time sender is never behind")
        assertEquals(0, p.behindTrades(sent, now - ms))
    }

    @Test fun aSenderThatIsSlowerThanTheScheduleFallsBehindAndTheCatchUpIsCappedPerBatch() {
        // each trade takes 150 us to send against a 100 us interval: the lateness grows by a third of a second per second
        val p = SchedulePacer(10_000, 0); var sent = 0L; var now = 0L; var capped = true
        while (now < 10 * sec) {
            val n = p.nextBatch(sent, now, 50); if (n > 50) capped = false
            if (n == 0) { now += p.nanosUntilNext(sent, now).coerceAtLeast(1); continue }
            sent += n; now += n * 150_000L
        }
        assertTrue(capped)
        val behind = p.behindTrades(sent, now)
        assertTrue(behind > 30_000, "behind=$behind: a slow sender must show up as overdue trades")
        assertTrue(behind / 10_000.0 > 3.0, "lateness in seconds of rate is what the producer rule is fed")
        assertEquals(0, p.nextBatch(0, -1, 50), "nothing is due before the start")
    }

    @Test fun afterAStallTheSenderOwesEverythingItMissedAndNoMore() {
        val p = SchedulePacer(1_000, 0)
        assertEquals(1, p.nextBatch(0, 0, 100)); assertEquals(0, p.nextBatch(1, 0, 100))
        assertEquals(100, p.nextBatch(1, 2 * sec, 100), "capped")
        assertEquals(1_999L, p.behindTrades(2, 2 * sec), "2000 due by t=2s, 2 sent")
    }

    // ---- the lag rule ----

    @Test fun lagMustStayOverTheThresholdForTheWholeSustainPeriod() {
        val rule = SustainedLagRule(thresholdSeconds = 30.0, sustainSeconds = 60.0)
        for (t in 10..100 step 10) assertFalse(rule.observe(t.toDouble(), 29.9), "under the threshold at $t")
        assertFalse(rule.observe(110.0, 31.0)); assertFalse(rule.observe(120.0, 90.0)); assertFalse(rule.observe(160.0, 90.0)); assertFalse(rule.observe(169.9, 40.0))
        assertTrue(rule.observe(170.0, 35.0), "60 s after the first over-threshold sample")
        assertTrue(rule.observe(180.0, 0.0), "a breach stays a breach")
    }

    @Test fun oneSampleUnderTheThresholdResetsTheClock() {
        val rule = SustainedLagRule(30.0, 60.0)
        assertFalse(rule.observe(10.0, 50.0)); assertFalse(rule.observe(50.0, 50.0)); assertEquals(40.0, rule.overFor(50.0))
        assertFalse(rule.observe(60.0, 5.0)); assertEquals(0.0, rule.overFor(60.0))
        assertFalse(rule.observe(70.0, 50.0)); assertFalse(rule.observe(120.0, 50.0)); assertTrue(rule.observe(130.0, 50.0))
    }

    @Test fun aLagExactlyAtTheThresholdIsNotOver() {
        val rule = SustainedLagRule(30.0, 20.0)
        for (t in 0..200 step 10) assertFalse(rule.observe(t.toDouble(), 30.0))
    }

    @Test fun theImpossibleRateScenarioBreachesInTheProducerRuleNotTheLaneRule() {
        // 200,000/s target, a sender that manages 40,000/s and a lane that keeps up with what it is given: only the producer is behind
        val target = 200_000L; val sendRate = 40_000L
        val producer = SustainedLagRule(30.0, 60.0); val booking = SustainedLagRule(30.0, 60.0); val projection = SustainedLagRule(30.0, 60.0)
        var breachedAt = -1
        for (t in 10..300 step 10) {
            val sent = sendRate * t; val due = target * t
            val producerBreached = producer.observe(t.toDouble(), (due - sent) / target.toDouble())
            val bookingBreached = booking.observe(t.toDouble(), 0.4); val projectionBreached = projection.observe(t.toDouble(), 0.2)
            val verdict = SoakVerdicts.judge(producerBreached, bookingBreached, projectionBreached)
            if (verdict != SoakVerdict.OK && breachedAt < 0) { breachedAt = t; assertEquals(SoakVerdict.PRODUCER_BEHIND, verdict) }
        }
        assertTrue(breachedAt in 40..120, "caught after the lag (0.8 s of rate per second) crossed 30 s plus the sustain period; at $breachedAt")
    }

    @Test fun aProducerBreachWinsOverALaneBreachAndLaneBreachesAreNamedBySide() {
        assertEquals(SoakVerdict.OK, SoakVerdicts.judge(false, false, false))
        assertEquals(SoakVerdict.PRODUCER_BEHIND, SoakVerdicts.judge(true, true, true))
        assertEquals(SoakVerdict.LANE_BEHIND_BOOKING, SoakVerdicts.judge(false, true, true))
        assertEquals(SoakVerdict.LANE_BEHIND_PROJECTION, SoakVerdicts.judge(false, false, true))
    }

    // ---- accounting ----

    private val instruments = (0 until 8).map { "INS$it" }
    private val plan = CapacityPlan("soak-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), CapacityRouting.engineLanes("p3-run", "p3-session", instruments, 2), 1000, 1000, 2000, 1000, sequenceBound = 1_000_000L)

    @Test fun tradesAreDecisionsMinusFundingDividedByTheModesDecisionsPerTrade() {
        val funds = SoakAccounting.fundingDecisions(plan, 0, 10)
        assertEquals(10L * (1 + plan.laneInstruments[0].size), funds)
        assertEquals(0, SoakAccounting.trades(funds, funds, DecisionMode.CAPTURE_SETTLE)); assertEquals(0, SoakAccounting.trades(funds - 3, funds, DecisionMode.TWO_STEP))
        assertEquals(500, SoakAccounting.trades(funds + 500, funds, DecisionMode.CAPTURE_SETTLE))
        assertEquals(250, SoakAccounting.trades(funds + 500, funds, DecisionMode.TWO_STEP))
        assertEquals(250, SoakAccounting.trades(funds + 501, funds, DecisionMode.TWO_STEP), "a half-booked trade (capture decided, settle not yet) is not booked")
    }

    @Test fun fundingIsSizedFromThePlannedTotalAndNeverBelowTheThroughputTestsBalances() {
        val small = SoakFunding.sized(10_000, 200, 64, 0.0); assertEquals(1_000_000_000_000L, small.cashPerAccountPerLane); assertEquals(1_000_000L, small.sharesPerAccountPerInstrument)
        val soak = SoakFunding.sized(30_000_000, 200, 64, 0.0)
        assertTrue(soak.cashPerAccountPerLane >= 30_000_000L * 4 * 1_049, "enough to buy every trade of the soak at the top quantity and price")
        val hot = SoakFunding.sized(30_000_000, 200, 64, 1.0)
        assertTrue(hot.sharesPerAccountPerInstrument >= 6L * 30_000_000 / 200 * 4, "the hot instrument's busiest seller: ${hot.sharesPerAccountPerInstrument}")
        assertTrue(hot.sharesPerAccountPerInstrument > soak.sharesPerAccountPerInstrument)
    }

    // ---- generator ----

    private val accounts = (0 until 20).map { AccountId("u$it") }

    private fun gen(hot: Double = 0.0, resolved: Boolean = true, seed: Int = 3) = SoakTradeGenerator(plan, instruments, accounts, hot, resolved, seed)

    @Test fun theGeneratorIsLazyDeterministicAndRoutesEveryTradeToItsInstrumentsLane() {
        val a = gen(); val b = gen(); val c = gen(seed = 4)
        val ta = List(500) { a.next() }; val tb = List(500) { b.next() }; val tc = List(500) { c.next() }
        assertEquals(500, a.generated)
        assertTrue(ta.zip(tb).all { (x, y) -> x.lane == y.lane && x.bytes.contentEquals(y.bytes) }, "same seed, same stream")
        assertFalse(ta.zip(tc).all { (x, y) -> x.bytes.contentEquals(y.bytes) }, "different seed, different stream")
        assertEquals((0L until 500L).toList(), ta.map { it.index })
        val decoder = CapacityResolvedContextDecoder(plan)
        for (t in ta) {
            val items = decoder.decode("CAPTURE", t.lane, t.index, t.bytes)
            assertEquals(2, items.size, "decodes (two-step plan): a capture and its first settle, never a bad record")
            val capture = (items[0] as CapacityItem.Run).input as FinanceInput.Capture
            assertEquals(t.lane, plan.laneOf(capture.execution.instrumentId)); assertEquals("exec-${t.index}", capture.execution.executionId)
            assertTrue(capture.buyer != capture.seller); assertTrue(capture.quantity in 1L..SoakFunding.MAX_UNITS); assertTrue(capture.priceNanos in 1_000L..SoakFunding.MAX_PRICE_NANOS)
        }
    }

    @Test fun theHotShareSendsThatFractionOfTradesToTheFirstInstrument() {
        fun hotFraction(share: Double): Double { val g = gen(hot = share); val n = 20_000; var hot = 0
            repeat(n) { val bytes = g.next().bytes; if (reef.contracts.calcify.v1.MatchContextResolvedV1.parseFrom(bytes).trade.fact.instrumentId == "INS0") hot++ }
            return hot.toDouble() / n }
        assertEquals(1.0 / 8, hotFraction(0.0), 0.01)
        assertEquals(0.5 + 0.5 / 8, hotFraction(0.5), 0.02)
        assertEquals(1.0, hotFraction(1.0), 0.0)
    }

    @Test fun theLifecycleSourceGivesGenuineEnvelopesThatTheLifecycleDecoderAccepts() {
        val lifecyclePlan = CapacityPlan("soak-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), listOf(instruments.toSet()), 1000, 1000, 2000, 1000, sequenceBound = 1_000_000L)
        val g = SoakTradeGenerator(lifecyclePlan, instruments, accounts, 0.0, resolved = false)
        val t = g.next(); assertEquals(0, t.lane)
        val decoder = CapacityInputDecoders(lifecyclePlan, com.reef.platform.calcify.FiniteLifecycleFixtures.binding().digest, true)
        val items = decoder.decode("CAPTURE", 0, 0, t.bytes)
        assertTrue(items.isNotEmpty() && items.none { it is CapacityItem.Bad }, "$items")
    }

    // ---- CSV ----

    @Test fun everyRowHasExactlyTheColumnsOfTheHeaderAndBlankFieldsStayBlank() {
        val columns = SoakSample.HEADER.split(",").size
        val full = SoakSample("run", 10.0, 100_000, 99_990, 99_000, 98_000, 3, 10_000.0, 9_900.0, 9_800.0, 10_000.0, 9_900.0, 9_800.0, 512, 2, 14, 180.0, 95.0, 40.0, 25.0, 30.0,
            1000L, 400L, 500L, 100L, 800L, 100L, 4_000_000L, 12L, 90.5, 70.2, 33.3, true)
        val sparse = full.copy(rpTotalKib = null, rpCaptureKib = null, rpDecisionsKib = null, rpChangelogKib = null, pgDirKib = null, pgWalKib = null, pgDatabaseBytes = null, stateDirKib = null, rpGrowthKibS = null, pgGrowthKibS = null, hostDiskWriteMibS = null)
        assertEquals(columns, full.csvRow(10_000).split(",").size); assertEquals(columns, sparse.csvRow(10_000).split(",").size)
        val row = full.csvRow(10_000).split(",").let { SoakSample.HEADER.split(",").zip(it).toMap() }
        assertEquals("1000", row["lag_sent_booked"]); assertEquals("1000", row["lag_booked_projected"]); assertEquals("0.1", row["lag_sent_booked_s"]); assertEquals("1", row["healthy"])
        assertEquals("", sparse.csvRow(10_000).split(",").let { SoakSample.HEADER.split(",").zip(it).toMap() }["rp_total_kib"])
    }
}
