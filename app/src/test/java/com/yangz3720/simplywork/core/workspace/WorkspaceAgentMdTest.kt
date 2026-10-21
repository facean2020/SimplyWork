package com.yangz3720.simplywork.core.workspace

import com.yangz3720.simplywork.core.agent.HeuristicTokenEstimator
import com.yangz3720.simplywork.core.agent.PromptDocCompactor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceAgentMdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val estimator = HeuristicTokenEstimator()

    private fun projectDir() = tmp.newFolder("projects").resolve("p1").apply { mkdirs() }

    @Test
    fun `AGENTS md is loaded and forked into the app-owned snapshot`() {
        val dir = projectDir()
        dir.resolve(WorkspaceAgentMd.SOURCE_NAME).writeText("# 约定\n- 先跑测试")

        val loaded = WorkspaceAgentMd.loadAgentMd(dir, estimator)

        assertEquals("# 约定\n- 先跑测试", loaded)
        // The fork is what gets injected from now on.
        assertEquals("# 约定\n- 先跑测试", dir.resolve(WorkspaceAgentMd.SNAPSHOT_NAME).readText())
    }

    @Test
    fun `the snapshot wins once it exists so edits to AGENTS md no longer change the prompt`() {
        val dir = projectDir()
        dir.resolve(WorkspaceAgentMd.SOURCE_NAME).writeText("旧内容")
        WorkspaceAgentMd.loadAgentMd(dir, estimator)

        dir.resolve(WorkspaceAgentMd.SOURCE_NAME).writeText("新内容")

        assertEquals("旧内容", WorkspaceAgentMd.loadAgentMd(dir, estimator))
    }

    @Test
    fun `an over-long AGENTS md is compressed in the snapshot and the cut is declared`() {
        val dir = projectDir()
        val long = (1..4_000).joinToString("\n") { "第 $it 条：始终使用中文回答" }
        dir.resolve(WorkspaceAgentMd.SOURCE_NAME).writeText(long)

        val loaded = WorkspaceAgentMd.loadAgentMd(dir, estimator)

        assertNotNull(loaded)
        assertEquals(loaded, dir.resolve(WorkspaceAgentMd.SNAPSHOT_NAME).readText())
        assertTrue(loaded!!.contains("第 1 条"))
        assertFalse(loaded.contains("第 4000 条"))
        assertTrue(loaded.contains(PromptDocCompactor.TRUNCATION_MARKER.trim()))
        assertTrue(estimator.estimate(loaded) <= PromptDocCompactor.DEFAULT_MAX_TOKENS)
    }

    @Test
    fun `a project with no document yields nothing and creates no snapshot`() {
        val dir = projectDir()

        assertNull(WorkspaceAgentMd.loadAgentMd(dir, estimator))
        assertFalse(dir.resolve(WorkspaceAgentMd.SNAPSHOT_NAME).exists())
    }

    @Test
    fun `a missing project directory yields nothing`() {
        val dir = File(tmp.root, "nope")

        assertNull(WorkspaceAgentMd.loadAgentMd(dir, estimator))
        assertFalse(dir.exists())
    }

    @Test
    fun `a blank document is not injected`() {
        val dir = projectDir()
        dir.resolve(WorkspaceAgentMd.SOURCE_NAME).writeText("   \n\n")

        assertNull(WorkspaceAgentMd.loadAgentMd(dir, estimator))
    }
}
