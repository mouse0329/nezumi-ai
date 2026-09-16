package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nezumi_ai.R

/**
 * 旧 MainActivity.createAndShowAuthOverlay() が View を手組みしていたロック画面の
 * Compose 置き換え。生体認証/PIN解除/シークレットモード終了の導線を提供する。
 */
@Composable
fun AuthLockScreen(
    hasPin: Boolean,
    inIncognito: Boolean,
    onRetry: () -> Unit,
    onPinUnlock: () -> Unit,
    onExitIncognito: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // クリックイベントを消費して背面のアプリ操作をブロックする
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 鍵アイコン (旧実装と同じ Material Symbols のレンダーURL)
        AsyncImage(
            model = "https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/edit_off.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50",
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Text(
            text = stringResource(R.string.secret_mode_waiting),
            fontSize = 20.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 30.dp)
        )
        Text(
            text = stringResource(R.string.secret_mode_biometrics_subtitle),
            fontSize = 14.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp, start = 50.dp, end = 50.dp)
        ) {
            AuthLockButton(
                text = stringResource(R.string.secret_mode_retry),
                backgroundColor = Color(0xFF4A90E2),
                onClick = onRetry
            )
            if (hasPin) {
                AuthLockButton(
                    text = stringResource(R.string.secret_mode_pin_unlock),
                    backgroundColor = Color(0xFF6A5ACD),
                    onClick = onPinUnlock
                )
            }
            if (inIncognito) {
                AuthLockButton(
                    text = stringResource(R.string.secret_mode_exit),
                    backgroundColor = Color(0xFFE24A4A),
                    onClick = onExitIncognito
                )
            }
        }
    }
}

@Composable
private fun AuthLockButton(
    text: String,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 15.dp)
            .height(56.dp)
    ) {
        Text(text = text, fontSize = 18.sp)
    }
}
