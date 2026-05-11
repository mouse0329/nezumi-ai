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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModel
import java.io.File

class ImageGenFragment : Fragment() {

    private val viewModel: ImageGenViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshAvailableModels()
    }

    override fun onPause() {
        super.onPause()
        // 画面が非表示になる時に生成をキャンセル
        viewModel.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Viewが破棄される時に生成をキャンセル
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
                    ImageGenScreen(viewModel, onNavigateUp = navigateUp)
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
private fun ImageGenScreen(vm: ImageGenViewModel, onNavigateUp: () -> Unit) {
    val ctx = LocalContext.current
    val prompt by vm.prompt.collectAsState()
    val neg by vm.negativePrompt.collectAsState()
    val steps by vm.steps.collectAsState()
    val cfg by vm.cfg.collectAsState()
    val size by vm.sizePx.collectAsState()
    val bitmap by vm.resultBitmap.collectAsState()
    val loading by vm.loading.collectAsState()
    val snack by vm.snackbar.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val selectedModelIndex by vm.selectedModelIndex.collectAsState()
    val currentStep by vm.currentStep.collectAsState()
    val backendInfo by vm.backendInfo.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val library = remember { mutableStateListOf<Pair<Bitmap, String>>() }
    var viewerImage by remember { mutableStateOf<Pair<Bitmap, String>?>(null) }
    
    // ライブラリの初期化（永続化から読み込み）
    LaunchedEffect(Unit) {
        val savedLibrary = loadLibrary(ctx)
        library.addAll(savedLibrary)
    }

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
            library.add(0, Pair(bmp, prompt))
            // 画像を保存
            saveImageToLibrary(ctx, bmp, prompt)
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
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E))) {
                Box(
                    Modifier.weight(1f).clickable { selectedTab = 0 }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "生成",
                        color = if (selectedTab == 0) Color(0xFF0084FF) else Color(0xFF999999),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Box(
                    Modifier.weight(1f).clickable { selectedTab = 1 }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "ライブラリ",
                        color = if (selectedTab == 1) Color(0xFF0084FF) else Color(0xFF999999),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
            Row(Modifier.fillMaxWidth().height(3.dp)) {
                if (selectedTab == 0) {
                    Box(Modifier.width(100.dp).height(3.dp).background(Color(0xFF0084FF)))
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(100.dp).height(3.dp).background(Color(0xFF0084FF)))
                }
            }
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
                
                if (backendInfo.isNotEmpty()) {
                    Text(
                        "📡 $backendInfo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = vm::setPrompt,
                    label = { Text(stringResource(R.string.image_gen_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = fieldColors
                )
                var negExpanded by remember { mutableStateOf(false) }
                TextButton(onClick = { negExpanded = !negExpanded }) {
                    Text(
                        if (negExpanded) "▼ ネガティブを隠す" else "▶ ネガティブプロンプト",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (negExpanded) {
                    OutlinedTextField(
                        value = neg,
                        onValueChange = vm::setNegativePrompt,
                        label = { Text(stringResource(R.string.image_gen_neg_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        colors = fieldColors
                    )
                }
                Text("サイズ: ${size}x$size", color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(256, 512, 768).forEach { s ->
                        Button(onClick = { vm.setSize(s) }, enabled = !loading) {
                            Text("${s}x$s")
                        }
                    }
                }
                val modelFileOk = availableModels.isNotEmpty() && selectedModelIndex in availableModels.indices && File(availableModels[selectedModelIndex]).isDirectory
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.generate() },
                        enabled = !loading && modelFileOk && prompt.isNotBlank()
                    ) {
                        Text(stringResource(R.string.image_gen_generate))
                    }
                    if (loading) {
                        CircularProgressIndicator(Modifier.height(36.dp))
                        Text(
                            "$currentStep / $steps",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { vm.cancel() }) {
                            Text(stringResource(R.string.image_gen_cancel))
                        }
                    }
                }
                bitmap?.let { b ->
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(library.size) { idx ->
                    val (bmp, libPrompt) = library[idx]
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E1E1E))
                            .clickable { viewerImage = Pair(bmp, libPrompt) }
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            libPrompt,
                            color = Color(0xFF999999),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
    
    // ビューワーダイアログ
    viewerImage?.let { (imgBitmap, imgPrompt) ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewerImage = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xF2000000)).clickable { viewerImage = null }
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(enabled = false) {}
                ) {
                    Image(
                        bitmap = imgBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                    
                    Text(
                        "Prompt:\n$imgPrompt",
                        color = Color(0xFFEEEEEE),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                    
                    Row(
                        Modifier.fillMaxWidth(0.8f),
                        horizontalArrangement = Arrangement.spacedBy(15.dp)
                    ) {
                        Button(
                            onClick = { viewerImage?.first?.let { vm.saveBitmapToGallery(ctx, it) } },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text("保存", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewerImage?.first?.let { vm.shareBitmap(ctx, it) } },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text("共有", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                    
                    Button(
                        onClick = { viewerImage = null },
                        modifier = Modifier.padding(top = 20.dp).width(160.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("閉じる")
                    }
                }
            }
        }
    }
}

// ライブラリの永続化関数
private fun saveImageToLibrary(context: android.content.Context, bitmap: Bitmap, prompt: String) {
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
    val metadata = "$timestamp|$prompt\n"
    metadataFile.appendText(metadata)
}

private fun loadLibrary(context: android.content.Context): List<Pair<Bitmap, String>> {
    val libraryDir = File(context.filesDir, "library")
    if (!libraryDir.exists()) return emptyList()
    
    val metadataFile = File(libraryDir, "metadata.txt")
    if (!metadataFile.exists()) return emptyList()
    
    val library = mutableListOf<Pair<Bitmap, String>>()
    val lines = metadataFile.readText().split("\n").filter { it.isNotEmpty() }
    
    for (line in lines.reversed()) { // 最新順に読み込み
        val parts = line.split("|", limit = 2)
        if (parts.size == 2) {
            val timestamp = parts[0]
            val libPrompt = parts[1]
            val imageFile = File(libraryDir, "img_$timestamp.jpg")
            
            if (imageFile.exists()) {
                val imageBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (imageBitmap != null) {
                    library.add(Pair(imageBitmap, libPrompt))
                }
            }
        }
    }
    
    return library
}
