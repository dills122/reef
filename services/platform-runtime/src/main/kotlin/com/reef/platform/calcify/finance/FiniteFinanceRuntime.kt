package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteBindingContract
import com.reef.platform.calcify.FiniteRuntime
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.streams.StreamsConfig
import org.postgresql.ds.PGSimpleDataSource

/**
 * The opt-in finite P3 FINANCE process role (selected by `CALCIFY_FINANCE_BINDING_DIGEST`, never together with the finite ingress role or a
 * `CALCIFY_STAGE`). It builds a [FiniteFinanceLauncher] from the environment and runs it, so the launcher has a production caller.
 *
 * Least privilege by construction: this JVM holds two broker credentials (the `finance` Streams principal and the read-only `observer`
 * principal used by the restore-cut evidence reader) and one database credential (the projector role). It holds no registrar, runtime,
 * control-writer or reader credential. The registered v2 opening is read from a private file, never from the registrar tables.
 * Secrets are read from `*_FILE` paths only (private regular files); an inline secret in the environment is refused.
 * The owner epoch is supplied by the operator (see docs/work/CALCIFY_FINITE_P3_FINANCE_RUNTIME.md): it is NOT yet derived from the
 * ingress owner claim, which is a recorded open item.
 */
internal object FiniteFinanceRuntime {
    private val digestShape = Regex("[0-9a-f]{64}")
    private val mechanisms = mapOf(
        "PLAIN" to "org.apache.kafka.common.security.plain.PlainLoginModule",
        "SCRAM-SHA-256" to "org.apache.kafka.common.security.scram.ScramLoginModule",
        "SCRAM-SHA-512" to "org.apache.kafka.common.security.scram.ScramLoginModule",
    )

    /** SASL client properties for one principal. Only security settings and the bootstrap list: timeouts stay the clients' own defaults. */
    internal fun clientProperties(bootstrap: String, protocol: String, mechanism: String, username: String, password: String): Properties {
        require(protocol in listOf("SASL_PLAINTEXT", "SASL_SSL")) { "finance Kafka security protocol must be SASL_PLAINTEXT or SASL_SSL" }
        val module = requireNotNull(mechanisms[mechanism]) { "finance Kafka SASL mechanism must be one of ${mechanisms.keys}" }
        require(bootstrap.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { "finance Kafka bootstrap, username and password are required" }
        fun quoted(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return Properties().apply {
            put("bootstrap.servers", bootstrap); put("security.protocol", protocol); put("sasl.mechanism", mechanism)
            put("sasl.jaas.config", "$module required username=\"${quoted(username)}\" password=\"${quoted(password)}\";")
            if (protocol == "SASL_SSL") put("ssl.endpoint.identification.algorithm", "https")
        }
    }

    /** Builds (does not start) the launcher. Reads files and the environment only: no broker or database connection is opened here. */
    fun create(lookup: (String) -> String? = System::getenv): FiniteFinanceLauncher {
        fun required(key: String) = lookup(key)?.takeIf { it.isNotBlank() } ?: error("missing $key")
        fun secret(name: String): String {
            require(lookup(name).isNullOrBlank()) { "inline finance credential forbidden: $name" }
            return FiniteRuntime.privateBytes(Path.of(required(name + "_FILE")), 8192).toString(Charsets.UTF_8).trimEnd('\n', '\r')
                .also { require(it.isNotBlank()) { "empty finance credential: $name" } }
        }
        val digest = required("CALCIFY_FINANCE_BINDING_DIGEST")
        require(digestShape.matches(digest)) { "CALCIFY_FINANCE_BINDING_DIGEST must be 64 lowercase hex characters" }
        val opening = FiniteBindingContract.parseOpeningV2(FiniteRuntime.privateBytes(Path.of(required("CALCIFY_FINANCE_OPENING_FILE")), 16384))
        require(opening.digest == digest) { "the registered v2 opening's digest differs from CALCIFY_FINANCE_BINDING_DIGEST" }
        val config = FinanceProcessorConfig.fromOpening(opening)

        val bootstrap = required("CALCIFY_FINANCE_KAFKA_BOOTSTRAP_SERVERS")
        val protocol = lookup("CALCIFY_FINANCE_KAFKA_SECURITY_PROTOCOL")?.takeIf { it.isNotBlank() } ?: "SASL_PLAINTEXT"
        val mechanism = lookup("CALCIFY_FINANCE_KAFKA_SASL_MECHANISM")?.takeIf { it.isNotBlank() } ?: "PLAIN"
        val financeUser = required("CALCIFY_FINANCE_KAFKA_USERNAME")
        val observerUser = required("CALCIFY_FINANCE_OBSERVER_KAFKA_USERNAME")
        require(financeUser != observerUser) { "the finance and observer principals must differ" }
        val streamsProps = clientProperties(bootstrap, protocol, mechanism, financeUser, secret("CALCIFY_FINANCE_KAFKA_PASSWORD"))
        val evidenceProps = clientProperties(bootstrap, protocol, mechanism, observerUser, secret("CALCIFY_FINANCE_OBSERVER_KAFKA_PASSWORD"))
        lookup("CALCIFY_FINANCE_SESSION_TIMEOUT_MS")?.takeIf { it.isNotBlank() }?.let { raw ->
            // Optional: a lower session timeout shortens crash recovery (measured 44 s at the 45 s default, 6 s at 6,000). Unset keeps the default.
            val session = raw.toIntOrNull()?.takeIf { it in 6_000..45_000 } ?: error("CALCIFY_FINANCE_SESSION_TIMEOUT_MS must be an integer in 6000..45000")
            streamsProps[StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)] = session
            streamsProps[StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)] = maxOf(1_000, session / 6)
        }

        val stateDir = Path.of(required("CALCIFY_FINANCE_STATE_DIRECTORY"))
        require(Files.isDirectory(stateDir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(stateDir)) { "CALCIFY_FINANCE_STATE_DIRECTORY must be a private directory, not a link" }
        val epoch = required("CALCIFY_FINANCE_OWNER_EPOCH").toLongOrNull()?.takeIf { it > 0 } ?: error("CALCIFY_FINANCE_OWNER_EPOCH must be a positive integer")
        val interval = lookup("CALCIFY_FINANCE_PROJECTION_INTERVAL_MS")?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.toLongOrNull()?.takeIf { it in 50..10_000 } ?: error("CALCIFY_FINANCE_PROJECTION_INTERVAL_MS must be an integer in 50..10000")
        } ?: 500L

        val dataSource = PGSimpleDataSource().apply {
            setURL(required("CALCIFY_FINANCE_POSTGRES_JDBC_URL")); user = required("CALCIFY_FINANCE_POSTGRES_USER")
            password = secret("CALCIFY_FINANCE_POSTGRES_PASSWORD"); connectTimeout = 5; socketTimeout = 30
        }
        return FiniteFinanceLauncher(config, evidenceProps, streamsProps, stateDir.toString(), { FinanceProjector(FinanceLedger(config.finance), dataSource) }, epoch, interval)
    }

    /**
     * Starts the launcher and supervises it. Fail closed: if the restore cut is refused, start throws and the process exits non-zero with
     * nothing started; if the app or the projection later dies the process closes and exits 1, so the supervisor restarts it and the
     * restore cut runs again.
     */
    fun run() {
        // The hook exists before start(), so a SIGTERM while the app is starting or just after it has started still closes (and leaves the group).
        val started = java.util.concurrent.atomic.AtomicReference<FiniteFinanceLauncher.Running?>(null)
        Runtime.getRuntime().addShutdownHook(Thread { started.get()?.let { runCatching { it.close() } } })
        val running = create().start().also { started.set(it) }
        try { while (running.healthy()) Thread.sleep(1_000) } catch (_: InterruptedException) { runCatching { running.close() }; return }
        System.err.println("finance runtime unhealthy: streams=${running.streamsState()} projection=${running.projectionFailure()}")
        runCatching { running.close() }
        kotlin.system.exitProcess(1)
    }
}
