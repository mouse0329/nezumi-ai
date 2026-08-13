package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log

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
        ToolSchema(
            "save_memory",
            "Persist an important fact for future recall. Call this when the user shares information worth remembering (preferences, profile, key decisions, plans). Content should be concise, self-contained, third-person or declarative, and stand on its own without conversation context.",
            """{"type":"object","properties":{"content":{"type":"string","description":"The fact to remember. Concise, self-contained."},"importance":{"type":"number","description":"0.0-1.0 (default 0.7)"}},"required":["content"]}"""
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
        ),
        ToolSchema(
            "web_fetch",
            "Fetches a web page and returns its content as Markdown text. Use this to read the body of a URL found by web_search.",
            """{"type":"object","properties":{"url":{"type":"string"},"max_chars":{"type":"integer"}},"required":["url"]}"""
        ),
        ToolSchema(
            "convert_md_to_document",
            "Prepare a Word (.docx), PDF (.pdf), or Excel (.xlsx) document from Markdown text. Supports headings, paragraphs, lists, tables, code blocks, quotes, bold/italic text. The content is placed on a card in the chat; the file is converted and saved when the user taps Save.",
            """{"type":"object","properties":{"markdown":{"type":"string","description":"The Markdown text to convert"},"format":{"type":"string","description":"Output format: 'docx', 'pdf', or 'xlsx'"},"fileName":{"type":"string","description":"Optional output file name without extension"}},"required":["markdown","format"]}"""
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
        NezumiTool.SAVE_MEMORY to "save_memory",
        // CALENDAR_DISABLED
        // NezumiTool.ADD_CALENDAR_EVENT to "add_calendar_event",
        // NezumiTool.LIST_CALENDAR_EVENTS to "list_calendar_events",
        NezumiTool.WEB_SEARCH to "web_search",
        NezumiTool.WEB_FETCH to "web_fetch",
        NezumiTool.CONVERT_MD_TO_DOCUMENT to "convert_md_to_document"
    )

    /**
     * 有効化されたビルトインツールと MCP ツールを列挙した JSON を返す。
     * (改行区切り。空文字なら有効ツールなし)
     */
    private fun collectEnabledToolsJson(context: Context): String {
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
        val schemas = allSchemas.filter { it.name in enabledNames }
        val mcpJson = McpToolPromptBuilder.currentToolsJson(context)
        val builtinJson = schemas.joinToString("\n") { schema ->
            """{"type":"function","function":{"name":"${schema.name}","description":"${schema.description}","parameters":${schema.parametersJson}}}"""
        }
        val toolsJson = listOf(builtinJson, mcpJson).filter { it.isNotBlank() }.joinToString("\n")
        Log.d(
            TAG,
            "collectEnabledToolsJson: enabled=${enabled.map { it.name }} " +
                "builtinSchemas=${schemas.size} mcpJsonEmpty=${mcpJson.isBlank()}"
        )
        return toolsJson
    }

    fun appendToolDefinitions(
        context: Context,
        systemPrompt: String,
        isGemma4: Boolean = false
    ): String {
        val toolsJson = collectEnabledToolsJson(context)
        // Bug fix: 組み込みツールが 1 つも有効でなくても、MCP サーバーが接続されていれば
        // MCP ツールだけを列挙する。以前はここで早期 return していたため、
        //「MCP だけ使いたい」プリセットでは MCP ツールが一切見えなかった。
        if (toolsJson.isBlank()) {
            Log.d(TAG, "GgufToolPromptBuilder: Skipped. Reason: no builtin schema and no MCP tool.")
            return systemPrompt
        }

        // Gemma 4 系は Google 公式仕様 (<|tool_call>call:NAME{...}<tool_call|>) を使う。
        // それ以外のモデル (Qwen 等) は従来通りの <tool_call>{"name":..,"arguments":..}</tool_call> 形式。
        val toolBlock = if (isGemma4) buildGemma4ToolBlock(toolsJson) else buildGenericToolBlock(toolsJson)

        return if (systemPrompt.isBlank()) toolBlock.trim() else systemPrompt + toolBlock
    }

    /**
     * v2.1+: LiteRT-LM 経路向けのツールブロック注入。
     *
     * 以前は LiteRT-LM ではビルトインツール定義がシステムプロンプトに一切書かれず、
     * ネイティブ ToolProvider として `createConversation(tools=...)` に渡してはいるものの、
     * automaticToolCalling=false のためモデル自身が「呼び出せるツールがある」ことに気付かず、
     * 結果としてツールが有効化されているのに使われない状態になっていた。
     * ここで GGUF と同じ <tools> ブロックを LiteRT-LM 側にも注入する。
     *
     * MCP ツールもこの <tools> にマージされるため、`McpToolPromptBuilder.appendForLiteRt`
     * を追加で呼ぶ必要はない (二重注入回避)。
     */
    fun appendForLiteRt(
        context: Context,
        systemPrompt: String,
        isGemma4: Boolean = false
    ): String {
        val toolsJson = collectEnabledToolsJson(context)
        if (toolsJson.isBlank()) {
            Log.d(TAG, "appendForLiteRt: no builtin schema and no MCP tool - skipping")
            return systemPrompt
        }
        val toolBlock = if (isGemma4) buildGemma4ToolBlock(toolsJson) else buildGenericToolBlock(toolsJson)
        Log.i(TAG, "appendForLiteRt: injected <tools> block into LiteRT-LM system prompt (gemma4=$isGemma4)")
        return if (systemPrompt.isBlank()) toolBlock.trim() else systemPrompt + toolBlock
    }

    /**
     * 汎用 (Qwen 等) の `<tool_call>...</tool_call>` + JSON 形式を教える指示ブロック。
     */
    private fun buildGenericToolBlock(toolsJson: String): String = buildString {
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

    /**
     * Gemma 4 (Google 公式仕様) の `<|tool_call>call:NAME{...}<tool_call|>` 形式を教える指示ブロック。
     *
     * 参考:
     *   - `<|tool_call>` / `<tool_call|>` は Gemma 4 tokenizer 上の専用トークンで、
     *     Google AI 公式の function calling サンプルはこの形で応答することを前提にしている。
     *   - 閉じタグ `<tool_call|>` を吐き忘れる既知のクセに備え、パーサ側 (salvageGemma4Payload)
     *     で救済しているが、まずはプロンプトで正しい形を強く指示しておく。
     */
    private fun buildGemma4ToolBlock(toolsJson: String): String = buildString {
        appendLine()
        appendLine()
        appendLine("You can call tools to help the user.")
        appendLine("Available tools are listed in <tools></tools>.")
        appendLine("When calling a tool, respond ONLY with the following Gemma tool-call format")
        appendLine("(do NOT use JSON `name`/`arguments` wrappers, do NOT use <tool_call> tags):")
        appendLine("<|tool_call>call:<tool-name>{\"arg\":value, ...}<tool_call|>")
        appendLine("Always close the call with <tool_call|>. Emit multiple calls back-to-back if needed.")
        appendLine("<tools>")
        append(toolsJson)
        appendLine()
        append("</tools>")
    }
}
