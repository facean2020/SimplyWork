package com.yangz3720.simplywork.core.session.store

import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionRecord

/**
 * Structure-side store: the DAG (nodes + tree edges + session rows) in SQLite
 * (agent-core-design.md §2). Payload bytes are NOT handled here — see [PayloadStore].
 */
interface SessionStore {

    fun insertSession(record: SessionRecord)

    fun getSession(sessionId: String): SessionRecord?

    /** All sessions, newest first (UI session list, ChatGPT-style). */
    fun listSessions(): List<SessionRecord>

    fun updateActiveLeaf(sessionId: String, nodeId: String)

    /**
     * Inserts a node row only — leaf advancement is the repository's decision.
     */
    fun insertNode(node: SessionNode)

    fun updateNodeStatus(nodeId: String, status: SessionNodeStatus)

    /** Compaction rewiring: reparent [nodeId] (agent-core-design.md §4). */
    fun rewireParent(nodeId: String, newParentId: String?)

    /** All nodes of a session ordered by createdAt (stable walk order). */
    fun listNodes(sessionId: String): List<SessionNode>

    /** Persists the State Memory blob (serialized [com.yangz3720.simplywork.core.session.StateMemory]). */
    fun upsertState(sessionId: String, stateJson: String)

    /** Returns the State Memory blob or null when never written. */
    fun loadState(sessionId: String): String?

    /**
     * Deletes a session row together with its nodes and State Memory blob. Irreversible —
     * the payload file is the [PayloadStore]'s to remove (see [PayloadStore.delete]).
     */
    fun deleteSession(sessionId: String)
}

/** Location of a payload record inside its JSONL file. */
data class PayloadLocation(val offset: Long, val length: Int)

/**
 * Payload-side store: one JSONL file per session, append-only, one line per record
 * (agent-core-design.md §2.1). Keyed by node id; offset/length are persisted as node
 * metadata for future random access.
 */
interface PayloadStore {

    /** Appends one line; returns the location to store in the node row. */
    fun append(sessionId: String, nodeId: String, nodeType: String, payloadJson: String): PayloadLocation

    /** Returns the payload line for [nodeId] or null when absent (P2: callers surface this). */
    fun read(sessionId: String, nodeId: String): String?

    /** Removes the session's whole JSONL file (session deletion). Absent file is a no-op. */
    fun delete(sessionId: String)
}
