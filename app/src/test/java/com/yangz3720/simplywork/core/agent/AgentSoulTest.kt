package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
