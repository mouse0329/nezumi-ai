package com.nezumi_ai.data.inference

import com.google.ai.edge.litertlm.ToolCall

fun interface GenerateImageToolHandler {
    suspend fun handle(toolCall: ToolCall): ToolExecutionResult
}

object GenerateImageToolBridge {
    @Volatile
    var handler: GenerateImageToolHandler? = null
}
