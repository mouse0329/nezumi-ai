package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.document.DocumentConversionManager
import com.nezumi_ai.data.inference.ToolExecutionResult
import com.nezumi_ai.presentation.viewmodel.platform.PlatformSdModelPathResolver
import com.nezumi_ai.sd.SdScheduler
import kotlin.math.abs

/**
 * クラスタ F (画像生成ツール連携) の引数パースと SD モデルパス解決。
 *
 * ファイルシステム走査は [PlatformSdModelPathResolver] に押し込み、
 * ViewModel は State / ダイアログ / ジョブ起動に専念する。
 */
class ImageToolInvoker(
    private val sdModelPathResolver: PlatformSdModelPathResolver
) {
    /** ツール呼び出しでモデル名指定がない場合のデフォルト SD モデルパス。 */
    fun defaultSdModelPath(): String = sdModelPathResolver.findAvailableSdModelPath()

    /** list_sd_models ツールが返す "name" から実パスを解決する。不明なら null。 */
    fun resolveSdModelPathByName(modelName: String): String? =
        sdModelPathResolver.resolveSdModelPathByName(modelName)

    /**
     * ツール引数の model 指定を実パスに正規化する。
     * 空指定はデフォルトパス、名前指定はパス解決、解決失敗時は null。
     */
    fun resolveSdModelPathForToolArg(modelArg: String?): String? {
        val name = modelArg?.trim().orEmpty()
        if (name.isEmpty()) {
            return defaultSdModelPath().takeIf { it.isNotEmpty() }
        }
        return sdModelPathResolver.resolveSdModelPathByName(name)
    }

    /** generate_image ツール引数の正規化結果。 */
    data class GenerateImageArgs(
        val prompt: String,
        val negativePrompt: String,
        val requestedModelName: String?,
        val width: Int,
        val height: Int,
        val steps: Int,
        val cfg: Float,
        val seed: Long,
        val scheduler: SdScheduler
    )

    /**
     * generate_image の ToolCall.arguments を正規化する。
     * prompt が空のときは null（呼び出し側で missing_prompt を返す）。
     */
    fun parseGenerateImageArgs(
        arguments: Map<String, Any?>,
        defaultSteps: Int,
        defaultCfg: Float
    ): GenerateImageArgs? {
        val prompt = arguments["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return null

        val negativePrompt = (
            arguments["negativePrompt"] ?: arguments["negative_prompt"]
        )?.toString()?.trim().orEmpty()

        val requestedModelName = (
            arguments["model"] ?: arguments["model_name"] ?: arguments["modelName"]
        )?.toString()?.trim()?.takeIf { it.isNotBlank() }

        val width = snapImageDimension((arguments["width"] as? Number)?.toInt() ?: 256)
        val height = snapImageDimension((arguments["height"] as? Number)?.toInt() ?: 256)
        val steps = (arguments["steps"] as? Number)?.toInt()?.coerceIn(1, 50) ?: defaultSteps
        val cfg = (arguments["cfg"] as? Number)?.toFloat()
            ?: (arguments["cfg_scale"] as? Number)?.toFloat()
            ?: defaultCfg
        val seed = (arguments["seed"] as? Number)?.toLong() ?: -1L
        val scheduler = SdScheduler.fromId(arguments["scheduler"] as? String)

        return GenerateImageArgs(
            prompt = prompt,
            negativePrompt = negativePrompt,
            requestedModelName = requestedModelName,
            width = width,
            height = height,
            steps = steps,
            cfg = cfg,
            seed = seed,
            scheduler = scheduler
        )
    }

    /** convert_md_to_document のパース成功結果。 */
    data class ConvertMdArgs(
        val markdown: String,
        val format: DocumentConversionManager.TargetFormat,
        val displayName: String
    )

    /**
     * convert_md_to_document の引数を検証・正規化する。
     * 失敗時は呼び出し側がそのまま返せる [ToolExecutionResult] を渡す。
     */
    fun parseConvertMdToDocumentArgs(
        arguments: Map<String, Any?>
    ): Pair<ConvertMdArgs?, ToolExecutionResult?> {
        val markdown = arguments["markdown"]?.toString().orEmpty()
        val formatArg = arguments["format"]?.toString()?.trim()?.lowercase().orEmpty()
        val fileNameArg = arguments["fileName"]?.toString()?.trim()

        if (markdown.isBlank()) {
            return null to ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "missing_markdown")
            )
        }

        val format = when (formatArg) {
            "docx", "word", "doc" -> DocumentConversionManager.TargetFormat.DOCX
            "pdf" -> DocumentConversionManager.TargetFormat.PDF
            "xlsx", "excel", "xls" -> DocumentConversionManager.TargetFormat.XLSX
            else -> null
        }
        if (format == null) {
            return null to ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "invalid_format",
                    "message" to "format must be one of: docx, pdf, xlsx"
                )
            )
        }

        val baseName = fileNameArg?.takeIf { it.isNotBlank() } ?: "document"
        val displayName = "$baseName.${format.extension}"
        return ConvertMdArgs(markdown, format, displayName) to null
    }

    /**
     * UI カード用 full payload とモデル向け modelPayload を組み立てる。
     * モデルには markdown 全文を戻さず文字数だけ伝える（コンテキスト浪費対策）。
     */
    fun buildConvertMdPayloads(args: ConvertMdArgs): Pair<Map<String, Any?>, Map<String, Any?>> {
        val fullPayload = buildMap {
            put("success", true)
            put("format", args.format.extension)
            put("fileName", args.displayName)
            put("markdown", args.markdown)
            put("pendingConversion", true)
        }
        val modelPayload = buildMap {
            put("success", true)
            put("format", args.format.extension)
            put("fileName", args.displayName)
            put("markdownLength", args.markdown.length)
            put("note", "Markdown was received and is queued for conversion. The full text is not echoed back to save context.")
        }
        return fullPayload to modelPayload
    }

    companion object {
        private val ALLOWED_IMAGE_SIZES = listOf(256, 512, 768)

        fun snapImageDimension(raw: Int): Int =
            ALLOWED_IMAGE_SIZES.minByOrNull { abs(it - raw) } ?: 512
    }
}
