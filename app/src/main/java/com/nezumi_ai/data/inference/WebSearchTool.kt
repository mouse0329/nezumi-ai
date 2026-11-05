package com.nezumi_ai.data.inference

import android.util.Log
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.json.JSONObject

private const val WEB_SEARCH_TAG = "WebSearchTool"

// ─────────────────────────────────────────────
// Brave Search API 実装
// ─────────────────────────────────────────────

internal suspend fun performBraveSearch(
    query: String,
    count: Int,
    offset: Int,
    country: String,
    searchLang: String,
    safeSearch: String,
    apiKey: String
): List<Map<String, Any?>> {
    Log.d(WEB_SEARCH_TAG, "Requesting Brave Search")
    val response = CloudHttpClient.instance.get("https://api.search.brave.com/res/v1/web/search") {
        parameter("q", query)
        parameter("count", count)
        parameter("offset", offset)
        parameter("country", country.lowercase())
        parameter("search_lang", searchLang.lowercase())
        parameter("safesearch", safeSearch)
        header("Accept", "application/json")
        header("X-Subscription-Token", apiKey)
    }
    if (!response.status.isSuccess()) {
        throw Exception("API request failed: ${response.status.value} ${response.status.description}")
    }
    return parseBraveSearchResponse(response.bodyAsText())
}

private fun parseBraveSearchResponse(json: String): List<Map<String, Any?>> {
    val results = mutableListOf<Map<String, Any?>>()
    
    try {
        val jsonObject = JSONObject(json)
        
        // Web results
        val webObj = jsonObject.optJSONObject("web")?.optJSONArray("results")
        if (webObj != null) {
            for (i in 0 until webObj.length()) {
                val result = webObj.getJSONObject(i)
                val snippet = result.optString("snippet", "")
                
                results.add(
                    mapOf(
                        "title" to result.optString("title", ""),
                        "url" to result.optString("url", ""),
                        "snippet" to snippet,
                        "score" to result.optDouble("score", 0.0),
                        "type" to "web"
                    )
                )
            }
        }
        
        // News results (optional)
        jsonObject.optJSONObject("news")?.optJSONArray("results")?.let { newsArray ->
            for (i in 0 until newsArray.length()) {
                val result = newsArray.getJSONObject(i)
                results.add(
                    mapOf(
                        "title" to result.optString("title", ""),
                        "url" to result.optString("url", ""),
                        "snippet" to result.optString("snippet", result.optString("description", "")),
                        "age" to result.optString("age", ""),
                        "source" to result.optString("source", ""),
                        "type" to "news"
                    )
                )
            }
        }
        
        // Videos results (optional)
        jsonObject.optJSONObject("videos")?.optJSONArray("results")?.let { videosArray ->
            for (i in 0 until videosArray.length()) {
                val result = videosArray.getJSONObject(i)
                results.add(
                    mapOf(
                        "title" to result.optString("title", ""),
                        "url" to result.optString("url", ""),
                        "snippet" to result.optString("snippet", ""),
                        "thumbnail" to result.optString("thumbnail", ""),
                        "type" to "video"
                    )
                )
            }
        }
    } catch (e: Exception) {
        Log.e(WEB_SEARCH_TAG, "Failed to parse response", e)
    }
    
    return results
}

// ─────────────────────────────────────────────
// ユーティリティ拡張
// ─────────────────────────────────────────────

private fun Map<String, Any?>.readInt(key: String): Int? {
    return when (val v = this[key] ?: return null) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else      -> null
    }
}
