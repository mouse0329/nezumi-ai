package com.nezumi_ai.data.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
data class ImageModel(
    val id: String,
    val name: String,
    val displayName: String,
    val backend: String,

    val variant: String? = null,
    val downloadUrl: String,
    val fileName: String,
    val size: Long,
    val repo: String
)

object ImageModelBrowser {
    private const val REPO_MNN = "xororz/sd-mnn"

    
    private var cachedModels: List<ImageModel>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_TTL = 5 * 60 * 1000L
    
    suspend fun fetchAvailableModels(forceRefresh: Boolean = false): Result<List<ImageModel>> = runCatching {

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
        
        models.sortBy { it.name }
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
        
        return ParsedModel(
            id = "${baseName.lowercase()}_cpu",
            name = baseName,
            displayName = "${insertSpaces(baseName)} (MNN)"

        )
    }
    
    private fun insertSpaces(name: String): String {
        return name.replace(Regex("([a-z\\d])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }
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
