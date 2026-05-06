package com.nezumi_ai.presentation.ui.fragment

import androidx.compose.material3.ExperimentalMaterial3Api
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

    Column(
        Modifier
            .fillMaxSize()
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        Column(Modifier.padding(16.dp)) {
            Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.image_gen_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                stringResource(R.string.image_gen_screen_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        Column(
            Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState()),
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
        }
    }
}
