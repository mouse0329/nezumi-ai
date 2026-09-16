package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nezumi_ai.R
import com.nezumi_ai.presentation.ui.widget.ClipboardAwareEditText

/**
 * 旧 fragment_chat.xml のヘッダー部 (chat_header) の Compose 置き換え。
 * シークレットモード時は headerColor が incognito_surface に差し替わる。
 */
@Composable
fun ChatHeader(
    title: String,
    titleContentDescription: String?,
    modelName: String,
    headerColor: Color,
    onBackClick: () -> Unit,
    onTitleClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(headerColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // bg_back_button.xml (primary_light + border の円) 相当
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(36.dp)
                .background(colorResource(R.color.primary_light), CircleShape)
                .border(1.dp, colorResource(R.color.border), CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = stringResource(R.string.open_sidebar),
                tint = colorResource(R.color.text_primary),
                modifier = Modifier.size(20.dp)
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_nezumi_ai),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp)
                .size(28.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .clickable(onClick = onTitleClick)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(R.color.text_primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    if (titleContentDescription != null) {
                        contentDescription = titleContentDescription
                    }
                }
            )
            Text(
                text = modelName,
                fontSize = 13.sp,
                color = colorResource(R.color.text_secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Box(modifier = Modifier.padding(start = 8.dp)) {
            actions()
        }
    }
}

/**
 * 旧 fragment_chat.xml の入力バー (input_bar / input_bar_card) の Compose 置き換え。
 * テキスト入力のみ IME の画像コミット対応のため ClipboardAwareEditText を AndroidView でホストする。
 */
@Composable
fun ChatInputBar(
    barColor: Color,
    isGenerating: Boolean,
    sendEnabled: Boolean,
    inputEnabled: Boolean,
    inputHint: String,
    mediaMenuEnabled: Boolean,
    micVisible: Boolean,
    micEnabled: Boolean,
    isRecording: Boolean,
    recordingElapsedText: String = "0:00",
    recordingWaveHeights: List<Int> = emptyList(),
    onSendClick: () -> Unit,
    onMediaMenuClick: () -> Unit,
    onMicClick: () -> Unit,
    onRecordCancel: () -> Unit,
    onClipboardImagePaste: () -> Unit,
    onInputViewReady: (ClipboardAwareEditText) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(barColor)
            .padding(10.dp)
    ) {
        // bg_input_bar_card.xml (surface_card + border + 20dp 角) 相当
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(R.color.surface_card), RoundedCornerShape(20.dp))
                .border(1.dp, colorResource(R.color.border), RoundedCornerShape(20.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // bg_plus_btn_circle.xml (nezumi_primary_container の円) 相当
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    onMediaMenuClick()
                },
                enabled = mediaMenuEnabled,
                modifier = Modifier
                    .size(36.dp)
                    .background(colorResource(R.color.nezumi_primary_container), CircleShape)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.add_media_menu),
                    tint = colorResource(R.color.nezumi_primary),
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp, end = 4.dp)
            ) {
                if (isRecording) {
                    // 録音中は EditText を隠して録音バーに差し替える
                    InlineRecordingBar(
                        elapsedText = recordingElapsedText,
                        waveHeights = recordingWaveHeights,
                        onCancel = onRecordCancel
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            ClipboardAwareEditText(ctx).apply {
                                background = null
                                hint = inputHint
                                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                                maxLines = 6
                                setPadding(6, 6, 6, 6)
                                setTextColor(ctx.getColor(R.color.text_primary))
                                setHintTextColor(ctx.getColor(R.color.text_secondary))
                                textSize = 15f
                                isVerticalScrollBarEnabled = true
                                this.onClipboardImagePaste = onClipboardImagePaste
                                onInputViewReady(this)
                            }
                        },
                        update = { view ->
                            view.isEnabled = inputEnabled
                            if (view.hint?.toString() != inputHint) view.hint = inputHint
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                    )
                }
            }

            if (micVisible) {
                // bg_mic_btn_circle.xml: 選択中 (録音中) は recording_red の円、非選択は透明
                IconButton(
                    onClick = onMicClick,
                    enabled = micEnabled,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isRecording) colorResource(R.color.recording_red) else Color.Transparent,
                            CircleShape
                        )
                ) {
                    Icon(
                        painter = painterResource(
                            if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic
                        ),
                        contentDescription = stringResource(
                            if (isRecording) R.string.audio_stop_and_send else R.string.audio_input
                        ),
                        // 録音中の停止アイコンは白、平常時はテキスト副色
                        tint = if (isRecording) Color.White else colorResource(R.color.text_secondary),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // bg_send_btn_circle.xml (nezumi_primary の円) 相当
            IconButton(
                onClick = onSendClick,
                enabled = sendEnabled,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(36.dp)
                    .background(colorResource(R.color.nezumi_primary), CircleShape)
            ) {
                Icon(
                    painter = painterResource(
                        if (isGenerating) R.drawable.ic_stop else R.drawable.ic_send
                    ),
                    contentDescription = stringResource(R.string.send_icon),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 旧 fragment_chat.xml のインライン録音バー (inline_record_bar) の Compose 置き換え。
 * 「削除」・録音ドット・経過時間・波形 8 本を表示する。
 */
@Composable
fun InlineRecordingBar(
    elapsedText: String,
    waveHeights: List<Int>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(colorResource(R.color.surface_card))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "削除",
            color = colorResource(R.color.text_secondary),
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(4.dp)
        )
        // bg_record_dot.xml (recording_red の円) 相当
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(9.dp)
                .background(colorResource(R.color.recording_red), CircleShape)
        )
        Text(
            text = elapsedText,
            color = colorResource(R.color.text_primary),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // bg_wave_bar.xml (nezumi_primary + 2dp 角) 相当
            waveHeights.forEachIndexed { index, heightDp ->
                Box(
                    modifier = Modifier
                        .padding(end = if (index == waveHeights.lastIndex) 0.dp else 3.dp)
                        .width(3.dp)
                        .height(heightDp.dp)
                        .background(
                            colorResource(R.color.nezumi_primary),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
    }
}
