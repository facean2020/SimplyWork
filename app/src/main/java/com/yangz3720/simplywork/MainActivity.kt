package com.yangz3720.simplywork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yangz3720.simplywork.ui.AgentApp
import com.yangz3720.simplywork.ui.agent.AgentViewModel
import com.yangz3720.simplywork.ui.theme.SimplyWorkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SimplyWorkApp).container
        val viewModel: AgentViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AgentViewModel(
                        container,
                        container.runSupervisor,
                        container.approvalCoordinator,
                        container.permissionCoordinator,
                        container.projectStore,
                        container.oneDrive,
                        container.projectFiles
                    ) as T
            }
        )[AgentViewModel::class.java]

        setContent {
            SimplyWorkTheme {
                AgentApp(viewModel)
            }
        }
    }
}