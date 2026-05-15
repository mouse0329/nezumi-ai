package com.nezumi_ai.desktop.mcp

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.nezumi_ai.desktop.inference.LlamaCppEngine

/**
 * MCP (Model Context Protocol) Server
 * Claude Desktop等のMCPクライアントから接続可能
 */
class McpServer(private val port: Int = 3000) {
    private var server: NettyApplicationEngine? = null
    private val engine = LlamaCppEngine()
    
    fun start() {
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                })
            }
            
            routing {
                // MCP Tools エンドポイント
                get("/mcp/tools") {
                    call.respond(ToolsResponse(
                        tools = listOf(
                            Tool("generate_text", "LLM推論を実行", listOf("prompt")),
                            Tool("get_context", "チャット履歴を取得", emptyList())
                        )
                    ))
                }
                
                // 推論実行
                post("/mcp/tools/generate_text") {
                    val request = call.receive<GenerateRequest>()
                    val result = StringBuilder()
                    
                    engine.generate(request.prompt).collect { token ->
                        result.append(token)
                    }
                    
                    call.respond(GenerateResponse(result.toString()))
                }
                
                // コンテキスト取得
                get("/mcp/tools/get_context") {
                    // TODO: データベースからチャット履歴を取得
                    call.respond(ContextResponse(
                        messages = listOf(
                            ContextMessage("user", "こんにちは"),
                            ContextMessage("assistant", "こんにちは！")
                        )
                    ))
                }
                
                // ヘルスチェック
                get("/health") {
                    call.respondText("OK", status = HttpStatusCode.OK)
                }
            }
        }.start(wait = false)
        
        println("MCP Server started on http://localhost:$port")
    }
    
    suspend fun stop() {
        server?.stop(1000, 2000)
        engine.release()
        println("MCP Server stopped")
    }
}

@Serializable
data class ToolsResponse(val tools: List<Tool>)

@Serializable
data class Tool(val name: String, val description: String, val parameters: List<String>)

@Serializable
data class GenerateRequest(val prompt: String)

@Serializable
data class GenerateResponse(val result: String)

@Serializable
data class ContextResponse(val messages: List<ContextMessage>)

@Serializable
data class ContextMessage(val role: String, val content: String)
