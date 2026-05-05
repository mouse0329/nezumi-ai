package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.nezumi_ai.R
import com.nezumi_ai.presentation.viewmodel.ImageGenViewModel
import java.io.File

class ImageGenFragment : Fragment() {

    private val viewModel: ImageGenViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    ImageGenScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageGenScreen(vm: ImageGenViewModel) {
    val ctx = LocalContext.current
    val prompt by vm.prompt.collectAsState()
    val neg by vm.negativePrompt.collectAsState()
    val steps by vm.steps.collectAsState()
    val cfg by vm.cfg.collectAsState()
    val size by vm.sizePx.collectAsState()
    val bitmap by vm.resultBitmap.collectAsState()
    val loading by vm.loading.collectAsState()
    val snack by vm.snackbar.collectAsState()
    val modelPath by vm.modelPath.collectAsState()

    snack?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            vm.clearSnackbar()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.image_gen_screen_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = modelPath,
            onValueChange = vm::setModelPath,
            label = { Text(stringResource(R.string.image_gen_model_path_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = vm::setPrompt,
            label = { Text(stringResource(R.string.image_gen_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        var negExpanded by remember { mutableStateOf(false) }
        TextButton(onClick = { negExpanded = !negExpanded }) {
            Text(if (negExpanded) "▼ ネガティブを隠す" else "▶ ネガティブプロンプト")
        }
        if (negExpanded) {
            OutlinedTextField(
                value = neg,
                onValueChange = vm::setNegativePrompt,
                label = { Text(stringResource(R.string.image_gen_neg_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }
        Text("Steps: $steps")
        Slider(
            value = steps.toFloat(),
            onValueChange = { vm.setSteps(it.toInt()) },
            valueRange = 1f..50f,
            steps = 48
        )
        Text("CFG: ${"%.1f".format(cfg)}")
        Slider(
            value = cfg,
            onValueChange = vm::setCfg,
            valueRange = 1f..20f,
            steps = 38
        )
        Text("サイズ: ${size}x$size")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(256, 512, 768).forEach { s ->
                Button(onClick = { vm.setSize(s) }, enabled = !loading) {
                    Text("${s}x$s")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.generate() },
                enabled = !loading && modelPath.isNotBlank() && prompt.isNotBlank() && File(modelPath).isFile
            ) {
                Text(stringResource(R.string.image_gen_generate))
            }
            if (loading) {
                CircularProgressIndicator(Modifier.height(36.dp))
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
