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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.fragment.app.activityViewModels
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

    // Bug fix (画面を閉じるとプロンプト等が失われる / 生成中に閉じると進行度が
    //   分からなくなる):
    //   旧実装は Fragment スコープの ViewModel だったため、画像生成画面を
    //   閉じる (バックスタックから pop) たびに ViewModel ごと破棄され、
    //   プロンプト・生成中ジョブ・進行度 StateFlow がすべて失われていた。
    //   Activity スコープに変更してプロンプトと生成状態を保持し、画面を
    //   閉じても生成は継続 (進行度・完了・失敗は既存の通知で追跡可能) して
    //   画面を再び開いたときに進行度がそのまま再表示されるようにする。
    private val viewModel: ImageGenViewModel by activityViewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshAvailableModels(force = true)
        // Bug fix (ステップ数 / CFG / スケジューラを設定画面で変えても
        //   即時に反映されない問題):
        //   設定画面から戻る度に Preferences を引き直し、ViewModel の
        //   StateFlow を同期させる。UI の入力中値を上書きしないよう、
        //   実際に変わったときだけ代入する。
        viewModel.refreshPreferencesBackedFields()
        // Bug fix ("model.json に img2img:true があるのに UI 上で使えない"):
        //   モデル切替 / 別画面からの復帰時に img2img capability を再判定し、
        //   Compose 側の supportsImg2img StateFlow を最新化する。
        //   acquireLocalDream() 待ち (=生成ボタン押下待ち) では遅すぎるため、
        //   ここで model.json + ファイル配置から先取り判定する。
        viewModel.refreshImg2imgCapability()
    }

    // Bug fix (生成中に画面を閉じると進行度が分からなくなる):
    //   旧実装は onDestroyView で viewModel.cancel() を呼んでおり、設定画面への
    //   一時的な遷移でも生成が中断され、戻ったときに進行度ゼロからのやり直しに
    //   なっていた。画面を閉じても生成を継続させるため、ここではキャンセルしない
    //   (中断したい場合は画面内の停止ボタンを使う)。

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
    val width by vm.widthPx.collectAsState()
    val height by vm.heightPx.collectAsState()
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

    // ---- img2img ----------------------------------------------------------
    val supportsImg2img by vm.supportsImg2img.collectAsState()
    val img2imgEnabled by vm.img2imgEnabled.collectAsState()
    val initImageBmp by vm.initImageBitmap.collectAsState()
    val denoiseStrength by vm.denoiseStrength.collectAsState()
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val stream = ctx.contentResolver.openInputStream(uri)
                val bmp = stream?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    vm.setInitImage(bmp)
                    vm.setImg2imgEnabled(true)
                } else {
                    Log.w("ImageGenFragment", "failed to decode picked image: $uri")
                }
            } catch (t: Throwable) {
                Log.w("ImageGenFragment", "failed to load init image", t)
            }
        }
    }
    
    var selectedTab by remember { mutableStateOf(0) }
    var batchCount by remember { mutableStateOf(1) }
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
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                stringResource(R.string.image_gen_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            // Feature: 設定画面へのリンク。スケジューラ / シードのデフォルト値は
            //   設定の「画像」タブに集約されているため、ここから直接飛べるようにする。
            TextButton(onClick = onNavigateToSettings) {
                Text(stringResource(R.string.settings_title), color = MaterialTheme.colorScheme.primary)
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
                text = { Text(stringResource(R.string.image_gen_tab_generate)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.image_gen_tab_library)) }
            )
        }
        
        // タブコンテンツ
        if (selectedTab == 0) {
            // 生成タブ — スクロール本体 + 固定フッター（モックアップ準拠）
            Column(Modifier.weight(1f, fill = true)) {
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
                            stringResource(R.string.image_gen_model_missing)
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
                
                // 詳細設定アコーディオン（モックアップ準拠）: バックエンド / シード / Step·CFG·Scheduler を1つにまとめる
                var advExpanded by remember { mutableStateOf(false) }
                val advSummaryText = "Step $steps · CFG ${String.format("%.1f", cfg)} · ${scheduler.displayName}"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { advExpanded = !advExpanded }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "詳細設定",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                advSummaryText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (advExpanded) "▲" else "▼",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (advExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // バックエンド
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.image_gen_backend_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "mnn" to stringResource(R.string.image_gen_backend_cpu_mnn),
                                        "opencl" to stringResource(R.string.image_gen_backend_gpu_opencl)
                                    ).forEach { (value, label) ->
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
                                        backendInfo,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // シード
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.image_gen_seed_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = if (seedValue == -1L) "" else seedValue.toString(),
                                        onValueChange = {
                                            val s = it.toLongOrNull() ?: -1L
                                            vm.setSeed(s)
                                        },
                                        placeholder = { Text(stringResource(R.string.image_gen_seed_random)) },
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
                                        Text(stringResource(R.string.image_gen_seed_reset))
                                    }
                                }
                                lastUsedSeed?.let { used ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            stringResource(R.string.image_gen_seed_previous_format, used),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { vm.setSeed(used) }) {
                                            Text(stringResource(R.string.image_gen_seed_reuse))
                                        }
                                    }
                                }
                            }
                            // Step / CFG / Scheduler（タップで設定画面へ）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { onNavigateToSettings() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                @Composable
                                fun MetricCell(label: String, value: String) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                MetricCell(stringResource(R.string.image_gen_step_label), steps.toString())
                                MetricCell(stringResource(R.string.image_gen_cfg_label), String.format("%.1f", cfg))
                                MetricCell(stringResource(R.string.image_gen_scheduler_label), scheduler.displayName)
                                Text(
                                    stringResource(R.string.image_gen_settings_link),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
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
                // サイズ: 縦横独立のシークバー（デフォルト 256）
                // SD1.5 は元の刻み(〜512)、SDXL は最大1024
                val isSdxl by vm.isSdxl.collectAsState()
                val sizeOptions = if (isSdxl) listOf(512, 640, 768, 832, 896, 960, 1024)
                                  else listOf(128, 192, 256, 320, 384, 448, 512)
                // SDXL でも 128〜 を選べるように下限は共通、上限のみモデルで制限
                val widthIndex = sizeOptions.indexOf(width).takeIf { it >= 0 }
                    ?: sizeOptions.indexOf(sizeOptions.minBy { kotlin.math.abs(it - width) })
                val heightIndex = sizeOptions.indexOf(height).takeIf { it >= 0 }
                    ?: sizeOptions.indexOf(sizeOptions.minBy { kotlin.math.abs(it - height) })
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.image_gen_size_label), color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            stringResource(R.string.image_gen_size_value_format, width, height),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 幅
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("幅", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                        Slider(
                            value = widthIndex.toFloat(),
                            onValueChange = { index ->
                                val snapped = sizeOptions.getOrElse(index.toInt().coerceIn(0, sizeOptions.lastIndex)) { sizeOptions.last() }
                                vm.setWidth(snapped)
                            },
                            valueRange = 0f..(sizeOptions.size - 1).toFloat(),
                            steps = (sizeOptions.size - 2).coerceAtLeast(0),
                            enabled = !loading,
                            modifier = Modifier.weight(1f)
                        )
                        Text("$width", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(36.dp))
                    }
                    // 高さ
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("高さ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                        Slider(
                            value = heightIndex.toFloat(),
                            onValueChange = { index ->
                                val snapped = sizeOptions.getOrElse(index.toInt().coerceIn(0, sizeOptions.lastIndex)) { sizeOptions.last() }
                                vm.setHeight(snapped)
                            },
                            valueRange = 0f..(sizeOptions.size - 1).toFloat(),
                            steps = (sizeOptions.size - 2).coerceAtLeast(0),
                            enabled = !loading,
                            modifier = Modifier.weight(1f)
                        )
                        Text("$height", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(36.dp))
                    }
                    // 目盛りラベル（間引き表示）
                    Row(
                        Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val tickLabels = if (sizeOptions.size <= 5) sizeOptions else listOf(sizeOptions.first(), sizeOptions[sizeOptions.size / 2], sizeOptions.last())
                        tickLabels.forEach { s ->
                            Text(s.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // ============ img2img UI (capability 驅動) ============
                if (supportsImg2img) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.image_gen_init_image_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = img2imgEnabled,
                                onCheckedChange = { on ->
                                    vm.setImg2imgEnabled(on)
                                    if (on && initImageBmp == null) {
                                        pickImageLauncher.launch("image/*")
                                    }
                                },
                                enabled = !loading
                            )
                        }
                        if (img2imgEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (initImageBmp != null) {
                                    Image(
                                        bitmap = initImageBmp!!.asImageBitmap(),
                                        contentDescription = stringResource(R.string.image_gen_init_image_title),
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    TextButton(
                                        onClick = { pickImageLauncher.launch("image/*") },
                                        enabled = !loading
                                    ) {
                                        Text(if (initImageBmp == null) stringResource(R.string.image_gen_pick_image) else stringResource(R.string.image_gen_change_image))
                                    }
                                    if (initImageBmp != null) {
                                        TextButton(
                                            onClick = { vm.clearInitImage() },
                                            enabled = !loading
                                        ) {
                                            Text(stringResource(R.string.image_gen_clear), color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.image_gen_denoise_strength_format, denoiseStrength),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Slider(
                                value = denoiseStrength,
                                onValueChange = { vm.setDenoiseStrength(it) },
                                valueRange = 0f..1f,
                                steps = 19,
                                enabled = !loading
                            )
                            Text(
                                stringResource(R.string.image_gen_denoise_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.image_gen_img2img_unsupported),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // ============ /img2img UI ============

                // 生成ボタン・枚数は固定フッターへ移動（モックアップ準拠）

                if (generationQueue.items.isNotEmpty()) {
                    Text(
                        stringResource(R.string.image_gen_queue_progress_format, generationQueue.currentIndex + 1, generationQueue.items.size, currentStep, steps),
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
                                    stringResource(R.string.image_gen_safety_blocked),
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
                                    stringResource(R.string.image_gen_safety_blurred),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        // デフォルト：プレースホルダー
                        else -> {
                            Text(
                                stringResource(R.string.image_gen_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (safetyVerdict == SafetyResult.Verdict.BLUR && displayImages.isNotEmpty()) {
                    Text(
                        stringResource(R.string.image_gen_safety_blurred),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
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
            } // end scrollable form

            // ========== Fixed footer（モックアップ準拠）: ±枚数 + 生成/キャンセル ==========
            val modelFileOk = availableModels.isNotEmpty() && selectedModelIndex in availableModels.indices && File(availableModels[selectedModelIndex]).isDirectory
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (safetyDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SvgSpinner(Modifier.size(20.dp))
                            Text(
                                stringResource(R.string.image_gen_safety_preparing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (safetyProgress >= 0f) {
                            val pct = (safetyProgress * 100).toInt()
                            LinearProgressIndicator(
                                progress = { safetyProgress },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                            Text("$pct%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (loading) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SvgSpinner(Modifier.size(22.dp))
                            Text(
                                stringResource(R.string.image_gen_generating_format, currentStep, steps),
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
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { if (batchCount < 10) batchCount++ },
                            enabled = !loading && batchCount < 10,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            batchCount.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { if (batchCount > 1) batchCount-- },
                            enabled = !loading && batchCount > 1,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("−", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (loading) {
                        Button(
                            onClick = { vm.cancel() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.image_gen_cancel), style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (vm.createGenerationQueue(batchCount)) {
                                    vm.startQueueGeneration()
                                }
                            },
                            enabled = !loading && !safetyDownloading && modelFileOk && prompt.isNotBlank(),
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.image_gen_generate), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            } // end outer generate Column (form + footer)
        } else {
            // ライブラリタブ
            var deleteTarget by remember { mutableStateOf<LibraryItem?>(null) }

            deleteTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text(stringResource(R.string.image_gen_delete_confirm_title)) },
                    text = { Text(stringResource(R.string.image_gen_delete_confirm_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            val idx = library.indexOfFirst { it.timestamp == target.timestamp }
                            deleteImageFromLibrary(ctx, target.timestamp)
                            if (idx >= 0) library.removeAt(idx)
                            if (viewerImage?.timestamp == target.timestamp) viewerImage = null
                            deleteTarget = null
                        }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
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
                                        stringResource(R.string.image_gen_negative_preview_format, item.negativePrompt),
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
                                            stringResource(R.string.image_gen_steps_preview_format, item.steps),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    if (item.seed != null) {
                                        Text(
                                            stringResource(R.string.image_gen_seed_preview_format, item.seed),
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
                                contentDescription = stringResource(R.string.common_delete),
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
            title = { Text(stringResource(R.string.image_gen_safety_modal_title)) },
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
                        stringResource(R.string.image_gen_safety_modal_message),
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
    // Bug fix: 極端な縦長 (例: 192×256) / 横長 (例: 512×192) で UI が壊れる問題。
    //   画像は最大高さ制限 + ContentScale.Fit で枠内に収め、
    //   メタデータはスクロール、ボタンは常に下部に固定。
    viewerImage?.let { item ->
        val config = LocalConfiguration.current
        val maxImageHeight = (config.screenHeightDp * 0.45f).dp
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewerImage = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                    .clickable { viewerImage = null }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .clickable(enabled = false) {}
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    // 画像: 縦長・横長どちらでも枠内に Fit。
                    //   高さ上限で縦長のはみ出しを防ぎ、横長は幅いっぱい・高さは自然に縮む。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = maxImageHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = item.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }

                    // メタデータ: 残り領域をスクロール可能に
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.image_gen_viewer_prompt_label),
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
                                stringResource(R.string.image_gen_viewer_negative_label),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                item.negativePrompt,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        val metaRows: List<Pair<String, String>> = buildList {
                            if (!item.modelName.isNullOrEmpty()) add(stringResource(R.string.image_gen_viewer_model_label) to item.modelName)
                            if (item.width != null && item.height != null) {
                                add(stringResource(R.string.image_gen_viewer_size_label) to "${item.width} x ${item.height}")
                            }
                            if (item.steps != null) add(stringResource(R.string.image_gen_viewer_steps_label) to item.steps.toString())
                            if (item.cfg != null) add(stringResource(R.string.image_gen_viewer_cfg_label) to String.format("%.1f", item.cfg))
                            if (!item.scheduler.isNullOrEmpty()) {
                                val displayName = com.nezumi_ai.sd.SdScheduler.fromId(item.scheduler).displayName
                                add(stringResource(R.string.image_gen_viewer_scheduler_label) to displayName)
                            }
                            if (item.seed != null) add(stringResource(R.string.image_gen_viewer_seed_label) to item.seed.toString())
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
                    }

                    // ボタン行: 常に下部に固定（画面外に出ない）
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { vm.saveBitmapToGallery(ctx, item.bitmap) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(stringResource(R.string.image_gen_save_gallery))
                        }
                        Button(
                            onClick = { vm.shareBitmap(ctx, item.bitmap) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(stringResource(R.string.image_gen_share))
                        }
                    }
                    TextButton(
                        onClick = { viewerImage = null },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.viewer_close), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // 縦長・横長どちらでも全体が見えるよう Fit。
    // 極端な縦長は高さ上限、極端な横長は幅100%で高さが自然に縮む。
    val aspect = if (itemBmp.height > 0) itemBmp.width.toFloat() / itemBmp.height.toFloat() else 1f
    val boxModifier = when {
        // 極端な縦長 (例: 192×512 相当)
        aspect < 0.55f -> Modifier.fillMaxWidth().heightIn(max = 360.dp)
        // 極端な横長 (例: 512×192 相当)
        aspect > 1.8f -> Modifier.fillMaxWidth().heightIn(max = 220.dp)
        // 通常範囲は実アスペクトで表示
        else -> Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.55f, 1.8f))
    }
    Box(
        modifier = boxModifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = itemBmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
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
