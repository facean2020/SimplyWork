package com.yangz3720.simplywork.core.attachment

/**
 * Model-ready image sizing (pure, so it is JVM-testable; the Bitmap half lives in `infrastructure/media`).
 *
 * The long edge is capped at [MAX_EDGE] because the bytes of an upload are not transient: the base64 payload is
 * persisted in the session JSONL and re-sent with every later turn, so a 12 MP camera photo (3-6 MB → ~8 MB
 * base64) would be paid for on every request. [MAX_EDGE] is the size vision models tile at anyway.
 */
object ImageSizing {

    /** Longest edge kept for an uploaded image. */
    const val MAX_EDGE = 1568

    /**
     * Power-of-two downsample factor to decode with, chosen so the decoded bitmap is still >= [maxEdge] on the
     * long edge (the exact fit is done afterwards by scaling -- decoding coarser would throw detail away).
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** Exact dimensions after scaling; `null` when the image already fits (no re-scale needed). */
    fun fitted(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int>? {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge || longEdge == 0) return null
        val ratio = maxEdge.toDouble() / longEdge
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }
}
