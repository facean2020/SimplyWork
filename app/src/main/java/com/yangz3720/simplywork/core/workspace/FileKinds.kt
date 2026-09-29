package com.yangz3720.simplywork.core.workspace

/**
 * File kind decisions (pure functions): preview must decide "Markdown rendering / plain text / no preview".
 * Extensions only -- binary detection is left to NUL bytes in the content (far more reliable than an extension allowlist).
 */
object FileKinds {

    private val MARKDOWN = setOf("md", "markdown")

    fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()

    fun isMarkdown(path: String): Boolean = extension(path) in MARKDOWN
}
