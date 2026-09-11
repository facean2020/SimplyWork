package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.serialization.json.JsonObject

/**
 * Per-execution context handed to tool runners: identifies the session the call
 * belongs to (tools that mutate session state, e.g. todo_write, need it).
 */
data class ToolExecutionContext(val sessionId: String)

/**
 * One registered executable tool — the Registry-lite transitional shape that maps
 * 1:1 onto systemops' `SystemToolEntry` (agent-core-design.md §8.1). When tool-core's
 * ToolRegistry lands, this class migrates there unchanged.
 *
 * Type-erased parse/execute pair: [parse] converts raw JSON arguments into whatever
 * typed value [execute] consumes; the pipeline only sees the Result channel.
 */
class RegisteredTool(
    val definition: ToolDefinition,
    val dangerLevel: DangerLevel,
    private val parser: (rawArguments: JsonObject) -> Result<Any?>,
    private val runner: suspend (parsed: Any?, context: ToolExecutionContext) -> JsonObject,
    /** Optional per-instance danger refinement (e.g. calendar DIRECT_WRITE → USER_CONFIRMING). */
    val instanceDangerLevel: ((parsed: Any?) -> DangerLevel)? = null
) {
    fun parse(rawArguments: JsonObject): Result<Any?> = parser(rawArguments)

    suspend fun execute(parsed: Any?, context: ToolExecutionContext): JsonObject = runner(parsed, context)
}

/**
 * The minimal tool registry feeding "Inject Skill and Tool" (flow step D) and the
 * audit gate. Names are unique; later registrations with the same name replace earlier
 * ones (deterministic override semantics).
 */
class AgentToolRegistry {

    private val tools = LinkedHashMap<String, RegisteredTool>()

    @Synchronized
    fun register(tool: RegisteredTool) {
        tools[tool.definition.name] = tool
    }

    @Synchronized
    fun get(name: String): RegisteredTool? = tools[name]

    @Synchronized
    fun definitions(): List<ToolDefinition> = tools.values.map { it.definition }

    @Synchronized
    fun size(): Int = tools.size
}
