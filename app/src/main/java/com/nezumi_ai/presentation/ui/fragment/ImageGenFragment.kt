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
import com.nezumi_ai.sd.SdScheduler

import com.nezumi_ai.sd.safety.SafetyResult
import java.io.File

data class LibraryItem(
    val bitmap: Bitmap,
    val prompt: String,
    val timestamp: Long,
    val negativePrompt: String? = null,
    val steps: Int? = null,
    val seed: Long? = null,
    // Feature: ライブラリのメタデータ拡張。
    //   指示書の「表示必須項目」に揃えるため、モデル名 / 画像サイズ /
    //   CFG スケール / スケジューラの 4 項目を新規で保持する。
    //   既存ライブラリファイルとの下位互換のため、すべて nullable にする。
    val modelName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val cfg: Float? = null,
    val scheduler: String? = null

)

class ImageGenFragment : Fragment() {

    private val viewModel: ImageGenViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshAvailableModels()
        // Bug fix (ステップ数 / CFG / スケジューラを設定画面で変えても
        //   即時に反映されない問題):
        //   設定画面から戻る度に Preferences を引き直し、ViewModel の
        //   StateFlow を同期させる。UI の入力中値を上書きしないよう、
        //   実際に変わったときだけ代入する。
        viewModel.refreshPreferencesBackedFields()

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
                // Feature: メインの画像生成ページから設定画面へリンクする導線。
                //   スケジューラ / シードの入力を設定に集約したので、代わりにここを
                //   タップしたら直接見に行けるようにする。
                // Bug fix: 導線先で「画像」タブを自動で選ばせるために
                //   Bundle で startSection=2 を伸べる。SettingsComposeFragment は
                //   onCreate でこの値を読んで内部タブを切り替える。
                val navigateToSettings: () -> Unit = {
                    runCatching {
                        findNavController().navigate(
                            R.id.settingsFragment,
                            android.os.Bundle().apply { putInt("startSection", 2) }
                        )
                    }
                }
                NezumiImageGenTheme {
                    LegacyImageGenScreen(viewModel, navigateUp, navigateToSettings)

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
private fun LegacyImageGenScreen(
    vm: ImageGenViewModel,
    onNavigateUp: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {

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
    val scheduler by vm.scheduler.collectAsState()
    val lastUsedSeed by vm.lastUsedSeed.collectAsState()

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
    
    val lastCompletedMetadata by vm.lastCompletedMetadata.collectAsState()

    // Bug fix (ライブラリにネガティブプロンプト等のメタデータが出ない問題):
    //   旧パス (vm.getImageMetadata(ctx, vm.lastSavedInternalUri)) はキュー経路で
    //   URI が更新されず null を与えてしまうケースがあった。
    //   代わりに ViewModel 側で StateFlow として公開している
    //   lastCompletedMetadata を直接参照する。同一 timestamp での二重登録を
    //   防ぐため、直近に登録したメタデータの timestamp を覚えておく。
    //
    // Bug fix (ライブラリに同じ画像が 2 つ入る問題):
    //   旧実装は LaunchedEffect のキーに bitmap と lastCompletedMetadata の
    //   両方を指定していたため、ViewModel 側で
    //     _resultBitmap.value = bmp                       // ← 1 回目の発火
    //     _lastCompletedMetadata.value = metadata          // ← 2 回目の発火
    //   と StateFlow を順に更新する ImageGenViewModel の生成完了処理
    //   （キュー / 単発ともに）で 1 回の生成につき 2 回コルーチンが走り、
    //   1 回目 (metadata がまだ古い / 未到着) と 2 回目 (metadata 到着後)
    //   で別 timestamp のライブラリエントリが 2 件作られていた。
    //   ガードは「同じ metaTs での二度登録防止」しか見ていなかったので、
    //   1 回目に metaTs=0L で通ってしまうと 2 回目もチェックを通過する。
    //
    //   対処:
    //     - キーを metadata の timestamp に絞る (bitmap 変化単独では発火しない)。
    //       これで「bitmap 更新 → metadata 更新」の順で来ても 1 回だけになる。
    //     - metadata が確定してから登録する (metaTs == 0L は skip)。
    //     - すでに同じ timestamp のエントリが library に居るなら追加しない
    //       (StateFlow の replay や ViewModel 再購読で二重に走ったときの保険)。
    var lastLibraryTs by remember { mutableStateOf(0L) }
    LaunchedEffect(lastCompletedMetadata?.timestamp) {
        val meta = lastCompletedMetadata ?: return@LaunchedEffect
        val bmp = bitmap ?: return@LaunchedEffect
        val metaTs = meta.timestamp
        if (metaTs == 0L) return@LaunchedEffect
        // 同じメタデータを二度登録しない（bitmap や metadata のどちらかが
        // 先に届いてリコンポーズでリークするのを防ぐ）。
        if (metaTs == lastLibraryTs) return@LaunchedEffect
        // 二重ガード: すでに同じ timestamp のライブラリエントリがあるなら skip。
        //   （プロセス復帰直後で lastLibraryTs が 0 のまま同じ生成完了イベントを
        //   再購読するケースを想定。）
        if (library.any { it.timestamp == metaTs }) {
            lastLibraryTs = metaTs
            return@LaunchedEffect
        }
        val ts = saveImageToLibrary(
            ctx, bmp, prompt,
            negativePrompt = meta.negativePrompt,
            steps = meta.steps,
            seed = meta.seed,
            modelName = meta.modelName,
            width = meta.width,
            height = meta.height,
            cfg = meta.cfg,
            scheduler = meta.scheduler
        )
        library.add(
            0,
            LibraryItem(
                bmp, prompt, ts,
                negativePrompt = meta.negativePrompt,
                steps = meta.steps,
                seed = meta.seed,
                modelName = meta.modelName,
                width = meta.width,
                height = meta.height,
                cfg = meta.cfg,
                scheduler = meta.scheduler
            )
        )
        lastLibraryTs = metaTs
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.statusBarsPadding())
        
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
            // Feature: 設定画面へのリンク。スケジューラ / シードのデフォルト値は
            //   設定の「画像」タブに集約されているため、ここから直接飛べるようにする。
            TextButton(onClick = onNavigateToSettings) {
                Text("設定", color = MaterialTheme.colorScheme.primary)
            }

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
                
                // UI 整理: シード値設定も設定画面に集約し、ここでは折りたたみで
                //   コンパクトに行う。デフォルトは閉じておき、
                //   現在のシード状態と前回使用シードだけを見せる。入力が必要なときだけ
                //   展開して 1 行の入力フィールドを見せる。
                var seedExpanded by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { seedExpanded = !seedExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "シード値",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                if (seedValue == -1L) "ランダム" else seedValue.toString(),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            if (seedExpanded) "–" else "⊕",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    if (seedExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = if (seedValue == -1L) "" else seedValue.toString(),
                                onValueChange = {
                                    val s = it.toLongOrNull() ?: -1L
                                    vm.setSeed(s)
                                },
                                label = { Text("-1 でランダム") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                colors = fieldColors
                            )
                            TextButton(
                                onClick = { vm.setSeed(-1L) },
                                enabled = seedValue != -1L
                            ) {
                                Text("リセット")
                            }
                        }
                        Text(
                            "※ 詳細なデフォルトは「設定 > 画像」で変更できます。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                // Bug fix (シード表示):
                //   以前は seed=-1 (ランダム) のとき何も出ず「シード値が出ない」
                //   と見えていた。実際に使われた seed を導入した lastUsedSeed から
                //   一行で見せ、タップして再利用できるようにする。
                lastUsedSeed?.let { used ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "前回使用シード: $used",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.setSeed(used) }) {
                            Text("再利用")
                        }
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
                    // QNN (NPU) サポートは廃止。UI では MNN を唯一の選択肢として表示する。
                    listOf("mnn" to "MNN (CPU/GPU)").forEach { (value, label) ->

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

                // UI 整理: スケジューラ / ステップ数 / CFG の現在値を一括で小さく見せ、
                //   タップで設定画面へ飛ぶ導線にする。ユーザーが「どこでステップ数や
                //   CFG を変えるのか分からない」のを防ぐ。スケジューラも同じバッジに入れ、
                //   設定を変更して戻ってきたときは onResume の refreshPreferencesBackedFields で
                //   StateFlow が更新されるのでここの表示も自動で切り替わる。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onNavigateToSettings() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    @Composable
                    fun MetricCell(label: String, value: String) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                value,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    MetricCell("ステップ", steps.toString())
                    MetricCell("CFG", String.format("%.1f", cfg))
                    MetricCell("スケジューラ", scheduler.displayName)
                    Text(
                        "設定へ ›",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
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
                                    "生成中 ${currentStep}/${steps}",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
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
                        "現在 ${generationQueue.currentIndex + 1}/${generationQueue.items.size} 件目を生成中  |  ステップ ${currentStep}/${steps}",
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
                        
                        // Feature: 指示書にある「表示必須メタデータ一覧」を青写して見せる。
                        //   「プロンプト / ネガティブプロンプト / モデル名 /
                        //    画像サイズ / CFGスケール / スケジューラの種類 / シード値」。
                        //   値がない（旧ファイル）行は飛ばし、存在する値だけ並べる。
                        val metaRows: List<Pair<String, String>> = buildList {
                            if (!item.modelName.isNullOrEmpty()) add("モデル名" to item.modelName)
                            if (item.width != null && item.height != null) {
                                add("画像サイズ" to "${item.width} x ${item.height}")
                            }
                            if (item.steps != null) add("Steps" to item.steps.toString())
                            if (item.cfg != null) add("CFGスケール" to String.format("%.1f", item.cfg))
                            if (!item.scheduler.isNullOrEmpty()) {
                                val displayName = com.nezumi_ai.sd.SdScheduler.fromId(item.scheduler).displayName
                                add("スケジューラ" to displayName)
                            }
                            if (item.seed != null) add("シード値" to item.seed.toString())
                        }
                        metaRows.forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "$label:",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.width(110.dp)
                                )
                                Text(
                                    value,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
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
// Feature: モデル名 / 画像サイズ / CFG / スケジューラをライブラリに導入。

private fun saveImageToLibrary(
    context: android.content.Context,
    bitmap: Bitmap,
    prompt: String,
    negativePrompt: String? = null,
    steps: Int? = null,
    seed: Long? = null,
    modelName: String? = null,
    width: Int? = null,
    height: Int? = null,
    cfg: Float? = null,
    scheduler: String? = null

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
        // 新規項目：旧ファイルとの下位互換のため opt フィールドとして込む
        if (!modelName.isNullOrEmpty()) put("modelName", modelName)
        if (width != null && width > 0) put("width", width)
        if (height != null && height > 0) put("height", height)
        if (cfg != null) put("cfg", cfg.toDouble())
        if (!scheduler.isNullOrEmpty()) put("scheduler", scheduler)

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
                val modelName = obj.optString("modelName").takeIf { it.isNotEmpty() }
                val width = obj.optInt("width").takeIf { it > 0 }
                val height = obj.optInt("height").takeIf { it > 0 }
                val cfg = if (obj.has("cfg")) obj.optDouble("cfg").toFloat() else null
                val scheduler = obj.optString("scheduler").takeIf { it.isNotEmpty() }


                val imageFile = File(libraryDir, "img_${timestamp}.jpg")
                if (imageFile.exists()) {
                    val imageBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (imageBitmap != null) {
                        library.add(
                            LibraryItem(
                                imageBitmap, libPrompt, timestamp,
                                negativePrompt = negPrompt,
                                steps = steps,
                                seed = seed,
                                modelName = modelName,
                                width = width,
                                height = height,
                                cfg = cfg,
                                scheduler = scheduler
                            )
                        )

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

            val progressRatio = if (steps > 0) currentStep.toFloat() / steps else 0f
            LinearProgressIndicator(
                progress = { progressRatio },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

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
