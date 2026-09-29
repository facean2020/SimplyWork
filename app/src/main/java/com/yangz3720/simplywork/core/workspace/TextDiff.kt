package com.yangz3720.simplywork.core.workspace

import kotlinx.serialization.Serializable

/**
 * Line-level diff (the single computation point of the v1.5 "change card"). Pure functions, no IO → directly testable on the JVM.
 *
 * Three stages: first strip the common prefix and suffix (`O(n)`, covering "append / small edit", the vast majority of cases), then run LCS only
 * on the **middle diff region**. When that region exceeds [MAX_LCS_LINES] no comparison is done and `null` counts are returned -- a DP over two
 * 256KB files is 25M cells and would freeze the UI outright; and passing off a whole-block replacement as a precise diff is lying.
 * Better to admit we cannot give numbers than to give wrong ones (P2).
 *
 * Create / clear (one side empty) takes a special path: all-added or all-removed lines, **not** bound by the LCS cap, so
 * creating a large file still has exact line counts.
 */
object TextDiff {

    @Serializable
    enum class LineKind { Context, Added, Removed }

    /** One diff line. [text] is already truncated per [MAX_LINE_CHARS] (a giant JSON squashed onto one line must not blow up the record). */
    @Serializable
    data class Line(val kind: LineKind, val text: String) {
        /** Unified-diff prefix character (the UI prepends it directly and does not colour semantics on top). */
        val prefix: Char
            get() = when (kind) {
                LineKind.Added -> '+'
                LineKind.Removed -> '-'
                LineKind.Context -> ' '
            }
    }

    /**
     * @param lines Diff lines with a little context, at most `maxLines` lines.
     * @param addedLines / [removedLines] Full line counts; `null` = the diff region was too large for an exact comparison.
     * @param omittedLines Lines not carried out after [lines] (the UI states how many were omitted, it does not silently swallow them).
     */
    data class Result(
        val lines: List<Line>,
        val addedLines: Int?,
        val removedLines: Int?,
        val omittedLines: Int = 0
    )

    /** Exact comparison is abandoned once the middle diff region exceeds this many lines (a `400×400` DP is already a ~640KB table). */
    const val MAX_LCS_LINES = 400

    /** Maximum characters in one line. */
    const val MAX_LINE_CHARS = 400

    fun compute(
        before: String,
        after: String,
        contextLines: Int = 2,
        maxLines: Int = 200
    ): Result {
        val a = toLines(before)
        val b = toLines(after)

        val prefix: Int
        val suffix: Int
        val ops: List<Op>
        if (a.isEmpty() || b.isEmpty()) {
            // Create / delete / clear: one side does not exist at all, there is nothing to compare and no LCS is needed.
            prefix = 0
            suffix = 0
            ops = if (a.isEmpty()) b.map { Op(LineKind.Added, it) } else a.map { Op(LineKind.Removed, it) }
        } else {
            var head = 0
            while (head < a.size && head < b.size && a[head] == b[head]) head++
            var tail = 0
            while (tail < a.size - head && tail < b.size - head &&
                a[a.size - 1 - tail] == b[b.size - 1 - tail]
            ) {
                tail++
            }
            prefix = head
            suffix = tail
            val aMid = a.subList(prefix, a.size - suffix)
            val bMid = b.subList(prefix, b.size - suffix)
            if (aMid.size > MAX_LCS_LINES || bMid.size > MAX_LCS_LINES) {
                return Result(emptyList(), null, null)
            }
            ops = lcs(aMid, bMid)
        }

        // Identical content: this is not "zero lines changed" but "no change at all", so not even context should be rendered.
        if (ops.isEmpty()) return Result(emptyList(), 0, 0)

        val added = ops.count { it.kind == LineKind.Added }
        val removed = ops.count { it.kind == LineKind.Removed }

        val all = ArrayList<Line>(ops.size + contextLines * 2)
        // Context comes from the **before** text: contextLines lines kept on each side of the diff region, so the reader can see where the change is.
        a.subList(maxOf(0, prefix - contextLines), prefix).forEach { all += Line(LineKind.Context, clip(it)) }
        ops.forEach { all += Line(it.kind, clip(it.text)) }
        val tail = minOf(suffix, contextLines)
        a.subList(a.size - suffix, a.size - suffix + tail).forEach { all += Line(LineKind.Context, clip(it)) }

        val shown = if (all.size > maxLines) all.subList(0, maxLines).toList() else all
        return Result(shown, added, removed, omittedLines = all.size - shown.size)
    }

    private data class Op(val kind: LineKind, val text: String)

    /** Classic LCS backtrack; [MAX_LCS_LINES] already keeps the size here bounded. */
    private fun lcs(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val width = m + 1
        val dp = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i * width + j] = if (a[i] == b[j]) {
                    dp[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(dp[(i + 1) * width + j], dp[i * width + j + 1])
                }
            }
        }
        val ops = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    ops += Op(LineKind.Context, a[i]); i++; j++
                }
                // A tie goes to "removed": consistent with most diff tools -- removals first, additions after.
                dp[(i + 1) * width + j] >= dp[i * width + j + 1] -> {
                    ops += Op(LineKind.Removed, a[i]); i++
                }
                else -> {
                    ops += Op(LineKind.Added, b[j]); j++
                }
            }
        }
        while (i < n) {
            ops += Op(LineKind.Removed, a[i]); i++
        }
        while (j < m) {
            ops += Op(LineKind.Added, b[j]); j++
        }
        return ops
    }

    /**
     * Empty text = zero lines; a trailing newline **terminates** the last line and does not produce an extra empty line -- otherwise
     * `"x\ny\n"` and `"x\ny"` would be treated as "one extra empty line", pure noise.
     */
    private fun toLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.lines()
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun clip(line: String): String = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
}
