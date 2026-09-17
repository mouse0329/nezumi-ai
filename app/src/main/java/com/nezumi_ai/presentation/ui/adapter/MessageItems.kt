package com.nezumi_ai.presentation.ui.adapter

import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.nezumi_ai.R
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.data.inference.stripTxtFileBlocks
import com.nezumi_ai.data.inference.stripVideoBlocks
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.media.VideoAttachmentEncoding
import com.nezumi_ai.presentation.ui.composable.InlineToolCallMessageBody
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import com.nezumi_ai.presentation.ui.component.MediaViewerDialog
import com.nezumi_ai.presentation.ui.component.TextFileViewerDialog

/**
 * 旧 item_message_user.xml / item_message_ai.xml の Compose 置き換え。
 * メッセージ行の見た目 (バブル背景・色・余白・幅上限) は XML と同等に再現する。
 */

// ─────────────────────────────────────────────────────────────
// 共通: タイムスタンプ書式
// ─────────────────────────────────────────────────────────────
internal fun formatMessageTime(timestamp: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))

// ─────────────────────────────────────────────────────────────
// ユーザーメッセージ行 (item_message_user.xml 相当)
// ─────────────────────────────────────────────────────────────
@Composable
fun UserMessageItem(
    message: MessageEntity,
    isGenerating: Boolean,
    onEdit: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        MessageMediaPreview(
            message = message,
            bubbleColor = colorResource(R.color.primary)
        )

        // <txtfile> / <video> ブロックはモデル向け埋め込みなので吹き出しには出さない
        val bubbleText = message.content.stripTxtFileBlocks().stripVideoBlocks()
        SelectableBubbleText(
            text = bubbleText,
            backgroundColor = colorResource(R.color.primary),
            textColor = colorResource(R.color.white),
            borderColor = null,
            modifier = Modifier.widthIn(max = 280.dp)
        )

        Text(
            text = formatMessageTime(message.timestamp),
            fontSize = 11.sp,
            color = colorResource(R.color.text_secondary),
            modifier = Modifier.padding(top = 3.dp, end = 4.dp)
        )

        // コピー・編集は常時表示 (#Actions-Always-Visible)。編集は生成中のみ隠す。
        Row(modifier = Modifier.padding(top = 2.dp)) {
            IconButton(
                onClick = { copyTextToClipboard(context, message.content) },
                modifier = Modifier.requiredSize(32.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.content_copy_24),
                    contentDescription = stringResource(R.string.copy_all),
                    tint = colorResource(R.color.text_secondary),
                    modifier = Modifier.size(20.dp)
                )
            }
            if (!isGenerating) {
                IconButton(onClick = { onEdit(message) }, modifier = Modifier.requiredSize(32.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit_prompt),
                        tint = colorResource(R.color.text_secondary),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// AI メッセージ行 (item_message_ai.xml 相当)
// ─────────────────────────────────────────────────────────────
@Composable
fun AiMessageItem(
    message: MessageEntity,
    isLastAiMessage: Boolean,
    isGenerating: Boolean,
    isSpeaking: Boolean,
    speakEnabled: Boolean,
    showTps: Boolean,
    showTtft: Boolean,
    variantTotal: Int,
    variantIndex: Int,
    streamingImagePlaceholder: Boolean,
    thinkingExpanded: Boolean,
    visibleContent: String,
    toolResults: List<ToolResultCard>,
    onSaveDocument: ((String, String, String, (Boolean) -> Unit) -> Unit)?,
    onThinkingToggle: () -> Unit,
    onRegenerate: (MessageEntity) -> Unit,
    onVariantSelect: (Int) -> Unit,
    onCopy: (MessageEntity) -> Unit,
    onSpeak: (MessageEntity, String) -> Unit,
    onBodySizeChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 応答バリアントの横スワイプ検出 (旧 aiMessageRoot の OnTouchListener 相当)。
    //   右スワイプ = 前の応答、左スワイプ = 次の応答。
    //   縦スクロールとの競合は Compose のタッチスロップ判定に委ねる。
    val viewConfig = LocalViewConfiguration.current
    val swipeEnabled = variantTotal > 1 && !message.isStreaming && !isGenerating
    val swipeModifier = Modifier.pointerInput(message.id, variantIndex, swipeEnabled) {
        if (!swipeEnabled) return@pointerInput
        val minSwipe = viewConfig.touchSlop * 4
        var totalDx = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDx = 0f },
            onDragEnd = {
                if (kotlin.math.abs(totalDx) >= minSwipe) {
                    val next = (variantIndex + if (totalDx > 0) -1 else 1)
                        .coerceIn(0, variantTotal - 1)
                    if (next != variantIndex) onVariantSelect(next)
                }
                totalDx = 0f
            },
            onDragCancel = { totalDx = 0f },
            onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(swipeModifier)
            .padding(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // メディアプレビュー (生成中の画像プレースホルダーは旧 aiImagePreview 相当)
        if (streamingImagePlaceholder) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(200.dp)
                    .background(colorResource(R.color.surface_card), RoundedCornerShape(18.dp))
                    .border(1.dp, colorResource(R.color.border), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = stringResource(R.string.message_image),
                    modifier = Modifier
                        .size(200.dp)
                        .padding(48.dp),
                    alpha = 0.3f,
                    colorFilter = ColorFilter.tint(colorResource(R.color.text_secondary))
                )
            }
        } else {
            MessageMediaPreview(
                message = message,
                bubbleColor = colorResource(R.color.surface_card)
            )
        }

        // Thinking ブロック (280dp 固定・bg_message_ai 相当)
        //   - 生成中: 強制展開・トグル行なし
        //   - 生成後: トグル行を表示し、thinkingExpanded で開閉
        val thinking = message.thinkingContent?.stripGemmaTokens()
        if (!thinking.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .width(280.dp)
                    .background(colorResource(R.color.surface_card), RoundedCornerShape(18.dp))
                    .border(1.dp, colorResource(R.color.border), RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (message.isStreaming) {
                    MessageThinkingBody(thinking)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onThinkingToggle)
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (thinkingExpanded) R.string.gemma_hide_thinking
                                else R.string.gemma_show_thinking
                            ),
                            color = colorResource(R.color.text_secondary),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (thinkingExpanded) "▲" else "▼",
                            color = colorResource(R.color.text_secondary),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (thinkingExpanded) {
                        val thinkingRuleColor = colorResource(R.color.text_secondary)
                            .copy(alpha = 0.35f)
                        // LazyColumn is based on SubcomposeLayout and cannot answer intrinsic
                        // size queries. Draw the leading rule in the content container instead
                        // of using Row.height(IntrinsicSize.Min) + fillMaxHeight().
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .drawBehind {
                                    drawRect(
                                        color = thinkingRuleColor,
                                        size = size.copy(width = 2.dp.toPx())
                                    )
                                }
                                .padding(start = 10.dp)
                        ) {
                            MessageThinkingBody(thinking)
                        }
                    }
                }
            }
        }

        // 本文。高さ変化は自動スクロール追従のため呼び出し側へ通知する
        // (旧 aiMessageMarkdownCompose / aiMessageText の OnLayoutChangeListener 相当)。
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .trackHeightChanges(onBodySizeChanged)
        ) {
            if (message.isStreaming && visibleContent.isBlank()) {
                SelectableBubbleText(
                    text = stringResource(
                        if (thinking.isNullOrBlank()) R.string.response_generating
                        else R.string.gemma_answer_generating_hint
                    ),
                    backgroundColor = colorResource(R.color.surface_card),
                    textColor = colorResource(R.color.text_primary),
                    borderColor = colorResource(R.color.border)
                )
            } else {
                // インライン tool-call カード対応の本文描画 (旧 renderInlineBody)。
                InlineToolCallMessageBody(
                    content = visibleContent,
                    toolResults = toolResults,
                    isStreaming = message.isStreaming,
                    onSaveDocument = onSaveDocument
                )
            }
        }

        // タイムスタンプ + t/s・TTFT (詳細トグルは廃止済み。有効な指標のみ常時表示)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp, start = 4.dp)
        ) {
            Text(
                text = formatMessageTime(message.timestamp),
                fontSize = 11.sp,
                color = colorResource(R.color.text_secondary)
            )
            val perfParts = mutableListOf<String>()
            if (!message.isStreaming) {
                if (showTps) {
                    message.generationTps?.takeIf { it > 0f }
                        ?.let { perfParts += String.format("%.1f t/s", it) }
                    message.generationTimeMs?.takeIf { it > 0L }
                        ?.let { perfParts += formatGenerationTime(context, it) }
                }
                if (showTtft) {
                    message.ttftMs?.takeIf { it > 0L }?.let { perfParts += formatTtft(it) }
                }
            }
            if (perfParts.isNotEmpty()) {
                Text(
                    text = perfParts.joinToString("  ·  "),
                    fontSize = 11.sp,
                    color = colorResource(R.color.text_secondary),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(colorResource(R.color.surface_card), RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            colorResource(R.color.border),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // アクション行: copy / speak / variant-nav / regenerate (常時表示)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            IconButton(onClick = { onCopy(message) }, modifier = Modifier.requiredSize(32.dp)) {
                Icon(
                    painter = painterResource(R.drawable.content_copy_24),
                    contentDescription = stringResource(R.string.copy_all),
                    tint = colorResource(R.color.text_secondary),
                    modifier = Modifier.size(20.dp)
                )
            }
            if (speakEnabled && !isSpeaking) {
                IconButton(
                    onClick = {
                        val text = message.content.stripGemmaTokens().trim()
                        onSpeak(message, text)
                    },
                    modifier = Modifier.requiredSize(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_to_speak_24),
                        contentDescription = "読み上げ",
                        tint = colorResource(R.color.text_secondary),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (speakEnabled && isSpeaking) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(28.dp),
                    strokeWidth = 2.dp
                )
            }
            if (variantTotal > 1 && !message.isStreaming && !isGenerating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    val canPrev = variantIndex > 0
                    val canNext = variantIndex < variantTotal - 1
                    IconButton(
                        onClick = { if (canPrev) onVariantSelect(variantIndex - 1) },
                        enabled = canPrev,
                        modifier = Modifier.requiredSize(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left_24),
                            contentDescription = "前の応答",
                            tint = colorResource(R.color.text_secondary)
                                .copy(alpha = if (canPrev) 1.0f else 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "${variantIndex + 1}/$variantTotal",
                        fontSize = 11.sp,
                        color = colorResource(R.color.text_secondary),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 36.dp)
                    )
                    IconButton(
                        onClick = { if (canNext) onVariantSelect(variantIndex + 1) },
                        enabled = canNext,
                        modifier = Modifier.requiredSize(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right_24),
                            contentDescription = "次の応答",
                            tint = colorResource(R.color.text_secondary)
                                .copy(alpha = if (canNext) 1.0f else 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            val canRegenerate = isLastAiMessage && !isGenerating && !message.isStreaming
            if (canRegenerate) {
                IconButton(
                    onClick = { onRegenerate(message) },
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .requiredSize(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh_24),
                        contentDescription = "再生成",
                        tint = colorResource(R.color.text_secondary),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Spacer8() {
    Box(Modifier.width(10.dp))
}

/**
 * Thinking ブロック専用の Markdown 描画 (旧 ThinkingMarkdownText)。
 * 自己のカード背景や固定幅は持たず、fillMaxWidth() で親コンテナに合わせる。
 */
@Composable
private fun MessageThinkingBody(content: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            ProvideTextStyle(
                value = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = colorResource(id = R.color.text_secondary),
                    letterSpacing = 0.2.sp
                )
            ) {
                MaterialTheme {
                    MarkdownLatexText(text = content, textSize = 36f)
                }
            }
        }
    }
}

/**
 * 高さの変化だけをコールバックに流す Modifier。
 * 旧 OnLayoutChangeListener (bottom-top の比較) と同等の発火条件。
 */
@Composable
private fun Modifier.trackHeightChanges(onChanged: () -> Unit): Modifier {
    var lastHeight by remember { mutableIntStateOf(-1) }
    return this.onSizeChanged { size ->
        if (size.height != lastHeight) {
            lastHeight = size.height
            onChanged()
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 共通部品
// ─────────────────────────────────────────────────────────────

/**
 * メディアプレビュー領域 (旧 media_container 相当)。
 * 複数画像は横スクロール、単一画像は 200dp カード、音声はプレイヤーカード、
 * 動画はサムネ + ▶ オーバーレイで表現する。タップで統一ビュワーを開く。
 */
@Composable
private fun MessageMediaPreview(
    message: MessageEntity,
    bubbleColor: Color
) {
    val context = LocalContext.current
    val (videoMeta, imageUrisRaw) = VideoAttachmentEncoding.split(message.imageUri)
    val textFiles = TextFileAttachmentEncoding.extract(message.imageUri)
    val imageUris = imageUrisRaw.filter { !TextFileAttachmentEncoding.isMarker(it) }
    val videoUri = videoMeta?.originalVideoUri
    val audioUri = videoMeta?.audioUri ?: message.audioUri
    val hasVideo = !videoUri.isNullOrBlank()
    val hasAudio = !audioUri.isNullOrEmpty()
    val hasImages = imageUris.isNotEmpty()
    val hasTextFiles = textFiles.isNotEmpty()

    if (!hasImages && !hasAudio && !hasVideo && !hasTextFiles) return

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        // 動画由来は「動画1本」としてサムネ1枚だけを描画する
        if (hasVideo) {
            VideoThumbnailCard(
                thumbnailUri = imageUris.firstOrNull(),
                onClick = {
                    MediaViewerDialog.show(
                        context,
                        MediaViewerDialog.MediaBundle(
                            imageUris = imageUris,
                            videoUri = videoUri,
                            audioUri = audioUri,
                            initialIndex = 0,
                            title = context.getString(R.string.viewer_bundle_title_video_audio)
                        )
                    )
                }
            )
        } else if (imageUris.size > 1 || (hasImages && (hasAudio || hasTextFiles)) || (!hasImages && (hasAudio || hasTextFiles) && hasImages)) {
            // 複数画像 or 画像+α: 横スクロールにカードを並べる
            Row(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .height(180.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                imageUris.forEachIndexed { idx, uri ->
                    ImageThumbCard(uri = uri) {
                        MediaViewerDialog.show(
                            context,
                            MediaViewerDialog.MediaBundle(
                                imageUris = imageUris,
                                videoUri = videoUri,
                                audioUri = audioUri,
                                initialIndex = idx
                            )
                        )
                    }
                }
                textFiles.forEach { entry ->
                    TextFileCard(entry = entry) {
                        TextFileViewerDialog.show(context, entry)
                    }
                }
            }
            if (hasAudio) {
                AudioPlayerCard(audioUri = audioUri!!, cardColor = bubbleColor)
            }
        } else if (hasImages) {
            // 単一画像のみ (旧 single_image_container 相当)
            SingleImageCard(
                uri = imageUris.first(),
                onClick = {
                    MediaViewerDialog.show(
                        context,
                        MediaViewerDialog.MediaBundle(
                            imageUris = imageUris,
                            videoUri = videoUri,
                            audioUri = audioUri
                        )
                    )
                }
            )
        } else if (hasAudio) {
            AudioPlayerCard(audioUri = audioUri!!, cardColor = bubbleColor)
            if (hasTextFiles) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .widthIn(max = 400.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    textFiles.forEach { entry ->
                        TextFileCard(entry = entry) { TextFileViewerDialog.show(context, entry) }
                    }
                }
            }
        } else if (hasTextFiles) {
            Row(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                textFiles.forEach { entry ->
                    TextFileCard(entry = entry) { TextFileViewerDialog.show(context, entry) }
                }
            }
        }
    }
}

/** 単一画像カード (200dp・角丸・センタークロップ)。 */
@Composable
private fun SingleImageCard(uri: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = MessageMediaStore.toUri(uri) ?: Uri.parse(uri),
            contentDescription = stringResource(R.string.message_image),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** 横スクロール内の画像サムネカード (160dp)。 */
@Composable
private fun ImageThumbCard(uri: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .size(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        AsyncImage(
            model = MessageMediaStore.toUri(uri) ?: Uri.parse(uri),
            contentDescription = stringResource(R.string.message_image),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** 動画サムネカード (250dp・黒背景・中央 ▶)。 */
@Composable
private fun VideoThumbnailCard(thumbnailUri: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .size(250.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (thumbnailUri != null) {
                AsyncImage(
                    model = MessageMediaStore.toUri(thumbnailUri) ?: Uri.parse(thumbnailUri),
                    contentDescription = stringResource(R.string.message_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x5A000000)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "▶", color = Color.White, fontSize = 28.sp)
            }
        }
    }
}

/** テキスト添付カード。 */
@Composable
private fun TextFileCard(
    entry: TextFileAttachmentEncoding.TextFileEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .size(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(R.drawable.ic_description),
                contentDescription = null,
                tint = colorResource(R.color.text_secondary),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
            Text(
                text = entry.name,
                color = colorResource(R.color.text_primary),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
            )
        }
    }
}

/**
 * 音声プレイヤーカード (再生ボタン + シークバー + 時間)。
 * 旧 setupAudioPlayback() と同等の挙動を Compose + MediaPlayer で再現する。
 */
@Composable
private fun AudioPlayerCard(audioUri: String, cardColor: Color) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    val player = remember(audioUri) {
        runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(context, MessageMediaStore.toUri(audioUri) ?: Uri.parse(audioUri))
                prepareAsync()
            }
        }.getOrNull()
    }

    DisposableEffect(player) {
        player?.setOnPreparedListener { mp -> durationMs = mp.duration.toLong().coerceAtLeast(1L) }
        player?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0L
        }
        onDispose { runCatching { player?.release() } }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: 0L
            kotlinx.coroutines.delay(250)
        }
    }

    Column(
        modifier = Modifier
            .width(200.dp)
            .background(cardColor, RoundedCornerShape(12.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Button(
            onClick = {
                runCatching {
                    if (player?.isPlaying == true) {
                        player?.pause()
                        isPlaying = false
                    } else {
                        player?.start()
                        isPlaying = true
                    }
                }
            },
            modifier = Modifier
                .padding(top = 12.dp)
                .size(56.dp),
            shape = CircleShape
        ) {
            Text(
                text = if (isPlaying) "■" else "▶",
                fontSize = 24.sp,
                color = colorResource(R.color.white)
            )
        }
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
            onValueChange = { ratio ->
                val target = (ratio * durationMs).toLong()
                runCatching { player?.seekTo(target.toInt()) }
                positionMs = target
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )
        Text(
            text = "%d:%02d / %d:%02d".format(
                (positionMs / 1000) / 60, (positionMs / 1000) % 60,
                (durationMs / 1000) / 60, (durationMs / 1000) % 60
            ),
            color = colorResource(R.color.white),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

/**
 * 選択可能テキストのバブル。
 * リンクタップ (LinkMovementMethod) と文字列選択の両方が必要なため
 * この1箇所のみ既存の TextView を AndroidView でホストする。
 * (SelectionContainer では URL リンクの個別タップが再現できないため)
 */
@Composable
fun SelectableBubbleText(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    borderColor: Color?,
    modifier: Modifier = Modifier
) {
    val bgArgb = backgroundColor.toArgb()
    val fgArgb = textColor.toArgb()
    val borderArgb = borderColor?.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                textSize = 14f
                val radius = 18 * resources.displayMetrics.density
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgArgb)
                    cornerRadius = radius
                    if (borderArgb != null) {
                        setStroke((1 * resources.displayMetrics.density).toInt(), borderArgb)
                    }
                }
                background = drawable
                val p = (11 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
        },
        update = { view ->
            view.text = text
            view.setTextColor(fgArgb)
        }
    )
}

// ─────────────────────────────────────────────────────────────
// 書式ヘルパー (旧 MessageAdapter と同一の表記)
// ─────────────────────────────────────────────────────────────
private fun formatGenerationTime(context: android.content.Context, ms: Long): String {
    return if (ms < 60_000L) {
        context.getString(R.string.perf_generation_time_short, ms / 1000f)
    } else {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        context.getString(R.string.perf_generation_time_long, minutes.toInt(), seconds.toInt())
    }
}

private fun formatTtft(ms: Long): String =
    if (ms < 1000L) "TTFT %dms".format(ms) else "TTFT %.2fs".format(ms / 1000f)

internal fun copyTextToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
    android.widget.Toast.makeText(
        context,
        context.getString(R.string.copied_to_clipboard),
        android.widget.Toast.LENGTH_SHORT
    ).show()
}
