package com.yangz3720.simplywork.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下文数字的展示格式。499.5k 这类**半截**数值最容易写错（直接取整会说成 500k，
 * 而 500k 正好是另一个档位的名字），所以单独钉住。
 */
class SettingsFormatTest {

    @Test
    fun `thousands are shown with a k suffix`() {
        assertEquals("24k", formatTokens(24_000))
        assertEquals("128k", formatTokens(128_000))
        assertEquals("200k", formatTokens(200_000))
    }

    @Test
    fun `half-thousands keep one decimal instead of rounding into another tier`() {
        // 999k 模型砍一半 = 499.5k；取整成 500k 会与真·500k 混淆
        assertEquals("499.5k", formatTokens(499_500))
        assertEquals("250k", formatTokens(250_000)) // 整数不带多余的 .0
    }

    @Test
    fun `millions use an M suffix`() {
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("2M", formatTokens(2_000_000))
        assertEquals("1.5M", formatTokens(1_500_000))
    }

    @Test
    fun `small or zero values are shown as-is`() {
        assertEquals("0", formatTokens(0))
        assertEquals("512", formatTokens(512))
    }
}
