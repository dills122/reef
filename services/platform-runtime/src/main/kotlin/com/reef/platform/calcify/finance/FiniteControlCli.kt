package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteBindingContract
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** A parsed operator command. Authority is not part of it: the writer stamps OPERATOR. */
internal sealed interface ControlCommand {
    data class Fund(val action: ActionKey, val account: AccountId, val asset: AssetId, val amount: Long) : ControlCommand
    data class Settle(val action: ActionKey, val executionId: String, val attempt: Int) : ControlCommand
}

/**
 * P-5, option A: the operator-run command line for the control writer. Authority is possession of the control principal's credential
 * (environment, never arguments, never a trading service) and an explicit invocation; there is no HTTP surface and no caller-supplied
 * authority. Usage:
 *   fund   <opening.json> <actionId> <accountId> <assetId> <amount>
 *   settle <opening.json> <actionId> <executionId> <attempt>
 * Environment: CALCIFY_CONTROL_BOOTSTRAP, CALCIFY_CONTROL_USERNAME, CALCIFY_CONTROL_PASSWORD (SASL/SCRAM-SHA-256 over SASL_PLAINTEXT or SASL_SSL via
 * CALCIFY_CONTROL_PROTOCOL).
 */
internal object FiniteControlCli {
    const val FUND_DOMAIN = "fund"
    const val REPAIR_DOMAIN = "repair"
    const val NAMESPACE = "operator"

    fun parse(args: List<String>): Pair<Path, ControlCommand> {
        require(args.isNotEmpty()) { "usage: fund|settle <opening.json> ..." }
        val opening = Path.of(args.getOrNull(1) ?: throw IllegalArgumentException("missing opening file"))
        return when (args[0]) {
            "fund" -> { require(args.size == 6) { "usage: fund <opening.json> <actionId> <accountId> <assetId> <amount>" }
                opening to ControlCommand.Fund(ActionKey(NAMESPACE, FUND_DOMAIN, args[2]), AccountId(args[3]), AssetId(args[4]), args[5].toLongOrNull() ?: throw IllegalArgumentException("amount must be an integer")) }
            "settle" -> { require(args.size == 5) { "usage: settle <opening.json> <actionId> <executionId> <attempt>" }
                opening to ControlCommand.Settle(ActionKey(NAMESPACE, REPAIR_DOMAIN, args[2]), args[3], args[4].toIntOrNull() ?: throw IllegalArgumentException("attempt must be an integer")) }
            else -> throw IllegalArgumentException("unknown command ${args[0]}")
        }
    }

    fun clientProperties(env: Map<String, String>): Properties {
        fun need(k: String) = env[k]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing environment variable $k")
        val protocol = env["CALCIFY_CONTROL_PROTOCOL"] ?: "SASL_PLAINTEXT"
        require(protocol == "SASL_PLAINTEXT" || protocol == "SASL_SSL") { "protocol must be SASL_PLAINTEXT or SASL_SSL" }
        fun quoted(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return Properties().apply {
            put("bootstrap.servers", need("CALCIFY_CONTROL_BOOTSTRAP")); put("security.protocol", protocol); put("sasl.mechanism", "SCRAM-SHA-256")
            put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${quoted(need("CALCIFY_CONTROL_USERNAME"))}\" password=\"${quoted(need("CALCIFY_CONTROL_PASSWORD"))}\";")
        }
    }

    fun run(args: List<String>, env: Map<String, String>): ControlSubmission {
        val (openingPath, command) = parse(args)
        val opening = FiniteBindingContract.parseOpeningV2(Files.readAllBytes(openingPath))
        FiniteControlWriter(clientProperties(env), opening.topic("CONTROL").name, opening.controlTransactionalId).use { writer ->
            writer.recover()
            return when (command) {
                is ControlCommand.Fund -> writer.fund(command.action, command.account, command.asset, command.amount)
                is ControlCommand.Settle -> writer.settle(command.action,
                    ExecutionKey(opening.json()["runId"].textValue(), opening.json()["venueSessionId"].textValue(), opening.json()["instrumentId"].textValue(), command.executionId), command.attempt)
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val result = try { run(args.toList(), System.getenv()) } catch (e: Exception) { System.err.println("control write failed: ${e.message}"); kotlin.system.exitProcess(2) }
        println(result)
        kotlin.system.exitProcess(if (result is ControlSubmission.Submitted || result is ControlSubmission.AlreadySubmitted) 0 else 3)
    }
}
