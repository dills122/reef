package com.reef.platform.calcify.finance

import com.fasterxml.jackson.databind.JsonNode
import com.reef.platform.calcify.FiniteBindingContract
import com.reef.platform.calcify.FiniteRuntime
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.streams.StreamsConfig
import org.postgresql.ds.PGSimpleDataSource

/** A parsed `calcify-capacity-plan-v1` file: the plan, the decoder for its capture source, the topics and the application id. */
internal data class CapacityDeployment(val plan: CapacityPlan, val decoder: CapacityInputDecoder, val topics: CapacityTopics, val applicationId: String)

/**
 * The opt-in capacity FINANCE process role (selected by `CALCIFY_CAPACITY_PLAN_FILE`, never together with the finite roles or a `CALCIFY_STAGE`). It builds a
 * [CapacityLauncher] from the environment and runs it. Same least-privilege shape as [FiniteFinanceRuntime]: two broker credentials (the `finance` Streams principal
 * and the read-only `observer` used for the decision-log heads and the projection tails) and one database credential (the projector role); secrets only from
 * private `*_FILE` paths; the plan from a private file. Binding v3 will replace the plan file with a registered, signed opening; until then the plan file is the
 * operator's statement of the deployment and its digest is the lane bindings' identity.
 *
 * Plan file (strict JSON, every field required, numbers spelled as decimal text as in the finite contract):
 * `{"schema":"calcify-capacity-plan-v1","planId","run","session","cashAsset","openingAccount","laneRouting":"stream-command-partition-v1"|"explicit","laneInstruments":[["A","B"],["C"]],
 *   "maxObligations","maxCaptures","maxSettleAttempts","maxControls","sequenceBound","autoSettle":true,"applicationId","topics":{"capture","control","decisions"},
 *   "captureSource":{"kind":"resolved-context-v1","policyVersion":"1"} | {"kind":"finite-lifecycle-capture-v1","bindingDigest":"<64 hex>","verifyContentDigest":true}}`
 * `stream-command-partition-v1` makes the file refuse any instrument assignment that is not the platform's stream-command routing (run, session, instrument hash), which is required for the production
 * source (resolved match contexts arrive on the venue source partition = the lane).
 */
internal object CapacityRuntime {
    private val digestShape = Regex("[0-9a-f]{64}")

    fun parsePlan(bytes: ByteArray): CapacityDeployment {
        val root = FiniteBindingContract.strictObject(bytes, 65_536)
        FiniteBindingContract.fields(root, listOf("schema", "planId", "run", "session", "cashAsset", "openingAccount", "laneInstruments", "maxObligations", "maxCaptures", "maxSettleAttempts",
            "maxControls", "sequenceBound", "autoSettle", "laneRouting", "captureSource", "applicationId", "topics"))
        require(FiniteBindingContract.text(root, "schema") == "calcify-capacity-plan-v1") { "unknown capacity plan schema" }
        fun t(k: String) = FiniteBindingContract.text(root, k, 128)
        fun n(k: String, max: Long) = FiniteBindingContract.number(root, k, max)
        val lanes: JsonNode = root["laneInstruments"]
        require(lanes.isArray && lanes.size() in 1..256) { "laneInstruments must be 1 to 256 lanes" }
        val laneSets = lanes.map { lane ->
            require(lane.isArray && lane.size() in 1..4096 && lane.all { it.isTextual }) { "each lane lists 1 to 4096 instrument ids" }
            lane.map { AssetId(it.textValue()).value }.toSet()
                .also { require(it.size == lane.size()) { "an instrument is listed twice in a lane" } }
        }
        require(root["autoSettle"].isBoolean) { "autoSettle must be a boolean" }
        val topicsNode = root["topics"]; FiniteBindingContract.fields(topicsNode, listOf("capture", "control", "decisions"))
        val topics = CapacityTopics(FiniteBindingContract.text(topicsNode, "capture", 128), FiniteBindingContract.text(topicsNode, "control", 128), FiniteBindingContract.text(topicsNode, "decisions", 128))
        require(setOf(topics.capture, topics.control, topics.decisions).size == 3) { "the three topics must differ" }
        val plan = CapacityPlan(t("planId"), t("run"), t("session"), AssetId(t("cashAsset")), AccountId(t("openingAccount")), laneSets,
            n("maxObligations", Int.MAX_VALUE.toLong()).toInt(), n("maxCaptures", Int.MAX_VALUE.toLong()).toInt(), n("maxSettleAttempts", Int.MAX_VALUE.toLong()).toInt(), n("maxControls", Int.MAX_VALUE.toLong()).toInt(),
            root["autoSettle"].booleanValue(), n("sequenceBound", FinanceProjector.MAX_SEQUENCE_BOUND))
        val routing = t("laneRouting"); require(routing == CapacityRouting.EXPLICIT || routing == CapacityRouting.ENGINE_PARTITIONS) { "unknown laneRouting" }
        if (routing == CapacityRouting.ENGINE_PARTITIONS) plan.requireEngineAligned()
        val source = root["captureSource"]; require(source != null && source.isObject) { "captureSource must be an object" }
        val decoder: CapacityInputDecoder = when (FiniteBindingContract.text(source, "kind", 64)) {
            "resolved-context-v1" -> {
                FiniteBindingContract.fields(source, listOf("kind", "policyVersion"))
                require(FiniteBindingContract.number(source, "policyVersion", 1_000) == CapacityResolvedContextDecoder.POLICY_VERSION.toLong()) { "unsupported resolved-context policyVersion" }
                require(routing == CapacityRouting.ENGINE_PARTITIONS) { "resolved contexts arrive on the engine's partitions: laneRouting must be stream-command-partition-v1" }
                CapacityResolvedContextDecoder(plan)
            }
            "finite-lifecycle-capture-v1" -> {
                FiniteBindingContract.fields(source, listOf("kind", "bindingDigest", "verifyContentDigest"))
                val digest = FiniteBindingContract.text(source, "bindingDigest", 64); require(digestShape.matches(digest)) { "bindingDigest must be 64 lowercase hex characters" }
                require(source["verifyContentDigest"].isBoolean) { "verifyContentDigest must be a boolean" }
                CapacityInputDecoders(plan, digest, source["verifyContentDigest"].booleanValue())
            }
            else -> throw IllegalArgumentException("unknown captureSource kind")
        }
        return CapacityDeployment(plan, decoder, topics, t("applicationId"))
    }

    /**
     * Kafka Streams widens the permissions of its own state directory to group read and execute the first time it runs, so the configured directory itself would fail the
     * owner-only check on every later start. Streams gets a subdirectory instead; the configured parent stays owner-only (0700), which keeps group and others from
     * traversing into the ledger whatever the permissions below it are. Found by restarting the service on a real stack.
     */
    internal fun streamsStateDirectory(configured: Path): Path = configured.resolve("streams")

    /** Builds (does not start) the launcher. Reads files and the environment only: no broker or database connection is opened here. */
    fun create(lookup: (String) -> String? = System::getenv): CapacityLauncher {
        fun required(key: String) = lookup(key)?.takeIf { it.isNotBlank() } ?: error("missing $key")
        fun secret(name: String): String {
            require(lookup(name).isNullOrBlank()) { "inline capacity credential forbidden: $name" }
            return FiniteRuntime.privateBytes(Path.of(required(name + "_FILE")), 8192).toString(Charsets.UTF_8).trimEnd('\n', '\r').also { require(it.isNotBlank()) { "empty capacity credential: $name" } }
        }
        val deployment = parsePlan(FiniteRuntime.privateBytes(Path.of(required("CALCIFY_CAPACITY_PLAN_FILE")), 65_536))
        val bootstrap = required("CALCIFY_CAPACITY_KAFKA_BOOTSTRAP_SERVERS")
        val protocol = lookup("CALCIFY_CAPACITY_KAFKA_SECURITY_PROTOCOL")?.takeIf { it.isNotBlank() } ?: "SASL_PLAINTEXT"
        val streamsProps: Properties; val evidenceProps: Properties
        if (protocol == "PLAINTEXT") {
            // Local development stacks only (the compose Redpanda has no authentication). Explicit and separate from the protocol so it cannot be selected by accident.
            require(lookup("CALCIFY_CAPACITY_DEV_PLAINTEXT") == "true") { "PLAINTEXT Kafka needs CALCIFY_CAPACITY_DEV_PLAINTEXT=true (local development stacks only)" }
            streamsProps = Properties().apply { put("bootstrap.servers", bootstrap) }; evidenceProps = Properties().apply { put("bootstrap.servers", bootstrap) }
        } else {
            val mechanism = lookup("CALCIFY_CAPACITY_KAFKA_SASL_MECHANISM")?.takeIf { it.isNotBlank() } ?: "PLAIN"
            val financeUser = required("CALCIFY_CAPACITY_KAFKA_USERNAME"); val observerUser = required("CALCIFY_CAPACITY_OBSERVER_KAFKA_USERNAME")
            require(financeUser != observerUser) { "the finance and observer principals must differ" }
            streamsProps = FiniteFinanceRuntime.clientProperties(bootstrap, protocol, mechanism, financeUser, secret("CALCIFY_CAPACITY_KAFKA_PASSWORD"))
            evidenceProps = FiniteFinanceRuntime.clientProperties(bootstrap, protocol, mechanism, observerUser, secret("CALCIFY_CAPACITY_OBSERVER_KAFKA_PASSWORD"))
        }
        lookup("CALCIFY_CAPACITY_SESSION_TIMEOUT_MS")?.takeIf { it.isNotBlank() }?.let { raw ->
            val session = raw.toIntOrNull()?.takeIf { it in 6_000..45_000 } ?: error("CALCIFY_CAPACITY_SESSION_TIMEOUT_MS must be an integer in 6000..45000")
            streamsProps[StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)] = session
            streamsProps[StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)] = maxOf(1_000, session / 6)
        }
        val stateDir = Path.of(required("CALCIFY_CAPACITY_STATE_DIRECTORY"))
        require(Files.isDirectory(stateDir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(stateDir)) { "CALCIFY_CAPACITY_STATE_DIRECTORY must be a private directory, not a link" }
        require(Files.getPosixFilePermissions(stateDir, LinkOption.NOFOLLOW_LINKS).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "CALCIFY_CAPACITY_STATE_DIRECTORY must be owner-only (the state holds the full ledger)" }
        fun ranged(key: String, default: Long, range: LongRange) = lookup(key)?.takeIf { it.isNotBlank() }?.let { raw -> raw.toLongOrNull()?.takeIf { it in range } ?: error("$key must be an integer in $range") } ?: default
        val interval = ranged("CALCIFY_CAPACITY_PROJECTION_INTERVAL_MS", 100L, 20L..10_000L)
        val maxApply = ranged("CALCIFY_CAPACITY_PROJECTION_CHUNK", 2_000L, 100L..50_000L).toInt()
        val threads = ranged("CALCIFY_CAPACITY_STREAM_THREADS", deployment.plan.lanes.toLong(), 1L..deployment.plan.lanes.toLong()).toInt()
        val jdbcUrl = required("CALCIFY_CAPACITY_POSTGRES_JDBC_URL")
        require(!Regex("(?i)[?&;]password=").containsMatchIn(jdbcUrl)) { "CALCIFY_CAPACITY_POSTGRES_JDBC_URL must not carry a password; use CALCIFY_CAPACITY_POSTGRES_PASSWORD_FILE" }
        val dataSource = PGSimpleDataSource().apply {
            setURL(jdbcUrl); user = required("CALCIFY_CAPACITY_POSTGRES_USER"); password = secret("CALCIFY_CAPACITY_POSTGRES_PASSWORD")
            connectTimeout = 5; socketTimeout = 120; reWriteBatchedInserts = true
        }
        return CapacityLauncher(deployment.plan, deployment.decoder, deployment.topics, deployment.applicationId, evidenceProps, streamsProps, streamsStateDirectory(stateDir).toString(), dataSource,
            threads = threads, projectionIntervalMs = interval, maxApply = maxApply)
    }

    /** The capacity role is its own process: it must not share a JVM (or credentials) with the finite roles or a Calcify stage. */
    internal fun requireExclusive(lookup: (String) -> String?) =
        require(listOf("CALCIFY_STAGE", "CALCIFY_FINITE_BINDING_DIGEST", "CALCIFY_FINANCE_BINDING_DIGEST").all { lookup(it).isNullOrBlank() }) {
            "CALCIFY_CAPACITY_PLAN_FILE must not be set with CALCIFY_STAGE, CALCIFY_FINITE_BINDING_DIGEST or CALCIFY_FINANCE_BINDING_DIGEST"
        }

    /** Blocks while [running] is healthy. True if it stopped because it was closed (a clean stop); false if it became unhealthy on its own, after calling [onUnhealthy] and closing it. */
    internal fun supervise(running: CapacityLauncher.Running, pollMs: Long = 1_000, onUnhealthy: (String) -> Unit): Boolean {
        try { while (running.healthy()) Thread.sleep(pollMs) } catch (_: InterruptedException) { runCatching { running.close() }; return true }
        if (running.isClosing()) return true
        onUnhealthy("capacity runtime unhealthy: streams=${running.streamsState()} projection=${running.projectionFailure()}")
        runCatching { running.close() }
        return false
    }

    /** Starts the launcher and supervises it; fail closed (a refusal or a later death exits non-zero so the supervisor restarts it and the head check runs again). */
    fun run() {
        val started = java.util.concurrent.atomic.AtomicReference<CapacityLauncher.Running?>(null)
        Runtime.getRuntime().addShutdownHook(Thread { started.get()?.let { runCatching { it.close() } } })
        val running = create().start().also { started.set(it) }
        if (!supervise(running) { System.err.println(it) }) kotlin.system.exitProcess(1)
    }
}
