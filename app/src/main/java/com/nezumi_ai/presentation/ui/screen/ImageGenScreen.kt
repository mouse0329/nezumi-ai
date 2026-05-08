package com.nezumi_ai.presentation.ui.screen

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModel
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModelFactory

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
    
    Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
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
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E))) {
            Box(
                Modifier.weight(1f).clickable { onSelect(0) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "生成",
                    color = if (selected == 0) Color(0xFF0084FF) else Color(0xFF999999),
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
                    "ライブラリ",
                    color = if (selected == 1) Color(0xFF0084FF) else Color(0xFF999999),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
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
            color = if (active) Color(0xFF0084FF) else Color(0xFF999999),
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
                        .background(Color(0xFF2A2A2A)).padding(10.dp)
                ) {
                    Text(
                        models.getOrNull(selectedIdx)?.substringAfterLast("/") ?: "未選択",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                Text(
                    "🛰️ $backendInfo",
                    color = Color(0xFF999999),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text("モデルが見つかりません", color = Color(0xFF999999), fontSize = 14.sp)
            }
        }
        
        FieldGroup("プロンプト") {
            OutlinedTextField(
                value = prompt,
                onValueChange = vm::setPrompt,
                modifier = Modifier.fillMaxWidth().height(80.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF2A2A2A),
                    unfocusedContainerColor = Color(0xFF2A2A2A),
                    focusedBorderColor = Color(0xFF0084FF),
                    unfocusedBorderColor = Color(0xFF444444)
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
        
        TextButton(onClick = { negExpanded = !negExpanded }) {
            Text(
                "${if (negExpanded) "▼" else "▶"} ネガティブプロンプト",
                color = Color(0xFF0084FF),
                fontSize = 14.sp
            )
        }
        
        AnimatedVisibility(negExpanded) {
            OutlinedTextField(
                value = negPrompt,
                onValueChange = vm::setNegativePrompt,
                placeholder = { Text("low quality, blurry...", color = Color(0xFF666666)) },
                modifier = Modifier.fillMaxWidth().height(80.dp).padding(bottom = 15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF2A2A2A),
                    unfocusedContainerColor = Color(0xFF2A2A2A),
                    focusedBorderColor = Color(0xFF0084FF),
                    unfocusedBorderColor = Color(0xFF444444)
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
        
        FieldGroup("サイズ") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A2A2A)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SizeTab("256x256", sizePx == 256) { vm.setSize(256) }
                SizeTab("512x512", sizePx == 512) { vm.setSize(512) }
                SizeTab("768x768", sizePx == 768) { vm.setSize(768) }
            }
        }
        
        Button(
            onClick = { if (loading) vm.cancel() else vm.generate() },
            modifier = Modifier.padding(vertical = 20.dp).width(120.dp).height(40.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (loading) Color(0xFF666666) else Color(0xFF0084FF)
            ),
            shape = RoundedCornerShape(25.dp)
        ) {
            Text(
                if (loading) "中止" else "生成",
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
                "Step $currentStep / $steps",
                color = Color(0xFF999999),
                fontSize = 12.sp
            )
        }
        
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
                    ActionButton("保存", Modifier.weight(1f)) { vm.saveToGallery(context) }
                    ActionButton("共有", Modifier.weight(1f)) { vm.share(context) }
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
            color = Color(0xFF999999),
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
            color = if (active) Color.White else Color(0xFF999999),
            fontSize = 13.sp,
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
            .background(Color(0xFF1E1E1E))
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
            color = Color(0xFF999999),
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
    
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xF2000000)).clickable { onClose() }
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
                    "Prompt:\n${img.prompt}",
                    color = Color(0xFFEEEEEE),
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { vm.share(context) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0084FF)),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("共有", fontWeight = FontWeight.Bold)
                    }
                }
                
                Button(
                    onClick = onClose,
                    modifier = Modifier.padding(top = 20.dp).width(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text("閉じる")
                }
            }
        }
    }
}
