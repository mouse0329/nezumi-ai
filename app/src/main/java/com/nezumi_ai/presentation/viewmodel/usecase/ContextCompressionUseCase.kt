package com.nezumi_ai.presentation.viewmodel.usecase

import android.util.Log
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.ModelManager
import kotlinx.coroutines.withTimeoutOrNull

/**
 * クラスタ D (コンテキスト圧縮) の推論オーケストレーションを切り出した UseCase。
 *
 * 圧縮プロンプトの組み立て・推論実行・結果のパースという一連の流れを
 * ViewModel から独立させる。テキスト整形の細部は [PromptBuildingUseCase] に委譲し、
 * ここでは「圧縮推論を流す」ことに専念する。
 */
class ContextCompressionUseCase(
    private val promptBuilding: PromptBuildingUseCase = PromptBuildingUseCase()
) {

    /**
     * 会話履歴を圧縮する要約を推論で生成する。
     * タイムアウト・JSON 形式での応答・空応答はすべてフォールバック要約に倒す。
     *
     * @return "要約: ..." 形式の文字列。失敗時もフォールバックを返す (null は返さない)。
     */
    suspend fun requestCompressedContextSummary(
        manager: ModelManager,
        sessionId: Long,
        messages: List<MessageEntity>,
        config: InferenceConfig,
        compressionTimeoutMs: Long = COMPRESSION_TIMEOUT_MS,
        summaryMaxChars: Int = COMPRESSION_SUMMARY_MAX_CHARS
    ): String {
        val transcript = messages.joinToString(separator = "\n") { msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            val content = promptBuilding.sanitizeMessageContentForPrompt(msg)
            "$role: $content"
        }

        val compressionPrompt = buildString {
            append("以下の会話履歴を、次回応答に必要な情報だけに圧縮してください。\n")
            append("出力は必ず日本語。JSONやMarkdownコードブロックは禁止。\n")
            append("最大4行、各行は簡潔な短文にしてください。\n")
            append("\n")
            append("含めるべき情報:\n")
            append("- ユーザーの目的・依頼内容\n")
            append("- 決定済みの前提（設定値・制約・方針）\n")
            append("- 未解決タスクや次のアクション\n")
            append("- 必要なら固有名詞・数値\n")
            append("\n")
            append("不要な情報:\n")
            append("- 挨拶、言い換え、冗長な説明\n")
            append("- 既に不要になった古い経緯\n")
            append("\n")
            append("会話履歴:\n")
            append(transcript)
        }

        val raw = withTimeoutOrNull(compressionTimeoutMs) {
            val compressionConfig = config.copy(
                temperature = config.temperature.coerceIn(0f, 0.7f),
                enableThinking = false
            ).normalized()
            val flow = manager.runInference(
                sessionId = sessionId,
                prompt = compressionPrompt,
                config = compressionConfig
            )
            val builder = StringBuilder()
            flow.collect { chunk ->
                val final = InferenceStreamProtocol.decodeFinal(chunk)
                val toolCallChunk = InferenceStreamProtocol.decodeToolCallChunk(chunk)
                val toolResultChunk = InferenceStreamProtocol.decodeToolResultChunk(chunk)
                if (final != null) {
                    builder.clear()
                    builder.append(final)
                } else if (toolCallChunk != null || toolResultChunk != null) {
                    // 圧縮用途ではツールイベントを本文として扱わない
                } else if (chunk.isNotEmpty()) {
                    val currentContent = builder.toString()
                    val merged = promptBuilding.mergeStreamingChunk(currentContent, chunk)
                    if (merged != currentContent && merged.length >= currentContent.length) {
                        builder.clear()
                        builder.append(merged)
                    } else if (merged.length < currentContent.length) {
                        Log.w(TAG, "Context compression merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping")
                    }
                }
            }
            builder.toString().trim()
        }

        // JSON形式で返ってきてしまったらフィルタリング（防衛線）
        if (raw?.trim()?.startsWith("{") == true) {
            Log.w(TAG, "Context compression returned JSON format instead of natural text: $raw")
            return promptBuilding.buildCompressedSummaryFallback(messages)
        }

        // 自然言語の要約が返ってきた場合（Gemma 4 のシンキングタグは除去して本文だけ使う）
        return if (!raw.isNullOrBlank()) {
            val answerOnly = Gemma4ThinkingParser.parse(raw.trim()).answer.ifBlank { raw.trim() }
            val compact = promptBuilding.compactCompressionSummary(answerOnly, summaryMaxChars)
            buildString {
                append("要約: ")
                append(compact)
            }
        } else {
            promptBuilding.buildCompressedSummaryFallback(messages)
        }
    }

    companion object {
        private const val TAG = "ContextCompressionUseCase"
        private const val COMPRESSION_TIMEOUT_MS = 25_000L
        private const val COMPRESSION_SUMMARY_MAX_CHARS = 700
    }
}
