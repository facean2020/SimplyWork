package com.yangz3720.simplywork.infrastructure.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table is only a fallback for endpoints that don't report modalities (measured: the whole 178-entry Bailian
 * catalogue and OpenAI's own `/v1/models`), so what matters is that it agrees with what those endpoints actually
 * serve -- **without** catching text-only names that share a prefix.
 *
 * Every model id in here was taken from the live catalogues (OpenRouter `/models` and Bailian `/models`,
 * 2026-10-21), not invented, so a failure means the table has drifted from reality rather than from a guess.
 */
class ModelVisionTableTest {

    @Test
    fun `the multimodal families that report nothing are recognised`() {
        // The whole 3.5 / 3.6 / 3.7-plus / 3.7-flash / 3.8 line is multimodal now -- not only the `vl`/`omni`
        // namesakes. Getting this wrong hides the image button on the provider's current models.
        listOf(
            "qwen3.8-flash", "qwen3.8-max-0902", "qwen3.8-omni-flash", "qwen3.8-27b",
            "qwen3.6-plus", "qwen3.6-flash", "qwen3.6-27b", "qwen3.5-27b", "qwen3.5-397b-a17b",
            "qwen3.7-plus", "qwen3.7-flash",
            "qwen-vl-ocr", "qwen3-vl-plus", "qwen2.5-vl-72b-instruct", "qwen3-omni-flash"
        ).forEach { model ->
            assertTrue("expected $model to be vision-capable", ModelVisionTable.supports(model))
        }
    }

    @Test
    fun `the other vendors in the same catalogue are recognised too`() {
        // The Bailian provider is a gateway: its model list also contains Zhipu, Kimi, MiniMax, DeepSeek and
        // third-party re-hosts, in three different naming styles.
        listOf(
            "ZHIPU/GLM-5.3-Flash", "ZHIPU/GLM-5.3-FlashX", "glm-4.6v", "glm-5v-turbo",
            "kimi-k2.5", "kimi-k2.6", "kimi/kimi-k2.7-code", "kimi-k3",
            "MiniMax/MiniMax-M3",
            "deepseek-v4.1-flash", "deepseek-flash", "vanchin/deepseek-v4.1-flash",
            "xiaomi/mimo-v2.6-pro-ultraspeed", "stepfun/step-5-preview"
        ).forEach { model ->
            assertTrue("expected $model to be vision-capable", ModelVisionTable.supports(model))
        }
    }

    @Test
    fun `text-only members of a vision family stay text-only`() {
        listOf(
            // Bailian / Qwen
            "qwen3.7-max", "qwen3.8-2.4t-a95b", "qwen3-coder-flash", "qwen-coder-plus-1106",
            "qwen-plus", "qwen-max", "qwen-image-3.0", "qwen3-tts-flash",
            // Zhipu / Kimi / MiniMax
            "ZHIPU/GLM-5.3", "glm-5.3-prime", "glm-4.6", "kimi-k2", "kimi-k2-thinking", "MiniMax-M2.5",
            // DeepSeek: only `-flash` and `-v4.1-flash` take images
            "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-0731", "deepseek-chat",
            // OpenAI: o3-mini is text-only under the vision-capable o3, gpt-4 under gpt-4o, gpt-oss under gpt
            "o3-mini", "o3-mini-high", "gpt-4", "gpt-4-1106-preview", "gpt-3.5-turbo", "gpt-oss-120b",
            // Google: only gemma-2 is text-only in that line
            "gemma-2-27b-it",
            // Xiaomi / Perplexity
            "xiaomi/mimo-v2.5-pro", "perplexity/sonar-deep-research"
        ).forEach { model ->
            assertFalse("expected $model to be text-only", ModelVisionTable.supports(model))
        }
    }

    @Test
    fun `the classic vision families are covered for self-hosted endpoints`() {
        listOf(
            "gpt-4o-mini", "gpt-4.1-nano", "gpt-5.2-chat", "gpt-6-astra", "o1", "o3-pro", "o4-mini",
            "claude-3.5-sonnet", "claude-4.5-opus", "claude-sonnet-5", "claude-haiku-4.5",
            "gemini-2.5-flash", "gemini-3-pro", "gemini-4-argon", "gemma-3-27b-it", "gemma-4-31b-it",
            "grok-4.20", "grok-build-0.1",
            "llama-4-scout", "llava-1.6", "internvl3", "pixtral-large", "minicpm-v",
            "mistral-small-3.2-24b-instruct", "muse-spark-1.3", "nova-pro-v1", "seed-2.0-mini",
            "nemotron-3.5-super-vl", "llama-3.2-11b-vision-instruct"
        ).forEach { model ->
            assertTrue("expected $model to be vision-capable", ModelVisionTable.supports(model))
        }
    }

    @Test
    fun `the same model in different separator styles gets the same answer`() {
        listOf(
            "qwen3.6-plus", "qwen-3.6-plus", "qwen 3.6 plus", "Qwen/Qwen3.6-Plus", "QWEN3.6-PLUS"
        ).forEach { model ->
            assertTrue("expected $model to be vision-capable", ModelVisionTable.supports(model))
        }
        listOf("claude-3-5-sonnet", "claude-3.5-sonnet", "anthropic/claude-3.5-sonnet-20241022").forEach { model ->
            assertTrue("expected $model to be vision-capable", ModelVisionTable.supports(model))
        }
    }

    @Test
    fun `an unknown model has no opinion and degrades to text-only`() {
        // `lookup` distinguishes "known text-only" from "never heard of it"; `supports` collapses both to false.
        assertNull(ModelVisionTable.lookup("some-internal-model-7b"))
        assertNull(ModelVisionTable.lookup("perceptron-mk1"))
        assertFalse(ModelVisionTable.supports("some-internal-model-7b"))
        assertNull(ModelVisionTable.lookup(""))
    }

    @Test
    fun `an explicit vision marker beats every prefix`() {
        // The name says it, so even a family whose base entry is text-only gets the flag.
        assertTrue(ModelVisionTable.supports("deepseek-v4-flash-vision-exp"))
        assertTrue(ModelVisionTable.supports("qwen3.5-ocr-vl"))
        assertEquals(true, ModelVisionTable.lookup("some-vendor/omni-7b"))
    }

    @Test
    fun `matching is case insensitive and tolerates surrounding whitespace`() {
        assertTrue(ModelVisionTable.supports("GPT-4O-MINI"))
        assertTrue(ModelVisionTable.supports("  Qwen3-VL-Plus  "))
        assertNull(ModelVisionTable.lookup("   "))
    }
}
