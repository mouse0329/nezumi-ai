package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.dao.AlarmDao
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.inference.NezumiTool
import com.nezumi_ai.data.inference.ToolPreferences
import com.nezumi_ai.data.tools.ToolSystemController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

class ToolsSettingsFragment : Fragment() {
    private lateinit var alarmDao: AlarmDao
    private lateinit var toolPreferences: ToolPreferences

    private var managedAlarms by mutableStateOf<List<AlarmEntity>>(emptyList())
    private var toolEnabled by mutableStateOf<Map<NezumiTool, Boolean>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        alarmDao = db.alarmDao()
        toolPreferences = ToolPreferences(requireContext())
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                ToolsScreen()
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadToolSettings()
        observeManagedAlarms()
    }

    @Composable
    private fun ToolsScreen() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { findNavController().navigateUp() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                    Text(
                        text = "ツール",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorResource(id = R.color.text_primary),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item { ToolSettingsCard() }
            item { AlarmSettingsCard() }
        }
    }

    @Composable
    private fun ToolSettingsCard() {
        val setAlarmEnabled = toolEnabled[NezumiTool.SET_ALARM] ?: false
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "ツール設定", fontWeight = FontWeight.Bold)
                NezumiTool.entries.forEach { tool ->
                    val enabled = toolEnabled[tool] ?: (tool == NezumiTool.GET_TIME)
                    val canToggle = tool != NezumiTool.LIST_ALARMS || setAlarmEnabled
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = tool.displayName, color = colorResource(id = R.color.text_primary))
                        Switch(
                            checked = enabled,
                            enabled = canToggle,
                            onCheckedChange = { checked -> updateToolEnabled(tool, checked) }
                        )
                    }
                    if (tool == NezumiTool.LIST_ALARMS && !setAlarmEnabled) {
                        Text(
                            text = "「アラームセット」が有効な場合のみ使用できます",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AlarmSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "アラーム管理", fontWeight = FontWeight.Bold)
                if (managedAlarms.isEmpty()) {
                    Text("登録されたアラームはありません", color = colorResource(id = R.color.text_secondary))
                } else {
                    managedAlarms.forEach { alarm ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%02d:%02d %s",
                                    alarm.hour,
                                    alarm.minute,
                                    alarm.label
                                ),
                                color = colorResource(id = R.color.text_primary)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Switch(
                                    checked = alarm.enabled,
                                    onCheckedChange = { checked ->
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            alarmDao.setEnabled(alarm.id, checked)
                                        }
                                    }
                                )
                                TextButton(onClick = { dismissAndDeleteAlarm(alarm) }) {
                                    Text(stringResource(id = R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadToolSettings() {
        val loaded = NezumiTool.entries.associateWith { toolPreferences.isEnabled(it) }
        toolEnabled = loaded
    }

    private fun updateToolEnabled(tool: NezumiTool, enabled: Boolean) {
        if (tool == NezumiTool.LIST_ALARMS && !(toolEnabled[NezumiTool.SET_ALARM] ?: true) && enabled) {
            toast("「アラームセット」を有効化してください")
            return
        }
        toolPreferences.setEnabled(tool, enabled)
        if (tool == NezumiTool.SET_ALARM && !enabled) {
            toolPreferences.setEnabled(NezumiTool.LIST_ALARMS, false)
        }
        loadToolSettings()
        if (enabled && tool == NezumiTool.SET_ALARM) {
            toast("アラームセットが有効になりました")
        }
    }

    private fun observeManagedAlarms() {
        viewLifecycleOwner.lifecycleScope.launch {
            alarmDao.observeAll().collect { rows ->
                managedAlarms = rows
            }
        }
    }

    private fun dismissAndDeleteAlarm(alarm: AlarmEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            ToolSystemController.dismissAlarm(requireContext(), alarm.hour, alarm.minute)
            alarmDao.deleteById(alarm.id)
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        val bg = colorResource(id = R.color.bg_session_list)
        val primary = colorResource(id = R.color.primary)
        val onPrimary = colorResource(id = R.color.nezumi_on_primary)
        val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
        val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
        val surface = colorResource(id = R.color.surface_card)
        val onSurface = colorResource(id = R.color.text_primary)
        val onSurfaceVariant = colorResource(id = R.color.text_secondary)

        val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
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
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }
}
