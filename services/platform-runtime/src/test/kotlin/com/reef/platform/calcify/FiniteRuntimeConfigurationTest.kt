package com.reef.platform.calcify

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class FiniteRuntimeConfigurationTest {
    private fun clients()="""{"schema":"calcify-finite-client-auth-v1","clients":[{"clientId":"buyer-client","token":"buyer-private-token","actorId":"buyer-actor","participantId":"buyer","accountId":"buyer-account"},{"clientId":"seller-client","token":"seller-private-token","actorId":"seller-actor","participantId":"seller","accountId":"seller-account"}]}"""
    @Test fun exactlyScopedDistinctClientsParseWithoutFallback() {
        val clients=FiniteRuntime.clientTokens(clients().toByteArray(),FiniteBindingFixtures.opening())
        assertEquals(setOf("buyer-client","seller-client"),clients.keys)
        assertEquals(setOf("buyer-account"),clients.getValue("buyer-client").principal.accountIds)
    }
    @Test fun malformedDuplicateOrUnscopedClientCredentialsRefuse() {
        for(body in listOf(clients().replace("seller-private-token","buyer-private-token"),clients().replace("seller-account","other-account"),
            clients().replace("seller-client","buyer-client"),clients().replace("\"actorId\":\"seller-actor\",",""),
            clients().replace("\"schema\":","\"unknown\":true,\"schema\":"),clients()+"{}")) {
            assertFails{FiniteRuntime.clientTokens(body.toByteArray(),FiniteBindingFixtures.opening())}
        }
    }
    @Test fun credentialFilesRequirePrivateRegularBoundedFiles() {
        val dir=Files.createTempDirectory("finite-private-test-");val path=dir.resolve("token");val link=dir.resolve("link")
        try {
            Files.writeString(path,"opaque-token");Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
            assertContentEquals("opaque-token".toByteArray(),FiniteRuntime.privateBytes(path,32))
            assertFails{FiniteRuntime.privateBytes(path,2)}
            Files.createSymbolicLink(link,path);assertFails{FiniteRuntime.privateBytes(link,32)}
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r-----"));assertFails{FiniteRuntime.privateBytes(path,32)}
        }finally{Files.deleteIfExists(link);Files.deleteIfExists(path);Files.deleteIfExists(dir)}
    }
}
