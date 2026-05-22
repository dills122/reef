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
              trace-events <traceId>
        """.trimIndent()
    }
}
