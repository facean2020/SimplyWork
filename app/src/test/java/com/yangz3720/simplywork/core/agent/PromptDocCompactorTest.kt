package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDocCompactorTest {

    private val estimator = HeuristicTokenEstimator()

    @Test
    fun `text under the budget is returned untouched`() {
        val text = "# 标题\n\n正文   \n\n\n\n第二段"

        assertEquals(text, PromptDocCompactor.compact(text, estimator))
    }

    @Test
    fun `a document that fits keeps its original formatting`() {
        // Padding is only removed once the budget is actually exceeded: a short document is not reformatted.
        val padded = "# 标题\n\n\n\n" + "正文" + " ".repeat(20)

        assertEquals(padded, PromptDocCompactor.compact(padded, estimator))
    }

    @Test
    fun `whitespace padding is dropped before anything is cut`() {
        val lines = (1..300).joinToString("\n") { "规则 $it" }
        val padded = "# 标题\n\n" + "\n".repeat(50) + lines + " ".repeat(20) + "\n\n\n"

        val compacted = PromptDocCompactor.compact(padded, estimator, maxTokens = 300)

        assertTrue(compacted.startsWith("# 标题\n\n规则 1\n规则 2\n"))
        assertFalse(compacted.contains("\n\n\n"))
        assertFalse(compacted.contains(" \n"))
    }

    @Test
    fun `a blank document compacts to nothing`() {
        assertEquals("", PromptDocCompactor.compact("  \n\n \t", estimator))
    }

    @Test
    fun `an over-budget document keeps whole lines from the top and declares the cut`() {
        val long = (1..2_000).joinToString("\n") { "规则 $it" }

        val compacted = PromptDocCompactor.compact(long, estimator, maxTokens = 200)

        assertTrue(compacted.startsWith("规则 1\n"))
        assertTrue(compacted.endsWith(PromptDocCompactor.TRUNCATION_MARKER))
        assertTrue(estimator.estimate(compacted) <= 200)
    }

    @Test
    fun `a single line longer than the whole budget is cut by characters instead of being dropped`() {
        val oneLine = "规".repeat(5_000)

        val compacted = PromptDocCompactor.compact(oneLine, estimator, maxTokens = 100)

        assertTrue(compacted.startsWith("规"))
        assertTrue(compacted.contains(PromptDocCompactor.TRUNCATION_MARKER.trim()))
        assertTrue(estimator.estimate(compacted) <= 100)
    }
}
