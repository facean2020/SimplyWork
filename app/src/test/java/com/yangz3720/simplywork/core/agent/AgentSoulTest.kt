package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSoulTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `each project has an independent soul file created on first read`() {
        val soul = AgentSoul(tmp.newFolder("agent_workspace"))

        assertNull(soul.loadProjectSoul("project-a"))
        soul.updateSoul("project-a", "# A\nOnly for A")
        soul.updateSoul("project-b", "# B\nOnly for B")

        assertEquals("# A\nOnly for A", soul.loadProjectSoul("project-a"))
        assertEquals("# B\nOnly for B", soul.loadProjectSoul("project-b"))
    }

    @Test
    fun `deleting a soul removes only that project's file`() {
        val soul = AgentSoul(tmp.newFolder("agent_workspace"))
        soul.updateSoul("project-a", "# A")
        soul.updateSoul("project-b", "# B")

        assertTrue(soul.deleteSoul("project-a"))

        assertNull(soul.loadProjectSoul("project-a"))
        assertEquals("# B", soul.loadProjectSoul("project-b"))
    }

    @Test
    fun `deleting a soul that never existed is not an error`() {
        val soul = AgentSoul(tmp.newFolder("agent_workspace"))

        assertTrue(soul.deleteSoul("never-used"))
        assertFalse(java.io.File(tmp.root, "agent_workspace/souls/never-used.md").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an invalid project id is rejected instead of escaping the soul directory`() {
        AgentSoul(tmp.newFolder("agent_workspace")).loadProjectSoul("../../etc/passwd")
    }
}
