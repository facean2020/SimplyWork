package com.yangz3720.simplywork.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.core.agent.PromptDocCompactor
import com.yangz3720.simplywork.core.session.Project
import com.yangz3720.simplywork.core.workspace.WorkspaceAgentMd
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import kotlin.math.roundToInt

/** Paging inside settings: the first screen is the index, and each concrete setting goes one level deeper. */
private enum class SettingsPage { Index, Providers, Context, Souls }

/**
 * Settings (v1.1).
 *
 * The first screen became an **entry index**: before, opening settings landed straight on a provider API-key form, turning "look at settings" into "fill in a Key",
 * and every other setting had nowhere to live. Now the index only answers "what can be set here", and the concrete actions go to second-level pages.
 *
 * Levels of the back key: a second-level page returns to the index first, and the index exits settings -- exiting straight from a second-level page would make "back" skip a level,
 * and the user would think the settings page had gone missing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accounts: List<ProviderAccount>,
    autoApproveNonDestructive: Boolean,
    contextBudget: ContextBudgetPlan?,
    contextPromptTokens: Int,
    projects: List<Project>,
    currentProjectId: String,
    projectSouls: Map<String, String>,
    onBack: () -> Unit,
    onSaveKey: (accountId: String, apiKey: String) -> Unit,
    onAddCustom: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onRemoveCustom: (accountId: String) -> Unit,
    onToggleAutoApprove: (Boolean) -> Unit,
    onSetContextBudget: (Int?) -> Unit,
    onSaveProjectSoul: (projectId: String, content: String) -> Unit
) {
    var page by remember { mutableStateOf(SettingsPage.Index) }
    BackHandler { if (page == SettingsPage.Index) onBack() else page = SettingsPage.Index }

    when (page) {
        SettingsPage.Index -> SettingsIndex(
            accounts = accounts,
            autoApproveNonDestructive = autoApproveNonDestructive,
            contextBudget = contextBudget,
            contextPromptTokens = contextPromptTokens,
            onOpenProviders = { page = SettingsPage.Providers },
            onOpenContext = { page = SettingsPage.Context },
            onOpenSouls = { page = SettingsPage.Souls },
            onToggleAutoApprove = onToggleAutoApprove,
            onBack = onBack
        )

        SettingsPage.Providers -> ProviderSettings(
            accounts = accounts,
            onBack = { page = SettingsPage.Index },
            onSaveKey = onSaveKey,
            onAddCustom = onAddCustom,
            onRemoveCustom = onRemoveCustom
        )

        SettingsPage.Context -> ContextSettings(
            budget = contextBudget,
            promptTokens = contextPromptTokens,
            onBack = { page = SettingsPage.Index },
            onSetBudget = onSetContextBudget
        )

        SettingsPage.Souls -> SoulSettings(
            projects = projects,
            currentProjectId = currentProjectId,
            souls = projectSouls,
            onBack = { page = SettingsPage.Index },
            onSave = onSaveProjectSoul
        )
    }
}

/** First-screen index: lists only "what can be set"; the value summary goes in the subtitle. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsIndex(
    accounts: List<ProviderAccount>,
    autoApproveNonDestructive: Boolean,
    contextBudget: ContextBudgetPlan?,
    contextPromptTokens: Int,
    onOpenProviders: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenSouls: () -> Unit,
    onToggleAutoApprove: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SettingsScaffold(title = "设置", onBack = onBack) {
        val configured = accounts.count { it.apiKey.isNotBlank() }
        SettingsEntry(
            title = "服务商与密钥",
            subtitle = if (configured == 0) "还没有填写密钥" else "$configured 个已填写密钥",
            onClick = onOpenProviders
        )
        SettingsEntry(
            title = "上下文窗口",
            subtitle = contextBudget?.let { budget ->
                "预算 ${formatTokens(budget.tokens)} · 本会话已用 ${formatTokens(contextPromptTokens)}" +
                    usedPercent(contextPromptTokens, budget.tokens).orEmpty()
            } ?: "未选择模型",
            onClick = onOpenContext
        )
        SettingsEntry(
            title = "项目 Soul",
            subtitle = "为每个项目编辑 Markdown Soul",
            onClick = onOpenSouls
        )
        // Auto-approval is a two-way choice, so an inline switch is faster than another level -- and the index page shows the current state at a glance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动批准", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (autoApproveNonDestructive) "只有删除操作需要你确认" else "每次操作都需要你确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoApproveNonDestructive, onCheckedChange = onToggleAutoApprove)
        }
    }
}

/** Providers and API keys (the old first screen of settings). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettings(
    accounts: List<ProviderAccount>,
    onBack: () -> Unit,
    onSaveKey: (accountId: String, apiKey: String) -> Unit,
    onAddCustom: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onRemoveCustom: (accountId: String) -> Unit
) {
    var showAddCustom by remember { mutableStateOf(false) }

    var showSaveConfirmDialog by remember { mutableStateOf(false) }
    var pendingSaveData by remember { mutableStateOf<Pair<String, String>?>(null) }

    SettingsScaffold(title = "服务商与密钥", onBack = onBack) {
        Text(
            text = "各服务商只需保存 API Key；模型在对话界面顶栏随时切换。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(10.dp))

        accounts.forEach { account ->
            ProviderKeyCard(
                account = account,
                onSave = { key ->
                    pendingSaveData = account.id to key
                    showSaveConfirmDialog = true
                },
                onRemove = if (account.id.startsWith("custom")) {
                    { onRemoveCustom(account.id) }
                } else null
            )
            Spacer(Modifier.size(10.dp))
        }

        if (showAddCustom) {
            CustomAccountForm(
                onAdd = { name, url, key ->
                    onAddCustom(name, url, key)
                    showAddCustom = false
                },
                onCancel = { showAddCustom = false }
            )
        } else {
            TextButton(onClick = { showAddCustom = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("添加自定义端点", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (showSaveConfirmDialog && pendingSaveData != null) {
            AlertDialog(
                onDismissRequest = { showSaveConfirmDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveKey(pendingSaveData!!.first, pendingSaveData!!.second)
                        showSaveConfirmDialog = false
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveConfirmDialog = false }) {
                        Text("取消")
                    }
                },title = {
                    Text(text = "确认保存")
                },text = {
                    Text(text = "确定要保存并更新该服务商的 API Key 吗？")
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

/**
 * Project Soul editor.
 *
 * Each project owns one Markdown file. Keeping project selection and editing on this second-level
 * page makes the setting discoverable without introducing a third navigation level.
 */
@Composable
private fun SoulSettings(
    projects: List<Project>,
    currentProjectId: String,
    souls: Map<String, String>,
    onBack: () -> Unit,
    onSave: (projectId: String, content: String) -> Unit
) {
    // Keyed by the id list, not by the list instance: every refresh rebuilds `projects`, and keying on the
    // instance would silently jump the selection back to the current project while the user is editing.
    var selectedProjectId by remember(currentProjectId, projects.map { it.id }) {
        mutableStateOf(projects.firstOrNull { it.id == currentProjectId }?.id ?: projects.firstOrNull()?.id)
    }
    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    val savedSoul = selectedProjectId?.let(souls::get).orEmpty()
    var draft by remember(selectedProjectId, savedSoul) { mutableStateOf(savedSoul) }
    var preview by remember(selectedProjectId) { mutableStateOf(false) }

    SettingsScaffold(title = "项目 Soul", onBack = onBack) {
        Text(
            text = "Soul 会作为该项目每次对话的系统上下文。使用 Markdown 记录角色、偏好和长期规则。" +
                "过长时会自动压缩，只注入前面的部分（约 ${PromptDocCompactor.DEFAULT_MAX_TOKENS} tokens 上限）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(14.dp))

        Text("选择项目", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            projects.forEach { project ->
                FilterChip(
                    selected = project.id == selectedProjectId,
                    onClick = { selectedProjectId = project.id },
                    label = { Text(project.name) }
                )
            }
        }
        Spacer(Modifier.size(16.dp))

        if (selectedProject == null) {
            Text(
                text = "还没有可配置的项目。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SettingsScaffold
        }

        Text("${selectedProject.name} 的 soul.md", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { preview = false }) { Text("编辑") }
            TextButton(onClick = { preview = true }) { Text("预览") }
        }

        if (preview) {
            if (draft.isBlank()) {
                Text(
                    text = "这个项目还没有 Soul。切到“编辑”开始编写。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Markdown(
                    content = draft,
                    colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
                    typography = markdownTypography(text = MaterialTheme.typography.bodyMedium),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                label = { Text("Markdown") },
                minLines = 12,
                maxLines = 24
            )
        }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onSave(selectedProject.id, draft) }, enabled = draft != savedSoul) {
                Text("保存 Soul")
            }
            if (draft != savedSoul) {
                Text(
                    text = "未保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Text(
            text = "项目目录里的 AGENTS.md 也会自动作为上下文：首次加载时压缩并迁移为 " +
                "${WorkspaceAgentMd.SNAPSHOT_NAME}，之后只读这份快照。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/**
 * Context window.
 *
 * The user does not need to know the compaction algorithm, only two things: **how big this budget is** and **when it kicks in**.
 * So the page is ordered "budget -> source -> usage -> manual override", and one sentence at the end spells out the compaction timing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ContextSettings(
    budget: ContextBudgetPlan?,
    promptTokens: Int,
    onBack: () -> Unit,
    onSetBudget: (Int?) -> Unit
) {
    SettingsScaffold(title = "上下文窗口", onBack = onBack) {
        if (budget == null) {
            Text(
                text = "还没有选择模型。先在对话界面顶栏选一个模型，这里才有可算的预算。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SettingsScaffold
        }

        Text(
            text = formatTokens(budget.tokens),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "当前预算，${budget.source.explain()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(14.dp))

        InfoLine(
            label = "模型本身",
            value = budget.modelContextTokens?.let { formatTokens(it) }
                ?: "未知（服务商没报，内置对照表里也没有）"
        )
        InfoLine(
            label = "本会话已用",
            value = if (promptTokens > 0) {
                "${formatTokens(promptTokens)} / ${formatTokens(budget.tokens)}" +
                    usedPercent(promptTokens, budget.tokens).orEmpty()
            } else {
                "还没有发送过消息"
            }
        )
        InfoLine(label = "保留不压缩", value = formatTokens(budget.workingTokenBudget))

        Spacer(Modifier.size(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.size(14.dp))

        Text("手动指定", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "留空即自动：按模型上下文的一半来定，模型不足 20 万则按模型本身，超过 100 万只取 20 万。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        // "Auto" sits on the same row as the concrete tiers: a manual value can always be reverted to auto with one tap, with no hunting elsewhere.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BudgetChoiceRow(
                options = listOf(null, 24_000, 64_000, 128_000),
                selected = if (budget.source == ContextBudgetPlan.Source.Manual) budget.tokens else null,
                onSelect = onSetBudget
            )
            BudgetChoiceRow(
                options = listOf(200_000, 500_000, 1_000_000),
                selected = if (budget.source == ContextBudgetPlan.Source.Manual) budget.tokens else null,
                onSelect = onSetBudget
            )
        }

        Spacer(Modifier.size(16.dp))
        Text(
            text = "会话增长到超过预算时，较早的历史会被自动压缩成摘要，最近的部分保持原样。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One row of budget tiers; `null` = auto. */
@Composable
private fun BudgetChoiceRow(
    options: List<Int?>,
    selected: Int?,
    onSelect: (Int?) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val active = option == selected
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable { onSelect(option) }
            ) {
                Text(
                    text = option?.let { formatTokens(it) } ?: "自动",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shell shared by the second-level pages: title + back + consistent padding. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            content()
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** One row in the index: title + value summary + right chevron. */
@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Explanatory copy for the budget source (the user cannot read enum names like `Provider`). */
private fun ContextBudgetPlan.Source.explain(): String = when (this) {
    ContextBudgetPlan.Source.Manual -> "由你手动指定"
    ContextBudgetPlan.Source.Provider -> "由服务商报告的模型上下文算出"
    ContextBudgetPlan.Source.ModelName -> "由模型名里的规格标记算出"
    ContextBudgetPlan.Source.BuiltIn -> "由内置对照表算出（模型目录更新很快，表可能滞后；可用下方手动指定覆盖）"
    ContextBudgetPlan.Source.Default -> "模型上下文未知，按默认值"
}

/**
 * The used amount as a share of the budget, shaped like `(6%)`; returns the empty string when no ratio can be computed.
 *
 * Like [formatTokens] it is an `internal` pure function -- it is used only in copy such as "used X / budget Y",
 * and unit tests hit the copy directly, without starting Compose.
 */
internal fun usedPercent(promptTokens: Int, budgetTokens: Int): String {
    if (promptTokens <= 0 || budgetTokens <= 0) return ""
    val percent = (promptTokens * 100.0 / budgetTokens).roundToInt()
    return "（$percent%）"
}

/** `200000` → `200k`，`499500` → `499.5k`，`2000000` → `2M`。 */
internal fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> trimNumber(tokens / 1_000_000.0) + "M"
    tokens >= 1_000 -> trimNumber(tokens / 1_000.0) + "k"
    else -> tokens.toString()
}

private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

/**
 * One provider's key card: name + endpoint + Key input and save.
 *
 * v1.1: the Key is **masked** by default. It used to be laid out in plaintext on the first settings screen -- screenshots, a bystander's glance, or screen sharing would all leak it.
 * When you need to verify it, tap "Show" to reveal it temporarily, instead of leaving it on display by default.
 */
@Composable
private fun ProviderKeyCard(
    account: ProviderAccount,
    onSave: (String) -> Unit,
    onRemove: (() -> Unit)?
) {
    var key by remember(account.id, account.apiKey) { mutableStateOf(account.apiKey) }
    var revealed by remember(account.id) { mutableStateOf(false) }
    val changed = key.trim() != account.apiKey

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = account.baseUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    visualTransformation = if (revealed) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    placeholder = { Text("API Key（sk-…）", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f)
                )
                // Text instead of an eye icon: the core icon set has no visibility glyph, and pulling in another
                // icon dependency just for it is not worth it (material-icons-extended would visibly grow the APK).
                TextButton(onClick = { revealed = !revealed }) {
                    Text(
                        text = if (revealed) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onSave(key.trim()) }, enabled = changed) {
                    Text(
                        text = "保存",
                        color = if (changed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Custom endpoint form: name + Base URL + Key. */
@Composable
private fun CustomAccountForm(
    onAdd: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val valid = name.trim().isNotBlank() && baseUrl.trim().startsWith("http")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "自定义端点（OpenAI 兼容）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("名称", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                label = { Text("Base URL", style = MaterialTheme.typography.labelMedium) },
                placeholder = { Text("https://api.example.com/v1", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("API Key（可选）", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onCancel) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onAdd(name, baseUrl, key) }, enabled = valid) {
                    Text(
                        text = "添加",
                        color = if (valid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
