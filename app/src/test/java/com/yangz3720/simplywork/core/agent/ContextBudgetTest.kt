package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Context budget derivation (v1.1): the rule table the user set, size-marker parsing off the model name, and the four-level precedence arbitration.
 */
class ContextBudgetTest {

    // ----- rule table (the user's own words: above 1M cut to 200K, under 1M halve it, floor 200K, and when the model itself has less than 200K use the model's own size) -----

    @Test
    fun `small models are used as-is`() {
        assertEquals(32_000, ContextBudget.derive(32_000))
        assertEquals(128_000, ContextBudget.derive(128_000))
        // boundary: exactly 200K does not count as "under 200K", so it goes halve-then-raise-to-floor -> still 200K
        assertEquals(200_000, ContextBudget.derive(200_000))
        assertEquals(199_999, ContextBudget.derive(199_999))
    }

    @Test
    fun `mid-range models are halved but never below the floor`() {
        assertEquals(250_000, ContextBudget.derive(500_000))
        assertEquals(200_000, ContextBudget.derive(400_000)) // 400k/2 = 200k, exactly at the floor
        assertEquals(200_000, ContextBudget.derive(300_000)) // 150k -> raised to 200k
    }

    @Test
    fun `huge models are capped at the floor`() {
        assertEquals(200_000, ContextBudget.derive(1_000_000))
        assertEquals(200_000, ContextBudget.derive(2_000_000))
    }

    @Test
    fun `the 1M boundary is deliberately discontinuous, as specified`() {
        // The **literal** outcome of the rule: 999K halved = 499.5K, while 1M only gets 200K.
        // That is no typo but a direct corollary of "anything above 1M is treated as good enough"; pinned
        // here so a future reader does not spot it as a bug and incidentally make the function monotonic.
        assertEquals(499_500, ContextBudget.derive(999_000))
        assertEquals(200_000, ContextBudget.derive(1_000_000))
    }

    @Test
    fun `unknown or nonsensical context falls back to the historical default`() {
        assertEquals(24_000, ContextBudget.derive(null))
        assertEquals(24_000, ContextBudget.derive(0))
        assertEquals(24_000, ContextBudget.derive(-1))
    }

    @Test
    fun `working window is half the budget and never zero`() {
        assertEquals(100_000, ContextBudget.workingTokenBudget(200_000))
        assertEquals(500_000, ContextBudget.workingTokenBudget(1_000_000))
        assertEquals(1, ContextBudget.workingTokenBudget(1))
    }

    // ----- size markers inside the model name -----

    @Test
    fun `size markers in the model name are honoured`() {
        assertEquals(32_000, ContextBudget.fromModelName("qwen2.5-32k"))
        assertEquals(128_000, ContextBudget.fromModelName("llama-3.1-128k"))
        assertEquals(1_000_000, ContextBudget.fromModelName("some-model-1M"))
        assertEquals(8_000, ContextBudget.fromModelName("local_8k"))
        assertEquals(64_000, ContextBudget.fromModelName("model 64k"))
    }

    @Test
    fun `names without an explicit size marker yield nothing instead of guessing`() {
        // Deliberately no "model name -> context" table is maintained: guessing too high gets the request refused outright by the server.
        assertNull(ContextBudget.fromModelName("gpt-4o"))
        assertNull(ContextBudget.fromModelName("deepseek-chat"))
        assertNull(ContextBudget.fromModelName("claude-3-5-sonnet-20241022"))
        assertNull(ContextBudget.fromModelName("gemini-1.5-pro"))
        // a bare four-digit year must not be read as a size marker
        assertNull(ContextBudget.fromModelName("model-2024"))
        assertNull(ContextBudget.fromModelName("gpt-4-0613"))
    }

    // ----- four-level arbitration: manual > provider > model name > default -----

    @Test
    fun `provider-reported context drives the budget and is labelled as such`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = 500_000, modelId = "whatever")

        assertEquals(250_000, plan.tokens)
        assertEquals(500_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Provider, plan.source)
        assertEquals(250_000, plan.compactionTriggerTokens)
        assertEquals(125_000, plan.workingTokenBudget)
    }

    @Test
    fun `model name is used when the provider reports nothing`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = null, modelId = "qwen2.5-32k")

        assertEquals(32_000, plan.tokens)
        assertEquals(32_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.ModelName, plan.source)
    }

    @Test
    fun `manual override wins but still reports the model's real size`() {
        val plan = ContextBudget.resolve(overrideTokens = 64_000, providerTokens = 500_000, modelId = "whatever")

        assertEquals(64_000, plan.tokens)
        // the settings page must be able to say "you set 64k, the model itself has 500k"
        assertEquals(500_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Manual, plan.source)
    }

    @Test
    fun `nothing known falls back to the default source`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = null, modelId = "gpt-4o")

        assertEquals(24_000, plan.tokens)
        assertNull(plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Default, plan.source)
    }

    @Test
    fun `nonsensical inputs are treated as absent`() {
        val zeroOverride = ContextBudget.resolve(overrideTokens = 0, providerTokens = -5, modelId = "gpt-4o")
        assertEquals(24_000, zeroOverride.tokens)
        assertEquals(ContextBudgetPlan.Source.Default, zeroOverride.source)
    }
}
