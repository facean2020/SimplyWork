package com.yangz3720.simplywork.core.attachment

/**
 * Upload → text dispatch (v1.8), the provider factory behind "支持上传文档".
 *
 * Dispatch rule: the **first** registered provider whose [DocumentTextExtractor.supports] matches handles the
 * document, so order is priority. Extending to pdf/word is one new provider plus one line in [defaults] --
 * the ViewModel, the input bar and the agent loop never learn a format.
 */
class DocumentExtractorFactory(
    private val extractors: List<DocumentTextExtractor> = defaults()
) {

    /** The provider that would handle this document; `null` = the format is not supported yet. */
    fun extractorFor(document: UploadDocument): DocumentTextExtractor? =
        extractors.firstOrNull { it.supports(document) }

    fun extract(document: UploadDocument): DocumentExtraction =
        extractorFor(document)?.extract(document) ?: DocumentExtraction.Unsupported(document.name)

    companion object {
        /** Registered formats, in match order. */
        fun defaults(): List<DocumentTextExtractor> = listOf(PlainTextDocumentExtractor())
    }
}
