package com.nezumi_ai.data.inference

import com.google.ai.edge.litertlm.ToolCall

/**
 * Markdown → Word/PDF/Excel 生成ツールの実行委譲先。
 * GenerateImageToolBridge と同じパターンで、ViewModel が生成したハンドラをここに
 * 差し込むことで、実際の処理を ViewModel/UI レイヤーに委譲する。
 *
 * (PDF/Word/Excel → Markdown の読み取り側は、ツール経由ではなく
 *  ChatFragment が添付時に DocumentConversionManager.extractMarkdownText() で
 *  直接変換する方式に変更されたため、このブリッジからは削除済み)
 *
 * ドキュメント変換自体はファイル入出力のみで完結し、画像生成のような
 * 「重い推論をキューイングしてバックグラウンドで進める」必要が薄いため、
 * ハンドラは同期的に完了を待ってから ToolExecutionResult を返す設計とする。
 */
fun interface ConvertMdToDocumentToolHandler {
    suspend fun handle(toolCall: ToolCall): ToolExecutionResult
}

object DocumentToolBridge {
    @Volatile
    var convertMdToDocumentHandler: ConvertMdToDocumentToolHandler? = null
}
