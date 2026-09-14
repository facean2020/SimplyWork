package com.yangz3720.simplywork.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上下文预算推导（v1.1）：用户定的规则表、模型名规格解析、以及四级优先级的裁决。
 */
class ContextBudgetTest {

    // ----- 规则表（用户原话：1M 以上砍到 200K，不到 1M 砍一半，下限 200K，模型本身不足 200K 就按模型本身） -----

    @Test
    fun `small models are used as-is`() {
        assertEquals(32_000, ContextBudget.derive(32_000))
        assertEquals(128_000, ContextBudget.derive(128_000))
        // 边界：正好 200K 不算"不足 200K"，走砍一半再抬回下限 → 仍是 200K
        assertEquals(200_000, ContextBudget.derive(200_000))
        assertEquals(199_999, ContextBudget.derive(199_999))
    }

    @Test
    fun `mid-range models are halved but never below the floor`() {
        assertEquals(250_000, ContextBudget.derive(500_000))
        assertEquals(200_000, ContextBudget.derive(400_000)) // 400k/2 = 200k，正好在下限
        assertEquals(200_000, ContextBudget.derive(300_000)) // 150k → 抬到 200k
    }

    @Test
    fun `huge models are capped at the floor`() {
        assertEquals(200_000, ContextBudget.derive(1_000_000))
        assertEquals(200_000, ContextBudget.derive(2_000_000))
    }

    @Test
    fun `the 1M boundary is deliberately discontinuous, as specified`() {
        // 规则的**字面**结果：999K 砍一半 = 499.5K，而 1M 只给 200K。
        // 这不是笔误，是"1M 以上按够用就好处理"的直接推论；钉在这里免得以后被
        // 当成 bug 顺手改成单调函数。
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

    // ----- 模型名里的规格标记 -----

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
        // 刻意不维护"模型名 → 上下文"对照表：猜大了请求会被服务端直接拒掉。
        assertNull(ContextBudget.fromModelName("gpt-4o"))
        assertNull(ContextBudget.fromModelName("deepseek-chat"))
        assertNull(ContextBudget.fromModelName("claude-3-5-sonnet-20241022"))
        assertNull(ContextBudget.fromModelName("gemini-1.5-pro"))
        // 纯数字年份不能被当成规格
        assertNull(ContextBudget.fromModelName("model-2024"))
        assertNull(ContextBudget.fromModelName("gpt-4-0613"))
    }

    // ----- 四级裁决：手动 > 服务商 > 模型名 > 默认 -----

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
        // 设置页要能说"你定的是 64k，模型本身有 500k"
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
