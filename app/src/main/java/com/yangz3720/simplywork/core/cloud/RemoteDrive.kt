package com.yangz3720.simplywork.core.cloud

/**
 * Cloud item -- the minimal projection of a Graph `driveItem` for this app. Only what mirror sync actually needs is kept:
 * `id` (primary key), `name`/`parentId` (to derive the logical path), `eTag` (change criterion),
 * `modifiedAtMs` (display and conflict hints), `deleted` (incremental deletion marker).
 *
 * Only [id] is reliable for a deleted item: in a delta Graph does not guarantee name/parent for deleted entries,
 * so deletions must look `id` back up to a path through the local manifest (see [ProjectSyncEngine]).
 */
data class RemoteItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val eTag: String?,
    val modifiedAtMs: Long,
    val sizeBytes: Long,
    val parentId: String? = null,
    val deleted: Boolean = false
)

/**
 * The result of one delta query.
 *
 * [reset] = true means this was a **full enumeration** (first bind, or a rebuild after the cursor expired with HTTP 410);
 * [items] is then the complete current state of that cloud folder, not just the deltas -- the engine uses this to mark entries "present in the
 * manifest but absent from the enumeration" as deleted in the cloud. When the delta is false, an absent entry does not mean it was deleted.
 */
data class RemoteDelta(
    val items: List<RemoteItem>,
    val deltaLink: String,
    val reset: Boolean
)

/** Explicit signal that the cloud delta cursor is invalid (HTTP 410 Gone); the engine decides whether to rebuild the baseline. */
class DeltaCursorExpired(message: String) : Exception(message)

/**
 * Cloud drive port (the OneDrive implementation lives in `infrastructure/cloud/onedrive/OneDriveGraphClient`,
 * with an in-memory fake for JVM unit tests). Deliberately narrow: mirror sync needs only these few things.
 *
 * Contract: all methods throw on failure (including [DeltaCursorExpired]), and [ProjectSyncEngine]
 * decides whether to retry or report -- the port layer does no silent degradation (P2).
 */
interface RemoteDrive {

    /** Fetches folder metadata; throws when it is not a folder or does not exist. */
    suspend fun folder(folderId: String): RemoteItem

    /** Lists children under [parentFolderId] (used by the folder picker, not part of incremental sync). */
    suspend fun children(folderId: String): List<RemoteItem>

    /** Creates a folder; when one with the same name already exists the implementation decides whether to return the existing item (Graph uses the fail conflict behaviour). */
    suspend fun createFolder(parentFolderId: String, name: String): RemoteItem

    /**
     * Incrementally enumerates the [folderId] subtree. A null [deltaLink] does a full enumeration ([RemoteDelta.reset] = true).
     * When the cursor has expired it throws [DeltaCursorExpired], and the caller retries with null.
     */
    suspend fun delta(folderId: String, deltaLink: String?): RemoteDelta

    suspend fun download(itemId: String): ByteArray

    /** Creates or overwrites [parentFolderId]/[fileName] (size limits are handled by the implementation). */
    suspend fun upload(parentFolderId: String, fileName: String, bytes: ByteArray): RemoteItem

    suspend fun deleteItem(itemId: String)
}
