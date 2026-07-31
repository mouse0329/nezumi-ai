package com.nezumi_ai.data.inference

import android.content.Context
import com.nezumi_ai.data.database.entity.MessageEntity

object PromptBuilder {
    /**
     * Bug fix(#42): モデルパスからユーザー選択のテンプレモードを確認するための軽量ヘルパ。
     * GPT-2 アーキテクチャなど「自動判定で PLAIN_COMPLETION 固定」になるモデルでも、
     * ユーザーが明示的に "chatml" / "custom" 等を選んでいる場合はそちらを尊重する。
     */
    private fun hasExplicitUserTemplate(appContext: Context?, modelPath: String): Boolean {
        if (appContext == null || modelPath.isBlank()) return false
        return runCatching {
            val sel = PromptTemplateStore.getSelection(appContext, modelPath)
            sel.mode != PromptTemplateStore.MODE_AUTO
        }.getOrDefault(false)
    }
    private const val COMPRESSED_CONTEXT_HEADER = "以下は過去会話の圧縮コンテキストです:"
    private const val GEMMA_THINK_PREFIX = "<|think|>\n"
    private const val ASSISTANT_THINK_PREFILL = "<think>\n"
    private const val QWEN_THINK_COMMAND = "/think"
    private const val QWEN_NO_THINK_COMMAND = "/no_think"

    /**
 * Qwen3 公式 non-thinking jinja と同じ「空 <think></think>」プレフィル文字列。
     * Thinking OFF のときに assistant ターン直後 (またはレンダー結果末尾) に注入することで、
     * モデルが chat_template の関係で <think> を吐こうとするのを「もう思考は終わった」と
     * 認識させて思考をスキップさせる。
     */
    private const val QWEN_EMPTY_THINK_PREFILL = "<think>\n\n</think>\n\n"

    enum class ThinkingPromptStyle {
        /**
         * Gemma 3 (litert / 旧 GGUF) 系: プロンプト最先頭に `<|think|>\n` を1度だけ置く。
         * 思考本文は `<|channel>thought\n ... <channel|>` で出力される。
         */
        GEMMA_PREFIX,
        /**
         * Qwen 系: 直近 user ターン末尾に `/think` または `/no_think` を付与する。
         */
        QWEN_COMMAND,
        /**
         * llama.cpp で標準的に使われる `<think>...</think>` プレフィル方式。
         * Gemma 4 GGUF も llama.cpp 上ではこの方式で thinking を吐く（公式 chat-template-kwargs
         * の enable_thinking=true 相当）。
         */
        ASSISTANT_TAG,
        /**
         * Gemma 4 専用: Google AI 公式仕様の thinking 構造（`<|think|>` をシステムターン内に置き、
         * assistant 側で `<think>...</think>` プレフィルもする）を実装する。
         * llama.cpp の F16 で `<unused49>` flood する既知バグ対策として stop に対応。
         */
        GEMMA4_CHANNEL,
        /**
         * GPT-2 のような plain completion モデル。chat/thinking 制御タグは注入しない。
         */
        PLAIN_COMPLETION
    }

    /** GGUF モデルのプロンプトフォーマット */
    enum class GgufPromptFormat {
        /** Gemma 系: <start_of_turn> / <end_of_turn> */
        GEMMA_CHAT,
        /** Llama 3 / Mistral / Bonsai 等: <|im_start|> / <|im_end|> ChatML */
        CHATML,
        /** GPT-2 など: プレーンな completion プロンプト */
        PLAIN_COMPLETION,
    }

    fun detectGgufFormat(modelPath: String, appContext: Context? = null): GgufPromptFormat {
        val name = modelPath.lowercase()
        // Bug fix(#42): ユーザーが明示的にテンプレを選んでいる場合は GPT-2 でも PLAIN_COMPLETION を強制しない。
        // ChatML / Gemma などのユーザー選択を尊重してテンプレ経路 (buildWithCustomTemplate) に流す。
        val userOverride = hasExplicitUserTemplate(appContext, modelPath)
        return when {
            !userOverride && isGpt2Model(modelPath) -> GgufPromptFormat.PLAIN_COMPLETION
            "gemma" in name -> GgufPromptFormat.GEMMA_CHAT
            else -> GgufPromptFormat.CHATML
        }
    }

    fun resolveThinkingPromptStyle(modelPath: String, appContext: Context? = null): ThinkingPromptStyle {
        val name = modelPath.lowercase()
        val userOverride = hasExplicitUserTemplate(appContext, modelPath)
        return when {
            !userOverride && isGpt2Model(modelPath) -> ThinkingPromptStyle.PLAIN_COMPLETION
            Regex("(^|[^a-z0-9])(qwen|qwq)([^a-z0-9]|$)").containsMatchIn(name) -> ThinkingPromptStyle.QWEN_COMMAND
            // Gemma4 (GGUF / litert) は thinking 構造が Gemma3 と異なるため専用スタイルへ振り分ける。
            // 一致条件: "gemma4", "gemma-4", "gemma_4", e2b/e4b/26b-a4b など Gemma4 サイズ識別子。
            isGemma4ModelName(name) -> ThinkingPromptStyle.GEMMA4_CHANNEL
            "gemma" in name -> ThinkingPromptStyle.GEMMA_PREFIX
            else -> ThinkingPromptStyle.ASSISTANT_TAG
        }
    }

    private fun isGemma4ModelName(loweredName: String): Boolean {
        if ("gemma" !in loweredName) return false
        // "gemma4", "gemma-4", "gemma_4", "gemma 4" などの直接表記
        if (Regex("gemma[\\-_ ]?4(?![0-9])").containsMatchIn(loweredName)) return true
        // E2B / E4B / 12B-A4B / 26B-A4B / 31B-A4B など Gemma4 サイズ識別子
        if (Regex("(^|[^a-z0-9])(e2b|e4b)([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        if (Regex("(^|[^a-z0-9])(12b|26b|31b)[\\-_]?a4b([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        return false
    }

    private fun isGpt2ModelName(loweredName: String): Boolean {
        return Regex("(^|[^a-z0-9])gpt[\\-_ ]?2([^a-z0-9]|$)").containsMatchIn(loweredName)
    }

    private fun isGpt2Architecture(modelPath: String): Boolean {
        val lowered = modelPath.lowercase()
        if (!lowered.endsWith(".gguf")) return false
        val file = java.io.File(modelPath)
        if (!file.isFile) return false
        return runCatching {
            com.nezumi_ai.utils.GgufMetadataReader.readSummary(file).architecture.lowercase()
        }.getOrNull() == "gpt2"
    }

    private fun isGpt2Model(modelPath: String): Boolean {
        val lowered = modelPath.lowercase()
        return isGpt2ModelName(lowered) || isGpt2Architecture(modelPath)
    }

    /**
     * `<think>\n` を assistant 開始タグ直後にプレフィルすべきかどうか。
     *
     * 旧実装は ASSISTANT_TAG のみだったが、Gemma4 GGUF (llama.cpp) も `<think>...</think>` 形式で
     * thinking を吐くため、`GEMMA4_CHANNEL` でも assistant 側 prefill を必要とする。
     */
    fun usesAssistantThinkingPrefill(modelPath: String): Boolean {
        val style = resolveThinkingPromptStyle(modelPath)
        return style == ThinkingPromptStyle.ASSISTANT_TAG ||
            style == ThinkingPromptStyle.GEMMA4_CHANNEL
    }

    /**
 * モデル名から Qwen 3 系かを判定し、non-thinking jinja 相当の空 <think></think>
     * プレフィルを使うべきかを返す。ユーザーが Thinking OFF にしたのに
     * モデルが chat_template の関係で <think> を吐くケースの最強の抑止手段。
     */
    fun usesQwenStyleThinking(modelPath: String): Boolean {
        return resolveThinkingPromptStyle(modelPath) == ThinkingPromptStyle.QWEN_COMMAND
    }

    /** モデル名から Gemma4 系かどうかを判定する公開ヘルパー（パーサ / ストップシーケンス側で参照）。 */
    fun isGemma4Model(modelPath: String): Boolean = isGemma4ModelName(modelPath.lowercase())

    fun buildForLiteRt(
        messages: List<MessageEntity>,
        systemPrompt: String,
        injectGemmaThinkTrigger: Boolean,
        compressedSummary: String? = null,
        sanitizeMessageContent: (MessageEntity) -> String,
        appContext: Context? = null,
        modelPath: String = ""
    ): String {
        // ユーザーがカスタム / ビルトインテンプレートを設定している場合はそちらを優先
        val customTemplate = appContext?.let { ctx ->
            if (modelPath.isNotBlank()) PromptTemplateStore.resolveTemplate(ctx, modelPath) else null
        }
        if (customTemplate != null) {
            return buildWithCustomTemplate(
                template = customTemplate,
                messages = messages,
                systemPrompt = systemPrompt,
                compressedSummary = compressedSummary,
                enableThinking = injectGemmaThinkTrigger,
                sanitizeMessageContent = sanitizeMessageContent,
                modelPath = modelPath
            )
        }

        val contextBuilder = StringBuilder()
        if (injectGemmaThinkTrigger) {
            contextBuilder.append(GEMMA_THINK_PREFIX)
        }
        if (systemPrompt.isNotEmpty()) {
            contextBuilder.append(systemPrompt)
            contextBuilder.append("\n\n")
        }
        if (!compressedSummary.isNullOrBlank()) {
            contextBuilder.append(COMPRESSED_CONTEXT_HEADER)
            contextBuilder.append('\n')
            contextBuilder.append(compressedSummary)
            contextBuilder.append("\n\n")
        }

        for (msg in messages) {
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "Assistant" else "User"
            contextBuilder.append(role)
                .append(": ")
                .append(content)
                .append('\n')
        }
        contextBuilder.append("Assistant:")
        return contextBuilder.toString()
    }

    fun buildForGguf(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String? = null,
        format: GgufPromptFormat = GgufPromptFormat.CHATML,
        enableThinking: Boolean = false,
        modelPath: String = "",
        sanitizeMessageContent: (MessageEntity) -> String,
        appContext: Context? = null
    ): String {
        // ユーザー設定のテンプレ（カスタム / ビルトイン）があればそれを優先
        val customTemplate = appContext?.let { ctx ->
            if (modelPath.isNotBlank()) PromptTemplateStore.resolveTemplate(ctx, modelPath) else null
        }
        if (customTemplate != null) {
            return buildWithCustomTemplate(
                template = customTemplate,
                messages = messages,
                systemPrompt = systemPrompt,
                compressedSummary = compressedSummary,
                enableThinking = enableThinking,
                sanitizeMessageContent = sanitizeMessageContent,
                modelPath = modelPath
            )
        }

        return when (format) {
            GgufPromptFormat.GEMMA_CHAT -> buildForGgufGemma(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
            GgufPromptFormat.CHATML -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
            GgufPromptFormat.PLAIN_COMPLETION -> buildForGgufPlainCompletion(messages, systemPrompt, compressedSummary, sanitizeMessageContent)
        }
    }

    /**
     * カスタム / ビルトインテンプレートで実際にプロンプトを構築する共通ルート。
     *
     * ここで圧縮済みサマリーをシステムプロンプトに前置きし、
     * 履歴メッセージは role + sanitized content の形で [PromptTemplateEngine] に渡す。
     */
    private fun buildWithCustomTemplate(
        template: String,
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        sanitizeMessageContent: (MessageEntity) -> String,
        modelPath: String
    ): String {
        val systemFinal = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        val style = resolveThinkingPromptStyle(modelPath, null)
        val rawHistory = messages.mapNotNull { msg ->
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) return@mapNotNull null
            val role = if (msg.role == "assistant") "assistant" else "user"
            PromptTemplateEngine.HistoryMessage(role = role, content = content)
        }
        val history = decorateHistoryForThinkingStyle(rawHistory, style, enableThinking)
        val lastUserContent = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val lastAssistantContent = history.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        val ctx = PromptTemplateEngine.PromptContext(
            system = systemFinal,
            prompt = lastUserContent,
            response = lastAssistantContent,
            thinking = enableThinking && style == ThinkingPromptStyle.ASSISTANT_TAG,
            history = history
        )
        return try {
            val rendered = PromptTemplateEngine.render(template, ctx)
            // Bug fix(#10): テンプレ末尾の assistant 開始部分と thinking prefill の衝突を防ぐため、
            // 同じ prefill が rendered に既に含まれているかをチェックして二重追加を避ける。
            // Qwen OFF 時の「空 <think></think>」、 ASSISTANT_TAG/GEMMA4_CHANNEL ON 時の <think>\n を
            // この一本で一元管理する。
            val prefill = assistantPrefillFor(style, enableThinking)
            val needsSuffix = prefill.isNotEmpty() && !rendered.trimEnd().endsWith(prefill.trimEnd())
            val suffix = if (needsSuffix) prefill else ""
            thinkingGlobalPrefix(style, enableThinking) + rendered + suffix
        } catch (e: Exception) {
            // フォールバック: テンプレ崩壊時もモデル種別に応じた既定フォーマットで再構築する。
            // Bug fix(#42): ここでの detectGgufFormat は appContext を渡せない (buildWithCustomTemplate の呼び出し元にない) ため、
            // GPT-2 判定で PLAIN_COMPLETION に落とす前に、テンプレ自身の内容 (例: <|im_start|> / <start_of_turn>) からフォーマットを推定する。
            val fallbackFormat = when {
                template.contains("<|im_start|>") || template.contains("<|im_end|>") -> GgufPromptFormat.CHATML
                template.contains("<start_of_turn>") || template.contains("<end_of_turn>") -> GgufPromptFormat.GEMMA_CHAT
                template.contains("<|start_header_id|>") || template.contains("<|eot_id|>") -> GgufPromptFormat.CHATML
                template.contains("<|user|>") || template.contains("<|assistant|>") -> GgufPromptFormat.CHATML
                else -> detectGgufFormat(modelPath)
            }
            when (fallbackFormat) {
                GgufPromptFormat.GEMMA_CHAT -> buildForGgufGemma(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
                GgufPromptFormat.CHATML -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
                GgufPromptFormat.PLAIN_COMPLETION -> buildForGgufPlainCompletion(messages, systemPrompt, compressedSummary, sanitizeMessageContent)
            }
        }
    }

    private fun thinkingGlobalPrefix(style: ThinkingPromptStyle, enableThinking: Boolean): String {
        // GEMMA_PREFIX のみグローバルプレフィックスを使う。
        // GEMMA4_CHANNEL はシステムターン内に <|think|> を埋め込むため、グローバルには付けない。
        return if (enableThinking && style == ThinkingPromptStyle.GEMMA_PREFIX) GEMMA_THINK_PREFIX else ""
    }

    /**
 * assistant 開始タグの直後に挿入すべきプレフィル文字列を返す。
     * モデルごとに Thinking ON/OFF を正しく効かせるための中枢ロジック:
     *   - QWEN_COMMAND + OFF → Qwen3 公式 non-thinking jinja と同じ「空 <think>\n\n</think>\n\n」
     *     (デフォルトの chat_template が <think> を吐きそうになっても、これを先に置くことで
     *     モデルは「もう思考は終わった」と認識し、思考をスキップする)
     *   - QWEN_COMMAND + ON  → prefill なし (Qwen はデフォルトで thinking モード)
     *   - ASSISTANT_TAG/GEMMA4_CHANNEL + ON → `<think>\n` を付ける
     *   - それ以外 → 何もしない　
     */
    private fun assistantPrefillFor(style: ThinkingPromptStyle, enableThinking: Boolean): String {
        return when {
            style == ThinkingPromptStyle.QWEN_COMMAND && !enableThinking -> QWEN_EMPTY_THINK_PREFILL
            enableThinking && (style == ThinkingPromptStyle.ASSISTANT_TAG ||
                style == ThinkingPromptStyle.GEMMA4_CHANNEL) -> ASSISTANT_THINK_PREFILL
            else -> ""
        }
    }

    private fun decorateHistoryForThinkingStyle(
        history: List<PromptTemplateEngine.HistoryMessage>,
        style: ThinkingPromptStyle,
        enableThinking: Boolean
    ): List<PromptTemplateEngine.HistoryMessage> {
        if (style != ThinkingPromptStyle.QWEN_COMMAND) return history
        val lastUserIndex = history.indexOfLast { it.role == "user" && it.content.isNotBlank() }
        if (lastUserIndex < 0) return history
        val directive = if (enableThinking) QWEN_THINK_COMMAND else QWEN_NO_THINK_COMMAND
        return history.mapIndexed { index, msg ->
            if (index == lastUserIndex) msg.copy(content = appendDirectiveOnce(msg.content, directive)) else msg
        }
    }

    private fun appendDirectiveOnce(content: String, directive: String): String {
        val trimmed = content.trimEnd()
        val directives = listOf(QWEN_THINK_COMMAND, QWEN_NO_THINK_COMMAND)
        if (directives.any { trimmed.endsWith(it) }) return trimmed
        if (directive in content) return trimmed
        return "$trimmed\n$directive"
    }

    private fun buildForGgufGemma(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        modelPath: String,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val style = resolveThinkingPromptStyle(modelPath)
        val sb = StringBuilder()
        val lastUserMessage = messages.indexOfLast { it.role == "user" && sanitizeMessageContent(it).isNotBlank() }
        sb.append(thinkingGlobalPrefix(style, enableThinking))
        val hasPrelude = systemPrompt.isNotEmpty() || !compressedSummary.isNullOrBlank()
        // Gemma4: Google 公式テンプレ仕様に従い、thinking ON 時はシステムターンの先頭に `<|think|>` を埋め込む。
        // システムプロンプトが空の場合でも、Gemma4 では thinking ON のときだけ `<|think|>` 専用システムターンを生成する。
        val isGemma4Channel = style == ThinkingPromptStyle.GEMMA4_CHANNEL
        if (hasPrelude || (isGemma4Channel && enableThinking)) {
            sb.append("<start_of_turn>user\n")
            if (isGemma4Channel && enableThinking) {
                sb.append("<|think|>")
                if (systemPrompt.isNotEmpty() || !compressedSummary.isNullOrBlank()) sb.append('\n')
            }
            if (systemPrompt.isNotEmpty()) sb.append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (systemPrompt.isNotEmpty()) sb.append("\n\n")
                sb.append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
            sb.append('\n').append("<end_of_turn>\n")
        }
        for (index in messages.indices) {
            val msg = messages[index]
            var content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            if (style == ThinkingPromptStyle.QWEN_COMMAND && index == lastUserMessage) {
                content = appendDirectiveOnce(content, if (enableThinking) QWEN_THINK_COMMAND else QWEN_NO_THINK_COMMAND)
            }
            val role = if (msg.role == "assistant") "model" else "user"
            sb.append("<start_of_turn>").append(role).append('\n')
                .append(content).append('\n').append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
 // Bug fix: assistant 側プレフィルを assistantPrefillFor() で一元化。
        //   Qwen OFF 時に「空 <think>\n\n</think>\n\n」をプレフィルして thinking を抑止する。
        sb.append(assistantPrefillFor(style, enableThinking))
        return sb.toString()
    }

    private fun buildForGgufChatMl(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        modelPath: String,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val style = resolveThinkingPromptStyle(modelPath)
        val sb = StringBuilder()
        val lastUserMessage = messages.indexOfLast { it.role == "user" && sanitizeMessageContent(it).isNotBlank() }
        sb.append(thinkingGlobalPrefix(style, enableThinking))
        val systemContent = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (systemPrompt.isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        // Gemma4 が ChatML テンプレ経由（ユーザーが手動で chatml 選択した場合等）で来た場合も
        // システムターンに <|think|> を埋め込んで thinking を発火させる。
        val isGemma4Channel = style == ThinkingPromptStyle.GEMMA4_CHANNEL
        if (systemContent.isNotEmpty() || (isGemma4Channel && enableThinking)) {
            sb.append("<|im_start|>system\n")
            if (isGemma4Channel && enableThinking) {
                sb.append("<|think|>")
                if (systemContent.isNotEmpty()) sb.append('\n')
            }
            if (systemContent.isNotEmpty()) sb.append(systemContent)
            sb.append("\n<|im_end|>\n")
        }
        for (index in messages.indices) {
            val msg = messages[index]
            var content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            if (style == ThinkingPromptStyle.QWEN_COMMAND && index == lastUserMessage) {
                content = appendDirectiveOnce(content, if (enableThinking) QWEN_THINK_COMMAND else QWEN_NO_THINK_COMMAND)
            }
            val role = if (msg.role == "assistant") "assistant" else "user"
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
 // Bug fix: Qwen OFF 時の「空 <think></think>」も含めてスタイル別に適用。
        sb.append(assistantPrefillFor(style, enableThinking))
        return sb.toString()
    }

    private fun buildForGgufPlainCompletion(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append(systemPrompt.trim()).append("\n\n")
        }
        if (!compressedSummary.isNullOrBlank()) {
            sb.append(COMPRESSED_CONTEXT_HEADER).append('\n')
                .append(compressedSummary.trim())
                .append("\n\n")
        }
        for (msg in messages) {
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "assistant" else "user"
            sb.append(role).append(": ").append(content.trim()).append("\n\n")
        }
        sb.append("assistant:")
        return sb.toString()
    }
}
