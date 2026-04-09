package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nezumi_ai.R
import com.nezumi_ai.utils.PreferencesHelper

@Composable
fun ThemeModeCard(
    currentMode: String,
    onModeSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.theme_mode_label),
            style = MaterialTheme.typography.titleMedium,
            color = colorResource(id = R.color.text_primary)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeChip(
                label = stringResource(id = R.string.theme_mode_system),
                selected = currentMode == PreferencesHelper.THEME_SYSTEM,
                onClick = { onModeSelected(PreferencesHelper.THEME_SYSTEM) }
            )
            ThemeChip(
                label = stringResource(id = R.string.theme_mode_light),
                selected = currentMode == PreferencesHelper.THEME_LIGHT,
                onClick = { onModeSelected(PreferencesHelper.THEME_LIGHT) }
            )
            ThemeChip(
                label = stringResource(id = R.string.theme_mode_dark),
                selected = currentMode == PreferencesHelper.THEME_DARK,
                onClick = { onModeSelected(PreferencesHelper.THEME_DARK) }
            )
        }
    }
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colorResource(id = R.color.primary_light),
            selectedLabelColor = colorResource(id = R.color.text_primary),
            containerColor = colorResource(id = R.color.surface_card),
            labelColor = colorResource(id = R.color.text_secondary)
        )
    )
}
