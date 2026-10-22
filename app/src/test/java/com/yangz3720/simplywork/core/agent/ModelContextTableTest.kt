package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The built-in context table. What really has to be guarded is the **direction of being wrong** -- an
 * overestimate gets the request refused by the server, an underestimate merely compacts early, so longest-prefix
 * matching is worth pinning down on its own.
 *
 * The model ids and the expected numbers come from the live catalogues (OpenRouter `/models` and Bailian
 * `/models`, 2026-10-21) plus the official spec pages listed on [ModelContextTable], so a failure means the table
 * has drifted from reality.
 */
class ModelContextTableTest {

    @Test
    fun `prefix match covers dated and snapshot suffixes`() {
        assertEquals(1_050_000, ModelContextTable.lookup("gpt-6-astra"))
        assertEquals(1_050_000, ModelContextTable.lookup("gpt-6-astra-2026-04-30"))
        assertEquals(1_000_000, ModelContextTable.lookup("qwen3.8-max-0902"))
        assertEquals(1_000_000, ModelContextTable.lookup("deepseek-v4-pro-0813"))
    }

    @Test
    fun `matching is case insensitive and trimmed`() {
        assertEquals(1_000_000, ModelContextTable.lookup("  DeepSeek-Flash "))
        assertEquals(1_050_000, ModelContextTable.lookup("GPT-6-SOL"))
    }

    @Test
    fun `the longest prefix wins so character models are not overstated 30x`() {
        // qwen-plus is 1M, qwen-plus-character only 32k -- matching the wrong one overestimates by 30x
        assertEquals(32_000, ModelContextTable.lookup("qwen-plus-character"))
        assertEquals(1_000_000, ModelContextTable.lookup("qwen-plus"))
        assertEquals(8_000, ModelContextTable.lookup("qwen-flash-character"))
        assertEquals(1_000_000, ModelContextTable.lookup("qwen-flash"))
    }

    @Test
    fun `sibling models with different windows do not bleed into each other`() {
        assertEquals(256_000, ModelContextTable.lookup("qwen3-coder-next"))
        assertEquals(1_000_000, ModelContextTable.lookup("qwen3-coder-plus"))
        assertEquals(256_000, ModelContextTable.lookup("qwen3.6-max-preview"))
        assertEquals(1_000_000, ModelContextTable.lookup("qwen3.6-plus"))
        assertEquals(80_000, ModelContextTable.lookup("qwen3-30b-a3b-thinking-2507"))
        assertEquals(128_000, ModelContextTable.lookup("qwen3-30b-a3b-instruct-2507"))
    }

    @Test
    fun `models outside the table stay unknown instead of getting an invented number`() {
        assertNull(ModelContextTable.lookup("some-local-model"))
        assertNull(ModelContextTable.lookup("totally-unknown-model"))
        assertNull(ModelContextTable.lookup(""))
        assertNull(ModelContextTable.lookup("   "))
        assertNull(ModelContextTable.lookup("/"))
    }

    @Test
    fun `the separator style vendors use does not change the answer`() {
        // Same model, four spellings -- Bailian, a gateway path, an HF-style path, and spaces.
        listOf("qwen3.6-plus", "qwen-3.6-plus", "Qwen/Qwen3.6-Plus", "qwen 3.6 plus").forEach { model ->
            assertEquals("expected $model to resolve", 1_000_000, ModelContextTable.lookup(model))
        }
        listOf("claude-3-5-sonnet", "claude-3.5-sonnet", "anthropic/claude-3.5-sonnet-20241022").forEach { model ->
            assertEquals("expected $model to resolve", 200_000, ModelContextTable.lookup(model))
        }
        assertEquals(1_047_576, ModelContextTable.lookup("gpt-4.1"))
        assertEquals(1_047_576, ModelContextTable.lookup("gpt4.1"))
    }

    @Test
    fun `every mainstream vendor family is covered`() {
        assertEquals(1_047_576, ModelContextTable.lookup("gpt-4.1-mini"))
        assertEquals(128_000, ModelContextTable.lookup("gpt-4o-2024-08-06"))
        assertEquals(400_000, ModelContextTable.lookup("gpt-5.1-codex"))
        assertEquals(1_050_000, ModelContextTable.lookup("gpt-5.4"))
        assertEquals(200_000, ModelContextTable.lookup("o3-mini"))
        assertEquals(200_000, ModelContextTable.lookup("claude-opus-4.1"))
        assertEquals(1_000_000, ModelContextTable.lookup("claude-sonnet-4.5"))
        assertEquals(1_048_576, ModelContextTable.lookup("gemini-2.5-pro"))
        assertEquals(1_048_576, ModelContextTable.lookup("gemini-3.8-flash"))
        assertEquals(32_768, ModelContextTable.lookup("gemini-1.0-pro"))
        assertEquals(131_072, ModelContextTable.lookup("gemma-3-27b-it"))
        assertEquals(256_000, ModelContextTable.lookup("grok-4"))
        assertEquals(500_000, ModelContextTable.lookup("grok-4.6"))
        assertEquals(2_000_000, ModelContextTable.lookup("grok-4.20"))
        assertEquals(204_800, ModelContextTable.lookup("glm-4.6"))
        assertEquals(1_048_576, ModelContextTable.lookup("glm-5.3-flash"))
        assertEquals(131_072, ModelContextTable.lookup("kimi-k2-0905"))
        assertEquals(1_048_576, ModelContextTable.lookup("kimi-k3"))
        assertEquals(1_048_576, ModelContextTable.lookup("MiniMax/MiniMax-M3"))
        assertEquals(262_144, ModelContextTable.lookup("qwen3-vl-plus"))
        assertEquals(128_000, ModelContextTable.lookup("qwen2.5-vl-72b-instruct"))
        assertEquals(1_310_720, ModelContextTable.lookup("llama-4-scout"))
    }

    @Test
    fun `a version in the middle of a name still matches without stealing a sibling's window`() {
        // `glm-4v` sits in the table precisely so it does not inherit `glm-4`'s 128k
        assertEquals(8_192, ModelContextTable.lookup("glm-4v-plus"))
        assertEquals(65_536, ModelContextTable.lookup("glm-4.5v"))
        assertEquals(131_072, ModelContextTable.lookup("glm-4.6v"))
        // A different version of the same family must never be crossed
        assertEquals(1_000_000, ModelContextTable.lookup("qwen3.6-plus"))
        assertEquals(256_000, ModelContextTable.lookup("qwen3.6-max"))
        assertEquals(256_000, ModelContextTable.lookup("qwen3.5-397b"))
        assertEquals(256_000, ModelContextTable.lookup("qwen3.5-35b"))
    }

    @Test
    fun `a size suffix is not mistaken for a version`() {
        // qwen3.8-27b once resolved to the legacy qwen3-8b entry (128k) because the 27 could be skipped
        assertEquals(1_000_000, ModelContextTable.lookup("qwen3.8-27b"))
        assertEquals(128_000, ModelContextTable.lookup("qwen3-8b"))
        assertEquals(256_000, ModelContextTable.lookup("qwen3.6-35b-a3b"))
    }

    // ----- how it plays with the arbitration order -----

    @Test
    fun `the table drives the budget and is labelled as such`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = null, modelId = "deepseek-flash")

        // 1M lands in the "huge context" tier: only a 200k budget is granted
        assertEquals(200_000, plan.tokens)
        assertEquals(1_000_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.BuiltIn, plan.source)
    }

    @Test
    fun `a provider-reported value still beats the table`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = 500_000, modelId = "deepseek-flash")

        assertEquals(250_000, plan.tokens)
        assertEquals(500_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Provider, plan.source)
    }

    @Test
    fun `an explicit marker in the model name still beats the table`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = null, modelId = "qwen-long-32k")

        assertEquals(32_000, plan.tokens)
        assertEquals(32_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.ModelName, plan.source)
    }

    @Test
    fun `a manual override still reports the table's model size`() {
        val plan = ContextBudget.resolve(overrideTokens = 64_000, providerTokens = null, modelId = "gpt-6-luna")

        assertEquals(64_000, plan.tokens)
        assertEquals(1_050_000, plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Manual, plan.source)
    }

    @Test
    fun `a model neither reported nor in the table still falls back to the default`() {
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = null, modelId = "totally-unknown-model")

        assertEquals(ContextBudget.UNKNOWN_MODEL_TOKENS, plan.tokens)
        assertNull(plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Default, plan.source)
    }
}
