package com.nezumi_ai.presentation.ui.fragment

// SettingsComposeFragment から切り出した検索、テレメトリ、デバッグ、履歴、About、PIN の設定カード。

import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import androidx.lifecycle.lifecycleScope
import com.nezumi_ai.R
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.utils.LogcatRecorder
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.net.URL
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors

@Composable
internal fun TelemetryConsentCard() {
    val context = LocalContext.current
    var crashReportsEnabled by remember { mutableStateOf(com.nezumi_ai.utils.TelemetryConsent.isCrashReportsEnabled(context)) }
    var performanceEnabled by remember { mutableStateOf(com.nezumi_ai.utils.TelemetryConsent.isPerformanceMetricsEnabled(context)) }
    var diagnosticsEnabled by remember { mutableStateOf(com.nezumi_ai.utils.TelemetryConsent.isInferenceDiagnosticsEnabled(context)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_telemetry_card_title),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize
            )
            Text(
                text = stringResource(id = R.string.settings_telemetry_card_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )

            // 「全カテゴリ OFF 時に Sentry を停止する」判断は TelemetryConsent の
            // setter 内で行われるため、UI 側は値を渡すだけでよい
            // （SetupWizardFragment 側と同一の呼び出しパターン）。
            TelemetryToggleRow(stringResource(R.string.settings_telemetry_crash_title), stringResource(R.string.settings_telemetry_crash_desc), crashReportsEnabled) { checked ->
                crashReportsEnabled = checked
                com.nezumi_ai.utils.TelemetryConsent.setCategoryEnabled(context, com.nezumi_ai.utils.TelemetryConsent.Category.CRASH_REPORTS, checked)
            }
            TelemetryToggleRow(stringResource(R.string.settings_telemetry_performance_title), stringResource(R.string.settings_telemetry_performance_desc), performanceEnabled) { checked ->
                performanceEnabled = checked
                com.nezumi_ai.utils.TelemetryConsent.setCategoryEnabled(context, com.nezumi_ai.utils.TelemetryConsent.Category.PERFORMANCE, checked)
            }
            TelemetryToggleRow(stringResource(R.string.settings_telemetry_diagnostics_title), stringResource(R.string.settings_telemetry_diagnostics_desc), diagnosticsEnabled) { checked ->
                diagnosticsEnabled = checked
                com.nezumi_ai.utils.TelemetryConsent.setCategoryEnabled(context, com.nezumi_ai.utils.TelemetryConsent.Category.DIAGNOSTICS, checked)
            }

            HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

            Text(
                text = stringResource(id = R.string.settings_telemetry_offline_note),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun TelemetryToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colorResource(id = R.color.text_primary), style = MaterialTheme.typography.bodyMedium)
            Text(description, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = nezumiSwitchColors())
    }
}

@Composable
internal fun SettingsComposeFragment.WebSearchApiKeyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(id = R.string.settings_brave_card_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
            Text(
                text = stringResource(id = R.string.settings_brave_card_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = braveSearchApiKeyInput,
                onValueChange = { braveSearchApiKeyInput = it },
                label = { Text(stringResource(id = R.string.settings_brave_api_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_brave_api_ph)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                text = if (braveSearchApiKeyInput.isBlank()) stringResource(id = R.string.settings_brave_unset_hint) else stringResource(id = R.string.settings_brave_set_hint),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun SettingsComposeFragment.DebugSettingsCard(settingsSearchJumpState: SettingsSearchJumpState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_debug_section_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    modifier = Modifier.settingsSearchAnchor(
                        R.string.settings_debug_section_title,
                        settingsSearchJumpState
                    )
                )
                SvgSpinner(modifier = Modifier.size(32.dp))
            }
            Text(
                text = stringResource(id = R.string.settings_debug_similarity_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_debug_similarity_title,
                    settingsSearchJumpState
                )
            )
            Text(
                text = stringResource(id = R.string.settings_debug_similarity_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            val localContext = LocalContext.current
            val memoryInfoFlow = remember(localContext) {
                MemoryObserver.observeSystemMemoryInfo(localContext)
            }
            val systemMemoryInfo by memoryInfoFlow.collectAsState(
                initial = MemoryObserver.SystemMemoryInfo(0, 0, 0, 0, 0, false)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(id = R.string.settings_debug_memory_status), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(id = R.string.settings_debug_memory_usage, systemMemoryInfo.usedPercent, systemMemoryInfo.availablePercent),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(id = R.string.settings_debug_memory_summary, systemMemoryInfo.totalMemoryMB, systemMemoryInfo.usedMemoryMB, systemMemoryInfo.availableMemoryMB),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Text(
                        text = if (systemMemoryInfo.lowMemoryFlag) stringResource(id = R.string.settings_debug_memory_low) else stringResource(id = R.string.settings_debug_memory_stable),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (systemMemoryInfo.lowMemoryFlag) MaterialTheme.colorScheme.error else colorResource(id = R.color.text_secondary)
                    )
                    Text(
text = stringResource(id = R.string.settings_debug_memory_source_format, systemMemoryInfo.source),
style = MaterialTheme.typography.bodySmall,
color = colorResource(id = R.color.text_secondary)
)
                }
            }

            OutlinedTextField(
                value = debugTextAInput,
                onValueChange = { debugTextAInput = it },
                label = { Text(stringResource(id = R.string.settings_debug_text_a)) },
                placeholder = { Text(stringResource(id = R.string.settings_debug_text_a_ph)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                maxLines = 4
            )
            OutlinedTextField(
                value = debugTextBInput,
                onValueChange = { debugTextBInput = it },
                label = { Text(stringResource(id = R.string.settings_debug_text_b)) },
                placeholder = { Text(stringResource(id = R.string.settings_debug_text_b_ph)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                maxLines = 4
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    errorDialogMessage = null
                    debugTextSimilarityResult = null
                    if (debugTextAInput.isBlank()) {
                        errorDialogMessage = localContext.getString(R.string.settings_debug_text_a_required)
                        return@Button
                    }
                    if (debugTextBInput.isBlank()) {
                        errorDialogMessage = localContext.getString(R.string.settings_debug_text_b_required)
                        return@Button
                    }
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        MemoryTextEmbedder.initializeAsync(localContext)
                        val embeddingA = MemoryTextEmbedder.embed(debugTextAInput)
                        val embeddingB = MemoryTextEmbedder.embed(debugTextBInput)
                        val normA = MemoryRepository.l2norm(embeddingA)
                        val normB = MemoryRepository.l2norm(embeddingB)
                        val similarity = runCatching {
                            MemoryRepository.cosineSimilarity(embeddingA, normA, embeddingB, normB)
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (embeddingA.isEmpty() || embeddingB.isEmpty()) {
                                errorDialogMessage = localContext.getString(R.string.settings_debug_embedding_failed)
                                return@withContext
                            }
                            if (embeddingA.size != embeddingB.size) {
                                errorDialogMessage = localContext.getString(R.string.settings_debug_embedding_dimension_mismatch)
                                return@withContext
                            }
                            if (normA == 0f || normB == 0f) {
                                errorDialogMessage = localContext.getString(R.string.settings_debug_embedding_zero_vector)
                                return@withContext
                            }
                            debugTextSimilarityResult = localContext.getString(R.string.settings_debug_similarity_result_format, similarity ?: 0.0)
                        }
                    }
                }) {
                    Text(stringResource(id = R.string.settings_debug_compute_button))
                }
                Button(onClick = {
                    debugTextAInput = ""
                    debugTextBInput = ""
                    debugTextSimilarityResult = null
                    errorDialogMessage = null
                }) {
                    Text(stringResource(id = R.string.common_clear))
                }
            }

            debugTextSimilarityResult?.let {
                Text(text = it, color = colorResource(id = R.color.primary))
            }

            // ---- NSFW チェッカー (open_nsfw.onnx / Yahoo Open NSFW) ----
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(id = R.string.settings_debug_nsfw_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_debug_nsfw_title,
                    settingsSearchJumpState
                )
            )
            Text(
                text = stringResource(id = R.string.settings_debug_nsfw_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { nsfwDebugPickLauncher.launch("image/*") },
                    enabled = !nsfwDebugRunning
                ) {
                    Text(if (nsfwDebugRunning) stringResource(id = R.string.settings_debug_nsfw_running) else stringResource(id = R.string.settings_debug_nsfw_pick))
                }
                Button(onClick = {
                    nsfwDebugBitmap = null
                    nsfwDebugStatus = null
                    nsfwDebugSafeProb = null
                    nsfwDebugNsfwProb = null
                    nsfwDebugXsNsflProb = null
                    nsfwDebugXsNsfwProb = null
                    nsfwDebugXsSfwProb = null
                }, enabled = !nsfwDebugRunning) {
                    Text(stringResource(id = R.string.common_clear))
                }
            }
            nsfwDebugStatus?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            nsfwDebugBitmap?.let { bmp ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(id = R.string.settings_debug_nsfw_image_desc),
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val safe = nsfwDebugSafeProb
                        val nsfw = nsfwDebugNsfwProb
                        val xsNsfl = nsfwDebugXsNsflProb
                        val xsNsfw = nsfwDebugXsNsfwProb
                        val xsSfw = nsfwDebugXsSfwProb

                        if (safe != null && nsfw != null) {
                            // SafetyPolicy 経由で統合判定(Open NSFW + xs のOR結合)を算出。
                            // ハードコードした独自閾値ではなく、実運用と同じロジックを使う。
                            val nsfwResult = com.nezumi_ai.sd.safety.SafetyPolicy.fromRawOutput(
                                floatArrayOf(safe, nsfw)
                            )
                            val nsfwVerdict = nsfwResult.verdict
                            val xsVerdict = if (xsNsfl != null && xsNsfw != null && xsSfw != null) {
                                com.nezumi_ai.sd.safety.SafetyPolicy.evaluateClassifierXs(
                                    com.nezumi_ai.sd.safety.ImageSafetyClassifierResult(xsNsfl, xsNsfw, xsSfw)
                                )
                            } else null
                            val finalVerdict = if (xsVerdict != null) {
                                com.nezumi_ai.sd.safety.SafetyPolicy.combine(nsfwVerdict, xsVerdict)
                            } else nsfwVerdict

                            val verdictLabel = when (finalVerdict) {
                                com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLOCK -> stringResource(id = R.string.settings_debug_nsfw_verdict_block)
                                com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLUR -> "BLUR"
                                com.nezumi_ai.sd.safety.SafetyResult.Verdict.ALLOW -> stringResource(id = R.string.settings_debug_nsfw_verdict_allow)
                            }
                            val verdictColor = when (finalVerdict) {
                                com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLOCK -> MaterialTheme.colorScheme.error
                                com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLUR -> colorResource(id = R.color.text_secondary)
                                com.nezumi_ai.sd.safety.SafetyResult.Verdict.ALLOW -> colorResource(id = R.color.primary)
                            }
                            Text(
                                text = stringResource(id = R.string.settings_debug_verdict_format, verdictLabel),
                                color = verdictColor,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "[Open NSFW] safe=${String.format(Locale.US, "%.4f", safe)} nsfw=${String.format(Locale.US, "%.4f", nsfw)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (xsNsfl != null && xsNsfw != null && xsSfw != null) {
                                Text(
                                    text = "[xs] NSFL=${String.format(Locale.US, "%.4f", xsNsfl)} NSFW=${String.format(Locale.US, "%.4f", xsNsfw)} SFW=${String.format(Locale.US, "%.4f", xsSfw)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = "閾値: NSFW block=0.85/blur=0.30, NSFL block=0.75/blur=0.45 (2モデルOR結合)",
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                modelErrorDialogMessage = localContext.getString(R.string.settings_debug_model_error_message)
            }) {
                Text(stringResource(id = R.string.settings_debug_model_error_button))
            }

            // ---- Qwen3-TTS (llama.cpp TTS) 動作確認 ----
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(id = R.string.settings_debug_tts_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_debug_tts_title,
                    settingsSearchJumpState
                )
            )
            Text(
                text = stringResource(id = R.string.settings_debug_tts_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
            LaunchedEffect(Unit) { refreshTtsDebugStatus(localContext) }
            ttsDebugStatus?.let {
                Text(
                    text = it,
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (ttsDebugDownloading) {
                ttsDebugDownloadProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { downloadTtsDebugModels(localContext) },
                    enabled = !ttsDebugDownloading && !ttsDebugReady
                ) {
                    Text(stringResource(id = R.string.settings_debug_tts_download))
                }
                Button(
                    onClick = { ttsSpeakerPickLauncher.launch("audio/*") },
                    enabled = ttsDebugReady && !ttsDebugSynthesizing
                ) {
                    Text(stringResource(id = R.string.settings_debug_tts_pick_speaker))
                }
                // アプリ全体のデータ削除に頼らず、TTS モデルだけをここから消せるように。
                // ダウンロード先 URL を差し替えた際、古い形式のファイルが tts/ に残っていても
                // これで一掃できる。
                OutlinedButton(
                    onClick = { deleteTtsDebugModels(localContext) },
                    enabled = !ttsDebugDownloading && !ttsDebugSynthesizing
                ) {
                    Text(stringResource(id = R.string.settings_debug_tts_delete))
                }
            }
            Text(
                text = ttsDebugSpeakerName?.let {
                    stringResource(id = R.string.settings_debug_tts_speaker_selected, it)
                } ?: stringResource(id = R.string.settings_debug_tts_speaker_none),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.text_secondary)
            )
            OutlinedTextField(
                value = ttsDebugTextInput,
                onValueChange = { ttsDebugTextInput = it },
                label = { Text(stringResource(id = R.string.settings_debug_tts_text_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                maxLines = 4
            )
            Button(
                onClick = { runTtsDebugSynthesis(localContext) },
                enabled = ttsDebugReady && !ttsDebugSynthesizing && ttsDebugTextInput.isNotBlank()
            ) {
                Text(
                    if (ttsDebugSynthesizing) stringResource(id = R.string.settings_debug_tts_synthesizing)
                    else stringResource(id = R.string.settings_debug_tts_synthesize)
                )
            }
            if (ttsDebugAudioHistory.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.settings_debug_tts_history_title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.settingsSearchAnchor(
                        R.string.settings_debug_tts_history_title,
                        settingsSearchJumpState
                    )
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ttsDebugAudioHistory.forEach { audio ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(audio.text, maxLines = 2)
                                Text(
                                    text = audio.file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorResource(id = R.color.text_secondary)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            ttsDebugOutputPath = audio.file.absolutePath
                                            playTtsDebugAudio(audio.file)
                                        },
                                        enabled = !ttsDebugPlaying && audio.file.isFile
                                    ) {
                                        Text(stringResource(id = R.string.settings_debug_tts_play))
                                    }
                                    OutlinedButton(
                                        onClick = { stopTtsDebugAudio() },
                                        enabled = ttsDebugPlaying && ttsDebugOutputPath == audio.file.absolutePath
                                    ) {
                                        Text(stringResource(id = R.string.settings_debug_tts_stop))
                                    }
                                    TextButton(onClick = { requestSaveTtsDebugAudio(audio) }) {
                                        Text(stringResource(id = R.string.settings_debug_tts_save))
                                    }
                                    TextButton(onClick = { deleteTtsDebugAudio(audio) }) {
                                        Text(stringResource(id = R.string.settings_debug_tts_delete_audio))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ttsDebugResult?.let {
                Text(text = it, color = colorResource(id = R.color.primary), style = MaterialTheme.typography.bodySmall)
            }

            // logcat / ツール履歴は「ログ」タブへ移動
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(id = R.string.settings_section_logs) + " →",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
    }
}

/**
 * LogcatRecorder がバックグラウンドで書き続けているログを表示するセクション。
 * - 収集自体は MyApplication 起動時から常時継続しているため、この画面を開くたびに
 *   その時点までの蓄積ログ（古いものは自動削除済み）を読み込むだけでよい。
 * - 自動更新 ON の間は一定間隔でファイルを再読込し、末尾に追従する。
 * - テキスト選択・全文コピー・ファイル書き出し（共有）・ログレベル別カラーリングに対応。
 */
@Composable
internal fun SettingsComposeFragment.LogcatViewerSection(settingsSearchJumpState: SettingsSearchJumpState) {
    val localContext = LocalContext.current
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // ★ パフォーマンス修正: 自動更新中は 2 秒ごとに全ログファイルを再読込するため、
    //   メインスレッドで実行するとスクロール中の定期ジャンクになる。
    //   ファイル I/O は IO ディスパッチャに逃がし、State 更新だけメインで行う。
    //   さらに readRecentLogs() で末尾だけを読むことで、ローテーション済みの
    //   古いファイルまで毎回読み直す無駄を省いている。
    suspend fun refreshLogcatViewer() {
        val (text, bytes) = withContext(Dispatchers.IO) {
            LogcatRecorder.readRecentLogs(localContext) to LogcatRecorder.totalSizeBytes(localContext)
        }
        logcatViewerText = text
        logcatViewerSizeLabel = "%.1f KB".format(bytes / 1024.0)
    }

    // ★ パフォーマンス修正: 巨大なログ全文に対する色分け (buildAnnotatedString + 行ごとの正規表現)
    //   はコストが高く、全文が State に入っていると再コンポーズのたびに走ってしまう。
    //   derivedStateOf で logcatViewerText が変わったときだけ再計算する。
    val colorizedLogcatText by remember {
        derivedStateOf { colorizeLogcatText(logcatViewerText) }
    }

    // 画面表示中、自動更新 ON なら 2 秒おきに再読込して末尾へ追従する。
    LaunchedEffect(logcatViewerAutoRefresh) {
        refreshLogcatViewer()
        while (logcatViewerAutoRefresh) {
            kotlinx.coroutines.delay(2000)
            refreshLogcatViewer()
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.settings_logcat_title),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.settingsSearchAnchor(
                R.string.settings_logcat_title,
                settingsSearchJumpState
            )
        )
    }
    Text(
        text = stringResource(id = R.string.settings_logcat_desc),
        color = colorResource(id = R.color.text_secondary),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        text = stringResource(id = R.string.settings_logcat_size, logcatViewerSizeLabel),
        color = colorResource(id = R.color.text_secondary),
        style = MaterialTheme.typography.labelSmall
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Button(onClick = { scope.launch { refreshLogcatViewer() } }) {
            Text(stringResource(id = R.string.settings_debug_reload_button))
        }
        Button(onClick = { logcatViewerAutoRefresh = !logcatViewerAutoRefresh }) {
            Text(if (logcatViewerAutoRefresh) stringResource(id = R.string.settings_logcat_auto_on) else stringResource(id = R.string.settings_logcat_auto_off))
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Button(onClick = {
            // 表示中の全文をクリップボードへコピーする。
            clipboardManager.setText(AnnotatedString(logcatViewerText))
            Toast.makeText(localContext, localContext.getString(R.string.settings_logcat_copied), Toast.LENGTH_SHORT).show()
        }) {
            Text(stringResource(id = R.string.settings_debug_copy_button))
        }
        Button(onClick = {
            // 蓄積ログを1ファイルにマージして cacheDir へ書き出し、
            // FileProvider 経由で共有 Intent を発行する（メール添付・保存アプリなどに渡せる）。
            runCatching {
                val file = LogcatRecorder.exportToFile(localContext)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    localContext,
                    "${localContext.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                localContext.startActivity(Intent.createChooser(shareIntent, localContext.getString(R.string.settings_logcat_export_title)))
            }.onFailure {
                Toast.makeText(localContext, localContext.getString(R.string.settings_logcat_export_failed, it.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(id = R.string.settings_debug_export_button))
        }
        Button(onClick = {
            scope.launch {
                LogcatRecorder.clearAll(localContext)
                refreshLogcatViewer()
            }
        }) {
            Text(stringResource(id = R.string.settings_debug_clear_log_button))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        // SelectionContainer でログ本文を選択可能にする（部分コピー・共有アプリへの引き渡し用）。
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 320.dp)
                .verticalScroll(scrollState)
                .padding(8.dp)
        ) {
            Text(
                text = if (logcatViewerText.isBlank()) {
                    AnnotatedString(stringResource(id = R.string.settings_logcat_empty))
                } else {
                    colorizedLogcatText
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = colorResource(id = R.color.text_secondary)
            )
        }
    }
}

/**
 * logcat の各行を `threadtime` フォーマットのログレベル1文字（V/D/I/W/E/F）に基づいて色分けする。
 * 例: "08-03 12:34:56.789  1234  5678 E TAG: message" -> "E" を検出して赤系に着色。
 * 想定外のフォーマットの行はデフォルト色のまま表示する。
 */
internal fun colorizeLogcatText(rawText: String): AnnotatedString {
    // threadtime 形式: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
    // LEVEL 部分（1文字）だけを抜き出す軽量な正規表現。
    val levelRegex = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")

    return buildAnnotatedString {
        val lines = rawText.split("\n")
        for ((index, line) in lines.withIndex()) {
            val level = levelRegex.find(line)?.groupValues?.get(1)
            val color = when (level) {
                "E", "F" -> Color(0xFFE57373) // Error / Fatal: 赤
                "W" -> Color(0xFFFFB74D)       // Warning: オレンジ
                "I" -> Color(0xFF81C784)       // Info: 緑
                "D" -> Color(0xFF64B5F6)       // Debug: 青
                "V" -> Color(0xFFB0BEC5)       // Verbose: グレー
                else -> Color.Unspecified      // 不明なフォーマットはデフォルト色
            }
            withStyle(SpanStyle(color = color)) {
                append(line)
            }
            if (index != lines.lastIndex) append("\n")
        }
    }
}

@Composable
internal fun SettingsComposeFragment.ChatHistoryCard(settingsSearchJumpState: SettingsSearchJumpState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_chat_history_management_title),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_chat_history_management_title,
                    settingsSearchJumpState
                )
            )

            Text(
                text = stringResource(id = R.string.settings_chat_history_count_title),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_chat_history_count_title,
                    settingsSearchJumpState
                )
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = chatHistoryLimit == 10,
                    onClick = { chatHistoryLimit = 10 },
                    label = { Text(stringResource(id = R.string.settings_chat_history_10)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = chatHistoryLimit == 30,
                    onClick = { chatHistoryLimit = 30 },
                    label = { Text(stringResource(id = R.string.settings_chat_history_30)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = chatHistoryLimit == 50,
                    onClick = { chatHistoryLimit = 50 },
                    label = { Text(stringResource(id = R.string.settings_chat_history_50)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = chatHistoryLimit == -1,
                    onClick = { chatHistoryLimit = -1 },
                    label = { Text(stringResource(id = R.string.settings_chat_history_unlimited_label)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun VersionInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_engine_version_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(id = R.string.settings_engine_version_literlm_format, BuildConfig.LITERTLM_VERSION))
                Text(stringResource(id = R.string.settings_engine_version_llamacpp_format, BuildConfig.LLAMACPP_VERSION))
                Text(
                    stringResource(id = R.string.settings_engine_version_runtime_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_close))
            }
        }
    )
}

@Composable
internal fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenLicenses: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_about_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_round),
                    contentDescription = stringResource(id = R.string.settings_about_icon_content_description),
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(id = R.string.brand_name_display),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Text(
                        text = stringResource(id = R.string.settings_about_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary),
                        textAlign = TextAlign.Center
                    )
                }

                AboutSection(title = stringResource(id = R.string.settings_about_app_info_title)) {
                    AboutInfoRow(stringResource(id = R.string.settings_about_version_label), BuildConfig.VERSION_NAME)
                    AboutInfoRow(stringResource(id = R.string.settings_about_build_number_label), BuildConfig.VERSION_CODE.toString())
                    AboutInfoRow(stringResource(id = R.string.settings_about_package_label), BuildConfig.APPLICATION_ID)
                    AboutInfoRow(stringResource(id = R.string.settings_about_build_type_label), BuildConfig.BUILD_TYPE)
                }

                AboutSection(title = stringResource(id = R.string.settings_about_engine_title)) {
                    AboutInfoRow(stringResource(id = R.string.settings_about_engine_literlm), BuildConfig.LITERTLM_VERSION)
                    AboutInfoRow(stringResource(id = R.string.settings_about_engine_gguf), BuildConfig.LLAMACPP_VERSION)
                    AboutInfoRow(stringResource(id = R.string.settings_about_engine_stable_diffusion), stringResource(id = R.string.settings_about_engine_stable_diffusion_value))
                    if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
                        AboutInfoRow(stringResource(id = R.string.settings_about_tts_label), "VOICEVOX CORE 0.16.4")
                    }
                }

                AboutSection(title = stringResource(id = R.string.settings_about_features_title)) {
                    AboutBullet(stringResource(id = R.string.settings_about_feature_gemma))
                    AboutBullet(stringResource(id = R.string.settings_about_feature_multimodal))
                    AboutBullet(stringResource(id = R.string.settings_about_feature_memory))
                    AboutBullet(stringResource(id = R.string.settings_about_feature_tools))
                }

                Text(
                    text = stringResource(id = R.string.settings_about_license_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary),
                    textAlign = TextAlign.Center
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenLicenses) {
                Text(stringResource(id = R.string.settings_about_license_link))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_close))
            }
        }
    )
}

@Composable
internal fun AboutSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.text_primary)
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
internal fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(id = R.color.text_secondary),
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(id = R.color.text_primary),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
internal fun AboutBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(id = R.color.primary)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(id = R.color.text_primary),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun PinSetupDialog(
    hasExistingPin: Boolean,
    onPinSet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (hasExistingPin) stringResource(id = R.string.settings_pin_change_title) else stringResource(id = R.string.settings_pin_setup_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(id = R.string.settings_pin_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(id = R.color.text_secondary)
                )

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                            pinInput = it
                        }
                    },
                    label = { Text(stringResource(id = R.string.settings_pin_label)) },
                    placeholder = { Text(stringResource(id = R.string.settings_pin_ph)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            text = "${pinInput.length}/4",
                            color = if (pinInput.length == 4) colorResource(id = R.color.success) else colorResource(id = R.color.text_secondary)
                        )
                    }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(id = R.string.common_cancel))
                    }

                    Button(
                        onClick = { onPinSet(pinInput) },
                        enabled = pinInput.length == 4,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(id = R.string.common_next))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PinConfirmDialog(
    expectedPin: String,
    onConfirmed: () -> Unit,
    onMismatch: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmInput by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.settings_pin_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(id = R.string.settings_pin_confirm_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(id = R.color.text_secondary)
                )

                OutlinedTextField(
                    value = confirmInput,
                    onValueChange = {
                        if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                            confirmInput = it
                            showError = false
                        }
                        if (it.length == 4) {
                            if (it == expectedPin) {
                                onConfirmed()
                            } else {
                                showError = true
                            }
                        }
                    },
                    label = { Text(stringResource(id = R.string.settings_pin_confirm_label2)) },
                    placeholder = { Text(stringResource(id = R.string.settings_pin_ph)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError,
                    supportingText = {
                        if (showError) {
                            Text(
                                text = stringResource(id = R.string.settings_pin_mismatch),
                                color = colorResource(id = R.color.error)
                            )
                        } else {
                            Text(
                                text = "${confirmInput.length}/4",
                                color = colorResource(id = R.color.text_secondary)
                            )
                        }
                    }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(id = R.string.common_cancel))
                    }

                    Button(
                        onClick = onMismatch,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(id = R.string.common_retry))
                    }
                }
            }
        }
    }
}
