package com.nezumi_ai.presentation.ui.fragment

import android.os.Environment
import android.os.StatFs
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.media.VideoAttachmentEncoding
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.sd.SdModelLayout
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

private data class ManagedFile(val file: File, val label: String, val builtIn: ModelFileManager.LocalModel? = null, val dependencies: List<File> = emptyList())
private data class SessionImages(val sessionId: Long, val name: String, val pinned: Boolean, val count: Int, val bytes: Long)

/** アプリが管理するモデルとキャッシュの使用量を確認・削除する設定ページ。 */
@Composable
fun StorageManagementSection(onOpenSession: (Long) -> Unit) {
    val context = LocalContext.current
    val repository = remember { MessageRepository(NezumiAiDatabase.getInstance(context).messageDao()) }
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ManagedFile>>(emptyList()) }
    var caches by remember { mutableStateOf<List<ManagedFile>>(emptyList()) }
    var freeBytes by remember { mutableStateOf(0L) }
    var imageBytes by remember { mutableStateOf(0L) }
    var imageCount by remember { mutableStateOf(0) }
    var sessionImages by remember { mutableStateOf<List<SessionImages>>(emptyList()) }
    var pendingImageSession by remember { mutableStateOf<SessionImages?>(null) }
    var leaks by remember { mutableStateOf<List<ManagedFile>>(emptyList()) }
    var clearLeaks by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ManagedFile?>(null) }
    var clearCaches by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        val builtInAndCustom = ModelFileManager.LocalModel.entries
            .filter { ModelFileManager.isDownloaded(context, it) }
            .map { ManagedFile(ModelFileManager.modelFile(context, it), it.name, it) } +
            ModelFileManager.listImportedTaskModels(context).map { imported ->
                val mmproj = ImportedModelCapabilityStore.get(context, imported.path).mmprojPath
                    ?.let(::File)?.takeIf { it.isFile }
                ManagedFile(File(imported.path), imported.shortDisplayName, dependencies = listOfNotNull(mmproj))
            }
        val imageGenerationModels = File(context.filesDir, "sd_models").listFiles().orEmpty()
            .filter { it.isDirectory && SdModelLayout.validate(it).isUsable }
            .map { ManagedFile(it, "Image generation: ${it.name}") }
        models = (builtInAndCustom + imageGenerationModels)
            .sortedByDescending { treeSize(it.file) + it.dependencies.sumOf(::treeSize) }
        caches = context.cacheDir.listFiles().orEmpty()
            .filter { treeSize(it) > 0L }
            .sortedByDescending(::treeSize)
            .map { ManagedFile(it, it.name.ifBlank { "cache" }) }
        freeBytes = StatFs(Environment.getDataDirectory().path).availableBytes
        val messageGroups = repository.getAllMessages().groupBy { it.sessionId }
        val sessionsById = NezumiAiDatabase.getInstance(context).chatSessionDao().getAllSessions().associateBy { it.id }
        sessionImages = messageGroups.mapNotNull { (sessionId, messages) ->
            val imageUris = messages.flatMap { imageUris(it.imageUri) }
            imageUris.takeIf { it.isNotEmpty() }?.let {
                val session = sessionsById[sessionId]
                SessionImages(sessionId, session?.name?.ifBlank { "Session #$sessionId" } ?: "Session #$sessionId", session?.isPinned == true, it.size, it.sumOf { uri -> imageSize(context, uri) })
            }
        }.sortedByDescending { it.bytes }
        val imageUris = sessionImages.flatMap { group -> messageGroups[group.sessionId].orEmpty().flatMap { imageUris(it.imageUri) } }
        imageCount = imageUris.size
        imageBytes = imageUris.sumOf { imageSize(context, it) }
        leaks = findLeaks(context)
    }

    val modelBytes = models.sumOf { treeSize(it.file) + it.dependencies.sumOf(::treeSize) }
    val cacheBytes = caches.sumOf { treeSize(it.file) }
    val leakBytes = leaks.sumOf { treeSize(it.file) }
    val used = modelBytes + cacheBytes + imageBytes + leakBytes
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StorageCard(stringResource(R.string.storage_overview)) {
            StorageRow(stringResource(R.string.storage_app_data), formatStorageBytes(used))
            StorageRow(stringResource(R.string.storage_free_space), formatStorageBytes(freeBytes))
            StorageBreakdownBar(
                models = models, images = imageBytes, cache = cacheBytes, leaks = leakBytes
            )
        }
        StorageCard(stringResource(R.string.storage_models, models.size)) {
            if (models.isEmpty()) Text(stringResource(R.string.storage_no_models))
            models.forEachIndexed { index, entry -> StorageModelRow(
                label = entry.label + entry.dependencies.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.storage_mmproj) }.orEmpty(),
                size = formatStorageBytes(treeSize(entry.file) + entry.dependencies.sumOf(::treeSize)),
                color = modelColor(index), onDelete = { pending = entry }
            ) }
        }
        StorageCard(stringResource(R.string.storage_chat_images, imageCount)) {
            sessionImages.forEach { group -> StorageRow(stringResource(R.string.storage_session_images, group.name, if (group.pinned) stringResource(R.string.storage_pinned) else "", group.count), formatStorageBytes(group.bytes), delete = { pendingImageSession = group }, open = { onOpenSession(group.sessionId) }) }
            Text(stringResource(R.string.storage_images_note), style = MaterialTheme.typography.bodySmall)
        }
        StorageCard(stringResource(R.string.storage_cache, caches.size)) {
            if (caches.isEmpty()) Text(stringResource(R.string.storage_no_cache))
            caches.forEach { entry -> StorageRow(entry.label, formatStorageBytes(treeSize(entry.file)), delete = { pending = entry }) }
            if (caches.isNotEmpty()) TextButton(onClick = { clearCaches = true }) { Text(stringResource(R.string.storage_clear_cache)) }
        }
        StorageCard(stringResource(R.string.storage_leaks, leaks.size)) {
            if (leaks.isEmpty()) Text(stringResource(R.string.storage_no_leaks))
            leaks.forEach { entry -> StorageRow(entry.label, formatStorageBytes(treeSize(entry.file)), delete = { pending = entry }) }
            if (leaks.isNotEmpty()) TextButton(onClick = { clearLeaks = true }) { Text(stringResource(R.string.storage_clear_detected)) }
        }
    }
    if (pending != null || pendingImageSession != null || clearCaches || clearLeaks) AlertDialog(
        onDismissRequest = { pending = null; pendingImageSession = null; clearCaches = false; clearLeaks = false }, title = { Text("Confirm deletion") },
        text = { Text(when { clearCaches -> "All cache files will be deleted."; pendingImageSession != null -> "Image files for ${pendingImageSession!!.name}" + if (pendingImageSession!!.pinned) " (pinned session)" else "" + " will be deleted."; clearLeaks -> "All detected temporary files will be deleted."; else -> "${pending!!.label} and its linked dependencies will be deleted." }) },
        confirmButton = { Button(onClick = {
            if (pendingImageSession != null) {
                scope.launch {
                    val ok = deleteChatImages(context, repository, pendingImageSession!!.sessionId)
                    Toast.makeText(context, if (ok) "Deleted" else "Could not delete", Toast.LENGTH_SHORT).show()
                    pending = null; pendingImageSession = null; refresh++
                }
                return@Button
            }
            val ok = if (clearCaches) context.cacheDir.listFiles().orEmpty().all { it.deleteRecursively() }
                else if (clearLeaks) leaks.all { it.file.deleteRecursively() }
                else deleteManagedModel(context, pending!!, models)
            Toast.makeText(context, if (ok) "Deleted" else "Could not delete", Toast.LENGTH_SHORT).show()
            pending = null; pendingImageSession = null; clearCaches = false; clearLeaks = false; refresh++
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { pending = null; pendingImageSession = null; clearCaches = false; clearLeaks = false }) { Text("Cancel") } }
    )
}

@Composable private fun StorageCard(title: String, content: @Composable ColumnScope.() -> Unit) = Card(
    modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colorResource(R.color.surface_card))
) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, fontWeight = FontWeight.Bold, color = colorResource(R.color.text_primary)); content()
} }
@Composable private fun StorageRow(label: String, size: String, delete: (() -> Unit)? = null, open: (() -> Unit)? = null) = Row(
    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
) { Text(label, Modifier.weight(1f)); Text(size, color = colorResource(R.color.text_secondary)); if (open != null) TextButton(onClick = open) { Text(stringResource(R.string.storage_open_session)) }; if (delete != null) TextButton(onClick = delete) { Text(stringResource(R.string.storage_delete)) } }
@Composable private fun StorageModelRow(label: String, size: String, color: Color, onDelete: () -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically
) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.width(8.dp))
    Text(size, color = colorResource(R.color.text_secondary), style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onDelete) { Text(stringResource(R.string.storage_delete)) }
}
private fun treeSize(file: File): Long = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
private fun formatStorageBytes(bytes: Long) = when { bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble()); bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble()); else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0) }

@Composable private fun StorageBreakdownBar(models: List<ManagedFile>, images: Long, cache: Long, leaks: Long) {
    val modelSizes = models.map { treeSize(it.file) + it.dependencies.sumOf(::treeSize) }
    val total = (modelSizes.sum() + images + cache + leaks).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            modelSizes.forEachIndexed { index, size -> if (size > 0) Spacer(Modifier.weight(size.toFloat() / total).fillMaxHeight().background(modelColor(index))) }
            if (images > 0) Spacer(Modifier.weight(images.toFloat() / total).fillMaxHeight().background(colorResource(R.color.success)))
            if (cache > 0) Spacer(Modifier.weight(cache.toFloat() / total).fillMaxHeight().background(colorResource(R.color.text_secondary), RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)))
            if (leaks > 0) Spacer(Modifier.weight(leaks.toFloat() / total).fillMaxHeight().background(colorResource(R.color.error)))
        }
        // Model labels use one row each, so long file names never squeeze the legend off-screen.
        models.forEachIndexed { index, model -> StorageLegendLabel(modelColor(index), model.label) }
        if (images > 0) StorageLegendLabel(colorResource(R.color.success), stringResource(R.string.storage_legend_images))
        if (cache > 0) StorageLegendLabel(colorResource(R.color.text_secondary), stringResource(R.string.storage_legend_cache))
        if (leaks > 0) StorageLegendLabel(colorResource(R.color.error), stringResource(R.string.storage_legend_temporary))
    }
}
@Composable private fun StorageLegendLabel(color: Color, label: String) = Row(
    modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
) {
    Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(color))
    Spacer(Modifier.width(6.dp))
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
}
private fun modelColor(index: Int): Color {
    val paletteIndex = index % 128 // 128 distinct hues/tones before cycling.
    val hue = (paletteIndex * 137.508f) % 360f
    return Color.hsv(hue, 0.58f + (paletteIndex % 4) * 0.08f, 0.72f + (paletteIndex % 3) * 0.09f)
}

private fun imageUris(value: String?): List<String> = VideoAttachmentEncoding.split(value).second
    .filter { it.isNotBlank() && !TextFileAttachmentEncoding.isMarker(it) }
private fun imageSize(context: android.content.Context, value: String): Long = runCatching {
    val uri = Uri.parse(value)
    if (uri.scheme == "file") File(uri.path!!).length() else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
}.getOrDefault(0L)
private fun findLeaks(context: android.content.Context): List<ManagedFile> = buildList {
    context.cacheDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") || it.name.endsWith(".download") }.forEach { add(ManagedFile(it, "Partial download: ${it.name}")) }
    File(context.filesDir, "sd_models").listFiles().orEmpty().filter { it.isDirectory && it.listFiles().orEmpty().none { f -> f.name.startsWith("unet") } }.forEach { add(ManagedFile(it, "Incomplete ZIP extraction: ${it.name}")) }
}
private fun deleteManagedModel(context: android.content.Context, entry: ManagedFile, allModels: List<ManagedFile>): Boolean {
    entry.builtIn?.let { return ModelFileManager.deleteModel(context, it) }
    val sdModelsRoot = File(context.filesDir, "sd_models").canonicalPath + File.separator
    if (runCatching { entry.file.canonicalPath.startsWith(sdModelsRoot) }.getOrDefault(false)) {
        return entry.file.deleteRecursively()
    }
    val mainRemoved = ModelFileManager.deleteImportedTask(context, entry.file.path).isSuccess
    entry.dependencies.forEach { dependency -> if (allModels.none { it.file != entry.file && dependency in it.dependencies }) dependency.delete() }
    return mainRemoved
}
private suspend fun deleteChatImages(context: android.content.Context, repository: MessageRepository, sessionId: Long): Boolean = runCatching {
    repository.getAllMessages().filter { it.sessionId == sessionId && imageUris(it.imageUri).isNotEmpty() }.forEach { message ->
        imageUris(message.imageUri).forEach { MessageMediaStore.deleteStoredFileIfOwned(context, it) }
        repository.updateMessageImageWithDescription(message.id, null)
    }
}.isSuccess
