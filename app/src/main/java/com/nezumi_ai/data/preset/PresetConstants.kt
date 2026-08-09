package com.nezumi_ai.data.preset

object PresetConstants {
    const val MODEL_GEMMA4_LITERT = "gemma4_litert"
    const val MODEL_QWEN3_GGUF = "qwen3_gguf"
    const val MODEL_GEMINI_API = "gemini_api"
    const val MODEL_CLAUDE_API = "claude_api"

    const val TOOL_ALARM = "alarm"
    const val TOOL_CALENDAR = "calendar"
    const val TOOL_GMAIL = "gmail"
    const val TOOL_SWITCHBOT = "switchbot"
    const val TOOL_FLASHLIGHT = "flashlight"
    const val TOOL_APP_LAUNCH = "app_launch"
    const val TOOL_TIME = "time"
    const val TOOL_BATTERY = "battery"
    const val TOOL_TIMER = "timer"
    const val TOOL_IMAGE_GENERATION = "image_generation"
    const val TOOL_MEMORY = "memory"
    // メモリ保存専用ツールのプリセット ID。これを有効にすると LLM に save_memory ツールが見える。
    // MEMORY_ONLY モードではこのツールの呼び出しが唇一の保存経路になる。
    const val TOOL_MEMORY_SAVE = "memory_save"
    const val TOOL_WEB_SEARCH = "web_search"
    // Markdown → Word/PDF/Excel 生成
    const val TOOL_CONVERT_MD_TO_DOCUMENT = "convert_md_to_document"
    // PDF/Word/Excel → Markdown 変換 (Chaquopy/MarkItDown)
    const val TOOL_CONVERT_DOCUMENT_TO_MD = "convert_document_to_md"

    val allToolIds: List<String> = listOf(
        TOOL_ALARM,
        // CALENDAR_DISABLED: TOOL_CALENDAR,
        TOOL_GMAIL,
        TOOL_SWITCHBOT,
        TOOL_FLASHLIGHT,
        TOOL_APP_LAUNCH,
        TOOL_TIME,
        TOOL_BATTERY,
        TOOL_TIMER,
        TOOL_IMAGE_GENERATION,
        TOOL_MEMORY,
        TOOL_MEMORY_SAVE,
        TOOL_WEB_SEARCH,
        TOOL_CONVERT_MD_TO_DOCUMENT,
        TOOL_CONVERT_DOCUMENT_TO_MD
    )

    /**
     * 新規プリセット作成時に初期で有効にするツール ID の集合。
     *
     * v2.1+ 仕様変更: ツールコールを ON にしても全ツールを自動でチェックしない
     * 方針に変更されたため、ここは空リスト。ユーザーは使いたいツールを選択する。
     */
    val defaultInitiallyEnabledToolIds: List<String> = emptyList()
}
