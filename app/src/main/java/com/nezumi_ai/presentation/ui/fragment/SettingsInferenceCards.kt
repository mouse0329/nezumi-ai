package com.nezumi_ai.presentation.ui.fragment

// SettingsComposeFragment から切り出した推論・画像生成の設定カード。状態は Fragment の internal プロパティを読む。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.LlamaCppGpuBackend
import kotlin.math.roundToInt
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors

@Composable
internal fun SettingsComposeFragment.BackendCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(id = R.string.settings_backend_card_title), fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(id = R.string.settings_backend_current_format, backendType),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                onClick = { versionDialogVisible = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(id = R.string.settings_inference_check_engine_version))
            }
        }
    }
}

@Composable
internal fun SettingsComposeFragment.InferenceParamsCard(settingsSearchJumpState: SettingsSearchJumpState) {
 // ユーザー要望: コンテキストウィンドウの上限を 128k まで拡張
    val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) ||
                                selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
        131072
    } else {
        131072
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_inference_params_title),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_params_title,
                    settingsSearchJumpState
                )
            )

            // コンテキストサイズと最大トークン数を2列グリッド
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = contextWindowInput,
                    onValueChange = { contextWindowInput = it },
                    label = { Text(stringResource(id = R.string.settings_inference_context_size)) },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .settingsSearchAnchor(
                            R.string.settings_inference_context_size,
                            settingsSearchJumpState
                        ),
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxTokensInput,
                    onValueChange = { maxTokensInput = it },
                    label = { Text(stringResource(id = R.string.settings_inference_max_tokens)) },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .settingsSearchAnchor(
                            R.string.settings_inference_max_tokens,
                            settingsSearchJumpState
                        ),
                    singleLine = true
                )
            }

            // Temperature Slider
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_temperature_label,
                    settingsSearchJumpState
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_inference_temperature_label),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = temperatureInput,
                        color = colorResource(id = R.color.primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Slider(
                    value = temperatureInput.toFloatOrNull() ?: 0.7f,
                    onValueChange = { temperatureInput = String.format("%.1f", it) },
                    valueRange = 0f..1.5f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Top-K Slider
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_topk_label,
                    settingsSearchJumpState
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_inference_topk_label),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = topkInput,
                        color = colorResource(id = R.color.primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Slider(
                    value = topkInput.toIntOrNull()?.toFloat() ?: 40f,
                    onValueChange = { topkInput = it.toInt().toString() },
                    valueRange = 1f..100f,
                    steps = 98,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Top-P Slider
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_topp_label,
                    settingsSearchJumpState
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_inference_topp_label),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = topPInput,
                        color = colorResource(id = R.color.primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Slider(
                    value = topPInput.toFloatOrNull() ?: 0.95f,
                    onValueChange = { topPInput = String.format("%.2f", it) },
                    valueRange = 0f..1f,
                    steps = 100,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_preload_warning_title,
                    settingsSearchJumpState
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_inference_preload_warning_title),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${preloadMemoryWarningThresholdPercent}%",
                        color = colorResource(id = R.color.primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Slider(
                    value = preloadMemoryWarningThresholdPercent.toFloat(),
                    onValueChange = { value ->
                        preloadMemoryWarningThresholdPercent = value.roundToInt().coerceIn(0, 100)
                    },
                    valueRange = 0f..100f,
                    steps = 100,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(id = R.string.settings_inference_preload_warning_desc),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // GGUF / llama.cpp 固有設定は別のカードに移動しました
        }
    }
}

@Composable
internal fun SettingsComposeFragment.GgufLlamaCppSettingsCard(settingsSearchJumpState: SettingsSearchJumpState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_gguf_title),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_gguf_title,
                    settingsSearchJumpState
                )
            )
            Text(
                text = stringResource(id = R.string.settings_gguf_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )

            var basicExpanded by remember { mutableStateOf(true) }
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { basicExpanded = !basicExpanded }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_basic_settings),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (basicExpanded) "▼" else "▶",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (basicExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val openClSelectable = llamaCppCompiledGpuBackends.contains(LlamaCppGpuBackend.OPENCL) && openClAvailable
                        val vulkanSelectable = llamaCppCompiledGpuBackends.contains(LlamaCppGpuBackend.VULKAN) && vulkanAvailable
                        val gpuOffloadEnabled = when (llamaCppGpuBackend) {
                            LlamaCppGpuBackend.OPENCL -> openClSelectable
                            LlamaCppGpuBackend.VULKAN -> vulkanSelectable
                            else -> false
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.settingsSearchAnchor(
                                R.string.settings_llamacpp_gpu_backend,
                                settingsSearchJumpState
                            )
                        ) {
                            Text(
                                text = stringResource(id = R.string.settings_llamacpp_gpu_backend),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(id = R.string.settings_llamacpp_gpu_backend_desc),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = llamaCppGpuBackend == LlamaCppGpuBackend.CPU,
                                    onClick = { llamaCppGpuBackend = LlamaCppGpuBackend.CPU },
                                    label = { Text(stringResource(id = R.string.settings_backend_cpu)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = llamaCppGpuBackend == LlamaCppGpuBackend.OPENCL,
                                    onClick = {
                                        llamaCppGpuBackend = LlamaCppGpuBackend.OPENCL
                                        if (llamaCppGpuLayers == 0) llamaCppGpuLayers = 99
                                    },
                                    enabled = openClSelectable,
                                    label = { Text(stringResource(id = R.string.settings_llamacpp_backend_opencl)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = llamaCppGpuBackend == LlamaCppGpuBackend.VULKAN,
                                    onClick = {
                                        llamaCppGpuBackend = LlamaCppGpuBackend.VULKAN
                                        if (llamaCppGpuLayers == 0) llamaCppGpuLayers = 99
                                    },
                                    enabled = vulkanSelectable,
                                    label = { Text(stringResource(id = R.string.settings_llamacpp_backend_vulkan)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (!openClSelectable || !vulkanSelectable) {
                                val unavailable = buildString {
                                    if (!openClSelectable) append("OpenCL")
                                    if (!openClSelectable && !vulkanSelectable) append(" / ")
                                    if (!vulkanSelectable) append("Vulkan")
                                }
                                Text(
                                    text = stringResource(id = R.string.settings_llamacpp_gpu_backend_unavailable, unavailable),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.settingsSearchAnchor(
                                R.string.settings_cpu_threads,
                                settingsSearchJumpState
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_cpu_threads),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = llamaCppThreads.toString(),
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = llamaCppThreads.toFloat(),
                                onValueChange = { llamaCppThreads = it.roundToInt() },
                                valueRange = 1f..maxThreads.toFloat(),
                                steps = maxOf(0, maxThreads - 2),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.settingsSearchAnchor(
                                R.string.settings_gpu_layers,
                                settingsSearchJumpState
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_gpu_layers),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = llamaCppGpuLayers.toString(),
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = llamaCppGpuLayers.toFloat(),
                                onValueChange = {
                                    if (gpuOffloadEnabled) {
                                        llamaCppGpuLayers = it.roundToInt()
                                    }
                                },
                                valueRange = 0f..128f,
                                steps = 127,
                                enabled = gpuOffloadEnabled,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (!gpuOffloadEnabled) {
                                Text(
                                    text = stringResource(id = R.string.settings_gpu_layers_backend_required),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.settingsSearchAnchor(
                                R.string.settings_batch_size,
                                settingsSearchJumpState
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_batch_size),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = llamaCppBatchSize.toString(),
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = llamaCppBatchSize.toFloat(),
                                onValueChange = { llamaCppBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                valueRange = 32f..2048f,
                                steps = 2016/32 - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_internal_batch_size),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = llamaCppUBatchSize.toString(),
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = llamaCppUBatchSize.toFloat(),
                                onValueChange = { llamaCppUBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                valueRange = 32f..2048f,
                                steps = 2016/32 - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // マルチモーダル (mtmd): --image-max-tokens 相当。
                        // 0 = デフォルト (256。従来動作と同じ)。
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_image_max_tokens),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (llamaCppImageMaxTokens <= 0) stringResource(id = R.string.settings_image_max_tokens_default) else llamaCppImageMaxTokens.toString(),
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = llamaCppImageMaxTokens.toFloat(),
                                onValueChange = { llamaCppImageMaxTokens = it.roundToInt().coerceIn(0, 8192) },
                                valueRange = 0f..8192f,
                                steps = 8192/256 - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(id = R.string.settings_image_max_tokens_desc),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = R.string.settings_kv_unified),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_kv_unified_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = llamaCppKvUnified,
                                onCheckedChange = { llamaCppKvUnified = it },
                                colors = nezumiSwitchColors()
                            )
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.settingsSearchAnchor(
                                R.string.settings_rope_base,
                                settingsSearchJumpState
                            )
                        ) {
                            Text(
                                text = stringResource(id = R.string.settings_rope_base),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = ropeFreqBaseInput,
                                onValueChange = { newValue ->
                                    ropeFreqBaseInput = newValue
                                    newValue.toFloatOrNull()?.let { llamaCppRopeFreqBase = it }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            Text(
                                text = stringResource(id = R.string.settings_rope_base_hint),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.settingsSearchAnchor(
                                R.string.settings_rope_scale,
                                settingsSearchJumpState
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_rope_scale),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = String.format("%.2f", llamaCppRopeFreqScale),
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = llamaCppRopeFreqScale,
                                onValueChange = { llamaCppRopeFreqScale = it },
                                valueRange = 0.5f..5.0f,
                                steps = 44,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(id = R.string.settings_rope_scale_hint),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            var perfExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { perfExpanded = !perfExpanded }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_performance_optimization),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (perfExpanded) "▼" else "▶",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (perfExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .settingsSearchAnchor(
                                        R.string.settings_mtp_title,
                                        settingsSearchJumpState
                                    )
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_mtp_title),
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_mtp_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = mtpEnabled,
                                onCheckedChange = { mtpEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }

                        if (mtpEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.settings_mtp_draft_tokens),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = mtpDraftTokens.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = mtpDraftTokens.toFloat(),
                                    onValueChange = { mtpDraftTokens = it.roundToInt() },
                                    valueRange = 1f..16f,
                                    steps = 14,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_mtp_recommended),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .settingsSearchAnchor(
                                        R.string.settings_flash_attention,
                                        settingsSearchJumpState
                                    )
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_flash_attention),
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_flash_attention_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = flashAttentionEnabled,
                                onCheckedChange = { flashAttentionEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .settingsSearchAnchor(
                                        R.string.settings_dynamic_batch,
                                        settingsSearchJumpState
                                    )
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_dynamic_batch),
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_dynamic_batch_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = dynamicBatchSizeEnabled,
                                onCheckedChange = { dynamicBatchSizeEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }

                        if (dynamicBatchSizeEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.settings_prompt_batch),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = promptBatchSize.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = promptBatchSize.toFloat(),
                                    onValueChange = { promptBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                    valueRange = 32f..2048f,
                                    steps = 2016/32 - 1,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.settingsSearchAnchor(
                                    R.string.settings_inference_generation_batch_title,
                                    settingsSearchJumpState
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.settings_inference_generation_batch_title),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = generationBatchSize.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = generationBatchSize.toFloat(),
                                    onValueChange = { generationBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                    valueRange = 32f..2048f,
                                    steps = 2016/32 - 1,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_inference_generation_batch_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .settingsSearchAnchor(
                                        R.string.settings_inference_kv_cache_title,
                                        settingsSearchJumpState
                                    )
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_inference_kv_cache_title),
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_inference_kv_cache_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = kvCacheOptimizationEnabled,
                                onCheckedChange = { kvCacheOptimizationEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .settingsSearchAnchor(
                                        R.string.settings_inference_context_shift_title,
                                        settingsSearchJumpState
                                    )
                            ) {
                                Text(
                                    text = stringResource(id = R.string.settings_inference_context_shift_title),
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_inference_context_shift_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = contextShiftEnabled,
                                onCheckedChange = { contextShiftEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }
                        Text(
                            text = "上級者向け llama.cpp 設定",
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            text = "モデルを再ロードすると反映されます。互換性のない値はロードに失敗する場合があります。",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = repeatPenaltyInput,
                                onValueChange = { repeatPenaltyInput = it },
                                label = { Text("Repeat penalty") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = repeatLastNInput,
                                onValueChange = { repeatLastNInput = it },
                                label = { Text("Repeat last N") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = llamaCppSeedInput,
                                onValueChange = { llamaCppSeedInput = it },
                                label = { Text("Seed (-1 = random)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = llamaCppThreadsBatch.toString(),
                                onValueChange = { llamaCppThreadsBatch = it.toIntOrNull()?.coerceIn(0, maxThreads) ?: llamaCppThreadsBatch },
                                label = { Text("Batch threads (0 = auto)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            val kvCacheTypes = listOf("f32", "f16", "bf16", "q8_0", "q4_0", "q5_0")
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { llamaCppCacheTypeKMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("KV cache K: $llamaCppCacheTypeK")
                                }
                                DropdownMenu(
                                    expanded = llamaCppCacheTypeKMenuExpanded,
                                    onDismissRequest = { llamaCppCacheTypeKMenuExpanded = false }
                                ) {
                                    kvCacheTypes.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type) },
                                            onClick = {
                                                llamaCppCacheTypeK = type
                                                llamaCppCacheTypeKMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { llamaCppCacheTypeVMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("KV cache V: $llamaCppCacheTypeV")
                                }
                                DropdownMenu(
                                    expanded = llamaCppCacheTypeVMenuExpanded,
                                    onDismissRequest = { llamaCppCacheTypeVMenuExpanded = false }
                                ) {
                                    kvCacheTypes.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type) },
                                            onClick = {
                                                llamaCppCacheTypeV = type
                                                llamaCppCacheTypeVMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        listOf(
                            Triple("mmap", llamaCppUseMmap) { v: Boolean -> llamaCppUseMmap = v },
                            Triple("mlock (RAM固定)", llamaCppUseMlock) { v: Boolean -> llamaCppUseMlock = v },
                            Triple("KVをGPUへオフロード", llamaCppOffloadKqv) { v: Boolean -> llamaCppOffloadKqv = v }
                        ).forEach { (label, checked, onChange) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                                Switch(checked = checked, onCheckedChange = onChange, colors = nezumiSwitchColors())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsComposeFragment.LiteRtSettingsCard(settingsSearchJumpState: SettingsSearchJumpState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_inference_literlm_settings_title),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_literlm_settings_title,
                    settingsSearchJumpState
                )
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_inference_backend_title,
                    settingsSearchJumpState
                )
            ) {
                Text(
                    text = stringResource(id = R.string.settings_inference_backend_title),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(id = R.string.settings_inference_backend_desc),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = backendType == "CPU",
                        onClick = { backendType = "CPU" },
                        label = { Text(stringResource(id = R.string.settings_backend_cpu)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = backendType == "GPU",
                        onClick = { backendType = "GPU" },
                        label = { Text(stringResource(id = R.string.settings_backend_gpu)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = backendType == "NPU",
                        onClick = { backendType = "NPU" },
                        label = { Text(stringResource(id = R.string.settings_backend_npu)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            TextButton(
                onClick = { versionDialogVisible = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsSearchAnchor(
                        R.string.settings_inference_check_engine_version,
                        settingsSearchJumpState
                    )
            ) {
                Text(stringResource(id = R.string.settings_inference_check_engine_version))
            }
            HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .settingsSearchAnchor(
                            R.string.settings_inference_speculative_decoding_title,
                            settingsSearchJumpState
                        )
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_inference_speculative_decoding_title),
                        color = colorResource(id = R.color.text_primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(id = R.string.settings_inference_speculative_decoding_desc),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = speculativeDecodingEnabled,
                    onCheckedChange = { speculativeDecodingEnabled = it },
                    colors = nezumiSwitchColors()
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .settingsSearchAnchor(
                            R.string.settings_require_multimodal,
                            settingsSearchJumpState
                        )
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_require_multimodal),
                        color = colorResource(id = R.color.text_primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(id = R.string.settings_require_multimodal_desc),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = requireMultimodal,
                    onCheckedChange = { requireMultimodal = it },
                    colors = nezumiSwitchColors()
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsComposeFragment.ImageGenSettingsCard(settingsSearchJumpState: SettingsSearchJumpState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_image_generation_title),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_image_generation_title,
                    settingsSearchJumpState
                )
            )

            Text(
                text = stringResource(id = R.string.settings_image_generation_desc),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )

            // ステップ数 Slider
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_steps_title,
                    settingsSearchJumpState
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_steps_title),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$sdSteps / 50",
                        color = colorResource(id = R.color.primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Slider(
                    value = sdSteps.toFloat(),
                    onValueChange = { sdSteps = it.toInt() },
                    valueRange = 1f..50f,
                    steps = 48,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // CFG Scale Slider
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_cfg_scale_title,
                    settingsSearchJumpState
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_cfg_scale_title),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = String.format("%.1f", sdCfg),
                        color = colorResource(id = R.color.primary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Slider(
                    value = sdCfg,
                    onValueChange = { sdCfg = it },
                    valueRange = 1f..20f,
                    steps = 38,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- Scheduler (コンパクトなドロップダウン) ----
            //   メインページ側の Chip を 8 個並べる UI をそのままコピーすると
            //   設定画面も縦に弸むため、ここでは 1 行の ExposedDropdownMenu に集約する。
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_scheduler_title,
                    settingsSearchJumpState
                )
            ) {
                Text(
                    text = stringResource(id = R.string.settings_scheduler_title),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                var schedulerExpanded by remember { mutableStateOf(false) }
                val schedulerOptions = remember { com.nezumi_ai.sd.SdScheduler.values().toList() }
                val currentScheduler = com.nezumi_ai.sd.SdScheduler.fromId(sdSchedulerId)
                ExposedDropdownMenuBox(
                    expanded = schedulerExpanded,
                    onExpandedChange = { schedulerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = currentScheduler.displayName,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = schedulerExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = schedulerExpanded,
                        onDismissRequest = { schedulerExpanded = false }
                    ) {
                        schedulerOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                onClick = {
                                    sdSchedulerId = option.id
                                    schedulerExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ---- Seed (デフォルト値) ----
            //   -1 (空欄) = ランダム。ここでは保存には Preferences を使わず、
            //   入力値のバリデーションとデフォルト値提示に役割を限定。
            //   (SD の実際の seed は生成タブ側のフィールドで逐回指定するフローを維持)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_seed_title,
                    settingsSearchJumpState
                )
            ) {
                Text(
                    text = stringResource(id = R.string.settings_seed_title),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = sdDefaultSeedInput,
                    onValueChange = { raw ->
                        // 数字のみ受け付け (先頭のマイナスも許容)
                        val cleaned = raw.filterIndexed { idx, c ->
                            c.isDigit() || (idx == 0 && c == '-')
                        }
                        sdDefaultSeedInput = cleaned
                    },
                    singleLine = true,
                    placeholder = { Text(stringResource(id = R.string.settings_seed_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(id = R.string.settings_seed_hint),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
