package com.yangz3720.simplywork.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 *
 * 运行时权限申请（v1.2 / P4）挂在根层而不是 [AgentScreen]：`when(screen)` 整体替换
 * 两个页面，挂载在页面内的 launcher 会在切到设置页时被销毁，挂起中的授权请求就
 * 只能等到执行器超时。挂在这里，pending 弹窗在整个 App 生命周期内稳定存活。
 */
@Composable
fun AgentApp(viewModel: AgentViewModel) {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Agent) }

    // 基础设施只报告缺哪些权限；弹窗时机与形态归 UI（P4）。结果回灌后执行器会
    // 复查权限再决定是否放行，所以"用户点了允许"不等于授权成功。
    val permission by viewModel.permission.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.respondPermission(results.isNotEmpty() && results.values.all { it })
    }
    LaunchedEffect(permission) {
        permission?.let { request ->
            permissionLauncher.launch(request.permissions.toTypedArray())
        }
    }

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