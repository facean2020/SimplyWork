package com.yangz3720.simplywork.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The display format for context numbers. Half-step values like 499.5k are the easiest to get wrong (rounding
 * straight to 500k, and 500k happens to be the name of another tier), so they get pinned down on their own.
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
        // a 999k model halved = 499.5k; rounding that to 500k would be confused with a real 500k
        assertEquals("499.5k", formatTokens(499_500))
        assertEquals("250k", formatTokens(250_000)) // whole numbers carry no redundant .0
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

    // ----- usedPercent: the used amount as a share of the budget -----

    @Test
    fun `used share is a rounded percentage`() {
        assertEquals("（6%）", usedPercent(12_000, 200_000))
        assertEquals("（100%）", usedPercent(200_000, 200_000))
        assertEquals("（0%）", usedPercent(1, 1_000_000))
    }

    @Test
    fun `no share is shown when there is nothing to divide`() {
        // no message sent yet / budget unknown: rather show nothing than a fake 0%
        assertEquals("", usedPercent(0, 200_000))
        assertEquals("", usedPercent(12_000, 0))
        assertEquals("", usedPercent(-5, 200_000))
    }
}
