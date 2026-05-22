package com.reef.platform.admin

import com.reef.platform.application.admin.AdminActor
import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.application.admin.UpsertAccountCommand
import com.reef.platform.application.admin.UpsertInstrumentCommand
import com.reef.platform.application.admin.UpsertParticipantCommand

class AdminCliAdapter(
    private val adminService: AdminApplicationService = AdminApplicationService()
) {
    fun execute(args: List<String>): String {
        if (args.isEmpty()) {
            return usage()
        }

        val actorId = System.getenv("ADMIN_ACTOR_ID") ?: "admin-cli"
        val actor = AdminActor(actorId = actorId, correlationId = "admin-cli")

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
}
