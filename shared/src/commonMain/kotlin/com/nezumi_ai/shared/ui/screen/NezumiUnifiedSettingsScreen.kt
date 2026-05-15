package com.nezumi_ai.shared.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nezumi_ai.shared.settings.NezumiInferenceLimits
import com.nezumi_ai.shared.settings.NezumiSettingsFormState
import com.nezumi_ai.shared.settings.NezumiThemeMode
import kotlin.math.abs
import kotlin.math.roundToInt

data class NezumiEngineVersionInfo(
    val liteRtLine: String,
    val llamaCppLine: String,
    val footnote: String = "※実行時には内部 JNI / モデル対応により挙動が変わる場合があります。",
)

private fun oneDecimalStr(x: Float): String {
    val r = (x * 10f).roundToInt()
    val w = r / 10
    val f = abs(r % 10)
    return "$w.$f"
}

private fun twoDecimalStr(x: Float): String {
    val r = (x * 100f).roundToInt()
    val sign = if (r < 0) "-" else ""
    val ar = abs(r)
    val w = ar / 100
    val f = ar % 100
    val frac = if (f < 10) "0$f" else "$f"
    return "$sign$w.$frac"
}

/**
 * Android 設定画面と同一構成の共有設定 UI。
 * 永続化・ナビゲーションはコールバックと [headerSlot] / フッターでプラットフォームに委譲。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NezumiUnifiedSettingsScreen(
    state: NezumiSettingsFormState,
    onStateChange: (NezumiSettingsFormState) -> Unit,
    showTopAppBar: Boolean = true,
    showStatusBarPadding: Boolean = false,
    titleText: String = "設定",
    titleLeadingContent: (@Composable () -> Unit)? = null,
    headerSlot: (@Composable ColumnScope.() -> Unit)? = null,
    showAndroidStyleFooter: Boolean = false,
    onOpenSetup: (() -> Unit)? = null,
    onOpenLicense: (() -> Unit)? = null,
    engineVersionInfo: NezumiEngineVersionInfo? = null,
    errorDialogMessage: String? = null,
    onDismissError: () -> Unit = {},
) {
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    val labelMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    var versionDialogVisible by remember { mutableStateOf(false) }

    errorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("設定エラー") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = onDismissError) { Text("OK") }
            },
        )
    }

    if (versionDialogVisible && engineVersionInfo != null) {
        AlertDialog(
            onDismissRequest = { versionDialogVisible = false },
            title = { Text("推論エンジンのバージョン") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(engineVersionInfo.liteRtLine)
                    Text(engineVersionInfo.llamaCppLine)
                    Text(
                        engineVersionInfo.footnote,
                        style = MaterialTheme.typography.bodySmall,
                        color = labelMuted,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { versionDialogVisible = false }) { Text("閉じる") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showTopAppBar) {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    titleLeadingContent?.invoke()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!showTopAppBar && (titleLeadingContent != null || showStatusBarPadding)) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (showStatusBarPadding) Modifier.statusBarsPadding()
                                else Modifier,
                            ),
                    ) {
                        if (titleLeadingContent != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                titleLeadingContent()
                                Text(
                                    text = titleText,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            headerSlot?.let { slot ->
                item { Column { slot() } }
            }
            item {
                GeneralSettingsCard(
                    state = state,
                    onStateChange = onStateChange,
                    cardColors = cardColors,
                    labelMuted = labelMuted,
                    accent = accent,
                    engineVersionInfo = engineVersionInfo,
                    onShowVersion = { versionDialogVisible = true },
                )
            }
            item {
                PersonalizationCard(
                    state = state,
                    onStateChange = onStateChange,
                    cardColors = cardColors,
                    labelMuted = labelMuted,
                )
            }
            item {
                InferenceParamsCard(
                    state = state,
                    onStateChange = onStateChange,
                    cardColors = cardColors,
                    labelMuted = labelMuted,
                    accent = accent,
                )
            }
            item {
                ImageGenSettingsCard(
                    state = state,
                    onStateChange = onStateChange,
                    cardColors = cardColors,
                    labelMuted = labelMuted,
                    accent = accent,
                )
            }
            item {
                ChatHistoryCard(
                    state = state,
                    onStateChange = onStateChange,
                    cardColors = cardColors,
                    labelMuted = labelMuted,
                )
            }
            if (showAndroidStyleFooter && (onOpenSetup != null || onOpenLicense != null)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onOpenSetup?.let { fn ->
                            TextButton(onClick = fn) { Text("セットアップを開く") }
                        }
                        onOpenLicense?.let { fn ->
                            TextButton(onClick = fn) { Text("ライセンスを開く") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsCard(
    state: NezumiSettingsFormState,
    onStateChange: (NezumiSettingsFormState) -> Unit,
    cardColors: CardColors,
    labelMuted: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    engineVersionInfo: NezumiEngineVersionInfo?,
    onShowVersion: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "全般設定",
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "テーマ (現在: ${
                        when (state.themeMode) {
                            NezumiThemeMode.Light -> "ライト"
                            NezumiThemeMode.Dark -> "ダーク"
                            NezumiThemeMode.System -> "システム"
                        }
                    })",
                    color = labelMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = state.themeMode == NezumiThemeMode.System,
                        onClick = { onStateChange(state.copy(themeMode = NezumiThemeMode.System)) },
                        label = { Text("システム") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.themeMode == NezumiThemeMode.Light,
                        onClick = { onStateChange(state.copy(themeMode = NezumiThemeMode.Light)) },
                        label = { Text("ライト") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.themeMode == NezumiThemeMode.Dark,
                        onClick = { onStateChange(state.copy(themeMode = NezumiThemeMode.Dark)) },
                        label = { Text("ダーク") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = labelMuted.copy(alpha = 0.2f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "バックエンド (現在: ${state.backendType})",
                    color = labelMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = state.backendType == "CPU",
                        onClick = { onStateChange(state.copy(backendType = "CPU")) },
                        label = { Text("CPU") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.backendType == "GPU",
                        onClick = { onStateChange(state.copy(backendType = "GPU")) },
                        label = { Text("GPU") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.backendType == "NPU",
                        onClick = { onStateChange(state.copy(backendType = "NPU")) },
                        label = { Text("NPU") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (engineVersionInfo != null) {
                TextButton(onClick = onShowVersion, modifier = Modifier.fillMaxWidth()) {
                    Text("llama.cpp / LiteRT-LM バージョンを確認")
                }
            }
        }
    }
}

@Composable
private fun PersonalizationCard(
    state: NezumiSettingsFormState,
    onStateChange: (NezumiSettingsFormState) -> Unit,
    cardColors: CardColors,
    labelMuted: androidx.compose.ui.graphics.Color,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "個人化設定",
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ユーザー名",
                    color = labelMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.userNameInput,
                    onValueChange = { onStateChange(state.copy(userNameInput = it)) },
                    placeholder = { Text("ユーザー名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "システムプロンプト",
                    color = labelMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.systemPromptInput,
                    onValueChange = { onStateChange(state.copy(systemPromptInput = it)) },
                    placeholder = { Text("AIの振る舞いやルールを入力...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    minLines = 3,
                )
            }
        }
    }
}

@Composable
private fun InferenceParamsCard(
    state: NezumiSettingsFormState,
    onStateChange: (NezumiSettingsFormState) -> Unit,
    cardColors: CardColors,
    labelMuted: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "推論パラメータ",
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.contextWindowInput,
                    onValueChange = { onStateChange(state.copy(contextWindowInput = it)) },
                    label = { Text("コンテキストサイズ") },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.maxTokensInput,
                    onValueChange = { onStateChange(state.copy(maxTokensInput = it)) },
                    label = { Text("最大トークン数") },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    singleLine = true,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "温度 (Temperature)",
                        color = labelMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.temperatureInput,
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = state.temperatureInput.toFloatOrNull() ?: 0.7f,
                    onValueChange = { onStateChange(state.copy(temperatureInput = oneDecimalStr(it))) },
                    valueRange = NezumiInferenceLimits.MIN_TEMPERATURE..NezumiInferenceLimits.MAX_TEMPERATURE,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Top-K",
                        color = labelMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.topkInput,
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = state.topkInput.toIntOrNull()?.toFloat() ?: 40f,
                    onValueChange = { onStateChange(state.copy(topkInput = it.toInt().toString())) },
                    valueRange = NezumiInferenceLimits.MIN_TOP_K.toFloat()..NezumiInferenceLimits.MAX_TOP_K.toFloat(),
                    steps = NezumiInferenceLimits.MAX_TOP_K - NezumiInferenceLimits.MIN_TOP_K - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = labelMuted.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "コンテキスト圧縮 (ベータ版)",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Switch(
                    checked = state.contextCompressionEnabled,
                    onCheckedChange = { onStateChange(state.copy(contextCompressionEnabled = it)) },
                )
            }
            if (state.contextCompressionEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "圧縮しきい値",
                            color = labelMuted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${state.contextCompressionThresholdPercent}%",
                            color = accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Slider(
                        value = state.contextCompressionThresholdPercent.toFloat(),
                        onValueChange = { value ->
                            onStateChange(
                                state.copy(
                                    contextCompressionThresholdPercent = value.roundToInt().coerceIn(
                                        NezumiInferenceLimits.MIN_COMPRESSION_THRESHOLD,
                                        NezumiInferenceLimits.MAX_COMPRESSION_THRESHOLD,
                                    ),
                                ),
                            )
                        },
                        valueRange = NezumiInferenceLimits.MIN_COMPRESSION_THRESHOLD.toFloat()..
                            NezumiInferenceLimits.MAX_COMPRESSION_THRESHOLD.toFloat(),
                        steps = NezumiInferenceLimits.MAX_COMPRESSION_THRESHOLD -
                            NezumiInferenceLimits.MIN_COMPRESSION_THRESHOLD - 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "メモリ使用量がこの割合を超えると自動圧縮",
                        color = labelMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            HorizontalDivider(color = labelMuted.copy(alpha = 0.2f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "高度な Llama.cpp 設定",
                    color = labelMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (expanded) "▼" else "▶",
                    color = labelMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "CPU スレッド数",
                                color = labelMuted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = state.llamaCppThreads.toString(),
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Slider(
                            value = state.llamaCppThreads.toFloat(),
                            onValueChange = {
                                onStateChange(state.copy(llamaCppThreads = it.roundToInt()))
                            },
                            valueRange = NezumiInferenceLimits.MIN_THREADS.toFloat()..state.maxThreads.toFloat(),
                            steps = maxOf(0, state.maxThreads - 2),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "GPU レイヤー数 (Offload)",
                                color = labelMuted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = state.llamaCppGpuLayers.toString(),
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Slider(
                            value = state.llamaCppGpuLayers.toFloat(),
                            onValueChange = {
                                onStateChange(state.copy(llamaCppGpuLayers = it.roundToInt()))
                            },
                            valueRange = 0f..128f,
                            steps = 127,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "バッチサイズ",
                                color = labelMuted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = state.llamaCppBatchSize.toString(),
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Slider(
                            value = state.llamaCppBatchSize.toFloat(),
                            onValueChange = {
                                onStateChange(state.copy(llamaCppBatchSize = it.roundToInt().coerceIn(32, 2048)))
                            },
                            valueRange = 32f..2048f,
                            steps = 2016 / 32 - 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "RoPE周波数基数",
                            color = labelMuted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = oneDecimalStr(state.llamaCppRopeFreqBase),
                            onValueChange = { newValue ->
                                newValue.toFloatOrNull()?.let {
                                    onStateChange(state.copy(llamaCppRopeFreqBase = it))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Text(
                            text = "0 = 自動設定",
                            color = labelMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "RoPE周波数スケール",
                                color = labelMuted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = twoDecimalStr(state.llamaCppRopeFreqScale),
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Slider(
                            value = state.llamaCppRopeFreqScale,
                            onValueChange = { onStateChange(state.copy(llamaCppRopeFreqScale = it)) },
                            valueRange = 0.5f..5.0f,
                            steps = 44,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "1.0 = デフォルト",
                            color = labelMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGenSettingsCard(
    state: NezumiSettingsFormState,
    onStateChange: (NezumiSettingsFormState) -> Unit,
    cardColors: CardColors,
    labelMuted: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "画像生成設定",
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ステップ数",
                        color = labelMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.sdSteps} / 50",
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = state.sdSteps.toFloat(),
                    onValueChange = { onStateChange(state.copy(sdSteps = it.toInt())) },
                    valueRange = 1f..50f,
                    steps = 48,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "CFG スケール",
                        color = labelMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = oneDecimalStr(state.sdCfg),
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = state.sdCfg,
                    onValueChange = { onStateChange(state.copy(sdCfg = it)) },
                    valueRange = 1f..20f,
                    steps = 38,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChatHistoryCard(
    state: NezumiSettingsFormState,
    onStateChange: (NezumiSettingsFormState) -> Unit,
    cardColors: CardColors,
    labelMuted: androidx.compose.ui.graphics.Color,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "チャット履歴管理",
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
            Text(
                text = "履歴保存件数",
                color = labelMuted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = state.chatHistoryLimit == 10,
                    onClick = { onStateChange(state.copy(chatHistoryLimit = 10)) },
                    label = { Text("10") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.chatHistoryLimit == 30,
                    onClick = { onStateChange(state.copy(chatHistoryLimit = 30)) },
                    label = { Text("30") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.chatHistoryLimit == 50,
                    onClick = { onStateChange(state.copy(chatHistoryLimit = 50)) },
                    label = { Text("50") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.chatHistoryLimit == -1,
                    onClick = { onStateChange(state.copy(chatHistoryLimit = -1)) },
                    label = { Text("無制限") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
