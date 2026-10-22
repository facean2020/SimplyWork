package com.yangz3720.simplywork.infrastructure.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normaliser is the piece both built-in tables sit on, so what it has to pin down is (a) that separator
 * style never changes the token list and (b) that the version-skip rule cannot cross between sibling versions.
 */
class ModelIdNormalizerTest {

    @Test
    fun `separator style does not change the token list`() {
        val expected = listOf("qwen", "3", "6", "plus")
        listOf(
            "qwen3.6-plus", "qwen-3.6-plus", "qwen 3.6 plus", "Qwen/Qwen3.6-Plus", "QWEN_3_6_PLUS"
        ).forEach { model ->
            assertEquals("tokens of $model", expected, ModelIdNormalizer.tokens(model))
        }
    }

    @Test
    fun `letters and digits are separate tokens`() {
        assertEquals(listOf("gpt", "4", "o"), ModelIdNormalizer.tokens("gpt-4o"))
        assertEquals(listOf("gpt", "4", "o"), ModelIdNormalizer.tokens("gpt4o"))
        assertEquals(listOf("qwen", "2", "5", "vl"), ModelIdNormalizer.tokens("qwen2.5vl"))
    }

    @Test
    fun `a size suffix stays one token so it cannot be swallowed as a version`() {
        // Without this rule qwen3-8b would be [qwen,3,8,b] and could skip the 27 of qwen3.8-27b.
        assertEquals(listOf("qwen", "3", "8b"), ModelIdNormalizer.tokens("qwen3-8b"))
        assertEquals(listOf("qwen", "3", "8", "27b"), ModelIdNormalizer.tokens("qwen3.8-27b"))
        assertEquals(listOf("qwen", "2", "5", "vl", "72b", "instruct"), ModelIdNormalizer.tokens("qwen2.5-vl-72b-instruct"))
    }

    @Test
    fun `only the last path segment is compared`() {
        assertEquals(listOf("glm", "5", "3", "flash"), ModelIdNormalizer.tokens("ZHIPU/GLM-5.3-Flash"))
        assertEquals(listOf("glm", "5", "3", "flash"), ModelIdNormalizer.tokens("glm-5.3-flash"))
    }

    @Test
    fun `an id that is only separators has no tokens`() {
        assertTrue(ModelIdNormalizer.tokens("").isEmpty())
        assertTrue(ModelIdNormalizer.tokens("   ").isEmpty())
        assertTrue(ModelIdNormalizer.tokens("/").isEmpty())
    }

    @Test
    fun `an exact prefix reports zero skips`() {
        assertEquals(0, ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("qwen3.6-plus"), ModelIdNormalizer.tokens("qwen3.6")))
        assertNull(ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("qwen3.5-plus"), ModelIdNormalizer.tokens("qwen3.6")))
    }

    @Test
    fun `a numeric token may be skipped when the prefix expects a name`() {
        // `qwen-vl` has to reach qwen2.5-vl / qwen3.5-vl, and `claude-sonnet` has to reach claude-3-5-sonnet
        assertEquals(2, ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("qwen2.5-vl-72b"), ModelIdNormalizer.tokens("qwen-vl")))
        assertEquals(2, ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("claude-3-5-sonnet"), ModelIdNormalizer.tokens("claude-sonnet")))
    }

    @Test
    fun `two versions of one family never collapse into each other`() {
        assertNull(ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("qwen3.5-plus"), ModelIdNormalizer.tokens("qwen3.6")))
        assertNull(ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("glm-4.7"), ModelIdNormalizer.tokens("glm-4.6")))
    }

    @Test
    fun `a version cannot be skipped to fake a vision suffix`() {
        // gpt-4-1106-preview is text-only: after skipping 1106 there is nothing left to match the `o` of gpt-4o
        assertNull(ModelIdNormalizer.matchSkips(ModelIdNormalizer.tokens("gpt-4-1106-preview"), ModelIdNormalizer.tokens("gpt-4o")))
        assertFalse(ModelIdNormalizer.matches("gpt-4-1106-preview", "gpt-4o"))
        assertTrue(ModelIdNormalizer.matches("gpt-4o-2024-08-06", "gpt-4o"))
    }
}
