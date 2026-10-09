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
import androidx.compose.ui.tooling.preview.Preview
import com.yangz3720.simplywork.core.session.ProjectLogic
import com.yangz3720.simplywork.ui.agent.AgentScreen
import com.yangz3720.simplywork.ui.agent.AgentViewModel
import com.yangz3720.simplywork.ui.agent.FilePreviewSheet
import com.yangz3720.simplywork.ui.agent.ProjectFilesScreen
import com.yangz3720.simplywork.ui.settings.SettingsScreen

/**
 * App composition root: switches between the conversation main screen, the project files screen (v1.5) and the settings screen (three screens are enough, no navigation library needed -- zero new dependencies).
 *
 * The runtime permission request (v1.2 / P4) is hosted at the root layer rather than in [AgentScreen]: `when(screen)` replaces
 * both pages wholesale, so a launcher attached inside a page is destroyed when we switch to the settings page, and a suspended permission request can then
 * only wait for the executor to time out. Hosted here, the pending dialog stays alive for the whole App lifecycle.
 *
 * File preview (v1.5) is likewise hosted at the root layer: it has two entry points (the new-files card at the bottom of the conversation, the file manager),
 * and only at the root layer does "which way you came in" leave the page itself unaffected.
 */
@Composable
fun AgentApp(viewModel: AgentViewModel) {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Agent) }
    val state by viewModel.uiState.collectAsState()

    // Infrastructure only reports which permissions are missing; when and how the dialog appears belongs to the UI (P4). After the result is fed back the executor
    // re-checks the permission before deciding whether to let it through, so "the user tapped allow" does not mean the grant succeeded.
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

    // v1.6: the foreground service only shows its persistent notification once POST_NOTIFICATIONS is
    // granted, and nothing else in the app ever asks for it -- without it a run keeps going in the
    // background but the notification drawer stays empty. Asked once per process (see the VM).
    LaunchedEffect(Unit) {
        viewModel.ensureNotificationPermission()
    }

    when (screen) {
        AppScreen.Agent ->
            AgentScreen(
                viewModel = viewModel,
                onOpenSettings = { screen = AppScreen.Settings },
                onOpenFiles = {
                    viewModel.openProjectFiles()
                    screen = AppScreen.Files
                }
            )

        AppScreen.Files ->
            ProjectFilesScreen(
                projectName = state.projects.firstOrNull { it.id == state.currentProjectId }?.name
                    ?: ProjectLogic.DEFAULT_PROJECT_NAME,
                path = state.filePath,
                entries = state.fileEntries,
                busy = state.filesBusy,
                error = state.filesError,
                onOpenDir = { viewModel.openFileDir(it) },
                onUp = { viewModel.fileDirUp() },
                onOpenFile = { viewModel.openFilePreview(it) },
                onDismissError = { viewModel.dismissFilesError() },
                onBack = { screen = AppScreen.Agent }
            )

        AppScreen.Settings ->
            SettingsScreenRoute(
                viewModel = viewModel,
                onBack = { screen = AppScreen.Agent }
            )
    }

    state.filePreview?.let { preview ->
        FilePreviewSheet(
            preview = preview,
            onDismiss = { viewModel.closeFilePreview() },
            onOpenExternally = { viewModel.openFileExternally() }
        )
    }
}

@Composable
private fun SettingsScreenRoute(viewModel: AgentViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsScreen(
        accounts = state.providerAccounts,
        autoApproveNonDestructive = state.autoApproveNonDestructive,
        contextBudget = state.contextBudget,
        contextPromptTokens = state.contextPromptTokens,
        onBack = onBack,
        onSaveKey = { id, key -> viewModel.saveAccountKey(id, key) },
        onAddCustom = { name, url, key -> viewModel.addCustomAccount(name, url, key) },
        onRemoveCustom = { id -> viewModel.removeCustomAccount(id) },
        onToggleAutoApprove = { viewModel.setAutoApproveNonDestructive(it) },
        onSetContextBudget = { viewModel.setContextBudgetOverride(it) }
    )
}

sealed interface AppScreen {
    data object Agent : AppScreen

    /** Project file manager (v1.5): read-only browsing of the current project's sandbox directory. */
    data object Files : AppScreen
    data object Settings : AppScreen
}