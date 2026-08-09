package com.nezumi_ai.data.document

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "DocumentConversionManager"
private const val AUTHORITY_SUFFIX = ".fileprovider"
private const val DOCS_DIR_NAME = "generated_documents"

/**
 * Markdown ⇔ Word/PDF/Excel 変換の窓口。
 * NezumiLiteRtTools (ツールディスパッチャ) から呼ばれ、ファイル生成・保存・
 * FileProvider URI 発行までを一括して行う。
 */
object DocumentConversionManager {

    enum class TargetFormat(val extension: String) {
        DOCX("docx"),
        PDF("pdf"),
        XLSX("xlsx")
    }

    data class GenerateResult(
        val success: Boolean,
        val filePath: String? = null,
        val fileUri: String? = null,
        val fileName: String? = null,
        val fileSizeBytes: Long = 0,
        val warning: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )

    data class ExtractResult(
        val success: Boolean,
        val markdown: String? = null,
        val markdownFilePath: String? = null,
        val markdownFileUri: String? = null,
        val charCount: Int = 0,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )

    private fun getDocsDir(context: Context): File {
        val dir = File(context.filesDir, DOCS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun uriFor(context: Context, file: File): String {
        val authority = context.packageName + AUTHORITY_SUFFIX
        return FileProvider.getUriForFile(context, authority, file).toString()
    }

    /**
     * Markdown 文字列を Word(.docx) / PDF(.pdf) / Excel(.xlsx) のいずれかに変換する。
     *
     * @param markdown 変換元 Markdown テキスト
     * @param format 出力形式
     * @param baseName 出力ファイル名（拡張子なし）。未指定ならUUIDベースの名前を生成。
     */
    suspend fun generateFromMarkdown(
        context: Context,
        markdown: String,
        format: TargetFormat,
        baseName: String? = null
    ): GenerateResult = withContext(Dispatchers.IO) {
        if (markdown.isBlank()) {
            return@withContext GenerateResult(
                success = false,
                errorCode = "empty_markdown",
                errorMessage = "Markdown content is empty"
            )
        }

        val safeName = sanitizeFileName(baseName) ?: "document_${UUID.randomUUID().toString().take(8)}"
        val outputFile = File(getDocsDir(context), "$safeName.${format.extension}")

        try {
            var warning: String? = null
            when (format) {
                TargetFormat.DOCX -> {
                    MarkdownToDocxWriter.write(markdown, outputFile)
                }
                TargetFormat.XLSX -> {
                    MarkdownToXlsxWriter.write(markdown, outputFile)
                }
                TargetFormat.PDF -> {
                    val result = MarkdownToPdfWriter.write(context, markdown, outputFile)
                    if (result.usedFallbackFont) {
                        warning = "device_missing_japanese_font"
                    }
                }
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                return@withContext GenerateResult(
                    success = false,
                    errorCode = "output_file_empty",
                    errorMessage = "Generated file is missing or empty"
                )
            }

            GenerateResult(
                success = true,
                filePath = outputFile.absolutePath,
                fileUri = uriFor(context, outputFile),
                fileName = outputFile.name,
                fileSizeBytes = outputFile.length(),
                warning = warning
            )
        } catch (e: Exception) {
            Log.e(TAG, "generateFromMarkdown failed: format=$format", e)
            GenerateResult(
                success = false,
                errorCode = "generation_failed",
                errorMessage = e.message ?: e.toString()
            )
        }
    }

    /**
     * PDF/Word/Excel などのファイルを Markdown に変換する（Chaquopy 経由の MarkItDown）。
     *
     * @param sourceFilePath 変換元ファイルの絶対パス（content:// URI はあらかじめ
     *   filesDir 配下等にコピーしておくこと。Python 側は file:// のローカルパスのみ扱う）
     */
    suspend fun convertFileToMarkdown(
        context: Context,
        sourceFilePath: String,
        baseName: String? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        val sourceFile = resolveToLocalFile(context, sourceFilePath)
            ?: return@withContext ExtractResult(
                success = false,
                errorCode = "file_not_found",
                errorMessage = "Could not resolve source file: $sourceFilePath"
            )

        val safeName = sanitizeFileName(baseName)
            ?: sourceFile.nameWithoutExtension.ifBlank { "document_${UUID.randomUUID().toString().take(8)}" }
        val outputFile = File(getDocsDir(context), "$safeName.md")

        val result = DocToMdPythonBridge.convertFileToMarkdown(
            context = context,
            inputPath = sourceFile.absolutePath,
            outputPath = outputFile.absolutePath
        )

        if (!result.success) {
            return@withContext ExtractResult(
                success = false,
                errorCode = result.errorCode,
                errorMessage = result.errorMessage
            )
        }

        val markdownText = try {
            outputFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read back generated markdown file, using preview only", e)
            result.preview.orEmpty()
        }

        ExtractResult(
            success = true,
            markdown = markdownText,
            markdownFilePath = outputFile.absolutePath,
            markdownFileUri = uriFor(context, outputFile),
            charCount = result.charCount
        )
    }

    private fun sanitizeFileName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        var sanitized = name.trim()
        for (c in invalidChars) sanitized = sanitized.replace(c, '_')
        return sanitized.take(80).ifBlank { null }
    }

    /**
     * ツール引数として渡された「ファイル参照」を実ファイルに解決する。
     * 受け付ける形式:
     *   - 生の絶対パス (/storage/... や /data/... など)
     *   - file:// URI
     *   - content:// URI （filesDir 配下の一時ファイルにコピーしてから返す。
     *     Chaquopy/Python は content:// を直接扱えないため）
     *   - nezumi://txtfile?...&uri=<content-or-file-uri> マーカー
     *     （TextFileAttachmentEncoding 由来。埋め込まれた uri を再帰的に解決する）
     */
    private fun resolveToLocalFile(context: Context, rawRef: String): File? {
        val ref = rawRef.trim()
        if (ref.isEmpty()) return null

        // nezumi://txtfile マーカーの場合は中の uri パラメータを取り出して再解決する
        if (com.nezumi_ai.data.media.TextFileAttachmentEncoding.isMarker(ref)) {
            val entry = com.nezumi_ai.data.media.TextFileAttachmentEncoding.tryDecode(ref)
                ?: return null
            return resolveToLocalFile(context, entry.uri)
        }

        return try {
            when {
                ref.startsWith("content://") -> copyContentUriToTempFile(context, android.net.Uri.parse(ref))
                ref.startsWith("file://") -> {
                    val path = android.net.Uri.parse(ref).path ?: return null
                    File(path).takeIf { it.exists() }
                }
                else -> File(ref).takeIf { it.exists() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveToLocalFile failed for: $ref", e)
            null
        }
    }

    /**
     * content:// URI の中身を filesDir/generated_documents/tmp_in 配下に元ファイル名で
     * コピーする。MarkItDown (Python) はローカルパスのみを扱えるための橋渡し。
     */
    private fun copyContentUriToTempFile(context: Context, uri: android.net.Uri): File? {
        val displayName = queryDisplayName(context, uri) ?: "input_${UUID.randomUUID().toString().take(8)}"
        val tmpDir = File(getDocsDir(context), "tmp_in").apply { mkdirs() }
        val outFile = File(tmpDir, sanitizeFileName(displayName) ?: displayName)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "copyContentUriToTempFile failed for: $uri", e)
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryDisplayName failed for: $uri", e)
            null
        }
    }
}
