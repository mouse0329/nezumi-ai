package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.mcp.McpToolRegistry

/**
 * GGUF / llama.rn 向けにツール定義をシステムプロンプトへ注入する。
 * LiteRT-LM の [buildEnabledToolProviders] と同じツールセットを [ToolPreferences] で絞り込む。
 */
object GgufToolPromptBuilder {
    private const val TAG = "GgufToolPromptBuilder"

    private data class ToolSchema(
        val name: String,
        val description: String,
        val parametersJson: String
    )

    private val allSchemas = listOf(
        ToolSchema(
            "get_current_time",
            "Returns current device datetime.",
            """{"type":"object","properties":{"timezone":{"type":"string","description":"IANA timezone e.g. Asia/Tokyo"}},"required":[]}"""
        ),
        ToolSchema(
            "get_battery_level",
            "Returns current device battery level and status.",
            """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSchema(
            "set_alarm",
            "Sets a system alarm at the given hour and minute.",
            """{"type":"object","properties":{"hour":{"type":"integer","description":"0-23"},"minute":{"type":"integer","description":"0-59"},"label":{"type":"string"}},"required":["hour","minute"]}"""
        ),
        ToolSchema(
            "dismiss_alarm",
            "Dismisses a system alarm by hour and minute.",
            """{"type":"object","properties":{"hour":{"type":"integer"},"minute":{"type":"integer"}},"required":["hour","minute"]}"""
        ),
        ToolSchema(
            "list_alarms",
            "Lists scheduled alarms.",
            """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSchema(
            "set_flashlight",
            "Turns the device flashlight on or off.",
            """{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}"""
        ),
        ToolSchema(
            "start_timer",
            "Starts a countdown timer.",
            """{"type":"object","properties":{"seconds":{"type":"integer","description":"Duration in seconds"},"label":{"type":"string"}},"required":["seconds"]}"""
        ),
        ToolSchema(
            "stop_timer",
            "Stops a running timer by id.",
            """{"type":"object","properties":{"timer_id":{"type":"string"}},"required":["timer_id"]}"""
        ),
        ToolSchema(
            "list_timers",
            "Lists active timers.",
            """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSchema(
            "generate_image",
            "Generates an image from a text prompt. Call list_sd_models first to get available model names.",
            """{"type":"object","properties":{"prompt":{"type":"string"},"negative_prompt":{"type":"string"},"model":{"type":"string","description":"Model directory name from list_sd_models. If omitted, the default model is used."},"width":{"type":"integer"},"height":{"type":"integer"},"steps":{"type":"integer"},"cfg":{"type":"number"},"seed":{"type":"integer"}},"required":["prompt"]}"""
        ),
        ToolSchema(
            "list_sd_models",
            "Returns the list of available Stable Diffusion image generation models on this device.",
            """{"type":"object","properties":{},"required":[]}"""
        ),
        ToolSchema(
            "search_memory",
            "Searches stored conversation memories.",
            """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}"""
        ),
        // CALENDAR_DISABLED
        // ToolSchema(
        //     "add_calendar_event",
        //     "Adds a calendar event.",
        //     """{"type":"object","properties":{"title":{"type":"string"},"start_time":{"type":"string"},"end_time":{"type":"string"},"description":{"type":"string"}},"required":["title","start_time"]}"""
        // ),
        // ToolSchema(
        //     "list_calendar_events",
        //     "Lists upcoming calendar events.",
        //     """{"type":"object","properties":{"days_ahead":{"type":"integer"}},"required":[]}"""
        // ),
        ToolSchema(
            "web_search",
            "Searches the web for information.",
            """{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}},"required":["query"]}"""
        )
    )

    private val schemaByTool = mapOf(
        NezumiTool.GET_TIME to "get_current_time",
        NezumiTool.GET_BATTERY to "get_battery_level",
        NezumiTool.SET_ALARM to "set_alarm",
        NezumiTool.DISMISS_ALARM to "dismiss_alarm",
        NezumiTool.LIST_ALARMS to "list_alarms",
        NezumiTool.FLASHLIGHT to "set_flashlight",
        NezumiTool.START_TIMER to "start_timer",
        NezumiTool.STOP_TIMER to "stop_timer",
        NezumiTool.LIST_TIMERS to "list_timers",
        NezumiTool.GENERATE_IMAGE to "generate_image",
        NezumiTool.SEARCH_MEMORY to "search_memory",
        // CALENDAR_DISABLED
        // NezumiTool.ADD_CALENDAR_EVENT to "add_calendar_event",
        // NezumiTool.LIST_CALENDAR_EVENTS to "list_calendar_events",
        NezumiTool.WEB_SEARCH to "web_search"
    )

    fun appendToolDefinitions(context: Context, systemPrompt: String): String {
        val enabled = ToolPreferences(context).getEnabledTools()
        val enabledNames = buildSet {
            enabled.forEach { tool -> schemaByTool[tool]?.let { add(it) } }
            if (NezumiTool.LIST_ALARMS in enabled &&
                (NezumiTool.SET_ALARM in enabled || NezumiTool.DISMISS_ALARM in enabled)
            ) {
                add("list_alarms")
            }
            if (NezumiTool.LIST_TIMERS in enabled &&
                (NezumiTool.START_TIMER in enabled || NezumiTool.STOP_TIMER in enabled)
            ) {
                add("list_timers")
            }
            if (NezumiTool.GENERATE_IMAGE in enabled) {
                add("list_sd_models")
            }
        }
        if (enabledNames.isEmpty()) {
            Log.d(TAG, "GgufToolPromptBuilder: Skipped. Reason: No enabled tools selected.")
            return systemPrompt
        }

        val schemas = allSchemas.filter { it.name in enabledNames }
        if (schemas.isEmpty()) {
            Log.d(TAG, "GgufToolPromptBuilder: Skipped. Reason: No matching tool schemas for enabled tools.")
            return systemPrompt
        }

        val registry = McpToolRegistry.get(context)
        var mcpTools = registry.currentTools()
        val activeIds = ToolPreferences(context).getActiveMcpServerIds()
        // MCP のレジストリが未初期化なのにアクティブサーバーがある場合はこのターンで同期リフレッシュして取り込む
        if (mcpTools.isEmpty() && activeIds.isNotEmpty()) {
            Log.d(TAG, "GgufToolPromptBuilder: MCP cache empty but ${activeIds.size} server(s) active - refreshing synchronously")
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                        registry.refresh(activeIds, force = true)
                    }
                }
            }
            mcpTools = registry.currentTools()
        }
        val builtinJson = schemas.joinToString("\n") { schema ->
            """{"type":"function","function":{"name":"${schema.name}","description":"${schema.description}","parameters":${schema.parametersJson}}}"""
        }
        val mcpJson = mcpTools.joinToString("\n") { desc ->
            val safeDesc = (desc.description.ifBlank { "MCP tool from ${desc.serverName}" })
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            """{"type":"function","function":{"name":"${desc.qualifiedName}","description":"[MCP:${desc.serverName}] $safeDesc","parameters":${desc.inputSchemaJson.ifBlank { "{\"type\":\"object\",\"properties\":{}}" }}}}"""
        }
        val toolsJson = listOf(builtinJson, mcpJson).filter { it.isNotBlank() }.joinToString("\n")

        val toolBlock = buildString {
            appendLine()
            appendLine()
            appendLine("You can call tools to help the user.")
            appendLine("Available tools are listed in <tools></tools>.")
            appendLine("When calling a tool, respond ONLY with:")
            appendLine("<tool_call>")
            appendLine("""{"name":"<tool-name>","arguments":{...}}""")
            appendLine("</tool_call>")
            appendLine("<tools>")
            append(toolsJson)
            appendLine()
            append("</tools>")
        }

        return if (systemPrompt.isBlank()) toolBlock.trim() else systemPrompt + toolBlock
    }
}
