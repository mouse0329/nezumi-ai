package com.nezumi_ai.data.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
data class ImageModel(
    val id: String,
    val name: String,
    val displayName: String,
    val backend: String, // "mnn" or "qnn"
    val variant: String? = null,
    val downloadUrl: String,
    val fileName: String,
    val size: Long,
    val repo: String
)

object ImageModelBrowser {
    private const val REPO_MNN = "xororz/sd-mnn"
    private const val REPO_QNN = "xororz/sd-qnn"
    
    private val variantLabels = mapOf(
        "min" to "非フラッグシップSnapdragon向け",
        "8gen1" to "Snapdragon 8 Gen 1向け",
        "8gen2" to "Snapdragon 8 Gen 2/3/4/5向け"
    )
    
    private var cachedModels: List<ImageModel>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_TTL = 5 * 60 * 1000L
    
    suspend fun fetchAvailableModels(forceRefresh: Boolean = false, skipQnn: Boolean = false): Result<List<ImageModel>> = runCatching {
        if (!forceRefresh && cachedModels != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL) {
            return@runCatching cachedModels!!
        }
        
        val models = mutableListOf<ImageModel>()
        
        val mnnFiles = fetchRepoFiles(REPO_MNN).getOrThrow()
        for (entry in mnnFiles) {
            if (entry.type != "file") continue
            val parsed = parseFileName(entry.path, "mnn") ?: continue
            models.add(ImageModel(
                id = parsed.id,
                name = parsed.name,
                displayName = parsed.displayName,
                backend = "mnn",
                variant = null,
                downloadUrl = "https://huggingface.co/$REPO_MNN/resolve/main/${entry.path}",
                fileName = entry.path,
                size = entry.lfs?.size ?: entry.size,
                repo = REPO_MNN
            ))
        }
        
        if (!skipQnn) {
            val qnnFiles = fetchRepoFiles(REPO_QNN).getOrThrow()
            for (entry in qnnFiles) {
                if (entry.type != "file") continue
                val parsed = parseFileName(entry.path, "qnn") ?: continue
                models.add(ImageModel(
                    id = parsed.id,
                    name = parsed.name,
                    displayName = parsed.displayName,
                    backend = "qnn",
                    variant = parsed.variant,
                    downloadUrl = "https://huggingface.co/$REPO_QNN/resolve/main/${entry.path}",
                    fileName = entry.path,
                    size = entry.lfs?.size ?: entry.size,
                    repo = REPO_QNN
                ))
            }
        }
        
        models.sortWith(compareBy<ImageModel> { if (it.backend == "mnn") 0 else 1 }.thenBy { it.name })
        
        cachedModels = models
        cacheTimestamp = System.currentTimeMillis()
        models
    }
    
    @Serializable
    private data class TreeEntry(
        val type: String,
        val path: String,
        val size: Long,
        val lfs: LfsInfo? = null
    )

    @Serializable
    private data class LfsInfo(
        val oid: String? = null,
        val size: Long = 0,
        val pointerSize: Long = 0
    )
    
    private data class ParsedModel(
        val id: String,
        val name: String,
        val displayName: String,
        val variant: String? = null
    )
    
    private suspend fun fetchRepoFiles(repo: String): Result<List<TreeEntry>> = runCatching {
        val url = "https://huggingface.co/api/models/$repo/tree/main"
        val response = java.net.URL(url).openConnection().apply {
            connectTimeout = 15000
            readTimeout = 15000
        }.getInputStream().bufferedReader().use { it.readText() }
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString<Array<TreeEntry>>(response).toList()
    }
    
    private fun parseFileName(fileName: String, backend: String): ParsedModel? {
        if (!fileName.endsWith(".zip")) return null
        
        val baseName = fileName.removeSuffix(".zip")
        
        if (backend == "qnn") {
            val match = Regex("^(.+?)_qnn[\\d.]+_(.+)$").find(baseName) ?: return null
            val (name, variant) = match.destructured
            val displayVariant = if (variant == "min") "非フラッグシップ" else variant
            return ParsedModel(
                id = "${name.lowercase()}_npu_$variant",
                name = name,
                displayName = "${insertSpaces(name)} (NPU $displayVariant)",
                variant = variant
            )
        }
        
        return ParsedModel(
            id = "${baseName.lowercase()}_cpu",
            name = baseName,
            displayName = "${insertSpaces(baseName)} (GPU)"
        )
    }
    
    private fun insertSpaces(name: String): String {
        return name.replace(Regex("([a-z\\d])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }
    
    fun getVariantLabel(variant: String?): String? = variant?.let { variantLabels[it] }
    
    fun guessStyle(name: String): String {
        val lower = name.lowercase()
        return if (lower.contains("reality") || lower.contains("realistic") || 
                   lower.contains("chillout") || lower.contains("photo")) {
            "photorealistic"
        } else {
            "anime"
        }
    }
}
