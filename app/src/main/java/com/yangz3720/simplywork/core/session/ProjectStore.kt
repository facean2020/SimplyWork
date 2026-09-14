package com.yangz3720.simplywork.core.session

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 项目 → OneDrive 文件夹的绑定（v1.4）。绑定的是**文件夹**（driveItem id），
 * 不是文件：项目的沙箱目录整棵镜像到该文件夹。
 *
 * [deltaLink] 是 Graph 增量游标：有它之后每次同步只拉"此后"的变化，配合
 * [lastSyncedAt] 构成懒惰同步（不做轮询/后台任务）。它随每次同步推进。
 */
@Serializable
data class OneDriveBinding(
    /** 云端文件夹的 driveItem id（同步主键，绝不存路径：重命名/移动会换路径）。 */
    val folderId: String,
    /** 展示用文件夹名（含父级路径，如 `工作/项目A`）。 */
    val folderPath: String,
    val deltaLink: String? = null,
    val lastSyncedAt: Long = 0L,
    val boundAt: Long = 0L
)

/**
 * 「项目」（v1.3，参考 Qwen Studio / GPT 建档语义，用户定调"会话归属项目"）：
 * 一个项目 = 一份档案 = 会话归属单位 + 沙箱工作空间目录
 * （`filesDir/agent_workspace/projects/<projectId>/`，由 [com.yangz3720.simplywork.core.workspace.AgentWorkspace] 管理）。
 *
 * 项目数量小（个位数到几十），用 SharedPreferences JSON 整体读写即可；
 * 会话归属在 `sessions.project_id` 列（SqliteSessionStore v2）。旧会话行的
 * `project_id = NULL` 由 [DEFAULT_PROJECT_ID] 语义兜底，不回填。
 *
 * [oneDrive] 为 null = 未绑定云端（v1.4）。新增字段带默认值，旧 JSON 直接解码；
 * 快照/序列化该结构时不泄露令牌——令牌只在 MSAL 自己的缓存里。
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val oneDrive: OneDriveBinding? = null
)

/** 纯逻辑（唯一裁决点，JVM 可测）：名字校验、去重、默认项目。 */
object ProjectLogic {

    /** 稳定 id 的默认项目：承接全部 pre-v1.3 会话（projectId == null）。 */
    const val DEFAULT_PROJECT_ID = "default"
    const val DEFAULT_PROJECT_NAME = "默认项目"

    const val MAX_NAME_LENGTH = 48
    val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** @return 校验后的名字，或错误消息（模型可据此自纠，P2 显式失败）。 */
    fun validateName(raw: String): String? = when {
        raw.isBlank() -> "project name must not be blank"
        raw.length > MAX_NAME_LENGTH -> "project name must be at most $MAX_NAME_LENGTH characters (got ${raw.length})"
        else -> null
    }

    fun create(projects: List<Project>, rawName: String, nowMs: Long): Result<Project> {
        validateName(rawName)?.let { return Result.failure(IllegalArgumentException(it)) }
        val name = rawName.trim()
        // 名字大小写不敏感去重：同名建档是模型的常见失误，显式拒绝而不是造出
        // 两个只差大小写的目录，让用户在切换器里猜哪个是哪个。
        projects.firstOrNull { it.name.equals(rawName.trim(), ignoreCase = true) }?.let {
            return Result.failure(IllegalArgumentException("project '${it.name}' already exists (${it.id})"))
        }
        return Result.success(
            Project(id = UUID.randomUUID().toString(), name = rawName.trim(), createdAt = nowMs)
        )
    }

    fun byId(projects: List<Project>, id: String): Project? = projects.firstOrNull { it.id == id }
}

/**
 * 项目注册表：项目列表 + 当前项目指针。整体 JSON 存 `SharedPreferences`（与
 * [com.yangz3720.simplywork.ui.agent.ProviderKeyring] 同一持久化惯例）；并发只允许
 * 单进程 UI/agent 循环访问，不加锁（与 keyring 一致）。
 *
 * [changes] 供 UI 层观察刷新（切项目 = 会话列表过滤条件变了）。
 */
class ProjectStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _changes = MutableStateFlow(0)

    /** 每次项目/当前项目变更自增；UI 据此刷新会话列表。 */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    init {
        ensureDefault()
    }

    fun list(): List<Project> = decode().sortedBy { it.createdAt }

    fun byId(id: String): Project? = decode().firstOrNull { it.id == id }

    fun currentId(): String {
        val stored = prefs.getString(CURRENT_KEY, null)
        // 当前指针失效（项目被外部清掉）→ 回落默认项目，绝不返回不存在的 id（P2）。
        val projects = decode()
        return if (projects.any { it.id == stored }) stored!! else ProjectLogic.DEFAULT_PROJECT_ID
    }

    fun current(): Project = byId(currentId()) ?: decode().firstOrNull { it.id == ProjectLogic.DEFAULT_PROJECT_ID }
        ?: Project(ProjectLogic.DEFAULT_PROJECT_ID, ProjectLogic.DEFAULT_PROJECT_NAME, 0L)

    fun create(name: String, nowMs: Long = System.currentTimeMillis()): Result<Project> {
        val existing = decode()
        val created = ProjectLogic.create(existing, name, nowMs)
            .getOrElse { return Result.failure(it) }
        encode(existing + created)
        bump()
        return Result.success(created)
    }

    /** @return false 当 [id] 不存在（不静默切换，P2）。 */
    fun switchTo(id: String): Boolean {
        if (byId(id) == null) return false
        prefs.edit().putString(CURRENT_KEY, id).apply()
        bump()
        return true
    }

    // ----- OneDrive 绑定（v1.4）-----

    fun oneDriveBinding(projectId: String): OneDriveBinding? = byId(projectId)?.oneDrive

    /** 所有已绑定云端的项目 id（同步协调器按此枚举，避免全量扫）。 */
    fun boundProjectIds(): List<String> = decode().filter { it.oneDrive != null }.map { it.id }

    /**
     * 绑定的唯一写入口：`transform` 收到当前绑定（可能为 null），返回新绑定，
     * 返回 null 即解除绑定。绑定/解除/推进 deltaLink/记录同步时间都走这里，
     * 保证"项目只有一个云端文件夹"这个不变量不被绕开。
     *
     * @return false 当 [projectId] 不存在（显式失败，不静默丢弃写操作）。
     */
    fun updateOneDrive(projectId: String, transform: (OneDriveBinding?) -> OneDriveBinding?): Boolean {
        val existing = decode()
        val project = existing.firstOrNull { it.id == projectId } ?: return false
        val updated = transform(project.oneDrive)
        encode(existing.map { if (it.id == projectId) it.copy(oneDrive = updated) else it })
        bump()
        return true
    }

    private fun ensureDefault() {
        if (byId(ProjectLogic.DEFAULT_PROJECT_ID) == null) {
            encode(
                decode() + Project(
                    id = ProjectLogic.DEFAULT_PROJECT_ID,
                    name = ProjectLogic.DEFAULT_PROJECT_NAME,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun decode(): List<Project> = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(ListSerializer(Project.serializer()), it) }
    }.getOrNull().orEmpty()

    private fun encode(projects: List<Project>) {
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(Project.serializer()), projects)).apply()
    }

    private fun bump() {
        _changes.value += 1
    }

    private companion object {
        const val KEY = "projects"
        const val CURRENT_KEY = "current_project_id"
    }
}