package com.reef.platform.infrastructure.config

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeEnvTest {
    @Test
    fun stringUsesFallbackForMissingOrBlankValues() {
        val values = mapOf("PRESENT" to "value", "BLANK" to " ")

        assertEquals("value", RuntimeEnv.string("PRESENT", "fallback", values::get))
        assertEquals("fallback", RuntimeEnv.string("MISSING", "fallback", values::get))
        assertEquals("fallback", RuntimeEnv.string("BLANK", "fallback", values::get))
    }

    @Test
    fun intUsesFallbackForInvalidValuesAndAppliesMinimum() {
        val values = mapOf("VALID" to "12", "INVALID" to "abc", "LOW" to "2")

        assertEquals(12, RuntimeEnv.int("VALID", 4, lookup = values::get))
        assertEquals(4, RuntimeEnv.int("INVALID", 4, lookup = values::get))
        assertEquals(4, RuntimeEnv.int("MISSING", 4, lookup = values::get))
        assertEquals(8, RuntimeEnv.int("LOW", 4, min = 8, lookup = values::get))
    }

    @Test
    fun longUsesFallbackForInvalidValuesAndAppliesMinimum() {
        val values = mapOf("VALID" to "1200", "INVALID" to "abc", "LOW" to "2")

        assertEquals(1200L, RuntimeEnv.long("VALID", 400L, lookup = values::get))
        assertEquals(400L, RuntimeEnv.long("INVALID", 400L, lookup = values::get))
        assertEquals(400L, RuntimeEnv.long("MISSING", 400L, lookup = values::get))
        assertEquals(800L, RuntimeEnv.long("LOW", 400L, min = 800L, lookup = values::get))
    }
}
