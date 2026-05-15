package com.nezumi_ai.shared.network

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
data class VoicevoxQuery(
    val text: String,
    val speaker: Int = 1
)

@Serializable
data class VoicevoxSynthesis(
    val audio: String // base64 encoded audio
)

class VoicevoxClient(
    private val baseUrl: String = "http://localhost:50021"
) {
    private val client = HttpClient()

    suspend fun synthesize(text: String, speaker: Int = 1): ByteArray? {
        return try {
            // First, create audio query
            val queryResponse = client.post("$baseUrl/audio_query") {
                contentType(ContentType.Application.Json)
                setBody(VoicevoxQuery(text, speaker))
            }

            if (queryResponse.status != HttpStatusCode.OK) {
                return null
            }

            val queryJson = queryResponse.bodyAsText()

            // Then, synthesize audio
            val synthesisResponse = client.post("$baseUrl/synthesis") {
                contentType(ContentType.Application.Json)
                setBody(queryJson)
                parameter("speaker", speaker)
            }

            if (synthesisResponse.status == HttpStatusCode.OK) {
                synthesisResponse.body()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}