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
    const val TOOL_WEB_SEARCH = "web_search"

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
        TOOL_WEB_SEARCH
    )
}
