package com.reef.platform.calcify.finance

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The operator-run command line for the capacity control writer (funding and repairs). Authority is possession of the control principal's credential (environment,
 * never arguments) and an explicit invocation; there is no HTTP surface. Every command names the plan file, so lanes, instruments and the control topic come from the
 * same statement of the deployment the service runs on. Usage:
 *   fund-cash     <plan.json> <actionPrefix> <accountId> <amount>          the same amount in every lane
 *   fund-cash-lane <plan.json> <actionPrefix> <accountId> <lane> <amount>  one lane
 *   fund-security <plan.json> <actionPrefix> <accountId> <instrumentId> <units>
 *   repair        <plan.json> <actionId> <instrumentId> <executionId> <attempt>   (attempt 2 or later; attempt 1 is the automatic one)
 * Action ids are idempotency keys: running a command again with the same ids is absorbed by the ledger, a different amount under the same id is a conflict.
 * Environment: CALCIFY_CAPACITY_CONTROL_BOOTSTRAP and either CALCIFY_CAPACITY_CONTROL_PROTOCOL=PLAINTEXT with CALCIFY_CAPACITY_DEV_PLAINTEXT=true (local development
 * stacks only) or SASL (CALCIFY_CAPACITY_CONTROL_USERNAME, CALCIFY_CAPACITY_CONTROL_PASSWORD, CALCIFY_CAPACITY_CONTROL_PROTOCOL SASL_PLAINTEXT or SASL_SSL).
 */
internal object CapacityControlCli {
    fun clientProperties(env: Map<String, String>): Properties {
        fun need(k: String) = env[k]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing environment variable $k")
        val protocol = env["CALCIFY_CAPACITY_CONTROL_PROTOCOL"] ?: "SASL_PLAINTEXT"
        if (protocol == "PLAINTEXT") {
            require(env["CALCIFY_CAPACITY_DEV_PLAINTEXT"] == "true") { "PLAINTEXT needs CALCIFY_CAPACITY_DEV_PLAINTEXT=true (local development stacks only)" }
            return Properties().apply { put("bootstrap.servers", need("CALCIFY_CAPACITY_CONTROL_BOOTSTRAP")) }
        }
        return FiniteFinanceRuntime.clientProperties(need("CALCIFY_CAPACITY_CONTROL_BOOTSTRAP"), protocol, env["CALCIFY_CAPACITY_CONTROL_MECHANISM"] ?: "SCRAM-SHA-256",
            need("CALCIFY_CAPACITY_CONTROL_USERNAME"), need("CALCIFY_CAPACITY_CONTROL_PASSWORD"))
    }

    /** Runs one command and returns the number of control records written. */
    fun run(args: List<String>, env: Map<String, String>): Int {
        require(args.size >= 2) { "usage: fund-cash|fund-cash-lane|fund-security|repair <plan.json> ..." }
        val deployment = CapacityRuntime.parsePlan(Files.readAllBytes(Path.of(args[1])))
        val plan = deployment.plan
        fun amount(s: String) = (s.toLongOrNull() ?: throw IllegalArgumentException("amount must be an integer")).also { require(it > 0) { "amount must be positive" } }
        return CapacityControlWriter(clientProperties(env), deployment.topics.control, plan).use { writer ->
            when (args[0]) {
                "fund-cash" -> { require(args.size == 5) { "usage: fund-cash <plan.json> <actionPrefix> <accountId> <amount>" }
                    writer.fundCash(args[2], AccountId(args[3]), List(plan.lanes) { amount(args[4]) }); plan.lanes }
                "fund-cash-lane" -> { require(args.size == 6) { "usage: fund-cash-lane <plan.json> <actionPrefix> <accountId> <lane> <amount>" }
                    val lane = args[4].toIntOrNull()?.takeIf { it in 0 until plan.lanes } ?: throw IllegalArgumentException("lane must be 0..${plan.lanes - 1}")
                    writer.fundCash(args[2], AccountId(args[3]), List(plan.lanes) { if (it == lane) amount(args[5]) else 0L }); 1 }
                "fund-security" -> { require(args.size == 6) { "usage: fund-security <plan.json> <actionPrefix> <accountId> <instrumentId> <units>" }
                    writer.fundSecurity(args[2], AccountId(args[3]), args[4], amount(args[5])); 1 }
                "repair" -> { require(args.size == 6) { "usage: repair <plan.json> <actionId> <instrumentId> <executionId> <attempt>" }
                    val attempt = args[5].toIntOrNull()?.takeIf { it >= 2 } ?: throw IllegalArgumentException("attempt must be an integer of 2 or more")
                    writer.repair(args[2], ExecutionKey(plan.run, plan.session, args[3], args[4]), attempt); 1 }
                else -> throw IllegalArgumentException("unknown command ${args[0]}")
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val written = try { run(args.toList(), System.getenv()) } catch (e: Exception) { System.err.println("control write failed: ${e.message}"); kotlin.system.exitProcess(2) }
        println("wrote $written control record(s)")
    }
}
