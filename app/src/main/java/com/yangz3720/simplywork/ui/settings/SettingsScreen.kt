package com.yangz3720.simplywork.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.yangz3720.simplywork.core.agent.ContextBudgetPlan
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount

/** 设置页的分页：首屏是索引，具体配置各进一层。 */
private enum class SettingsPage { Index, Providers, Context }

/**
 * 设置（v1.1）。
 *
 * 首屏改成**条目索引**：以前一打开设置就是一张服务商密钥表单，把"看设置"变成"填 Key"，
 * 其余配置无处安放。现在索引只回答"这里能设什么"，具体动作进二级页。
 *
 * 返回键的层级：二级页先回索引，索引再退出设置 —— 直接从二级页退出会让"返回"跳一层，
 * 用户会以为设置页丢了。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accounts: List<ProviderAccount>,
    autoApproveNonDestructive: Boolean,
    contextBudget: ContextBudgetPlan?,
    contextPromptTokens: Int,
    onBack: () -> Unit,
    onSaveKey: (accountId: String, apiKey: String) -> Unit,
    onAddCustom: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onRemoveCustom: (accountId: String) -> Unit,
    onToggleAutoApprove: (Boolean) -> Unit,
    onSetContextBudget: (Int?) -> Unit
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
    }
}

/** 首屏索引：只列"能设什么"，数值摘要放在副标题上。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsIndex(
    accounts: List<ProviderAccount>,
    autoApproveNonDestructive: Boolean,
    contextBudget: ContextBudgetPlan?,
    contextPromptTokens: Int,
    onOpenProviders: () -> Unit,
    onOpenContext: () -> Unit,
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
            subtitle = contextBudget?.let { "预算 ${formatTokens(it.tokens)} · 本会话已用 ${formatTokens(contextPromptTokens)}" }
                ?: "未选择模型",
            onClick = onOpenContext
        )
        // 自动批准是二选一，就地开关比再进一层更快 —— 索引页也能一眼看到当前状态。
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

/** 服务商与密钥（原来的设置首屏）。 */
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
                onSave = { key -> onSaveKey(account.id, key) },
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
    }
}

/**
 * 上下文窗口。
 *
 * 用户不需要知道压缩算法，只需要知道两件事：**这个预算是多少**、**它在什么时候出手**。
 * 所以页面按"预算 → 来源 → 用量 → 手动指定"排，并在末尾用一句话说清压缩时机。
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
            value = budget.modelContextTokens?.let { formatTokens(it) } ?: "未知（该服务商没报）"
        )
        InfoLine(
            label = "本会话已用",
            value = if (promptTokens > 0) formatTokens(promptTokens) else "还没有发送过消息"
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
        // 「自动」与具体档位同排：手动值随时能一键退回自动，不用去别处找。
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

/** 一排预算档位；`null` = 自动。 */
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

/** 二级页共用的外壳：标题 + 返回 + 一致的留白。 */
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

/** 索引里的一行：标题 + 数值摘要 + 右箭头。 */
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

/** 预算来源的说明文案（用户看不懂 `Provider` 这种枚举名）。 */
private fun ContextBudgetPlan.Source.explain(): String = when (this) {
    ContextBudgetPlan.Source.Manual -> "由你手动指定"
    ContextBudgetPlan.Source.Provider -> "由服务商报告的模型上下文算出"
    ContextBudgetPlan.Source.ModelName -> "由模型名里的规格标记算出"
    ContextBudgetPlan.Source.Default -> "模型上下文未知，按默认值"
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
 * 单个服务商的密钥卡片：名称 + 端点 + Key 输入与保存。
 *
 * v1.1：Key 默认**打码**。它原先以明文铺在设置首屏上 —— 截图、旁人一瞥、投屏都会漏。
 * 需要核对时点「显示」临时露出，而不是默认就一直摆着。
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
                // 用文字而不是眼睛图标：核心图标集里没有 visibility 字形，为它多引一套
                // 图标依赖不值得（material-icons-extended 会让 APK 明显变大）。
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

/** 自定义端点表单：名称 + Base URL + Key。 */
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
