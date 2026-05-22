package com.reef.platform.admin

import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains

class AdminCliAdapterTest {
    @Test
    fun executesReferenceDataUpsertCommands() {
        val service = AdminApplicationService(InMemoryRuntimePersistence())
        val cli = AdminCliAdapter(service)

        assertContains(cli.execute(listOf("instrument-upsert", "AAPL", "AAPL")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("participant-upsert", "participant-1", "Participant 1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("account-upsert", "account-1", "participant-1")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("role-upsert", "ops_role", "reference.write,auth.admin")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("role-assign", "ops-2", "ops_role")), "\"status\":\"ok\"")
        assertContains(cli.execute(listOf("roles-list")), "\"rolesCount\":")
        assertContains(cli.execute(listOf("actor-roles", "ops-2")), "\"rolesCount\":")
        assertContains(cli.execute(listOf("events-recent", "10")), "\"eventsCount\":")
    }

    @Test
    fun returnsUsageForUnknownCommand() {
        val cli = AdminCliAdapter(AdminApplicationService(InMemoryRuntimePersistence()))
        val output = cli.execute(listOf("unknown"))
        assertContains(output, "admin commands:")
    }
}
