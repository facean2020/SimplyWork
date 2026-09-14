package com.yangz3720.simplywork

import android.content.SharedPreferences
import androidx.core.content.edit
import com.yangz3720.simplywork.infrastructure.model.config.ModelConfig
import com.yangz3720.simplywork.infrastructure.model.config.ModelProviderPresets
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 多服务商密钥环状态：各服务商 API Key 并存 + 当前选择（provider + model）。
 * 持久化于 SharedPreferences（明文，v1 与原 ModelConfig 同级安全性）。
 */
@Serializable
data class KeyringState(
    val accounts: List<ProviderAccount> = ModelProviderPresets.ALL,
    val activeId: String? = null,
    val activeModel: String = "",
    val timeoutMs: Long = 60_000L,
    /**
     * v1.1：非删除类调用自动批准（用户定调"只有高危操作才手动批准"）。产品默认**开启**；
     * 删除类（`RegisteredTool.destructive`）不受它影响，永远弹窗。
     */
    val autoApproveNonDestructive: Boolean = true,
    /**
     * v1.1：服务商在 /models 里报过的模型上下文长度，键为 [KeyringLogic.contextKey]。
     * 只写真实正数 —— 记一个 0 或猜测值会让"未知"和"已知"再也分不开。
     */
    val modelContextTokens: Map<String, Int> = emptyMap(),
    /** v1.1：上下文预算的手动覆盖；null = 自动推导（见 `ContextBudget.resolve`）。 */
    val contextBudgetOverride: Int? = null
)

/**
 * [KeyringState] 的纯函数变换（JVM 可测）：全部返回新状态，不触碰存储。
 */
object KeyringLogic {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 一次性迁移：旧的单配置 [ModelConfig]（KEY_MODEL_CONFIG）→ keyring state。
     * baseUrl 命中预设则填入该预设，否则注册为自定义账号；旧记录缺失/损坏返回默认态。
     */
    fun migrateLegacy(legacyJson: String?): KeyringState {
        val defaults = KeyringState()
        if (legacyJson.isNullOrBlank()) return defaults
        val legacy = runCatching { json.decodeFromString(ModelConfig.serializer(), legacyJson) }
            .getOrNull()
            ?.takeIf { it.baseUrl.isNotBlank() && it.defaultModel.isNotBlank() }
            ?: return defaults
        val preset = ModelProviderPresets.matchBaseUrl(legacy.baseUrl)
        val account = if (preset != null) {
            preset.copy(apiKey = legacy.apiKey)
        } else {
            ProviderAccount(id = CUSTOM_LEGACY_ID, name = "自定义", baseUrl = legacy.baseUrl, apiKey = legacy.apiKey)
        }
        return defaults.copy(
            accounts = defaults.accounts
                .map { if (it.id == account.id) account else it }
                .let { if (preset == null) it + account else it },
            activeId = account.id,
            activeModel = legacy.defaultModel,
            timeoutMs = legacy.timeoutMs
        )
    }

    /** 保存某服务商的 API Key。 */
    fun withKey(state: KeyringState, accountId: String, apiKey: String): KeyringState =
        state.copy(accounts = state.accounts.map { if (it.id == accountId) it.copy(apiKey = apiKey.trim()) else it })

    /** 新增或更新自定义端点（id 由调用方生成）。 */
    fun upsertCustom(state: KeyringState, account: ProviderAccount): KeyringState =
        if (state.accounts.any { it.id == account.id }) {
            state.copy(accounts = state.accounts.map { if (it.id == account.id) account else it })
        } else {
            state.copy(accounts = state.accounts + account)
        }

    /** 删除账号（预设不可删由调用方保证）；删的是当前选中则清空选择。 */
    fun removeAccount(state: KeyringState, accountId: String): KeyringState =
        state.copy(
            accounts = state.accounts.filterNot { it.id == accountId },
            activeId = if (state.activeId == accountId) null else state.activeId,
            activeModel = if (state.activeId == accountId) "" else state.activeModel
        )

    /** 切换当前服务商 + 模型（对话界面顶栏动态选择）。 */
    fun selectModel(state: KeyringState, accountId: String, model: String): KeyringState =
        state.copy(activeId = accountId, activeModel = model.trim())

    fun withTimeout(state: KeyringState, timeoutMs: Long): KeyringState =
        state.copy(timeoutMs = timeoutMs.coerceAtLeast(1_000L))

    /** v1.1：非删除类调用是否自动批准。 */
    fun withAutoApprove(state: KeyringState, enabled: Boolean): KeyringState =
        state.copy(autoApproveNonDestructive = enabled)

    /**
     * v1.1：批量记录服务商报来的上下文长度。一次落盘 + 一次版本自增，而不是每个模型一次 ——
     * 否则"拉一次模型列表"会把运行时重建触发几十次。非正数一律丢弃（见 [KeyringState]）。
     */
    fun withModelContexts(state: KeyringState, learned: Map<String, Int>): KeyringState {
        val usable = learned.filterValues { it > 0 }
        if (usable.isEmpty()) return state
        return state.copy(modelContextTokens = state.modelContextTokens + usable)
    }

    /** v1.1：手动指定上下文预算；null 或非正数 = 回到自动推导。 */
    fun withContextBudgetOverride(state: KeyringState, tokens: Int?): KeyringState =
        state.copy(contextBudgetOverride = tokens?.takeIf { it > 0 })

    /**
     * 模型上下文的缓存键。**必须带 provider**：同名模型在不同端点的规格可能不同
     * （同一个 `gpt-4o` 走官方与走第三方中转，上下文未必一致）。
     */
    fun contextKey(accountId: String, modelId: String): String = "$accountId/$modelId"

    fun activeAccount(state: KeyringState): ProviderAccount? =
        state.accounts.firstOrNull { it.id == state.activeId }

    /** 当前选择 → 可用的 [ModelConfig]；未选中/无模型返回 null（运行时不可用）。 */
    fun toModelConfig(state: KeyringState): ModelConfig? {
        val account = activeAccount(state) ?: return null
        if (state.activeModel.isBlank()) return null
        return ModelConfig(
            baseUrl = account.baseUrl,
            apiKey = account.apiKey,
            defaultModel = state.activeModel,
            timeoutMs = state.timeoutMs
        )
    }

    const val CUSTOM_LEGACY_ID = "custom-legacy"
    const val CUSTOM_ID_PREFIX = "custom-"
}

/**
 * [KeyringState] 的 SharedPreferences 持久化壳。首读时执行 legacy [KeyringLogic.migrateLegacy]。
 */
class ProviderKeyring(private val prefs: SharedPreferences) {

    // 先于 [state] 初始化：read() 在属性初始化期就会用到 json（初始化顺序陷阱）。
    private val json = Json { ignoreUnknownKeys = true }

    var state: KeyringState = read()
        private set

    /** 应用一个纯函数变换结果并落盘。 */
    fun update(transform: (KeyringState) -> KeyringState) {
        state = transform(state)
        prefs.edit { putString(KEY_KEYRING, encode(state)) }
    }

    private fun read(): KeyringState {
        val raw = prefs.getString(KEY_KEYRING, null)
        if (raw != null) {
            return runCatching { json.decodeFromString(KeyringState.serializer(), raw) }
                .getOrElse { KeyringLogic.migrateLegacy(null) }
        }
        // 首次：尝试迁移旧的单配置存储。
        val migrated = KeyringLogic.migrateLegacy(prefs.getString(KEY_LEGACY_MODEL_CONFIG, null))
        prefs.edit { putString(KEY_KEYRING, encode(migrated)) }
        return migrated
    }

    private fun encode(state: KeyringState): String =
        json.encodeToString(KeyringState.serializer(), state)

    companion object {
        const val KEY_KEYRING = "provider_keyring"
        const val KEY_LEGACY_MODEL_CONFIG = "model_config"
    }
}
