package com.reef.arena.controlplane.arena

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArenaAccountRiskExtensionProviderTest {
    @Test
    fun disabledValuesDoNotCreateDatabaseBackedExtensions() {
        val provider = ArenaAccountRiskExtensionProvider()

        listOf(null, "", "false", "0", "no", "off", "unexpected").forEach { enabled ->
            val extensions = provider.extensions { name ->
                if (name == "EXTERNAL_API_ARENA_BOT_VERSION_RISK_ENABLED") enabled else error("unexpected lookup: $name")
            }
            assertTrue(extensions.isEmpty(), "expected disabled value '$enabled' to produce no extensions")
        }
    }

    @Test
    fun enabledExtensionRequiresArenaDatabaseUrlBeforeConstruction() {
        val provider = ArenaAccountRiskExtensionProvider()

        listOf("true", "1", "yes", "on", " TRUE ").forEach { enabled ->
            val error = assertFailsWith<IllegalStateException> {
                provider.extensions { name ->
                    when (name) {
                        "EXTERNAL_API_ARENA_BOT_VERSION_RISK_ENABLED" -> enabled
                        "ARENA_POSTGRES_JDBC_URL" -> ""
                        else -> null
                    }
                }
            }
            assertTrue(error.message!!.contains("ARENA_POSTGRES_JDBC_URL is required"))
        }
    }
}
