package com.nezumi_ai.shared.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

actual class AlarmTool actual constructor() : Tool {
    override val name = "alarm"
    override val description = "Set or manage alarms"
    override val parameters: Map<String, JsonElement> = mapOf(
        "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Action: set, cancel, list")
        )),
        "time" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Time in HH:MM format (for set action)")
        )),
        "message" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Alarm message (for set action)")
        ))
    )

    // TODO: Contextが必要なので、実際のアプリで注入する必要がある
    // 仮実装
    override suspend fun execute(parameters: Map<String, Any>): ToolResult {
        val action = parameters["action"] as? String ?: return ToolResult(false, "", "Missing action parameter")

        return when (action) {
            "set" -> {
                val time = parameters["time"] as? String ?: return ToolResult(false, "", "Missing time parameter")
                val message = parameters["message"] as? String ?: "Alarm"
                ToolResult(true, "Alarm set for $time: $message")
            }
            "cancel" -> ToolResult(true, "Alarm cancelled")
            "list" -> ToolResult(true, "No active alarms")
            else -> ToolResult(false, "", "Unknown action: $action")
        }
    }
}