package com.yangz3720.simplywork.core.attachment

import com.yangz3720.simplywork.core.agent.TokenEstimator
import com.yangz3720.simplywork.infrastructure.model.model.Attachment

/**
 * An upload the user picked but has not sent yet -- one chip above the input bar (v1.8).
 *
 * Already converted: [image] carries the final payload, [text] the extracted document text. Converting at pick
 * time rather than at send time is deliberate -- a format the app cannot read must be reported while the user is
 * still looking at the picker, not discovered after they hit send.
 */
data class PendingUpload(
    val id: String,
    val name: String,
    val kind: UploadKind,
    val sizeBytes: Long = 0L,
    /** Image payload, ready to send (kind = [UploadKind.Image]). */
    val image: Attachment.Image? = null,
    /** Extracted plain text (kind = [UploadKind.Document]). */
    val text: String? = null,
    val truncated: Boolean = false
)

/**
 * Pending uploads → what the loop persists and the model receives.
 *
 * Images stay images (the client turns them into `image_url` parts); documents become text parts, which is also
 * why the transcript can render a tidy chip instead of a 40k-character wall inside the user's own bubble.
 */
object PromptUploads {

    fun attachments(uploads: List<PendingUpload>): List<Attachment> =
        uploads.mapNotNull { upload ->
            when (upload.kind) {
                UploadKind.Image -> upload.image
                UploadKind.Document -> upload.text?.let {
                    Attachment.Document(name = upload.name, text = it, truncated = upload.truncated)
                }
            }
        }

    /**
     * Context cost of one uploaded image (~1.1-1.6k tokens for a [ImageSizing.MAX_EDGE] picture with the usual
     * tiling). A flat number because the payload carries no dimensions -- and because ignoring images entirely
     * would let a picture-heavy conversation pass the compaction trigger unquestioned.
     */
    const val IMAGE_TOKEN_ESTIMATE = 1_200

    /** Token estimate of a turn's uploads: document text through [estimator], images at [IMAGE_TOKEN_ESTIMATE]. */
    fun estimatedTokens(attachments: List<Attachment>, estimator: TokenEstimator): Int =
        estimator.estimate(documentText(attachments)) +
            attachments.count { it is Attachment.Image } * IMAGE_TOKEN_ESTIMATE

    /** The document part of the payload as text (images contribute none: their cost is not textual). */
    fun documentText(attachments: List<Attachment>): String =
        attachments.filterIsInstance<Attachment.Document>()
            .joinToString("\n") { it.text }
}
