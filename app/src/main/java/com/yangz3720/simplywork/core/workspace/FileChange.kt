package com.yangz3720.simplywork.core.workspace

import kotlinx.serialization.Serializable

/**
 * Change kind (the state words of the v1.5 change card). The UI is only responsible for translating the enum into Chinese; the decision logic is not in the UI.
 */
@Serializable
enum class FileChangeKind { Created, Updated, Appended, Moved, Deleted }

/**
 * One workspace change (v1.5): the single data source for the inline diff card in the conversation flow and for the small "new files" card at the bottom.
 *
 * It is **fact**, not guesswork: [addedLines]/[removedLines] are computed from the two real contents before and after the write, and when they cannot
 * be computed (baseline truncated by the read-side cap, or the diff region too large) they are `null` -- the card then reports only the path, never line counts, and never
 * passes off a whole new content as "added lines". The agent writes through fs_*, cloud sync goes through [AgentWorkspace.writeBytes], and
 * the latter does **not** record a change: a mirror is not "the agent changed your file".
 */
@Serializable
data class FileChange(
    /** Tool call id that triggered this change: the conversation flow uses it to attach the card under the matching entry. */
    val toolCallId: String? = null,
    val sessionId: String,
    val projectId: String,
    /** Workspace path relative to the project root. */
    val path: String,
    val kind: FileChangeKind,
    /** Path after the rename (only for [FileChangeKind.Moved]). */
    val toPath: String? = null,
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    val diff: List<TextDiff.Line> = emptyList(),
    /** Number of lines omitted after [diff]. */
    val omittedLines: Int = 0,
    val atMs: Long = 0L
)
