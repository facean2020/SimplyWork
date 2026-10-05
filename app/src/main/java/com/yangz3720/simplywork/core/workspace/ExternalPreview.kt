package com.yangz3720.simplywork.core.workspace

/**
 * External preview seam (v1.7): hand one workspace file to an outside app (image viewer / media player / an editor),
 * so "show me this png/wav" does not require building an image decoder and an audio player inside this app.
 *
 * Why a seam rather than calling the launcher straight from the preview page:
 * 1. The sandbox lives in app-private storage (`<filesDir>/agent_workspace/projects/<projectId>/`), which no outside
 *    app can read at all -- what actually gets handed over is a short-lived `content://` read grant (see the Android
 *    implementation), and that is a decision about **which part of the app's storage becomes reachable**. The UI layer
 *    must not be the place where that decision is made.
 * 2. Android-free: [AgentWorkspace] is pure `java.io`, so the JVM tests can run the whole flow against a fake and
 *    assert what gets surfaced to the user when the launch fails.
 */
interface ExternalPreview {

    /**
     * Opens `path` (relative to the project root, same path rules as the `fs_*` tools) with an outside app.
     *
     * A successful call means "an outside app is now showing it"; there is nothing to render on our side.
     * A failure carries an [ExternalPreviewException] whose message is already user-facing text
     * (no extension / no capable app), so the caller can put it on screen as-is.
     */
    suspend fun open(projectId: String, path: String): Result<Unit>
}

/**
 * External preview refused or impossible. [message] is meant to be shown to the user directly -- it explains what
 * they can do about it ("no app can open it"), not which class threw what (same normalization idea as [WorkspaceException]).
 */
class ExternalPreviewException(message: String) : Exception(message)
