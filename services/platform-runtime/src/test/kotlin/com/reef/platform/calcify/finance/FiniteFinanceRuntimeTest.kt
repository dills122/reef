package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteBindingContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The environment contract of the finite finance process role: what it accepts, and every way it must refuse to start. No broker or database is contacted. */
class FiniteFinanceRuntimeTest {
    private val fixture = Path.of("../../contracts/calcify/finite-binding-v2.json")
    private val digest = FiniteBindingContract.parseOpeningV2(Files.readAllBytes(fixture)).digest

    private fun private(dir: Path, name: String, content: ByteArray, perms: String = "rw-------"): Path =
        Files.write(dir.resolve(name), content).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(perms)) }

    /** A complete valid environment; [edit] mutates it. */
    private fun env(edit: (MutableMap<String, String>, Path) -> Unit = { _, _ -> }): Map<String, String> {
        val dir = Files.createTempDirectory("finance-runtime-env")
        val state = Files.createDirectory(dir.resolve("state")).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        val m = mutableMapOf(
            "CALCIFY_FINANCE_BINDING_DIGEST" to digest,
            "CALCIFY_FINANCE_OPENING_FILE" to private(dir, "opening.json", Files.readAllBytes(fixture)).toString(),
            "CALCIFY_FINANCE_KAFKA_BOOTSTRAP_SERVERS" to "127.0.0.1:9092",
            "CALCIFY_FINANCE_KAFKA_USERNAME" to "calcify_finite_finance",
            "CALCIFY_FINANCE_KAFKA_PASSWORD_FILE" to private(dir, "finance.pw", "finance-secret\n".toByteArray()).toString(),
            "CALCIFY_FINANCE_OBSERVER_KAFKA_USERNAME" to "calcify_finite_observer",
            "CALCIFY_FINANCE_OBSERVER_KAFKA_PASSWORD_FILE" to private(dir, "observer.pw", "observer-secret".toByteArray()).toString(),
            "CALCIFY_FINANCE_POSTGRES_JDBC_URL" to "jdbc:postgresql://127.0.0.1:5432/reef",
            "CALCIFY_FINANCE_POSTGRES_USER" to "calcify_finite_projector",
            "CALCIFY_FINANCE_POSTGRES_PASSWORD_FILE" to private(dir, "projector.pw", "projector-secret".toByteArray()).toString(),
            "CALCIFY_FINANCE_STATE_DIRECTORY" to state.toString(),
            "CALCIFY_FINANCE_OWNER_EPOCH" to "1",
        )
        edit(m, dir)
        return m
    }

    private fun create(m: Map<String, String>) = FiniteFinanceRuntime.create(m::get)
    private fun refused(message: String, edit: (MutableMap<String, String>, Path) -> Unit) {
        val e = assertFailsWith<RuntimeException>(message) { create(env(edit)) }
        assertTrue(e is IllegalStateException || e is IllegalArgumentException, "$message: unexpected ${e::class}")
    }

    @Test fun aCompleteEnvironmentBuildsALauncherWithoutTouchingTheBrokerOrDatabase() {
        assertNotNull(create(env()))
        assertNotNull(create(env { m, _ -> m["CALCIFY_FINANCE_SESSION_TIMEOUT_MS"] = "6000"; m["CALCIFY_FINANCE_PROJECTION_INTERVAL_MS"] = "250"; m["CALCIFY_FINANCE_KAFKA_SASL_MECHANISM"] = "SCRAM-SHA-256" }))
    }

    @Test fun everyMissingRequiredSettingIsRefused() {
        for (key in env().keys) refused("missing $key") { m, _ -> m.remove(key) }
    }

    @Test fun anInlineSecretIsRefusedEvenWithTheFileSet() {
        for (name in listOf("CALCIFY_FINANCE_KAFKA_PASSWORD", "CALCIFY_FINANCE_OBSERVER_KAFKA_PASSWORD", "CALCIFY_FINANCE_POSTGRES_PASSWORD")) refused("inline $name") { m, _ -> m[name] = "plaintext" }
    }

    @Test fun credentialAndOpeningFilesMustBePrivateRegularFiles() {
        refused("group-readable credential") { m, dir -> m["CALCIFY_FINANCE_KAFKA_PASSWORD_FILE"] = private(dir, "loose.pw", "x".toByteArray(), "rw-r-----").toString() }
        refused("world-readable opening") { m, dir -> m["CALCIFY_FINANCE_OPENING_FILE"] = private(dir, "loose.json", Files.readAllBytes(fixture), "rw-rw-rw-").toString() }
        refused("empty credential") { m, dir -> m["CALCIFY_FINANCE_POSTGRES_PASSWORD_FILE"] = private(dir, "empty.pw", "\n".toByteArray()).toString() }
        refused("symlinked credential") { m, dir ->
            val real = private(dir, "real.pw", "x".toByteArray()); m["CALCIFY_FINANCE_KAFKA_PASSWORD_FILE"] = Files.createSymbolicLink(dir.resolve("link.pw"), real).toString()
        }
    }

    @Test fun theOpeningMustMatchTheDigestAndBeAValidV2Opening() {
        refused("digest mismatch") { m, _ -> m["CALCIFY_FINANCE_BINDING_DIGEST"] = "0".repeat(64) }
        refused("digest shape") { m, _ -> m["CALCIFY_FINANCE_BINDING_DIGEST"] = digest.uppercase() }
        refused("a v1 opening is not a finance binding") { m, dir -> m["CALCIFY_FINANCE_OPENING_FILE"] = private(dir, "v1.json", Files.readAllBytes(Path.of("../../contracts/calcify/finite-binding-v1.json"))).toString() }
        refused("garbage opening") { m, dir -> m["CALCIFY_FINANCE_OPENING_FILE"] = private(dir, "bad.json", "{}".toByteArray()).toString() }
    }

    @Test fun financeAndObserverMustBeDifferentPrincipals() {
        refused("same principal") { m, _ -> m["CALCIFY_FINANCE_OBSERVER_KAFKA_USERNAME"] = m.getValue("CALCIFY_FINANCE_KAFKA_USERNAME") }
    }

    @Test fun numericAndEnumSettingsAreRangeChecked() {
        refused("epoch zero") { m, _ -> m["CALCIFY_FINANCE_OWNER_EPOCH"] = "0" }
        refused("epoch text") { m, _ -> m["CALCIFY_FINANCE_OWNER_EPOCH"] = "one" }
        refused("session below the floor") { m, _ -> m["CALCIFY_FINANCE_SESSION_TIMEOUT_MS"] = "1000" }
        refused("session above the default") { m, _ -> m["CALCIFY_FINANCE_SESSION_TIMEOUT_MS"] = "60000" }
        refused("interval too small") { m, _ -> m["CALCIFY_FINANCE_PROJECTION_INTERVAL_MS"] = "1" }
        refused("plaintext protocol") { m, _ -> m["CALCIFY_FINANCE_KAFKA_SECURITY_PROTOCOL"] = "PLAINTEXT" }
        refused("unknown mechanism") { m, _ -> m["CALCIFY_FINANCE_KAFKA_SASL_MECHANISM"] = "GSSAPI" }
        refused("state dir readable by others") { m, dir -> m["CALCIFY_FINANCE_STATE_DIRECTORY"] = Files.createDirectory(dir.resolve("open-state")).also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x")) }.toString() }
        refused("password in the JDBC URL") { m, _ -> m["CALCIFY_FINANCE_POSTGRES_JDBC_URL"] = "jdbc:postgresql://127.0.0.1:5432/reef?user=x&Password=secret" }
        refused("state dir is a file") { m, dir -> m["CALCIFY_FINANCE_STATE_DIRECTORY"] = private(dir, "notadir", ByteArray(1)).toString() }
        refused("state dir is a link") { m, dir -> m["CALCIFY_FINANCE_STATE_DIRECTORY"] = Files.createSymbolicLink(dir.resolve("statelink"), Path.of(m.getValue("CALCIFY_FINANCE_STATE_DIRECTORY"))).toString() }
    }

    @Test fun saslClientPropertiesMatchTheMechanismAndEscapeCredentials() {
        val plain = FiniteFinanceRuntime.clientProperties("b:9092", "SASL_PLAINTEXT", "PLAIN", "u", "p\"w\\x")
        assertEquals("PLAIN", plain["sasl.mechanism"]); assertEquals("SASL_PLAINTEXT", plain["security.protocol"])
        assertEquals("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"u\" password=\"p\\\"w\\\\x\";", plain["sasl.jaas.config"])
        val scram = FiniteFinanceRuntime.clientProperties("b:9092", "SASL_SSL", "SCRAM-SHA-512", "u", "pw")
        assertEquals("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"u\" password=\"pw\";", scram["sasl.jaas.config"])
        assertEquals("https", scram["ssl.endpoint.identification.algorithm"])
        assertFailsWith<IllegalArgumentException> { FiniteFinanceRuntime.clientProperties("b:9092", "SASL_PLAINTEXT", "PLAIN", "", "pw") }
    }

    @Test fun theFinanceRoleRefusesToShareAProcessWithIngressOrAStage() {
        FiniteFinanceRuntime.requireExclusive { null }
        FiniteFinanceRuntime.requireExclusive { if (it == "CALCIFY_FINANCE_BINDING_DIGEST") digest else "" }
        assertFailsWith<IllegalArgumentException> { FiniteFinanceRuntime.requireExclusive { if (it == "CALCIFY_STAGE") "resolver" else null } }
        assertFailsWith<IllegalArgumentException> { FiniteFinanceRuntime.requireExclusive { if (it == "CALCIFY_FINITE_BINDING_DIGEST") digest else null } }
    }
}
