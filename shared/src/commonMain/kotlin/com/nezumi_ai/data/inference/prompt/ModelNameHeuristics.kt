package com.nezumi_ai.data.inference.prompt

/**
 * モデル名 / パスからモデルファミリを推定するヒューリスティックの単一の真実源 (計画書 2.4)。
 *
 * 旧実装では以下の2箇所に同一ロジックが重複していた (DRY 違反):
 *   - app 側 `PromptBuilder` (isGemma4ModelName / isQwen35OrLaterModelName / ...)
 *   - shared 側 `Gemma4ModelDetector` (ツール呼び出しタグ形式の判定専用)
 * これらをこのオブジェクトに統合し、Phase 3 の `GgufRenderer` / Phase 5 の
 * `CloudRenderer` / ツール呼び出しタグ形式判定のすべてがここを参照する。
 *
 * すべて純粋関数 (副作用なし) で、ファイル I/O (GGUF アーキテクチャ読み取り) や
 * Android Context には依存しない。ファイル実体の検査が必要な判定
 * (旧 `isGpt2Architecture`) は app 側の呼び出し元で行い、その結果を
 * `isGpt2ArchitectureHint` として渡す設計とした。
 */
object ModelNameHeuristics {

    /** thinking プロンプト制御のスタイル。旧 `PromptBuilder.ThinkingPromptStyle` の移行先。 */
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
         */
        GEMMA4_CHANNEL,

        /**
         * GPT-2 のような plain completion モデル。chat/thinking 制御タグは注入しない。
         */
        PLAIN_COMPLETION
    }

    // ---- 判定ロジック (旧 PromptBuilder / Gemma4ModelDetector からの移行) ----

    /**
     * Gemma 4 系モデル名かどうかを判定する。
     * 旧 `PromptBuilder.isGemma4ModelName` と `Gemma4ModelDetector` の統合版。
     *
     * 一致条件: "gemma4", "gemma-4", "gemma_4" 等の直接表記、
     * E2B / E4B 等の Gemma 4 Efficient シリーズ識別子、12B-A4B / 26B-A4B 等の
     * MoE 表記 (activated 4B)、および "gemma4b" 等の短縮命名。
     */
    fun isGemma4ModelName(loweredName: String): Boolean {
        if ("gemma" !in loweredName) return false
        // Bug fix(#43): 命名規則の揺れに対応するため判定を拡張。
        if (Regex("gemma[\\-_ .]?4(?![0-9])").containsMatchIn(loweredName)) return true
        // E2B / E4B / E8B / E12B (Gemma 4 の "Efficient" シリーズ) など英字プレフィックス系サイズ識別子
        if (Regex("(^|[^a-z0-9])(e2b|e4b|e8b|e12b)([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        // 12B-A4B / 26B-A4B / 31B-A4B / 46B-A4B など MoE 表記 (activated 4B) を伴う Gemma 4 系
        if (Regex("(^|[^a-z0-9])(12b|26b|31b|46b)[\\-_]?a4b([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        // "gemma4b" 等の 4b 単独表記 (Google がリリース時に採用した短縮命名)
        if (Regex("gemma[\\-_ .]?4b(?![0-9])").containsMatchIn(loweredName)) return true
        return false
    }

    /**
     * モデル ID / パスから Gemma 4 系かを判定する公開ヘルパー
     * (ツール呼び出しタグ形式・パーサ・ストップシーケンス側で参照)。
     *
     * クラウドモデル ID (`cloud:provider:modelName`) の場合は、プロバイダ接頭辞を剥がした
     * 実モデル名だけを見て判定する。Ollama / LM Studio 等で llama・qwen などを使うときに
     * Gemma 公式の `<|tool_call>call:NAME{...}<tool_call|>` 形式を誤って注入しないため。
     * (旧 `PromptBuilder.isGemma4Model` / `Gemma4ModelDetector.isGemma4Model` の移行先)
     */
    fun isGemma4Model(modelIdOrPath: String): Boolean {
        val raw = modelIdOrPath.trim()
        if (raw.isEmpty()) return false
        return isGemma4ModelName(resolveModelNameForCheck(raw).lowercase())
    }

    /**
     * 判定対象のモデル名を正規化する。
     * - `cloud:provider:modelName` → modelName (依存を避けるため CloudModelId を直接呼ばず同等ロジック)
     * - ローカルパス → ファイル名部分 (親ディレクトリに gemma があっても誤判定しない)
     * (旧 `resolveModelNameForGemmaCheck` の移行先)
     */
    fun resolveModelNameForCheck(modelIdOrPath: String): String {
        val trimmed = modelIdOrPath.trim()
        if (trimmed.startsWith("cloud:", ignoreCase = true)) {
            val body = trimmed.substringAfter(":")
            val parts = body.split(":", limit = 2)
            // cloud:provider:model... → model 部分（':' を含みうる）
            if (parts.size >= 2) {
                val modelName = parts[1]
                if (modelName.isNotBlank()) return modelName
            }
            return trimmed
        }
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        if (slash >= 0 && slash < trimmed.lastIndex) {
            return trimmed.substring(slash + 1)
        }
        return trimmed
    }

    /**
     * Qwen 3.5 以降のモデルを判定する (QWEN_ASSISTANT_PREFILL の対象)。
     * Qwen 公式は 3.5、3.6 と minor バージョンを上げているので、
     * 「major==3 && minor>=5」または「major>=4」ならこれに当てる。
     */
    fun isQwen35OrLaterModelName(loweredName: String): Boolean {
        if (!Regex("(^|[^a-z])qwen(?![a-z])").containsMatchIn(loweredName)) return false
        val version = parseQwenVersion(loweredName) ?: return false
        val (major, minor) = version
        return when {
            major >= 4 -> true                                  // Qwen 4.x 以降も将来の互換のためこちら
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
     *   - Qwen 2.x 以下: そもそも thinking 機能をネイティブに持たない。/think を渡すと
     *     モデルが「指示に従おうとして」不完全な思考出力を作ってしまう。
     *   - QwQ 系: thinking がデフォルト常時 ON。ソフトスイッチは提供されていない。
     *   - バージョンが名前から取れない Qwen 系 ("qwen-max", "qwen-plus" 等):
     *     ソフトスイッチ非対応として扱い、誤って /think を注入しないようにする。
     */
    fun isQwenSoftSwitchCompatibleModelName(loweredName: String): Boolean {
        // QwQ はソフトスイッチ非対応 (デフォルト常時 thinking) なので除外。
        // "qwen3" / "qwen-3" のように直後に数字やハイフンが来ることを許すため、
        // 一般的な単語境界 ([^a-z0-9]) ではなく (?![a-z]) (英字が直後に来ない) で判定する。
        if (!Regex("(^|[^a-z])qwen(?![a-z])").containsMatchIn(loweredName)) return false
        val (major, minor) = parseQwenVersion(loweredName) ?: return false
        if (major != 3) return false // Qwen 3 系列以外は非対応
        // Qwen 3.5 以降はソフトスイッチ廃止 → 除外
        if (minor != null && minor >= 5) return false
        return true
    }

    /**
     * Qwen モデル名から (major, minor) バージョンを取り出す。
     * minor が取れない場合や "qwen3.4b" のように小数点+ 'b' がパラメータ数表記の場合は
     * minor = null とする。バージョン自体が読めなければ null。
     */
    private fun parseQwenVersion(loweredName: String): Pair<Int, Int?>? {
        val versionRegex = Regex("qwen[\\-_ ]?(\\d+)(?:[\\.](\\d+))?")
        val match = versionRegex.find(loweredName) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minorRaw = match.groupValues.getOrNull(2).orEmpty()
        // "qwen3.4b" のような小数点+ 'b' はマイナーバージョンではなくパラメータ数なので minor = null 扱い。
        val afterMatch = loweredName.substring(match.range.last + 1)
        val minorIsParamCount = minorRaw.isNotEmpty() && afterMatch.startsWith("b")
        val minor = if (minorRaw.isNotEmpty() && !minorIsParamCount) minorRaw.toIntOrNull() else null
        return major to minor
    }

    /**
     * Liquid Foundation Models (LFM / LFM2 / LFM2.5 系) かどうかを判定する。
     * モデル名に "lfm" が含まれるものを対象 (例: LFM2.5-2.6B, LFM2.5-1.2B-Thinking)。
     */
    fun isLfmModelName(loweredName: String): Boolean {
        return Regex("(^|[^a-z0-9])lfm([0-9]|[_\\-.]|$)").containsMatchIn(loweredName)
    }

    /** GPT-2 系のモデル名かどうかを判定する (ファイル実体の検査は含まない)。 */
    fun isGpt2ModelName(loweredName: String): Boolean {
        return Regex("(^|[^a-z0-9])gpt[\\-_ ]?2([^a-z0-9]|$)").containsMatchIn(loweredName)
    }

    /**
     * GPT-2 系かどうかを判定する。モデル名の判定に加え、呼び出し側で GGUF メタデータの
     * アーキテクチャを読んで分かっている場合は [isGpt2ArchitectureHint] に true を渡す
     * (旧 `isGpt2Architecture` のファイル I/O は app 側 `GgufMetadataReader` に残す)。
     */
    fun isGpt2Model(modelPath: String, isGpt2ArchitectureHint: Boolean = false): Boolean {
        return isGpt2ModelName(modelPath.lowercase()) || isGpt2ArchitectureHint
    }

    // ---- スタイル / 形式の解決 (旧 resolveThinkingPromptStyle / detectGgufFormat) ----

    /**
     * thinking プロンプト制御スタイルを解決する。
     *
     * @param modelPathOrName モデルパスまたはモデル名 (lowercase 変換は内部で行う)
     * @param hasExplicitUserTemplate ユーザーが MODE_AUTO 以外のテンプレートを選択済みか。
     *        true の場合、GPT-2 への PLAIN_COMPLETION 強制は行わない (旧 `hasExplicitUserTemplate`)。
     * @param isGpt2ArchitectureHint GGUF メタデータ上の architecture が gpt2 と判明している場合 true。
     */
    fun resolveThinkingPromptStyle(
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
        isGpt2ArchitectureHint: Boolean = false,
    ): ThinkingPromptStyle {
        val name = modelPathOrName.lowercase()
        return when {
            !hasExplicitUserTemplate && isGpt2Model(name, isGpt2ArchitectureHint) ->
                ThinkingPromptStyle.PLAIN_COMPLETION
            // Bug fix(#44,#46): Qwen 判定を世代別に完全分離。
            isQwen35OrLaterModelName(name) -> ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            isQwenSoftSwitchCompatibleModelName(name) -> ThinkingPromptStyle.QWEN_COMMAND
            // Gemma4 は thinking 構造が Gemma3 と異なるため専用スタイルへ振り分ける。
            isGemma4ModelName(name) -> ThinkingPromptStyle.GEMMA4_CHANNEL
            "gemma" in name -> ThinkingPromptStyle.GEMMA_PREFIX
            // LFM / LFM2 / LFM2.5: 公式 chat_template は ChatML + <think> prefill 方式。
            isLfmModelName(name) -> ThinkingPromptStyle.ASSISTANT_TAG
            else -> ThinkingPromptStyle.ASSISTANT_TAG
        }
    }

    /**
     * GGUF モデルの手組みフォールバック形式を推定する (FormatResolver の推定経路で使用)。
     *
     * Bug fix(#42): ユーザーが明示的にテンプレを選んでいる場合は GPT-2 でも
     * PLAIN_COMPLETION を強制しない (ChatML / Gemma 等の選択を尊重する)。
     * Bug fix(#45): Gemma 4 は GEMMA_CHAT を返し、Gemma4 固有の Thinking 制御は
     * レンダラー内の GEMMA4_CHANNEL 分岐に任せる (CHATML に振ると Thinking が発火しない)。
     */
    fun guessGgufFormat(
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
        isGpt2ArchitectureHint: Boolean = false,
    ): PromptFormat {
        val name = modelPathOrName.lowercase()
        return when {
            !hasExplicitUserTemplate && isGpt2Model(name, isGpt2ArchitectureHint) ->
                PromptFormat.PlainCompletion
            "gemma" in name -> PromptFormat.GemmaChat
            else -> PromptFormat.ChatMl
        }
    }

    // ---- 派生ヘルパー (旧 usesAssistantThinkingPrefill / usesQwenStyleThinking) ----

    /**
     * `<think>\n` を assistant 開始タグ直後にプレフィルすべきかどうか。
     * ASSISTANT_TAG のほか、Gemma4 GGUF (GEMMA4_CHANNEL) と Qwen 3.5+
     * (QWEN_ASSISTANT_PREFILL) も `<think>...</think>` 形式で thinking を吐くため対象。
     */
    fun usesAssistantThinkingPrefill(
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
        isGpt2ArchitectureHint: Boolean = false,
    ): Boolean {
        val style = resolveThinkingPromptStyle(modelPathOrName, hasExplicitUserTemplate, isGpt2ArchitectureHint)
        return style == ThinkingPromptStyle.ASSISTANT_TAG ||
            style == ThinkingPromptStyle.GEMMA4_CHANNEL ||
            style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
    }

    /**
     * Qwen 系の non-thinking jinja 相当の空 `<think></think>` プレフィルを使うべきかを返す。
     * Qwen 3.0-3.4 (QWEN_COMMAND) と Qwen 3.5+ (QWEN_ASSISTANT_PREFILL) を両方含む。
     */
    fun usesQwenStyleThinking(
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
        isGpt2ArchitectureHint: Boolean = false,
    ): Boolean {
        val style = resolveThinkingPromptStyle(modelPathOrName, hasExplicitUserTemplate, isGpt2ArchitectureHint)
        return style == ThinkingPromptStyle.QWEN_COMMAND ||
            style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
    }
}
