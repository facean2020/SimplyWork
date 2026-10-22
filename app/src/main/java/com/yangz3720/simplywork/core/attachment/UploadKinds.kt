package com.yangz3720.simplywork.core.attachment

/** What a picked file turns into: an image payload sent as-is, or a document whose text is extracted first. */
enum class UploadKind { Image, Document }

/**
 * Pick → kind decision (pure, v1.8).
 *
 * MIME first because that is what the document picker actually reports; the extension is the fallback for
 * providers/stores that hand out `application/octet-stream` for an `.md` file. `null` = this app does not
 * accept the file at all -- reported to the user instead of silently attaching something unusable.
 */
object UploadKinds {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    private val DOCUMENT_EXTENSIONS = setOf("txt", "md", "markdown")

    fun of(name: String, mimeType: String): UploadKind? {
        val mime = mimeType.lowercase()
        val extension = extensionOf(name)
        return when {
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> UploadKind.Image
            mime.startsWith("text/") || extension in DOCUMENT_EXTENSIONS -> UploadKind.Document
            else -> null
        }
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
