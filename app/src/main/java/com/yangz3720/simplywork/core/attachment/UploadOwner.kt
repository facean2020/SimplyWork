package com.yangz3720.simplywork.core.attachment

/**
 * Upload seam (v1.8): a picked file → a [PendingUpload] the model can consume.
 *
 * The ViewModel only knows this interface, so attaching stays testable on the JVM: the real implementation
 * (`AndroidUploadLoader`) resolves a `content://` URI, decodes the image and dispatches documents through
 * [DocumentExtractorFactory]; a test hands over bytes directly.
 *
 * Failure is `Result.failure` with a user-facing reason ("暂不支持该格式") -- the caller shows it next to the
 * input bar, since the user is standing right there at the picker.
 */
interface UploadOwner {

    /** Loads one picked URI. [name] is the display name resolved by the caller (null = the loader resolves it). */
    suspend fun load(uri: String, name: String? = null): Result<PendingUpload>

    /**
     * Drops a picked-but-unsent upload's stored bytes (the image blob written at pick time).
     * No-op when nothing was stored -- a document extraction holds no file. Sent uploads are
     * never released: their blob is owned by the session payload that references it.
     */
    suspend fun release(uploadId: String) {}
}

/** An upload failure whose [message] is written for the user (unsupported format / undecodable / too large). */
class UploadException(message: String) : Exception(message)
