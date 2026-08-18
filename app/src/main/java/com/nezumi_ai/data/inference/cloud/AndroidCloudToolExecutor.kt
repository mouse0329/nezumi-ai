package com.nezumi_ai.data.inference.cloud

import android.content.Context
import com.google.ai.edge.litertlm.ToolCall
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.CloudToolExecutionResult
import com.nezumi_ai.data.inference.CloudToolExecutor
import com.nezumi_ai.data.inference.NezumiLiteRtToolExecutor
import com.nezumi_ai.data.inference.ParsedToolCall
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.repository.MemoryRepository

/**
 * [CloudToolExecutor] の Android 実装。
 *
 * shared のクラウドエンジン基底からツール実行を委譲され、既存の
 * [NezumiLiteRtToolExecutor] (DB / ONNX 埋め込みに依存) へ橋渡しする。
 * DB 直接参照 (NezumiAiDatabase.getInstance) はこのアダプタに隔離し、
 * SQLDelight 移行 (フェーズ2) までは shared 側から DB を見せない。
 */
class AndroidCloudToolExecutor(
    private val appContext: Context
) : CloudToolExecutor {

    private val delegate by lazy {
        val db = NezumiAiDatabase.getInstance(appContext)
        NezumiLiteRtToolExecutor(
            appContext,
            db.alarmDao(),
            MemoryRepository(db.memoryDao()),
            MemoryTextEmbedder
        )
    }

    override suspend fun execute(call: ParsedToolCall): CloudToolExecutionResult {
        val result = delegate.execute(ToolCall(name = call.name, arguments = call.arguments))
        return CloudToolExecutionResult(
            success = result.success,
            payload = result.payload,
            modelPayload = result.modelPayload
        )
    }
}
