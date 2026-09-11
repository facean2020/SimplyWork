package com.yangz3720.simplywork.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount

/**
 * 服务商设置：多服务商并存，各只保存 API Key（预设端点内置，可添加自定义端点）。
 * 模型不在此处选择——对话界面顶栏随时动态切换（Gemini/ChatGPT 式）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    accounts: List<ProviderAccount>,
    timeoutMs: Long,
    onBack: () -> Unit,
    onSaveKey: (accountId: String, apiKey: String) -> Unit,
    onAddCustom: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onRemoveCustom: (accountId: String) -> Unit,
    onSaveTimeout: (ms: Long) -> Unit
) {
    BackHandler { onBack() }

    var showAddCustom by remember { mutableStateOf(false) }
    var timeoutText by remember(timeoutMs) { mutableStateOf(timeoutMs.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
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
            Text(
                text = "服务商",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.size(4.dp))
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

            Spacer(Modifier.size(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.size(14.dp))
            Text(
                text = "高级",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it },
                label = { Text("请求超时（毫秒）", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = { timeoutText.toLongOrNull()?.let(onSaveTimeout) },
                enabled = (timeoutText.toLongOrNull() ?: 0L) >= 1_000L
            ) {
                Text("保存超时设置")
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** 单个服务商的密钥卡片：名称 + 端点 + Key 输入与保存。 */
@Composable
private fun ProviderKeyCard(
    account: ProviderAccount,
    onSave: (String) -> Unit,
    onRemove: (() -> Unit)?
) {
    var key by remember(account.id, account.apiKey) { mutableStateOf(account.apiKey) }
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
                    placeholder = { Text("API Key（sk-…）", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onSave(key.trim()) }, enabled = changed) {
                    Text("保存", color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                label = { Text("API Key（可选）", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onCancel) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onAdd(name, baseUrl, key) }, enabled = valid) {
                    Text("添加", color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
