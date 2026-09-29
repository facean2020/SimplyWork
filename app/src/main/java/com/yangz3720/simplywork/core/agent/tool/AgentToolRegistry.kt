package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.serialization.json.JsonObject

/**
 * Per-execution context handed to tool runners: identifies the session the call
 * belongs to (tools that mutate session state, e.g. todo_write, need it) and the
 * call itself — v1.5: fs_* records its change under [toolCallId], which is how the
 * diff card lands under the matching entry in the conversation.
 */
data class ToolExecutionContext(val sessionId: String, val toolCallId: String = "")

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
    val instanceDangerLevel: ((parsed: Any?) -> DangerLevel)? = null,
    /**
     * v1.1: this call will **irreversibly** change or delete user data.
     *
     * Orthogonal to [dangerLevel], deliberately not merged: [dangerLevel] answers "should the
     * user be told" (reading location or querying the calendar also counts as USER_CONFIRMING),
     * whereas this flag answers "can auto-approval waive it". A delete-style call must still
     * leave the human a beat even under auto-approval, so it needs its own marker that a coarse
     * grading cannot flatten away; and we do not add back the HIGH_RISK level that was removed
     * by design (see the v1.1 note in OperationSpec.kt).
     */
    val destructive: Boolean = false
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
