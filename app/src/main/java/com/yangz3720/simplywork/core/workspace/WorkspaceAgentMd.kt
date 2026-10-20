package com.yangz3720.simplywork.core.workspace

import java.io.File

object WorkspaceAgentMd {
    fun loadAgentMd(root: File): String? {
        if (!root.isDirectory) return null
        val f = root.resolve("AGENTS.md")
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }
}