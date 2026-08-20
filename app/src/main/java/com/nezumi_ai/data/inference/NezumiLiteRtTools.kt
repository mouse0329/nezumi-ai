package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.nezumi_ai.data.database.dao.AlarmDao
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.mcp.McpToolRegistry
import com.nezumi_ai.data.tools.ToolSystemController
import com.nezumi_ai.utils.PreferencesHelper
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.nezumi_ai.data.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private const val TOOL_TAG = "NezumiTools"

// LiteRTに渡すツール名の正規化マップ（ホワイトリスト方式）
// LiteRTが返すツール名の揺れをここで吸収する
private val TOOL_NAME_MAP = mapOf(
    // get_current_time
    "get_current_time"   to "getcurrenttime",
    "getCurrentTime"     to "getcurrenttime",
    "getcurrenttime"     to "getcurrenttime",
    // get_battery_level
    "get_battery_level"  to "getbatterylevel",
    "getBatteryLevel"    to "getbatterylevel",
    "getbatterylevel"    to "getbatterylevel",
    // set_alarm
    "set_alarm"          to "setalarm",
    "setAlarm"           to "setalarm",
    "setalarm"           to "setalarm",
    // dismiss_alarm
    "dismiss_alarm"      to "dismissalarm",
    "dismissAlarm"       to "dismissalarm",
    "dismissalarm"       to "dismissalarm",
    // list_alarms
    "list_alarms"        to "listalarms",
    "listAlarms"         to "listalarms",
    "listalarms"         to "listalarms",
    // flashlight（snake_case / camelCase / 旧名 "flashlight" すべて吸収）
    "set_flashlight"     to "setflashlight",
    "setFlashlight"      to "setflashlight",
    "setflashlight"      to "setflashlight",
    "flashlight"         to "setflashlight",
    // start_timer
    "start_timer"        to "starttimer",
    "startTimer"         to "starttimer",
    "starttimer"         to "starttimer",
    // stop_timer
    "stop_timer"         to "stoptimer",
    "stopTimer"          to "stoptimer",
    "stoptimer"          to "stoptimer",
    // list_timers
    "list_timers"        to "listtimers",
    "listTimers"         to "listtimers",
    "listtimers"         to "listtimers",
    // generate_image
    "generate_image"     to "generateimage",
    "generateImage"      to "generateimage",
    "generateimage"      to "generateimage",
    // list_sd_models
    "list_sd_models"     to "listsdmodels",
    "listSdModels"       to "listsdmodels",
    "listsdmodels"       to "listsdmodels",
    // search_memory
    "search_memory"      to "searchmemory",
    "searchMemory"       to "searchmemory",
    "searchmemory"       to "searchmemory",
    // save_memory (LLM が明示的にメモリを追加保存するためのツール)
    "save_memory"        to "savememory",
    "saveMemory"         to "savememory",
    "savememory"         to "savememory",
    "remember"           to "savememory",
    // CALENDAR_DISABLED
    // "add_calendar_event" to "addcalendarevent",
    // "addCalendarEvent"   to "addcalendarevent",
    // "addcalendarevent"   to "addcalendarevent",
    // // list_calendar_events
    // "list_calendar_events" to "listcalendarevents",
    // "listCalendarEvents"   to "listcalendarevents",
    // "listcalendarevents"   to "listcalendarevents",
    // web_search
    "web_search"           to "websearch",
    "webSearch"            to "websearch",
    "websearch"            to "websearch",
    // web_fetch
    "web_fetch"            to "webfetch",
    "webFetch"             to "webfetch",
    "webfetch"             to "webfetch",
    // MCP generic dispatcher
    "mcp_call"             to "mcpcall",
    "mcpCall"              to "mcpcall",
    "mcpcall"              to "mcpcall",
    // MCP tool discovery（system prompt が届かなかった場合の保険）
    "mcp_list_tools"       to "mcplisttools",
    "mcpListTools"         to "mcplisttools",
    "mcplisttools"         to "mcplisttools",
    // convert_md_to_document: Markdown → Word/PDF/Excel
    "convert_md_to_document" to "convertmdtodocument",
    "get_skill"           to "getskill",
    "getSkill"            to "getskill",
    "getskill"            to "getskill",
    "convertMdToDocument"    to "convertmdtodocument",
    "convertmdtodocument"    to "convertmdtodocument",
)

// ─────────────────────────────────────────────
// スキーマ登録用 ToolProvider ビルダー
// ToolSet のメソッドはスキーマ定義のみ。実行ロジックは持たない。
// ─────────────────────────────────────────────

internal fun buildEnabledToolProviders(context: Context, alarmDao: AlarmDao): List<ToolProvider> {
    val enabled = ToolPreferences(context).getEnabledTools()
    Log.d(TOOL_TAG, "Building tool providers. Enabled tools: ${enabled.map { it.name }}")
    return buildList {
        if (NezumiTool.GET_TIME in enabled)      add(tool(GetTimeSchema()))
        if (NezumiTool.GET_BATTERY in enabled)   add(tool(GetBatterySchema()))
        if (NezumiTool.SET_ALARM in enabled)     add(tool(SetAlarmSchema()))
        if (NezumiTool.DISMISS_ALARM in enabled) add(tool(DismissAlarmSchema()))
        // LIST は SET か DISMISS どちらかが有効なら表示できる
        if (NezumiTool.LIST_ALARMS in enabled &&
            (NezumiTool.SET_ALARM in enabled || NezumiTool.DISMISS_ALARM in enabled)
        ) {
            add(tool(ListAlarmsSchema()))
        }
        if (NezumiTool.FLASHLIGHT in enabled)    add(tool(FlashlightSchema()))
        if (NezumiTool.START_TIMER in enabled)   add(tool(StartTimerSchema()))
        if (NezumiTool.STOP_TIMER in enabled)    add(tool(StopTimerSchema()))
        // LIST は START か STOP どちらかが有効なら表示できる
        if (NezumiTool.LIST_TIMERS in enabled &&
            (NezumiTool.START_TIMER in enabled || NezumiTool.STOP_TIMER in enabled)
        ) {
            add(tool(ListTimersSchema()))
        }
        if (NezumiTool.GENERATE_IMAGE in enabled) {
            add(tool(GenerateImageSchema()))
            add(tool(ListSdModelsSchema()))
        }
        if (NezumiTool.SEARCH_MEMORY in enabled) {
            Log.d(TOOL_TAG, "Adding SearchMemorySchema to tool providers")
            add(tool(SearchMemorySchema()))
        }
        if (NezumiTool.SAVE_MEMORY in enabled) {
            Log.d(TOOL_TAG, "Adding SaveMemorySchema to tool providers")
            add(tool(SaveMemorySchema()))
        }
        // CALENDAR_DISABLED
        // if (NezumiTool.ADD_CALENDAR_EVENT in enabled) add(tool(AddCalendarEventSchema()))
        // if (NezumiTool.LIST_CALENDAR_EVENTS in enabled) add(tool(ListCalendarEventsSchema()))
        if (NezumiTool.WEB_SEARCH in enabled) {
            Log.d(TOOL_TAG, "Adding WebSearchSchema to tool providers")
            add(tool(WebSearchSchema()))
        }
        if (NezumiTool.WEB_FETCH in enabled) {
            Log.d(TOOL_TAG, "Adding WebFetchSchema to tool providers")
            add(tool(WebFetchSchema()))
        }
        if (NezumiTool.CONVERT_MD_TO_DOCUMENT in enabled) {
            Log.d(TOOL_TAG, "Adding ConvertMdToDocumentSchema to tool providers")
            add(tool(ConvertMdToDocumentSchema()))
        }
        // MCP: 現在のプリセットに MCP サーバーが付いていれば汎用ディスパッチャを公開する。
        // LiteRT はアノテーション経由の固定スキーマのため、別途 tools/list の内容は
        // system prompt 側で列挙して LLM に伝え、実行はこの mcp_call に集約する。
        //
        // Bug fix: 以前は「tools/list のキャッシュが既に埋まっていること」を条件にしていたため、
        // アプリ起動直後（プリセット選択時の非同期 refresh がまだ終わっていない）や
        // 一時的にサーバーへ到達できなかった場合に mcp_call すら登録されず、
        // MCP ツールが恒久的に見えなくなっていた。
        // サーバーが 1 つでも紐付いていれば必ず公開し、一覧は mcp_list_tools で
        // 実行時に取得できるようにする。
        val mcpRegistry = McpToolRegistry.get(context)
        if (mcpRegistry.hasActiveServers() || mcpRegistry.currentTools().isNotEmpty()) {
            Log.d(TOOL_TAG, "Adding McpCallSchema / McpListToolsSchema to tool providers")
            add(tool(McpCallSchema()))
            add(tool(McpListToolsSchema()))
        }
    }.also {
        Log.d(TOOL_TAG, "Total tool providers registered: ${it.size}")
    }
}

// ─────────────────────────────────────────────
// スキーマ専用 ToolSet（ボディはスタブ）
// LiteRT はここからツール名・引数定義を読み取る。
// automaticToolCalling = false のため実際には呼ばれない。
// ─────────────────────────────────────────────

private class GetTimeSchema : ToolSet {
    @Tool(description = "Returns current device datetime.")
    fun getCurrentTime(
        @ToolParam(description = "IANA timezone. e.g. Asia/Tokyo") timezone: String?
    ): Map<String, String> = emptyMap()
}

private class GetBatterySchema : ToolSet {
    @Tool(description = "Returns current device battery level and status.")
    fun getBatteryLevel(): Map<String, Any?> = emptyMap()
}

private class SetAlarmSchema : ToolSet {
    @Tool(description = "Sets a system alarm at the given hour and minute.")
    fun setAlarm(
        @ToolParam(description = "Hour in 24h format, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int,
        @ToolParam(description = "Optional alarm label") label: String?
    ): Map<String, Any?> = emptyMap()
}

private class DismissAlarmSchema : ToolSet {
    @Tool(description = "Dismisses a system alarm by hour and minute.")
    fun dismissAlarm(
        @ToolParam(description = "Hour in 24h format, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int
    ): Map<String, Any?> = emptyMap()
}

private class ListAlarmsSchema : ToolSet {
    @Tool(description = "Returns alarms managed by nezumi-ai.")
    fun listAlarms(): Map<String, Any?> = emptyMap()
}

private class FlashlightSchema : ToolSet {
    @Tool(description = "Turns flashlight on or off.")
    fun setFlashlight(
        @ToolParam(description = "true turns on, false turns off") on: Boolean
    ): Map<String, Any?> = emptyMap()
}

private class StartTimerSchema : ToolSet {
    @Tool(description = "Starts a timer for the specified duration in seconds.")
    fun startTimer(
        @ToolParam(description = "Duration in seconds") durationSeconds: Int,
        @ToolParam(description = "Optional label for the timer") label: String?
    ): Map<String, Any?> = emptyMap()
}

private class StopTimerSchema : ToolSet {
    @Tool(description = "Stops a running timer by its ID.")
    fun stopTimer(
        @ToolParam(description = "Timer ID to stop") timerId: String
    ): Map<String, Any?> = emptyMap()
}

private class ListTimersSchema : ToolSet {
    @Tool(description = "Lists all currently running timers.")
    fun listTimers(): Map<String, Any?> = emptyMap()
}

private class GenerateImageSchema : ToolSet {
    @Tool(description = "Generate an image from a text prompt using Stable Diffusion. Call list_sd_models to get available model names.")
    fun generateImage(
        @ToolParam(description = "English image generation prompt, detailed and descriptive") prompt: String,
        @ToolParam(description = "Things to avoid in the image (optional)") negativePrompt: String?,
        @ToolParam(description = "Model directory name from list_sd_models (optional)") model: String?,
        @ToolParam(description = "256, 512, or 768 (default 256)") width: Int?,
        @ToolParam(description = "256, 512, or 768 (default 256)") height: Int?
    ): Map<String, Any?> = emptyMap()
}

private class ListSdModelsSchema : ToolSet {
    @Tool(description = "List available Stable Diffusion image generation models on this device")
    fun listSdModels(): Map<String, Any?> = emptyMap()
}

private class SearchMemorySchema : ToolSet {
    @Tool(description = "Search stored memories by semantic similarity to a query")
    fun searchMemory(
        @ToolParam(description = "Search query text") query: String,
        @ToolParam(description = "Maximum number of results (default 5)") topK: Int?
    ): Map<String, Any?> = emptyMap()
}

/**
 * LLM が「これを覚えておいて」と明示的に呼ぶツール。
 * TOOL_ONLY モードではこのツールの呼び出しが唯一のメモリ保存経路になる。
 */
private class SaveMemorySchema : ToolSet {
    @Tool(
        description = "Persist an important fact for future recall. " +
            "Call this when the user shares information worth remembering (preferences, profile, key decisions, plans). " +
            "Content should be concise, self-contained, third-person or declarative, and stand on its own without conversation context."
    )
    fun saveMemory(
        @ToolParam(description = "The fact to remember. Concise, self-contained, third-person or declarative form.") content: String,
        @ToolParam(description = "Importance from 0.0 to 1.0 (default 0.7)") importance: Double?
    ): Map<String, Any?> = emptyMap()
}

private class AddCalendarEventSchema : ToolSet {
    @Tool(description = "Add an event to the device calendar")
    fun addCalendarEvent(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "Start time: year (e.g. 2024)") startYear: Int,
        @ToolParam(description = "Start time: month (1-12)") startMonth: Int,
        @ToolParam(description = "Start time: day (1-31)") startDay: Int,
        @ToolParam(description = "Start time: hour (0-23)") startHour: Int,
        @ToolParam(description = "Start time: minute (0-59)") startMinute: Int,
        @ToolParam(description = "Duration in minutes") durationMinutes: Int,
        @ToolParam(description = "Event description (optional)") description: String?,
        @ToolParam(description = "Event location (optional)") location: String?
    ): Map<String, Any?> = emptyMap()
}

private class ListCalendarEventsSchema : ToolSet {
    @Tool(description = "List calendar events within a time range")
    fun listCalendarEvents(
        @ToolParam(description = "Start year (e.g. 2024)") startYear: Int,
        @ToolParam(description = "Start month (1-12)") startMonth: Int,
        @ToolParam(description = "Start day (1-31)") startDay: Int,
        @ToolParam(description = "End year (e.g. 2024)") endYear: Int,
        @ToolParam(description = "End month (1-12)") endMonth: Int,
        @ToolParam(description = "End day (1-31)") endDay: Int
    ): Map<String, Any?> = emptyMap()
}

private class McpCallSchema : ToolSet {
    @Tool(description = "Invoke a tool exposed by a connected MCP server. Use the fully-qualified tool name from the system prompt's <tools> list (starts with 'mcp__'). Arguments must be a JSON object matching that tool's inputSchema.")
    fun mcpCall(
        @ToolParam(description = "Fully qualified MCP tool name, e.g. mcp__abcd1234__list_files") name: String,
        @ToolParam(description = "JSON string of arguments for the tool (defaults to '{}')") argumentsJson: String?
    ): Map<String, Any?> = emptyMap()
}

private class McpListToolsSchema : ToolSet {
    @Tool(description = "List every tool currently exposed by the connected MCP servers, with its fully-qualified name (mcp__...) and JSON input schema. Call this first when you are unsure which MCP tools exist, then invoke one with mcp_call.")
    fun mcpListTools(): Map<String, Any?> = emptyMap()
}

private class WebSearchSchema : ToolSet {
    @Tool(description = "Search the web using Brave Search API")
    fun webSearch(
        @ToolParam(description = "Search query text") query: String,
        @ToolParam(description = "Result count 1-20") count: Int?,
        @ToolParam(description = "Page offset 0-9") offset: Int?,
        @ToolParam(description = "2-letter ISO country code only. Example: JP, US, GB") country: String?,
        @ToolParam(description = "2-letter language code only. Example: ja, en") searchLang: String?,
        @ToolParam(description = "Safe search: off, moderate, or strict") safeSearch: String?
    ): Map<String, Any?> = emptyMap()
}

private class WebFetchSchema : ToolSet {
    @Tool(
        description = "Fetch a web page and return its content as Markdown text. " +
            "Use this to read the body of a URL found by web_search. " +
            "JavaScript-rendered pages are not supported."
    )
    fun webFetch(
        @ToolParam(description = "http(s) URL of the page to fetch") url: String,
        @ToolParam(description = "Maximum characters of Markdown to return (default 4000, max 12000)") maxChars: Int?
    ): Map<String, Any?> = emptyMap()
}

private class ConvertMdToDocumentSchema : ToolSet {
    @Tool(
        description = "Convert Markdown text into a Word (.docx), PDF (.pdf), or Excel (.xlsx) file. " +
            "Supports headings, paragraphs, bullet/numbered lists, tables, code blocks, quotes, bold/italic text. " +
            "The content is placed on a card in the chat. The actual file is converted and saved when the user taps Save."
    )
    fun convertMdToDocument(
        @ToolParam(description = "The Markdown text to convert") markdown: String,
        @ToolParam(description = "Output format: 'docx', 'pdf', or 'xlsx'") format: String,
        @ToolParam(description = "Optional output file name without extension") fileName: String?
    ): Map<String, Any?> = emptyMap()
}

// ─────────────────────────────────────────────
// 実行エンジン（単一責任・全ロジックここに集約）
// ─────────────────────────────────────────────

/**
 * @param payload UI カード描画用のフルペイロード (InlineToolCallCard / ToolResultCard が参照する)。
 * @param modelPayload モデルへの `<tool_response>` に埋め込むペイロード。省略時は [payload] と同一。
 *   ドキュメント作成ツールのように、UI 表示には元テキスト全文が必要だがモデルには
 *   その全文を再送する必要がない (むしろコンテキストの無駄になる) ケースで、
 *   UI 向けとモデル向けのペイロードを分離するために使う。
 *   [GgufToolCallParser.formatToolResults] はこちらを使ってモデル向けレスポンスを組み立てる。
 */
data class ToolExecutionResult(
    val success: Boolean,
    val payload: Map<String, Any?>,
    val modelPayload: Map<String, Any?>? = null
) {
    /** モデルに実際に送るペイロード。[modelPayload] が未指定なら [payload] を使う (後方互換)。 */
    val payloadForModel: Map<String, Any?> get() = modelPayload ?: payload
}

internal class NezumiLiteRtToolExecutor(
    private val context: Context,
    private val alarmDao: AlarmDao,
    private val memoryRepository: MemoryRepository? = null,
    private val memoryEmbedder: MemoryTextEmbedder? = null
) {
    suspend fun execute(toolCall: ToolCall): ToolExecutionResult {
        val normalized = normalizeToolName(toolCall.name)
        val toolPrefs = ToolPreferences(context)
        val gate = gateTool(normalized)
        if (gate != null && !toolPrefs.isEnabled(gate)) {
            Log.w(TOOL_TAG, "Tool disabled by preferences: name=${toolCall.name} normalized=$normalized tool=$gate")
            return ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "tool_disabled",
                    "tool" to toolCall.name
                )
            )
        }

        return when (normalized) {
            "getcurrenttime"  -> executeGetCurrentTime(toolCall)
            "getbatterylevel" -> executeGetBatteryLevel()
            "setalarm"        -> executeSetAlarm(toolCall)
            "dismissalarm"    -> executeDismissAlarm(toolCall)
            "listalarms"      -> executeListAlarms()
            "setflashlight"   -> executeSetFlashlight(toolCall)
            "starttimer"      -> executeStartTimer(toolCall)
            "stoptimer"       -> executeStopTimer(toolCall)
            "listtimers"      -> executeListTimers()
            "generateimage"   -> executeGenerateImage(toolCall)
            "listsdmodels"    -> executeListSdModels()
            "searchmemory"    -> executeSearchMemory(toolCall)
            "savememory"      -> executeSaveMemory(toolCall)
            // CALENDAR_DISABLED
            // "addcalendarevent" -> executeAddCalendarEvent(toolCall)
            // "listcalendarevents" -> executeListCalendarEvents(toolCall)
            "websearch"       -> executeWebSearch(toolCall)
            "webfetch"        -> executeWebFetch(toolCall)
            "convertmdtodocument"  -> executeConvertMdToDocument(toolCall)
            "getskill" -> executeGetSkill(toolCall)
            "mcpcall"         -> executeMcpCall(toolCall)
            "mcplisttools"    -> executeMcpListTools()
            else -> {
                // フォールバック: LLM が mcp__... を直接呼んだ場合はそのままディスパッチ
                if (toolCall.name.startsWith("mcp__")) {
                    return executeMcpToolByQualifiedName(toolCall)
                }
                Log.w(TOOL_TAG, "Unknown tool: ${toolCall.name}")
                ToolExecutionResult(
                    success = false,
                    payload = mapOf("success" to false, "error" to "unknown_tool:${toolCall.name}")
                )
            }
        }
    }

    private fun gateTool(normalizedToolName: String): NezumiTool? {
        return when (normalizedToolName) {
            "getcurrenttime" -> NezumiTool.GET_TIME
            "getbatterylevel" -> NezumiTool.GET_BATTERY
            "setalarm" -> NezumiTool.SET_ALARM
            "dismissalarm" -> NezumiTool.DISMISS_ALARM
            "listalarms" -> NezumiTool.LIST_ALARMS
            "setflashlight" -> NezumiTool.FLASHLIGHT
            "starttimer" -> NezumiTool.START_TIMER
            "stoptimer" -> NezumiTool.STOP_TIMER
            "listtimers" -> NezumiTool.LIST_TIMERS
            "generateimage" -> NezumiTool.GENERATE_IMAGE
            "listsdmodels" -> NezumiTool.GENERATE_IMAGE
            "searchmemory" -> NezumiTool.SEARCH_MEMORY
            "savememory" -> NezumiTool.SAVE_MEMORY
            // CALENDAR_DISABLED
            // "addcalendarevent" -> NezumiTool.ADD_CALENDAR_EVENT
            // "listcalendarevents" -> NezumiTool.LIST_CALENDAR_EVENTS
            "websearch" -> NezumiTool.WEB_SEARCH
            "webfetch" -> NezumiTool.WEB_FETCH
            "convertmdtodocument" -> NezumiTool.CONVERT_MD_TO_DOCUMENT
            // mcpcall / mcplisttools はプリセット側の MCP サーバー ID で制御されるため、
            // NezumiTool にはマッピングしない
            "mcpcall" -> null
            "mcplisttools" -> null
            "getskill" -> null
            else -> null
        }
    }

    private fun normalizeToolName(name: String): String {
        TOOL_NAME_MAP[name]?.let { return it }
        
        // フォールバック: マップに未登録のツール名
        val normalized = name.replace("_", "").lowercase(Locale.US)
        Log.w(TOOL_TAG, "Tool name not in whitelist: original=$name normalized=$normalized")
        recordUnknownToolNameMetric(original = name, normalized = normalized)
        return normalized
    }

    private suspend fun executeGetSkill(toolCall: ToolCall): ToolExecutionResult {
        val skillName = toolCall.arguments["skillName"]?.toString()
            ?: toolCall.arguments["skill_name"]?.toString()
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_skill_name"))
        val referencePath = toolCall.arguments["referencePath"]?.toString()
            ?: toolCall.arguments["reference_path"]?.toString()
        val preset = NezumiAiDatabase.getInstance(context).presetDao()
            .getById(PreferencesHelper.getCurrentPresetId(context))
        if (preset?.skillsEnabled != true) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "skills_disabled_for_preset"))
        }
        val hidden = runCatching {
            val names = org.json.JSONArray(preset.hiddenSkillNames)
            buildSet { for (index in 0 until names.length()) add(names.getString(index)) }
        }.getOrDefault(emptySet())
        if (skillName in hidden) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "skill_not_available"))
        }
        val result = SkillRepository(context).read(skillName, referencePath)
        return result.fold(
            onSuccess = { ToolExecutionResult(true, mapOf("success" to true, "skillName" to skillName, "content" to it)) },
            onFailure = { ToolExecutionResult(false, mapOf("success" to false, "error" to (it.message ?: "skill_read_failed"))) }
        )
    }

    private fun recordUnknownToolNameMetric(original: String, normalized: String) {
        // メトリクスを記録（未知のツール名の候補を追跡可能にする）
        // 本番環境：Firebase Analytics, Crashlytics などに送信可能
        Log.d(TOOL_TAG, "METRIC_UNKNOWN_TOOL | original=$original | normalized=$normalized")
    }

    // ── 各ツール実装 ──────────────────────────────

    private fun executeGetCurrentTime(toolCall: ToolCall): ToolExecutionResult {
        val timezone = toolCall.arguments["timezone"]?.toString()?.takeIf { it.isNotBlank() }
        val zone = runCatching {
            if (timezone != null) ZoneId.of(timezone) else ZoneId.systemDefault()
        }.getOrElse {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "invalid_timezone")
            )
        }
        val now = ZonedDateTime.now(zone)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "datetime"     to now.format(fmt),
                "timezone"     to zone.id,
                "timestamp_ms" to now.toInstant().toEpochMilli().toString()
            )
        )
    }

    private suspend fun executeGetBatteryLevel(): ToolExecutionResult {
        val result = ToolSystemController.getBatteryLevel(context)
        return if (result.isSuccess) {
            ToolExecutionResult(
                success = true,
                payload = result.getOrElse { emptyMap() }
            )
        } else {
            ToolExecutionResult(
                success = false,
                payload = mapOf("error" to "battery_status_failed:${result.exceptionOrNull()?.message.orEmpty()}")
            )
        }
    }

    private suspend fun executeSetAlarm(toolCall: ToolCall): ToolExecutionResult {
        val hour = toolCall.arguments.readInt("hour")
        val minute = toolCall.arguments.readInt("minute")
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_time"))
        }
        val label = toolCall.arguments["label"]?.toString()?.takeIf { it.isNotBlank() } ?: "nezumi-ai alarm"
        val intentResult = ToolSystemController.setAlarm(context, hour, minute, label)
        if (intentResult.isFailure) {
            return ToolExecutionResult(
                false,
                mapOf("success" to false, "error" to "set_alarm_failed:${intentResult.exceptionOrNull()?.message.orEmpty()}")
            )
        }
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            alarmDao.insert(AlarmEntity(
                title = label,
                hour = hour,
                minute = minute,
                label = label,
                triggerTime = now,
                isEnabled = true
            ))
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf("success" to true, "hour" to hour, "minute" to minute, "label" to label)
        )
    }

    private suspend fun executeDismissAlarm(toolCall: ToolCall): ToolExecutionResult {
        val hour = toolCall.arguments.readInt("hour")
        val minute = toolCall.arguments.readInt("minute")
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_time"))
        }
        val intentResult = ToolSystemController.dismissAlarm(context, hour, minute)
        if (intentResult.isFailure) {
            return ToolExecutionResult(
                false,
                mapOf("success" to false, "error" to "dismiss_alarm_failed:${intentResult.exceptionOrNull()?.message.orEmpty()}")
            )
        }
        withContext(Dispatchers.IO) {
            val alarm = alarmDao.getAllAlarms().find { it.hour == hour && it.minute == minute }
            if (alarm != null) {
                alarmDao.deleteById(alarm.id)
            }
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf("success" to true, "hour" to hour, "minute" to minute)
        )
    }

    private suspend fun executeListAlarms(): ToolExecutionResult {
        val alarms = withContext(Dispatchers.IO) { alarmDao.getAllAlarms() }
        val rows = alarms.map {
            mapOf(
                "id" to it.id,
                "hour" to it.hour,
                "minute" to it.minute,
                "label" to it.label,
                "enabled" to it.isEnabled
            )
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "count" to rows.size,
                "alarms" to rows,
                "note" to "May differ if alarms were modified outside nezumi-ai"
            )
        )
    }

    private suspend fun executeSetFlashlight(toolCall: ToolCall): ToolExecutionResult {
        if (!ToolSystemController.hasFlashlightPermission(context)) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "permission_denied"))
        }
        val on = toolCall.arguments.readBooleanAny("on", "enabled", "state", "mode")
        val result = ToolSystemController.toggleFlashlight(context, on)
        return if (result.isSuccess) {
            ToolExecutionResult(true, mapOf("success" to true, "flashlight" to if (on) "on" else "off"))
        } else {
            ToolExecutionResult(
                false,
                mapOf("success" to false, "error" to "flashlight_failed:${result.exceptionOrNull()?.message.orEmpty()}")
            )
        }
    }

    private suspend fun executeStartTimer(toolCall: ToolCall): ToolExecutionResult {
        // LiteRT が snake_case で渡す場合（duration_seconds）と、camelCase の両方に対応
        val durationSeconds = (toolCall.arguments["durationSeconds"]
            ?: toolCall.arguments["duration_seconds"]
            ?: toolCall.arguments["duration"]
            ?: toolCall.arguments["seconds"]
            ?: toolCall.arguments["minutes"])?.let {
            when (it) {
                is Number -> it.toLong()
                is String -> it.toLongOrNull()
                else      -> null
            }
        }
        val normalizedDurationSeconds = when {
            durationSeconds == null -> null
            toolCall.arguments.containsKey("minutes") -> durationSeconds * 60L
            else -> durationSeconds
        }
        if (normalizedDurationSeconds == null || normalizedDurationSeconds <= 0) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_duration"))
        }
        val label = toolCall.arguments["label"]?.toString()?.takeIf { it.isNotBlank() } ?: ""
        val result = ToolSystemController.TimerManager.startTimer(context, normalizedDurationSeconds, label)
        return ToolExecutionResult(
            success = result["success"] as? Boolean ?: false,
            payload = result
        )
    }

    private suspend fun executeStopTimer(toolCall: ToolCall): ToolExecutionResult {
        // LiteRT が snake_case で渡す場合（timer_id）と、camelCase の両方に対応
        val timerId = (
            toolCall.arguments["timerId"]
                ?: toolCall.arguments["timer_id"]
                ?: toolCall.arguments["id"]
        )?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_timer_id"))
        val result = ToolSystemController.TimerManager.stopTimer(timerId)
        return ToolExecutionResult(
            success = result["success"] as? Boolean ?: false,
            payload = result
        )
    }

    private suspend fun executeListTimers(): ToolExecutionResult {
        val result = ToolSystemController.TimerManager.listTimers()
        return ToolExecutionResult(
            success = result["success"] as? Boolean ?: false,
            payload = result
        )
    }

    private suspend fun executeGenerateImage(toolCall: ToolCall): ToolExecutionResult {
        Log.d(TOOL_TAG, "executeGenerateImage: dispatching to handler")
        val handler = GenerateImageToolBridge.handler
            ?: return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "generate_image_handler_missing")
            )
        try {
            val res = handler.handle(toolCall)
            Log.d(TOOL_TAG, "executeGenerateImage: handler returned: success=${res.success}")
            return res
        } catch (e: CancellationException) {
            Log.w(TOOL_TAG, "executeGenerateImage: handler cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TOOL_TAG, "executeGenerateImage: handler threw", e)
            return ToolExecutionResult(success = false, payload = mapOf("success" to false, "error" to (e.message ?: "handler_error")))
        }
    }

    private suspend fun executeListSdModels(): ToolExecutionResult {
        val models = mutableListOf<Map<String, Any>>()

        // sd_models directory
        val sdModelsDir = java.io.File(context.filesDir, "sd_models")
        sdModelsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val targetDir = resolveNestedSdModelDir(dir)
            if (isProbableSdModelDir(targetDir)) {
                models.add(mapOf(
                    "name" to targetDir.name,
                    "path" to targetDir.absolutePath,
                    "isSdxl" to com.nezumi_ai.sd.SdModelLayout.isSdxlModelDir(targetDir)
                ))
            }
        }

        // App external files directory
        val appDir = context.getExternalFilesDir(null)
        appDir?.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "isSdxl" to com.nezumi_ai.sd.SdModelLayout.isSdxlModelDir(file)
                ))
            }
        }

        // Imported models directory
        val importedDir = java.io.File(context.filesDir, "models/imported")
        importedDir.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "isSdxl" to com.nezumi_ai.sd.SdModelLayout.isSdxlModelDir(file)
                ))
            }
        }

        val savedPath = com.nezumi_ai.utils.PreferencesHelper.getSdModelPath(context).trim()
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "success" to true,
                "count" to models.size,
                "models" to models,
                "defaultModel" to (savedPath.ifEmpty { models.firstOrNull()?.get("name") ?: "" })
            )
        )
    }

    private fun resolveNestedSdModelDir(dir: java.io.File): java.io.File {
        var current = dir
        repeat(3) {
            val children = current.listFiles()?.toList() ?: return current
            if (children.size == 1 && children[0].isDirectory) current = children[0]
            else return current
        }
        return current
    }

    private fun isProbableSdModelDir(file: java.io.File): Boolean {
        return com.nezumi_ai.sd.SdModelLayout.isUsableModelDir(file) ||
               com.nezumi_ai.sd.SdModelLayout.isLegacyQnnDir(file)
    }

    private suspend fun executeSearchMemory(toolCall: ToolCall): ToolExecutionResult {
        val query = toolCall.arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_query"))
        val topK = toolCall.arguments.readInt("topK") ?: 5

        if (memoryRepository == null) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "memory_not_initialized")
            )
        }

        // MemoryTextEmbedderの初期化を確認（初回のみ、IOで実行）
        if (!MemoryTextEmbedder.initializeAsync(context)) {
            Log.w(TOOL_TAG, "MemoryTextEmbedder initialization failed, using fallback")
        }

        val embedding = MemoryTextEmbedder.embed(query)
        if (embedding.isEmpty()) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "embedding_failed")
            )
        }

        val results = memoryRepository.search(embedding, topK = topK, markAccessed = false)
        val memories = results.map { scored ->
            mapOf(
                "content" to scored.memory.content,
                "similarity" to scored.similarity,
                "score" to scored.score,
                "importance" to scored.memory.importance,
                "source" to scored.memory.source
            )
        }

        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "success" to true,
                "count" to memories.size,
                "memories" to memories
            )
        )
    }

    /**
     * LLM が「これを覚えておいて」と呼ぶ save_memory ツールの実行体。
     * MemorySaveMode.TOOL_ONLY ではここが唯一のメモリ保存経路になる。
     */
    private suspend fun executeSaveMemory(toolCall: ToolCall): ToolExecutionResult {
        val content = toolCall.arguments["content"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_content"))

        // 安全弁: 作り込み防止と LLM の暴走防止
        val trimmedContent = when {
            content.length > MAX_MEMORY_CONTENT_LEN ->
                content.substring(0, MAX_MEMORY_CONTENT_LEN)
            else -> content
        }

        val importance = (toolCall.arguments["importance"] as? Number)?.toFloat()
            ?: (toolCall.arguments["importance"] as? String)?.toFloatOrNull()
            ?: 0.7f

        if (memoryRepository == null) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "memory_not_initialized")
            )
        }

        // Embedder を IO で確実に初期化
        if (!MemoryTextEmbedder.initializeAsync(context)) {
            Log.w(TOOL_TAG, "MemoryTextEmbedder initialization failed for save_memory")
        }

        val embedding = MemoryTextEmbedder.embed(trimmedContent)
        if (embedding.isEmpty()) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "embedding_failed")
            )
        }

        // 重複ガード: 既存メモリと高類似なものは保存しない（LLM の同一ターン連呼を防ぐ）
        val existing = memoryRepository.search(
            embedding,
            topK = 1,
            markAccessed = false
        )
        val topSim = existing.firstOrNull()?.similarity ?: 0f
        if (topSim >= DUPLICATE_SAVE_THRESHOLD) {
            Log.d(TOOL_TAG, "executeSaveMemory: skipped near-duplicate (similarity=$topSim) content=\"$trimmedContent\"")
            return ToolExecutionResult(
                success = true,
                payload = mapOf(
                    "success" to true,
                    "saved" to false,
                    "reason" to "duplicate",
                    "similarity" to topSim,
                    "content" to trimmedContent
                )
            )
        }

        val id = memoryRepository.saveMemory(
            content = trimmedContent,
            embedding = embedding,
            importance = importance.coerceIn(0f, 1f),
            source = com.nezumi_ai.data.database.entity.MemoryEntity.SOURCE_ASSISTANT
        )
        Log.i(TOOL_TAG, "executeSaveMemory: saved id=$id importance=$importance content=\"$trimmedContent\"")

        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "success" to true,
                "saved" to true,
                "id" to id,
                "content" to trimmedContent,
                "importance" to importance
            )
        )
    }

    private suspend fun executeAddCalendarEvent(toolCall: ToolCall): ToolExecutionResult {
        val title = toolCall.arguments["title"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_title"))
        
        val startYear = toolCall.arguments.readInt("startYear")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_start_year"))
        val startMonth = toolCall.arguments.readInt("startMonth")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_start_month"))
        val startDay = toolCall.arguments.readInt("startDay")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_start_day"))
        val startHour = toolCall.arguments.readInt("startHour") ?: 0
        val startMinute = toolCall.arguments.readInt("startMinute") ?: 0
        val durationMinutes = toolCall.arguments.readInt("durationMinutes") ?: 60
        
        val description = toolCall.arguments["description"]?.toString()
        val location = toolCall.arguments["location"]?.toString()

        val calendar = java.util.Calendar.getInstance().apply {
            set(startYear, startMonth - 1, startDay, startHour, startMinute, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startTimeMillis = calendar.timeInMillis
        val endTimeMillis = startTimeMillis + (durationMinutes * 60 * 1000L)

        val result = com.nezumi_ai.data.tools.CalendarTool.addEvent(
            context, title, startTimeMillis, endTimeMillis, description, location
        )

        return if (result.isSuccess) {
            ToolExecutionResult(
                success = true,
                payload = mapOf(
                    "success" to true,
                    "eventId" to result.getOrNull(),
                    "title" to title
                )
            )
        } else {
            ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "add_event_failed:${result.exceptionOrNull()?.message.orEmpty()}"
                )
            )
        }
    }

    private suspend fun executeListCalendarEvents(toolCall: ToolCall): ToolExecutionResult {
        val startYear = toolCall.arguments.readInt("startYear")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_start_year"))
        val startMonth = toolCall.arguments.readInt("startMonth")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_start_month"))
        val startDay = toolCall.arguments.readInt("startDay")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_start_day"))
        val endYear = toolCall.arguments.readInt("endYear")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_end_year"))
        val endMonth = toolCall.arguments.readInt("endMonth")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_end_month"))
        val endDay = toolCall.arguments.readInt("endDay")
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_end_day"))

        val startCalendar = java.util.Calendar.getInstance().apply {
            set(startYear, startMonth - 1, startDay, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val endCalendar = java.util.Calendar.getInstance().apply {
            set(endYear, endMonth - 1, endDay, 23, 59, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }

        val result = com.nezumi_ai.data.tools.CalendarTool.listEvents(
            context, startCalendar.timeInMillis, endCalendar.timeInMillis
        )

        return if (result.isSuccess) {
            val events = result.getOrNull() ?: emptyList()
            ToolExecutionResult(
                success = true,
                payload = mapOf(
                    "success" to true,
                    "count" to events.size,
                    "events" to events
                )
            )
        } else {
            ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "list_events_failed:${result.exceptionOrNull()?.message.orEmpty()}"
                )
            )
        }
    }

    private suspend fun executeWebSearch(toolCall: ToolCall): ToolExecutionResult {
        val query = toolCall.arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_query"))

        val count = toolCall.arguments.readInt("count") ?: 10
        val offset = toolCall.arguments.readInt("offset") ?: 0
        val country = (toolCall.arguments["country"] ?: toolCall.arguments["country_code"])?.toString()
            ?.replace("/", "")
            ?.filter { it.isLetter() }
            ?.uppercase()
            ?.takeIf { it.length == 2 } ?: "JP"
        val searchLang = (toolCall.arguments["searchLang"] ?: toolCall.arguments["search_lang"])?.toString()
            ?.let { lang ->
                when (lang.lowercase()) {
                    "ja", "japanese" -> "jp"
                    else -> lang.lowercase()
                }
            }?.takeIf { it.length >= 2 } ?: "jp"
        val safeSearch = (toolCall.arguments["safeSearch"] ?: toolCall.arguments["safesearch"])?.toString()?.lowercase() ?: "moderate"

        // Validate parameters
        if (count !in 1..20) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_count"))
        }
        if (offset !in 0..9) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_offset"))
        }

        return try {
            val apiKey = PreferencesHelper.getBraveSearchApiKey(context.applicationContext).takeIf { it.isNotBlank() }
                ?: context.applicationContext.getSharedPreferences("web_search_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("brave_api_key", null)
            if (apiKey.isNullOrBlank()) {
                return ToolExecutionResult(
                    success = false,
                    payload = mapOf("success" to false, "error" to "api_key_not_configured")
                )
            }

            val results = performBraveSearch(
                query = query,
                count = count,
                offset = offset,
                country = country,
                searchLang = searchLang,
                safeSearch = safeSearch,
                apiKey = apiKey
            )

            ToolExecutionResult(
                success = true,
                payload = mapOf(
                    "success" to true,
                    "query" to query,
                    "count" to results.size,
                    "results" to results
                )
            )
        } catch (e: Exception) {
            Log.e(TOOL_TAG, "Web search failed", e)
            ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "search_failed:${e.message}")
            )
        }
    }

    private suspend fun executeWebFetch(toolCall: ToolCall): ToolExecutionResult {
        val url = toolCall.arguments["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_url"))
        val maxChars = toolCall.arguments.readInt("maxChars")
            ?: toolCall.arguments.readInt("max_chars")
            ?: 4000

        val jsRenderEnabled = ToolPreferences(context.applicationContext).isWebFetchJsRenderEnabled()

        return try {
            val result = if (jsRenderEnabled) {
                // WebViewはメインスレッドでのみ操作可能なため、内部でDispatchers.Mainに切り替わる
                performWebFetchWithJs(context = context.applicationContext, url = url, maxChars = maxChars)
            } else {
                withContext(Dispatchers.IO) {
                    performWebFetch(url = url, maxChars = maxChars)
                }
            }
            ToolExecutionResult(
                success = result["success"] as? Boolean ?: false,
                payload = result
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TOOL_TAG, "Web fetch failed", e)
            ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "fetch_failed:${e.message}")
            )
        }
    }

    // ─────────────────────────────────────────────
    // ドキュメント作成: Markdown から Word/PDF/Excel ファイルを生成する
    // 実処理は DocumentToolBridge 経由で ChatViewModel に委譲する
    // (GenerateImageToolBridge と同一パターン)。
    // ─────────────────────────────────────────────

    private suspend fun executeConvertMdToDocument(toolCall: ToolCall): ToolExecutionResult {
        val handler = DocumentToolBridge.convertMdToDocumentHandler
            ?: return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "convert_md_to_document_handler_missing")
            )
        return try {
            handler.handle(toolCall)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TOOL_TAG, "executeConvertMdToDocument: handler threw", e)
            ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to (e.message ?: "handler_error"))
            )
        }
    }

    // ─────────────────────────────────────────────
    // MCP: 汎用ディスパッチ (mcp_call) と修飾名の直接呼び出し
    // ─────────────────────────────────────────────

    private suspend fun executeMcpListTools(): ToolExecutionResult {
        val registry = McpToolRegistry.get(context)
        runCatching { registry.ensureFresh() }
            .onFailure { Log.w(TOOL_TAG, "mcp_list_tools: refresh failed", it) }
        val tools = registry.currentTools()
        if (tools.isEmpty()) {
            return ToolExecutionResult(
                success = true,
                payload = mapOf(
                    "success" to true,
                    "count" to 0,
                    "tools" to emptyList<Map<String, Any?>>(),
                    "note" to "No MCP tool is currently available. Check that an MCP server is attached to this preset and reachable."
                )
            )
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "success" to true,
                "count" to tools.size,
                "tools" to tools.map { desc ->
                    mapOf(
                        "name" to desc.qualifiedName,
                        "server" to desc.serverName,
                        "description" to desc.description,
                        "input_schema" to desc.inputSchemaJson
                    )
                }
            )
        )
    }

    private suspend fun executeMcpCall(toolCall: ToolCall): ToolExecutionResult {
        val name = (toolCall.arguments["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "missing_mcp_tool_name")
            )
        }
        val rawArgs = toolCall.arguments["argumentsJson"] as? String
        val args = parseArgumentsJson(rawArgs)
        return dispatchMcpTool(name, args)
    }

    private suspend fun executeMcpToolByQualifiedName(toolCall: ToolCall): ToolExecutionResult {
        // 引数マップをそのまま MCP へ委譲。argumentsJson が渡ってきた場合はそれを優先。
        // モデルが argumentsJson と方式の両方を混在させることもあるので、両方をマージしておく。
        val explicit = toolCall.arguments["argumentsJson"] as? String
        val fromJson = if (!explicit.isNullOrBlank()) parseArgumentsJson(explicit) else emptyMap()
        val rest = toolCall.arguments.filterKeys { it != "argumentsJson" && it != "name" }
        val merged = LinkedHashMap<String, Any?>().apply {
            putAll(rest)
            putAll(fromJson) // argumentsJson 側を優先
        }
        return dispatchMcpTool(toolCall.name, merged)
    }

    private suspend fun dispatchMcpTool(qualifiedName: String, args: Map<String, Any?>): ToolExecutionResult {
        val registry = McpToolRegistry.get(context)
        val res = registry.callQualified(qualifiedName, args)
        return if (res.success) {
            ToolExecutionResult(
                success = true,
                payload = mapOf(
                    "success" to true,
                    "tool" to qualifiedName,
                    "result" to (res.resultText ?: "")
                )
            )
        } else {
            ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "tool" to qualifiedName,
                    "error" to (res.errorMessage ?: "mcp_call_failed")
                )
            )
        }
    }

    private fun parseArgumentsJson(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap<String, Any?> {
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    put(k, obj.opt(k))
                }
            }
        }.getOrElse {
            Log.w(TOOL_TAG, "Failed to parse MCP argumentsJson: $raw", it)
            emptyMap()
        }
    }

    private companion object {
        const val MAX_MEMORY_CONTENT_LEN = 1024
        const val DUPLICATE_SAVE_THRESHOLD = 0.95f
    }
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

private fun Map<String, Any?>.readLong(key: String): Long? {
    return when (val v = this[key] ?: return null) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else      -> null
    }
}

private fun Map<String, Any?>.readBoolean(key: String): Boolean {
    return when (val v = this[key] ?: return false) {
        is Boolean -> v
        is String  -> v.equals("true", ignoreCase = true)
        is Number  -> v.toInt() != 0
        else       -> false
    }
}

private fun Map<String, Any?>.readBooleanAny(vararg keys: String): Boolean {
    for (key in keys) {
        if (!containsKey(key)) continue
        return when (val v = this[key]) {
            is Boolean -> v
            is String -> {
                val normalized = v.trim().lowercase(Locale.US)
                normalized == "true" || normalized == "on" || normalized == "1" || normalized == "enable"
            }
            is Number -> v.toInt() != 0
            else -> false
        }
    }
    return false
}
