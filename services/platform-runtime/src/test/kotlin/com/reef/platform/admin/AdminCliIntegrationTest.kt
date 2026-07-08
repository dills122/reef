package com.reef.platform.admin

import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains

class AdminCliIntegrationTest {
    @Test
    fun fullAdminFlowRunsThroughCliAdapter() {
        val service = AdminApplicationService(InMemoryRuntimePersistence())
        val cli = AdminCliAdapter(service)

        assertContains(
            cli.execute(
                listOf(
                    "role-upsert",
                    "ops_role",
                    "reference.write,auth.admin,calendar.admin,post-trade-profile.admin,override.admin,simulation.control"
                )
            ),
            "\"status\":\"ok\""
        )
        assertContains(cli.execute(listOf("role-assign", "ops-1", "ops_role")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("instrument-upsert", "AAPL", "AAPL")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("calendar-upsert", "us-default", "America/New_York", "T+1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("post-trade-profile-list")), "\"activeProfileId\":\"ops-realistic-v1\"")
        assertContains(cli.execute(listOf("post-trade-profile-activate", "instant-post-trade-v1")), "\"activeProfileId\":\"instant-post-trade-v1\"")
        assertContains(cli.execute(listOf("override-upsert", "MANUAL_REPAIR", "manual operational repair")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("sim-start", "scenario-1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("sim-state")), "\"status\":\"running\"")
        assertContains(cli.execute(listOf("sim-stop")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("events-recent", "50")), "\"eventsCount\":")
    }
}
