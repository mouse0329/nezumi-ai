package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.presentation.ui.composable.MediaPreviewBar
import com.nezumi_ai.presentation.ui.composable.ToolCallProgressBar
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.presentation.ui.widget.ClipboardAwareEditText

/**
 * 旧 fragment_chat.xml のルート構成 (FrameLayout + LinearLayout 縦積み) の Compose 置き換え。
 * メッセージ一覧のみ既存 RecyclerView を AndroidView でホストし、
 * ヘッダー / メディアプレビュー / 入力バー / 各種オーバーレイは全て Compose で構築する。
 */
@Composable
fun ChatScreen(
    // ヘッダー
    headerColor: Color,
    chatTitle: String,
    chatTitleContentDescription: String?,
    modelName: String,
    onBackClick: () -> Unit,
    onChatTitleClick: () -> Unit,
    headerActions: @Composable () -> Unit,
    // コンテキストメーター / ツール進捗 (旧 ComposeView 差し込み部)
    contextMeter: @Composable () -> Unit,
    // メッセージ一覧
    messagesRecyclerFactory: (android.content.Context) -> RecyclerView,
    onMessagesRecyclerReady: (RecyclerView) -> Unit,
    emptyState: @Composable () -> Unit,
    showEmptyState: Boolean,
    showMessagesLoading: Boolean,
    responseTyping: @Composable () -> Unit,
    toolCallProgress: @Composable () -> Unit,
    scrollToBottomButton: @Composable () -> Unit,
    // メディアプレビュー (入力欄直上)
    mediaPreviewBarColor: Color,
    hasImage: Boolean,
    hasAudio: Boolean,
    imageUris: List<String>,
    onClearImage: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    audioUri: String?,
    onClearAudio: () -> Unit,
    textFiles: List<TextFileAttachmentEncoding.TextFileEntry>,
    onRemoveTextFile: (Int) -> Unit,
    onOpenTextFile: (TextFileAttachmentEncoding.TextFileEntry) -> Unit,
    videoUri: String?,
    onClearVideo: () -> Unit,
    isExtractingVideo: Boolean,
    isConvertingDocument: Boolean,
    convertingDocumentName: String?,
    onOpenViewer: (String) -> Unit,
    // 入力バー
    inputBarColor: Color,
    isGenerating: Boolean,
    sendEnabled: Boolean,
    inputEnabled: Boolean,
    inputHint: String,
    mediaMenuEnabled: Boolean,
    micVisible: Boolean,
    micEnabled: Boolean,
    isRecording: Boolean,
    recordingElapsedText: String,
    recordingWaveHeights: List<Int>,
    onSendClick: () -> Unit,
    onMediaMenuClick: () -> Unit,
    onMicClick: () -> Unit,
    onRecordCancel: () -> Unit,
    onClipboardImagePaste: () -> Unit,
    onInputViewReady: (ClipboardAwareEditText) -> Unit,
    // 全画面ローディングオーバーレイ
    modelLoadingOverlay: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatHeader(
                title = chatTitle,
                titleContentDescription = chatTitleContentDescription,
                modelName = modelName,
                headerColor = headerColor,
                onBackClick = onBackClick,
                onTitleClick = onChatTitleClick,
                actions = headerActions
            )
            contextMeter()

            // メッセージ一覧領域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // AndroidView の RecyclerView は Compose のレイアウト境界を越えて
                    // 描画できるため、ヘッダーや入力欄の背後へメッセージがはみ出さない
                    // ように一覧領域で明示的に切り抜く。
                    .clipToBounds()
            ) {
                AndroidView(
                    factory = { ctx ->
                        messagesRecyclerFactory(ctx).also { rv ->
                            onMessagesRecyclerReady(rv)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
                if (showEmptyState) {
                    Box(Modifier.fillMaxSize()) { emptyState() }
                }
                if (showMessagesLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // タイピング + ツール進捗 + 「下へ」ボタン (下部に縦積み)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    responseTyping()
                    toolCallProgress()
                    scrollToBottomButton()
                }
            }

            // メディアプレビューバー (入力欄の直上)
            Box(modifier = Modifier.background(mediaPreviewBarColor)) {
                MediaPreviewBar(
                    hasImage = hasImage,
                    hasAudio = hasAudio,
                    imageUris = imageUris,
                    onClearImage = onClearImage,
                    onRemoveImage = onRemoveImage,
                    audioUri = audioUri,
                    onClearAudio = onClearAudio,
                    textFiles = textFiles,
                    onRemoveTextFile = onRemoveTextFile,
                    onOpenTextFile = onOpenTextFile,
                    videoUri = videoUri,
                    onClearVideo = onClearVideo,
                    isExtractingVideo = isExtractingVideo,
                    isConvertingDocument = isConvertingDocument,
                    convertingDocumentName = convertingDocumentName,
                    onOpenViewer = onOpenViewer
                )
            }

            ChatInputBar(
                barColor = inputBarColor,
                isGenerating = isGenerating,
                sendEnabled = sendEnabled,
                inputEnabled = inputEnabled,
                inputHint = inputHint,
                mediaMenuEnabled = mediaMenuEnabled,
                micVisible = micVisible,
                micEnabled = micEnabled,
                isRecording = isRecording,
                recordingElapsedText = recordingElapsedText,
                recordingWaveHeights = recordingWaveHeights,
                onSendClick = onSendClick,
                onMediaMenuClick = onMediaMenuClick,
                onMicClick = onMicClick,
                onRecordCancel = onRecordCancel,
                onClipboardImagePaste = onClipboardImagePaste,
                onInputViewReady = onInputViewReady
            )
        }

        // モデルロード中の全画面オーバーレイ (タップを消費して下層へ透過させない)
        modelLoadingOverlay()
    }
}
