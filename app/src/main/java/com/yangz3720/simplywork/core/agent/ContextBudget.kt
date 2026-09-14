package com.yangz3720.simplywork.core.agent

/**
 * 上下文预算的推导（v1.1，用户定调）——**纯函数**，JVM 可测，不碰网络也不碰存储。
 *
 * 背景：压缩阈值与工作窗口原先写死在 [AgentConfig]（24k / 12k），对现代长上下文模型
 * 来说太小 —— 模型明明能装 200k，却在 24k 就开始压缩，白白丢掉还能留着的历史。
 * 所以预算改为按模型**自身**的上下文长度推出来。
 *
 * 推导规则（用户原话："1M及以上就砍到200K，不到1M的砍一半，但是砍的下限是200K，
 * 但是若模型上下文不足200K则按照模型本身的上下文来进行"）：
 *
 * | 模型上下文 L | 预算 |
 * |---|---|
 * | L < 200K | L（模型本身不够，就按它本身的来） |
 * | 200K ≤ L < 1M | max(L/2, 200K) |
 * | L ≥ 1M | 200K |
 *
 * 注意这条规则在 L=1M 处**不连续**：L=999K → 499.5K，L=1M → 200K。这是规则的字面
 * 结果（1M 以上的模型按"够用就好"处理），实现照办，没有自作主张地把它改成单调函数。
 */
object ContextBudget {

    /** 预算下限：低于这个值就不再"对半砍"。 */
    const val FLOOR_TOKENS = 200_000

    /** 划入"超大上下文"的门槛，达到即只给 [FLOOR_TOKENS]。 */
    const val HUGE_CONTEXT_TOKENS = 1_000_000

    /**
     * 模型上下文未知时的预算。等于 [AgentConfig] 的历史默认值：拿不到信息时行为与
     * 旧版本完全一致，不会因为"改了个设置页"就悄悄改变压缩时机。
     */
    const val UNKNOWN_MODEL_TOKENS = 24_000

    /**
     * 工作窗口占预算的比例。沿用旧默认值 12k/24k = 1/2：预算变大时"保留多少不压缩的
     * 近期历史"跟着同比放大，而不是留一个 12k 的绝对值把大预算浪费掉。
     */
    const val WORKING_WINDOW_RATIO = 2

    /** 预算 → 压缩阈值（等于预算本身：装不下就压）。 */
    fun compactionTriggerTokens(limit: Int?): Int = derive(limit)

    /** 预算 → 工作窗口 token 上限。 */
    fun workingTokenBudget(budgetTokens: Int): Int = (budgetTokens / WORKING_WINDOW_RATIO).coerceAtLeast(1)

    /** 见类注释的表格。`null` / 非正数 = 未知 → [UNKNOWN_MODEL_TOKENS]。 */
    fun derive(modelContextTokens: Int?): Int {
        val limit = modelContextTokens ?: return UNKNOWN_MODEL_TOKENS
        if (limit <= 0) return UNKNOWN_MODEL_TOKENS
        return when {
            limit >= HUGE_CONTEXT_TOKENS -> FLOOR_TOKENS
            limit < FLOOR_TOKENS -> limit
            else -> maxOf(limit / 2, FLOOR_TOKENS)
        }
    }

    /**
     * 从模型名里读上下文长度 —— 有些 OpenAI 兼容端点（自建/本地部署）不在 /models 里
     * 返回上下文，但会把规格写进模型名，如 `qwen2.5-32k`、`llama-3.1-128k`、`xxx-1m`。
     *
     * 刻意**只认名字里显式写出的规格**，不维护"模型名 → 上下文"的对照表：那种表发布
     * 新模型就会过期，而错误地高估模型上下文，代价是请求被服务端直接拒掉。
     */
    fun fromModelName(modelId: String): Int? {
        val match = SIZE_MARKER.find(modelId) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "k" -> value * 1_000
            "m" -> value * 1_000_000
            else -> null
        }
    }

    /** `128k` / `1m` 这类规格标记；必须紧贴词边界，免得从别的数字里截出一段。 */
    private val SIZE_MARKER = Regex("""(?:^|[-_ ])(\d{1,4})([km])(?:$|[-_ .])""", RegexOption.IGNORE_CASE)

    /**
     * 最终预算的裁决点。顺序即可信度：手动 > 服务商报的 > 模型名规格 > 默认。
     *
     * 手动设置时仍把"模型本身多大"一并带出来（[ContextBudgetPlan.modelContextTokens]）：
     * 设置页要能显示"你定的是 64k，而这模型本身有 128k"，否则用户没法判断自己设得多偏。
     */
    fun resolve(overrideTokens: Int?, providerTokens: Int?, modelId: String): ContextBudgetPlan {
        val knownModelTokens = providerTokens?.takeIf { it > 0 } ?: fromModelName(modelId)
        overrideTokens?.takeIf { it > 0 }?.let { manual ->
            return ContextBudgetPlan(manual, knownModelTokens, ContextBudgetPlan.Source.Manual)
        }
        providerTokens?.takeIf { it > 0 }?.let { reported ->
            return ContextBudgetPlan(derive(reported), reported, ContextBudgetPlan.Source.Provider)
        }
        fromModelName(modelId)?.let { fromName ->
            return ContextBudgetPlan(derive(fromName), fromName, ContextBudgetPlan.Source.ModelName)
        }
        return ContextBudgetPlan(derive(null), null, ContextBudgetPlan.Source.Default)
    }
}

/**
 * 预算的最终取值 + **它是怎么来的**。设置页要如实告诉用户"这个数是你自己定的 / 服务商
 * 报的 / 从模型名读的 / 未知所以退回默认"，否则一个自动推导的数字看起来像随机数。
 */
data class ContextBudgetPlan(
    val tokens: Int,
    val modelContextTokens: Int?,
    val source: Source
) {
    enum class Source {
        /** 用户手动指定。 */
        Manual,

        /** 服务商在 /models 里报的上下文长度。 */
        Provider,

        /** 从模型名里的规格标记读出来的。 */
        ModelName,

        /** 模型上下文未知 → 退回历史默认值。 */
        Default
    }

    /** 压缩阈值：装不下就压。 */
    val compactionTriggerTokens: Int get() = tokens

    /** 工作窗口上限：预算的一半左右留给不压缩的近况。 */
    val workingTokenBudget: Int get() = ContextBudget.workingTokenBudget(tokens)
}
