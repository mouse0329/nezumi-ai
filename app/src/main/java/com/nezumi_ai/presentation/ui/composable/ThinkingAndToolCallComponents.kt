package com.nezumi_ai.presentation.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
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

// PersistedToolCallIndicators / StreamingToolCallIndicator は InlineToolCallCard
// (インラインカード) に統合したため削除。本文中の <tool_call> タグ位置で
// InlineToolCallMessageBody が InlineToolCallCard を差し込む方式に切り替え済み。
// ToolCallProgressBar (画面下部のグローバル進捗バー・画像生成プログレスなど) はここに残す。

@Composable
private fun toolExecutingLabel(toolName: String): String = when (toolName.lowercase()) {
    "set_alarm" -> stringResource(R.string.tool_call_executing_alarm)
    "send_message" -> stringResource(R.string.tool_call_executing_message)
    "search", "web_search", "search_memory" -> stringResource(R.string.tool_call_executing_search)
    "generate_image" -> stringResource(R.string.tool_call_executing_image)
    else -> stringResource(R.string.tool_call_executing_generic, toolName)
}

/**
 * generate_image 専用の進捗カード。
 * モック `nezumi-imagegen-progress-v1` に準拠:
 * - % 表示 + シマーアニメーションするグラデーションバー
 * - 「プロンプトを解析 → 拡散モデルで生成 (step x/y) → 仕上げ処理」の 3 ステップチェックリスト
 * - 生成中プレースホルダー（完成前のプレビュー枠）
 *
 * フェーズはツール側から明示的に渡されないため、step/totalSteps から推定する:
 *   step == 0            → まだ拡散ループに入っていない「プロンプトを解析」中
 *   0 < step < totalSteps → 拡散ループ実行中「拡散モデルで生成」
 *   step >= totalSteps    → 最終デコード/後処理中「仕上げ処理」
 * (実エンジンは今のところこの3値以上の粒度を返さないため、この近似で十分な体感を作れる。
 *  将来 onProgress にフェーズ文字列が追加されたら、ここで直接使うよう差し替える。)
 */
@Composable
fun ImageGenProgressCard(
    step: Int,
    totalSteps: Int
) {
    val safeTotal = totalSteps.coerceAtLeast(1)
    val safeStep = step.coerceIn(0, safeTotal)
    val fraction = (safeStep.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()

    val phase = when {
        safeStep <= 0 -> ImageGenPhase.ANALYZING
        safeStep < safeTotal -> ImageGenPhase.DIFFUSING
        else -> ImageGenPhase.FINISHING
    }

    // シマー: バー内をゆっくり流れる光のアニメーション。
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "imagegen_shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "imagegen_shimmer_offset"
    )

    val cardBg = colorResource(id = R.color.image_gen_progress_bg)
    val accent = colorResource(id = R.color.image_gen_progress_accent)
    val accentLight = colorResource(id = R.color.image_gen_progress_accent_light)
    val trackColor = colorResource(id = R.color.image_gen_progress_track)
    val titleColor = colorResource(id = R.color.image_gen_progress_title)
    val dimText = colorResource(id = R.color.image_gen_progress_dim_text)
    val faintText = colorResource(id = R.color.image_gen_progress_faint_text)
    val previewBg = colorResource(id = R.color.image_gen_progress_preview_bg)
    val previewBorder = colorResource(id = R.color.image_gen_progress_preview_border)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- ヘッダー: タイトル + % ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("◈", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.image_gen_progress_title),
                        color = titleColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "$percent%",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- シマーグラデーションバー ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(trackColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                        .height(6.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(accent, accentLight, accent),
                                startX = -400f + shimmerOffset * 800f,
                                endX = 400f + shimmerOffset * 800f
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp)
                        )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- 3 ステップ チェックリスト ---
            ImageGenStepRow(
                label = stringResource(id = R.string.image_gen_progress_step_analyze),
                status = if (phase == ImageGenPhase.ANALYZING) StepRowStatus.ACTIVE else StepRowStatus.DONE,
                accent = accent,
                doneColor = dimText,
                pendingColor = faintText,
                trackColor = trackColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            ImageGenStepRow(
                label = stringResource(
                    id = R.string.image_gen_progress_step_diffuse,
                    safeStep.coerceAtMost(safeTotal),
                    safeTotal
                ),
                status = when {
                    phase == ImageGenPhase.DIFFUSING -> StepRowStatus.ACTIVE
                    phase == ImageGenPhase.FINISHING -> StepRowStatus.DONE
                    else -> StepRowStatus.PENDING
                },
                accent = accent,
                doneColor = dimText,
                pendingColor = faintText,
                trackColor = trackColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            ImageGenStepRow(
                label = stringResource(id = R.string.image_gen_progress_step_finish),
                status = if (phase == ImageGenPhase.FINISHING) StepRowStatus.ACTIVE else StepRowStatus.PENDING,
                accent = accent,
                doneColor = dimText,
                pendingColor = faintText,
                trackColor = trackColor
            )
        }
    }
}

private enum class ImageGenPhase { ANALYZING, DIFFUSING, FINISHING }
private enum class StepRowStatus { DONE, ACTIVE, PENDING }

@Composable
private fun ImageGenStepRow(
    label: String,
    status: StepRowStatus,
    accent: Color,
    doneColor: Color,
    pendingColor: Color,
    trackColor: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when (status) {
                StepRowStatus.DONE -> {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(accent, shape = androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                StepRowStatus.ACTIVE -> {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                }
                StepRowStatus.PENDING -> {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .border(2.dp, trackColor, shape = androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = when (status) {
                StepRowStatus.ACTIVE -> doneColor.copy(alpha = 1f)
                StepRowStatus.DONE -> doneColor
                StepRowStatus.PENDING -> pendingColor
            },
            fontWeight = if (status == StepRowStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
    }
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

    // 画像生成の実行中は、汎用のシンプルなバーではなく専用のリッチな進捗カードを表示する。
    // (モック `nezumi-imagegen-progress-v1` 準拠: % 表示 + ステップチェックリスト + プレビュー枠)
    // 完了/失敗 (ToolCallState.Result) になった後は、従来通り下の汎用カードで結果を表示する。
    if (state is ToolCallState.Executing && state.toolName.equals("generate_image", ignoreCase = true)) {
        val (step, total) = imageGenProgress ?: Pair(0, 1)
        ImageGenProgressCard(step = step, totalSteps = total)
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
            // ここに到達する時点で generate_image の Executing は上のガードで処理済みのため、
            // 残るのは Result（成功/失敗）・Responding・他ツールの Executing のみ。
            // いずれも段階的な進捗値を持たないため、不確定（indeterminate）バーで表示する。
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
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
    // ドキュメント (PDF/Word/Excel等) の Markdown 変換が進行中かどうか。
    //   動画抽出 (isExtractingVideo) と同じく、完了まで送信はブロックされる。
    isConvertingDocument: Boolean = false,
    convertingDocumentName: String? = null,
    onOpenViewer: (selectedKey: String) -> Unit = {},
    textFiles: List<com.nezumi_ai.data.media.TextFileAttachmentEncoding.TextFileEntry> = emptyList(),
    onRemoveTextFile: (index: Int) -> Unit = {},
    onOpenTextFile: (com.nezumi_ai.data.media.TextFileAttachmentEncoding.TextFileEntry) -> Unit = {}
) {
    val hasVideo = !videoUri.isNullOrBlank()
    if (!hasImage && !hasAudio && !hasVideo && !isExtractingVideo && !isConvertingDocument && textFiles.isEmpty()) {
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
                if (isConvertingDocument) {
                    add("ドキュメントを変換中…" + (convertingDocumentName?.let { " ($it)" } ?: ""))
                }
                if (hasVideo) add("動画1本")
                // 動画には音声トラックが含まれているので、hasVideo のときは
                // 「音声あり」を別途表示しない (チップ側と揃えて一元化)
                if (hasAudio && !hasVideo) add("音声あり")
                if (imagesToShow.isNotEmpty()) add("${imagesToShow.size}/5 画像")
                if (textFiles.isNotEmpty()) add("テキスト${textFiles.size}件")
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

        if (hasVideo || hasAudio || imagesToShow.isNotEmpty() || isExtractingVideo || isConvertingDocument || textFiles.isNotEmpty()) {
            
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
                // 0.5) ドキュメント変換中スピナー (変換が終わるまで添付チップの代わりに表示)
                if (isConvertingDocument) {
                    item(key = "doc_converting") {
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
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.tertiary
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

                // 1.5) テキスト添付チップ。タップでテキストビュワーを開く。
                //    画像/動画/音声と同じストリップに並べて、送信前に何が添付されているか
                //    一覧できるようにする。
                items(
                    count = textFiles.size,
                    key = { index -> "txt:" + (textFiles.getOrNull(index)?.uri ?: index.toString()) }
                ) { index ->
                    val entry = textFiles.getOrNull(index) ?: return@items
                    Box(
                        modifier = Modifier
                            .width(90.dp)
                            .height(90.dp)
                            .background(
                                MaterialTheme.colorScheme.tertiaryContainer,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.tertiary,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            )
                            .clickable { onOpenTextFile(entry) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = "T",
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onRemoveTextFile(index) },
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
                                contentDescription = "Clear text file",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(14.dp)
                            )
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
