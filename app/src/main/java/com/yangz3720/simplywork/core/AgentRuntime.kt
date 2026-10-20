package com.yangz3720.simplywork.core

import android.content.Context
import com.yangz3720.simplywork.core.agent.AgentConfig
import com.yangz3720.simplywork.core.agent.AgentContextAssembler
import com.yangz3720.simplywork.core.agent.AgentLoopCoordinator
import com.yangz3720.simplywork.core.agent.AgentSoul
import com.yangz3720.simplywork.core.agent.AuditApprovalHandler
import com.yangz3720.simplywork.core.agent.AuditGatePipeline
import com.yangz3720.simplywork.core.agent.CompletionGuard
import com.yangz3720.simplywork.core.agent.ContextCompactor
import com.yangz3720.simplywork.core.agent.MessageQueueManager
import com.yangz3720.simplywork.core.agent.ModelSummarizer
import com.yangz3720.simplywork.core.agent.TokenEstimator
import com.yangz3720.simplywork.core.agent.HeuristicTokenEstimator
import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.core.agent.tool.SystemOpsToolAdapter
import com.yangz3720.simplywork.core.agent.tool.TodoTool
import com.yangz3720.simplywork.core.agent.tool.WaitTool
import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.store.JsonlPayloadStore
import com.yangz3720.simplywork.core.session.store.SessionStore
import com.yangz3720.simplywork.core.session.store.SqliteSessionStore
import com.yangz3720.simplywork.core.workspace.AgentWorkspace
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.systemops.SystemOperationRouter
import java.io.File

/**
 * Composition root for the Agent Core (agent-core-design.md §1): wires the dual-medium
 * session persistence, the registry-lite (sys_* tools + todo_write), the audit gates,
 * the compactor and the loop into one usable bundle. The UI layer's ViewModel consumes
 * [coordinator] (run flow) and [queue] (steering / follow-up prompts).
 */
class AgentRuntime private constructor(
    val repository: SessionRepository,
    val registry: AgentToolRegistry,
    val queue: MessageQueueManager,
    val coordinator: AgentLoopCoordinator
) {

    /** Convenience: generate a fresh session id (create it via [repository] before running). */
    fun newSessionId(): String = java.util.UUID.randomUUID().toString()

    companion object {

        /**
         * Production wiring: SQLite structure store + JSONL payload store under
         * `filesDir/agent/`. [provider] and [router] come from the infrastructure layer.
         */
        fun create(
            context: Context,
            provider: ModelProvider,
            router: SystemOperationRouter,
            config: AgentConfig = AgentConfig(),
            estimator: TokenEstimator = HeuristicTokenEstimator(),
            approvalHandler: AuditApprovalHandler = AuditApprovalHandler.AutoApprove,
            selfOps: ((repository: SessionRepository) -> List<RegisteredTool>)? = null,
            workspace: AgentWorkspace,
            soul: AgentSoul
        ): AgentRuntime {
            val baseDir = File(context.filesDir, "agent").apply { mkdirs() }
            val structureStore: SessionStore = SqliteSessionStore(context, "sessions.db")
            val payloadStore = JsonlPayloadStore(File(baseDir, "payloads"))
            return createWithStores(structureStore, payloadStore, provider, router, config, estimator, approvalHandler = approvalHandler, selfOps = selfOps, workspace = workspace, soul = soul)
        }

        /**
         * Wiring with injected stores (in-memory in unit tests, custom storage elsewhere).
         */
        fun createWithStores(
            structureStore: SessionStore,
            payloadStore: com.yangz3720.simplywork.core.session.store.PayloadStore,
            provider: ModelProvider,
            router: SystemOperationRouter,
            config: AgentConfig = AgentConfig(),
            estimator: TokenEstimator = HeuristicTokenEstimator(),
            summarizer: com.yangz3720.simplywork.core.agent.Summarizer = ModelSummarizer(provider),
            approvalHandler: AuditApprovalHandler = AuditApprovalHandler.AutoApprove,
            // Tools injected by the composition root (v1.3 self_* / fs_* / project; v1.6 adds the
            // js_* sandbox tools that need an Android context): not registered by default (zero
            // changes to unit tests).
            selfOps: ((repository: SessionRepository) -> List<RegisteredTool>)? = null,
            workspace: AgentWorkspace,
            soul: AgentSoul
        ): AgentRuntime {
            val repository = SessionRepositoryImpl(structureStore, payloadStore)
            val registry = AgentToolRegistry()
            SystemOpsToolAdapter.registerAll(registry, router)
            registry.register(TodoTool.registeredTool(repository))
            // v1.6: pure wait (it depends on nothing, so it is registered unconditionally; the
            // sandbox-style tools need an Android context and are injected by AppContainer through
            // the selfOps seam, see SelfOpsTools).
            registry.register(WaitTool.registeredTool())
            selfOps?.invoke(repository)?.forEach(registry::register)

            val queue = MessageQueueManager()
            val auditGate = AuditGatePipeline(
                registry,
                approvalHandler = approvalHandler,
                maxToolResultChars = config.maxToolResultChars,
                autoApproveNonDestructive = config.autoApproveNonDestructive
            )
            val assembler = AgentContextAssembler(repository, registry, config, soul, workspace)
            val compactor = ContextCompactor(repository, summarizer, estimator, config)
            val coordinator = AgentLoopCoordinator(
                repository = repository,
                modelProvider = provider,
                queue = queue,
                auditGate = auditGate,
                assembler = assembler,
                compactor = compactor,
                completionGuard = CompletionGuard(repository),
                config = config
            )
            return AgentRuntime(repository, registry, queue, coordinator)
        }
    }
}
