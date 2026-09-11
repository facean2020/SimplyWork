package com.yangz3720.simplywork.core.agent

/**
 * "Inject Skill" seam (flow node D): contributes an extra system-prompt fragment for
 * skills matched in the current context. The skill-engine lands here later
 * (agent-core-design.md §1); v1 ships only the empty default.
 */
fun interface SkillInjector {

    /** Extra system-prompt text for this loop, or null/empty for none. */
    fun promptFragment(): String?

    companion object {
        val None = SkillInjector { null }
    }
}
