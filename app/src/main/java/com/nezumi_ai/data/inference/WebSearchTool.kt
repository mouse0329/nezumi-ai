package com.nezumi_ai.data.inference

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    val client = OkHttpClient()
    
    // APIエンドポイント構築
    val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
        .addQueryParameter("q", query)
        .addQueryParameter("count", count.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("country", country.lowercase())
        .addQueryParameter("search_lang", searchLang.lowercase())
        .addQueryParameter("safesearch", safeSearch)
        .build()
    
    Log.d(WEB_SEARCH_TAG, "Request URL: $url")
    
    val request = Request.Builder()
        .url(url)
        .get()
        .addHeader("Accept", "application/json")
        .addHeader("X-Subscription-Token", apiKey)
        .build()
    
    val response = client.newCall(request).execute()
    
    return response.use { resp ->
        if (!resp.isSuccessful) {
            throw Exception("API request failed: ${resp.code} ${resp.message}")
        }
        
        val body = resp.body?.string() ?: throw Exception("Empty response body")
        parseBraveSearchResponse(body)
    }
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
