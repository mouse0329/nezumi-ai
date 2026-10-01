package com.nezumi_ai.presentation.ui.fragment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.preset.PresetConstants
import com.nezumi_ai.data.preset.PresetModelOption
import java.io.File

internal enum class PresetPickerTab { PRESET, MODEL }

internal data class ModelNameMeta(
    val groupKey: String,
    val quant: String,
    val size: String
)

internal fun modelNameMeta(modelId: String): ModelNameMeta {
    val fileName = modelId.substringAfterLast('/').substringAfterLast('\\')
    val base = fileName
        .replace(Regex("^[a-z0-9]+-community[./]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^[^/]+/"), "")
    val token = base.split(Regex("[-._ ]")).firstOrNull().orEmpty()
    val key = token.lowercase().replace(Regex("\\d+$"), "").ifBlank { token.lowercase() }
        .ifBlank { "other" }
    val quant = Regex("(?:^|[-._])(iq\\d|q\\d(?:_[0-9a-z]+)?|f32|f16|bf16)", RegexOption.IGNORE_CASE)
        .find(base)?.groupValues?.getOrNull(1).orEmpty().uppercase()
    val size = Regex("(?:^|[-._])(\\d+(?:\\.\\d+)?b)(?=[-._]|$)", RegexOption.IGNORE_CASE)
        .find(base)?.groupValues?.getOrNull(1).orEmpty().uppercase()
    return ModelNameMeta(groupKey = key, quant = quant, size = size)
}

private enum class ToolBucket(val iconRes: Int, val labelRes: Int) {
    DEVICE(R.drawable.ic_access_time, R.string.preset_cat_device),
    MEMORY(R.drawable.ic_brain_24, R.string.preset_cat_memory),
    WEB(R.drawable.ic_search, R.string.preset_cat_web),
    CREATE(R.drawable.ic_description, R.string.preset_cat_create),
    OTHER(R.drawable.settings_24, R.string.preset_cat_other)
}

private fun toolBucket(toolId: String): ToolBucket = when (toolId) {
    PresetConstants.TOOL_TIME,
    PresetConstants.TOOL_BATTERY,
    PresetConstants.TOOL_ALARM,
    PresetConstants.TOOL_TIMER,
    PresetConstants.TOOL_FLASHLIGHT,
    PresetConstants.TOOL_APP_LAUNCH,
    PresetConstants.TOOL_GMAIL,
    PresetConstants.TOOL_SWITCHBOT,
    PresetConstants.TOOL_CALENDAR -> ToolBucket.DEVICE
    PresetConstants.TOOL_MEMORY,
    PresetConstants.TOOL_MEMORY_SAVE -> ToolBucket.MEMORY
    PresetConstants.TOOL_WEB_SEARCH,
    PresetConstants.TOOL_WEB_FETCH -> ToolBucket.WEB
    PresetConstants.TOOL_IMAGE_GENERATION,
    PresetConstants.TOOL_CONVERT_MD_TO_DOCUMENT -> ToolBucket.CREATE
    else -> ToolBucket.OTHER
}

@Composable
internal fun PresetPickerContent(
    tab: PresetPickerTab,
    query: String,
    presets: List<PresetEntity>,
    models: List<PresetModelOption>,
    currentPreset: PresetEntity?,
    detached: Boolean,
    overrideModelId: String,
    fallbackModelId: String,
    pins: Set<String>,
    expandedIds: Set<String>,
    modelLabel: (String) -> String,
    toolLabel: (String) -> String,
    enabledToolIds: (PresetEntity) -> List<String>,
    mcpNames: (PresetEntity) -> List<String>,
    onTab: (PresetPickerTab) -> Unit,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onTogglePin: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onSelectPreset: (PresetEntity) -> Unit,
    onSelectNone: () -> Unit,
    onClearPreset: () -> Unit,
    onResetOverride: () -> Unit,
    onSelectModel: (String) -> Unit,
    onEdit: (PresetEntity) -> Unit,
    onDelete: (PresetEntity) -> Unit
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF0B1220) else MaterialTheme.colorScheme.background
    val card = if (dark) Color(0xFF151D2E) else MaterialTheme.colorScheme.surface
    val ink = if (dark) Color(0xFFE8EEF8) else MaterialTheme.colorScheme.onBackground
    val sub = if (dark) Color(0xFF8B9BB5) else MaterialTheme.colorScheme.onSurfaceVariant
    val line = if (dark) Color(0xFF2A3548) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val accent = Color(0xFF5B9CFF)
    val accentBg = if (dark) Color(0xFF1E2F4D) else Color(0xFFE7F0FF)
    val pin = Color(0xFFFFC83D)
    val danger = Color(0xFFE5604D)
    val radius = RoundedCornerShape(14.dp)
    val radiusSm = RoundedCornerShape(10.dp)

    val activePreset = if (detached) null else currentPreset
    val changing = activePreset != null && overrideModelId.isNotBlank() && overrideModelId != activePreset.modelId
    val effectiveModelId = when {
        overrideModelId.isNotBlank() -> overrideModelId
        activePreset != null && activePreset.modelId.isNotBlank() -> activePreset.modelId
        else -> fallbackModelId
    }
    val q = query.trim()
    val filteredPresets = presets.filter { preset ->
        q.isEmpty() || preset.name.contains(q, ignoreCase = true) ||
            preset.description.contains(q, ignoreCase = true)
    }
    val filteredModels = models.filter { model ->
        q.isEmpty() || model.label.contains(q, ignoreCase = true) ||
            model.id.contains(q, ignoreCase = true) ||
            File(model.id).name.contains(q, ignoreCase = true)
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(bg)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.back),
                        tint = ink
                    )
                }
                Text(
                    text = stringResource(R.string.preset_picker_title),
                    color = ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(card)
                    .border(1.dp, line, RoundedCornerShape(12.dp))
            ) {
                PickerTab(
                    text = stringResource(R.string.preset_tab_preset),
                    selected = tab == PresetPickerTab.PRESET,
                    accent = accent,
                    accentBg = accentBg,
                    sub = sub,
                    onClick = { onTab(PresetPickerTab.PRESET) },
                    modifier = Modifier.weight(1f)
                )
                PickerTab(
                    text = stringResource(R.string.preset_tab_model),
                    selected = tab == PresetPickerTab.MODEL,
                    accent = accent,
                    accentBg = accentBg,
                    sub = sub,
                    onClick = { onTab(PresetPickerTab.MODEL) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            SelectionBar(
                title = when {
                    activePreset != null -> activePreset.name
                    tab == PresetPickerTab.MODEL || detached -> stringResource(R.string.preset_standalone_title)
                    else -> stringResource(R.string.preset_none)
                },
                modelLine = modelLabel(effectiveModelId).ifBlank { stringResource(R.string.preset_model_unselected) },
                showModelLine = tab == PresetPickerTab.MODEL || changing || activePreset == null,
                badge = when {
                    activePreset == null -> stringResource(R.string.preset_badge_none)
                    changing -> stringResource(R.string.preset_badge_changing)
                    else -> stringResource(R.string.preset_badge_default)
                },
                badgeChanged = changing,
                showReset = changing,
                showClear = activePreset != null,
                switchLabel = if (tab == PresetPickerTab.MODEL) {
                    stringResource(R.string.preset_tab_back_preset)
                } else {
                    stringResource(R.string.preset_tab_model)
                },
                card = card,
                line = line,
                ink = ink,
                sub = sub,
                accent = accent,
                accentBg = accentBg,
                pin = pin,
                radius = radiusSm,
                onReset = onResetOverride,
                onClear = onClearPreset,
                onSwitch = {
                    onTab(if (tab == PresetPickerTab.MODEL) PresetPickerTab.PRESET else PresetPickerTab.MODEL)
                }
            )
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(card)
                .border(1.dp, line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(
                        if (tab == PresetPickerTab.MODEL) R.string.preset_search_model else R.string.preset_search_name
                    ),
                    color = sub,
                    fontSize = 15.sp
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink, fontSize = 15.sp),
                cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (q.isNotEmpty()) {
            val shown = if (tab == PresetPickerTab.PRESET) filteredPresets.size else filteredModels.size
            val total = if (tab == PresetPickerTab.PRESET) presets.size else models.size
            Text(
                text = stringResource(R.string.preset_search_count, shown, total),
                color = sub,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = if (tab == PresetPickerTab.PRESET) 148.dp else 28.dp)
        ) {
            if (tab == PresetPickerTab.PRESET) {
                if (q.isNotEmpty() && filteredPresets.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(stringResource(R.string.preset_empty_no_matches), sub)
                    }
                } else {
                    item(key = "none") {
                        GroupCard(card, line, radius) {
                            NoneRow(
                                selected = activePreset == null,
                                ink = ink,
                                sub = sub,
                                accentBg = accentBg,
                                onClick = onSelectNone
                            )
                        }
                    }
                    val pinned = filteredPresets.filter { pins.contains(pinKeyPreset(it.id)) }
                    val rest = filteredPresets.filterNot { pins.contains(pinKeyPreset(it.id)) }
                    if (pinned.isNotEmpty()) {
                        item(key = "pin-h") { SectionLabel(stringResource(R.string.preset_pinned_section), sub) }
                        item(key = "pin-g") {
                            GroupCard(card, line, radius) {
                                pinned.forEachIndexed { index, preset ->
                                    PresetPickerRow(
                                        preset = preset,
                                        selected = preset.id == activePreset?.id,
                                        pinned = true,
                                        expanded = expandedIds.contains(preset.id),
                                        modelText = modelLabel(preset.modelId),
                                        toolIds = enabledToolIds(preset),
                                        mcpNames = mcpNames(preset),
                                        toolLabel = toolLabel,
                                        ink = ink,
                                        sub = sub,
                                        accent = accent,
                                        accentBg = accentBg,
                                        pin = pin,
                                        line = line,
                                        danger = danger,
                                        showDivider = index > 0,
                                        onSelect = { onSelectPreset(preset) },
                                        onPin = { onTogglePin(pinKeyPreset(preset.id)) },
                                        onExpand = { onToggleExpanded(preset.id) },
                                        onEdit = { onEdit(preset) },
                                        onDelete = { onDelete(preset) }
                                    )
                                }
                            }
                        }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "rest") {
                            GroupCard(card, line, radius) {
                                rest.forEachIndexed { index, preset ->
                                    PresetPickerRow(
                                        preset = preset,
                                        selected = preset.id == activePreset?.id,
                                        pinned = false,
                                        expanded = expandedIds.contains(preset.id),
                                        modelText = modelLabel(preset.modelId),
                                        toolIds = enabledToolIds(preset),
                                        mcpNames = mcpNames(preset),
                                        toolLabel = toolLabel,
                                        ink = ink,
                                        sub = sub,
                                        accent = accent,
                                        accentBg = accentBg,
                                        pin = pin,
                                        line = line,
                                        danger = danger,
                                        showDivider = index > 0,
                                        onSelect = { onSelectPreset(preset) },
                                        onPin = { onTogglePin(pinKeyPreset(preset.id)) },
                                        onExpand = { onToggleExpanded(preset.id) },
                                        onEdit = { onEdit(preset) },
                                        onDelete = { onDelete(preset) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (filteredModels.isEmpty()) {
                item(key = "empty-m") {
                    EmptyState(stringResource(R.string.preset_empty_no_matches), sub)
                }
            } else {
                val pinned = filteredModels.filter { pins.contains(pinKeyModel(it.id)) }
                val rest = filteredModels.filterNot { pins.contains(pinKeyModel(it.id)) }
                if (pinned.isNotEmpty()) {
                    item(key = "mpin-h") { SectionLabel(stringResource(R.string.preset_pinned_section), sub) }
                    item(key = "mpin-g") {
                        GroupCard(card, line, radius) {
                            pinned.forEachIndexed { index, model ->
                                ModelPickerRow(
                                    model = model,
                                    inUse = model.id == effectiveModelId,
                                    pinned = true,
                                    ink = ink,
                                    sub = sub,
                                    accent = accent,
                                    accentBg = accentBg,
                                    pin = pin,
                                    line = line,
                                    showDivider = index > 0,
                                    onSelect = { onSelectModel(model.id) },
                                    onPin = { onTogglePin(pinKeyModel(model.id)) }
                                )
                            }
                        }
                    }
                }
                val grouped = rest.groupBy { modelNameMeta(it.id).groupKey }
                grouped.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { key ->
                    val rows = grouped.getValue(key)
                    item(key = "mh-$key") {
                        SectionLabel(
                            if (key == "other") stringResource(R.string.preset_group_other) else key.uppercase(),
                            sub
                        )
                    }
                    item(key = "mg-$key") {
                        GroupCard(card, line, radius) {
                            rows.forEachIndexed { index, model ->
                                ModelPickerRow(
                                    model = model,
                                    inUse = model.id == effectiveModelId,
                                    pinned = false,
                                    ink = ink,
                                    sub = sub,
                                    accent = accent,
                                    accentBg = accentBg,
                                    pin = pin,
                                    line = line,
                                    showDivider = index > 0,
                                    onSelect = { onSelectModel(model.id) },
                                    onPin = { onTogglePin(pinKeyModel(model.id)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
        if (tab == PresetPickerTab.PRESET) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0xFF3A6FD8))
                    .clickable(onClick = onCreate)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.preset_fab_new),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

internal fun pinKeyPreset(id: String) = "preset:$id"
internal fun pinKeyModel(id: String) = "model:$id"

@Composable
private fun PickerTab(
    text: String,
    selected: Boolean,
    accent: Color,
    accentBg: Color,
    sub: Color,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .background(if (selected) accentBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) accent else sub,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SelectionBar(
    title: String,
    modelLine: String,
    showModelLine: Boolean,
    badge: String,
    badgeChanged: Boolean,
    showReset: Boolean,
    showClear: Boolean,
    switchLabel: String,
    card: Color,
    line: Color,
    ink: Color,
    sub: Color,
    accent: Color,
    accentBg: Color,
    pin: Color,
    radius: RoundedCornerShape,
    onReset: () -> Unit,
    onClear: () -> Unit,
    onSwitch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(radius)
            .background(card)
            .border(1.dp, line, radius)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showModelLine) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Text(
                        text = stringResource(R.string.preset_model_line, modelLine),
                        color = sub,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = badge,
                        color = if (badgeChanged) pin else accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .border(1.dp, if (badgeChanged) pin else Color.Transparent, RoundedCornerShape(6.dp))
                            .background(if (badgeChanged) Color.Transparent else accentBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (showReset) {
            BarButton(stringResource(R.string.preset_action_reset), accent, onReset)
        }
        if (showClear) {
            BarButton(stringResource(R.string.preset_action_clear), accent, onClear)
        }
        BarButton(switchLabel, accent, onSwitch)
    }
}

@Composable
private fun BarButton(text: String, accent: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(1.dp, accent, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun SectionLabel(text: String, sub: Color) {
    Text(
        text = text,
        color = sub,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun GroupCard(
    card: Color,
    line: Color,
    radius: RoundedCornerShape,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(radius)
            .background(card)
            .border(1.dp, line, radius)
    ) {
        content()
    }
}

@Composable
private fun EmptyState(text: String, sub: Color) {
    Text(
        text = text,
        color = sub,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 48.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun NoneRow(
    selected: Boolean,
    ink: Color,
    sub: Color,
    accentBg: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) accentBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chat_24),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(stringResource(R.string.preset_none), color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.preset_none_sub), color = sub, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun PresetPickerRow(
    preset: PresetEntity,
    selected: Boolean,
    pinned: Boolean,
    expanded: Boolean,
    modelText: String,
    toolIds: List<String>,
    mcpNames: List<String>,
    toolLabel: (String) -> String,
    ink: Color,
    sub: Color,
    accent: Color,
    accentBg: Color,
    pin: Color,
    line: Color,
    danger: Color,
    showDivider: Boolean,
    onSelect: () -> Unit,
    onPin: () -> Unit,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val counts = toolIds.groupingBy { toolBucket(it) }.eachCount()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) accentBg else Color.Transparent)
            .then(if (showDivider) Modifier.border(width = 0.dp, color = Color.Transparent) else Modifier)
    ) {
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(line))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preset.name,
                        color = ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (toolIds.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MiniBadge(stringResource(R.string.preset_tool_count, toolIds.size), accent, accentBg)
                    }
                    if (preset.memoryEnabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MiniBadge(stringResource(R.string.preset_memory_on), accent, accentBg)
                    }
                }
                Text(
                    text = stringResource(R.string.preset_model_line, modelText),
                    color = sub,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
                if (counts.isNotEmpty() || mcpNames.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        counts.entries.sortedBy { it.key.ordinal }.forEach { (bucket, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(bucket.iconRes),
                                    contentDescription = stringResource(bucket.labelRes),
                                    tint = sub,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("$count", color = sub, fontSize = 11.sp)
                            }
                        }
                        if (mcpNames.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.plumbing_24),
                                    contentDescription = stringResource(R.string.preset_edit_mcp_server_label),
                                    tint = sub,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("${mcpNames.size}", color = sub, fontSize = 11.sp)
                            }
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(
                        if (toolIds.isEmpty()) stringResource(R.string.preset_tools_off) else stringResource(R.string.preset_tools_on),
                        sub,
                        line
                    )
                    if (preset.memoryEnabled) {
                        Tag(stringResource(R.string.preset_memory_on), sub, line)
                    }
                }
                if (expanded) {
                    Text(
                        text = stringResource(
                            R.string.preset_status_memory,
                            stringResource(if (preset.memoryEnabled) R.string.status_on else R.string.status_off)
                        ),
                        color = sub,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    ToolBucket.entries.forEach { bucket ->
                        val names = toolIds.filter { toolBucket(it) == bucket }
                        if (names.isEmpty()) return@forEach
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Tag(stringResource(bucket.labelRes), sub, line)
                            names.forEach { id ->
                                Tag(toolLabel(id), accent, accent.copy(alpha = 0.45f))
                            }
                        }
                    }
                    if (mcpNames.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Tag(stringResource(R.string.preset_edit_mcp_server_label), sub, line)
                            mcpNames.forEach { name ->
                                Tag(name, accent, accent.copy(alpha = 0.45f))
                            }
                        }
                    }
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(
                            text = stringResource(R.string.preset_action_edit),
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 4.dp)
                        )
                        if (!preset.isLocked && !preset.isDefault) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = danger,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(onClick = onDelete).padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            Column {
                Icon(
                    painter = painterResource(if (expanded) R.drawable.expand_more_24 else R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.preset_action_details),
                    tint = sub,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f }
                        .clickable(onClick = onExpand)
                        .padding(4.dp)
                )
                Icon(
                    painter = painterResource(if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_outline),
                    contentDescription = stringResource(R.string.preset_action_pin),
                    tint = if (pinned) pin else sub,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onPin)
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    model: PresetModelOption,
    inUse: Boolean,
    pinned: Boolean,
    ink: Color,
    sub: Color,
    accent: Color,
    accentBg: Color,
    pin: Color,
    line: Color,
    showDivider: Boolean,
    onSelect: () -> Unit,
    onPin: () -> Unit
) {
    val meta = modelNameMeta(model.id)
    Column(modifier = Modifier.fillMaxWidth().background(if (inUse) accentBg else Color.Transparent)) {
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(line))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.label.ifBlank { File(model.id).name.ifBlank { model.id } },
                        color = ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (inUse) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MiniBadge(stringResource(R.string.preset_badge_in_use), accent, accentBg)
                    }
                }
                val tags = listOf(meta.size, meta.quant).filter { it.isNotBlank() }
                if (tags.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        tags.forEach { Tag(it, sub, line) }
                    }
                }
            }
            Icon(
                painter = painterResource(if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_outline),
                contentDescription = stringResource(R.string.preset_action_pin),
                tint = if (pinned) pin else sub,
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onPin)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun MiniBadge(text: String, accent: Color, accentBg: Color) {
    Text(
        text = text,
        color = accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(accentBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun Tag(text: String, color: Color, border: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
