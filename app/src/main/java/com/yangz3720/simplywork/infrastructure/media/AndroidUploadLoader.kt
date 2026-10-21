package com.yangz3720.simplywork.infrastructure.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.yangz3720.simplywork.core.attachment.DocumentExtraction
import com.yangz3720.simplywork.core.attachment.DocumentExtractorFactory
import com.yangz3720.simplywork.core.attachment.ImageSizing
import com.yangz3720.simplywork.core.attachment.PendingUpload
import com.yangz3720.simplywork.core.attachment.UploadDocument
import com.yangz3720.simplywork.core.attachment.UploadException
import com.yangz3720.simplywork.core.attachment.UploadKind
import com.yangz3720.simplywork.core.attachment.UploadKinds
import com.yangz3720.simplywork.core.attachment.UploadOwner
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [UploadOwner] (v1.8): `content://` URI → what the model can consume.
 *
 * Two paths only, and the split is [UploadKinds]' decision, not this class's:
 * - image  → decoded at a bounded size and re-encoded ([ImageSizing]) so the payload stays cheap to resend;
 * - document → bytes go to [DocumentExtractorFactory], which picks the format provider (txt/md today).
 *
 * Nothing here knows a document format: adding pdf/word is a new provider in the factory, and this file does
 * not change. The document picker is opened without a MIME filter for the same reason -- the accepted formats
 * live in one place, and a file that nobody claims comes back as an explicit "暂不支持" instead of a silently
 * empty message.
 */
class AndroidUploadLoader(
    context: Context,
    private val factory: DocumentExtractorFactory = DocumentExtractorFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UploadOwner {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun load(uri: String, name: String?): Result<PendingUpload> = withContext(ioDispatcher) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext Result.failure(UploadException("无法识别所选文件"))
        val displayName = name ?: displayNameOf(parsed)
        val mimeType = runCatching { resolver.getType(parsed) }.getOrNull().orEmpty()
        when (UploadKinds.of(displayName, mimeType)) {
            UploadKind.Image -> prepareImage(parsed, displayName)
            UploadKind.Document -> extractDocument(parsed, displayName, mimeType)
            null -> Result.failure(
                UploadException("暂不支持「$displayName」这种格式，目前支持图片和 txt / md 文档")
            )
        }
    }

    private fun prepareImage(uri: Uri, name: String): Result<PendingUpload> {
        val source = openStream(uri) { it.readBytes() }
            ?: return Result.failure(UploadException("无法读出这张图片，换个格式或重试"))
        if (source.size > MAX_IMAGE_BYTES) {
            return Result.failure(UploadException("图片过大（超过 ${MAX_IMAGE_BYTES / (1024 * 1024)} MB）"))
        }
        // Bounds first: decoding a 12 MP photo straight into memory can OOM before any scaling happens
        // (a bounds-only decode fills the options and deliberately returns no bitmap).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Result.failure(UploadException("无法读出这张图片，换个格式或重试"))
        }
        val sample = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = BitmapFactory.decodeByteArray(
            source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return Result.failure(UploadException("无法解码这张图片，换个格式或重试"))

        return try {
            val fitted = ImageSizing.fitted(decoded.width, decoded.height)
            val bitmap = fitted?.let { (width, height) ->
                Bitmap.createScaledBitmap(decoded, width, height, true)
            } ?: decoded
            val encoded = ByteArrayOutputStream()
            // Lossy WebP keeps alpha (screenshots would otherwise get a black background) and is far smaller
            // than PNG; every vision-capable provider understands it.
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, encoded)
            if (bitmap !== decoded) bitmap.recycle()
            val bytes = encoded.toByteArray()
            Result.success(
                PendingUpload(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    kind = UploadKind.Image,
                    sizeBytes = bytes.size.toLong(),
                    image = Attachment.Image(
                        mimeType = WEBP_MIME,
                        base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        name = name
                    )
                )
            )
        } finally {
            decoded.recycle()
        }
    }

    private fun extractDocument(uri: Uri, name: String, mimeType: String): Result<PendingUpload> {
        val bytes = openStream(uri) { it.readBytes() }
            ?: return Result.failure(UploadException("无法读取「$name」"))
        val document = UploadDocument(name = name, mimeType = mimeType, bytes = bytes)
        return when (val extraction = factory.extract(document)) {
            is DocumentExtraction.Success -> Result.success(
                PendingUpload(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    kind = UploadKind.Document,
                    sizeBytes = document.sizeBytes,
                    text = extraction.text,
                    truncated = extraction.truncated
                )
            )
            is DocumentExtraction.Unsupported ->
                Result.failure(UploadException("暂不支持「$name」这种格式，目前支持图片和 txt / md 文档"))
            is DocumentExtraction.Failed -> Result.failure(UploadException(extraction.reason))
        }
    }

    private fun <T> openStream(uri: Uri, block: (InputStream) -> T): T? =
        runCatching { resolver.openInputStream(uri)?.use(block) }.getOrNull()

    private fun displayNameOf(uri: Uri): String {
        val fromProvider = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "附件"
    }

    private companion object {
        const val WEBP_QUALITY = 85
        const val WEBP_MIME = "image/webp"

        /** Well above any phone camera JPEG; the guard exists to fail clearly instead of decoding into an OOM. */
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    }
}
