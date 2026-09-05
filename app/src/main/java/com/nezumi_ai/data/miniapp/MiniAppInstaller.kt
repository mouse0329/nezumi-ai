package com.nezumi_ai.data.miniapp

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 仕様 v1.1 §35.5.3 コアロジック（ライフサイクル）の実装。
 *
 * ```
 * [a] 取得 → [b] インストール検証（§32、スキップ不可）→ [c] Dev Mode同意
 *   → [d] Permission提示 → [e] Atomic Install → [f] 一覧反映
 * ```
 * [c]/[d] のユーザー同意は UI 層（MiniAppManagerFragment）が担い、
 * 本クラスは「検証」「実インストール」のみを担当する。
 */
object MiniAppInstaller {

    private const val TAG = "MiniAppInstaller"
    private const val MAX_ZIP_BYTES = 256L * 1024 * 1024   // 256MB
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024  // 1ファイル64MB
    private const val MAX_ENTRIES = 4096

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** [b] までの検証結果。同意待ちの UI がここからインストール確定へ進む。 */
    sealed class PendingInstall {
        abstract val source: InstallSource

        /** 署名OK・信頼済み鍵 → [d] Permission 提示のうえ通常インストール。 */
        data class TrustedReady(
            override val source: InstallSource,
            val verification: MiniAppSignatureVerifier.VerificationResult
        ) : PendingInstall()

        /** 未署名 → Dev Mode 同意が必要（§35.5.3 [c]）。 */
        data class UnsignedNeedsConsent(
            override val source: InstallSource,
            val verification: MiniAppSignatureVerifier.VerificationResult
        ) : PendingInstall()

        /** 署名は正当だが鍵が未信頼 → Dev Mode 同意 + 鍵信頼登録が必要。 */
        data class UnknownKeyNeedsConsent(
            override val source: InstallSource,
            val files: Map<String, ByteArray>,
            val manifest: MiniAppManifest,
            val keyId: String,
            val publicKey: String
        ) : PendingInstall()
    }

    sealed class InstallSource {
        data class LocalZip(val uri: Uri) : InstallSource()
        data class Url(val url: String) : InstallSource()
    }

    /**
     * [a] 取得 + [b] 検証を実行し、同意が必要な場合は同意種別つきで返す。
     *
     * @throws MiniAppException DOWNLOAD_FAILED / PACKAGE_INVALID / PACKAGE_HASH_MISMATCH /
     *         SIGNATURE_INVALID / PACKAGE_TAMPERED 等
     */
    suspend fun prepare(context: Context, source: InstallSource): PendingInstall =
        withContext(Dispatchers.IO) {
            // [a] 取得
            val zipBytes = when (source) {
                is InstallSource.LocalZip -> readLocalZip(context, source.uri)
                is InstallSource.Url -> downloadZip(source.url)
            }

            // ZIP 構造検査 + Path Traversal 検査を兼ねた展開
            val files = unzip(zipBytes)

            // [b] 検証（§32、スキップ不可）
            val verification = try {
                MiniAppSignatureVerifier.verify(context, files)
            } catch (e: MiniAppException) {
                if (e.code == "UNKNOWN_SIGNING_KEY") {
                    @Suppress("UNCHECKED_CAST")
                    val details = e.details as? Map<String, String>
                    val manifestBytes = files["manifest.json"]
                        ?: throw MiniAppException("PACKAGE_INVALID", "manifest.json がありません")
                    return@withContext PendingInstall.UnknownKeyNeedsConsent(
                        source = source,
                        files = files,
                        manifest = MiniAppManifest.parse(String(manifestBytes, Charsets.UTF_8)),
                        keyId = details?.get("keyId").orEmpty(),
                        publicKey = details?.get("publicKey").orEmpty()
                    )
                }
                throw e
            }

            if (!verification.signed) {
                PendingInstall.UnsignedNeedsConsent(source, verification)
            } else {
                PendingInstall.TrustedReady(source, verification)
            }
        }

    /**
     * [e] Atomic Install。
     * Old 維持 → New 検証済み → staging 展開 → Atomic Replace → 失敗時 Rollback。
     * Dev Mode 同意済みの呼び出し側は [devModeConsent] = true を渡す。
     *
     * @throws MiniAppException APP_ALREADY_INSTALLED / DEV_MODE_REQUIRED
     */
    suspend fun install(
        context: Context,
        verification: MiniAppSignatureVerifier.VerificationResult,
        devModeConsent: Boolean
    ): MiniAppStore.InstalledApp = withContext(Dispatchers.IO) {
        // [c] Dev Mode 強制: 未署名は同意なしでインストール不可（§35.5.4 暗黙の使い回し禁止）
        if (!verification.signed && !devModeConsent) {
            throw MiniAppException("DEV_MODE_REQUIRED", "未署名の Mini App は Dev Mode 同意なしにインストールできません")
        }

        val store = MiniAppStore.get(context)
        val appId = verification.manifest.id
        val packageDir = store.packageDir(appId)
        val stagingDir = store.stagingDir(appId)
        val backupDir = File(store.appRoot(appId), "package.bak")
        val isUpdate = store.isInstalled(appId)

        try {
            // staging に展開
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            for ((path, bytes) in verification.files) {
                val out = File(stagingDir, path)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }

            // Old 維持 → Atomic Replace
            backupDir.deleteRecursively()
            if (packageDir.exists() && !packageDir.renameTo(backupDir)) {
                throw MiniAppException("PACKAGE_INVALID", "既存パッケージの退避に失敗しました")
            }
            if (!stagingDir.renameTo(packageDir)) {
                // Rollback
                if (backupDir.exists()) backupDir.renameTo(packageDir)
                throw MiniAppException("PACKAGE_INVALID", "パッケージの配置に失敗しました")
            }
            backupDir.deleteRecursively()

            // 新規インストール時のみ App Data 領域を作成（更新時は既存データを維持）
            if (!isUpdate) {
                File(store.dataDir(appId), "cache").mkdirs()
                File(store.dataDir(appId), "user-data").mkdirs()
            }

            val app = MiniAppStore.InstalledApp(
                manifest = verification.manifest,
                keyId = verification.keyId,
                trusted = verification.trusted,
                devMode = !verification.signed || !verification.trusted,
                installedAt = System.currentTimeMillis()
            )
            store.register(app)
            Log.i(TAG, "Installed mini app: $appId (update=$isUpdate, signed=${verification.signed})")
            app
        } catch (e: MiniAppException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw MiniAppException("PACKAGE_INVALID", "インストールに失敗しました: ${e.message}", cause = e)
        }
    }

    /** UnknownKey 経路のインストール（Dev Mode 同意 + 鍵の信頼登録を済ませたうえで呼ぶ）。 */
    suspend fun installTrustingKey(
        context: Context,
        pending: PendingInstall.UnknownKeyNeedsConsent
    ): MiniAppStore.InstalledApp {
        val verification = MiniAppSignatureVerifier.verifyTrustingKey(
            context,
            pending.files,
            MiniAppSignatureVerifier.TrustedKey(pending.keyId, pending.publicKey, pending.manifest.publisher)
        )
        return install(context, verification, devModeConsent = true)
    }

    /** アンインストール（§35.5.2 [削除]）。App Data ごと削除する。 */
    suspend fun uninstall(context: Context, appId: String): Boolean = withContext(Dispatchers.IO) {
        val store = MiniAppStore.get(context)
        if (!store.isInstalled(appId)) return@withContext false
        store.appRoot(appId).deleteRecursively()
        store.unregister(appId)
        Log.i(TAG, "Uninstalled mini app: $appId")
        true
    }

    // ---------------------------------------------------------------------

    private fun readLocalZip(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_ZIP_BYTES) {
                    throw MiniAppException("PACKAGE_INVALID", "ZIP が大きすぎます（上限 256MB）")
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw MiniAppException("PACKAGE_INVALID", "ZIP を読み込めませんでした")
        return bytes
    }

    private fun downloadZip(url: String): ByteArray {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw MiniAppException("DOWNLOAD_FAILED", "URL は http(s):// である必要があります")
        }
        val request = Request.Builder().url(url).build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw MiniAppException("DOWNLOAD_FAILED", "ダウンロードに失敗しました (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw MiniAppException("DOWNLOAD_FAILED", "レスポンスが空です")
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_BYTES) {
                            throw MiniAppException("PACKAGE_INVALID", "ZIP が大きすぎます（上限 256MB）")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
        } catch (e: MiniAppException) {
            throw e
        } catch (e: Exception) {
            throw MiniAppException("DOWNLOAD_FAILED", "ダウンロードに失敗しました: ${e.message}", cause = e)
        }
    }

    /**
     * ZIP 構造検査 + Path Traversal 検査（§32 ZIPセキュリティ）を行いつつ全展開する。
     * `../`、絶対パス、ディレクトリエントリの symlink 的パス、特殊ファイルを拒否。
     */
    private fun unzip(zipBytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var count = 0
            while (entry != null) {
                count++
                if (count > MAX_ENTRIES) {
                    throw MiniAppException("PACKAGE_INVALID", "ZIP 内のファイル数が多すぎます")
                }
                val name = entry.name.replace('\\', '/')
                // Path Traversal / 絶対パス / 特殊パス拒否
                if (name.startsWith("/") || name.startsWith("..") ||
                    name.split('/').any { it == ".." } || name.contains(":")
                ) {
                    throw MiniAppException("PACKAGE_INVALID", "不正なパスを含むZIPです: $name")
                }
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ENTRY_BYTES) {
                            throw MiniAppException("PACKAGE_INVALID", "ファイルが大きすぎます: $name")
                        }
                        out.write(buf, 0, n)
                    }
                    files[name] = out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (files.isEmpty()) {
            throw MiniAppException("PACKAGE_INVALID", "ZIP にファイルが含まれていません")
        }
        return files
    }
}
