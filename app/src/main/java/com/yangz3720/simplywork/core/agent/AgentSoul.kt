package com.yangz3720.simplywork.core.agent

import java.io.File

const val SOUL_DIR: String = "souls"

class AgentSoul(private val root: File) {

    private val updateLock = Any()

    fun loadProjectSoul(projectId: String): String? {
        val soulFile = getSoulFile(projectId)
        return if (soulFile.exists() && soulFile.isFile) {
            soulFile.readText()
        } else {
            soulFile.parentFile?.mkdirs()
            soulFile.createNewFile()
            null
        }
    }

    fun updateSoul(projectId: String, content: String) {
        val soulFile = getSoulFile(projectId)
        synchronized(updateLock) {
            soulFile.parentFile.mkdirs()
            soulFile.writeText(content)
        }
    }

    fun ensureSoulExists(projectId: String): File {
        val soulFile = getSoulFile(projectId)
        if (!soulFile.exists()) {
            soulFile.parentFile.mkdirs()
            soulFile.createNewFile()
        }
        return soulFile
    }

    private fun getSoulFile(projectId: String): File {
        return root.resolve(SOUL_DIR).resolve("$projectId.md")
    }
}
