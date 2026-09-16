package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R

/**
 * 旧 sheet_attachment_options.xml の Compose 置き換え。
 * 「画像」「カメラ」「ファイル」の 3 タイルと「キャンセル」を縦に並べる。
 * BottomSheetDialog のコンテンツ、または Compose の ModalBottomSheet から使う。
 */
@Composable
fun AttachmentOptionsSheet(
    imageEnabled: Boolean,
    onImageClick: () -> Unit,
    onCameraClick: () -> Unit,
    onFileClick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(R.color.surface_card))
            .padding(16.dp)
    ) {
        // 上部のドラッグハンドル相当 (36dp x 4dp のバー)
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
                .width(36.dp)
                .height(4.dp)
                .background(colorResource(R.color.border), RoundedCornerShape(2.dp))
        )

        Text(
            text = stringResource(R.string.attachment_options_title),
            color = colorResource(R.color.text_secondary),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            AttachmentOptionTile(
                iconRes = R.drawable.ic_image,
                label = stringResource(R.string.attachment_options_image),
                iconBackgroundColor = colorResource(R.color.nezumi_primary_container),
                iconTint = colorResource(R.color.nezumi_primary),
                enabled = imageEnabled,
                onClick = onImageClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            AttachmentOptionTile(
                iconRes = R.drawable.ic_camera,
                label = stringResource(R.string.attachment_options_camera),
                iconBackgroundColor = Color(0xFFF3E9DE),
                iconTint = Color(0xFFA5652F),
                enabled = imageEnabled,
                onClick = onCameraClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 3.dp, end = 3.dp)
            )
            AttachmentOptionTile(
                iconRes = R.drawable.ic_description,
                label = stringResource(R.string.attachment_options_file),
                iconBackgroundColor = Color(0xFFE9E6F5),
                iconTint = Color(0xFF5B4E9C),
                enabled = true,
                onClick = onFileClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
            )
        }

        // bg_option_tile.xml (bg_chat + border + 16dp 角) 相当
        Text(
            text = stringResource(R.string.attachment_options_cancel),
            color = colorResource(R.color.text_secondary),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(colorResource(R.color.bg_chat), RoundedCornerShape(16.dp))
                .clickable(onClick = onCancel)
                .padding(vertical = 14.dp)
        )
    }
}

@Composable
private fun AttachmentOptionTile(
    iconRes: Int,
    label: String,
    iconBackgroundColor: Color,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 無効時は半透明にしてグレイアウト (旧 applyAttachmentTileEnabled と同等)
    val contentAlpha = if (enabled) 1.0f else 0.38f
    Column(
        modifier = modifier
            .alpha(contentAlpha)
            .background(colorResource(R.color.bg_chat), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(iconBackgroundColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            color = colorResource(
                if (enabled) R.color.text_primary else R.color.text_secondary
            ),
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
