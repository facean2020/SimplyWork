package com.yangz3720.simplywork.core

import android.content.Context
import com.yangz3720.simplywork.core.agent.AgentConfig
import com.yangz3720.simplywork.core.agent.AgentContextAssembler
import com.yangz3720.simplywork.core.agent.AgentLoopCoordinator
import com.yangz3720.simplywork.core.agent.AuditApprovalHandler
import com.yangz3720.simplywork.core.agent.AuditGatePipeline
import com.yangz3720.simplywork.core.agent.CompletionGuard
import com.yangz3720.simplywork.core.agent.ContextCompactor
import com.yangz3720.simplywork.core.agent.MessageQueueManager
import com.yangz3720.simplywork.core.agent.ModelSummarizer
import com.yangz3720.simplywork.core.agent.SkillInjector
import com.yangz3720.simplywork.core.agent.TokenEstimator
import com.yangz3720.simplywork.core.agent.HeuristicTokenEstimator
import com.yangz3720.simplywork.core.agent.tool.AgentToolRegistry
import com.yangz3720.simplywork.core.agent.tool.RegisteredTool
import com.yangz3720.simplywork.core.agent.tool.SystemOpsToolAdapter
import com.yangz3720.simplywork.core.agent.tool.TodoTool
import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.session.SessionRepositoryImpl
import com.yangz3720.simplywork.core.session.store.JsonlPayloadStore
import com.yangz3720.simplywork.core.session.store.SessionStore
import com.yangz3720.simplywork.core.session.store.SqliteSessionStore
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
            skillInjector: SkillInjector = SkillInjector.None,
            approvalHandler: AuditApprovalHandler = AuditApprovalHandler.AutoApprove,
            selfOps: ((repository: SessionRepository) -> List<RegisteredTool>)? = null
        ): AgentRuntime {
            val baseDir = File(context.filesDir, "agent").apply { mkdirs() }
            val structureStore: SessionStore = SqliteSessionStore(context, "sessions.db")
            val payloadStore = JsonlPayloadStore(File(baseDir, "payloads"))
            return createWithStores(structureStore, payloadStore, provider, router, config, estimator, skillInjector, approvalHandler = approvalHandler, selfOps = selfOps)
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
            skillInjector: SkillInjector = SkillInjector.None,
            summarizer: com.yangz3720.simplywork.core.agent.Summarizer = ModelSummarizer(provider),
            approvalHandler: AuditApprovalHandler = AuditApprovalHandler.AutoApprove,
            // v1.3 自操作工具（self_* / fs_* / project）：AppContainer 注入缝；缺省不注册（单元测试零改动）。
            selfOps: ((repository: SessionRepository) -> List<RegisteredTool>)? = null
        ): AgentRuntime {
            val repository = SessionRepositoryImpl(structureStore, payloadStore)
            val registry = AgentToolRegistry()
            SystemOpsToolAdapter.registerAll(registry, router)
            registry.register(TodoTool.registeredTool(repository))
            selfOps?.invoke(repository)?.forEach(registry::register)

            val queue = MessageQueueManager()
            val auditGate = AuditGatePipeline(
                registry,
                approvalHandler = approvalHandler,
                maxToolResultChars = config.maxToolResultChars,
                autoApproveNonDestructive = config.autoApproveNonDestructive
            )
            val assembler = AgentContextAssembler(repository, registry, config, skillInjector)
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
