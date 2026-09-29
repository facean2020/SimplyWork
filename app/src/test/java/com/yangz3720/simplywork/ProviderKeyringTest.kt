package com.yangz3720.simplywork

import com.yangz3720.simplywork.infrastructure.model.config.ModelProviderPresets
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KeyringLogic] pure-function transform tests (multi-provider keys + dynamic selection). */
class KeyringLogicTest {

    // ----- migrateLegacy -----

    @Test
    fun `migrateLegacy with null returns default presets`() {
        val state = KeyringLogic.migrateLegacy(null)
        assertEquals(ModelProviderPresets.ALL.map { it.id }, state.accounts.map { it.id })
        assertNull(state.activeId)
        assertEquals("", state.activeModel)
    }

    @Test
    fun `migrateLegacy maps preset baseUrl to preset account with key`() {
        val legacy = """{"baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-abc","defaultModel":"deepseek-chat","timeoutMs":45000}"""
        val state = KeyringLogic.migrateLegacy(legacy)
        val deepseek = state.accounts.first { it.id == "deepseek" }
        assertEquals("sk-abc", deepseek.apiKey)
        assertEquals(4, state.accounts.size)
        assertEquals("deepseek", state.activeId)
        assertEquals("deepseek-chat", state.activeModel)
        assertEquals(45_000L, state.timeoutMs)
    }

    @Test
    fun `migrateLegacy registers unknown baseUrl as custom account`() {
        val legacy = """{"baseUrl":"https://my.local/v1","apiKey":"k1","defaultModel":"m1","timeoutMs":60000}"""
        val state = KeyringLogic.migrateLegacy(legacy)
        val custom = state.accounts.first { it.id == KeyringLogic.CUSTOM_LEGACY_ID }
        assertEquals("https://my.local/v1", custom.baseUrl)
        assertEquals("k1", custom.apiKey)
        assertEquals(KeyringLogic.CUSTOM_LEGACY_ID, state.activeId)
        assertEquals(5, state.accounts.size)
    }

    @Test
    fun `migrateLegacy with broken json returns defaults`() {
        val state = KeyringLogic.migrateLegacy("{not json")
        assertEquals(ModelProviderPresets.ALL, state.accounts)
        assertNull(state.activeId)
    }

    // ----- withKey / upsertCustom / removeAccount -----

    @Test
    fun `withKey replaces trimmed key of matching account only`() {
        val state = KeyringState()
        val updated = KeyringLogic.withKey(state, "openrouter", "  sk-or  ")
        assertEquals("sk-or", updated.accounts.first { it.id == "openrouter" }.apiKey)
        assertEquals("", updated.accounts.first { it.id == "deepseek" }.apiKey)
    }

    @Test
    fun `upsertCustom appends new and replaces existing`() {
        val state = KeyringState()
        val added = KeyringLogic.upsertCustom(state, ProviderAccount("custom-1", "A", "https://a/v1"))
        assertEquals(5, added.accounts.size)
        val replaced = KeyringLogic.upsertCustom(added, ProviderAccount("custom-1", "A2", "https://a2/v1"))
        assertEquals(5, replaced.accounts.size)
        assertEquals("A2", replaced.accounts.first { it.id == "custom-1" }.name)
    }

    @Test
    fun `removeAccount clears selection when removing active`() {
        val state = KeyringLogic.selectModel(KeyringState(), "custom-1", "m1")
        val removed = KeyringLogic.removeAccount(state, "custom-1")
        assertNull(removed.accounts.firstOrNull { it.id == "custom-1" })
        assertNull(removed.activeId)
        assertEquals("", removed.activeModel)
    }

    @Test
    fun `removeAccount keeps selection when removing other`() {
        val state = KeyringLogic.selectModel(KeyringState(), "deepseek", "deepseek-chat")
        val removed = KeyringLogic.removeAccount(state, "custom-1")
        assertEquals("deepseek", removed.activeId)
        assertEquals("deepseek-chat", removed.activeModel)
    }

    // ----- selectModel / toModelConfig -----

    @Test
    fun `selectModel sets active id and trimmed model`() {
        val state = KeyringLogic.selectModel(KeyringState(), "qwen", "  qwen-max  ")
        assertEquals("qwen", state.activeId)
        assertEquals("qwen-max", state.activeModel)
    }

    @Test
    fun `toModelConfig assembles active selection`() {
        val state = KeyringLogic.selectModel(KeyringLogic.withKey(KeyringState(), "deepseek", "sk"), "deepseek", "deepseek-chat")
        val config = KeyringLogic.toModelConfig(state)!!
        assertEquals("https://api.deepseek.com/v1", config.baseUrl)
        assertEquals("sk", config.apiKey)
        assertEquals("deepseek-chat", config.defaultModel)
    }

    @Test
    fun `toModelConfig returns null without selection or model`() {
        assertNull(KeyringLogic.toModelConfig(KeyringState()))
        val noModel = KeyringLogic.migrateLegacy(null).copy(activeId = "deepseek", activeModel = "")
        assertNull(KeyringLogic.toModelConfig(noModel))
    }

    @Test
    fun `withTimeout enforces lower bound`() {
        assertEquals(60_000L, KeyringLogic.withTimeout(KeyringState(), 60_000L).timeoutMs)
        assertEquals(1_000L, KeyringLogic.withTimeout(KeyringState(), 0).timeoutMs)
        assertTrue(KeyringLogic.withTimeout(KeyringState(), -5L).timeoutMs >= 1_000L)
    }

    // ----- withModelContexts / contextKey (v1.1: learn the context length the provider reports) -----

    @Test
    fun `learned model contexts are keyed by provider and model`() {
        // provider is required: the same model name need not share the spec across endpoints
        assertEquals("openrouter/gpt-6-luna", KeyringLogic.contextKey("openrouter", "gpt-6-luna"))
    }

    @Test
    fun `withModelContexts merges instead of replacing`() {
        val first = KeyringLogic.withModelContexts(KeyringState(), mapOf("openrouter/a" to 100))
        val second = KeyringLogic.withModelContexts(first, mapOf("openrouter/b" to 200))

        assertEquals(mapOf("openrouter/a" to 100, "openrouter/b" to 200), second.modelContextTokens)
    }

    @Test
    fun `withModelContexts drops non-positive values and no-ops on an empty batch`() {
        val state = KeyringState()

        // official OpenAI/DeepSeek /models report no context -> what we learn is an empty map, and it must be returned as-is
        assertTrue(KeyringLogic.withModelContexts(state, emptyMap()) === state)
        assertTrue(KeyringLogic.withModelContexts(state, mapOf("x/y" to 0, "x/z" to -1)) === state)

        val mixed = KeyringLogic.withModelContexts(state, mapOf("x/ok" to 128_000, "x/bad" to 0))
        assertEquals(mapOf("x/ok" to 128_000), mixed.modelContextTokens)
    }
}
