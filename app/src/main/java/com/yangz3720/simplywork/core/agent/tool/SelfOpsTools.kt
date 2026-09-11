package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.WorkspaceException
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer

/**
 * 配置自操作缝（v1.3）：agent 读配置快照、发起白名单内的变更。
 *
 * Android 上 App 操作自己的私有目录不需要任何权限，但让 agent 直接写
 * `shared_prefs` XML 或 SQLite 是自毁行为 —— 所以这里只暴露**类型化白名单**
 * （切换模型/服务商、改超时、切当前项目），与 UI 点选走同一条 [KeyringLogic]/
 * [ProjectStore] 路径。API Key 永远不在白名单内（用户拍板），快照里只有 `hasKey`。
 */
interface AgentSelfConfig {

    data class AccountView(val id: String, val name: String, val baseUrl: String, val hasKey: Boolean)

    data class Snapshot(
        val activeProviderId: String?,
        val activeModel: String,
        val timeoutMs: Long,
        val accounts: List<AccountView>,
        val projects: List<Project>,
        val currentProjectId: String
    )

    data class Mutation(
        val providerId: String? = null,
        val model: String? = null,
        val timeoutMs: Long? = null,
        val projectId: String? = null
    )

    data class ApplyResult(val applied: List<String>, val rebuildPending: Boolean, val errors: List<String>)

    fun snapshot(): Snapshot

    /** 运行中调用是允许的：变更落盘，runtime 重建推迟到空闲（见 AppContainer 缝实现）。 */
    suspend fun apply(mutation: Mutation): ApplyResult
}

/**
 * 自操作工具族（v1.3，用户定调"操作自己"）：`todo_write` 先例的延伸 —— 这些不是
 * Android 系统能力，**不走 SystemOperationRouter**，直接以 [RegisteredTool] 注册
 * （超时归一、异常归一由 Router 承担的只是"系统 Intent 语义"；文件与配置操作是
 * 进程内同步调用，天然有界）。
 *
 * 三族：
 * - `self_config_read` / `self_config_update`（USER_CONFIRMING）：配置 hook，白名单内。
 * - `self_session_read`：只读会话 DAG（结构 + payload），跨会话可读。
 * - `self_project_create` + `fs_list/read/write/mkdir`（SAFE）、`fs_move`（USER_VISIBLE）、
 *   `fs_delete`（USER_CONFIRMING，用户定调"写 SAFE、删确认"）。
 */
object SelfOpsTools {

    // ----- 输入 -----

    @Serializable
    data class ConfigUpdateInput(
        val providerId: String? = null,
        val model: String? = null,
        val timeoutMs: Long? = null,
        val projectId: String? = null
    )

    @Serializable
    data class SessionReadInput(
        val mode: String = "read",
        val sessionId: String? = null,
        val limit: Int? = null,
        val includePayloads: Boolean = true
    )

    @Serializable
    data class ProjectCreateInput(val name: String)

    @Serializable
    data class FsListInput(val projectId: String? = null, val path: String = "")

    @Serializable
    data class FsPathInput(val projectId: String? = null, val path: String)

    @Serializable
    data class FsWriteInput(val projectId: String? = null, val path: String, val content: String, val append: Boolean = false)

    @Serializable
    data class FsMoveInput(val projectId: String? = null, val from: String, val to: String)

    @Serializable
    data class FsDeleteInput(val projectId: String? = null, val path: String, val recursive: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }

    // ----- 注册 -----

    fun registeredTools(
        repository: SessionRepository,
        config: AgentSelfConfig,
        workspace: AgentWorkspace,
        projects: ProjectStore
    ): List<RegisteredTool> = listOf(
        configRead(config),
        configUpdate(config),
        sessionRead(repository, projects),
        projectCreate(projects, workspace),
        fsList(workspace, projects),
        fsRead(workspace, projects),
        fsWrite(workspace, projects),
        fsMkdir(workspace, projects),
        fsMove(workspace, projects),
        fsDelete(workspace, projects)
    )

    // ----- self_config_read (SAFE) -----

    private fun configRead(config: AgentSelfConfig): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "self_config_read",
            description = "Read this app's own configuration: active provider/model, request timeout, " +
                "configured provider accounts (API keys are never included, only hasKey), the project list " +
                "and which project is current. Read before self_config_update.",
            parametersSchema = objSchema(required = emptyList())
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { Result.success(Unit) },
        runner = { _, _ ->
            val snapshot = config.snapshot()
            buildJsonObject {
                put("activeProviderId", snapshot.activeProviderId)
                put("activeModel", snapshot.activeModel)
                put("timeoutMs", snapshot.timeoutMs)
                put("currentProjectId", snapshot.currentProjectId)
                putJsonArray("accounts") {
                    snapshot.accounts.forEach {
                        add(buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                            put("baseUrl", it.baseUrl)
                            put("hasKey", it.hasKey)
                        })
                    }
                }
                putJsonArray("projects") {
                    snapshot.projects.forEach {
                        add(buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                            put("createdAt", it.createdAt)
                        })
                    }
                }
            }
        }
    )

    // ----- self_config_update (USER_CONFIRMING) -----

    private fun configUpdate(config: AgentSelfConfig): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "self_config_update",
            description = "Update this app's own configuration. Allowed keys only: switch the active " +
                "provider+model (both fields together), set the request timeoutMs (1000..600000), or " +
                "switch the current project (changes where fs_* tools operate and where new sessions land). " +
                "API keys can never be written. Changes take effect for the NEXT run, not the current one.",
            parametersSchema = objSchema(
                "providerId" to strProp("Provider account id (see self_config_read.accounts); set together with model"),
                "model" to strProp("Model id to activate; set together with providerId"),
                "timeoutMs" to intProp("Request timeout in milliseconds (1000..600000)"),
                "projectId" to strProp("Switch the current project (see self_config_read.projects)")
            )
        ),
        dangerLevel = DangerLevel.USER_CONFIRMING,
        parser = { raw ->
            runCatching { decode<ConfigUpdateInput>(raw) }.mapCatching { input ->
                val fields = listOf(input.providerId, input.model, input.timeoutMs, input.projectId)
                require(fields.any { it != null }) { "at least one of providerId+model, timeoutMs, projectId is required" }
                require((input.providerId == null) == (input.model == null)) {
                    "providerId and model must be set together"
                }
                input.timeoutMs?.let { require(it in 1_000..600_000) { "timeoutMs must be between 1000 and 600000 (got $it)" } }
                input
            }
        },
        runner = { parsed, _ ->
            val input = parsed as? ConfigUpdateInput
                ?: return@RegisteredTool error("self_config_update arguments did not parse")
            val result = config.apply(
                AgentSelfConfig.Mutation(
                    providerId = input.providerId,
                    model = input.model,
                    timeoutMs = input.timeoutMs,
                    projectId = input.projectId
                )
            )
            buildJsonObject {
                put("status", if (result.errors.isEmpty()) "success" else "partial")
                put("applied", result.applied.joinToString())
                put("rebuildPending", result.rebuildPending)
                if (result.rebuildPending) {
                    put("note", "Runtime rebuild is pending; the change takes effect for the NEXT conversation run.")
                }
                put("errors", result.errors.joinToString("; ").ifEmpty { "(none)" })
            }
        }
    )

    // ----- self_session_read (SAFE) -----

    private fun sessionRead(repository: SessionRepository, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "self_session_read",
            description = "Read-only view of the conversation records. mode=list returns session metadata " +
                "(newest first); mode=read returns the session's active-path nodes with payloads " +
                "(default: the CURRENT session — pass sessionId to read any other conversation). " +
                "Read-only by contract: there is no tool that rewrites past nodes.",
            parametersSchema = objSchema(
                "mode" to enumProp(listOf("list", "read"), "list = session metadata, read = node records with payloads"),
                "sessionId" to strProp("Session id (read mode); defaults to the current session"),
                "limit" to intProp("Cap on returned entries (1..200; list default 50, read default 50 tail nodes)"),
                "includePayloads" to boolProp("read mode: include each node's payload JSON (default true)")
            )
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw ->
            runCatching { decode<SessionReadInput>(raw) }.mapCatching { input ->
                require(input.mode in setOf("list", "read")) { "mode must be 'list' or 'read' (got '${input.mode}')" }
                input.limit?.let { require(it in 1..200) { "limit must be between 1 and 200 (got $it)" } }
                input
            }
        },
        runner = { parsed, context ->
            val input = parsed as? SessionReadInput
                ?: return@RegisteredTool error("self_session_read arguments did not parse")
            if (input.mode == "list") {
                val all = repository.listSessions()
                val sessions = all.takeLast(input.limit ?: 50).asReversed()
                buildJsonObject {
                    put("mode", "list")
                    put("count", sessions.size)
                    put("truncated", all.size > sessions.size)
                    putJsonArray("sessions") {
                        sessions.forEach { record ->
                            add(buildJsonObject {
                                put("id", record.id)
                                put("title", record.title)
                                put("createdAt", record.createdAt)
                                put("projectId", record.projectId ?: ProjectLogic.DEFAULT_PROJECT_ID)
                            })
                        }
                    }
                }
            } else {
                val sessionId = input.sessionId ?: context.sessionId
                val record = repository.getSession(sessionId)
                    ?: return@RegisteredTool error("unknown sessionId: $sessionId")
                val path = repository.activePath(sessionId)
                val limit = input.limit ?: 50
                val nodes = path.takeLast(limit)
                buildJsonObject {
                    put("mode", "read")
                    put("sessionId", sessionId)
                    put("title", record.title)
                    put("projectId", record.projectId ?: ProjectLogic.DEFAULT_PROJECT_ID)
                    put("totalPathNodes", path.size)
                    put("truncated", path.size > nodes.size)
                    putJsonArray("nodes") {
                        nodes.forEach { node ->
                            add(buildJsonObject {
                                put("id", node.id)
                                put("type", node.nodeType.name)
                                put("status", node.status.name)
                                put("createdAt", node.createdAt)
                                if (input.includePayloads) {
                                    // 损坏的 payload 跳过而不是断流（P2 显式字段）。
                                    runCatching { repository.payload(node) }.getOrNull()
                                        ?.let { put("payload", it) }
                                        ?: put("payloadUnavailable", true)
                                }
                            })
                        }
                    }
                }
            }
        }
    )

    // ----- self_project_create (SAFE) -----

    private fun projectCreate(projects: ProjectStore, workspace: AgentWorkspace): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "self_project_create",
            description = "Create a new project: an archive with its own workspace directory " +
                "(fs_* tools operate inside it) that future sessions can belong to. Names are " +
                "case-insensitively unique and at most 48 characters. The current project does NOT change.",
            parametersSchema = objSchema("name" to strProp("Project display name (unique, <=48 chars)"), required = listOf("name"))
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw ->
            runCatching { decode<ProjectCreateInput>(raw) }.mapCatching { input ->
                ProjectLogic.validateName(input.name)?.let { throw IllegalArgumentException(it) }
                input
            }
        },
        runner = { parsed, _ ->
            val input = parsed as? ProjectCreateInput
                ?: return@RegisteredTool error("self_project_create arguments did not parse")
            projects.create(input.name).fold(
                onSuccess = { project ->
                    workspace.projectDir(project.id) // 沙箱目录与项目同生
                    buildJsonObject {
                        put("status", "success")
                        put("projectId", project.id)
                        put("name", project.name)
                        put("workspaceReady", true)
                    }
                },
                onFailure = { e -> error(e.message ?: "project creation failed") }
            )
        }
    )

    // ----- fs_* 工具 -----

    /** projectId 缺省 = 当前项目；显式给出则必须存在（P2 显式失败）。 */
    private fun resolveProjectId(projects: ProjectStore, requested: String?): String {
        val id = requested ?: projects.currentId()
        if (projects.byId(id) == null) {
            throw WorkspaceException("unknown projectId '$id' — call self_config_read for the project list")
        }
        return id
    }

    private fun fsList(workspace: AgentWorkspace, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "fs_list",
            description = "List files in a project workspace directory (default: the CURRENT project's root). " +
                "Entries are sorted directories-first and capped; check 'truncated' before treating a listing as complete.",
            parametersSchema = objSchema(
                "projectId" to strProp("Project id; defaults to the current project"),
                "path" to strProp("Directory path relative to the project root; empty = root")
            )
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> Result.success(decode<FsListInput>(raw)) },
        runner = { parsed, _ ->
            guarded {
                val input = parsed as? FsListInput ?: throw IllegalArgumentException("fs_list arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val listing = workspace.list(projectId, input.path)
                buildJsonObject {
                    put("path", listing.path)
                    put("projectId", projectId)
                    put("truncated", listing.truncated)
                    putJsonArray("entries") {
                        listing.entries.forEach {
                            add(buildJsonObject {
                                put("name", it.name)
                                put("type", if (it.isDirectory) "directory" else "file")
                                put("sizeBytes", it.sizeBytes)
                                put("modifiedAtMs", it.modifiedAtMs)
                            })
                        }
                    }
                }
            }
        }
    )

    private fun fsRead(workspace: AgentWorkspace, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "fs_read",
            description = "Read a text file from a project workspace. Files larger than the 256KB cap are " +
                "returned truncated with truncated=true and the full sizeBytes.",
            parametersSchema = objSchema(
                "projectId" to strProp("Project id; defaults to the current project"),
                "path" to strProp("File path relative to the project root"),
                required = listOf("path")
            )
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> Result.success(decode<FsPathInput>(raw)) },
        runner = { parsed, _ ->
            guarded {
                val input = parsed as? FsPathInput ?: throw IllegalArgumentException("fs_read arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val content = workspace.read(projectId, input.path)
                buildJsonObject {
                    put("path", content.path)
                    put("projectId", projectId)
                    put("content", content.content)
                    put("sizeBytes", content.sizeBytes)
                    put("truncated", content.truncated)
                }
            }
        }
    )

    private fun fsWrite(workspace: AgentWorkspace, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "fs_write",
            description = "Create or overwrite a text file in a project workspace (parent directories are " +
                "created automatically). Set append=true to append instead of overwrite. Hard cap 256KB per " +
                "write — split large content across calls.",
            parametersSchema = objSchema(
                "projectId" to strProp("Project id; defaults to the current project"),
                "path" to strProp("File path relative to the project root"),
                "content" to strProp("UTF-8 text to write"),
                "append" to boolProp("true = append to the file; default false = overwrite"),
                required = listOf("path", "content")
            )
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> Result.success(decode<FsWriteInput>(raw)) },
        runner = { parsed, _ ->
            guarded {
                val input = parsed as? FsWriteInput ?: throw IllegalArgumentException("fs_write arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val result = workspace.write(projectId, input.path, input.content, input.append)
                buildJsonObject {
                    put("path", result.path)
                    put("projectId", projectId)
                    put("bytesWritten", result.bytesWritten)
                    put("totalBytes", result.totalBytes)
                    put("created", result.created)
                }
            }
        }
    )

    private fun fsMkdir(workspace: AgentWorkspace, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "fs_mkdir",
            description = "Create a directory (and parents) inside a project workspace. Returns created=false " +
                "when it already existed.",
            parametersSchema = objSchema(
                "projectId" to strProp("Project id; defaults to the current project"),
                "path" to strProp("Directory path relative to the project root"),
                required = listOf("path")
            )
        ),
        dangerLevel = DangerLevel.SAFE,
        parser = { raw -> Result.success(decode<FsPathInput>(raw)) },
        runner = { parsed, _ ->
            guarded {
                val input = parsed as? FsPathInput ?: throw IllegalArgumentException("fs_mkdir arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val result = workspace.mkdir(projectId, input.path)
                buildJsonObject {
                    put("path", result.path)
                    put("projectId", projectId)
                    put("created", result.created)
                }
            }
        }
    )

    private fun fsMove(workspace: AgentWorkspace, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "fs_move",
            description = "Rename or move a file/directory inside a project workspace. Refused when the " +
                "target already exists (no silent overwrite).",
            parametersSchema = objSchema(
                "projectId" to strProp("Project id; defaults to the current project"),
                "from" to strProp("Source path relative to the project root"),
                "to" to strProp("Target path relative to the project root"),
                required = listOf("from", "to")
            )
        ),
        dangerLevel = DangerLevel.USER_VISIBLE,
        parser = { raw ->
            runCatching { decode<FsMoveInput>(raw) }
        },
        runner = { parsed, _ ->
            guarded {
                val input = parsed as? FsMoveInput ?: throw IllegalArgumentException("fs_move arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val result = workspace.move(projectId, input.from, input.to)
                buildJsonObject {
                    put("from", result.from)
                    put("to", result.to)
                    put("projectId", projectId)
                }
            }
        }
    )

    private fun fsDelete(workspace: AgentWorkspace, projects: ProjectStore): RegisteredTool = RegisteredTool(
        definition = ToolDefinition(
            name = "fs_delete",
            description = "Delete a file or directory inside a project workspace. Deleting a non-empty " +
                "directory requires recursive=true and is irreversible; the project root itself cannot be deleted.",
            parametersSchema = objSchema(
                "projectId" to strProp("Project id; defaults to the current project"),
                "path" to strProp("Path relative to the project root"),
                "recursive" to boolProp("Required true to delete a non-empty directory"),
                required = listOf("path")
            )
        ),
        dangerLevel = DangerLevel.USER_CONFIRMING,
        parser = { raw -> Result.success(decode<FsDeleteInput>(raw)) },
        runner = { parsed, _ ->
            guarded {
                val input = parsed as? FsDeleteInput ?: throw IllegalArgumentException("fs_delete arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val result = workspace.delete(projectId, input.path, input.recursive)
                buildJsonObject {
                    put("path", result.path)
                    put("projectId", projectId)
                    put("deletedEntries", result.deletedEntries)
                }
            }
        }
    )

    // ----- 辅助 -----

    private inline fun <reified T> decode(raw: JsonObject): T = json.decodeFromJsonElement<T>(raw)

    private fun error(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("reason", message)
    }

    /**
     * runner 统一出口：沙箱拒绝 / 参数越界等运行期异常归一成 JSON 错误对象，
     * 模型能读到原因并自纠（P2/P5 精神：异常不裸抛进循环）。
     */
    private inline fun guarded(crossinline block: () -> JsonObject): JsonObject = try {
        block()
    } catch (e: WorkspaceException) {
        error(e.message ?: "workspace operation failed")
    } catch (e: IllegalArgumentException) {
        error(e.message ?: "invalid argument")
    }

    private fun strProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("format", "int64")
        put("description", description)
    }

    private fun boolProp(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun enumProp(values: List<String>, description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", description)
    }

    private fun objSchema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (name, schema) -> put(name, schema) } })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}