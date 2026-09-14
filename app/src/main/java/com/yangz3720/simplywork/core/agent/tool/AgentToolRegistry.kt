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
     * v1.1：这次调用会**不可逆**地改动或删除用户数据。
     *
     * 与 [dangerLevel] 正交，刻意不合并：[dangerLevel] 回答"要不要让用户知情"（读位置、
     * 查日历也算 USER_CONFIRMING），而这个标志回答"自动批准能不能免掉它"。删除类调用即使
     * 在自动批准下也必须留下人的一拍，所以它需要一个单独的、不会被粗分级抹平的标记；
     * 也不把已按设计移除的 HIGH_RISK 等级加回来（见 OperationSpec.kt 的 v1.1 说明）。
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
