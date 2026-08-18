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

/** [CloudToolExecutor] の Android 実装。既存 NezumiLiteRtToolExecutor へ橋渡し。DB 直接参照はここに隔離。 */
class AndroidCloudToolExecutor(private val appContext: Context) : CloudToolExecutor {
    private val delegate by lazy {
        val db = NezumiAiDatabase.getInstance(appContext)
        NezumiLiteRtToolExecutor(appContext, db.alarmDao(), MemoryRepository(db.memoryDao()), MemoryTextEmbedder)
    }
    override suspend fun execute(call: ParsedToolCall): CloudToolExecutionResult {
        val r = delegate.execute(ToolCall(name = call.name, arguments = call.arguments))
        return CloudToolExecutionResult(success = r.success, payload = r.payload, modelPayload = r.modelPayload)
    }
}
