package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.infrastructure.model.config.ProviderAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Top-bar model picker sheet (VSCode Copilot style, focused): a single search field (no placeholder text) + one vertical list
 * that aggregates the models of every provider with a "configured API key" (fetched concurrently via GET /models and merged), with the provider name annotated on the right of each row
 * and the selected entry ticked. When the search term matches no model at all, "type the model name manually" is merged in as a fallback row. No more provider chips /
 * API key management entry points -- the provider is chosen along with the model here, and API key management goes back to the settings page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    accounts: List<ProviderAccount>,
    activeProviderId: String?,
    activeModel: String,
    fetchModels: suspend (ProviderAccount) -> Result<List<String>>,
    onSelect: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit
) {
    val configured = remember(accounts) { accounts.filter { it.apiKey.isNotBlank() } }
    var entries by remember { mutableStateOf<List<Pair<ProviderAccount, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }

    // Fetch every provider's model list concurrently and merge them; a single failure is silently ignored (stay focused), and only "everything empty" raises an explicit error.
    LaunchedEffect(reloadKey, configured.size) {
        if (configured.isEmpty()) {
            loading = false
            error = null
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        val merged = coroutineScope {
            configured.map { account ->
                async { fetchModels(account).getOrNull().orEmpty().map { account to it } }
            }.awaitAll().flatten()
        }
        entries = merged.sortedWith(
            compareBy({ (account, _) -> account.id != activeProviderId }, { (account, _) -> account.name }, { (_, model) -> model })
        )
        loading = false
        if (merged.isEmpty()) error = "未能获取模型列表，可下拉重试或手动输入模型名"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .heightIn(max = 560.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(6.dp))

            val query = search.trim()
            val filtered = entries.filter {
                query.isEmpty() ||
                    it.second.contains(query, ignoreCase = true) ||
                    it.first.name.contains(query, ignoreCase = true)
            }
            val canManual = query.isNotBlank() && entries.none { it.second.equals(query, ignoreCase = true) }

            when {
                loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                configured.isEmpty() -> Text(
                    text = "尚未配置任何服务商密钥，请在设置中添加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(filtered, key = { "${it.first.id}::${it.second}" }) { (account, model) ->
                        val selected = account.id == activeProviderId && model == activeModel
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(account.id, model)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selected) {
                                Spacer(Modifier.size(8.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Manual-entry fallback (absorbing what used to be a separate input box): when the search term matches nothing, use it as a custom model name.
                    if (canManual) {
                        val target = activeProviderId ?: configured.firstOrNull()?.id
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        target?.let { onSelect(it, query) }
                                        onDismiss()
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = "使用「$query」",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "手动输入",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (error != null) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    text = error.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = { reloadKey++ }) {
                                    Text("重试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty() && error == null && !canManual) {
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
