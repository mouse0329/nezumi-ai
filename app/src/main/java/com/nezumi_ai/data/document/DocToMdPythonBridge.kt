package com.nezumi_ai.data.document

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "DocToMdPythonBridge"

/**
 * Chaquopy 経由で Python (MarkItDown) を呼び出し、
 * PDF / Word(.docx) / Excel(.xlsx, .xls) / PowerPoint / HTML などを
 * Markdown に変換するブリッジ。
 *
 * このオブジェクトは com.nezumi_ai.data.python パッケージ配下に置かれた
 * app/src/main/python/doc_to_md.py の convert_file() / is_available() を呼び出す。
 */
object DocToMdPythonBridge {

    data class ConversionResult(
        val success: Boolean,
        val outputPath: String? = null,
        val charCount: Int = 0,
        val preview: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )

    @Volatile
    private var started = false

    /**
     * Python ランタイムが起動済みであることを保証する。
     * MyApplication.onCreate() で通常は起動済みだが、念のため二重チェックする。
     */
    private fun ensureStarted(context: Context) {
        if (started && Python.isStarted()) return
        synchronized(this) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            started = true
        }
    }

    /**
     * markitdown パッケージが実際に import 可能かどうかを確認する。
     * Chaquopy のビルド設定ミスや、稀に発生する pip インストール漏れの検知用。
     */
    suspend fun isAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureStarted(context)
            val py = Python.getInstance()
            val module = py.getModule("doc_to_md")
            val resultJson = module.callAttr("is_available").toString()
            JSONObject(resultJson).optBoolean("available", false)
        } catch (e: Exception) {
            Log.w(TAG, "isAvailable check failed", e)
            false
        }
    }

    /**
     * inputPath のドキュメントを Markdown に変換し、outputPath に書き出す。
     *
     * @param inputPath 変換元ファイルの絶対パス（PDF/DOCX/XLSX/XLS/PPTX/HTML 等）
     * @param outputPath 書き出し先の .md ファイル絶対パス
     */
    suspend fun convertFileToMarkdown(
        context: Context,
        inputPath: String,
        outputPath: String
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            ensureStarted(context)
            val py = Python.getInstance()
            val module = py.getModule("doc_to_md")
            val resultJson = module.callAttr("convert_file", inputPath, outputPath).toString()
            parseResult(resultJson)
        } catch (e: PyException) {
            Log.e(TAG, "convertFileToMarkdown: PyException", e)
            ConversionResult(
                success = false,
                errorCode = "python_exception",
                errorMessage = e.message ?: "Unknown Python error"
            )
        } catch (e: Exception) {
            Log.e(TAG, "convertFileToMarkdown: unexpected failure", e)
            ConversionResult(
                success = false,
                errorCode = "bridge_error",
                errorMessage = e.message ?: e.toString()
            )
        }
    }

    private fun parseResult(json: String): ConversionResult {
        return try {
            val obj = JSONObject(json)
            val success = obj.optBoolean("success", false)
            if (success) {
                ConversionResult(
                    success = true,
                    outputPath = obj.optString("outputPath", null.toString()).takeIf { it.isNotBlank() },
                    charCount = obj.optInt("charCount", 0),
                    preview = obj.optString("preview", null.toString()).takeIf { it.isNotBlank() }
                )
            } else {
                ConversionResult(
                    success = false,
                    errorCode = obj.optString("error", "unknown_error"),
                    errorMessage = obj.optString("message", "Conversion failed")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON result: $json", e)
            ConversionResult(
                success = false,
                errorCode = "invalid_json_result",
                errorMessage = "Failed to parse Python result: ${e.message}"
            )
        }
    }
}
