package com.yangz3720.simplywork.core.attachment

/**
 * A document the user picked, in the only form an extractor needs: display name, declared MIME and the raw bytes.
 *
 * No Android type appears here on purpose -- an extractor is fed a `ByteArray`, so every format provider stays
 * runnable on the JVM (pdf/word parsers included, once they exist).
 */
class UploadDocument(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}

/**
 * What a document format provider made of one upload.
 *
 * Three outcomes, not two: "this format is not supported yet" ([Unsupported]) is a different thing from
 * "the content could not be read" ([Failed]), and only the latter blames the file.
 */
sealed interface DocumentExtraction {

    /** Text the model can read. [truncated] = the source was longer and only the head was kept. */
    data class Success(val name: String, val text: String, val truncated: Boolean = false) : DocumentExtraction

    /** No provider claims this format (pdf / word before their providers land). */
    data class Unsupported(val name: String) : DocumentExtraction

    /** A provider claimed it, but the content cannot become text. [reason] is shown to the user as-is. */
    data class Failed(val name: String, val reason: String) : DocumentExtraction
}

/**
 * One document format -- the provider interface of [DocumentExtractorFactory] (v1.8).
 *
 * Adding pdf/word later means one implementation of this interface: nothing in the UI, the ViewModel or
 * the agent loop learns a file extension.
 */
interface DocumentTextExtractor {

    /** Stable identifier (error messages / tests). */
    val id: String

    /** Whether this provider handles [document]; decided by name and declared MIME only, never by reading the bytes. */
    fun supports(document: UploadDocument): Boolean

    /** Never throws for unreadable content: that outcome is [DocumentExtraction.Failed]. */
    fun extract(document: UploadDocument): DocumentExtraction
}
