package com.nezumi_ai.shared.inference

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApiRequest(
    val prompt: String,
    val max_tokens: Int = 1000,
    val temperature: Float = 0.7f
)

@Serializable
data class ApiResponse(
    val text: String
)

class ApiBackend(
    private val apiType: ApiType,
    private val apiKey: String,
    private val config: EngineConfig = EngineConfig()
) : InferenceBackend {

    enum class ApiType {
        ANTHROPIC, GEMINI, OPENAI
    }

    private val client = HttpClient()

    override suspend fun generate(prompt: String): Flow<String> = flow {
        try {
            val response = when (apiType) {
                ApiType.ANTHROPIC -> callAnthropic(prompt)
                ApiType.GEMINI -> callGemini(prompt)
                ApiType.OPENAI -> callOpenAI(prompt)
            }
            emit(response)
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }

    private suspend fun callAnthropic(prompt: String): String {
        // TODO: Anthropic API実装
        return "Anthropic response for: $prompt"
    }

    private suspend fun callGemini(prompt: String): String {
        // TODO: Gemini API実装
        return "Gemini response for: $prompt"
    }

    private suspend fun callOpenAI(prompt: String): String {
        // TODO: OpenAI API実装
        return "OpenAI response for: $prompt"
    }

    override suspend fun load(modelPath: String) {
        // APIバックエンドなので何もしない
    }

    override fun unload() {
        client.close()
    }
}