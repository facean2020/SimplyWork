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
 * 文件预览页（v1.5）：Markdown 渲染 / 纯文本等宽 / 明说"不支持"。
 *
 * 两个入口共用这一个页面：会话底部的「新增文件」卡片，和文件管理器里的文件行 ——
 * 所以"从哪进来"不影响看到什么。
 *
 * v1.1：允许**上拉成整页**。原先 `skipPartiallyExpanded = true` 且内容高度写死
 * （560dp / 正文 400dp），所以面板“展开”也只有半个屏幕 —— 读文档时等于永远读一个小窗。
 * 现在初始停在半开态，往上拉就铺满整屏：内容 `fillMaxHeight()`、正文 `weight(1f)`，
 * 展开时正文自然占满剩余空间。
 *
 * 正文用 [verticalScroll] 而不是列表：Markdown 渲染器自身是普通 Column，套惰性列表
 * 只会得到同向嵌套滚动（崩）。滚的是正文本身，而它现在有多高就滚多高。
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
                    // 没有内容可显示时不留白屏：说明为什么（二进制 / 读不到）。
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
                // 截断/不可预览的理由跟在正文后面，读者不会误以为这就是全文。
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
 * 文档标题梯度。库默认把 h1..h6 映射到 displayLarge/Medium/Small（手机上 36~57sp），
 * 在阅读页里过大；这里压到能读的区间，仍保留大中小可辨（正文 bodyMedium=14sp）。
 */
private fun documentHeading(base: TextStyle, size: TextUnit): TextStyle =
    base.copy(fontSize = size, fontWeight = FontWeight.Bold)
