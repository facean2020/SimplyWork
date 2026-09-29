package com.yangz3720.simplywork.core.agent

import com.yangz3720.simplywork.core.agent.tool.ToolExecutionContext
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionRecord
import com.yangz3720.simplywork.core.session.SessionRepository
import com.yangz3720.simplywork.core.session.SystemPromptKind
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.model.ModelProvider
import com.yangz3720.simplywork.infrastructure.model.model.Message
import com.yangz3720.simplywork.infrastructure.model.model.ModelResponse
import com.yangz3720.simplywork.infrastructure.model.model.ToolCall
import com.yangz3720.simplywork.infrastructure.util.AgentLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Agent Loop driver (agent-core-design.md §6) — a faithful, deterministic
 * implementation of the user's flow chart:
 *
 * ```
 * B drain queue → C system prompt → D tools → E compaction → F assemble
 * → H LLM → I persist turn → J tool calls?
 *     ├─ Yes → O audit → P execute → Q post-gate → (T) next loop
 *     │        └─ deny → persisted rejection result → (U) next loop
 *     └─ No  → K queue empty? → no → (L) next loop
 *                             → yes → N completion guard
 *                                      ├─ completed → (M) end
 *                                      └─ not → inject follow-up → (W) next loop
 * ```
 *
 * Cold [Flow]: one collector = one run. Cancellation propagates through the model
 * call's suspension point; already-persisted nodes stay (resume cleanup is §2.3).
 */
class AgentLoopCoordinator(
    private val repository: SessionRepository,
    private val modelProvider: ModelProvider,
    private val queue: MessageQueueManager,
    private val auditGate: AuditGatePipeline,
    private val assembler: AgentContextAssembler,
    private val compactor: ContextCompactor,
    private val completionGuard: CompletionGuard,
    private val config: AgentConfig = AgentConfig()
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val estimator = HeuristicTokenEstimator()

    /**
     * One collector = one run. [initialPrompt] is optional: a `null` restarts the loop
     * over the existing session without enqueueing a new user message — the Q2 decision
     * (agent-core-design.md §12): transient LLM failures are retried loop-OUTER by the
     * caller (UI retry button), never auto-retried inside the loop.
     */
    fun run(sessionId: String, initialPrompt: String?): Flow<AgentStepEvent> = flow {
        val session = requireExistingSession(sessionId)

        // §2.3: clean up pending nodes a previous (killed) run may have left behind.
        repository.cleanupInterrupted(sessionId)

        // ③ C: base system prompt node persists once per session, before the first prompt.
        ensureSystemPromptNode(session)

        if (initialPrompt != null) {
            queue.enqueue(IncomingMessage.UserPrompt(initialPrompt))
        }
        emit(AgentStepEvent.LoopStarted(sessionId))

        var guardInterventions = 0

        for (iteration in 1..config.maxLoopIterations) {
            // ② B: drain queue → persist every inbound message as a node.
            val incoming = queue.drainAll()
            // A real user signal resets the completion-guard intervention counter.
            if (incoming.any { it is IncomingMessage.UserPrompt || it is IncomingMessage.Steer }) {
                guardInterventions = 0
            }
            drainAndPersist(sessionId, incoming)
            if (incoming.isNotEmpty()) emit(AgentStepEvent.InboundPersisted(incoming.size))

            // ⑤ E: compaction before assembly.
            var path = repository.activePath(sessionId)
            when (val outcome = compactor.maybeCompact(sessionId, path)) {
                is ContextCompactor.Outcome.Compacted ->
                    emit(AgentStepEvent.ContextCompacted(outcome.summaryNodeId, outcome.savedTokens))
                is ContextCompactor.Outcome.Skipped ->
                    emit(AgentStepEvent.CompactionSkipped(outcome.reason))
                ContextCompactor.Outcome.NotNeeded -> Unit
            }

            // ⑥ F: convert format.
            path = repository.activePath(sessionId)
            val request = assembler.assemble(sessionId, path)

            // ⑦ H: send to LLM (P5 timeout; no auto-retry, design Q2).
            val response: ModelResponse = callModel(sessionId, iteration, request) ?: return@flow

            // ⑧ I: persist the assistant turn as a ToolCall node (pending → finalized below).
            val assistantNode = repository.appendNode(
                sessionId = sessionId,
                parentId = path.lastOrNull()?.id,
                nodeType = SessionNodeType.ToolCall,
                payload = json.encodeToJsonElement(Message.serializer(), response.message) as JsonObject,
                tokenCount = response.usage.completionTokens.takeIf { it > 0 }
                    ?: estimator.estimate(response.text),
                status = SessionNodeStatus.pending
            )
            emit(
                AgentStepEvent.ModelTurn(
                    iteration = iteration,
                    text = response.text,
                    toolCallCount = response.toolCalls.size,
                    promptTokens = response.usage.promptTokens,
                    completionTokens = response.usage.completionTokens
                )
            )

            // ⑨ J: does the response contain tool calls?
            if (response.hasToolCalls) {
                val anyDenied = executeToolCalls(sessionId, assistantNode, response)
                repository.updateNodeStatus(assistantNode.id, if (anyDenied) SessionNodeStatus.failed else SessionNodeStatus.success)
                continue // (T)/(U): next loop
            }

            // Text-only turn: complete immediately (nothing is in flight).
            repository.updateNodeStatus(assistantNode.id, SessionNodeStatus.success)

            // ⑫ K: queue empty?
            if (!queue.isEmpty) continue // (L) — queued messages are drained at ②

            // ⑬ N: completion guard.
            val verdict = completionGuard.evaluate(sessionId, response.text)
            if (verdict.completed) {
                emit(AgentStepEvent.Completed(sessionId, response.text))
                return@flow // (M)
            }

            // Safety valve: too many consecutive guard interventions → forced stop (§6).
            guardInterventions++
            if (guardInterventions >= config.maxGuardInterventions) {
                AgentLogger.w(TAG) { "Completion guard exhausted ($guardInterventions); forcing stop" }
                emit(
                    AgentStepEvent.Completed(
                        sessionId,
                        response.text.ifBlank { "(stopped: completion guard exhausted)" },
                        reason = AgentStepEvent.COMPLETED_GUARD_EXHAUSTED
                    )
                )
                return@flow
            }

            // (W): follow-up enters the queue; next loop ② persists it as SystemPrompt(kind=follow_up).
            val followUpText = verdict.followUp ?: "(continue)"
            queue.enqueue(IncomingMessage.FollowUp(followUpText, verdict.reason.orEmpty()))
            emit(AgentStepEvent.FollowUpInjected(followUpText, verdict.reason.orEmpty()))
        }

        // Loop budget exhausted.
        val budgetError = IllegalStateException("Loop budget exceeded (${config.maxLoopIterations} iterations)")
        AgentLogger.e(TAG, budgetError) { "Run aborted" }
        emit(AgentStepEvent.Failed(sessionId, budgetError))
    }

    // ----- loop pieces -----

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentStepEvent>.drainAndPersist(
        sessionId: String,
        incoming: List<IncomingMessage>
    ) {
        if (incoming.isEmpty()) return
        var parentId: String? = repository.activePath(sessionId).lastOrNull()?.id
        for (message in incoming) {
            val (nodeType, payload, tokenText) = when (message) {
                is IncomingMessage.UserPrompt -> Triple(
                    SessionNodeType.UserPrompt,
                    json.encodeToJsonElement(Message.serializer(), Message.User(message.text, message.attachments)) as JsonObject,
                    message.text
                )
                is IncomingMessage.Steer -> Triple(
                    SessionNodeType.Steer,
                    buildJsonObject { put("text", message.text); put("atLoop", message.atLoop) },
                    message.text
                )
                is IncomingMessage.FollowUp -> Triple(
                    SessionNodeType.SystemPrompt,
                    buildJsonObject {
                        put("kind", SystemPromptKind.follow_up.name)
                        put("text", message.text)
                    },
                    message.text
                )
            }
            val node = repository.appendNode(
                sessionId = sessionId,
                parentId = parentId,
                nodeType = nodeType,
                payload = payload,
                tokenCount = estimator.estimate(tokenText),
                status = SessionNodeStatus.success
            )
            parentId = node.id
        }
    }

    private suspend fun ensureSystemPromptNode(session: SessionRecord) {
        val path = repository.activePath(session.id)
        val hasBasePrompt = path.firstOrNull()?.nodeType == SessionNodeType.SystemPrompt
        if (!hasBasePrompt) {
            repository.appendNode(
                sessionId = session.id,
                parentId = null,
                nodeType = SessionNodeType.SystemPrompt,
                payload = buildJsonObject {
                    put("kind", SystemPromptKind.base.name)
                    put("text", config.systemPrompt)
                },
                tokenCount = estimator.estimate(config.systemPrompt),
                status = SessionNodeStatus.success
            )
        }
    }

    /**
     * ⑨ J → ⑩ O → ⑪ P → ⑫ Q for every tool call of the turn.
     * @return true when at least one call was audit-denied.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentStepEvent>.executeToolCalls(
        sessionId: String,
        assistantNode: SessionNode,
        response: ModelResponse
    ): Boolean {
        var anyDenied = false
        for (call in response.toolCalls) {
            // Context travels with the call (v1.5): a tool hangs its change back onto this very entry by toolCallId.
            val context = ToolExecutionContext(sessionId, call.id)
            when (val decision = auditGate.preToolAudit(call)) {
                is AuditGatePipeline.AuditDecision.Approved -> {
                    emit(AgentStepEvent.ToolStarted(call.name, call.id))
                    // ⑪ P: execute — a crash is normalized into an error JSON (P2).
                    val resultJson = try {
                        decision.tool.execute(decision.parsed, context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        buildJsonObject {
                            put("status", "failed")
                            put("message", e.message ?: e::class.simpleName)
                        }
                    }
                    // ⑫ Q: post-tool gate → persist result → (T) next loop.
                    val content = auditGate.postToolProcess(resultJson)
                    persistToolResult(sessionId, assistantNode.id, call, content, isError = false)
                    emit(AgentStepEvent.ToolCompleted(call.name, call.id, isError = false))
                }

                is AuditGatePipeline.AuditDecision.Denied -> {
                    // (U): persist the rejection as an error result so the model self-corrects.
                    anyDenied = true
                    persistToolResult(
                        sessionId = sessionId,
                        assistantNodeId = assistantNode.id,
                        call = call,
                        content = buildJsonObject {
                            put("status", "audit_denied")
                            put("reason", decision.reason)
                        }.toString(),
                        isError = true
                    )
                    emit(AgentStepEvent.ToolRejected(call.name, call.id, decision.reason))
                }
            }
        }
        return anyDenied
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentStepEvent>.callModel(
        sessionId: String,
        iteration: Int,
        request: com.yangz3720.simplywork.infrastructure.model.model.ModelRequest
    ): ModelResponse? {
        val result = try {
            withTimeout(config.llmTimeoutMs) { modelProvider.generate(request) }
        } catch (e: TimeoutCancellationException) {
            val timeout = InfrastructureException.Model.NetworkTimeout("LLM turn timed out after ${config.llmTimeoutMs}ms")
            AgentLogger.e(TAG, timeout) { "Iteration $iteration timed out" }
            emit(AgentStepEvent.Failed(sessionId, timeout))
            return null
        }
        return result.getOrElse { failure ->
            AgentLogger.e(TAG, failure) { "Model call failed at iteration $iteration" }
            emit(AgentStepEvent.Failed(sessionId, failure))
            null
        }
    }

    private suspend fun persistToolResult(
        sessionId: String,
        assistantNodeId: String,
        call: ToolCall,
        content: String,
        isError: Boolean
    ) {
        repository.appendNode(
            sessionId = sessionId,
            parentId = assistantNodeId,
            nodeType = SessionNodeType.ToolResult,
            payload = json.encodeToJsonElement(
                Message.serializer(),
                Message.ToolResult(toolCallId = call.id, toolName = call.name, content = content, isError = isError)
            ) as JsonObject,
            tokenCount = estimator.estimate(content),
            status = SessionNodeStatus.success
        )
    }

    private suspend fun requireExistingSession(sessionId: String): SessionRecord =
        repository.getSession(sessionId)
            ?: throw IllegalArgumentException("Session $sessionId does not exist — create it via SessionRepository.createSession first")

    private companion object {
        const val TAG = "AgentLoop"
    }
}
