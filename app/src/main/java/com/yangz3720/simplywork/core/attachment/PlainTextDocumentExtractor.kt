package com.yangz3720.simplywork.core.attachment

/**
 * The `txt` / `md` provider (v1.8): the whole document is already text, so "extracting" is decoding it.
 *
 * The cap is not a nicety: the extracted text lands in one persisted payload, is re-sent on every turn, and
 * counts against the context budget. A 2 MB log file would take the whole conversation window, so the head is
 * kept and the truncation is declared (both to the model and in the UI chip).
 */
class PlainTextDocumentExtractor(
    private val maxChars: Int = DEFAULT_MAX_CHARS
) : DocumentTextExtractor {

    override val id: String get() = "plain-text"

    override fun supports(document: UploadDocument): Boolean {
        val mime = document.mimeType.lowercase()
        return UploadKinds.extensionOf(document.name) in EXTENSIONS ||
            mime.startsWith("text/") ||
            mime in MIME_TYPES
    }

    override fun extract(document: UploadDocument): DocumentExtraction {
        if (document.bytes.isEmpty()) {
            return DocumentExtraction.Failed(document.name, "文件为空")
        }
        // NUL bytes are the reliable binary marker (same rule as the file preview): decoding those into text
        // produces a page of mojibake that the model would then be asked to reason about.
        if (document.bytes.any { it == 0.toByte() }) {
            return DocumentExtraction.Failed(document.name, "内容疑似二进制文件，无法作为文本读取")
        }
        val decoded = String(document.bytes, Charsets.UTF_8).removePrefix(BOM)
        val truncated = decoded.length > maxChars
        return DocumentExtraction.Success(
            name = document.name,
            text = if (truncated) head(decoded) else decoded,
            truncated = truncated
        )
    }

    /** The head of the document, never cutting a surrogate pair in half (half a character renders as a replacement glyph). */
    private fun head(text: String): String {
        val limit = maxChars.coerceAtLeast(1)
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }

    companion object {
        /** Characters kept per document (~10k tokens of Chinese text): the head of a big file still fits a chat turn. */
        const val DEFAULT_MAX_CHARS = 40_000

        /** UTF-8 BOM left behind by some editors: harmless to the model, pure noise in the prompt. */
        private const val BOM = "\uFEFF"

        private val EXTENSIONS = setOf("txt", "md", "markdown")

        private val MIME_TYPES = setOf("text/plain", "text/markdown", "text/x-markdown")
    }
}

