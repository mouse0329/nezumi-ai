package com.nezumi_ai.presentation.ui.fragment

// ModelSettingsFragment から切り出した Hugging Face 検索とダウンロードキュー。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.height
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch

@Composable
internal fun ModelSettingsFragment.DownloadQueueCard() {
    // Gemma (LocalModel) のみここで表示。おすすめ llama.cpp は hfQueuedDownloads 側に出るため二重表示しない。
    val builtinDownloading = ModelFileManager.LocalModel.entries.mapNotNull { m ->
        val s = modelStates[m] ?: return@mapNotNull null
        if (s.isDownloading) m to s else null
    }
    if (builtinDownloading.isNotEmpty()) {
        Text(
            text = stringResource(id = R.string.model_download_queue_builtin_header),
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            builtinDownloading.forEach { (model, state) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.surface_card))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = state.title, fontWeight = FontWeight.SemiBold)
                        if (state.progress > 0f) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                progress = { state.progress },
                                color = colorResource(id = R.color.primary),
                                trackColor = colorResource(id = R.color.context_meter_track)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorResource(id = R.color.primary),
                                trackColor = colorResource(id = R.color.context_meter_track)
                            )
                        }
                        Text(
                            text = state.progressText.ifBlank { state.status },
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                ModelDownloadWorker.pause(requireContext(), model)
                                toast(getString(R.string.model_download_paused_toast))
                            }) { Text(stringResource(id = R.string.model_download_pause)) }
                        }
                    }
                }
            }
        }
    }
    if (hfQueuedDownloads.isNotEmpty()) {
        Text(
            text = stringResource(id = R.string.model_download_queue_hf_header),
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                start = 4.dp,
                bottom = 8.dp,
                top = if (builtinDownloading.isNotEmpty()) 16.dp else 0.dp
            )
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hfQueuedDownloads.forEach { item ->
                // HF カスタム DL の速度キーは observeDownloadSpeeds() で
                //   "{modelId}/{fileName}" 形式で登録される。
                val speedKey = "${item.modelId}/${item.filePath.substringAfterLast('/')}"
                val speedInfo = activeDownloadSpeeds[speedKey]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colorResource(id = R.color.surface_card)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = item.modelId, fontWeight = FontWeight.SemiBold)
                        Text(text = item.filePath, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                        if (item.totalBytes > 0L) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                progress = { item.progress },
                                color = colorResource(id = R.color.primary),
                                trackColor = colorResource(id = R.color.context_meter_track)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorResource(id = R.color.primary),
                                trackColor = colorResource(id = R.color.context_meter_track)
                            )
                        }
                        Text(text = item.statusText, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                        // 各カードに通信速度と残り時間を表示
                        speedInfo?.let { info ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format("%.1f MB/s", info.speedMbps),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorResource(id = R.color.primary),
                                    fontWeight = FontWeight.Bold
                                )
                                if (info.estimatedRemainingSec > 0) {
                                    val remainMin = (info.estimatedRemainingSec / 60).toInt()
                                    val remainSec = (info.estimatedRemainingSec % 60).toInt()
                                    Text(
                                        text = "残り ${remainMin}分${remainSec}秒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.text_secondary)
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            if (item.isActive) {
                                TextButton(onClick = {
                                    ModelDownloadWorker.pauseCustomHf(
                                        requireContext(),
                                        item.modelId,
                                        item.filePath
                                    )
                                    toast("一時停止しました。再開時は続きからダウンロードします")
                                }) { Text("一時停止") }
                            } else if (item.isPaused) {
                                TextButton(onClick = {
                                    ModelDownloadWorker.enqueueCustomHf(
                                        requireContext(),
                                        item.modelId,
                                        item.filePath
                                    )
                                }) { Text("再開") }
                            }
                            if (item.isActive || item.isPaused) {
                                TextButton(onClick = {
                                    ModelDownloadWorker.cancelCustomHf(
                                        requireContext(),
                                        item.modelId,
                                        item.filePath
                                    )
                                }) { Text("キャンセル") }
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (imageModelDownloadStates.isNotEmpty()) {
        Text(
            text = "画像生成モデル ダウンロード中",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = if (hfQueuedDownloads.isNotEmpty()) 16.dp else 0.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            imageModelDownloadStates.forEach { item ->
                ModelDownloadProgressCard(
                    item,
                    onPause = {
                        ModelDownloadWorker.pauseImageModel(requireContext(), item.modelId)
                        toast("一時停止しました。再開時は続きからダウンロードします")
                    },
                    onCancel = {
                        ModelDownloadWorker.cancelImageModel(requireContext(), item.modelId)
                    }
                )
            }
        }
    }

    safetyModelDownloadState?.let { item ->
        Text(
            text = "セーフティモデル ダウンロード中",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                start = 4.dp,
                bottom = 8.dp,
                top = if (hfQueuedDownloads.isNotEmpty() || imageModelDownloadStates.isNotEmpty()) 16.dp else 0.dp
            )
        )
        ModelDownloadProgressCard(item)
    }
    
    val anyRecommendedDownloading = recommendedGgufStates.values.any { it.isDownloading }
    val anyBuiltinDownloading = modelStates.values.any { it.isDownloading }
    if (hfQueuedDownloads.isEmpty() && imageModelDownloadStates.isEmpty() &&
        safetyModelDownloadState == null && !anyRecommendedDownloading && !anyBuiltinDownloading
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Text(
                text = stringResource(id = R.string.model_download_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(id = R.color.text_secondary),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
internal fun ModelSettingsFragment.HfCard() {
    Text(
        text = "Hugging Face 連携",
        style = MaterialTheme.typography.labelSmall,
        color = colorResource(id = R.color.text_secondary),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "HF:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (hfLinked) "連携済み" else "未連携",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(
                    onClick = { if (hfLinked) logoutHf() else startOAuthLogin() },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(if (hfLinked) "ログアウト" else "ログイン 🤗", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun ModelSettingsFragment.HfModelSearchCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = hfSearchQuery,
                    onValueChange = { hfSearchQuery = it },
                    placeholder = { Text("キーワード / repo id") },
                    singleLine = true
                )
                Button(
                    enabled = !hfSearchLoading,
                    onClick = { searchHfModels() },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(if (hfSearchLoading) "検索中..." else "検索")
                }
            }
            if (hfSearchResults.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !hfSearchLoading,
                        onClick = { hfSearchResultsDialogVisible = true }
                    ) {
                        Text("結果を見る (${hfSearchResults.size})")
                    }
                    TextButton(
                        enabled = !hfSearchLoading,
                        onClick = {
                            hfSearchResults = emptyList()
                            hfSearchNextPageUrl = null
                            hfSearchError = null
                            hfHasSearched = false
                            hfSearchResultsDialogVisible = false
                        }
                    ) {
                        Text("クリア")
                    }
                }
            }
            hfSearchError?.let {
                Text(text = it, color = colorResource(id = R.color.text_primary), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ModelSettingsFragment.SdImageGenFromHfCard() {
    Text(
        text = "リポジトリ",
        style = MaterialTheme.typography.labelSmall,
        color = colorResource(id = R.color.text_secondary),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "sd-mnn (MNN)",

                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.text_secondary)
            )
            Button(
                onClick = { loadImageModels() },
                enabled = !imageModelsLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (imageModelsLoading) "読込中..." else "モデル一覧を表示")
            }
            imageModelsError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!hfLinked || it.contains("認証")) {
                    TextButton(onClick = { startOAuthLogin() }) {
                        Text("HuggingFaceに再ログイン")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelSettingsFragment.HfSearchResultsContent() {
    // 旧 HfModelSearchCard を廃止したので、検索入力欄をこのページの上部に移した。
    // さらにリストを一定量スクロールしたときだけ右下に「上にジャンプ」ボタンを出す。
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val showJumpTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 200
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg_session_list))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hfSearchResultsDialogVisible = false }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "戻る",
                        tint = colorResource(id = R.color.text_primary)
                    )
                }
                Text(
                    text = "検索結果",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(id = R.color.text_primary),
                    fontWeight = FontWeight.Bold
                )
            }

            // 検索入力欄（旧 HfModelSearchCard から移行）。
            //   - 旧実装と同じく hfSearchQuery / searchHfModels() にバインドし、
            //     検索実行後は現ページに結果リストが差し替わる。
            //   - クリア・結果を見るボタンはこのページ自体が結果ビューなので不要。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = hfSearchQuery,
                            onValueChange = { hfSearchQuery = it },
                            placeholder = { Text("キーワード / repo id") },
                            singleLine = true
                        )
                        Button(
                            enabled = !hfSearchLoading,
                            onClick = { searchHfModels() },
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text(if (hfSearchLoading) "検索中..." else "検索")
                        }
                    }
                    hfSearchError?.let {
                        Text(
                            text = it,
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (hfSearchResults.isEmpty()) {
                Text(
                    text = if (hfSearchLoading) "検索中..." else if (!hfHasSearched) "キーワードを入力して検索してください" else "検索結果がありません",
                    color = colorResource(id = R.color.text_secondary)
                )
            } else {
                Text(
                    text = "${hfSearchResults.size}件の結果",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
 // 次ページの自動読み込み:
                //   旧: LaunchedEffect(hfSearchResults.size) → trigger item が
                //       LazyColumn に compose された瞬間に発火していたため、
                //       ユーザーがスクロールしていなくても全ページを一気に取得してしまう。
                //   新: リストの末尾付近が実際に表示されたときだけ loadMore を呼ぶ。
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val total = info.totalItemsCount
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        // 末尾に多少余裕を持たせる (2item 手前からプリフェッチ)
                        total > 0 && lastVisible >= total - 2
                    }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            val nextUrl = hfSearchNextPageUrl
                            if (nextUrl != null && !hfSearchLoadingMore) {
                                loadMoreHfResults(nextUrl)
                            }
                        }
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hfSearchResults, key = { it.id }) { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = colorResource(id = R.color.primary_light)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "\u2B73",
                                        color = colorResource(id = R.color.primary),
                                        fontSize = 18.sp
                                    )
                                    Text(text = result.id, fontWeight = FontWeight.SemiBold)
                                }
                                Text(
                                    text = "DL: ${result.downloads} / Likes: ${result.likes}",
                                    color = colorResource(id = R.color.text_secondary)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !hfFilePickerLoading,
                                        onClick = {
                                            openHfFilePicker(result)
                                        }
                                    ) {
                                        Text("ファイル選択")
                                    }
                                    TextButton(onClick = {
                                        if (!com.nezumi_ai.utils.ExternalLinkOpener.openUrl(
                                                requireContext(),
                                                "https://huggingface.co/${result.id}"
                                            )
                                        ) {
                                            toast("ブラウザを起動できませんでした")
                                        }
                                    }) {
                                        Text("ページを開く")
                                    }
                                }
                            }
                        }
                    }
     // 次ページプレースホルダー: スピナーのみ。loadMore のトリガーは
                    //   上の snapshotFlow 監視で行うので、この item は "現在ロード中に見える" 存在だけ。
                    item {
                        if (hfSearchNextPageUrl != null && hfSearchLoadingMore) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                SvgSpinner()
                            }
                        } else if (hfSearchNextPageUrl != null) {
                            // 候補があるが未ロードのときもプレースホルダーだけ支持しておく（高さは保つ）
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }

        // スクロールして先頭から離れたときだけ右下に「上にジャンプ」 FAB を表示する。
        // ModelScreen の「＋」FAB と同じ位置だが、他タブと並びではない検索ビュー上のボタンなので衝突はない。
        if (showJumpTop && hfSearchResults.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                containerColor = colorResource(id = R.color.primary),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "上までジャンプ"
                )
            }
        }
    }
}

@Composable
internal fun ModelSettingsFragment.HfFilePickerDialog(model: ModelFileManager.HfModelSearchResult) {
    Dialog(onDismissRequest = {
        if (hfDownloadingFilePath == null) {
            hfFilePickerModel = null
        }
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "ダウンロードするファイルを選択", fontWeight = FontWeight.Bold)
                Text(text = model.id, color = colorResource(id = R.color.text_secondary))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { 
                        hfReadmePageTitle = model.id
                        hfReadmePageVisible = true
                        fetchHfReadme(model.id) 
                    }) {
                        Text("README")
                    }
                }
                if (hfFilePickerLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SvgSpinner(modifier = Modifier.size(18.dp))
                        Text("ファイル一覧を取得中...")
                    }
                } else if (hfFilePickerFiles.isEmpty() && hfMmprojCandidates.isEmpty()) {
                    Text("対応ファイル（.gguf / .task / .litertlm / .mmproj）が見つかりません")
                } else {
                    // メインモデルファイル一覧
                    if (hfFilePickerFiles.isNotEmpty()) {
                        // mmproj がある場合は自動DLの旨を表示
                        if (hfMmprojCandidates.isNotEmpty()) {
                            val autoMmproj = hfMmprojCandidates
                                .filter { it.sizeBytes != null }
                                .minByOrNull { it.sizeBytes!! }
                                ?: hfMmprojCandidates.first()
                            Text(
 text = "mmproj が見つかりました。DL時に「${autoMmproj.path}」も自動ダウンロードし、画像認識が有効になります。",
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        hfFilePickerFiles.forEach { file ->
                            HfFileRow(model.id, file)
                        }
                    }
                    // mmproj セクション（同リポジトリのみ）
                    if (hfMmprojCandidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "mmproj（マルチモーダル用）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorResource(id = R.color.text_primary)
                        )
                        hfMmprojCandidates.forEach { file ->
                            HfFileRow(model.id, file, isMmproj = true)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        enabled = hfDownloadingFilePath == null,
                        onClick = { hfFilePickerModel = null }
                    ) { Text("閉じる") }
                }
            }
        }
    }
}

@Composable
internal fun ModelSettingsFragment.HfFileRow(
    modelId: String,
    file: ModelFileManager.HfModelFile,
    isMmproj: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = file.path)
            Text(
                text = file.sizeBytes?.let { formatBytes(it) } ?: "size: unknown",
                color = colorResource(id = R.color.text_secondary)
            )
            if (file.sizeBytes != null) {
                val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(requireContext(), file.sizeBytes, preloadMemoryWarningThresholdPercent, useAvailable = false)
                val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), file.sizeBytes, preloadMemoryWarningThresholdPercent)
                if (isMemoryLow || resourceCheck.isStorageLow) {
                    Text(
                        text = when {
 isMemoryLow && resourceCheck.isStorageLow -> "メモリ・ストレージ不足"
 isMemoryLow -> "メモリ不足"
 else -> "ストレージ不足"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        Button(
            enabled = hfDownloadingFilePath == null && (file.sizeBytes == null || !ModelFileManager.checkDownloadResources(requireContext(), file.sizeBytes, preloadMemoryWarningThresholdPercent).isStorageLow),
            onClick = {
                if (isMmproj) downloadHfMmprojFile(modelId, file.path)
                else downloadHfModelFile(modelId, file.path)
            }
        ) {
            val isDownloading = hfDownloadingFilePath == file.path
            Text(if (isDownloading) "DL中..." else "DL")
        }
    }
}

@Composable
internal fun ModelSettingsFragment.HfReadmePage() {
    val isDark = isSystemInDarkTheme()
    val textColor = colorResource(id = R.color.text_primary)
    val linkSpan = SpanStyle(color = textColor, textDecoration = TextDecoration.Underline)
    val linkStyle = TextLinkStyles(
        style = linkSpan,
        hoveredStyle = linkSpan,
        pressedStyle = linkSpan,
        focusedStyle = linkSpan
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(colorResource(id = R.color.bg_session_list))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "README",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (isDark) Color.White else LocalContentColor.current
                )
                Text(
                    text = hfReadmePageTitle,
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(
                onClick = { hfReadmePageVisible = false }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = if (isDark) Color.White else colorResource(id = R.color.text_primary)
                )
            }
        }

        // Content
        if (hfReadmeLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SvgSpinner()
                Text("READMEを読み込み中...", modifier = Modifier.padding(top = 8.dp), color = if (isDark) Color.White else LocalContentColor.current)
            }
        } else if (hfReadmeError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("エラー: ${hfReadmeError}", color = if (isDark) Color.White else colorResource(id = R.color.text_primary))
            }
        } else if (hfReadmeText != null) {
            CompositionLocalProvider(
                LocalContentColor provides (if (isDark) androidx.compose.ui.graphics.Color.White else LocalContentColor.current)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    MarkdownLatexText(
                        text = hfReadmeText!!,
                        modifier = Modifier.fillMaxWidth(),
                        linkStyle = linkStyle
                    )
                }
            }
        }
    }
}
