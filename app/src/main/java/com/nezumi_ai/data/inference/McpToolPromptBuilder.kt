package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.mcp.McpToolDescriptor
import com.nezumi_ai.data.mcp.McpToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MCP サーバー由来のツール定義をシステムプロンプトへ注入する共通ビルダー。
 *
 * これまで MCP ツールの列挙は [GgufToolPromptBuilder] の中だけに実装されており、
 * LiteRT-LM 経路（ChatViewModel が `PromptBuilder.buildForLiteRt` を使う側）では
 * 一切注入されていなかった。結果として LiteRT では
 *
 *   - `mcp_call` は「system prompt の <tools> に載っている修飾名を使え」という
 *     説明のスキーマだけが登録される
 *   - しかしその <tools> ブロックが存在しない
 *
 * という状態になり、LLM から MCP ツールが 1 つも見えなかった。
 * ここを共通化し、GGUF / LiteRT どちらの経路でも同じ一覧が入るようにする。
 *
 * 「キャッシュが空なのにアクティブサーバーがある場合はそのターンで同期取得する」
 * 挙動もここに集約し、両エンジンで同じ即時反映が効くようにしている。
 */
object McpToolPromptBuilder {
    private const val TAG = "McpToolPromptBuilder"

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    private const val SYNC_REFRESH_TIMEOUT_MS = 8_000L

    /** MCP ツール 1 件を OpenAI 互換の function 定義 JSON にする。 */
    fun toFunctionJson(desc: McpToolDescriptor): String {
        val safeDesc = (desc.description.ifBlank { "MCP tool from ${desc.serverName}" })
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        val schema = desc.inputSchemaJson.ifBlank { EMPTY_SCHEMA }
        return """{"type":"function","function":{"name":"${desc.qualifiedName}","description":"[MCP:${desc.serverName}] $safeDesc","parameters":$schema}}"""
    }

    /**
     * プロンプト生成時点で参照できる MCP ツール一覧を返す。
     *
     * レジストリが未初期化（プロセス再生成直後、プリセット適用の非同期 refresh が
     * まだ終わっていない等）なのにアクティブサーバーが存在する場合は、
     * このターンでタイムアウト付きの同期取得を行って取り込む。
     */
    fun resolveTools(context: Context): List<McpToolDescriptor> {
        val registry = McpToolRegistry.get(context)
        val cached = registry.currentTools()
        if (cached.isNotEmpty()) return cached

        val activeIds = registry.activeServerIds()
        if (activeIds.isEmpty()) return emptyList()

        Log.d(TAG, "MCP cache empty but ${activeIds.size} server(s) active - refreshing synchronously")
        runCatching {
            runBlocking {
                withTimeoutOrNull(SYNC_REFRESH_TIMEOUT_MS) {
                    registry.refresh(activeIds, force = true)
                }
            }
        }.onFailure { Log.w(TAG, "Synchronous MCP refresh failed", it) }
        return registry.currentTools()
    }

    /** 参照可能な MCP ツールの function 定義 JSON（改行区切り）。無ければ空文字。 */
    fun currentToolsJson(context: Context): String {
        val tools = resolveTools(context)
        if (tools.isEmpty()) return ""
        return tools.joinToString("\n") { toFunctionJson(it) }
    }

    /**
     * LiteRT-LM 用。ネイティブのツールスキーマは `mcp_call` という汎用ディスパッチャ
     * 1 つしか登録できないため、実際に呼べる MCP ツールの一覧と呼び出し方を
     * システムプロンプト側で明示する。
     */
    fun appendForLiteRt(context: Context, systemPrompt: String): String {
        val tools = resolveTools(context)
        if (tools.isEmpty()) {
            if (McpToolRegistry.get(context).hasActiveServers()) {
                Log.w(TAG, "MCP servers are active but tools/list returned nothing; skipping prompt injection")
            }
            return systemPrompt
        }

        val toolsJson = tools.joinToString("\n") { toFunctionJson(it) }
        val block = buildString {
            appendLine()
            appendLine()
            appendLine("You can also use tools provided by connected MCP servers.")
            appendLine("They are NOT callable directly: invoke them through the `mcp_call` tool.")
            appendLine("Set `name` to the fully-qualified name below (it starts with `mcp__`),")
            appendLine("and `argumentsJson` to a JSON object string matching that tool's parameters.")
            appendLine("""Example: mcp_call(name="mcp__abcd1234__list_files", argumentsJson="{\"path\":\"/tmp\"}")""")
            appendLine("<mcp_tools>")
            append(toolsJson)
            appendLine()
            append("</mcp_tools>")
        }
        Log.d(TAG, "Injected ${tools.size} MCP tool(s) into LiteRT system prompt")
        return if (systemPrompt.isBlank()) block.trim() else systemPrompt + block
    }
}
