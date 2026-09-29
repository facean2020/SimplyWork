package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The built-in context table. What really has to be guarded is the **direction of being wrong** -- an
 * overestimate gets the request refused by the server, an underestimate merely compacts early, so longest-prefix matching is worth pinning down on its own.
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
        assertNull(ModelContextTable.lookup("gpt-4o"))
        assertNull(ModelContextTable.lookup(""))
        assertNull(ModelContextTable.lookup("   "))
        assertNull(ModelContextTable.lookup("some-local-model"))
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
        val plan = ContextBudget.resolve(overrideTokens = null, providerTokens = null, modelId = "gpt-4o")

        assertEquals(ContextBudget.UNKNOWN_MODEL_TOKENS, plan.tokens)
        assertNull(plan.modelContextTokens)
        assertEquals(ContextBudgetPlan.Source.Default, plan.source)
    }
}
