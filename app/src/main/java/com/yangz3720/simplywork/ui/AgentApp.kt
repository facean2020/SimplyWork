package com.yangz3720.simplywork.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yangz3720.simplywork.ui.agent.AgentScreen
import com.yangz3720.simplywork.ui.agent.AgentViewModel
import com.yangz3720.simplywork.ui.settings.SettingsScreen

/**
 * App 根装配：对话主屏与设置屏之间切换（两屏即可，无需导航库——零新增依赖）。
 */
@Composable
fun AgentApp(viewModel: AgentViewModel) {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Agent) }
    when (screen) {
        AppScreen.Agent ->
            AgentScreen(
                viewModel = viewModel,
                onOpenSettings = { screen = AppScreen.Settings }
            )

        AppScreen.Settings ->
            SettingsScreenRoute(
                viewModel = viewModel,
                onBack = { screen = AppScreen.Agent }
            )
    }
}

@Composable
private fun SettingsScreenRoute(viewModel: AgentViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsScreen(
        accounts = state.providerAccounts,
        timeoutMs = viewModel.timeoutMs(),
        onBack = onBack,
        onSaveKey = { id, key -> viewModel.saveAccountKey(id, key) },
        onAddCustom = { name, url, key -> viewModel.addCustomAccount(name, url, key) },
        onRemoveCustom = { id -> viewModel.removeCustomAccount(id) },
        onSaveTimeout = { ms -> viewModel.saveTimeoutMs(ms) }
    )
}

sealed interface AppScreen {
    data object Agent : AppScreen
    data object Settings : AppScreen
}