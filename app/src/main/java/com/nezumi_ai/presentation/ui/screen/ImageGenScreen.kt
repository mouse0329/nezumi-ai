package com.nezumi_ai.presentation.ui.screen

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nezumi_ai.R
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModel
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModelFactory
import com.nezumi_ai.sd.GenerationQueue
import com.nezumi_ai.sd.GenerationQueueItem
import com.nezumi_ai.sd.ImageGenerationMetadata
import com.nezumi_ai.sd.SdScheduler

/**
 * バグ修正 (ライトモード対応):
 *   当初 `isSystemInDarkTheme()` で dark 判定していたが、
 *   これは OS レベルのシステムテーマしか見ないため、
 *   `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO)` で
 *   アプリ内テーマだけライトに切り替えているケースでも
 *   dark = true のままになり、画像ビュワーの overlay が黒いままになっていた。
 *   Activity の実効テーマを `Configuration.UI_MODE_NIGHT_MASK` で見ることで、
 *   アプリ内テーマ切替にも正しく追従するようにする。
 */
@Composable
private fun isAppInDarkMode(): Boolean {
    val config = LocalConfiguration.current
    val night = config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * バグ修正 (ライトモード対応):
 *   当初 ImageGenScreen 内の各色 (カード背景、入力欄背景、ラベル色 …) を
 *   0xFF1A1A1A / 0xFF2A2A2A / 0xFF999999 などの暗色にハードコードしていたため、
 *   ライトモードにしても Generate / Library タブのカード背景が
 *   黒いままになっていた。ImageViewer だけでなく下層のカードも刅さないと
 *   ユーザーの見え方上、ビューワーの背景だけ白くなるアンバランスな
 *   パレットになってしまうので、全体をテーマ連動に切り替える。
 *
 *   [darkColor] をライトモードでの代替色に変換する写像。
 */
private fun mapDarkToLight(darkColor: Color): Color = when (darkColor) {
    Color(0xFF121212) -> Color(0xFFF5F6F8)   // ルート背景
    Color(0xFF1E1E1E) -> Color(0xFFF0F1F4)   // タブヘッダー背景
    Color(0xFF1A1A1A) -> Color(0xFFE5E7EB)   // カード背景 (深め)
    Color(0xFF2A2A2A) -> Color(0xFFF3F4F6)   // カード背景 (一般) / 入力欄
    Color(0xFF333333) -> Color(0xFFD1D5DB)   // divider
    Color(0xFF444444) -> Color(0xFFCBD1DA)   // border / 未選択 track
    Color(0xFF666666) -> Color(0xFF4B5563)   // 不活性テキスト / secondary
    Color(0xFF999999) -> Color(0xFF4B5563)   // プレースホルダー / ラベル
    Color(0xFFEEEEEE) -> Color(0xFF111827)   // 本文
    else -> darkColor
}

@Composable
private fun themed(darkColor: Color): Color =
    if (isAppInDarkMode()) darkColor else mapDarkToLight(darkColor)
data class GeneratedImage(val bitmap: Bitmap, val prompt: String, val timestamp: Long)

@Composable
fun ImageGenScreen() {
    val context = LocalContext.current
    val viewModel: ImageGenViewModel = viewModel(
        factory = ImageGenViewModelFactory(
            context.applicationContext as android.app.Application
        )
    )
    var selectedTab by remember { mutableStateOf(0) }
    val library = remember { mutableStateListOf<GeneratedImage>() }
    var viewerImage by remember { mutableStateOf<GeneratedImage?>(null) }
    
    val resultBitmap by viewModel.resultBitmap.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    
    LaunchedEffect(resultBitmap) {
        resultBitmap?.let { bmp ->
            library.add(0, GeneratedImage(bmp, prompt, System.currentTimeMillis()))
        }
    }
    
    Column(Modifier.fillMaxSize().background(themed(Color(0xFF121212)))) {
        TabHeader(selectedTab) { selectedTab = it }
        
        when (selectedTab) {
            0 -> GenerateTab(viewModel) { viewerImage = it }
            1 -> LibraryTab(library) { viewerImage = it }
        }
    }
    
    viewerImage?.let { img ->
        ImageViewer(img, viewModel) { viewerImage = null }
    }
}

@Composable
fun TabHeader(selected: Int, onSelect: (Int) -> Unit) {
    // バグ修正 (ライトモード対応):
    //   タブヘッダーも背景を 0xFF1E1E1E の暗色に固定していたため、
    //   ライトモードでもヘッダーだけ黒い額縁のようになっていた。
    //   アプリ内テーマにあわせて背景/未選択タブの文字色を切り替える。
    val dark = isAppInDarkMode()
    val headerBg = if (dark) Color(0xFF1E1E1E) else Color(0xFFF5F6F8)
    val dividerColor = if (dark) Color(0xFF333333) else Color(0xFFD1D5DB)
    val inactiveText = if (dark) Color(0xFF999999) else Color(0xFF4B5563)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(headerBg)) {
            Box(
                Modifier.weight(1f).clickable { onSelect(0) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(id = R.string.image_gen_tab_generate),
                    color = if (selected == 0) Color(0xFF0084FF) else inactiveText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Box(
                Modifier.weight(1f).clickable { onSelect(1) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(id = R.string.image_gen_tab_library),
                    color = if (selected == 1) Color(0xFF0084FF) else inactiveText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
        Row(Modifier.fillMaxWidth().height(3.dp)) {
            if (selected == 0) {
                Box(Modifier.width(100.dp).height(3.dp).background(Color(0xFF0084FF)))
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(100.dp).height(3.dp).background(Color(0xFF0084FF)))
            }
        }
    }
}

@Composable
fun TabButton(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) Color(0xFF0084FF) else themed(Color(0xFF999999)),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun GenerateTab(vm: ImageGenViewModel, onImageClick: (GeneratedImage) -> Unit) {
    val context = LocalContext.current
    val models by vm.availableModels.collectAsState()
    val selectedIdx by vm.selectedModelIndex.collectAsState()
    val backendInfo by vm.backendInfo.collectAsState()
    val prompt by vm.prompt.collectAsState()
    val negPrompt by vm.negativePrompt.collectAsState()
    val sizePx by vm.sizePx.collectAsState()
    val seed by vm.seed.collectAsState()
    val scheduler by vm.scheduler.collectAsState()
    val loading by vm.loading.collectAsState()
    val resultBitmap by vm.resultBitmap.collectAsState()
    val currentStep by vm.currentStep.collectAsState()
    val steps by vm.steps.collectAsState()
    
    var negExpanded by remember { mutableStateOf(false) }
    
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        FieldGroup("モデル") {
            if (models.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(themed(Color(0xFF2A2A2A))).padding(10.dp)
                ) {
                    Text(
                        models.getOrNull(selectedIdx)?.substringAfterLast("/") ?: "未選択",
                        // バグ修正 (ライトモード対応):
                        //   背景と セットでテーマ連動させないと白背景に白文字で同化する。
                        color = themed(Color(0xFFEEEEEE)),
                        fontSize = 14.sp
                    )
                }
                Text(
 "$backendInfo",
                    color = themed(Color(0xFF999999)),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(stringResource(R.string.image_gen_model_missing), color = themed(Color(0xFF999999)), fontSize = 14.sp)
            }
        }
        
        FieldGroup("プロンプト") {
            val isStopKeyboardLearning = remember { com.nezumi_ai.utils.PreferencesHelper.isStopKeyboardLearningEnabled(context) }
            if (isStopKeyboardLearning) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp)).background(themed(Color(0xFF2A2A2A))).border(1.dp, themed(Color(0xFF444444)), RoundedCornerShape(8.dp)).padding(8.dp),
                    factory = { context ->
                        EditText(context).apply {
                            setTextColor(android.graphics.Color.WHITE)
                            setHintTextColor(android.graphics.Color.GRAY)
                            setBackground(null)
                            gravity = android.view.Gravity.TOP
                            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    vm.setPrompt(s?.toString() ?: "")
                                }
                                override fun afterTextChanged(s: android.text.Editable?) {}
                            })
                        }
                    },
                    update = { editText ->
                        if (editText.text.toString() != prompt) {
                            editText.setText(prompt)
                        }
                    }
                )
            } else {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = vm::setPrompt,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = themed(Color(0xFFEEEEEE)),
                        unfocusedTextColor = themed(Color(0xFFEEEEEE)),
                        focusedContainerColor = themed(Color(0xFF2A2A2A)),
                        unfocusedContainerColor = themed(Color(0xFF2A2A2A)),
                        focusedBorderColor = Color(0xFF0084FF),
                        unfocusedBorderColor = themed(Color(0xFF444444))
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
        
        TextButton(onClick = { negExpanded = !negExpanded }) {
            Text(
                "${if (negExpanded) "▼" else "▶"} ${stringResource(R.string.image_gen_negative_prompt_label)}",
                color = Color(0xFF0084FF),
                fontSize = 14.sp
            )
        }
        
        AnimatedVisibility(negExpanded) {
            val isStopKeyboardLearning = remember { com.nezumi_ai.utils.PreferencesHelper.isStopKeyboardLearningEnabled(context) }
            val negativePromptPlaceholder = context.getString(R.string.image_gen_negative_prompt_placeholder)
            if (isStopKeyboardLearning) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(80.dp).padding(bottom = 15.dp).clip(RoundedCornerShape(8.dp)).background(themed(Color(0xFF2A2A2A))).border(1.dp, themed(Color(0xFF444444)), RoundedCornerShape(8.dp)).padding(8.dp),
                    factory = { context ->
                        EditText(context).apply {
                            setHint(negativePromptPlaceholder)
                            setTextColor(android.graphics.Color.WHITE)
                            setHintTextColor(android.graphics.Color.GRAY)
                            setBackground(null)
                            gravity = android.view.Gravity.TOP
                            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    vm.setNegativePrompt(s?.toString() ?: "")
                                }
                                override fun afterTextChanged(s: android.text.Editable?) {}
                            })
                        }
                    },
                    update = { editText ->
                        if (editText.text.toString() != negPrompt) {
                            editText.setText(negPrompt)
                        }
                    }
                )
            } else {
                OutlinedTextField(
                    value = negPrompt,
                    onValueChange = vm::setNegativePrompt,
                    placeholder = { Text(negativePromptPlaceholder, color = themed(Color(0xFF666666))) },
                    modifier = Modifier.fillMaxWidth().height(80.dp).padding(bottom = 15.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = themed(Color(0xFFEEEEEE)),
                        unfocusedTextColor = themed(Color(0xFFEEEEEE)),
                        focusedContainerColor = themed(Color(0xFF2A2A2A)),
                        unfocusedContainerColor = themed(Color(0xFF2A2A2A)),
                        focusedBorderColor = Color(0xFF0084FF),
                        unfocusedBorderColor = themed(Color(0xFF444444))
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
        
        FieldGroup("サイズ") {
            // SDXL モデルロード時は上限を 1024 に拡張 (mnn-sd-engine 側 caps.max_side_px=1536 と合わせる)。
            val isSdxl by vm.isSdxl.collectAsState()
            val sizeOptions = if (isSdxl) listOf(512, 640, 768, 832, 896, 960, 1024)
                              else listOf(128, 192, 256, 320, 384, 448, 512)
            val selectedIndex = sizeOptions.indexOf(sizePx).coerceAtLeast(0)

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(themed(Color(0xFF2A2A2A))).padding(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.image_gen_generate_size), color = themed(Color(0xFF999999)), fontSize = 12.sp)
                    Text(
                        "${sizePx}x$sizePx",
                        color = Color(0xFF0084FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Slider(
                    value = selectedIndex.toFloat(),
                    onValueChange = { index ->
                        val snapped = sizeOptions.getOrElse(index.roundToInt()) { sizeOptions.last() }
                        vm.setSize(snapped)
                    },
                    valueRange = 0f..(sizeOptions.size - 1).toFloat(),
                    steps = sizeOptions.size - 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF0084FF),
                        activeTrackColor = Color(0xFF0084FF),
                        inactiveTrackColor = themed(Color(0xFF444444)),
                        activeTickColor = themed(Color(0xFFEEEEEE)),
                        inactiveTickColor = themed(Color(0xFF666666))
                    )
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    sizeOptions.forEach { size ->
                        Text(size.toString(), color = themed(Color(0xFF999999)), fontSize = 11.sp)
                    }
                }
            }
        }

        FieldGroup("スケジューラ") {
            val schedulerOptions = remember { SdScheduler.values().toList() }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(themed(Color(0xFF2A2A2A))).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                schedulerOptions.chunked(2).forEach { rowOptions ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { option ->
                            SchedulerChip(
                                text = option.displayName,
                                active = scheduler == option,
                                onClick = { vm.setScheduler(option) }
                            )
                        }
                        if (rowOptions.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    "※ 現状 JNI の MNN 経路は PLMS で固定実行されます (ネイティブの mnn_sd_run_pipeline 制限)。選択はメタデータにのみ反映され、実際のスケジューラは変わりません。HTTP 互換経路では選択値がそのまま送信されます。",
                    color = Color(0xFFFFA726),
                    fontSize = 11.sp
                )
            }
        }

        FieldGroup("バックエンド (CPU / GPU)") {
            val selectedBackend by vm.selectedBackend.collectAsState()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(themed(Color(0xFF2A2A2A))).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("mnn" to "CPU (MNN)", "opencl" to "GPU (OpenCL)").forEach { (key, label) ->
                    SchedulerChip(text = label, active = selectedBackend == key, onClick = {
                        vm.setSelectedBackend(key)
                    })
                }
            }
            Text(
                "※ GPU (OpenCL) は対応端末と *_opencl モデルでのみ動作します。",
                color = themed(Color(0xFF999999)),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        FieldGroup("シード") {
            var seedInput by remember(seed) { mutableStateOf(if (seed < 0) "" else seed.toString()) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = seedInput,
                    onValueChange = { value ->
                        seedInput = value
                        if (value.isBlank()) {
                            vm.setSeed(-1L)
                        } else {
                            value.toLongOrNull()?.let(vm::setSeed)
                        }
                    },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.image_gen_seed_empty_hint), color = themed(Color(0xFF666666))) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = themed(Color(0xFFEEEEEE)),
                        unfocusedTextColor = themed(Color(0xFFEEEEEE)),
                        focusedContainerColor = themed(Color(0xFF2A2A2A)),
                        unfocusedContainerColor = themed(Color(0xFF2A2A2A)),
                        focusedBorderColor = Color(0xFF0084FF),
                        unfocusedBorderColor = themed(Color(0xFF444444))
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Button(
                    onClick = { vm.setSeed(-1L) },
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = themed(Color(0xFF666666))),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.image_gen_seed_random), fontSize = 12.sp)
                }
            }
            Text(
                if (seed < 0) stringResource(R.string.image_gen_seed_current_format, stringResource(R.string.image_gen_seed_random)) else stringResource(R.string.image_gen_seed_current_format, seed),
                color = themed(Color(0xFF999999)),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        
        // ============ 一括生成キュー機能 ============
        val queueProgressState = vm.queueProgress.collectAsState()
        val isQueueRunningState = vm.isQueueRunning.collectAsState()
        val queueState = vm.generationQueue.collectAsState()
        
        val queueProgress = queueProgressState.value
        val isQueueRunning = isQueueRunningState.value
        val queue = queueState.value
        
        var showQueueDialog by remember { mutableStateOf(false) }
        var batchCount by remember { mutableStateOf(1) }
        
        Divider(color = themed(Color(0xFF333333)), modifier = Modifier.padding(vertical = 16.dp))
        
        Text(
            "一括生成",
            color = Color(0xFF0084FF),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(themed(Color(0xFF2A2A2A))).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.image_gen_queue_count_label), color = themed(Color(0xFF999999)), fontSize = 12.sp)
            
            for (count in 1..10) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .background(if (batchCount == count) Color(0xFF0084FF) else themed(Color(0xFF1A1A1A)))
                        .clickable { batchCount = count }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        count.toString(),
                        // バグ修正: アクティブ時の背景は青なので白のままで OK。非アクティブをテーマ連動。
                        color = if (batchCount == count) Color.White else themed(Color(0xFF666666)),
                        fontSize = 11.sp,
                        fontWeight = if (batchCount == count) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { showQueueDialog = true },
                modifier = Modifier.weight(1f).height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.image_gen_queue_add), fontSize = 12.sp)
            }
            
            Button(
                onClick = { 
                    if (isQueueRunning) vm.cancelQueueExecution()
                    else if (queue.items.isNotEmpty()) vm.startQueueGeneration()
                },
                modifier = Modifier.weight(1f).height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isQueueRunning) themed(Color(0xFF666666)) else Color(0xFF00CC00)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isQueueRunning) stringResource(R.string.image_gen_queue_running) else stringResource(R.string.image_gen_queue_run), fontSize = 12.sp)
            }
        }
        
        if (queue.items.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(themed(Color(0xFF1A1A1A)))
                    .padding(10.dp)
            ) {
                Column {
                    Text(
                        stringResource(R.string.image_gen_queue_status_format, queue.completedCount, queue.items.size),
                        color = Color(0xFF0084FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    queueProgress?.let { (current, total) ->
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = Color(0xFF00CC00)
                        )
                    }
                }
            }
            
            Button(
                onClick = { vm.clearQueue() },
                modifier = Modifier.padding(top = 8.dp).height(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themed(Color(0xFF666666))),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.image_gen_clear), fontSize = 11.sp)
            }
        }
        
        // キューに追加ダイアログ
        if (showQueueDialog) {
            AlertDialog(
                onDismissRequest = { showQueueDialog = false },
                title = { Text(stringResource(R.string.image_gen_queue_add), color = themed(Color(0xFFEEEEEE))) },
                text = {
                    Text(
                        stringResource(R.string.image_gen_queue_confirm_message, batchCount),
                        color = themed(Color(0xFF999999))
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (vm.createGenerationQueue(batchCount)) {
                                showQueueDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF))
                    ) {
                        Text(stringResource(R.string.image_gen_queue_confirm_add))
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showQueueDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = themed(Color(0xFF666666)))
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                containerColor = themed(Color(0xFF2A2A2A))
            )
        }
        // ==========================================
        
        Button(
            onClick = { if (loading) vm.cancel() else vm.generate() },
            modifier = Modifier.padding(vertical = 20.dp).width(120.dp).height(40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (loading) themed(Color(0xFF666666)) else Color(0xFF0084FF)
            ),
            shape = RoundedCornerShape(25.dp)
        ) {
            Text(
                if (loading) stringResource(R.string.image_gen_stop) else stringResource(R.string.image_gen_generate),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
        
        if (loading) {
            LinearProgressIndicator(
                progress = { if (steps > 0) currentStep.toFloat() / steps else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = Color(0xFF0084FF)
            )
            Text(
                stringResource(R.string.image_gen_step_progress_format, currentStep, steps),
                color = themed(Color(0xFF999999)),
                fontSize = 12.sp
            )
        }
        
        // 生成完了後の再描画のガクガクを防ぐため animateContentSize でなだらかに切り替える。
        // resultBitmap の到達で表示が突然差し込まれるとリストの縦サイズが飛ぶため、
        // Column 自体のサイズ変化をアニメーションで補間する。
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = FastOutSlowInEasing
                    )
                )
        ) {
            resultBitmap?.let { bmp ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onImageClick(GeneratedImage(bmp, prompt, System.currentTimeMillis())) },
                        contentScale = ContentScale.FillWidth
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ActionButton(stringResource(R.string.image_gen_save_gallery), Modifier.weight(1f)) { vm.saveToGallery(context) }
                        ActionButton(stringResource(R.string.image_gen_share), Modifier.weight(1f)) { vm.share(context) }
                    }
                }
            }
        }
    }
}

@Composable
fun FieldGroup(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            label,
            color = themed(Color(0xFF999999)),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun RowScope.SizeTab(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
            .background(if (active) Color(0xFF0084FF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            // アクティブ時は青の背景なので白固定。非アクティブはテーマ連動。
            color = if (active) Color.White else themed(Color(0xFF999999)),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun RowScope.SchedulerChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
            .background(if (active) Color(0xFF0084FF) else themed(Color(0xFF1A1A1A)))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) Color.White else themed(Color(0xFF999999)),
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(text, fontSize = 14.sp)
    }
}

@Composable
fun LibraryTab(library: List<GeneratedImage>, onImageClick: (GeneratedImage) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(library) { img ->
            LibraryCard(img, onImageClick)
        }
    }
}

@Composable
fun LibraryCard(img: GeneratedImage, onClick: (GeneratedImage) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(themed(Color(0xFF1E1E1E)))
            .clickable { onClick(img) }
    ) {
        androidx.compose.foundation.Image(
            bitmap = img.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = ContentScale.Crop
        )
        Text(
            img.prompt,
            // バグ修正 (ライトモード対応):
            //   ライブラリのカード背景をテーマ連動させたのに合わせて、
            //   キャプションも secondary テキスト色にマッピングする。
            color = themed(Color(0xFF999999)),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
fun ImageViewer(img: GeneratedImage, vm: ImageGenViewModel, onClose: () -> Unit) {
    val context = LocalContext.current

    // バグ修正 (ライトモード対応):
    //   以前は背景を常に半透明黒 (0xF2000000)、テキストを常に白系に
    //   固定していたため、ライトモードでも画像ビュワーの背景が真っ黒に
    //   なっていた。さらに `isSystemInDarkTheme()` だけでは
    //   AppCompatDelegate でアプリ内テーマだけ切り替えているケースを見逃して
    //   いたので、Activity の実効テーマを見る `isAppInDarkMode()` を使う。
    //   テーマに応じて背景と前景のパレットを切り替える:
    //     - ライト: 白背景 (半透明白) + 黒テキスト
    //     - ダーク: 従来の黒背景 + 白テキスト
    val dark = isAppInDarkMode()
    val overlayColor = if (dark) Color(0xF2000000) else Color(0xF2FFFFFF)
    val onOverlayText = if (dark) Color(0xFFEEEEEE) else Color(0xFF111827)
    val closeButtonBg = if (dark) themed(Color(0xFF444444)) else Color(0xFFE5E7EB)
    val closeButtonText = if (dark) Color.White else Color(0xFF111827)

    Dialog(
        onDismissRequest = onClose,
 // バグ修正: usePlatformDefaultWidth = false だけだと Dialog の window 属性が
        //   Application Overlay 相当のレイヤになってしまい、一部端末で keyguard
        //   (ロック画面) より前面に出てしまう → securePolicy を SecureOn にし、
        //   decorFitsSystemWindows も見直して本体 Activity の window より前に出さない。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.Inherit,
            decorFitsSystemWindows = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
 // ビューアの window 属性をロック画面より前に出ないよう固定し、
        //   スクリーンショット無効化設定に従って FLAG_SECURE を切り替える。
        val currentView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            try {
                var host: android.view.View? = currentView
                var dialogWindow: android.view.Window? = null
                while (host != null) {
                    if (host is androidx.compose.ui.window.DialogWindowProvider) {
                        dialogWindow = (host as androidx.compose.ui.window.DialogWindowProvider).window
                        break
                    }
                    host = host.parent as? android.view.View
                }
                dialogWindow?.let { w ->
                    // ロック画面より前に出ないよう、以下のフラグをクリアする。
                    w.clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                    // スクリーンショット無効化設定にしたがって FLAG_SECURE を切り替える。
                    if (com.nezumi_ai.utils.PreferencesHelper.isDisableScreenshot(context)) {
                        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            } catch (_: Throwable) {}
        }

        Box(
            Modifier.fillMaxSize().background(overlayColor).clickable { onClose() }
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(enabled = false) {}
            ) {
                androidx.compose.foundation.Image(
                    bitmap = img.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.FillWidth
                )

                Text(
                    "${stringResource(id = R.string.viewer_prompt_prefix)}\n${img.prompt}",
                    // バグ修正: overlay の背景色に応じて適切なコントラストを保つ。
                    //   ライト: 黒文字, ダーク: 白文字。
                    color = onOverlayText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 20.dp)
                )

                Row(
                    Modifier.fillMaxWidth(0.8f),
                    horizontalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    Button(
                        onClick = { vm.saveToGallery(context) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0084FF),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text(stringResource(id = R.string.viewer_action_save), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Button(
                        onClick = { vm.share(context) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0084FF),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text(stringResource(id = R.string.viewer_share), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Button(
                    onClick = onClose,
                    modifier = Modifier.padding(top = 20.dp).width(80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = closeButtonBg,
                        contentColor = closeButtonText
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(stringResource(id = R.string.viewer_close), color = closeButtonText)
                }
            }
        }
    }
}
