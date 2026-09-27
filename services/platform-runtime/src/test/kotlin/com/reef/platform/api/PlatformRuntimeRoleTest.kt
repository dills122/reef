package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformRuntimeRoleTest {
    @Test
    fun postMatchRoleOwnsOnlyPostMatchBackgroundWork() {
        val role = PlatformRuntimeRole.from("postmatch")
        assertEquals("postmatch", role.configValue)
        assertFalse(role.publicHttpEnabled)
        assertFalse(role.commandWorkersEnabled)
        assertTrue(role.postMatchWorkersEnabled)
        assertFalse(role == PlatformRuntimeRole.Projector)
    }

    @Test
    fun legacyProjectorCannotStartPostMatchWorkers() {
        assertFalse(PlatformRuntimeRole.Projector.postMatchWorkersEnabled)
        assertFalse(PlatformRuntimeRole.Api.postMatchWorkersEnabled)
        assertFalse(PlatformRuntimeRole.Materializer.postMatchWorkersEnabled)
        assertFalse(PlatformRuntimeRole.Worker.postMatchWorkersEnabled)
    }
}
