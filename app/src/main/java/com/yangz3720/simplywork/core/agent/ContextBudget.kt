package com.yangz3720.simplywork.core.agent

/**
 * Deriving the context budget (v1.1, the user set the tone) -- a **pure function**, testable on the
 * JVM, touching neither network nor storage.
 *
 * Background: the compaction trigger and the working window used to be hard-coded in [AgentConfig]
 * (24k / 12k), which is too small for modern long-context models -- a model can plainly hold 200k,
 * yet compaction kicked in at 24k and threw away history it could have kept. So the budget is now
 * derived from the model's **own** context length.
 *
 * Derivation rule (the user's own words, faithfully translated: "1M and above gets cut to 200K,
 * anything under 1M gets halved, but the lower bound of the cut is 200K, and if the model's own
 * context is under 200K then go by the model's own context"):
 *
 * | Model context L | Budget |
 * |---|---|
 * | L < 200K | L (the model itself is smaller, so go by its own) |
 * | 200K <= L < 1M | max(L/2, 200K) |
 * | L >= 1M | 200K |
 *
 * Note this rule is **discontinuous** at L=1M: L=999K -> 499.5K, L=1M -> 200K. That is the literal
 * result of the rule (models of 1M and above are treated as "enough is enough"), and the
 * implementation follows it rather than taking it upon itself to make it monotonic.
 */
object ContextBudget {

    /** Budget floor: below this value we stop "halving". */
    const val FLOOR_TOKENS = 200_000

    /** Threshold for counting as "huge context"; at or above it you only get [FLOOR_TOKENS]. */
    const val HUGE_CONTEXT_TOKENS = 1_000_000

    /**
     * Budget when the model context is unknown. Equals [AgentConfig]'s historical default: with no
     * information available the behaviour is exactly that of the old version, so "we changed a
     * settings page" never silently moves when compaction happens.
     */
    const val UNKNOWN_MODEL_TOKENS = 24_000

    /**
     * The working window's share of the budget. Keeps the old default 12k/24k = 1/2: as the budget
     * grows, "how much uncompacted recent history is kept" grows in step, instead of leaving a flat
     * 12k that wastes a large budget.
     */
    const val WORKING_WINDOW_RATIO = 2

    /** Budget -> compaction trigger (equal to the budget itself: compact once it no longer fits). */
    fun compactionTriggerTokens(limit: Int?): Int = derive(limit)

    /** Budget -> working window token cap. */
    fun workingTokenBudget(budgetTokens: Int): Int = (budgetTokens / WORKING_WINDOW_RATIO).coerceAtLeast(1)

    /** See the class-comment table. `null` / non-positive = unknown -> [UNKNOWN_MODEL_TOKENS]. */
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
     * Read the context length out of the model name -- some OpenAI-compatible endpoints
     * (self-hosted / local deployments) do not return a context in /models, but do write the spec
     * into the model name, e.g. `qwen2.5-32k`, `llama-3.1-128k`, `xxx-1m`.
     *
     * Deliberately honours **only a spec written explicitly in the name**; we do not maintain a
     * "model name -> context" table here: such a table goes stale as soon as a new model ships, and
     * wrongly overestimating a model's context costs a request the server rejects outright. Models
     * whose factory spec is known and whose name says nothing go through [ModelContextTable]
     * (v1.6) -- it ranks **after** this function in the arbitration order, because a marker in the
     * name means "this endpoint is declaring its own window", which is more specific.
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

    /** Spec markers like `128k` / `1m`; must sit on a word boundary so we never carve one out of another digit run. */
    private val SIZE_MARKER = Regex("""(?:^|[-_ ])(\d{1,4})([km])(?:$|[-_ .])""", RegexOption.IGNORE_CASE)

    /**
     * The arbitration point for the final budget. The order is the confidence order:
     * manual > what the provider reports > the spec in the model name > built-in table > default.
     *
     * When the value is manual we still carry "how big the model itself is" along
     * ([ContextBudgetPlan.modelContextTokens]): the settings page has to be able to show "you set
     * 64k, but this model itself has 128k", otherwise the user cannot tell how far off their own
     * setting is.
     */
    fun resolve(overrideTokens: Int?, providerTokens: Int?, modelId: String): ContextBudgetPlan {
        val reported = providerTokens?.takeIf { it > 0 }
        val fromName = fromModelName(modelId)
        val fromTable = ModelContextTable.lookup(modelId)
        val knownModelTokens = reported ?: fromName ?: fromTable
        overrideTokens?.takeIf { it > 0 }?.let { manual ->
            return ContextBudgetPlan(manual, knownModelTokens, ContextBudgetPlan.Source.Manual)
        }
        reported?.let { return ContextBudgetPlan(derive(it), it, ContextBudgetPlan.Source.Provider) }
        fromName?.let { return ContextBudgetPlan(derive(it), it, ContextBudgetPlan.Source.ModelName) }
        fromTable?.let { return ContextBudgetPlan(derive(it), it, ContextBudgetPlan.Source.BuiltIn) }
        return ContextBudgetPlan(derive(null), null, ContextBudgetPlan.Source.Default)
    }
}

/**
 * The budget's final value plus **where it came from**. The settings page has to tell the user
 * faithfully "this number is one you set / one the provider reported / one read from the model name
 * / unknown so we fell back to the default", otherwise an auto-derived number looks like a random
 * one.
 */
data class ContextBudgetPlan(
    val tokens: Int,
    val modelContextTokens: Int?,
    val source: Source
) {
    enum class Source {
        /** Set manually by the user. */
        Manual,

        /** Context length the provider reports in /models. */
        Provider,

        /** Read out of the spec marker in the model name. */
        ModelName,

        /**
         * Read out of [ModelContextTable]'s built-in table. Official endpoints generally do not
         * report a context, so this is the most common rung; the table lags behind the model
         * catalogue, so the settings page must say so faithfully and offer a manual override.
         */
        BuiltIn,

        /** Model context unknown -> fall back to the historical default. */
        Default
    }

    /** Compaction trigger: compact once it no longer fits. */
    val compactionTriggerTokens: Int get() = tokens

    /** Working window cap: roughly half the budget is left for uncompacted recent history. */
    val workingTokenBudget: Int get() = ContextBudget.workingTokenBudget(tokens)
}
