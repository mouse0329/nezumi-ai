package com.nezumi_ai.presentation.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nezumi_ai.data.inference.ToolCallState

/**
 * 🧠 Thinking チャンネル折りたたみ表示コンポーネント
 *
 * @param thinking 思考プロセステキスト（Markdown対応推奨）
 * @param isLoading ストリーミング中フラグ
 */
@Composable
fun ExpandableThinkingBlock(
    thinking: String,
    isLoading: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    // ストリーミング中：パルスアニメーション風テキスト
                    Text(
                        text = "🧠 思考プロセス生成中...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = "🧠 思考プロセス",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = if (expanded) "▲" else "▼",
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            AnimatedVisibility(visible = expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(content = thinking)
                    }
                }
            }
        }
    }
}

/**
 * Tool Call 進捗表示コンポーネント
 *
 * 状態マシンの各段階で異なるUI表現を表示します。
 * - Result: ✅ 成功 / ❌ 失敗
 * - Responding: ✍️ 回答を作成中...
 * - Done: 完了（表示消失）
 *
 * @param state ToolCallState（null の場合は表示しない）
 */
@Composable
fun ToolCallProgressBar(
    state: ToolCallState?
) {
    if (state == null || state is ToolCallState.Done) {
        return
    }

    val (icon, color, message) = when (state) {
        is ToolCallState.Result -> {
            when (state.status) {
                "success" -> Triple(
                    "✅",
                    Color(0xFF4CAF50),
                    "${state.toolName}: 成功${state.resultMessage?.let { " ($it)" } ?: ""}"
                )
                else -> Triple(
                    "❌",
                    Color(0xFFF44336),
                    "${state.toolName}: 失敗${state.resultMessage?.let { " ($it)" } ?: ""}"
                )
            }
        }
        ToolCallState.Responding -> {
            Triple("✍️", Color(0xFF9C27B0), "回答を作成中...")
        }
        else -> return // Done, その他のケース
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$icon $message",
                    fontSize = 12.sp,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Markdown テキストレンダリングコンポーネント
 *
 * 正規表現を使用して Markdown テキストをパースし、
 * Jetpack Compose で適切にレンダリングします。
 *
 * @param content Markdown 形式のテキスト
 * @param modifier Modifier
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier
) {
    // 簡易的な Markdown スタイル処理（正規表現ベース）
    val styledText = buildAnnotatedString {
        var remainingText = content
        var currentPos = 0
        
        // **text** (bold) と *text* (italic) のパターンを検出
        val boldPattern = Regex("""\*\*([^*]+)\*\*""")
        val italicPattern = Regex("""\*([^*]+)\*""")
        
        val boldMatches = boldPattern.findAll(content)
        val italicMatches = italicPattern.findAll(content)
        
        // マッチを位置でソート
        val allMatches = (boldMatches.map { it to "bold" } + italicMatches.map { it to "italic" })
            .sortedBy { it.first.range.first }
        
        var lastEndPos = 0
        
        for ((match, styleType) in allMatches) {
            // マッチ前のプレーンテキストを追加
            if (match.range.first > lastEndPos) {
                append(content.substring(lastEndPos, match.range.first))
            }
            
            // マッチしたテキストにスタイルを適用
            val matchedText = match.groupValues[1]
            when (styleType) {
                "bold" -> withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(matchedText)
                }
                "italic" -> withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(matchedText)
                }
            }
            
            lastEndPos = match.range.last + 1
        }
        
        // 残りのテキストを追加
        if (lastEndPos < content.length) {
            append(content.substring(lastEndPos))
        }
    }
    
    Text(
        text = styledText,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/**
 * メディア添付プレビューコンポーネント
 *
 * 選択された画像・音声ファイルのプレビュー表示
 *
 * @param hasImage 画像が選択されているか
 * @param hasAudio 音声が選択されているか
 * @param onClearImage 画像クリアボタンのコールバック
 * @param onClearAudio 音声クリアボタンのコールバック
 */
@Composable
fun MediaPreviewBar(
    hasImage: Boolean,
    hasAudio: Boolean,
    onClearImage: () -> Unit = {},
    onClearAudio: () -> Unit = {},
    imageUris: List<String> = emptyList(),  // Phase 11: 複数画像URI対応
    onRemoveImage: (index: Int) -> Unit = {},  // Phase 11: 個別画像削除
    audioUri: String? = null  // Phase 12: 音声ファイル URI
) {
    if (!hasImage && !hasAudio) {
        return
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        // Phase 11: 複数画像のスクロール表示
        if (imageUris.isNotEmpty()) {
            Text(
                text = "📸 ${imageUris.size}/5 画像",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(imageUris.size) { index ->
                    Box(
                        modifier = Modifier
                            .width(90.dp)
                            .height(90.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceDim,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Phase 11: 画像サムネイル表示（AsyncImage で遅延ロード）
                        AsyncImageWithDelete(
                            uri = imageUris[index],
                            onDelete = { onRemoveImage(index) }
                        )
                    }
                }
            }
        }
        
        // Phase 12: 音声プレビュー表示（画像と同じエリアに統合）
        if (hasAudio) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Audio",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                ) {
                    Text(
                        text = "🎙️ 音声",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (audioUri != null) {
                        val fileName = audioUri.substringAfterLast("/")
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onClearAudio,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear audio",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AsyncImageWithDelete(
    uri: String,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceDim),
        contentAlignment = Alignment.Center
    ) {
        // Phase 11: 実際の画像をCoil AsyncImageで表示
        AsyncImage(
            model = uri,
            contentDescription = "Selected image preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        
        // 削除ボタン（右上）
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .background(
                    MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// 古い実装は削除（以下のコードは不要）
@Composable
@Deprecated("Use new MediaPreviewBar with imageUris parameter")
fun OldMediaPreviewBar(
    hasImage: Boolean,
    hasAudio: Boolean,
    onClearImage: () -> Unit = {},
    onClearAudio: () -> Unit = {}
) {
    if (!hasImage && !hasAudio) {
        return
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasImage) {
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🖼️",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "画像",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { onClearImage() },
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            if (hasAudio) {
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎵",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "音声",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { onClearAudio() },
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
