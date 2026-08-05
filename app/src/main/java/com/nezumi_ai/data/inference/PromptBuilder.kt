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
         * Qwen 3.0〜3.4 系: 直近 user ターン末尾に `/think` または `/no_think` を付与する
         * ソフトスイッチ方式。Qwen 3.5 以降では廃止されているので QWEN_ASSISTANT_PREFILL を使う。
         */
        QWEN_COMMAND,
        /**
         * Qwen 3.5+ / Qwen3 GGUF 専用: ソフトスイッチ廃止世代のため、
         * assistant ターン直後に必ず prefill を入れて thinking を制御する。
         *   - ON  → `<think>\n`             (thinking 発火)
         *   - OFF → `<think>\n\n</think>\n\n` (公式 non-thinking jinja と同じ空思考 prefill)
         * これにより llama.cpp 側の chat_template が enable_thinking をサポートしていなくても
         * 思考 ON/OFF を 100% 制御できる。
         */
        QWEN_ASSISTANT_PREFILL,
        /**
         * llama.cpp で標準的に使われる `<think>...</think>` プレフィル方式。
         * DeepSeek-R1 / Llama-3.1-R / QwQ など thinking がデフォルト常時 ON なモデルで使う。
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
            // Bug fix(#45): Gemma 4 の Thinking は公式仕様で「システムターン内に <|think|>、
            // model ターンの prefill」という Gemma 固有の構造を使う (Google AI 公式ドキュメント参照)。
            // 前回 CHATML に振ってしまったため <start_of_turn>user\n<|think|>\n... という
            // GEMMA4_CHANNEL 専用のビルド経路 (buildForGgufGemma) を通らなくなり Thinking が発火しなかった。
            // Gemma 3/4 は引き続き GEMMA_CHAT でビルドし、Gemma4 固有の Thinking 制御は
            // buildForGgufGemma 内の GEMMA4_CHANNEL 分岐に任せる。
            "gemma" in name -> GgufPromptFormat.GEMMA_CHAT
            else -> GgufPromptFormat.CHATML
        }
    }

    fun resolveThinkingPromptStyle(modelPath: String, appContext: Context? = null): ThinkingPromptStyle {
        val name = modelPath.lowercase()
        val userOverride = hasExplicitUserTemplate(appContext, modelPath)
        return when {
            !userOverride && isGpt2Model(modelPath) -> ThinkingPromptStyle.PLAIN_COMPLETION
            // Bug fix(#44,#46): Qwen 判定を世代別に完全分離。
            //   - Qwen 3.5+ / Qwen3 公式 GGUF : QWEN_ASSISTANT_PREFILL で <think>\n / <think>\n\n</think>\n\n を
            //     assistant ターン直後に必ず入れる (Hugging Face 公式 non-thinking jinja と同じ形)。
            //     Qwen3.5-2B は公式仕様で non-thinking がデフォルトだが、llama.rn は公式
            //     chat_template を使わず自前組立のため prefill を強制しないと <think> が暴発する。
            //   - Qwen 3.0 〜 3.4 : 从来の /think・/no_think ソフトスイッチ (QWEN_COMMAND)。
            //   - Qwen 2.x / qwen-max 等 : thinking 非対応なので何も注入せず ASSISTANT_TAG (OFF 時 prefill 無し)。
            //   - QwQ : thinking 常時 ON。ASSISTANT_TAG。
            isQwen35OrLaterModelName(name) -> ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            isQwenSoftSwitchCompatibleModelName(name) -> ThinkingPromptStyle.QWEN_COMMAND
            // Gemma4 (GGUF / litert) は thinking 構造が Gemma3 と異なるため専用スタイルへ振り分ける。
            // 一致条件: "gemma4", "gemma-4", "gemma_4", e2b/e4b/26b-a4b など Gemma4 サイズ識別子。
            isGemma4ModelName(name) -> ThinkingPromptStyle.GEMMA4_CHANNEL
            "gemma" in name -> ThinkingPromptStyle.GEMMA_PREFIX
            else -> ThinkingPromptStyle.ASSISTANT_TAG
        }
    }

    /**
     * Qwen 3.5 以降のモデルを判定する (QWEN_ASSISTANT_PREFILL の対象)。
     * Qwen 公式は 3.5、3.6 と minor バージョンを上げているので、
     * 「major==3 && minor>=5」または「major>=4」ならこれに当てる。
     */
    private fun isQwen35OrLaterModelName(loweredName: String): Boolean {
        if (!Regex("(^|[^a-z])qwen(?![a-z])").containsMatchIn(loweredName)) return false
        val versionRegex = Regex("qwen[\\-_ ]?(\\d+)(?:[\\.](\\d+))?")
        val match = versionRegex.find(loweredName) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minorRaw = match.groupValues.getOrNull(2).orEmpty()
        // "qwen3.4b" というパラメータ数表記は minor バージョンではない
        val afterMatch = loweredName.substring(match.range.last + 1)
        val minorIsParamCount = minorRaw.isNotEmpty() && afterMatch.startsWith("b")
        val minor = if (minorRaw.isNotEmpty() && !minorIsParamCount) minorRaw.toIntOrNull() else null
        return when {
            major >= 4 -> true                    // Qwen 4.x 以降も将来の互換のためこちら
            major == 3 && minor != null && minor >= 5 -> true   // Qwen 3.5, 3.6, ...
            else -> false
        }
    }

    /**
     * Qwen 系モデルのうち、`/think` および `/no_think` ソフトスイッチが有効な世代かを判定する。
     *
     * サポート対象:
     *   - Qwen 3.0 〜 3.4 系 (Qwen3, Qwen-3, qwen3-14b, qwen-3.2-4b-instruct など)
     *
     * 除外対象:
     *   - Qwen 3.5 以降: 公式仕様でソフトスイッチが廃止され、常に thinking がデフォルト有効になる。
     *     コマンド注入は無視されるだけでなく、prefill が効かないと勝手に思考してしまう。
     *   - Qwen 2.x 以下: そもそも thinking 機能をネイティブに持たない。/think を渡すと
     *     モデルが「指示に従おうとして」不完全な思考出力を作ってしまう。
     *   - QwQ 系: thinking がデフォルト常時 ON。ソフトスイッチは提供されていない。
     */
    private fun isQwenSoftSwitchCompatibleModelName(loweredName: String): Boolean {
        // QwQ はソフトスイッチ非対応 (デフォルト常時 thinking) なので除外し、
        // ASSISTANT_TAG 経由で <think>...</think> の抑止・prefill を扱う。
        // "qwen3" / "qwen-3" のように直後に数字やハイフンが来ることを許すため、
        // 一般的な単語境界 ([^a-z0-9]) ではなく (?![a-z]) (英字が直後に来ない) で判定する。
        // これにより qwq は引っかからず、qwen3 / qwen-3-14b を全て拂える。
        if (!Regex("(^|[^a-z])qwen(?![a-z])").containsMatchIn(loweredName)) return false
        // 明示的にメジャーバージョンが読み取れる場合はバージョン範囲でフィルタする。
        // 例: qwen3, qwen-3, qwen_3, qwen 3, qwen3.2, qwen-3.4-7b, qwen3.4b ("3.4b" のようなパラメータ数表記に注意)
        val versionRegex = Regex("qwen[\\-_ ]?(\\d+)(?:[\\.](\\d+))?")
        val match = versionRegex.find(loweredName)
        if (match != null) {
            val major = match.groupValues[1].toIntOrNull() ?: return false
            val minorRaw = match.groupValues.getOrNull(2).orEmpty()
            // "qwen3.4b" のような小数点+ 'b' はマイナーバージョンではなくパラメータ数なので minor = null 扱い。
            val afterMatch = loweredName.substring(match.range.last + 1)
            val minorIsParamCount = minorRaw.isNotEmpty() && afterMatch.startsWith("b")
            val minor = if (minorRaw.isNotEmpty() && !minorIsParamCount) minorRaw.toIntOrNull() else null
            if (major != 3) return false // Qwen 3 系列以外は非対応
            // Qwen 3.5 以降はソフトスイッチ廃止 → 除外
            if (minor != null && minor >= 5) return false
            return true
        }
        // バージョンが名前から取れない Qwen 系 ("qwen-max", "qwen-plus" 等) はソフトスイッチ非対応として扱い、
        // 誤って /think を注入しないようにする。
        return false
    }

    private fun isGemma4ModelName(loweredName: String): Boolean {
        if ("gemma" !in loweredName) return false
        // Bug fix(#43): 命名規則の揺れに対応するため判定を拡張。
        // "gemma4", "gemma-4", "gemma_4", "gemma 4", "gemma.4" などの直接表記
        if (Regex("gemma[\\-_ .]?4(?![0-9])").containsMatchIn(loweredName)) return true
        // E2B / E4B / E8B / E12B (Gemma 4 の "Efficient" シリーズ) など英字プレフィックス系サイズ識別子
        if (Regex("(^|[^a-z0-9])(e2b|e4b|e8b|e12b)([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        // 12B-A4B / 26B-A4B / 31B-A4B / 46B-A4B など MoE 表記 (activated 4B) を伴う Gemma 4 系
        if (Regex("(^|[^a-z0-9])(12b|26b|31b|46b)[\\-_]?a4b([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        // "gemma4b" 等の 4b 単独表記 (Google がリリース時に採用した短縮命名)
        if (Regex("gemma[\\-_ .]?4b(?![0-9])").containsMatchIn(loweredName)) return true
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
     * Qwen 3.5+ (QWEN_ASSISTANT_PREFILL) も <think>...</think> プレフィル方式。
     */
    fun usesAssistantThinkingPrefill(modelPath: String): Boolean {
        val style = resolveThinkingPromptStyle(modelPath)
        return style == ThinkingPromptStyle.ASSISTANT_TAG ||
            style == ThinkingPromptStyle.GEMMA4_CHANNEL ||
            style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
    }

    /**
     * モデル名から Qwen 系かを判定し、non-thinking jinja 相当の空 <think></think>
     * プレフィルを使うべきかを返す。ユーザーが Thinking OFF にしたのに
     * モデルが chat_template の関係で <think> を吐くケースの最強の抑止手段。
     * Qwen 3.0-3.4 (QWEN_COMMAND) と Qwen 3.5+ (QWEN_ASSISTANT_PREFILL) を両方含む。
     */
    fun usesQwenStyleThinking(modelPath: String): Boolean {
        val style = resolveThinkingPromptStyle(modelPath)
        return style == ThinkingPromptStyle.QWEN_COMMAND ||
            style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
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
                modelPath = modelPath,
                appContext = appContext
            )
        }

        // Bug fix(#47): カスタムテンプレ未設定の LiteRT 経路は、以前は
        //   "User: xxx\nAssistant: yyy\nAssistant:" というプレーンな role prefix を吐いていたが、
        //   これが Gemma / Llama 系の LiteRT ランタイムでもチャットテンプレを回避してしまい、
        //   モデルが自身の chat_template を適用したときに "User:" / "Assistant:" が生の
        //   トークンとして混入する不具合の主要因になっていた。
        //   ここでは常に Gemma 標準の <start_of_turn> / <end_of_turn> ChatML 相当に統一して
        //   プレフィックス role リテラルが二度と混入しないようにする。
        val gemmaBuilder = StringBuilder()
        if (injectGemmaThinkTrigger) {
            gemmaBuilder.append(GEMMA_THINK_PREFIX)
        }
        val preludeContent = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        if (preludeContent.isNotEmpty()) {
            gemmaBuilder.append("<start_of_turn>user\n")
                .append(preludeContent)
                .append("\n<end_of_turn>\n")
        }
        for (msg in messages) {
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "model" else "user"
            gemmaBuilder.append("<start_of_turn>").append(role).append('\n')
                .append(content).append("\n<end_of_turn>\n")
        }
        gemmaBuilder.append("<start_of_turn>model\n")
        return gemmaBuilder.toString()
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
                modelPath = modelPath,
                appContext = appContext
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
        modelPath: String,
        appContext: Context? = null
    ): String {
        val systemFinal = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        val style = resolveThinkingPromptStyle(modelPath, appContext)
        val rawHistory = messages.mapNotNull { msg ->
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) return@mapNotNull null
            val role = if (msg.role == "assistant") "assistant" else "user"
            PromptTemplateEngine.HistoryMessage(role = role, content = content)
        }
        val history = decorateHistoryForThinkingStyle(rawHistory, style, enableThinking)
        // Bug fix(#48): テンプレが `{{ .Prompt }}` と `{{ range .History }}` の両方を持つ場合、
        // これまでは最後の user / assistant を Prompt / Response に **もコピー** して渡していたため、
        // 「履歴末尾がラップされた形」と「Prompt/Response でラップされない裸のテキスト」の
        // 二重展開が起きて `user:` / `assistant:` 相当の生ロールが混入するケースがあった。
        // History に既に最後のユーザー / アシスタントターンが含まれる場合は、
        // Prompt / Response を空文字にして二重展開を封じる。
        val hasHistoryUser = history.any { it.role == "user" }
        val hasHistoryAssistant = history.any { it.role == "assistant" }
        val lastUserContent = if (hasHistoryUser) "" else ""
        val lastAssistantContent = if (hasHistoryAssistant) "" else ""
        val ctx = PromptTemplateEngine.PromptContext(
            system = systemFinal,
            prompt = lastUserContent,
            response = lastAssistantContent,
            // Bug fix(#46): thinking プレースホルダは ASSISTANT_TAG だけでなく、Gemma4/Qwen3.5+ の
            // プレフィル方式でも ON になる必要がある。
            thinking = enableThinking && (
                style == ThinkingPromptStyle.ASSISTANT_TAG ||
                style == ThinkingPromptStyle.GEMMA4_CHANNEL ||
                style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            ),
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
            // Bug fix(#42, #47): buildWithCustomTemplate の呼び出し元から appContext を受け取れるようになったので、
            // フォールバック時も detectGgufFormat(modelPath, appContext) を通してユーザー選択を尊重する。
            // これにより GPT-2 アーキ判定であってもユーザー選択の ChatML / Gemma を優先し、
            // PLAIN_COMPLETION に落ちて `user:` / `assistant:` が混入するのを防ぐ。
            val fallbackFormat = when {
                template.contains("<|im_start|>") || template.contains("<|im_end|>") -> GgufPromptFormat.CHATML
                template.contains("<start_of_turn>") || template.contains("<end_of_turn>") -> GgufPromptFormat.GEMMA_CHAT
                template.contains("<|start_header_id|>") || template.contains("<|eot_id|>") -> GgufPromptFormat.CHATML
                template.contains("<|user|>") || template.contains("<|assistant|>") -> GgufPromptFormat.CHATML
                else -> detectGgufFormat(modelPath, appContext)
            }
            // Bug fix(#47): カスタムテンプレを明示的に選んでいるユーザーが
            // PLAIN_COMPLETION に落ちて `user:` / `assistant:` を混入させるのを防ぐため、
            // ここでは PLAIN_COMPLETION を選択肢から排除し ChatML に統一する。
            val safeFallback = if (fallbackFormat == GgufPromptFormat.PLAIN_COMPLETION) {
                GgufPromptFormat.CHATML
            } else fallbackFormat
            when (safeFallback) {
                GgufPromptFormat.GEMMA_CHAT -> buildForGgufGemma(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
                GgufPromptFormat.CHATML -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
                GgufPromptFormat.PLAIN_COMPLETION -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
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
     *
     *   - QWEN_COMMAND + OFF          → 空 <think>\n\n</think>\n\n (Qwen3 公式 non-thinking jinja 相当)
     *   - QWEN_COMMAND + ON           → prefill なし (ソフトスイッチとデフォルト thinking に任せる)
     *
     *   - QWEN_ASSISTANT_PREFILL + OFF → 空 <think>\n\n</think>\n\n (Qwen 3.5+ の公式 jinja 相当)
     *     Bug fix(#46): llama.rn は公式 chat_template の enable_thinking を使わず自前で ChatML を
     *     組み立てるため、この prefill を必ず入れないと Qwen3.5-2B が <think> を暴発させる。
     *   - QWEN_ASSISTANT_PREFILL + ON  → `<think>\n` (thinking 発火を確実にする)
     *
     *   - ASSISTANT_TAG/GEMMA4_CHANNEL + ON  → `<think>\n`
     *   - それ以外 → 何もしない
     */
    private fun assistantPrefillFor(style: ThinkingPromptStyle, enableThinking: Boolean): String {
        return when {
            // Qwen 系の OFF: 空 <think></think> を必ず先入れして chat_template の暴発を封じる。
            !enableThinking && (style == ThinkingPromptStyle.QWEN_COMMAND ||
                style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL) -> QWEN_EMPTY_THINK_PREFILL
            // Thinking 発火側: <think>\n を assistant 直後に入れる。
            enableThinking && (style == ThinkingPromptStyle.ASSISTANT_TAG ||
                style == ThinkingPromptStyle.GEMMA4_CHANNEL ||
                style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL) -> ASSISTANT_THINK_PREFILL
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

    /**
     * Bug fix(#47): `user:` / `assistant:` の生ロールプレフィックスを吐く唯一のビルダー。
     *
     * GPT-2 / distilgpt2 のような「チャットテンプレートを持たない completion モデル」向けだが、
     * ここを以前は「フォールバック先」として使っていたため、Gemma や ChatML を選択しても
     * レンダリング中の例外や GPT-2 名判定でここへ飛ばされ "user:" / "assistant:" が混入していた。
     *
     * 現在は以下パスのみから到達する:
     *   - detectGgufFormat が PLAIN_COMPLETION を返し、かつ buildWithCustomTemplate を通らないケース
     *     (= ユーザーが MODE_AUTO のまま GPT-2 モデルを使う場合)
     * 将来カスタムテンプレのフォールバックからは呼ばないため、ユーザー選択を談しに押しつぶす安全性は確保された。
     */
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
