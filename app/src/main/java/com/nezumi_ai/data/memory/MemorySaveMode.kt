package com.nezumi_ai.data.memory

/**
 * メモリ保存モード。
 *
 * - [LLM]: 会話後に LLM で事実を自動抽出して保存する。
 * - [RULE_BASED]: キーワードマッチのルールベースで抽出して保存する。
 * - [TOOL_ONLY]: 自動抽出を一切行わず、LLM が明示的に `save_memory` ツールを
 *   呼んだときにのみ保存する。v2.1 以降のデフォルト。
 */
enum class MemorySaveMode {
    LLM,
    RULE_BASED,
    TOOL_ONLY
}
