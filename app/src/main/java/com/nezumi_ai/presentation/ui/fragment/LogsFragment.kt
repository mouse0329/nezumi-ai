package com.nezumi_ai.presentation.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.entity.ToolCallHistoryEntity
import com.nezumi_ai.data.repository.ToolCallHistoryRepository
import com.nezumi_ai.utils.LogcatRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    LogsScreen(onBack = { runCatching { findNavController().popBackStack() } })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.logs_tab_tool_history),
        stringResource(R.string.logs_tab_logcat)
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                }
            }
            when (selectedTab) {
                0 -> ToolHistoryTab(context)
                else -> LogcatTab(context)
            }
        }
    }
}

@Composable
private fun ToolHistoryTab(context: Context) {
    val db = remember { NezumiAiDatabase.getInstance(context) }
    val repo = remember { ToolCallHistoryRepository(db.toolCallHistoryDao(), db.chatSessionDao()) }
    val items by repo.observeRecent(500).collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var filtered by remember { mutableStateOf<List<ToolCallHistoryEntity>?>(null) }
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    scope.launch {
                        filtered = if (it.isBlank()) null
                        else withContext(Dispatchers.IO) { repo.search(it.trim()) }
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.logs_tool_history_query)) }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { repo.clearAll() }
                    filtered = null
                    query = ""
                    Toast.makeText(context, context.getString(R.string.logs_tool_history_cleared), Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.logs_clear_tool_history)) }
        }
        Spacer(Modifier.height(8.dp))
        val display = filtered ?: items
        if (display.isEmpty()) {
            Text(stringResource(R.string.logs_tool_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(display, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(timeFmt.format(Date(row.timestamp)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("${stringResource(R.string.logs_tool_history_tool)}: ${row.toolName}", fontWeight = FontWeight.Bold)
                            Text("${stringResource(R.string.logs_tool_history_session)}: " + (row.sessionName?.takeIf { it.isNotBlank() } ?: "#${row.sessionId}"))
                            if (!row.query.isNullOrBlank()) {
                                Text("${stringResource(R.string.logs_tool_history_query)}: ${row.query}", fontSize = 13.sp)
                            }
                            Text(if (row.success) "OK" else "FAIL", color = if (row.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            if (!row.resultSummary.isNullOrBlank()) {
                                Text(row.resultSummary!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatTab(context: Context) {
    var logText by remember { mutableStateOf("") }
    var sizeLabel by remember { mutableStateOf("") }
    var autoRefresh by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    fun refresh() {
        logText = LogcatRecorder.readAllLogs(context)
        sizeLabel = "%.1f KB".format(LogcatRecorder.totalSizeBytes(context) / 1024.0)
    }
    LaunchedEffect(autoRefresh) {
        refresh()
        while (autoRefresh) {
            delay(1500)
            refresh()
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(stringResource(R.string.settings_logcat_title), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_logcat_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.settings_logcat_size, sizeLabel), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh() }) { Text("Refresh") }
            Button(onClick = { autoRefresh = !autoRefresh }) {
                Text(if (autoRefresh) stringResource(R.string.settings_logcat_auto_on) else stringResource(R.string.settings_logcat_auto_off))
            }
            Button(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("logcat", logText))
                Toast.makeText(context, context.getString(R.string.settings_logcat_copied), Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
            Button(onClick = {
                runCatching {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, logText)
                    }
                    context.startActivity(Intent.createChooser(share, context.getString(R.string.settings_logcat_export_title)))
                }
            }) { Text("Export") }
            Button(onClick = { LogcatRecorder.clearAll(context); refresh() }) {
                Text(stringResource(R.string.settings_debug_clear_log))
            }
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = logText.ifBlank { stringResource(R.string.settings_logcat_empty) },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(scroll)
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
            )
        }
    }
}
