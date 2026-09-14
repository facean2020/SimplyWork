package com.yangz3720.simplywork.core.session.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yangz3720.simplywork.core.session.SessionNode
import com.yangz3720.simplywork.core.session.SessionNodeStatus
import com.yangz3720.simplywork.core.session.SessionNodeType
import com.yangz3720.simplywork.core.session.SessionRecord

/**
 * SQLite-backed [SessionStore]: the DAG structure half of the dual-medium session
 * persistence (agent-core-design.md §2.1). Single shared database for all sessions;
 * payload bytes live in the JSONL store, located via payloadOffset/payloadLength.
 *
 * Kept intentionally thin (Android-bound, not JVM-testable); all semantics are covered
 * by [InMemorySessionStore] + repository tests, correctness here is verified by the
 * instrumented acceptance backlog.
 */
class SqliteSessionStore(context: Context, databaseName: String = "agent_sessions.db") :
    SQLiteOpenHelper(context, databaseName, null, DB_VERSION), SessionStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_SESSIONS)
        db.execSQL(SQL_CREATE_NODES)
        db.execSQL(SQL_CREATE_INDEX)
        db.execSQL(SQL_CREATE_STATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1→v2: add the project column IN PLACE — the device database already holds real
        // conversation history, so the old drop-and-recreate is no longer acceptable.
        // ALTER TABLE ADD COLUMN leaves pre-v1.3 rows with project_id = NULL, which the
        // repository contract reads as "default project".
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN project_id TEXT")
        }
    }

    override fun insertSession(record: SessionRecord) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO sessions(id, title, created_at, active_leaf_id, project_id) VALUES(?, ?, ?, ?, ?)",
            arrayOf(record.id, record.title, record.createdAt, record.activeLeafId, record.projectId)
        )
    }

    override fun getSession(sessionId: String): SessionRecord? =
        readableDatabase.rawQuery(
            "SELECT id, title, created_at, active_leaf_id, project_id FROM sessions WHERE id = ?",
            arrayOf(sessionId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else SessionRecord(
                id = cursor.getString(0),
                title = cursor.getString(1),
                createdAt = cursor.getLong(2),
                activeLeafId = cursor.getString(3),
                projectId = cursor.getString(4)
            )
        }

    override fun listSessions(): List<SessionRecord> =
        readableDatabase.rawQuery(
            "SELECT id, title, created_at, active_leaf_id, project_id FROM sessions ORDER BY created_at DESC",
            emptyArray()
        ).use { cursor ->
            val result = ArrayList<SessionRecord>(cursor.count)
            while (cursor.moveToNext()) {
                result.add(
                    SessionRecord(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        activeLeafId = cursor.getString(3),
                        projectId = cursor.getString(4)
                    )
                )
            }
            result
        }

    override fun updateActiveLeaf(sessionId: String, nodeId: String) {
        writableDatabase.execSQL("UPDATE sessions SET active_leaf_id = ? WHERE id = ?", arrayOf(nodeId, sessionId))
    }

    /**
     * Inserts a node row only — leaf advancement is a repository decision
     * ([com.yangz3720.simplywork.core.session.SessionRepository.appendNode]'s advanceLeaf).
     */
    override fun insertNode(node: SessionNode) {
        writableDatabase.execSQL(
            "INSERT INTO nodes(id, session_id, parent_id, node_type, token_count, status, created_at, payload_offset, payload_length) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                node.id, node.sessionId, node.parentId, node.nodeType.name, node.tokenCount,
                node.status.name, node.createdAt, node.payloadOffset, node.payloadLength
            )
        )
    }

    override fun updateNodeStatus(nodeId: String, status: SessionNodeStatus) {
        writableDatabase.execSQL("UPDATE nodes SET status = ? WHERE id = ?", arrayOf(status.name, nodeId))
    }

    override fun rewireParent(nodeId: String, newParentId: String?) {
        writableDatabase.execSQL("UPDATE nodes SET parent_id = ? WHERE id = ?", arrayOf(newParentId, nodeId))
    }

    override fun listNodes(sessionId: String): List<SessionNode> =
        readableDatabase.rawQuery(
            "SELECT id, parent_id, node_type, token_count, status, created_at, payload_offset, payload_length " +
                "FROM nodes WHERE session_id = ? ORDER BY created_at ASC, rowid ASC",
            arrayOf(sessionId)
        ).use { cursor ->
            val result = ArrayList<SessionNode>(cursor.count)
            while (cursor.moveToNext()) {
                result.add(
                    SessionNode(
                        id = cursor.getString(0),
                        sessionId = sessionId,
                        parentId = cursor.getString(1),
                        nodeType = SessionNodeType.valueOf(cursor.getString(2)),
                        tokenCount = cursor.getInt(3),
                        status = SessionNodeStatus.valueOf(cursor.getString(4)),
                        createdAt = cursor.getLong(5),
                        payloadOffset = cursor.getLong(6),
                        payloadLength = cursor.getInt(7)
                    )
                )
            }
            result
        }

    override fun upsertState(sessionId: String, stateJson: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO session_state(session_id, state_json) VALUES(?, ?)",
            arrayOf(sessionId, stateJson)
        )
    }

    override fun loadState(sessionId: String): String? =
        readableDatabase.rawQuery(
            "SELECT state_json FROM session_state WHERE session_id = ?",
            arrayOf(sessionId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    override fun deleteSession(sessionId: String) {
        val db = writableDatabase
        // 三张表必须一起消失：留一行悬空 node 会让"会话已删"变成半个事实。
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM nodes WHERE session_id = ?", arrayOf(sessionId))
            db.execSQL("DELETE FROM session_state WHERE session_id = ?", arrayOf(sessionId))
            db.execSQL("DELETE FROM sessions WHERE id = ?", arrayOf(sessionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DB_VERSION = 2

        private const val SQL_CREATE_SESSIONS =
            "CREATE TABLE sessions (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "active_leaf_id TEXT, " +
                "project_id TEXT)"

        private const val SQL_CREATE_NODES =
            "CREATE TABLE nodes (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "session_id TEXT NOT NULL, " +
                "parent_id TEXT, " +
                "node_type TEXT NOT NULL, " +
                "token_count INTEGER NOT NULL, " +
                "status TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "payload_offset INTEGER NOT NULL, " +
                "payload_length INTEGER NOT NULL)"

        private const val SQL_CREATE_INDEX =
            "CREATE INDEX idx_nodes_session ON nodes(session_id, created_at)"

        private const val SQL_CREATE_STATE =
            "CREATE TABLE session_state (" +
                "session_id TEXT PRIMARY KEY NOT NULL, " +
                "state_json TEXT NOT NULL)"
    }
}
