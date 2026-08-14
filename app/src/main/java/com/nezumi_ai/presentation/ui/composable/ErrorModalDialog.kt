package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 共通エラーモーダルダイアログ（Compose）
 * 設定画面のデバッグボタンから表示可能なスタイルで統一
 */
@Composable
fun ErrorModalDialog(
    title: String,
    message: String,
    detail: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ErrorModalDialogContent(
            title = title,
            message = message,
            detail = detail,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }
}

@Composable
fun ErrorModalDialogContent(
    title: String,
    message: String,
    detail: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null
) {
    val overlayColor = if (isSystemInDarkTheme()) {
        Color.Black.copy(alpha = 0.2f)
    } else {
        Color.Black.copy(alpha = 0.2f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(overlayColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                }
                .padding(horizontal = 32.dp, vertical = 40.dp)
        ) {
            // エラーコード等の詳細が長すぎる場合でも下部のボタン (コピー/閉じる) が
            // 画面外に押し出されないよう、本文エリアに最大高を設けて内部スクロールにする。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 1.1.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 26.sp
                )
                detail?.let {
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(18.dp)
                    ) {
                        Text(
                            text = it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        val fullText = buildString {
                            append(title)
                            append("\n\n")
                            append(message)
                            detail?.let {
                                append("\n\n")
                                append(it)
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("error_message", fullText))
                        Toast.makeText(context, "エラーメッセージをコピーしました", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .height(44.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        ),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text(
 text = "コピー",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "閉じる",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (onConfirm != null) {
                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "再試行する",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
