package com.yangz3720.simplywork.core.workspace

import kotlinx.serialization.Serializable

/**
 * 行级差异（v1.5「改动卡片」的唯一计算点）。纯函数、无 IO → JVM 直接可测。
 *
 * 三段式：先剥掉公共前后缀（`O(n)`，覆盖"追加 / 小改"这两种绝大多数场景），只对**中间
 * 差异区**求 LCS。差异区超过 [MAX_LCS_LINES] 时不做比较，返回 `null` 计数 —— 256KB 的
 * 两个大文件做 DP 是 25M 格，会直接拖死界面；而"整块替换"冒充精确 diff 是撒谎。
 * 宁可承认给不出数字，也不给错数字（P2）。
 *
 * 新建 / 清空（一侧为空）走特判：全是增行或全是删行，**不**受 LCS 上限约束，所以
 * 新建大文件依然有精确行数。
 */
object TextDiff {

    @Serializable
    enum class LineKind { Context, Added, Removed }

    /** 一行差异。[text] 已按 [MAX_LINE_CHARS] 截断（压缩成一行的巨型 JSON 不能撑爆记录）。 */
    @Serializable
    data class Line(val kind: LineKind, val text: String) {
        /** 统一 diff 前缀字符（UI 直接拼在最前面，不额外着色语义）。 */
        val prefix: Char
            get() = when (kind) {
                LineKind.Added -> '+'
                LineKind.Removed -> '-'
                LineKind.Context -> ' '
            }
    }

    /**
     * @param lines 带少量上下文的差异行，最多 `maxLines` 行。
     * @param addedLines / [removedLines] 全量行数；`null` = 差异区过大未做精确比较。
     * @param omittedLines [lines] 之后没带出来的行数（UI 明说省略了多少，不静默吞掉）。
     */
    data class Result(
        val lines: List<Line>,
        val addedLines: Int?,
        val removedLines: Int?,
        val omittedLines: Int = 0
    )

    /** 中间差异区超过这个行数就放弃精确比较（`400×400` 的 DP 已是 ~640KB 中间表）。 */
    const val MAX_LCS_LINES = 400

    /** 单行最长字符数。 */
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
            // 新建 / 删除 / 清空：一侧整体不存在，没有可比较的东西，也无需 LCS。
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

        // 内容完全相同：不是"改了零行"，而是"没有改动"，连上下文都不该渲染。
        if (ops.isEmpty()) return Result(emptyList(), 0, 0)

        val added = ops.count { it.kind == LineKind.Added }
        val removed = ops.count { it.kind == LineKind.Removed }

        val all = ArrayList<Line>(ops.size + contextLines * 2)
        // 上下文取自**原文**：差异区两侧各留 contextLines 行，读者才知道改在哪。
        a.subList(maxOf(0, prefix - contextLines), prefix).forEach { all += Line(LineKind.Context, clip(it)) }
        ops.forEach { all += Line(it.kind, clip(it.text)) }
        val tail = minOf(suffix, contextLines)
        a.subList(a.size - suffix, a.size - suffix + tail).forEach { all += Line(LineKind.Context, clip(it)) }

        val shown = if (all.size > maxLines) all.subList(0, maxLines).toList() else all
        return Result(shown, added, removed, omittedLines = all.size - shown.size)
    }

    private data class Op(val kind: LineKind, val text: String)

    /** 经典 LCS 回溯；[MAX_LCS_LINES] 已保证这里的规模有界。 */
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
                // 平局取"删"：与多数 diff 工具一致，删在前、增在后。
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
     * 空文本 = 零行；结尾的换行符**终止**最后一行，不额外产生一个空行 —— 否则
     * `"x\ny\n"` 与 `"x\ny"` 会被当成"多了一行空行"，纯噪声。
     */
    private fun toLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.lines()
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun clip(line: String): String = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) else line
}
