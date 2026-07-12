package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformAdminDataRoutesTest {
    @Test
    fun queryValueDecodesUrlEncodedKeysAndValues() {
        assertEquals("run 1+2", queryValue("runId=run+1%2B2", "runId"))
        assertEquals("value", queryValue("encoded%20key=value", "encoded key"))
    }

    @Test
    fun queryValueKeepsMalformedPercentEncodingRaw() {
        assertEquals("bad%zz", queryValue("runId=bad%zz", "runId"))
    }
}
