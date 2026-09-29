package com.yangz3720.simplywork.core.session.store

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SessionStore]. Used by JVM unit tests and as a fallback runtime mode;
 * mirrors the SQLite implementation's semantics exactly (leaf advancement is the
 * repository's decision).
 */
class InMemorySessionStore : SessionStore {

    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val nodes = ConcurrentHashMap<String, SessionNode>()
    private val nodeOrder = ConcurrentHashMap<String, MutableList<String>>()

    override fun insertSession(record: SessionRecord) {
        sessions[record.id] = record
        nodeOrder.putIfAbsent(record.id, mutableListOf())
    }

    override fun getSession(sessionId: String): SessionRecord? = sessions[sessionId]

    override fun listSessions(): List<SessionRecord> =
        sessions.values.sortedByDescending { it.createdAt }

    override fun updateActiveLeaf(sessionId: String, nodeId: String) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(activeLeafId = nodeId) }
    }

    /**
     * Inserts a node row only — leaf advancement is a repository decision
     * ([com.yangz3720.simplywork.core.session.SessionRepository.appendNode]'s advanceLeaf).
     */
    override fun insertNode(node: SessionNode) {
        nodes[node.id] = node
        nodeOrder.getOrPut(node.sessionId) { mutableListOf() }.add(node.id)
    }

    override fun updateNodeStatus(nodeId: String, status: SessionNodeStatus) {
        nodes[nodeId]?.let { nodes[nodeId] = it.copy(status = status) }
    }

    override fun rewireParent(nodeId: String, newParentId: String?) {
        nodes[nodeId]?.let { nodes[nodeId] = it.copy(parentId = newParentId) }
    }

    override fun listNodes(sessionId: String): List<SessionNode> =
        nodeOrder[sessionId].orEmpty().mapNotNull { nodes[it] }

    private val states = ConcurrentHashMap<String, String>()

    override fun upsertState(sessionId: String, stateJson: String) {
        states[sessionId] = stateJson
    }

    override fun loadState(sessionId: String): String? = states[sessionId]

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        nodeOrder.remove(sessionId)?.forEach { nodes.remove(it) }
        states.remove(sessionId)
    }
}

/** In-memory [PayloadStore] mirroring [JsonlPayloadStore] semantics. */
class InMemoryPayloadStore : PayloadStore {

    private val payloads = ConcurrentHashMap<String, String>()

    /** nodeId -> sessionId, only so that [delete] can clear a whole session cleanly ([payloads] is indexed by nodeId alone). */
    private val sessionOf = ConcurrentHashMap<String, String>()

    override fun append(sessionId: String, nodeId: String, nodeType: String, payloadJson: String): PayloadLocation {
        payloads[nodeId] = payloadJson
        sessionOf[nodeId] = sessionId
        return PayloadLocation(offset = -1L, length = payloadJson.length)
    }

    override fun read(sessionId: String, nodeId: String): String? = payloads[nodeId]

    override fun delete(sessionId: String) {
        sessionOf.entries.filter { it.value == sessionId }.forEach { entry ->
            sessionOf.remove(entry.key)
            payloads.remove(entry.key)
        }
    }
}
