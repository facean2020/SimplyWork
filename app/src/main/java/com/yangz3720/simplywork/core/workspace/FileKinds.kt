package com.yangz3720.simplywork.core.workspace

/**
 * 文件类型判定（纯函数）：预览要决定"Markdown 渲染 / 纯文本 / 不预览"。
 * 只看扩展名 —— 二进制判定交给内容里的 NUL 字节（那比扩展名白名单可靠得多）。
 */
object FileKinds {

    private val MARKDOWN = setOf("md", "markdown")

    fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()

    fun isMarkdown(path: String): Boolean = extension(path) in MARKDOWN
}
