package com.yangz3720.simplywork.core.attachment

import com.yangz3720.simplywork.core.agent.HeuristicTokenEstimator
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upload-side pure functions (v1.8): image sizing and the pending-upload → attachment mapping.
 * Both decide things that are expensive to get wrong (payload size / what the model actually receives),
 * so they are tested without Android in the way.
 */
class UploadSizingTest {

    @Test
    fun `sample size keeps the long edge at or above the cap`() {
        // 4000px photo -> 3 rounds of halving is still >= 1568, a 4th would be under it.
        assertEquals(2, ImageSizing.sampleSize(4000, 3000))
        assertEquals(1, ImageSizing.sampleSize(1200, 900))
        assertEquals(1, ImageSizing.sampleSize(1568, 1568))
        assertEquals(2, ImageSizing.sampleSize(3136, 3136))
    }

    @Test
    fun `sample size follows the long edge in either orientation`() {
        assertEquals(ImageSizing.sampleSize(4000, 3000), ImageSizing.sampleSize(3000, 4000))
    }

    @Test
    fun `fitting scales the long edge to the cap and keeps the aspect ratio`() {
        assertEquals(1568 to 1176, ImageSizing.fitted(4000, 3000))
        assertEquals(1176 to 1568, ImageSizing.fitted(3000, 4000))
    }

    @Test
    fun `an image that already fits is not re-scaled`() {
        assertNull(ImageSizing.fitted(800, 600))
        assertNull(ImageSizing.fitted(0, 0))
    }

    @Test
    fun `uploads become images and document text attachments`() {
        val image = Attachment.Image(id = "blob-1", mimeType = "image/webp", name = "cat.jpg")
        val uploads = listOf(
            PendingUpload(id = "1", name = "cat.jpg", kind = UploadKind.Image, image = image),
            PendingUpload(id = "2", name = "notes.md", kind = UploadKind.Document, text = "# 标题", truncated = true),
            // A document whose extraction produced nothing contributes nothing: no empty attachment is invented.
            PendingUpload(id = "3", name = "empty.md", kind = UploadKind.Document, text = null)
        )

        val attachments = PromptUploads.attachments(uploads)

        assertEquals(2, attachments.size)
        assertEquals(image, attachments[0])
        assertEquals(Attachment.Document(name = "notes.md", text = "# 标题", truncated = true), attachments[1])
        // Documents are text; an image is not free either but cannot be read off its base64 length, so it
        // contributes a flat estimated cost (otherwise a picture-only conversation never triggers compaction).
        assertEquals("# 标题", PromptUploads.documentText(attachments))
        val estimator = HeuristicTokenEstimator()
        assertEquals(estimator.estimate("# 标题") + PromptUploads.IMAGE_TOKEN_ESTIMATE, PromptUploads.estimatedTokens(attachments, estimator))
        assertEquals(0, PromptUploads.estimatedTokens(emptyList(), estimator))
        assertTrue(PromptUploads.attachments(emptyList()).isEmpty())
    }
}
