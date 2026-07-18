package com.reef.platform.api

import com.reef.platform.application.admin.AdminAccessUserSummary
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminRole
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.AdminUser
import com.reef.platform.application.admin.AdminUserRole

/**
 * Admin DB access-management endpoints. The public gateway still performs
 * coarse session-role checks; AdminIdentityService owns the trust/role policy.
 */
internal class AdminAccessGateway(
    private val adminIdentityService: AdminIdentityService?,
    private val adminSessionAuth: AdminSessionAuth
) {
    fun usersResponse(query: String?): PlatformHotPathResponse {
        val service = service()
            ?: return serviceUnavailable()
        val limit = queryValue(query, "limit").toIntOrNull() ?: 100
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "users" to service.accessUsers(limit).map(::userSummaryJson)
            )
        )
    }

    fun rolesResponse(): PlatformHotPathResponse {
        val service = service()
            ?: return serviceUnavailable()
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "roles" to service.roles().map(::roleJson)
            )
        )
    }

    fun updateTrustStateResponse(body: String): PlatformHotPathResponse {
        val service = service()
            ?: return serviceUnavailable()
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val trustState = try {
            AdminTrustState.fromDb(json.string("trustState"))
        } catch (ex: IllegalArgumentException) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid trust state")))
        }
        return try {
            val user = service.updateTrustStateByOperator(
                actorId = actorId(),
                reefUserId = json.string("reefUserId"),
                trustState = trustState,
                reason = json.string("reason")
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "user" to userJson(user)
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid trust-state update")))
        }
    }

    fun assignRoleResponse(body: String): PlatformHotPathResponse {
        val service = service()
            ?: return serviceUnavailable()
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val binding = service.assignRoleByOperator(
                actorId = actorId(),
                reefUserId = json.string("reefUserId"),
                roleId = json.string("roleId"),
                reason = json.string("reason")
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "role" to roleBindingJson(binding)
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid role assignment")))
        }
    }

    fun revokeRoleResponse(body: String): PlatformHotPathResponse {
        val service = service()
            ?: return serviceUnavailable()
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            service.revokeRoleByOperator(
                actorId = actorId(),
                reefUserId = json.string("reefUserId"),
                roleId = json.string("roleId"),
                reason = json.string("reason")
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "reefUserId" to json.string("reefUserId"),
                    "roleId" to json.string("roleId")
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid role revocation")))
        }
    }

    private fun actorId(): String = adminSessionAuth.currentPrincipal().actorId

    private fun service(): AdminIdentityService? = adminIdentityService

    private fun serviceUnavailable(): PlatformHotPathResponse {
        return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "admin identity service unavailable"))
    }

    private fun userSummaryJson(summary: AdminAccessUserSummary): Map<String, Any?> {
        return userJson(summary.user) + mapOf(
            "roles" to summary.roles.map(::roleBindingJson)
        )
    }

    private fun userJson(user: AdminUser): Map<String, Any?> {
        return linkedMapOf(
            "reefUserId" to user.reefUserId,
            "githubUserId" to user.githubUserId,
            "githubLogin" to user.githubLogin,
            "displayName" to user.displayName,
            "trustState" to user.trustState.dbValue,
            "createdAt" to user.createdAt.toString(),
            "lastSeenAt" to user.lastSeenAt.toString(),
            "updatedAt" to user.updatedAt.toString()
        )
    }

    private fun roleJson(role: AdminRole): Map<String, Any?> {
        return linkedMapOf(
            "roleId" to role.roleId,
            "description" to role.description,
            "createdAt" to role.createdAt.toString()
        )
    }

    private fun roleBindingJson(binding: AdminUserRole): Map<String, Any?> {
        return linkedMapOf(
            "reefUserId" to binding.reefUserId,
            "roleId" to binding.roleId,
            "assignedBy" to binding.assignedBy,
            "assignedAt" to binding.assignedAt.toString()
        )
    }

}
