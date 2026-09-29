package com.yangz3720.simplywork.core.agent.tool

import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.core.session.ProjectStore
import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.FileChange
import com.yangz3720.simplywork.core.workspace.FileChangeKind
import com.yangz3720.simplywork.core.workspace.FileChangeLog
import com.yangz3720.simplywork.core.workspace.TextDiff
import com.yangz3720.simplywork.core.workspace.WorkspaceException
import com.yangz3720.simplywork.infrastructure.model.model.ToolDefinition
import com.yangz3720.simplywork.infrastructure.systemops.model.DangerLevel
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import kotlinx.coroutines.CancellationException
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
 * Config self-operation seam (v1.3): the agent reads the config snapshot and issues changes inside the whitelist.
 *
 * On Android an app operating on its own private directory needs no permission, but letting the agent write
 * `shared_prefs` XML or SQLite directly is self-destructive — so only a **typed whitelist** is exposed
 * here (switch model/provider, change timeout, switch current project), taking the same [KeyringLogic]/
 * [ProjectStore] path as UI taps. The API key is never in the whitelist (user decision); the snapshot only carries `hasKey`.
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

    /** Calling this while running is allowed: the change is persisted and the runtime rebuild is deferred to idle (see the AppContainer seam implementation). */
    suspend fun apply(mutation: Mutation): ApplyResult
}

/**
 * Self-operation tool family (v1.3, the user's call to "operate on itself"): an extension of the `todo_write` precedent — these are not
 * Android system capabilities, so they do **not go through SystemOperationRouter** and are registered directly as [RegisteredTool]
 * instances (the timeout normalization and exception normalization the Router performs only cover "system Intent semantics";
 * file and config operations are in-process synchronous calls, inherently bounded).
 *
 * Three groups:
 * - `self_config_read` / `self_config_update` (USER_CONFIRMING): config hooks, inside the whitelist.
 * - `self_session_read`: read-only session DAG (structure + payload), readable across sessions.
 * - `self_project_create` + `fs_list/read/write/mkdir` (SAFE), `fs_move` (USER_VISIBLE),
 *   `fs_delete` (USER_CONFIRMING, the user's call: "writes SAFE, deletes confirmed").
 */
object SelfOpsTools {

    private const val TAG = "SelfOpsTools"

    // ----- Input -----

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

    // ----- Registration -----

    fun registeredTools(
        repository: SessionRepository,
        config: AgentSelfConfig,
        workspace: AgentWorkspace,
        projects: ProjectStore,
        /** v1.5: every fs_* change lands here; the conversation stream renders diff cards from it. */
        changes: FileChangeLog
    ): List<RegisteredTool> = listOf(
        configRead(config),
        configUpdate(config),
        sessionRead(repository, projects),
        projectCreate(projects, workspace),
        fsList(workspace, projects),
        fsRead(workspace, projects),
        fsWrite(workspace, projects, changes),
        fsMkdir(workspace, projects),
        fsMove(workspace, projects, changes),
        fsDelete(workspace, projects, changes)
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
                                    // A corrupted payload is skipped rather than breaking the stream (P2 explicit field).
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
                    workspace.projectDir(project.id) // the sandbox directory is created together with the project
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

    // ----- fs_* tools -----

    /** projectId defaults to the current project; when given explicitly it must exist (P2 explicit failure). */
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

    private fun fsWrite(
        workspace: AgentWorkspace,
        projects: ProjectStore,
        changes: FileChangeLog
    ): RegisteredTool = RegisteredTool(
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
        runner = { parsed, context ->
            guarded {
                val input = parsed as? FsWriteInput ?: throw IllegalArgumentException("fs_write arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                // The prior text before writing: the change card must report the "real diff", not the "new content".
                val existing = workspace.readOrNull(projectId, input.path)
                val baseline = existing?.takeIf { !it.truncated }?.content
                val result = workspace.write(projectId, input.path, input.content, input.append)
                recordQuietly(
                    changes,
                    changeOf(
                        context = context,
                        projectId = projectId,
                        path = result.path,
                        kind = when {
                            result.created -> FileChangeKind.Created
                            input.append -> FileChangeKind.Appended
                            else -> FileChangeKind.Updated
                        },
                        diff = when {
                            // Newly created: one side is empty -> everything is an addition, exact.
                            existing == null -> TextDiff.compute("", input.content)
                            baseline != null -> TextDiff.compute(
                                baseline,
                                if (input.append) baseline + input.content else input.content
                            )
                            // Appended but the baseline was truncated by the read-side cap: the computed delta is still correct (exactly the appended lines).
                            input.append -> TextDiff.compute("", input.content)
                            // Whole-file overwrite + untrustworthy baseline -> do not compare, report only the path (do not pass new content off as additions).
                            else -> null
                        }
                    )
                )
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

    private fun fsMove(
        workspace: AgentWorkspace,
        projects: ProjectStore,
        changes: FileChangeLog
    ): RegisteredTool = RegisteredTool(
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
        runner = { parsed, context ->
            guarded {
                val input = parsed as? FsMoveInput ?: throw IllegalArgumentException("fs_move arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                val result = workspace.move(projectId, input.from, input.to)
                // A rename has no content diff to compare; the card reporting "from where to where" is the whole truth.
                recordQuietly(
                    changes,
                    changeOf(
                        context = context,
                        projectId = projectId,
                        path = result.from,
                        kind = FileChangeKind.Moved,
                        diff = null,
                        toPath = result.to
                    )
                )
                buildJsonObject {
                    put("from", result.from)
                    put("to", result.to)
                    put("projectId", projectId)
                }
            }
        }
    )

    private fun fsDelete(
        workspace: AgentWorkspace,
        projects: ProjectStore,
        changes: FileChangeLog
    ): RegisteredTool = RegisteredTool(
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
        // The only tool in the whole app that deletes data irreversibly: even under auto-approval (v1.1) it must still ask the user.
        destructive = true,
        parser = { raw -> Result.success(decode<FsDeleteInput>(raw)) },
        runner = { parsed, context ->
            guarded {
                val input = parsed as? FsDeleteInput ?: throw IllegalArgumentException("fs_delete arguments did not parse")
                val projectId = resolveProjectId(projects, input.projectId)
                // Read the prior text first: after deleting there is nowhere left to answer "which lines were deleted" (a directory has no content to compare).
                val removed = workspace.readOrNull(projectId, input.path)?.takeIf { !it.truncated }
                val result = workspace.delete(projectId, input.path, input.recursive)
                recordQuietly(
                    changes,
                    changeOf(
                        context = context,
                        projectId = projectId,
                        path = result.path,
                        kind = FileChangeKind.Deleted,
                        diff = removed?.let { TextDiff.compute(it.content, "") }
                    )
                )
                buildJsonObject {
                    put("path", result.path)
                    put("projectId", projectId)
                    put("deletedEntries", result.deletedEntries)
                }
            }
        }
    )

    // ----- Change log -----

    /** Assemble one change: include the diff and line counts when comparable, report only the facts (path / kind) when not. */
    private fun changeOf(
        context: ToolExecutionContext,
        projectId: String,
        path: String,
        kind: FileChangeKind,
        diff: TextDiff.Result?,
        toPath: String? = null,
        atMs: Long = System.currentTimeMillis()
    ): FileChange = FileChange(
        toolCallId = context.toolCallId.ifBlank { null },
        sessionId = context.sessionId,
        projectId = projectId,
        path = path,
        kind = kind,
        toPath = toPath,
        addedLines = diff?.addedLines,
        removedLines = diff?.removedLines,
        diff = diff?.lines.orEmpty(),
        omittedLines = diff?.omittedLines ?: 0,
        atMs = atMs
    )

    /**
     * Record a change. **The file write already succeeded**, so this must never turn the tool result into
     * an error because "recording a card" failed — that would be lying about the file state (the inverse of P2: rather one card too few than one false failure).
     */
    private suspend fun recordQuietly(log: FileChangeLog, change: FileChange) {
        try {
            log.record(change)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.w(TAG, e) { "failed to record file change for ${change.path}" }
        }
    }

    // ----- Helpers -----

    private inline fun <reified T> decode(raw: JsonObject): T = json.decodeFromJsonElement<T>(raw)

    private fun error(message: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("reason", message)
    }

    /**
     * The single exit of every runner: runtime exceptions such as sandbox refusal / out-of-range arguments
     * are normalized into a JSON error object so the model can read the reason and self-correct (the spirit of P2/P5: no raw exception is thrown into the loop).
     * `suspend` since v1.5: the block has to record a change (see [recordQuietly]).
     */
    private suspend inline fun guarded(crossinline block: suspend () -> JsonObject): JsonObject = try {
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