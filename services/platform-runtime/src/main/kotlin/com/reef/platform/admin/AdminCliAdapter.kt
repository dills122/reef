package com.reef.platform.admin

import com.reef.platform.application.admin.AdminActor
import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.application.admin.UpsertAccountCommand
import com.reef.platform.application.admin.UpsertInstrumentCommand
import com.reef.platform.application.admin.UpsertParticipantCommand
import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.CommandCircuitBreakerStore
import com.reef.platform.api.defaultAccountRiskControlStore
import com.reef.platform.api.defaultCommandCircuitBreakerStore

class AdminCliAdapter(
    private val adminService: AdminApplicationService = AdminApplicationService(),
    private val accountRiskControls: () -> AccountRiskControlStore = { defaultAccountRiskControlStore() },
    private val commandCircuitBreakers: () -> CommandCircuitBreakerStore = { defaultCommandCircuitBreakerStore() },
    private val now: () -> java.time.Instant = { java.time.Instant.now() }
) {
    fun execute(args: List<String>): String {
        if (args.isEmpty()) {
            return usage()
        }

        val actorId = System.getenv("ADMIN_ACTOR_ID") ?: "admin-cli"
        val actor = AdminActor(actorId = actorId, correlationId = "admin-cli", occurredAt = now().toString())

        return when (args[0]) {
            "instrument-upsert" -> {
                if (args.size < 3) return "usage: instrument-upsert <instrumentId> <symbol>"
                adminService.upsertInstrument(actor, UpsertInstrumentCommand(args[1], args[2]))
                """{"status":"ok","command":"instrument-upsert"}"""
            }
            "participant-upsert" -> {
                if (args.size < 3) return "usage: participant-upsert <participantId> <name>"
                adminService.upsertParticipant(actor, UpsertParticipantCommand(args[1], args[2]))
                """{"status":"ok","command":"participant-upsert"}"""
            }
            "account-upsert" -> {
                if (args.size < 3) return "usage: account-upsert <accountId> <participantId>"
                adminService.upsertAccount(actor, UpsertAccountCommand(args[1], args[2]))
                """{"status":"ok","command":"account-upsert"}"""
            }
            "events-recent" -> {
                val limit = args.getOrNull(1)?.toIntOrNull() ?: 20
                val events = adminService.recentEvents(limit)
                """{"eventsCount":${events.size}}"""
            }
            "role-upsert" -> {
                if (args.size < 3) return "usage: role-upsert <roleId> <permissionCsv>"
                adminService.upsertRole(actor, args[1], args[2])
                """{"status":"ok","command":"role-upsert"}"""
            }
            "role-assign" -> {
                if (args.size < 3) return "usage: role-assign <actorId> <roleId>"
                adminService.assignRole(actor, args[1], args[2])
                """{"status":"ok","command":"role-assign"}"""
            }
            "roles-list" -> {
                val roles = adminService.listRoles()
                """{"rolesCount":${roles.size}}"""
            }
            "actor-roles" -> {
                if (args.size < 2) return "usage: actor-roles <actorId>"
                val roles = adminService.listActorRoles(args[1])
                """{"rolesCount":${roles.size},"actorId":"${args[1]}"}"""
            }
            "account-risk-set" -> {
                if (args.size < 4) return "usage: account-risk-set <account|bot> <id> <allow|reject|backpressure|disabled-bot> [reason]"
                val scopeType = when (args[1].trim().lowercase()) {
                    "account" -> "ACCOUNT"
                    "bot" -> "BOT"
                    else -> return "usage: account-risk-set <account|bot> <id> <allow|reject|backpressure|disabled-bot> [reason]"
                }
                val decision = parseAccountRiskDecision(args[3])
                    ?: return "usage: account-risk-set <account|bot> <id> <allow|reject|backpressure|disabled-bot> [reason]"
                val reason = args.drop(4).joinToString(" ")
                accountRiskControls().upsertControl(scopeType, args[2], decision, reason)
                """{"status":"ok","command":"account-risk-set","scopeType":"$scopeType","scopeId":"${escapeJson(args[2])}","decision":"${decision.name}"}"""
            }
            "account-risk-list" -> {
                val controls = accountRiskControls().listControls()
                """{"controlsCount":${controls.size}}"""
            }
            "breaker-set" -> {
                if (args.size < 4) return "usage: breaker-set <global|venue-session|instrument> <id|*> <trip|reset> [reason]"
                val scopeType = when (args[1].trim().lowercase()) {
                    "global" -> "GLOBAL"
                    "venue-session" -> "VENUE_SESSION"
                    "instrument" -> "INSTRUMENT"
                    else -> return "usage: breaker-set <global|venue-session|instrument> <id|*> <trip|reset> [reason]"
                }
                val tripped = when (args[3].trim().lowercase()) {
                    "trip", "tripped", "on" -> true
                    "reset", "clear", "off" -> false
                    else -> return "usage: breaker-set <global|venue-session|instrument> <id|*> <trip|reset> [reason]"
                }
                val reason = args.drop(4).joinToString(" ")
                commandCircuitBreakers().setBreaker(scopeType, args[2], tripped, reason)
                """{"status":"ok","command":"breaker-set","scopeType":"$scopeType","scopeId":"${escapeJson(args[2])}","tripped":$tripped}"""
            }
            "breaker-list" -> {
                val breakers = commandCircuitBreakers().listBreakers()
                """{"breakersCount":${breakers.size}}"""
            }
            "calendar-upsert" -> {
                if (args.size < 4) return "usage: calendar-upsert <profileId> <timezone> <settlementCycle>"
                adminService.upsertCalendarProfile(
                    actor,
                    com.reef.platform.application.admin.CalendarProfile(args[1], args[2], args[3])
                )
                """{"status":"ok","command":"calendar-upsert"}"""
            }
            "calendar-list" -> {
                val profiles = adminService.listCalendarProfiles()
                """{"profilesCount":${profiles.size}}"""
            }
            "override-upsert" -> {
                if (args.size < 3) return "usage: override-upsert <code> <description>"
                adminService.upsertOverrideReason(
                    actor,
                    com.reef.platform.application.admin.OverrideReasonCode(args[1], args[2])
                )
                """{"status":"ok","command":"override-upsert"}"""
            }
            "override-list" -> {
                val reasons = adminService.listOverrideReasons()
                """{"reasonsCount":${reasons.size}}"""
            }
            "sim-start" -> {
                if (args.size < 2) return "usage: sim-start <scenario>"
                adminService.startSimulation(actor, args[1])
                """{"status":"ok","command":"sim-start"}"""
            }
            "sim-pause" -> {
                adminService.pauseSimulation(actor)
                """{"status":"ok","command":"sim-pause"}"""
            }
            "sim-stop" -> {
                adminService.stopSimulation(actor)
                """{"status":"ok","command":"sim-stop"}"""
            }
            "sim-state" -> {
                val state = adminService.simulationState()
                """{"status":"${state.status}","scenario":"${state.scenario}"}"""
            }
            "trace-events" -> {
                if (args.size < 2) return "usage: trace-events <traceId>"
                val events = adminService.traceEvents(args[1])
                """{"eventsCount":${events.size},"traceId":"${args[1]}"}"""
            }
            else -> usage()
        }
    }

    private fun usage(): String {
        return """
            admin commands:
              instrument-upsert <instrumentId> <symbol>
              participant-upsert <participantId> <name>
              account-upsert <accountId> <participantId>
              events-recent [limit]
              role-upsert <roleId> <permissionCsv>
              role-assign <actorId> <roleId>
              roles-list
              actor-roles <actorId>
              account-risk-set <account|bot> <id> <allow|reject|backpressure|disabled-bot> [reason]
              account-risk-list
              breaker-set <global|venue-session|instrument> <id|*> <trip|reset> [reason]
              breaker-list
              calendar-upsert <profileId> <timezone> <settlementCycle>
              calendar-list
              override-upsert <code> <description>
              override-list
              sim-start <scenario>
              sim-pause
              sim-stop
              sim-state
              trace-events <traceId>
        """.trimIndent()
    }

    private fun parseAccountRiskDecision(raw: String): AccountRiskDecision? {
        return when (raw.trim().lowercase()) {
            "allow" -> AccountRiskDecision.ALLOW
            "reject" -> AccountRiskDecision.REJECT
            "backpressure" -> AccountRiskDecision.BACKPRESSURE
            "disabled-bot", "disabled_bot" -> AccountRiskDecision.DISABLED_BOT
            else -> null
        }
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
