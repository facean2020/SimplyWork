package com.yangz3720.simplywork.core.workspace

/**
 * 项目文件接缝（v1.5）：只读浏览 / 预览 + 会话改动记录的读取面 + 删除项目时的清空面。
 *
 * UI 只经它触碰工作区，所以 ViewModel 不需要认识 [AgentWorkspace] 的路径安全规则，
 * 也不需要在测试里造 Android 依赖 —— [LocalProjectFiles] 是纯 `java.io`，用临时目录
 * 就能跑真实实现（不 mock）。
 */
interface ProjectFilesOwner {

    /** 列目录（[path] 相对项目根，空 = 根）。 */
    suspend fun list(projectId: String, path: String): Result<AgentWorkspace.Listing>

    /** 读文本预览（读侧上限与 fs_read 一致，超限时 `truncated = true`）。 */
    suspend fun read(projectId: String, path: String): Result<AgentWorkspace.FileContent>

    /** 当前会话累计的改动（旧 → 新）。 */
    suspend fun changes(sessionId: String): List<FileChange>

    /** 删除某会话的改动记录（会话被删时）。 */
    suspend fun deleteSession(sessionId: String)

    /** 删除整个项目沙箱目录（项目被删时）；目录不存在是 no-op。 */
    suspend fun deleteProject(projectId: String)
}

class LocalProjectFiles(
    private val workspace: AgentWorkspace,
    private val changeLog: FileChangeLog
) : ProjectFilesOwner {

    // runCatching 收编的是同步文件操作的失败（路径逃逸 / 不是普通文件 / IO）；
    // 这里没有任何挂起点，所以不存在把取消一起吞掉的问题。
    override suspend fun list(projectId: String, path: String): Result<AgentWorkspace.Listing> =
        runCatching { workspace.list(projectId, path) }

    override suspend fun read(projectId: String, path: String): Result<AgentWorkspace.FileContent> =
        runCatching { workspace.read(projectId, path) }

    override suspend fun changes(sessionId: String): List<FileChange> = changeLog.changes(sessionId)

    override suspend fun deleteSession(sessionId: String) {
        changeLog.delete(sessionId)
    }

    override suspend fun deleteProject(projectId: String) {
        workspace.deleteProject(projectId)
    }
}
