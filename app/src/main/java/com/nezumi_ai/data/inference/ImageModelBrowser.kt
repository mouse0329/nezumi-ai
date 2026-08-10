package com.nezumi_ai.data.inference

import android.content.Context
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
    // 複数リポジトリに対応するためのリスト
    private val REPOS_MNN = listOf(
        "Mouserat/Illustrious-XL-v2.0-diffusers-mnn",
        "Mouserat/majicMIX_realistic_v6-mnn",
        "Mouserat/Realistic_Vision_V6.0_B1-mnn",
        "Mouserat/CuteYukiMix-mnn",
        "Mouserat/Anything-V5-mnn",
        "Mouserat/dreamshaper-8-mnn",
        "Mouserat/CyberRealistic-mnn",
        "Mouserat/ReV_Animated-mnn",
    )

    private var cachedModels: List<ImageModel>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_TTL = 5 * 60 * 1000L

    /** HTTP 401/403 が返ってきたリポジトリ数。呼び出し元で「トークン失効」表示の判定に使う。 */
    private var lastAuthErrorCount = 0

    /** 直前の fetchAvailableModels() 実行で、認証エラー（401/403）が1件以上あったか。 */
    fun hasAuthError(): Boolean = lastAuthErrorCount > 0

    suspend fun fetchAvailableModels(context: Context? = null, forceRefresh: Boolean = false): Result<List<ImageModel>> = runCatching {

        if (!forceRefresh && cachedModels != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL) {
            return@runCatching cachedModels!!
        }
        
        val models = mutableListOf<ImageModel>()
        var authErrorCount = 0
        
        // リポジトリ一覧をループ処理
        for (repo in REPOS_MNN) {
            val result = fetchRepoFiles(context, repo)
            val mnnFiles = result.getOrNull()
            if (mnnFiles == null) {
                if ((result.exceptionOrNull() as? HfApiException)?.isAuthError == true) {
                    authErrorCount++
                }
                continue
            }
            
            for (entry in mnnFiles) {
                if (entry.type != "file") continue
                val parsed = parseFileName(entry.path, "mnn") ?: continue
                
                // リポジトリごとにユニークなIDを生成（重複防止）
                val uniqueId = "${repo.replace("/", "_")}_${parsed.id}"

                models.add(
                    ImageModel(
                        id = uniqueId,
                        name = parsed.name,
                        displayName = parsed.displayName,
                        backend = "mnn",
                        variant = null,
                        downloadUrl = "https://huggingface.co/$repo/resolve/main/${entry.path}",
                        fileName = entry.path,
                        size = entry.lfs?.size ?: entry.size,
                        repo = repo
                    )
                )
            }
        }
        
        lastAuthErrorCount = authErrorCount
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

    /** HF API 呼び出し失敗時に、認証エラーかどうかを呼び出し元へ伝えるための例外。 */
    private class HfApiException(val httpCode: Int) : Exception("HF API HTTP $httpCode") {
        val isAuthError: Boolean get() = httpCode == 401 || httpCode == 403
    }
    
    private suspend fun fetchRepoFiles(context: Context?, repo: String): Result<List<TreeEntry>> = runCatching {
        val url = "https://huggingface.co/api/models/$repo/tree/main"
        // HF 連携済みの場合は必ずトークンを付与する（プライベートリポジトリや
        // レート制限対策のため）
        val token = context?.let { HfAuthManager.getToken(it) }.orEmpty()
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "nezumi-ai/1.0")
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) {
            throw HfApiException(code)
        }
        if (code !in 200..299) {
            throw HfApiException(code)
        }
        val response = conn.getInputStream().bufferedReader().use { it.readText() }
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

    /**
     * ダウンロード直前に、該当リポジトリのライセンス情報を取得する。
     *
     * 優先順位:
     *   1. リポジトリ直下の LICENSE.md（モデルごとに個別アップロードされている想定）
     *   2. README.md の YAML frontmatter `license` フィールド（種別）+ 本文全体
     *   3. どちらも取得できない場合は notFound を返す（呼び出し側でフォールバック表示）
     *
     * キャッシュはしない。ダウンロードボタンを押した時点で毎回最新を取得する。
     */
    suspend fun fetchLicenseInfo(context: Context?, repo: String): ImageModelLicenseInfo {
        val repoUrl = "https://huggingface.co/$repo"

        // 1) LICENSE.md を試す
        fetchRawFile(context, repo, "LICENSE.md").getOrNull()?.let { text ->
            if (text.isNotBlank()) {
                return ImageModelLicenseInfo(
                    repo = repo,
                    repoUrl = repoUrl,
                    source = ImageModelLicenseSource.LICENSE_FILE,
                    licenseId = null,
                    bodyText = text,
                    found = true
                )
            }
        }

        // 2) README.md の frontmatter + 本文
        val readme = fetchRawFile(context, repo, "README.md").getOrNull()
        if (!readme.isNullOrBlank()) {
            val licenseId = parseFrontmatterLicense(readme)
            return ImageModelLicenseInfo(
                repo = repo,
                repoUrl = repoUrl,
                source = ImageModelLicenseSource.README,
                licenseId = licenseId,
                bodyText = readme,
                found = true
            )
        }

        // 3) どちらも取得できなかった
        return ImageModelLicenseInfo(
            repo = repo,
            repoUrl = repoUrl,
            source = ImageModelLicenseSource.NOT_FOUND,
            licenseId = null,
            bodyText = null,
            found = false
        )
    }

    private suspend fun fetchRawFile(context: Context?, repo: String, fileName: String): Result<String?> = runCatching {
        val url = "https://huggingface.co/$repo/raw/main/$fileName"
        val token = context?.let { HfAuthManager.getToken(it) }.orEmpty()
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "nezumi-ai/1.0")
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code == 404) return@runCatching null
        if (code !in 200..299) return@runCatching null
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** README.md の YAML frontmatter (--- ... ---) から license: の値だけ拾う簡易パーサ。 */
    private fun parseFrontmatterLicense(readmeText: String): String? {
        if (!readmeText.startsWith("---")) return null
        val end = readmeText.indexOf("\n---", 3)
        if (end == -1) return null
        val frontmatter = readmeText.substring(3, end)
        val line = frontmatter.lineSequence().firstOrNull { it.trim().startsWith("license:") } ?: return null
        return line.substringAfter("license:").trim().trim('"', '\'').takeIf { it.isNotBlank() }
    }
}

enum class ImageModelLicenseSource {
    /** リポジトリ直下の LICENSE.md から取得できた。 */
    LICENSE_FILE,
    /** README.md の frontmatter / 本文から取得した。 */
    README,
    /** どちらも取得できなかった（ネットワーク不可・ファイル不在など）。 */
    NOT_FOUND
}

/**
 * ダウンロード確認ダイアログに表示するためのライセンス情報。
 * bodyText は LICENSE_FILE の場合は LICENSE.md 全文、README の場合は README.md 全文
 * （frontmatter含む）を保持する。UI側で必要な範囲を表示する。
 */
data class ImageModelLicenseInfo(
    val repo: String,
    val repoUrl: String,
    val source: ImageModelLicenseSource,
    val licenseId: String?,
    val bodyText: String?,
    val found: Boolean
)