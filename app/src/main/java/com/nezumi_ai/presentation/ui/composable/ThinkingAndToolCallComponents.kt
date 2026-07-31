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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.R

/**
 * Thinking チャンネル折りたたみ表示コンポーネント
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
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !isLoading) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    Text(
                        text = "思考プロセス生成中...",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = "思考プロセス",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = if (expanded) "▲" else "▼",
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (isLoading) 0.5f else 1f
                    )
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
 * ストリーミング中の AI メッセージ内に表示するツール呼び出しインジケーター。
 * 下部の ToolResultCard とは別に、出力欄内で呼び出しタイミングを示す。
 */
/**
 * 生成完了後も履歴に残すツール呼び出しインジケーター。
 * ストリーミング中の [StreamingToolCallIndicator] と同じ位置・スタイルで表示する。
 */
@Composable
fun PersistedToolCallIndicators(cards: List<ToolResultCard>) {
    if (cards.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        cards.forEach { card ->
            val label = if (card.success) {
                stringResource(R.string.tool_call_result_success, card.toolName)
            } else {
                stringResource(R.string.tool_call_result_error, card.toolName)
            }
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                color = colorResource(id = R.color.text_secondary),
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
fun StreamingToolCallIndicator(state: ToolCallState) {
    if (state is ToolCallState.Done) return

    val label = when (state) {
        is ToolCallState.Executing -> toolExecutingLabel(state.toolName)
        is ToolCallState.Result -> when (state.status) {
            "success" -> stringResource(R.string.tool_call_result_success, state.toolName)
            else -> stringResource(R.string.tool_call_result_error, state.toolName)
        }
        ToolCallState.Responding -> stringResource(R.string.tool_call_responding)
        else -> return
    }

    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        fontSize = 13.sp,
        color = colorResource(id = R.color.text_secondary),
        fontStyle = FontStyle.Italic
    )
}

@Composable
private fun toolExecutingLabel(toolName: String): String = when (toolName.lowercase()) {
    "set_alarm" -> stringResource(R.string.tool_call_executing_alarm)
    "send_message" -> stringResource(R.string.tool_call_executing_message)
    "search", "web_search", "search_memory" -> stringResource(R.string.tool_call_executing_search)
    "generate_image" -> stringResource(R.string.tool_call_executing_image)
    else -> stringResource(R.string.tool_call_executing_generic, toolName)
}

/**
 * Tool Call 進捗表示コンポーネント
 *
 * 状態マシンの各段階で異なるUI表現を表示します。
 * - Result: 成功 / 失敗
 * - Responding: 回答を作成中...
 * - Done: 完了（表示消失）
 *
 * @param state ToolCallState（null の場合は表示しない）
 */
@Composable
fun ToolCallProgressBar(
    state: ToolCallState?,
    imageGenProgress: Pair<Int, Int>? = null
) {
    if (state == null || state is ToolCallState.Done) {
        return
    }

    val (icon, color, message) = when (state) {
        is ToolCallState.Executing -> {
            Triple(
 "",
                colorResource(id = R.color.text_secondary),
                toolExecutingLabel(state.toolName)
            )
        }
        is ToolCallState.Result -> {
            when (state.status) {
                "success" -> Triple(
                    "[OK]",
                    Color(0xFF4CAF50),
                    "${state.toolName}: 成功${state.resultMessage?.let { " ($it)" } ?: ""}"
                )
                else -> Triple(
                    "[ERROR]",
                    Color(0xFFF44336),
                    "${state.toolName}: 失敗${state.resultMessage?.let { " ($it)" } ?: ""}"
                )
            }
        }
        ToolCallState.Responding -> {
 Triple("", colorResource(id = R.color.text_secondary), stringResource(R.string.tool_call_responding))
        }
        else -> return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.surface_card)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 画像生成ツール専用：進捗表示は generate_image ツールのみ
            val progress = if (
                state is ToolCallState.Executing &&
                    state.toolName.equals("generate_image", ignoreCase = true)
            ) {
                imageGenProgress
            } else {
                null
            }
            
            val progressFraction = progress?.let { (step, total) ->
                if (total > 0) (step.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
            }

            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = progressFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = color,
                    trackColor = color.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = color,
                    trackColor = color.copy(alpha = 0.2f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$icon $message",
                    fontSize = 12.sp,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
                
                if (progress != null) {
                    Text(
                        text = "${progress.first}/${progress.second}",
                        fontSize = 12.sp,
                        color = Color(0xFFFF6F00),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * メディア添付プレビューコンポーネント
 *
 * 選択された画像・動画・音声ファイルの統一プレビュー表示。
 * タップで MediaViewerDialog (統一ビュワー) を開く。
 *
 * @param hasImage 画像が選択されているか
 * @param hasAudio 音声が選択されているか
 * @param onClearImage 画像クリアボタンのコールバック
 * @param onClearAudio 音声クリアボタンのコールバック
 * @param videoUri  元動画 URI (存在すればフレーム列と別に動画サムネカードを先頭に並べる)
 * @param onClearVideo 動画クリアボタン
 * @param isExtractingVideo 動画選択後、フレーム/音声抽出処理が進行中かどうか。
 *   true の間はサムネの代わりにスピナーカードを表示する (抽出結果がまだ無いため)。
 *   送信可否の制御はこの Composable ではなく呼び出し側 (送信ボタン) が isExtractingVideo を見て行う。
 * @param onOpenViewer 項目をタップしたときの統一ビュワーを開くコールバック。
 *   selectedKey: "video" または "image:<index>"
 */
@Composable
fun MediaPreviewBar(
    hasImage: Boolean,
    hasAudio: Boolean,
    onClearImage: () -> Unit = {},
    onClearAudio: () -> Unit = {},
    imageUris: List<String> = emptyList(),
    onRemoveImage: (index: Int) -> Unit = {},
    audioUri: String? = null,
    videoUri: String? = null,
    onClearVideo: () -> Unit = {},
    isExtractingVideo: Boolean = false,
    onOpenViewer: (selectedKey: String) -> Unit = {}
) {
    val hasVideo = !videoUri.isNullOrBlank()
    if (!hasImage && !hasAudio && !hasVideo && !isExtractingVideo) {
        return
    }

    // 動画がある場合、imageUris は内部的にはその動画から分解したフレーム列。
    // ユーザーには「動画1本」としてのみ見せたいので、個別の画像サムネとしては列挙しない
    // (動画サムネの背景に先頭フレームだけ流用する分には内部利用として問題ない)。
    val imagesToShow = if (hasVideo) emptyList() else imageUris

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        // 統一ストリップ: [動画サムネ (あれば)] [音声チップ (あれば)] [画像...] を 1 本の LazyRow に並べる
        run {
            val labelParts = buildList {
                if (isExtractingVideo) add("動画を解析中…")
                if (hasVideo) add("動画1本")
                // 動画には音声トラックが含まれているので、hasVideo のときは
                // 「音声あり」を別途表示しない (チップ側と揃えて一元化)
                if (hasAudio && !hasVideo) add("音声あり")
                if (imagesToShow.isNotEmpty()) add("${imagesToShow.size}/5 画像")
            }
            if (labelParts.isNotEmpty()) {
                Text(
                    text = labelParts.joinToString(" ・ "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (hasVideo || hasAudio || imagesToShow.isNotEmpty() || isExtractingVideo) {
            
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
 // バグ修正: items に key を指定して画像の一意性を保証
                // key を指定しないと、リスト順序が変わった時に古い Composable が再利用される可能性がある
                // 0) 動画抽出中スピナー (フレーム/音声抽出が終わるまで、動画サムネの代わりに表示)
                if (isExtractingVideo) {
                    item(key = "video_extracting") {
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
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                // 1) 動画サムネ (あれば先頭)
                if (hasVideo) {
                    item(key = "video") {
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
                                    MaterialTheme.colorScheme.primary,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                )
                                .clickable { onOpenViewer("video") },
                            contentAlignment = Alignment.Center
                        ) {
                            // 動画先頭フレームを背景に (imageUris の先頭を代用)
                            val bg = imageUris.firstOrNull()
                            if (bg != null) {
                                AsyncImage(
                                    model = bg,
                                    contentDescription = "Video thumbnail",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x66000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            IconButton(
                                onClick = onClearVideo,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear video",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // 2) 音声チップ
                //    動画由来の音声トラック (hasVideo=true 時の audioUri) は動画サムネの一部として
                //    扱い、単独の音声チップとしては並べない。ユーザー視点で「動画1本 + 音声1本」の
                //    2つのメディアが並んで見えると誤解される (動画側にも音声トラックが含まれるため
                //    実質同じ音源を二重に表示していることになる)。
                //    通常の音声単独添付 (動画なし) のときだけ独立チップを描画する。
                if (hasAudio && !hasVideo) {
                    item(key = "audio") {
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(90.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                )
                                .clickable { onOpenViewer("audio") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Audio",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            IconButton(
                                onClick = onClearAudio,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear audio",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // 3) 画像列 (動画由来のフレーム列は imagesToShow=empty のため表示されない)
                items(
                    count = imagesToShow.size,
                    key = { index -> "img:" + (imagesToShow.getOrNull(index) ?: index.toString()) }
                ) { index ->
                    val uri = imagesToShow.getOrNull(index) ?: return@items
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
                            )
                            .clickable { onOpenViewer("image:$index") },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImageWithDelete(
                            uri = uri,
                            onDelete = { onRemoveImage(index) }
                        )
                    }
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
