package com.yangz3720.simplywork.core.session

import com.yangz3720.simplywork.core.session.store.PayloadStore
import com.yangz3720.simplywork.core.session.store.SessionStore
import com.yangz3720.simplywork.infrastructure.error.InfrastructureException
import com.yangz3720.simplywork.infrastructure.model.model.Attachment
import com.yangz3720.simplywork.infrastructure.model.model.Message
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The single facade over the dual-medium session persistence (agent-core-design.md §2):
 * payload lines go to [PayloadStore] FIRST (fsync), then the node row to [SessionStore]
 * (crash-consistency, §2.2). All mutations are serialized through a mutex — single-writer
 * assumption (Q3), defensive only.
 */
interface SessionRepository {

    /** [projectId] = owning project (v1.3); null kept for callers that predate projects. */
    suspend fun createSession(title: String, projectId: String? = null): SessionRecord

    fun getSession(sessionId: String): SessionRecord?

    /** All sessions, newest first (UI session list). */
    fun listSessions(): List<SessionRecord>

    /**
     * Appends a node: payload line to the JSONL store, then the metadata row (which
     * advances the active leaf). Returns the persisted node.
     */
    suspend fun appendNode(
        sessionId: String,
        parentId: String?,
        nodeType: SessionNodeType,
        payload: JsonObject,
        tokenCount: Int,
        status: SessionNodeStatus,
        advanceLeaf: Boolean = true
    ): SessionNode

    suspend fun updateNodeStatus(nodeId: String, status: SessionNodeStatus)

    /** Compaction rewiring (agent-core-design.md §4). */
    suspend fun rewireParent(nodeId: String, newParentId: String?)

    /** Active branch from root to the active leaf (chronological). Empty when the session has no nodes. */
    fun activePath(sessionId: String): List<SessionNode>

    /** All nodes of the session in walk order (branch navigation / projections). */
    fun listNodes(sessionId: String): List<SessionNode>

    /**
     * Appends a user prompt as a NEW BRANCH: sibling of [parentId]'s children, leaf
     * advances to the new node (edit-and-resend semantics, VSCode-Copilot style).
     *
     * [attachments] (v1.8) are the turn's uploads; an edit-and-resend carries the original ones over
     * rather than quietly dropping the images the user attached.
     */
    suspend fun appendUserPrompt(
        sessionId: String,
        parentId: String?,
        text: String,
        attachments: List<Attachment> = emptyList()
    ): SessionNode

    /** Same-parent UserPrompt siblings of [nodeId] (inclusive), ordered by createdAt. */
    fun promptSiblings(sessionId: String, nodeId: String): List<SessionNode>

    /**
     * Deepest node of the branch rooted at [nodeId], walking the newest child at each
     * level (a branch's "current tip"). Returns [nodeId] itself when it has no children.
     */
    fun branchLeaf(sessionId: String, nodeId: String): String

    /** Leaf pointer switch (branch navigation). */
    suspend fun updateActiveLeaf(sessionId: String, nodeId: String)

    /** Decoded payload of [node]; missing payload = explicit [InfrastructureException.Session.PayloadMissing] (P2). */
    fun payload(node: SessionNode): JsonObject

    fun loadState(sessionId: String): StateMemory

    suspend fun saveState(sessionId: String, state: StateMemory)

    /**
     * Resume cleanup (agent-core-design.md §2.3): pending nodes left by a killed process
     * are marked abandoned; each pending ToolCall additionally receives a synthetic
     * ToolResult ("interrupted by restart") so message pairing stays valid.
     */
    suspend fun cleanupInterrupted(sessionId: String)

    /**
     * Deletes the session and everything that belongs to it: nodes, State Memory and the
     * payload file. Irreversible — callers get user confirmation first.
     */
    suspend fun deleteSession(sessionId: String)
}

class SessionRepositoryImpl(
    private val structureStore: SessionStore,
    private val payloadStore: PayloadStore
) : SessionRepository {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createSession(title: String, projectId: String?): SessionRecord {
        val record = SessionRecord(
            id = UUID.randomUUID().toString(),
            title = title.take(TITLE_MAX_LENGTH),
            createdAt = System.currentTimeMillis(),
            projectId = projectId
        )
        mutex.withLock { structureStore.insertSession(record) }
        return record
    }

    override fun getSession(sessionId: String): SessionRecord? = structureStore.getSession(sessionId)

    override fun listSessions(): List<SessionRecord> = structureStore.listSessions()

    override suspend fun appendNode(
        sessionId: String,
        parentId: String?,
        nodeType: SessionNodeType,
        payload: JsonObject,
        tokenCount: Int,
        status: SessionNodeStatus,
        advanceLeaf: Boolean
    ): SessionNode = mutex.withLock {
        appendNodeLocked(sessionId, parentId, nodeType, payload, tokenCount, status, advanceLeaf)
    }

    /** Lock-assumed append used by [cleanupInterrupted] (Mutex is not reentrant). */
    private fun appendNodeLocked(
        sessionId: String,
        parentId: String?,
        nodeType: SessionNodeType,
        payload: JsonObject,
        tokenCount: Int,
        status: SessionNodeStatus,
        advanceLeaf: Boolean = true
    ): SessionNode {
        val id = UUID.randomUUID().toString()
        val location = payloadStore.append(sessionId, id, nodeType.name, payload.toString())
        val node = SessionNode(
            id = id,
            sessionId = sessionId,
            parentId = parentId,
            nodeType = nodeType,
            tokenCount = tokenCount,
            status = status,
            createdAt = System.currentTimeMillis(),
            payloadOffset = location.offset,
            payloadLength = location.length
        )
        structureStore.insertNode(node)
        // Leaf advancement is a repository decision (compaction inserts must NOT move it).
        if (advanceLeaf) structureStore.updateActiveLeaf(sessionId, node.id)
        return node
    }

    override suspend fun updateNodeStatus(nodeId: String, status: SessionNodeStatus) {
        mutex.withLock { structureStore.updateNodeStatus(nodeId, status) }
    }

    override suspend fun rewireParent(nodeId: String, newParentId: String?) {
        mutex.withLock { structureStore.rewireParent(nodeId, newParentId) }
    }

    override fun activePath(sessionId: String): List<SessionNode> {
        val record = structureStore.getSession(sessionId) ?: return emptyList()
        val nodes = structureStore.listNodes(sessionId)
        if (nodes.isEmpty()) return emptyList()

        val byId = nodes.associateBy { it.id }
        // Leaf pointer missing/stale → fall back to the latest node.
        var current: SessionNode? = record.activeLeafId?.let { byId[it] } ?: nodes.last()
        val path = ArrayList<SessionNode>()
        val visited = HashSet<String>()
        while (current != null && visited.add(current.id)) {
            path.add(current)
            current = current.parentId?.let { byId[it] }
        }
        path.reverse()
        return path
    }

    override fun payload(node: SessionNode): JsonObject {
        val raw = payloadStore.read(node.sessionId, node.id)
            ?: throw InfrastructureException.Session.PayloadMissing(node.id)
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw InfrastructureException.Session.SerializeFailed("Malformed payload for node ${node.id}", e)
        }
    }

    override fun listNodes(sessionId: String): List<SessionNode> = structureStore.listNodes(sessionId)

    override suspend fun appendUserPrompt(
        sessionId: String,
        parentId: String?,
        text: String,
        attachments: List<Attachment>
    ): SessionNode =
        mutex.withLock {
            val payload = json.encodeToJsonElement(
                Message.serializer(),
                Message.User(content = text, attachments = attachments)
            ) as JsonObject
            appendNodeLocked(
                sessionId = sessionId,
                parentId = parentId,
                nodeType = SessionNodeType.UserPrompt,
                payload = payload,
                tokenCount = text.length,
                status = SessionNodeStatus.success
            )
        }

    override fun promptSiblings(sessionId: String, nodeId: String): List<SessionNode> {
        val nodes = structureStore.listNodes(sessionId)
        val target = nodes.firstOrNull { it.id == nodeId } ?: return emptyList()
        return nodes
            .filter { it.nodeType == SessionNodeType.UserPrompt && it.parentId == target.parentId }
            .sortedBy { it.createdAt }
    }

    override fun branchLeaf(sessionId: String, nodeId: String): String {
        val children = structureStore.listNodes(sessionId).groupBy { it.parentId }
        val visited = HashSet<String>()
        var current = nodeId
        while (visited.add(current)) {
            current = children[current]?.maxByOrNull { it.createdAt }?.id ?: return current
        }
        return current
    }

    override suspend fun updateActiveLeaf(sessionId: String, nodeId: String) {
        mutex.withLock { structureStore.updateActiveLeaf(sessionId, nodeId) }
    }

    override fun loadState(sessionId: String): StateMemory {
        val raw = structureStore.loadState(sessionId) ?: return StateMemory()
        return runCatching { json.decodeFromString(StateMemory.serializer(), raw) }
            .getOrElse { StateMemory() }
    }

    override suspend fun saveState(sessionId: String, state: StateMemory) {
        mutex.withLock { structureStore.upsertState(sessionId, json.encodeToString(StateMemory.serializer(), state)) }
    }

    override suspend fun cleanupInterrupted(sessionId: String) {
        mutex.withLock {
            val nodes = structureStore.listNodes(sessionId)
            val pending = nodes.filter { it.status == SessionNodeStatus.pending }
            pending.forEach { pendingNode ->
                structureStore.updateNodeStatus(pendingNode.id, SessionNodeStatus.abandoned)
                if (pendingNode.nodeType == SessionNodeType.ToolCall) {
                    // One synthetic rejection per tool call id keeps assistant/tool pairing intact.
                    val assistant = runCatching {
                        json.decodeFromString(Message.serializer(), payload(pendingNode).toString())
                            as? Message.Assistant
                    }.getOrNull()
                    val toolCalls = assistant?.toolCalls.orEmpty()
                    if (toolCalls.isEmpty()) {
                        appendSyntheticInterruptedResult(sessionId, pendingNode.id, toolCallId = "interrupted", toolName = "unknown")
                    } else {
                        toolCalls.forEach { call ->
                            appendSyntheticInterruptedResult(sessionId, pendingNode.id, call.id, call.name)
                        }
                    }
                }
            }
        }
    }

    /** Lock-assumed: called from [cleanupInterrupted] (Mutex is not reentrant). */
    private fun appendSyntheticInterruptedResult(sessionId: String, parentId: String, toolCallId: String, toolName: String) {
        val payload = json.encodeToString(
            Message.serializer(),
            Message.ToolResult(
                toolCallId = toolCallId,
                toolName = toolName,
                content = "Tool execution was interrupted by an app restart; the call never completed.",
                isError = true
            )
        )
        appendNodeLocked(
            sessionId = sessionId,
            parentId = parentId,
            nodeType = SessionNodeType.ToolResult,
            payload = json.parseToJsonElement(payload).jsonObject,
            tokenCount = 32,
            status = SessionNodeStatus.success
        )
    }

    override suspend fun deleteSession(sessionId: String) {
        // Structure first, payload after: the other way round, being killed midway leaves a row pointing at a payload that does not
        // exist (reading it throws PayloadMissing), whereas deleting the structure first at worst leaves an unreadable dead file.
        mutex.withLock {
            structureStore.deleteSession(sessionId)
            payloadStore.delete(sessionId)
        }
    }

    private companion object {
        const val TITLE_MAX_LENGTH = 48
    }
}
