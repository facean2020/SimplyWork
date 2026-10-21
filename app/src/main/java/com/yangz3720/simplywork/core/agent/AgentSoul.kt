package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import java.io.File

/** Directory under the agent workspace root holding one `<projectId>.md` per project. */
private const val SOUL_DIR = "souls"

/**
 * Per-project Markdown "Soul" (`souls/<projectId>.md`): the persistent role/preferences text a project
 * gets injected as system context on every run (see `AgentContextAssembler`).
 *
 * Lives outside the project sandbox (`projects/<projectId>/`) on purpose: the sandbox is what the
 * agent reads and writes through the `fs_*` tools, and the soul is not user content to be edited by
 * the loop.
 */
class AgentSoul(private val root: File) {

    private val updateLock = Any()

    /** The project's soul text; `null` when it has none yet (the file is created empty for the editor). */
    fun loadProjectSoul(projectId: String): String? {
        val soulFile = getSoulFile(projectId)
        return if (soulFile.isFile) {
            soulFile.readText()
        } else {
            soulFile.parentFile?.mkdirs()
            soulFile.createNewFile()
            null
        }
    }

    fun updateSoul(projectId: String, content: String) {
        val soulFile = getSoulFile(projectId)
        synchronized(updateLock) {
            soulFile.parentFile?.mkdirs()
            soulFile.writeText(content)
        }
    }

    /** Drops the soul along with its project (deleting a project must not leave this behind). */
    fun deleteSoul(projectId: String): Boolean {
        val soulFile = getSoulFile(projectId)
        synchronized(updateLock) { return soulFile.delete() || !soulFile.exists() }
    }

    private fun getSoulFile(projectId: String): File {
        require(AgentWorkspace.ProjectIdRegex.matches(projectId)) { "invalid project id: '$projectId'" }
        return root.resolve(SOUL_DIR).resolve("$projectId.md")
    }
}
