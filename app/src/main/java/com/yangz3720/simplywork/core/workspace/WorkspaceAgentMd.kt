package com.yangz3720.simplywork.core.workspace

import com.yangz3720.simplywork.core.agent.PromptDocCompactor
import com.yangz3720.simplywork.core.agent.TokenEstimator
import java.io.File

/**
 * Project-level instructions (`AGENTS.md`) as system-prompt context.
 *
 * The file someone edits is always `AGENTS.md` -- uppercase on purpose: Android file systems are
 * case-sensitive, so we pin exactly one name instead of guessing at `agents.md` variants.
 *
 * On the **first** load the document is migrated into [SNAPSHOT_NAME], compressed with
 * [PromptDocCompactor] when it exceeds the budget. From then on the snapshot is the injected text and
 * `AGENTS.md` is no longer consulted -- the snapshot is a *fork* of it, so the per-iteration read
 * stays cheap and the injected prompt stays stable. Deleting the snapshot makes the next load fork
 * again (that is the only way to refresh it after editing `AGENTS.md`).
 *
 * A document that cannot be read is simply not injected: project context is optional and must never
 * break a run.
 */
object WorkspaceAgentMd {

    /** Source document, written by a human or by the agent itself through the `fs_*` tools. */
    const val SOURCE_NAME = "AGENTS.md"

    /** App-owned compressed fork of [SOURCE_NAME]; once present it is the only file consulted. */
    const val SNAPSHOT_NAME = "AGENTS.SimplyWork.md"

    fun loadAgentMd(root: File, tokenEstimator: TokenEstimator): String? {
        if (!root.isDirectory) return null
        val snapshot = root.resolve(SNAPSHOT_NAME)
        if (!snapshot.exists()) {
            val source = root.resolve(SOURCE_NAME)
            if (!source.isFile) return null
            runCatching {
                snapshot.writeText(PromptDocCompactor.compact(source.readText(), tokenEstimator))
            }
        }
        return runCatching { snapshot.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
