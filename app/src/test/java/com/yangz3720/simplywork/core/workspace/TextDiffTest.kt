package com.yangz3720.simplywork.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextDiff pure-function tests: diff lines, exact line counts, and the two boundaries where it admits
 * it cannot compute one (diff region too large / single line too long).
 */
class TextDiffTest {

    private fun diff(before: String, after: String, contextLines: Int = 2, maxLines: Int = 200) =
        TextDiff.compute(before, after, contextLines = contextLines, maxLines = maxLines)

    private fun rendered(result: TextDiff.Result): List<String> =
        result.lines.map { "${it.prefix}${it.text}" }

    @Test
    fun `identical content reports no change at all`() {
        val result = diff("a\nb", "a\nb")
        assertEquals(0, result.addedLines)
        assertEquals(0, result.removedLines)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `created file is all additions with exact count`() {
        val result = diff("", "x\ny\n")
        assertEquals(2, result.addedLines)
        assertEquals(0, result.removedLines)
        assertEquals(listOf("+x", "+y"), rendered(result))
    }

    @Test
    fun `cleared file is all removals`() {
        val result = diff("x\ny\n", "")
        assertEquals(0, result.addedLines)
        assertEquals(2, result.removedLines)
        assertEquals(listOf("-x", "-y"), rendered(result))
    }

    @Test
    fun `append keeps the tail of the original as context`() {
        val result = diff("a\nb", "a\nb\nc")
        assertEquals(1, result.addedLines)
        assertEquals(0, result.removedLines)
        assertEquals(listOf(" a", " b", "+c"), rendered(result))
    }

    @Test
    fun `single middle edit shows removal before addition, with context`() {
        val result = diff("1\n2\n3", "1\nx\n3")
        assertEquals(1, result.addedLines)
        assertEquals(1, result.removedLines)
        assertEquals(listOf(" 1", "-2", "+x", " 3"), rendered(result))
    }

    @Test
    fun `common lines inside the changed region stay as context`() {
        val result = diff("a\nb\nc\nd", "a\nX\nc\nY\nd")
        assertEquals(2, result.addedLines)
        assertEquals(1, result.removedLines)
        assertEquals(listOf(" a", "-b", "+X", " c", "+Y", " d"), rendered(result))
    }

    @Test
    fun `context window is bounded by the requested size`() {
        val result = diff("1\n2\n3\n4\n5", "1\nX\n3\n4\n5", contextLines = 1)
        assertEquals(listOf(" 1", "-2", "+X", " 3"), rendered(result))
    }

    @Test
    fun `huge edited region refuses to guess counts`() {
        val before = (1..500).joinToString("\n") { "old$it" }
        val after = (1..500).joinToString("\n") { "new$it" }
        val result = diff(before, after)
        assertNull(result.addedLines)
        assertNull(result.removedLines)
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `huge created file keeps exact counts and reports what it omitted`() {
        val after = (1..500).joinToString("\n") { "l$it" }
        val result = diff("", after, maxLines = 10)
        assertEquals(500, result.addedLines)
        assertEquals(0, result.removedLines)
        assertEquals(10, result.lines.size)
        assertEquals(490, result.omittedLines)
        assertEquals("+l1", rendered(result).first())
    }

    @Test
    fun `overlong line is clipped to the per-line cap`() {
        val result = diff("", "x".repeat(1_000))
        assertEquals(TextDiff.MAX_LINE_CHARS, result.lines.first().text.length)
    }

    @Test
    fun `crlf input does not show as a whole-file rewrite`() {
        val result = diff("a\r\nb\r\nc", "a\nb\nc")
        assertEquals(0, result.addedLines)
        assertEquals(0, result.removedLines)
    }
}
