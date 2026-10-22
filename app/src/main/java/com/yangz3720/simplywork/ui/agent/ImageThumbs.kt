package com.yangz3720.simplywork.ui.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image thumbnails for the chat surface (v1.9 UI).
 *
 * Bytes stay in [com.yangz3720.simplywork.core.attachment.ImageBlobStore]; the UI pulls a
 * downsampled bitmap only while the attachment is on screen. Decoding at a small [THUMB_MAX_EDGE]
 * (not the full 1568-px model image) is what keeps a picture-heavy transcript cheap -- a chat
 * thumbnail is 56-96 dp on a phone, and a full-resolution bitmap per bubble would not fit.
 */
@Composable
fun rememberImageThumb(imageId: String?, loadBytes: (String) -> ByteArray?): State<ImageBitmap?> {
    // Keyed by id: switching sessions drops the previous bitmap instead of pinning every decoded frame.
    val state = remember(imageId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageId) {
        state.value = if (imageId == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadBytes(imageId)?.let { decodeThumb(it) }
            }
        }
    }
    return state
}

/**
 * One attachment thumbnail: the bitmap when it decodes, otherwise a quiet placeholder so a
 * missing blob still occupies its slot instead of collapsing the layout.
 */
@Composable
fun AttachmentThumb(
    imageId: String?,
    name: String?,
    loadBytes: (String) -> ByteArray?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val thumb by rememberImageThumb(imageId, loadBytes)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name ?: "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Text(
                text = "图",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Longest edge of a decoded thumbnail; anything larger is wasted memory for a 56-96 dp slot. */
private const val THUMB_MAX_EDGE = 256

private fun decodeThumb(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Same power-of-two strategy as ImageSizing, aimed at the thumb edge instead of the model edge.
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_MAX_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    val longEdge = maxOf(decoded.width, decoded.height)
    val scaled = if (longEdge > THUMB_MAX_EDGE && longEdge > 0) {
        val ratio = THUMB_MAX_EDGE.toFloat() / longEdge
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== decoded) decoded.recycle() }
    } else {
        decoded
    }
    return scaled.asImageBitmap()
}
