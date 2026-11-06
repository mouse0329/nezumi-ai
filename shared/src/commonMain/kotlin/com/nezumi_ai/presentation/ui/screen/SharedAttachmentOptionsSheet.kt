package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SharedAttachmentOptionsSheet(
    imageIcon: Painter,
    cameraIcon: Painter,
    fileIcon: Painter,
    title: String,
    imageLabel: String,
    cameraLabel: String,
    fileLabel: String,
    cancelLabel: String,
    thinkingLabel: String,
    effortLabels: Map<String, String>,
    imageEnabled: Boolean,
    onImageClick: () -> Unit,
    onCameraClick: () -> Unit,
    onFileClick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    thinkingOn: Boolean? = null,
    thinkingSwitchable: Boolean = true,
    thinkingEffort: String = "",
    effortLevels: List<String> = emptyList(),
    onThinkingChange: (Boolean) -> Unit = {},
    onEffortChange: (String) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp)
    ) {
        Box(
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp).width(36.dp).height(4.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
        )
        if (thinkingOn != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp)
            ) {
                Text(thinkingLabel, fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = thinkingOn,
                    onCheckedChange = onThinkingChange,
                    enabled = thinkingSwitchable,
                    modifier = Modifier.padding(start = 12.dp)
                )
                if (effortLevels.isNotEmpty()) {
                    EffortSegments(
                        selected = thinkingEffort,
                        levels = effortLevels,
                        labels = effortLabels,
                        onSelect = onEffortChange,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = 14.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AttachmentTile(imageIcon, imageLabel, imageEnabled, onImageClick, Modifier.weight(1f))
            AttachmentTile(cameraIcon, cameraLabel, imageEnabled, onCameraClick, Modifier.weight(1f))
            AttachmentTile(fileIcon, fileLabel, true, onFileClick, Modifier.weight(1f))
        }
        Text(
            text = cancelLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                .clickable(onClick = onCancel).padding(vertical = 14.dp)
        )
    }
}

@Composable
private fun EffortSegments(
    selected: String,
    levels: List<String>,
    labels: Map<String, String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveSelected = selected.takeIf { it in levels } ?: levels.firstOrNull()
    Row(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
    ) {
        levels.forEachIndexed { index, level ->
            Text(
                text = labels[level] ?: level,
                color = if (level == effectiveSelected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(
                    if (level == effectiveSelected) MaterialTheme.colorScheme.surfaceVariant
                    else Color.Transparent
                ).clickable { onSelect(level) }.padding(horizontal = 10.dp, vertical = 7.dp)
            )
            if (index < levels.lastIndex) {
                Box(
                    modifier = Modifier.width(1.dp).height(32.dp)
                        .align(Alignment.CenterVertically)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    icon: Painter,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.38f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    }
}
