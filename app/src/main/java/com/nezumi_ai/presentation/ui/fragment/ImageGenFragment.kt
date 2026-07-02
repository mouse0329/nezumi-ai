package com.nezumi_ai.presentation.ui.fragment

import androidx.compose.material3.ExperimentalMaterial3Api
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.border
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModel
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.sd.safety.SafetyResult
import java.io.File

data class LibraryItem(
    val bitmap: Bitmap,
    val prompt: String,
    val timestamp: Long,
    val negativePrompt: String? = null,
    val steps: Int? = null,
    val seed: Long? = null
)

class ImageGenFragment : Fragment() {

    private val viewModel: ImageGenViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshAvailableModels()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.cancel()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val navigateUp: () -> Unit = {
                    findNavController().navigateUp()
                    Unit
                }
                NezumiImageGenTheme {
                    LegacyImageGenScreen(viewModel, navigateUp)
                }
            }
        }
    }
}

@Composable
private fun NezumiImageGenTheme(content: @Composable () -> Unit) {
    val bg = colorResource(id = R.color.bg_session_list)
    val primary = colorResource(id = R.color.primary)
    val onPrimary = colorResource(id = R.color.nezumi_on_primary)
    val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
    val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
    val surface = colorResource(id = R.color.surface_card)
    val onSurface = colorResource(id = R.color.text_primary)
    val onSurfaceVariant = colorResource(id = R.color.text_secondary)
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onPrimaryContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = onPrimaryContainer,
            background = bg,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onPrimaryContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = onPrimaryContainer,
            background = bg,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceVariant
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography, content = content)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LegacyImageGenScreen(vm: ImageGenViewModel, onNavigateUp: () -> Unit) {
    val ctx = LocalContext.current
    val prompt by vm.prompt.collectAsState()
    val neg by vm.negativePrompt.collectAsState()
    val steps by vm.steps.collectAsState()
    val cfg by vm.cfg.collectAsState()
    val size by vm.sizePx.collectAsState()
    val seedValue by vm.seed.collectAsState()
    val bitmap by vm.resultBitmap.collectAsState()
    val loading by vm.loading.collectAsState()
    val snack by vm.snackbar.collectAsState()
    val safetyVerdict by vm.safetyVerdict.collectAsState()
    val safetyDownloading by vm.safetyDownloading.collectAsState()
    val safetyProgress by vm.safetyProgress.collectAsState()
    val safetyTotalBytes by vm.safetyTotalBytes.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val selectedModelIndex by vm.selectedModelIndex.collectAsState()
    val currentStep by vm.currentStep.collectAsState()
    val backendInfo by vm.backendInfo.collectAsState()
    val selectedBackend by vm.selectedBackend.collectAsState()
    val previewBitmap by vm.previewBitmap.collectAsState()
    val queueResultBitmaps by vm.queueResultBitmaps.collectAsState()
    val generationQueue by vm.generationQueue.collectAsState()
    val isQueueRunning by vm.isQueueRunning.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val library = remember { mutableStateListOf<LibraryItem>() }
    var viewerImage by remember { mutableStateOf<LibraryItem?>(null) }
    
    // ライブラリの初期化（永続化から読み込み）
    LaunchedEffect(Unit) {
        val savedLibrary = loadLibrary(ctx)
        library.addAll(savedLibrary)
    }

    val isStopKeyboardLearning: Boolean = remember { PreferencesHelper.isStopKeyboardLearningEnabled(ctx) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline
    )

    snack?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            vm.clearSnackbar()
        }
    }
    
    LaunchedEffect(bitmap) {
        bitmap?.let { bmp ->
            val meta = vm.getImageMetadata(ctx, vm.lastSavedInternalUri)
            val ts = saveImageToLibrary(ctx, bmp, prompt, meta?.negativePrompt, meta?.steps, meta?.seed)
            library.add(0, LibraryItem(bmp, prompt, ts, meta?.negativePrompt, meta?.steps, meta?.seed))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.statusBarsPadding())
        
        // 戻るボタンとタイトル
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "画像生成",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        
        // タブヘッダー
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("生成") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("ライブラリ") }
            )
        }
        
        // タブコンテンツ
        if (selectedTab == 0) {
            // 生成タブ
            Column(
                Modifier
                    .weight(1f, fill = true)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = if (availableModels.isNotEmpty() && selectedModelIndex in availableModels.indices) {
                            File(availableModels[selectedModelIndex]).name
                        } else {
                            "モデルが見つかりません"
                        },
                        onValueChange = {},
                        label = { Text(stringResource(R.string.image_gen_model_path_hint_full)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        singleLine = true,
                        colors = fieldColors,
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        availableModels.forEachIndexed { index, path ->
                            DropdownMenuItem(
                                text = { Text(File(path).name) },
                                onClick = {
                                    vm.setSelectedModelIndex(index)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                // シード値設定
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = if (seedValue == -1L) "" else seedValue.toString(),
                        onValueChange = {
                            val s = it.toLongOrNull() ?: -1L
                            vm.setSeed(s)
                        },
                        label = { Text("シード値 (-1でランダム)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        colors = fieldColors
                    )
                    Button(
                        onClick = { vm.setSeed(-1L) },
                        enabled = seedValue != -1L,
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("リセット")
                    }
                }

                // バックエンド選択
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "バックエンド:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    listOf("auto" to "自動", "qnn" to "GPU/NPU", "mnn" to "CPU").forEach { (value, label) ->
                        val selected = selectedBackend == value
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = { vm.setSelectedBackend(value) },
                            label = { Text(label) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                if (backendInfo.isNotEmpty()) {
                    Text(
                        "📡 $backendInfo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                if (isStopKeyboardLearning) {
                    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                    val hintText = stringResource(R.string.image_gen_prompt_hint)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(120.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(8.dp),
                        factory = { context ->
                            EditText(context).apply {
                                setHint(hintText)
                                setHintTextColor(hintColor)
                                setTextColor(textColor)
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
                        label = { Text(stringResource(R.string.image_gen_prompt_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = fieldColors,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                            autoCorrectEnabled = true,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text
                        )
                    )
                }
                var negExpanded by remember { mutableStateOf(false) }
                TextButton(onClick = { negExpanded = !negExpanded }) {
                    Text(
                        if (negExpanded) "▼ ネガティブを隠す" else "▶ ネガティブプロンプト",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (negExpanded) {
                        if (isStopKeyboardLearning) {
                            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                            val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                            val hintText = stringResource(R.string.image_gen_neg_hint)
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().height(80.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(8.dp),
                                factory = { context ->
                                    EditText(context).apply {
                                        setHint(hintText)
                                        setHintTextColor(hintColor)
                                        setTextColor(textColor)
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
                                    if (editText.text.toString() != neg) {
                                        editText.setText(neg)
                                    }
                                }
                            )
                        } else {
                            OutlinedTextField(
                                value = neg,
                                onValueChange = vm::setNegativePrompt,
                                label = { Text(stringResource(R.string.image_gen_neg_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                colors = fieldColors,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                                    autoCorrectEnabled = true,
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text
                                )
                            )
                        }
                }
                val sizeOptions = listOf(128, 192, 256, 320, 384, 448, 512)
                val selectedSizeIndex = sizeOptions.indexOf(size).coerceAtLeast(0)
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("サイズ", color = MaterialTheme.colorScheme.onSurface)
                        Text("${size}x$size", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = selectedSizeIndex.toFloat(),
                        onValueChange = { index ->
                            val snapped = sizeOptions.getOrElse(index.toInt()) { sizeOptions.last() }
                            vm.setSize(snapped)
                        },
                        valueRange = 0f..(sizeOptions.size - 1).toFloat(),
                        steps = sizeOptions.size - 2,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        sizeOptions.forEach { s ->
                            Text(s.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                var batchCount by remember { mutableStateOf(1) }
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        "生成数: $batchCount",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = batchCount.toFloat(),
                        onValueChange = { batchCount = it.toInt().coerceIn(1, 10) },
                        valueRange = 1f..10f,
                        steps = 8,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }

                val modelFileOk = availableModels.isNotEmpty() && selectedModelIndex in availableModels.indices && File(availableModels[selectedModelIndex]).isDirectory
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (vm.createGenerationQueue(batchCount)) {
                                vm.startQueueGeneration()
                            }
                        },
                        enabled = !loading && !safetyDownloading && modelFileOk && prompt.isNotBlank()
                    ) {
                        Text(stringResource(R.string.image_gen_generate))
                    }
                    if (safetyDownloading) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SvgSpinner(
                                    Modifier.size(20.dp)
                                )
                                Text(
                                    "セーフティモデル準備中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // ダウンロードプログレス表示
                            if (safetyProgress >= 0f) {
                                val pct = (safetyProgress * 100).toInt()
                                LinearProgressIndicator(
                                    progress = { safetyProgress },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outlineVariant
                                )
                                Text(
                                    "$pct%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else if (loading) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SvgSpinner(Modifier.size(24.dp))
                                Text(
                                    "$currentStep / $steps",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${if (steps > 0) (currentStep * 100 / steps) else 0}%",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (steps > 0) currentStep.toFloat() / steps else 0f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        Button(
                            onClick = { vm.cancel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(stringResource(R.string.image_gen_cancel))
                        }
                    }
                }

                if (generationQueue.items.isNotEmpty()) {
                    Text(
                        "現在 ${generationQueue.currentIndex + 1}/${generationQueue.items.size}  |  ステップ ${currentStep}/${steps}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // 画像表示エリア
                val displayImages = when {
                    queueResultBitmaps.isNotEmpty() -> queueResultBitmaps
                    bitmap != null -> listOf(bitmap)
                    else -> emptyList()
                }.filterNotNull()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        // 画像がある、または生成中
                        displayImages.isNotEmpty() || loading -> {
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)
                                ) {
                                    items(displayImages) { itemBmp ->
                                        ImageResultItem(itemBmp)
                                    }
                                    item {
                                        ImagePreviewSection(vm)
                                    }
                                }
                            }
                        }
                        // セーフティ違反ブロック
                        safetyVerdict == SafetyResult.Verdict.BLOCK -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.height(40.dp).width(40.dp)
                                )
                                Text(
                                    "不適切な表現が含まれる\n可能性があるコンテンツの表示を制限しました",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        // セーフティ警告：ブラー処理
                        safetyVerdict == SafetyResult.Verdict.BLUR -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.height(40.dp).width(40.dp)
                                )
                                Text(
                                    "セーフティフィルター適用\n潜在的に不適切なコンテンツは\nぼかし処理されました",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        // デフォルト：プレースホルダー
                        else -> {
                            Text(
                                "画像がここに表示されます",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (bitmap != null && !loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = { vm.saveToGallery(ctx) }) {
                            Text(stringResource(R.string.image_gen_save_gallery))
                        }
                        Button(onClick = { vm.share(ctx) }) {
                            Text(stringResource(R.string.image_gen_share))
                        }
                    }
                }
            }
        } else {
            // ライブラリタブ
            var deleteTarget by remember { mutableStateOf<LibraryItem?>(null) }

            deleteTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text("削除の確認") },
                    text = { Text("この画像をライブラリから削除しますか？") },
                    confirmButton = {
                        TextButton(onClick = {
                            val idx = library.indexOfFirst { it.timestamp == target.timestamp }
                            deleteImageFromLibrary(ctx, target.timestamp)
                            if (idx >= 0) library.removeAt(idx)
                            if (viewerImage?.timestamp == target.timestamp) viewerImage = null
                            deleteTarget = null
                        }) { Text("削除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
                    }
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(library.size) { idx ->
                    val item = library[idx]
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.clickable { viewerImage = item }) {
                            Image(
                                bitmap = item.bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Crop
                            )
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    item.prompt,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!item.negativePrompt.isNullOrEmpty()) {
                                    Text(
                                        "Neg: ${item.negativePrompt}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (item.steps != null) {
                                        Text(
                                            "Steps: ${item.steps}",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    if (item.seed != null) {
                                        Text(
                                            "Seed: ${item.seed}",
                                            color = MaterialTheme.colorScheme.secondary,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick = { deleteTarget = item },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
    
    // セーフティモデルダウンロード中モーダル
    if (safetyDownloading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("セーフティモデルを準備中") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (safetyProgress >= 0f) {
                        val pct = (safetyProgress * 100).toInt().coerceIn(0, 100)
                        val downloadedMb = safetyProgress * (safetyTotalBytes / (1024f * 1024f))
                        val totalMb = safetyTotalBytes / (1024f * 1024f)
                        Text(
                            if (safetyTotalBytes > 0L)
                                "$pct%  (${String.format("%.1f", downloadedMb)} / ${String.format("%.1f", totalMb)} MB)"
                            else
                                "$pct%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { safetyProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        SvgSpinner()
                    }
                    Text(
                        "安全フィルターをダウンロードしています。\n完了後に自動で生成を開始します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            confirmButton = {}
        )
    }

    // ビューワーダイアログ
    viewerImage?.let { item ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewerImage = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                    .clickable { viewerImage = null }
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(enabled = false) {}
                ) {
                    Image(
                        bitmap = item.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                    Column(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Prompt:",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            item.prompt,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        if (!item.negativePrompt.isNullOrEmpty()) {
                            Text(
                                "Negative Prompt:",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                item.negativePrompt,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (item.steps != null) {
                                Column {
                                    Text(
                                        "Steps:",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        item.steps.toString(),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            if (item.seed != null) {
                                Column {
                                    Text(
                                        "Seed:",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        item.seed.toString(),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(0.8f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { vm.saveBitmapToGallery(ctx, item.bitmap) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("保存")
                        }
                        Button(
                            onClick = { vm.shareBitmap(ctx, item.bitmap) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("共有")
                        }
                    }
                    TextButton(
                        onClick = { viewerImage = null },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("閉じる", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ライブラリの永続化関数
private fun saveImageToLibrary(
    context: android.content.Context,
    bitmap: Bitmap,
    prompt: String,
    negativePrompt: String? = null,
    steps: Int? = null,
    seed: Long? = null
): Long {
    val libraryDir = File(context.filesDir, "library")
    if (!libraryDir.exists()) {
        libraryDir.mkdirs()
    }
    
    val timestamp = System.currentTimeMillis()
    val filename = "img_$timestamp.jpg"
    val file = File(libraryDir, filename)
    
    file.outputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    }
    
    // メタデータを保存
    val metadataFile = File(libraryDir, "metadata.txt")
    val json = org.json.JSONObject().apply {
        put("timestamp", timestamp)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt ?: "")
        put("steps", steps ?: 0)
        put("seed", seed ?: -1L)
    }
    metadataFile.appendText(json.toString() + "\n")
    return timestamp
}

private fun loadLibrary(context: android.content.Context): List<LibraryItem> {
    val libraryDir = File(context.filesDir, "library")
    if (!libraryDir.exists()) return emptyList()
    
    val metadataFile = File(libraryDir, "metadata.txt")
    if (!metadataFile.exists()) return emptyList()
    
    val library = mutableListOf<LibraryItem>()
    val lines = metadataFile.readText().split("\n").filter { it.isNotEmpty() }
    
    for (line in lines.reversed()) { // 最新順に読み込み
        try {
            if (line.startsWith("{")) {
                val obj = org.json.JSONObject(line)
                val timestamp = obj.getLong("timestamp")
                val libPrompt = obj.getString("prompt")
                val negPrompt = obj.optString("negativePrompt").takeIf { it.isNotEmpty() }
                val steps = obj.optInt("steps").takeIf { it > 0 }
                val seed = obj.optLong("seed").takeIf { it != -1L }
                
                val imageFile = File(libraryDir, "img_${timestamp}.jpg")
                if (imageFile.exists()) {
                    val imageBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (imageBitmap != null) {
                        library.add(LibraryItem(imageBitmap, libPrompt, timestamp, negPrompt, steps, seed))
                    }
                }
            } else {
                // 以前の形式との互換性
                val parts = line.split("|", limit = 2)
                if (parts.size == 2) {
                    val timestamp = parts[0].toLongOrNull() ?: continue
                    val libPrompt = parts[1]
                    val imageFile = File(libraryDir, "img_${timestamp}.jpg")
                    if (imageFile.exists()) {
                        val imageBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (imageBitmap != null) {
                            library.add(LibraryItem(imageBitmap, libPrompt, timestamp))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Skip invalid lines
        }
    }

    return library
}

private fun deleteImageFromLibrary(context: android.content.Context, timestamp: Long) {
    val libraryDir = File(context.filesDir, "library")
    if (!libraryDir.exists()) return

    val imageFile = File(libraryDir, "img_${timestamp}.jpg")
    try {
        if (imageFile.exists()) {
            imageFile.delete()
        }

        val metadataFile = File(libraryDir, "metadata.txt")
        if (metadataFile.exists()) {
            val remaining = metadataFile.readText().split("\n").filter { it.isNotEmpty() }
                .filterNot { line ->
                    if (line.startsWith("{")) {
                        try {
                            org.json.JSONObject(line).getLong("timestamp") == timestamp
                        } catch (e: Exception) { false }
                    } else {
                        line.startsWith("${timestamp}|")
                    }
                }
            metadataFile.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        }
    } catch (e: Exception) {
        // ignore failure silently for now
    }
}

@Composable
private fun ImageResultItem(itemBmp: Bitmap) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Image(
            bitmap = itemBmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun ImagePreviewSection(vm: ImageGenViewModel) {
    val loading by vm.loading.collectAsState()
    val steps by vm.steps.collectAsState()
    val currentStep by vm.currentStep.collectAsState()

    if (loading) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // プレビュー画像の代わりにインジケーターを表示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp
                )
            }

            // プログレスバー
            val progressRatio = if (steps > 0) currentStep.toFloat() / steps else 0f
            LinearProgressIndicator(
                progress = { progressRatio },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            
            // ステップ情報
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ステップ $currentStep / $steps",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${(progressRatio * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
