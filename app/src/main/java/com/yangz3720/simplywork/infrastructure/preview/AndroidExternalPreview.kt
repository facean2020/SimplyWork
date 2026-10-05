package com.yangz3720.simplywork.infrastructure.preview

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.core.workspace.ExternalPreview
import com.yangz3720.simplywork.core.workspace.ExternalPreviewException
import com.yangz3720.simplywork.core.workspace.FileKinds

/**
 * [ExternalPreview]'s Android implementation: `FileProvider` + `ACTION_VIEW`.
 *
 * The workspace sits in app-private storage, so `file://` URIs are useless here (since API 24 a `file://` URI outside
 * our own process throws FileUriExposedException, and even before that the other app had no read access). What we hand
 * over instead is a `content://` URI minted by [FileProvider] plus `FLAG_GRANT_READ_URI_PERMISSION` -- a read grant for
 * that one file, revoked when the receiving app's task ends.
 *
 * That is also why the provider's path config (`res/xml/file_paths.xml`) exposes exactly one directory,
 * `agent_workspace/`, rather than all of `filesDir`: the app's session stores and key material live in the same
 * private tree, and "preview a wav" must not become "let any installed app read the whole private storage".
 *
 * MIME comes from the extension; an unknown one degrades to the wildcard type rather than being refused -- the chooser
 * then offers everything, which is the honest answer and lets the user pick (a text editor for a `.log`, say).
 */
class AndroidExternalPreview(
    context: Context,
    private val workspace: AgentWorkspace
) : ExternalPreview {

    // Application context on purpose: the seam outlives any Activity (it belongs to the app container), and the
    // intents below are all launched with FLAG_ACTIVITY_NEW_TASK for the same reason.
    private val appContext: Context = context.applicationContext

    // runCatching here wraps a synchronous sequence (resolve + intent launch); there is no suspension point inside it,
    // so there is no question of swallowing a cancellation along with a real failure.
    override suspend fun open(projectId: String, path: String): Result<Unit> = runCatching {
        // Path safety lives in the workspace: an escape throws before we ever touch FileProvider.
        val file = workspace.fileForRead(projectId, path)
        val mime = mimeOf(path)
        val uri = FileProvider.getUriForFile(appContext, authority(), file)

        // 1st choice: an app that declares it can view this type (gallery / music player / viewer).
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (view.resolveActivity(appContext.packageManager) != null) {
            launch(view)
            return@runCatching
        }

        // Fallback: nothing claims to *view* it, but something can still *receive* it -- a media player that only
        // registers for ACTION_SEND, "save to Drive", etc. Better than a dead end on a perfectly readable file.
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (send.resolveActivity(appContext.packageManager) == null) {
            throw ExternalPreviewException("没有能打开该文件的应用，可以先分享或导出它")
        }
        launch(send)
    }

    /**
     * Wraps the target in a chooser instead of starting it directly: the user picks the app rather than us guessing
     * at a default, and "which app is the default" is not a decision this app should own. The grant flag is set on the
     * chooser as well as the target -- the resolver decides what to hand to the chosen app, and a picker that forgets
     * to forward the grant would leave it staring at a URI it cannot read.
     */
    private fun launch(target: Intent) {
        appContext.startActivity(
            Intent.createChooser(target, CHOOSER_TITLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Must equal the provider's `android:authorities` in the manifest (`${applicationId}.fileprovider`). */
    private fun authority(): String = "${appContext.packageName}.fileprovider"

    private fun mimeOf(path: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileKinds.extension(path)) ?: "*/*"

    private companion object {
        const val CHOOSER_TITLE = "用外部应用打开"
    }
}
