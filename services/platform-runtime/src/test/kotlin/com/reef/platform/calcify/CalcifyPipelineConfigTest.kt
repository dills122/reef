package com.reef.platform.calcify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalcifyPipelineConfigTest {
    @Test
    fun unsetLimitsPreserveHundredRecordPolls() {
        for (stage in listOf("extractor", "verifier", "receipt")) {
            assertEquals(100, CalcifyPipeline.maxPollRecords(stage) { null })
        }
    }

    @Test
    fun limitsApplyOnlyToTheirOwnStageAndAcceptBounds() {
        for ((stage, key) in listOf(
            "verifier" to "CALCIFY_VERIFIER_MAX_POLL_RECORDS",
            "receipt" to "CALCIFY_RECEIPT_MAX_POLL_RECORDS",
        )) {
            for (limit in listOf(1, 500, 1000)) {
                assertEquals(limit, CalcifyPipeline.maxPollRecords(stage) {
                    if (it == key) limit.toString() else "invalid unrelated setting"
                })
            }
        }
        assertEquals(100, CalcifyPipeline.maxPollRecords("extractor") { error("extractor must not read stage limits") })
    }

    @Test
    fun explicitMalformedOrOutOfRangeLimitsFailBeforeStartup() {
        for (stage in listOf("verifier", "receipt")) {
            for (value in listOf("0", "-1", "1001", "2147483648", "abc", "", " ")) {
                assertFailsWith<IllegalArgumentException>("stage=$stage value=$value") {
                    CalcifyPipeline.maxPollRecords(stage) { value }
                }
            }
        }
    }
}
