package com.yangz3720.simplywork.ui.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * File preview page (v1.5): Markdown rendering / monospace plain text / an explicit "not supported".
 *
 * Both entry points share this one page: the "new files" card at the bottom of the session, and a file row in the file manager --
 * so "how you got here" does not change what you see.
 *
 * v1.1: allow **pulling up to a full page**. Before, `skipPartiallyExpanded = true` and the content height was hard-coded
 * (560dp / body 400dp), so "expanding" the sheet was still only half a screen -- reading a document meant forever reading through a small window.
 * Now it starts at the half-open state and a pull-up fills the whole screen: content `fillMaxHeight()`, body `weight(1f)`, so when
 * expanded the body naturally takes the remaining space.
 *
 * The body uses [verticalScroll] rather than a list: the Markdown renderer is itself a plain Column, and wrapping a lazy list
 * around it only yields same-direction nested scrolling (a crash). What scrolls is the body itself, and it scrolls as tall as it now is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(preview: FilePreview, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preview.path,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(preview.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭", style = MaterialTheme.typography.labelLarge)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.size(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                val text = preview.text
                if (text == null) {
                    // When there is nothing to show, don't leave a blank screen: state why (binary / unreadable).
                    Text(
                        text = preview.notice ?: "没有可显示的内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (preview.markdown) {
                    val body = MaterialTheme.typography.bodyMedium
                    Markdown(
                        content = text,
                        colors = markdownColor(text = MaterialTheme.colorScheme.onBackground),
                        typography = markdownTypography(
                            text = body,
                            paragraph = body,
                            h1 = documentHeading(body, 22.sp),
                            h2 = documentHeading(body, 19.sp),
                            h3 = documentHeading(body, 17.sp),
                            h4 = documentHeading(body, 16.sp),
                            h5 = documentHeading(body, 15.sp),
                            h6 = documentHeading(body, 14.sp)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                // The reason for truncation / no preview follows the body, so the reader won't mistake this for the full text.
                preview.notice?.takeIf { text != null }?.let { notice ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Document heading scale. The library by default maps h1..h6 to displayLarge/Medium/Small (36~57sp on a phone),
 * which is too large on a reading page; here we press it into a readable range while keeping large/medium/small distinguishable (body text bodyMedium=14sp).
 */
private fun documentHeading(base: TextStyle, size: TextUnit): TextStyle =
    base.copy(fontSize = size, fontWeight = FontWeight.Bold)
