#pragma once

// Structural JSON scanners + BPE tokenizer helpers.
//
// 旧 mnn_session.cpp の BPE Tokenizer セクションにあった JSON スキャナ
// (json_skip_ws / json_read_string / json_skip_value / json_find_key /
//  json_read_int / clip_parse_vocab_object / clip_parse_merges_array)、
// UTF-8 ヘルパ (utf8_next / cp_is_* / cp_encode_utf8 / pre_tokenize_clip)、
// および BPE ランクテーブル (BpeRankTable / bpe_rank_table_for) を
// ClipTokenizer::load/bpe/encode_single と一緒にここへ集約した。

#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace mnn_sd_detail
{

    // ---------------------------------------------------------------------
    // Structural JSON scanners for tokenizer.json.
    //
    // These are NOT a general-purpose JSON parser; they only handle the
    // subset produced by HuggingFace's `tokenizers` library for a BPE
    // model (an object with a "model" sub-object containing a "vocab"
    // object and a "merges" array of strings).
    //
    // Previous version of this file used std::string::find('"') to iterate
    // through the JSON text without tracking structural context. That
    // walked over the inter-entry whitespace/comma as if it were part of
    // the next entry, silently losing ~40% of the merges table on the
    // real CLIP tokenizer.json. Concretely, in
    //     "merges": [
    //       "i n",
    //       "t h",
    //       ...
    // the naive scan produced garbage entries like  (",\n", "     ")
    // and pushed real merges past their true rank, so lookups such as
    // ("h","ello</w>") returned "not found" and BPE stopped at 'h'+'ello</w>'
    // (IDs 71 + 2512) instead of merging to 'hello</w>' (ID 3306) --
    // hence the "prompt is being ignored" symptom.
    //
    // The new scanner walks the token stream while respecting JSON syntax:
    // after a value in an array or object, the next meaningful character
    // is either ',' or ']'/'}' (whitespace is skipped). That is enough to
    // separate entries reliably without a full JSON grammar.

    void json_skip_ws(const std::string &s, size_t &i);
    bool json_read_string(const std::string &s, size_t &i, std::string &out);
    void json_skip_value(const std::string &s, size_t &i);
    bool json_find_key(const std::string &s, size_t start, const std::string &key, size_t &out_after_colon);
    bool json_read_int(const std::string &s, size_t &i, long &out);
    bool clip_parse_vocab_object(const std::string &s, size_t start,
                                 std::unordered_map<std::string, int> &vocab);
    bool clip_parse_merges_array(const std::string &s, size_t start,
                                 std::vector<std::pair<std::string, std::string>> &merges);

    // ---- UTF-8 helpers ----------------------------------------------------
    uint32_t utf8_next(const std::string &s, size_t &pos);
    bool cp_is_space(uint32_t cp);
    bool cp_is_letter(uint32_t cp);
    bool cp_is_digit(uint32_t cp);
    void cp_encode_utf8(uint32_t cp, std::string &out);

    // Bug fix (プロンプト無視): CLIP のプリトークナイザ (HuggingFace の
    //   Regex(r"'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"))
    // と等価な分割を行う。旧実装は空白のみで分割していたため "a red, cute cat" が
    //   "a"  "red,"  "cute"  "cat"
    // に化け、"red,</w>" が語彙に存在せずに silent-drop されていた。正しくは
    //   "a"  "red"  ","  "cute"  "cat"
    // の 5 断片に割り、それぞれを ByteLevel 経由で BPE にかける。
    std::vector<std::string> pre_tokenize_clip(const std::string &lower_text);

    // Lazy rank table cached per merges vector. The engine loads exactly
    // one tokenizer per process lifetime, so a single cached table is
    // sufficient. Key format is  "a\0b"  (first token, NUL, second token)
    // to avoid needing a custom hash for std::pair<string,string>.
    struct BpeRankTable
    {
        const void *owner = nullptr;
        std::unordered_map<std::string, int> rank;
    };
    BpeRankTable &bpe_rank_table_for(const std::vector<std::pair<std::string, std::string>> &merges);

} // namespace mnn_sd_detail
