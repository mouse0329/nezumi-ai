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
     * Qwen 3.8 以降のモデルを判定する (reasoning_effort 変数を実際に解釈する世代)。
     *
     * 実測によると Qwen3.5 / 3.6 の公式 chat_template は `reasoning_effort` に
     * 一切言及せず `enable_thinking` の ON/OFF のみで、Qwen3.8 系で初めて
     * `reasoning_effort ∈ {low, medium, xhigh}` の比較 (未知値は raise_exception)
     * が導入された。[isQwen35OrLaterModelName] は QWEN_ASSISTANT_PREFILL など
     * 別用途の判定であり、reasoning_effort 対応の判定には使わないこと。
     */
    fun isQwen38OrLaterModelName(loweredName: String): Boolean {
        if (!Regex("(^|[^a-z])qwen(?![a-z])").containsMatchIn(loweredName)) return false
        val version = parseQwenVersion(loweredName) ?: return false
        val (major, minor) = version
        return when {
            major >= 4 -> true
            major == 3 && minor != null && minor >= 8 -> true
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

    /**
     * ツールコールの出力タグ形式。モデルファミリではなく「出力形式」の分類。
     * 解決の最優先は GGUF 内蔵 chat_template (app 側 `GgufFormatResolver.resolveToolCallFormat`)、
     * ここのモデル名推定はテンプレートが読めない場合のフォールバック。
     */
    enum class ToolCallFormat {
        /** `<tool_call>{"name":..,"arguments":..}</tool_call>` (Qwen / Hermes 等)。 */
        GENERIC,

        /** `<|tool_call>call:NAME{...}<tool_call|>` (Gemma 4 Google 公式)。 */
        GEMMA4,

        /**
         * `<tool_call><function=name><parameter=k>v</parameter></function></tool_call>`
         * (IBM Granite 4.x 公式 chat_template が要求する XML 形式)。
         */
        GRANITE
    }

    /**
     * IBM Granite 4.x 以降のモデル名かどうかを判定する。
     * Granite 3.x までは汎用 `<tool_call>{json}</tool_call>` 形式、4.0 以降は公式
     * chat_template が `<function=name>` 形式を要求するため判定を分ける。
     * バージョンが読めない "granite" 単独表記は従来どおり GENERIC 扱い (誤爆防止)。
     */
    fun isGranite4OrLaterModelName(loweredName: String): Boolean {
        if ("granite" !in loweredName) return false
        val match = Regex("granite[\\-_ .]?(\\d+)").find(loweredName) ?: return false
        return (match.groupValues[1].toIntOrNull() ?: 0) >= 4
    }

    /**
     * モデル名からツールコール出力形式を推定する (テンプレート非読取時のフォールバック)。
     * ユーザーが手動でツールを ON にしただけのモデルは GENERIC (デフォルト) になる。
     */
    fun guessToolCallFormat(loweredName: String): ToolCallFormat = when {
        isGemma4ModelName(loweredName) -> ToolCallFormat.GEMMA4
        isGranite4OrLaterModelName(loweredName) -> ToolCallFormat.GRANITE
        else -> ToolCallFormat.GENERIC
    }

    /**
     * chat_template 文字列がツールコールを宣言しているかを判定する。
     * GGUF メタデータ (`tokenizer.chat_template`) から読んだテンプレートに対して使う。
     */
    fun templateDeclaresToolSupport(template: String): Boolean =
        template.contains("tools is defined") ||
            template.contains("tool_calls") ||
            template.contains("<tool_call>") ||
            template.contains("<function=") ||
            template.contains("<tools>")

    /**
     * チャットテンプレートが `reasoning_effort` 変数を解釈するかどうか。
     * (Qwen3.5 / gpt-oss 系など、思考強度をテンプレート経由で制御するモデル。)
     * 要望: 思考強度 (low / medium / high) の UI 表示とプロンプト注入は、
     * この判定が true のモデルのみで行う。
     */
    fun templateSupportsThinkingEffort(template: String): Boolean =
        template.contains("reasoning_effort")

    /**
     * モデル名が `reasoning_effort` 前提の公式テンプレートを持つ既知ファミリかどうか。
     * GGUF メタデータが読めない状況 (ロード前 / クラウド) でのフォールバック判定。
     *
     * 修正: Qwen は 3.5 / 3.6 では reasoning_effort 非対応と実測で判明したため、
     * [isQwen35OrLaterModelName] ではなく [isQwen38OrLaterModelName] を使う。
     * テンプレートが読める場合は [templateSupportsThinkingEffort] による実測判定が
     * 常に優先され、ここは読めない場合のみのフォールバックである点に注意。
     */
    fun usesThinkingEffortVariable(modelPathOrName: String): Boolean {
        val name = modelPathOrName.lowercase()
        return "gpt-oss" in name || "gpt_oss" in name ||
            isQwen38OrLaterModelName(name)
    }

    // ---- 思考強度 (reasoning_effort) のテンプレート解析 ----

    /**
     * chat_template が `reasoning_effort` 変数を解釈する粒度。
     * [parseReasoningEffortGranularity] の解析結果として使う。
     */
    sealed interface ReasoningEffortGranularity {
        /** テンプレートに `reasoning_effort` の言及がない (effort 概念なし)。 */
        data object None : ReasoningEffortGranularity

        /** "low" のみが比較対象 (low / それ以外の 2 値。例: IBM Granite 4.x)。 */
        data object Binary : ReasoningEffortGranularity

        /** 複数レベルが個別に比較されている (例 {"low","medium","high"})。 */
        data class Graded(val levels: Set<String>) : ReasoningEffortGranularity
    }

    /** UI 選択肢の表示順 (既知レベル優先、その後に未知レベルを名前順で並べる)。 */
    private val KNOWN_EFFORT_ORDER = listOf("minimal", "low", "medium", "high", "xhigh", "max")

    /** `reasoning_effort == "xxx"` / `'xxx'` 形式の比較パターンを抽出する正規表現。 */
    private val REASONING_EFFORT_COMPARISON_REGEX =
        Regex("""reasoning_effort\s*==\s*["']([A-Za-z0-9_\-]+)["']""")

    /**
     * `(resolved_)?reasoning_effort (not )?in ('xhigh', 'medium', 'low')` のような
     * タプル所属チェック (Python/Jinja の `in` 演算子) から候補集合を抽出する正規表現。
     * 変数名が `reasoning_effort` そのものではなく、`resolved_reasoning_effort` のように
     * 一度 `reasoning_effort|default(...)` 等で束ね直された別名になっているケースがある
     * (prism-ml/Ternary-Bonsai-2-27B-gguf 系で確認) ため、変数名は限定せず
     * 直前の `in` 演算子とタプルの並びのみを見る。
     */
    private val REASONING_EFFORT_IN_TUPLE_REGEX =
        Regex("""reasoning_effort\b[^\n(){}]*?\bin\s*\(([^)]+)\)""")

    private val QUOTED_TOKEN_REGEX = Regex("""["']([A-Za-z0-9_\-]+)["']""")

    /**
     * chat_template 文字列を静的にスキャンし、`reasoning_effort` がどの値と
     * 比較されているかを検出する (GGUF メタデータ `tokenizer.chat_template`
     * やユーザー選択の Jinja テンプレートに対して使う)。
     *
     * 判定規則:
     *  - テンプレートに `reasoning_effort` の言及自体がない → None
     *  - 比較対象が "low" の 1 種類のみ → Binary
     *    (Granite 4.2 系の `reasoning_effort == "low"` 二値解釈が該当)
     *  - 複数レベルが個別比較されている → Graded(levels)
     *    (`==` 比較の列挙、および `in (...)` タプル所属チェックの両方から集める。
     *    後者は prism-ml/Ternary-Bonsai-2-27B-gguf のように
     *    `resolved_reasoning_effort not in ('xhigh', 'medium', 'low')` の形で
     *    未知値を `raise_exception` するテンプレートで使われている)
     *  - 言及はあるが比較パターンを読み取れない → 従来互換の 3 値 Graded
     */
    fun parseReasoningEffortGranularity(template: String): ReasoningEffortGranularity {
        if (template.isBlank()) return ReasoningEffortGranularity.None
        if (!template.contains("reasoning_effort")) return ReasoningEffortGranularity.None
        val equalityLevels = REASONING_EFFORT_COMPARISON_REGEX.findAll(template)
            .map { it.groupValues[1].lowercase() }
        val tupleLevels = REASONING_EFFORT_IN_TUPLE_REGEX.findAll(template)
            .flatMap { match ->
                QUOTED_TOKEN_REGEX.findAll(match.groupValues[1]).map { it.groupValues[1].lowercase() }
            }
        val levels = (equalityLevels + tupleLevels).toSet()
        return when {
            levels.isEmpty() -> ReasoningEffortGranularity.Graded(setOf("low", "medium", "high"))
            levels.size == 1 && "low" in levels -> ReasoningEffortGranularity.Binary
            else -> ReasoningEffortGranularity.Graded(levels)
        }
    }

    /**
     * 粒度に応じた有効エフォートレベル一覧を返す。
     * None → 空リスト (effort 選択肢なし)、Binary → ["default", "low"]。
     *
     * Binary に "default" (未指定) を含めるのは、Thinking ON 時に "low" しか
     * 選べないと、テンプレート本来のフル思考 (デフォルト) に戻す手段が UI から
     * 失われてしまうため。IBM Granite 4.x 系の公式ドキュメントも
     * full thinking (default) / non-thinking / low-effort の 3 モードを
     * 明示しており、UI 側もこれに合わせる。
     * "default" 選択時は applyReasoningEffort が何も挿入しない
     * (= 指定なしと同じ) 前提。
     */
    fun supportedEffortLevels(granularity: ReasoningEffortGranularity): List<String> =
        when (granularity) {
            ReasoningEffortGranularity.None -> emptyList()
            ReasoningEffortGranularity.Binary -> listOf("default", "low")
            is ReasoningEffortGranularity.Graded ->
                KNOWN_EFFORT_ORDER.filter { it in granularity.levels } +
                    granularity.levels.filter { it !in KNOWN_EFFORT_ORDER }.sorted()
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
