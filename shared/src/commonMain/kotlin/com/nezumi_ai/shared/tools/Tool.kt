package com.nezumi_ai.shared.tools

import kotlinx.serialization.json.JsonElement

interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, JsonElement>

    suspend fun execute(parameters: Map<String, Any>): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val result: String,
    val error: String? = null
)