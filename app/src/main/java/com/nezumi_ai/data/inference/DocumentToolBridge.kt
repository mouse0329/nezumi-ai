package com.nezumi_ai.data.inference

import com.google.ai.edge.litertlm.ToolCall

/**
 * Markdown → Word/PDF/Excel 生成、および PDF/Word/Excel → Markdown 変換ツールの
 * 実行委譲先。GenerateImageToolBridge と同じパターンで、ViewModel が生成した
 * ハンドラをここに差し込むことで、実際の処理を ViewModel/UI レイヤーに委譲する。
 *
 * ドキュメント変換自体はファイル入出力のみで完結し、画像生成のような
 * 「重い推論をキューイングしてバックグラウンドで進める」必要が薄いため、
 * ハンドラは同期的に完了を待ってから ToolExecutionResult を返す設計とする。
 */
fun interface ConvertMdToDocumentToolHandler {
    suspend fun handle(toolCall: ToolCall): ToolExecutionResult
}

fun interface ConvertDocumentToMdToolHandler {
    suspend fun handle(toolCall: ToolCall): ToolExecutionResult
}

object DocumentToolBridge {
    @Volatile
    var convertMdToDocumentHandler: ConvertMdToDocumentToolHandler? = null

    @Volatile
    var convertDocumentToMdHandler: ConvertDocumentToMdToolHandler? = null
}
