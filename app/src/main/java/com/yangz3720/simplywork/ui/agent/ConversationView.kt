package com.yangz3720.simplywork.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponents
import kotlinx.coroutines.delay

/**
 * Message stream (the signature element): a tool activity timeline -- calls/results render as left-side activity entries carrying a status dot,
 * assistant body text has no bubble and is pure typography, and user messages keep a right-aligned bubble to create a directional contrast.
 */
@Composable
fun ConversationList(
    messages: List<UiMessage>,
    modifier: Modifier = Modifier,
    onEditUser: (UiMessage) -> Unit = {},
    onSwitchBranch: (UiMessage, Int) -> Unit = { _, _ -> },
    /** Resolves an image blob id to raw bytes for attachment thumbnails (null = no store wired). */
    loadImageBytes: (String) -> ByteArray? = { null },
    /** In-flight assistant text (streaming). Rendered as a live markdown item at the tail of the list. */
    streamingText: String? = null
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // Stick to the bottom and scroll when new content arrives (including streaming growth).
    val tailCount = messages.size + (if (streamingText != null) 1 else 0)
    LaunchedEffect(tailCount) {
        if (tailCount > 0) listState.animateScrollToItem(tailCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages) { message ->
            MessageItem(
                message = message,
                expanded = expanded[message.toolCallId ?: message.id] == true,
                onToggleExpand = {
                    val key = message.toolCallId ?: message.id
                    expanded[key] = !(expanded[key] ?: false)
                },
                onEditUser = onEditUser,
                onSwitchBranch = onSwitchBranch,
                loadImageBytes = loadImageBytes
            )
        }
        if (streamingText != null) {
            item(key = "streaming") {
                StreamingAssistantText(text = streamingText)
            }
        }
    }
}

/**
 * Live assistant text while a model turn is still streaming: same typography pipeline as the
 * finished [AssistantText], fed with the partial markdown so far. A soft cursor marks the tail.
 */
@Composable
private fun StreamingAssistantText(text: String) {
    val body = MaterialTheme.typography.bodyLarge
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Markdown(
            content = text,
            colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
            typography = markdownTypography(
                text = body,
                paragraph = body,
                h1 = chatHeading(body, 24.sp),
                h2 = chatHeading(body, 20.sp),
                h3 = chatHeading(body, 18.sp),
                h4 = chatHeading(body, 16.sp),
                h5 = chatHeading(body, 15.sp),
                h6 = chatHeading(body, 14.sp)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        // Blinking tail cursor: cheap proof that the turn is still live (text may end on a partial token).
        StreamingCursor()
    }
}

@Composable
private fun StreamingCursor() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            visible = !visible
        }
    }
    Box(
        modifier = Modifier
            .padding(start = 2.dp, top = 2.dp)
            .size(width = 8.dp, height = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (visible) 0.85f else 0.15f),
                shape = RoundedCornerShape(1.dp)
            )
    )
}

@Composable
private fun MessageItem(
    message: UiMessage,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditUser: (UiMessage) -> Unit,
    onSwitchBranch: (UiMessage, Int) -> Unit,
    loadImageBytes: (String) -> ByteArray?
) {
    when (message.kind) {
        UiMessageKind.User -> UserBubble(message, onEditUser, onSwitchBranch, loadImageBytes)
        UiMessageKind.Assistant -> AssistantText(message)
        UiMessageKind.ToolActivity -> ToolActivityItem(message, expanded, onToggleExpand)
        UiMessageKind.Steer -> CenterPill(prefix = "转向", text = message.text)
        UiMessageKind.FollowUp -> CenterPill(prefix = "守卫跟进", text = message.text)
        UiMessageKind.Summary -> SummaryItem(message)
        UiMessageKind.SystemNote -> ErrorNote(message)
    }
}

// ----- User: right-aligned bubble (long-press to edit / branch switching) -----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: UiMessage,
    onEditUser: (UiMessage) -> Unit,
    onSwitchBranch: (UiMessage, Int) -> Unit,
    loadImageBytes: (String) -> ByteArray?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onEditUser(message) }
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // v1.8: uploads render compactly inside the bubble. v1.9: images are thumbnails (like GPT/Gemini),
                // documents stay as one-line chips -- re-printing 40k characters would bury the conversation.
                if (message.attachments.isNotEmpty()) {
                    message.attachments.forEach { attachment ->
                        AttachmentTile(attachment, loadImageBytes)
                        Spacer(Modifier.size(4.dp))
                    }
                }
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        // Branch navigation (VSCode-Copilot style): after an edit-and-resend, several prompt branches appear at the same position.
        if (message.branchCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSwitchBranch(message, -1) },
                    enabled = message.branchIndex > 0,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "上一个分支",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${message.branchIndex + 1}/${message.branchCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { onSwitchBranch(message, 1) },
                    enabled = message.branchIndex < message.branchCount - 1,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "下一个分支",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * One upload inside a user bubble: an image renders as a thumbnail (the mainstream chat look), a
 * document stays a one-line chip (its value is the extracted text, which must not fill the bubble).
 */
@Composable
private fun AttachmentTile(attachment: UiAttachment, loadImageBytes: (String) -> ByteArray?) {
    if (attachment.image) {
        AttachmentThumb(
            imageId = attachment.imageId,
            name = attachment.name,
            loadBytes = loadImageBytes,
            size = 88.dp
        )
    } else {
        val label = "文档 · ${attachment.name}" + if (attachment.truncated) " · 已截断" else ""
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

// ----- Assistant: no bubble, pure typography (Markdown rendering) -----

/**
 * Heading font sizes: the library maps h1..h6 to displayLarge/Medium/Small by default (36-57sp on a phone, too large).
 * Here they are pressed down to a gradient suited to chat, while still keeping large/medium/small distinguishable (body text is bodyLarge=16sp).
 */
private fun chatHeading(base: TextStyle, size: androidx.compose.ui.unit.TextUnit): TextStyle =
    base.copy(fontSize = size, fontWeight = FontWeight.Bold)

@Composable
private fun AssistantText(message: UiMessage) {
    if (message.isError) {
        // A business error stays plain text + error color.
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        )
    } else {
        val clipboard = LocalClipboardManager.current
        var copied by remember(message.id) { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(1600)
                copied = false
            }
        }
        val body = MaterialTheme.typography.bodyLarge
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            // Markdown rendering (mikepenz multiplatform-markdown-renderer, aligned with the M3 theme).
            Markdown(
                content = message.text,
                colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
                typography = markdownTypography(
                    text = body,
                    paragraph = body,
                    h1 = chatHeading(body, 24.sp),
                    h2 = chatHeading(body, 20.sp),
                    h3 = chatHeading(body, 18.sp),
                    h4 = chatHeading(body, 16.sp),
                    h5 = chatHeading(body, 15.sp),
                    h6 = chatHeading(body, 14.sp)
                ),
                modifier = Modifier.fillMaxWidth()

            )
            Spacer(Modifier.size(2.dp))
            // Copy the whole reply (no icon dependency: material-icons-core does not include ContentCopy).
            Text(
                text = if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelMedium,
                color = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(message.text))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

// ----- Tool activity timeline entry (the signature element) -----

@Composable
private fun ToolActivityItem(message: UiMessage, expanded: Boolean, onToggleExpand: () -> Unit) {
    val hasDetail = !message.toolArgs.isNullOrBlank() || !message.toolResult.isNullOrBlank()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasDetail) Modifier.clickable { onToggleExpand() } else Modifier)
                .padding(vertical = 2.dp)
        ) {
            // Status dot: in progress (hollow) / done (ink cyan) / failed (ochre red)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .then(
                        when {
                            message.toolResultIsError ->
                                Modifier.background(MaterialTheme.colorScheme.error, CircleShape)
                            message.toolResult != null ->
                                Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                            else ->
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    CircleShape
                                )
                        }
                    )
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = message.toolName ?: "未知调用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (message.toolResultIsError) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.weight(1f))
            if (hasDetail) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded && hasDetail) {
            Column(modifier = Modifier.padding(start = 18.dp, top = 6.dp)) {
                message.toolArgs?.takeIf { it.isNotBlank() }?.let { args ->
                    Text(
                        text = "参数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = args,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                message.toolResult?.takeIf { it.isNotBlank() }?.let { result ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = result,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.toolResultIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // v1.5: what this call did to files (a Codex-style diff card). A different layer from the expandable "result":
        // the result answers "what the tool said", the card answers "what the file looks like now".
        message.fileChange?.let { change ->
            Spacer(Modifier.size(6.dp))
            Column(modifier = Modifier.padding(start = 18.dp)) { ToolChangeCard(change) }
        }
    }
}

// ----- Steer / guard follow-up: a small centered pill -----

@Composable
private fun CenterPill(prefix: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "$prefix · $text",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// ----- Summary (the product of Episode compaction) -----

@Composable
private fun SummaryItem(message: UiMessage) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "上下文已压缩",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ErrorNote(message: UiMessage) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
    )
}