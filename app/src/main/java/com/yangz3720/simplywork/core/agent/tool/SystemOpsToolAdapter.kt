package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import com.yangz3720.simplywork.infrastructure.systemops.model.OperationSpec
import com.yangz3720.simplywork.infrastructure.systemops.model.SystemOperationResult
import com.yangz3720.simplywork.infrastructure.systemops.tool.SystemOperationToolFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridges the systemops `sys_*` tool entries into the agent-core registry
 * (agent-core-design.md §8.3): parse via [SystemOperationToolFactory], execute via
 * [SystemOperationRouter] (which owns timeouts and result normalization), and emit
 * the result as a model-readable JSON object.
 */
object SystemOpsToolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    /** Registers all 7 `sys_*` tools. */
    fun registerAll(registry: AgentToolRegistry, router: SystemOperationRouter) {
        SystemOperationToolFactory.toolEntries().forEach { entry ->
            registry.register(
                RegisteredTool(
                    definition = entry.definition,
                    dangerLevel = entry.dangerLevel,
                    parser = { raw ->
                        SystemOperationToolFactory.parseArguments(entry.definition.name, raw)
                            .map { it as Any? }
                    },
                    // P3: the spec instance resolves its own danger level (calendar dual-mode).
                    instanceDangerLevel = { parsed -> (parsed as? OperationSpec)?.dangerLevel ?: entry.dangerLevel },
                    runner = { parsed, _ ->
                        val spec = parsed as? OperationSpec
                            ?: return@RegisteredTool errorObject("System tool arguments did not parse to a spec")
                        toOutputJson(router.execute(spec))
                    }
                )
            )
        }
    }

    /** Normalizes a [SystemOperationResult] into the tool-result JSON (P2: failures explicit). */
    fun toOutputJson(result: SystemOperationResult): JsonObject = when (result) {
        is SystemOperationResult.Success -> result.outputJson
            ?: buildJsonObject { put("status", "success"); put("executedVia", result.executedVia.name) }
        is SystemOperationResult.PermissionRequired -> result.toJson("permission_required")
        is SystemOperationResult.SecurityBlocked -> result.toJson("security_blocked")
        is SystemOperationResult.TargetNotFound -> result.toJson("target_not_found")
        is SystemOperationResult.Timeout -> result.toJson("timeout")
        is SystemOperationResult.Failed -> result.toJson("failed")
    }

    private fun SystemOperationResult.PermissionRequired.toJson(kind: String): JsonObject =
        buildJsonObject {
            put("status", kind)
            put("permissions", JsonArrayToString(permissions))
            put("rationale", rationale)
        }

    private fun SystemOperationResult.SecurityBlocked.toJson(kind: String): JsonObject =
        buildJsonObject { put("status", kind); put("reason", reason) }

    private fun SystemOperationResult.TargetNotFound.toJson(kind: String): JsonObject =
        buildJsonObject { put("status", kind); put("target", target) }

    private fun SystemOperationResult.Timeout.toJson(kind: String): JsonObject =
        buildJsonObject { put("status", kind); put("timeoutMs", timeoutMs); put("executionTimeMs", executionTimeMs) }

    private fun SystemOperationResult.Failed.toJson(kind: String): JsonObject =
        buildJsonObject { put("status", kind); put("message", message); cause?.let { put("cause", it) } }

    private fun JsonArrayToString(values: List<String>): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })

    private fun errorObject(message: String): JsonObject = buildJsonObject {
        put("status", "invalid_arguments")
        put("reason", message)
    }
}
