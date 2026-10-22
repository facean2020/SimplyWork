package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangz3720.simplywork.R
import com.yangz3720.simplywork.core.attachment.PendingUpload
import com.yangz3720.simplywork.core.attachment.UploadKind
import com.yangz3720.simplywork.core.session.TodoItem
import com.yangz3720.simplywork.core.session.TodoStatus

/**
 * Bottom input area: run status row / failure retry row (Q2) / edit-history-prompt row -> todo entry
 * -> staged uploads -> input field.
 * Typing while running is Steering (design §6 node B: a steer takes effect on the next loop step).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    running: Boolean,
    iteration: Int,
    runError: String?,
    todos: List<TodoItem>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onShowTodos: () -> Unit,
    editing: UiMessage? = null,
    onCancelEdit: () -> Unit = {},
    /** v1.8: uploads picked but not sent yet, shown as removable chips above the field. */
    uploads: List<PendingUpload> = emptyList(),
    uploadBusy: Boolean = false,
    uploadError: String? = null,
    /**
     * v1.9: whether the selected model accepts image input. False keeps the "图片" entry visible but inert, with a
     * reason -- the alternative is letting the user pick a photo and only then have the request rejected.
     */
    canAttachImage: Boolean = true,
    onAddImage: () -> Unit = {},
    onAddDocument: () -> Unit = {},
    onRemoveUpload: (String) -> Unit = {},
    /** Resolves an image blob id to raw bytes so staged uploads can draw a thumbnail (null = no store wired). */
    loadImageBytes: (String) -> ByteArray? = { null },
    modifier: Modifier = Modifier
) {
    var showAttachSheet by remember { mutableStateOf(false) }
    val openTodos = todos.count { it.isOpen }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // Extra margin for cutout screens (large corner radius): the input bar lifts off the gesture area so it does not visually hug the edge.
            .padding(bottom = 6.dp)
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (running) {
            RunStatusRow(iteration = iteration, onStop = onStop)
        }
        if (runError != null && !running) {
            ErrorBanner(text = runError, onRetry = onRetry)
        }
        if (editing != null) {
            EditBanner(onCancel = onCancelEdit)
        }
        if (openTodos > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待办 $openTodos 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onShowTodos) {
                    Text("查看", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (uploads.isNotEmpty() || uploadBusy || uploadError != null) {
            StagedUploads(
                uploads = uploads,
                busy = uploadBusy,
                error = uploadError,
                onRemove = onRemoveUpload,
                loadImageBytes = loadImageBytes
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attaching is refused during a run (steering is text-only, so an upload picked now would have
            // nowhere to go) and while editing a past prompt (a branch only carries text). A disabled button
            // beats a file that is picked and then silently dropped.
            val canAttach = !running && !uploadBusy && editing == null
            IconButton(
                onClick = { showAttachSheet = true },
                enabled = canAttach
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "添加图片或文档",
                    tint = if (canAttach) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = when {
                            editing != null -> "修改你的提问…"
                            running -> "运行中…输入将作为转向指令"
                            uploads.isNotEmpty() -> "补充说明（可不填）"
                            else -> "描述你的目标"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions.Default,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.size(8.dp))
            // An upload-only turn is a legitimate message (an image with no caption), so the chips count as content.
            val sendable = draft.isNotBlank() || uploads.isNotEmpty()
            IconButton(
                onClick = { onSend(draft); onDraftChange("") },
                enabled = sendable
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (sendable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AttachSheet(
                canAttachImage = canAttachImage,
                onPickImage = {
                    showAttachSheet = false
                    onAddImage()
                },
                onPickDocument = {
                    showAttachSheet = false
                    onAddDocument()
                }
            )
        }
    }
}

/** Attachment source sheet: two entries, because the two paths end up in different pipelines (image vs extracted text). */
@Composable
private fun AttachSheet(canAttachImage: Boolean, onPickImage: () -> Unit, onPickDocument: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        // Section title, not a button: no clickable, no shape -- the two entries below are the actions.
        Text(
            "添加附件",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (canAttachImage) "图片需要模型支持视觉输入；文档目前支持 txt / md。"
            else "当前模型不支持图片输入，仅可添加文档（txt / md）。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        AttachEntry(
            icon = painterResource(R.drawable.ic_attach_image),
            title = "图片",
            detail = if (canAttachImage) "照片 / 截图，发送前会自动压缩" else "当前模型不支持图片输入",
            enabled = canAttachImage,
            onClick = onPickImage
        )
        Spacer(Modifier.size(8.dp))
        AttachEntry(
            icon = painterResource(R.drawable.ic_attach_document),
            title = "文档",
            detail = "txt / md，内容将作为文字读入",
            onClick = onPickDocument
        )
        Spacer(Modifier.size(16.dp))
    }
}

/**
 * One source entry as an outlined card: icon + title + detail, with a visible stroke so it reads as a
 * button and is no longer confusable with the sheet's heading text.
 *
 * Corner radius follows the input field's 22 dp (the app's control language); a smaller box radius
 * next to those pill-shaped controls reads as a stiff rectangle.
 */
@Composable
private fun AttachEntry(
    icon: Painter,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val borderColor = if (enabled) MaterialTheme.colorScheme.outline
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val titleColor = if (enabled) MaterialTheme.colorScheme.onBackground
    else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Staged uploads: what will be sent with the next message, plus the reason the last pick was rejected. */
@Composable
private fun StagedUploads(
    uploads: List<PendingUpload>,
    busy: Boolean,
    error: String?,
    onRemove: (String) -> Unit,
    loadImageBytes: (String) -> ByteArray? = { null }
) {
    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(6.dp))
            Text(
                text = "正在读取…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        uploads.forEach { upload ->
            if (upload.kind == UploadKind.Image && upload.image != null) {
                StagedImageThumb(
                    upload = upload,
                    onRemove = onRemove,
                    loadImageBytes = loadImageBytes
                )
            } else {
                StagedDocumentChip(upload = upload, onRemove = onRemove)
            }
        }
    }
}

/**
 * A staged image as a small thumbnail (56 dp) with a remove badge -- the GPT/Gemini-style
 * "preview above the input bar", sized so a few attachments never eat the conversation.
 */
@Composable
private fun StagedImageThumb(
    upload: PendingUpload,
    onRemove: (String) -> Unit,
    loadImageBytes: (String) -> ByteArray?
) {
    Box(modifier = Modifier.padding(end = 8.dp)) {
        AttachmentThumb(
            imageId = upload.image?.id,
            name = upload.name,
            loadBytes = loadImageBytes,
            size = 56.dp
        )
        // Remove badge sits on the corner so the whole tile stays the preview, not a labelled chip.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .clickable { onRemove(upload.id) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** Staged document: still a text chip (no preview target), with the truncation caveat before send. */
@Composable
private fun StagedDocumentChip(upload: PendingUpload, onRemove: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = uploadChipLabel(upload),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp)
            )
            IconButton(onClick = { onRemove(upload.id) }, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** One-line label for a staged document; a truncated file says so before it is sent, not after. */
private fun uploadChipLabel(upload: PendingUpload): String = when (upload.kind) {
    UploadKind.Image -> "图片 · ${upload.name}"
    UploadKind.Document -> "文档 · ${upload.name}" + if (upload.truncated) " · 已截断" else ""
}

/** Run status row: step indicator + stop. */
@Composable
private fun RunStatusRow(iteration: Int, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = if (iteration > 0) "第 $iteration 步 · 运行中" else "思考中…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onStop) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(2.dp))
            Text("停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Failure banner: an explicit error + retry (Q2 outer-loop restart). */
@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit) {    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.07f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Edit-history-prompt banner: it explicitly declares the branch semantics (after sending, the old conversation is kept as a sibling of the new branch). */
@Composable
private fun EditBanner(onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "正在编辑历史提问 · 发送后将从该处开启新分支",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text("取消", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ----- Empty state -----
/**
 * Empty state: welcome line + examples (no onboarding hint -- the configuration entry point is the settings gear in the top bar).
 */
@Composable
fun EmptyState(
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "今天，\n要做什么？",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "描述一个目标，我来安排步骤、调用系统工具并逐项完成。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(20.dp))
        SuggestionRow("把「买牛奶」记进待办", onSuggestion)
        SuggestionRow("发一条通知：该喝水了", onSuggestion)
        SuggestionRow("打开设置看看日历权限", onSuggestion)
    }
}

@Composable
private fun SuggestionRow(text: String, onSelect: (String) -> Unit) {
    TextButton(onClick = { onSelect(text) }) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ----- Q1: HITL approval dialog -----

@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("需要你的确认", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = "助手请求执行会直接作用于系统的操作：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = request.argsJson.ifBlank { "{}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text("允许", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("拒绝", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

// ----- Todo panel (the projection of State Memory) -----

@Composable
fun TodoSheet(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Text("待办", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.size(10.dp))
        if (todos.isEmpty()) {
            Text(
                text = "当前会话还没有待办。对助手说「把……记进待办」即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            todos.forEach { todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(status = todo.status)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = todo.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (todo.status == TodoStatus.done || todo.status == TodoStatus.cancelled)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground,
                        textDecoration = if (todo.status == TodoStatus.done) TextDecoration.LineThrough else null
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = todo.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun StatusDot(status: TodoStatus) {
    val color = when (status) {
        TodoStatus.in_progress -> MaterialTheme.colorScheme.primary
        TodoStatus.done -> MaterialTheme.colorScheme.onSurfaceVariant
        TodoStatus.cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        TodoStatus.pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .then(
                if (status == TodoStatus.in_progress) Modifier.background(color, RoundedCornerShape(4.dp))
                else Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            )
    )
}