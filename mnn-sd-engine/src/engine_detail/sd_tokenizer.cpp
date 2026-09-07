#include "engine_detail/sd_tokenizer.h"
#include "engine_detail/sd_log.h"
#include "engine_internal.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

// ============================================================
// BPE Tokenizer (ClipTokenizer)
// ============================================================

namespace
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

    inline void json_skip_ws(const std::string &s, size_t &i)
    {
        while (i < s.size())
        {
            char c = s[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
                ++i;
            else
                break;
        }
    }

    // Read a JSON string literal starting at s[i] == '"'. On success sets
    // i to just past the closing quote and returns the unescaped content.
    bool json_read_string(const std::string &s, size_t &i, std::string &out)
    {
        if (i >= s.size() || s[i] != '"')
            return false;
        ++i;
        out.clear();
        while (i < s.size())
        {
            unsigned char c = (unsigned char)s[i];
            if (c == '"')
            {
                ++i;
                return true;
            }
            if (c == '\\')
            {
                if (i + 1 >= s.size())
                    return false;
                char esc = s[i + 1];
                switch (esc)
                {
                case '"':
                    out += '"';
                    break;
                case '\\':
                    out += '\\';
                    break;
                case '/':
                    out += '/';
                    break;
                case 'b':
                    out += '\b';
                    break;
                case 'f':
                    out += '\f';
                    break;
                case 'n':
                    out += '\n';
                    break;
                case 'r':
                    out += '\r';
                    break;
                case 't':
                    out += '\t';
                    break;
                case 'u':
                {
                    if (i + 5 >= s.size())
                        return false;
                    unsigned int cp = 0;
                    for (int k = 0; k < 4; ++k)
                    {
                        char h = s[i + 2 + k];
                        cp <<= 4;
                        if (h >= '0' && h <= '9')
                            cp |= (h - '0');
                        else if (h >= 'a' && h <= 'f')
                            cp |= (h - 'a' + 10);
                        else if (h >= 'A' && h <= 'F')
                            cp |= (h - 'A' + 10);
                        else
                            return false;
                    }
                    if (cp < 0x80)
                    {
                        out += (char)cp;
                    }
                    else if (cp < 0x800)
                    {
                        out += (char)(0xC0 | (cp >> 6));
                        out += (char)(0x80 | (cp & 0x3F));
                    }
                    else
                    {
                        out += (char)(0xE0 | (cp >> 12));
                        out += (char)(0x80 | ((cp >> 6) & 0x3F));
                        out += (char)(0x80 | (cp & 0x3F));
                    }
                    i += 6;
                    continue;
                }
                default:
                    out += '\\';
                    out += esc;
                    break;
                }
                i += 2;
                continue;
            }
            out += (char)c;
            ++i;
        }
        return false;
    }

    // Scan the value that starts at s[i], leaving i just past it.
    // Handles strings, objects, arrays, numbers, true/false/null.
    void json_skip_value(const std::string &s, size_t &i)
    {
        json_skip_ws(s, i);
        if (i >= s.size())
            return;
        char c = s[i];
        if (c == '"')
        {
            std::string dummy;
            json_read_string(s, i, dummy);
            return;
        }
        if (c == '{' || c == '[')
        {
            char open_c = c;
            char close_c = (c == '{') ? '}' : ']';
            ++i;
            int depth = 1;
            bool in_str = false;
            while (i < s.size() && depth > 0)
            {
                char ch = s[i];
                if (in_str)
                {
                    if (ch == '\\' && i + 1 < s.size())
                    {
                        i += 2;
                        continue;
                    }
                    if (ch == '"')
                        in_str = false;
                    ++i;
                    continue;
                }
                if (ch == '"')
                {
                    in_str = true;
                    ++i;
                    continue;
                }
                if (ch == open_c)
                    ++depth;
                else if (ch == close_c)
                    --depth;
                ++i;
            }
            return;
        }
        // number / bool / null: skip until control char at depth 0
        while (i < s.size())
        {
            char ch = s[i];
            if (ch == ',' || ch == '}' || ch == ']' ||
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')
                break;
            ++i;
        }
    }

    // Find `"key"` inside the object whose opening `{` is at start.
    // On success sets out_after_colon to point just past the ':' (whitespace
    // may follow before the value). Returns false if the key is missing.
    bool json_find_key(const std::string &s, size_t start, const std::string &key, size_t &out_after_colon)
    {
        json_skip_ws(s, start);
        if (start >= s.size() || s[start] != '{')
            return false;
        size_t i = start + 1;
        while (i < s.size())
        {
            json_skip_ws(s, i);
            if (i >= s.size())
                return false;
            if (s[i] == '}')
                return false;
            if (s[i] == ',')
            {
                ++i;
                continue;
            }
            std::string k;
            if (!json_read_string(s, i, k))
                return false;
            json_skip_ws(s, i);
            if (i >= s.size() || s[i] != ':')
                return false;
            ++i;
            json_skip_ws(s, i);
            if (k == key)
            {
                out_after_colon = i;
                return true;
            }
            json_skip_value(s, i);
        }
        return false;
    }

    bool json_read_int(const std::string &s, size_t &i, long &out)
    {
        json_skip_ws(s, i);
        size_t start = i;
        if (i < s.size() && (s[i] == '-' || s[i] == '+'))
            ++i;
        while (i < s.size() && std::isdigit((unsigned char)s[i]))
            ++i;
        if (i == start)
            return false;
        try
        {
            out = std::stol(s.substr(start, i - start));
        }
        catch (...)
        {
            return false;
        }
        return true;
    }

    // Parse `{ "tok": id, ... }` at s[start]==`{`.
    bool clip_parse_vocab_object(const std::string &s, size_t start,
                                 std::unordered_map<std::string, int> &vocab)
    {
        json_skip_ws(s, start);
        if (start >= s.size() || s[start] != '{')
            return false;
        size_t i = start + 1;
        while (i < s.size())
        {
            json_skip_ws(s, i);
            if (i >= s.size())
                return false;
            if (s[i] == '}')
            {
                ++i;
                return true;
            }
            if (s[i] == ',')
            {
                ++i;
                continue;
            }
            std::string tok;
            if (!json_read_string(s, i, tok))
                return false;
            json_skip_ws(s, i);
            if (i >= s.size() || s[i] != ':')
                return false;
            ++i;
            long id = 0;
            if (!json_read_int(s, i, id))
                return false;
            vocab[tok] = (int)id;
        }
        return false;
    }

    // Parse `[ "a b", "c d", ... ]` (legacy string form) OR
    //       `[ ["a", "b"], ... ]` (modern list form) at s[start]==`[`.
    bool clip_parse_merges_array(const std::string &s, size_t start,
                                 std::vector<std::pair<std::string, std::string>> &merges)
    {
        json_skip_ws(s, start);
        if (start >= s.size() || s[start] != '[')
            return false;
        size_t i = start + 1;
        while (i < s.size())
        {
            json_skip_ws(s, i);
            if (i >= s.size())
                return false;
            if (s[i] == ']')
            {
                ++i;
                return true;
            }
            if (s[i] == ',')
            {
                ++i;
                continue;
            }

            if (s[i] == '"')
            {
                std::string entry;
                if (!json_read_string(s, i, entry))
                    return false;
                auto sp = entry.find(' ');
                if (sp != std::string::npos)
                    merges.emplace_back(entry.substr(0, sp), entry.substr(sp + 1));
            }
            else if (s[i] == '[')
            {
                ++i;
                std::string a, b;
                json_skip_ws(s, i);
                if (!json_read_string(s, i, a))
                    return false;
                json_skip_ws(s, i);
                if (i >= s.size() || s[i] != ',')
                    return false;
                ++i;
                json_skip_ws(s, i);
                if (!json_read_string(s, i, b))
                    return false;
                json_skip_ws(s, i);
                if (i >= s.size() || s[i] != ']')
                    return false;
                ++i;
                merges.emplace_back(std::move(a), std::move(b));
            }
            else
            {
                ++i;
            }
        }
        return false;
    }
} // namespace

bool ClipTokenizer::load(const std::string &path)
{
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f)
        return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::string json(sz, '\0');
    std::fread(&json[0], 1, sz, f);
    std::fclose(f);

    size_t p = 0;
    json_skip_ws(json, p);
    if (p >= json.size() || json[p] != '{')
        return false;

    // Descend to "model": { ... }
    size_t model_start = 0;
    if (!json_find_key(json, p, "model", model_start))
        return false;
    json_skip_ws(json, model_start);
    if (model_start >= json.size() || json[model_start] != '{')
        return false;

    // Descend to "model.vocab": { ... }
    size_t vocab_start = 0;
    if (!json_find_key(json, model_start, "vocab", vocab_start))
        return false;
    if (!clip_parse_vocab_object(json, vocab_start, vocab))
        return false;

    // Descend to "model.merges": [ ... ]
    size_t merges_start = 0;
    if (!json_find_key(json, model_start, "merges", merges_start))
        return false;
    if (!clip_parse_merges_array(json, merges_start, merges))
        return false;

    return !vocab.empty() && !merges.empty();
}

// CLIP byte-level unicode mapping (same as GPT-2)
std::string ClipTokenizer::bytes_to_unicode(unsigned char c)
{
    // printable ASCII (33-126) and latin-1 supplement (161-172, 174-255) map to themselves
    if ((c >= 33 && c <= 126) || (c >= 161 && c <= 172) || (c >= 174 && c <= 255))
        return std::string(1, (char)c);
    // remaining 256 bytes map to U+0100..U+013F range encoded as UTF-8
    // offset: 0->256, 1->257, ... but we need the actual unicode codepoint
    // The mapping fills gaps: 0-32, 127-160, 173 -> codepoints 256+
    static const int remap[] = {
        256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271,
        272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287,
        288, // 32 entries for 0-32
        289, // 127
        290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305,
        306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321,
        322, // 160
        323  // 173
    };
    // Build lookup on first call
    static std::unordered_map<int, int> byte_to_cp;
    if (byte_to_cp.empty())
    {
        int n = 0;
        for (int b = 0; b < 256; ++b)
        {
            bool printable = (b >= 33 && b <= 126) || (b >= 161 && b <= 172) || (b >= 174 && b <= 255);
            if (!printable)
                byte_to_cp[b] = 256 + n++;
            else
                byte_to_cp[b] = b;
        }
    }
    int cp = byte_to_cp[c];
    // Encode codepoint as UTF-8
    std::string out;
    if (cp < 0x80)
    {
        out += (char)cp;
    }
    else if (cp < 0x800)
    {
        out += (char)(0xC0 | (cp >> 6));
        out += (char)(0x80 | (cp & 0x3F));
    }
    else
    {
        out += (char)(0xE0 | (cp >> 12));
        out += (char)(0x80 | ((cp >> 6) & 0x3F));
        out += (char)(0x80 | (cp & 0x3F));
    }
    return out;
}

namespace
{
    // Lazy rank table cached per merges vector. The engine loads exactly
    // one tokenizer per process lifetime, so a single cached table is
    // sufficient. Key format is  "a\0b"  (first token, NUL, second token)
    // to avoid needing a custom hash for std::pair<string,string>.
    struct BpeRankTable
    {
        const void *owner = nullptr;
        std::unordered_map<std::string, int> rank;
    };
    inline BpeRankTable &bpe_rank_table_for(const std::vector<std::pair<std::string, std::string>> &merges)
    {
        static BpeRankTable table;
        if (table.owner != (const void *)&merges)
        {
            table.owner = (const void *)&merges;
            table.rank.clear();
            table.rank.reserve(merges.size() * 2);
            for (size_t i = 0; i < merges.size(); ++i)
            {
                std::string key;
                key.reserve(merges[i].first.size() + 1 + merges[i].second.size());
                key.append(merges[i].first);
                key.push_back('\0');
                key.append(merges[i].second);
                table.rank.emplace(std::move(key), (int)i);
            }
        }
        return table;
    }
}

std::string ClipTokenizer::bpe(const std::string &token) const
{
    if (token.empty())
        return token;
    // Split token into UTF-8 characters, append </w> to last
    std::vector<std::string> chars;
    size_t i = 0;
    while (i < token.size())
    {
        unsigned char c = (unsigned char)token[i];
        int len = 1;
        if (c >= 0xF0)
            len = 4;
        else if (c >= 0xE0)
            len = 3;
        else if (c >= 0xC0)
            len = 2;
        chars.push_back(token.substr(i, len));
        i += len;
    }
    if (!chars.empty())
        chars.back() += "</w>";

    // BPE merge loop
    auto &rank_table = bpe_rank_table_for(merges).rank;
    while (chars.size() > 1)
    {
        // Find the highest-priority merge pair (lowest rank number).
        int best_rank = -1;
        size_t best_pos = 0;
        std::string key;
        for (size_t k = 0; k + 1 < chars.size(); ++k)
        {
            key.clear();
            key.reserve(chars[k].size() + 1 + chars[k + 1].size());
            key.append(chars[k]);
            key.push_back('\0');
            key.append(chars[k + 1]);
            auto it = rank_table.find(key);
            if (it == rank_table.end())
                continue;
            int r = it->second;
            if (best_rank < 0 || r < best_rank)
            {
                best_rank = r;
                best_pos = k;
            }
        }
        if (best_rank < 0)
            break;
        chars[best_pos] += chars[best_pos + 1];
        chars.erase(chars.begin() + best_pos + 1);
    }

    std::string result;
    for (size_t k = 0; k < chars.size(); ++k)
    {
        if (k > 0)
            result += ' ';
        result += chars[k];
    }
    return result;
}

namespace
{
    // ---- UTF-8 helpers ----------------------------------------------------
    // Read a single UTF-8 codepoint starting at bytes[pos]. Advances pos.
    // On malformed input, treats each stray byte as its own codepoint.
    static inline uint32_t utf8_next(const std::string &s, size_t &pos)
    {
        if (pos >= s.size())
            return 0;
        unsigned char c = (unsigned char)s[pos];
        uint32_t cp;
        int extra;
        if (c < 0x80)
        {
            cp = c;
            extra = 0;
        }
        else if ((c & 0xE0) == 0xC0)
        {
            cp = c & 0x1F;
            extra = 1;
        }
        else if ((c & 0xF0) == 0xE0)
        {
            cp = c & 0x0F;
            extra = 2;
        }
        else if ((c & 0xF8) == 0xF0)
        {
            cp = c & 0x07;
            extra = 3;
        }
        else
        {
            pos += 1;
            return c;
        }
        if (pos + 1 + extra > s.size())
        {
            pos += 1;
            return c;
        }
        for (int k = 0; k < extra; ++k)
        {
            unsigned char nc = (unsigned char)s[pos + 1 + k];
            if ((nc & 0xC0) != 0x80)
            {
                pos += 1;
                return c;
            }
            cp = (cp << 6) | (nc & 0x3F);
        }
        pos += 1 + extra;
        return cp;
    }

    // ASCII category tables adequate for CLIP (which lowercases + NFC first).
    // For the non-ASCII plane we conservatively call every non-ASCII codepoint
    // a "letter" (matches \p{L} for the great majority of prompts; wrong for
    // stray punctuation but harmless because ByteLevel + BPE will still map
    // known n-grams correctly).
    static inline bool cp_is_space(uint32_t cp)
    {
        return cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0B ||
               cp == 0x0C || cp == 0x0D || cp == 0xA0;
    }
    static inline bool cp_is_letter(uint32_t cp)
    {
        if (cp < 0x80)
            return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || cp == '_';
        // Treat any non-ASCII printable as a letter for splitting purposes.
        return true;
    }
    static inline bool cp_is_digit(uint32_t cp)
    {
        return cp >= '0' && cp <= '9';
    }
    static inline void cp_encode_utf8(uint32_t cp, std::string &out)
    {
        if (cp < 0x80)
        {
            out += (char)cp;
        }
        else if (cp < 0x800)
        {
            out += (char)(0xC0 | (cp >> 6));
            out += (char)(0x80 | (cp & 0x3F));
        }
        else if (cp < 0x10000)
        {
            out += (char)(0xE0 | (cp >> 12));
            out += (char)(0x80 | ((cp >> 6) & 0x3F));
            out += (char)(0x80 | (cp & 0x3F));
        }
        else
        {
            out += (char)(0xF0 | (cp >> 18));
            out += (char)(0x80 | ((cp >> 12) & 0x3F));
            out += (char)(0x80 | ((cp >> 6) & 0x3F));
            out += (char)(0x80 | (cp & 0x3F));
        }
    }

    // Bug fix (プロンプト無視): CLIP のプリトークナイザ (HuggingFace の
    //   Regex(r"'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"))
    // と等価な分割を行う。旧実装は空白のみで分割していたため "a red, cute cat" が
    //   "a"  "red,"  "cute"  "cat"
    // に化け、"red,</w>" が語彙に存在せずに silent-drop されていた。正しくは
    //   "a"  "red"  ","  "cute"  "cat"
    // の 5 断片に割り、それぞれを ByteLevel 経由で BPE にかける。
    static std::vector<std::string> pre_tokenize_clip(const std::string &lower_text)
    {
        std::vector<std::string> pieces;
        const std::string &s = lower_text;
        size_t i = 0;
        while (i < s.size())
        {
            // Skip whitespace runs (regex removes them).
            size_t save = i;
            uint32_t cp = utf8_next(s, i);
            if (cp_is_space(cp))
                continue;
            i = save; // rewind

            // Try apostrophe contractions: 's 't 're 've 'm 'll 'd
            if ((unsigned char)s[i] == '\'' && i + 1 < s.size())
            {
                static const char *contractions[] = {"'s", "'t", "'re", "'ve", "'m", "'ll", "'d"};
                bool matched = false;
                for (const char *c : contractions)
                {
                    size_t L = std::strlen(c);
                    if (i + L <= s.size() && s.compare(i, L, c) == 0)
                    {
                        pieces.emplace_back(c);
                        i += L;
                        matched = true;
                        break;
                    }
                }
                if (matched)
                    continue;
            }

            // Peek codepoint category.
            size_t start = i;
            uint32_t first = utf8_next(s, i);

            if (cp_is_letter(first))
            {
                // Consume run of letters.
                while (i < s.size())
                {
                    size_t sv = i;
                    uint32_t nc = utf8_next(s, i);
                    if (!cp_is_letter(nc))
                    {
                        i = sv;
                        break;
                    }
                }
                pieces.emplace_back(s.substr(start, i - start));
            }
            else if (cp_is_digit(first))
            {
                // Single digit per token (CLIP's [\p{N}] is not +, it splits each digit).
                pieces.emplace_back(s.substr(start, i - start));
            }
            else
            {
                // Run of non-space, non-letter, non-digit.
                while (i < s.size())
                {
                    size_t sv = i;
                    uint32_t nc = utf8_next(s, i);
                    if (cp_is_space(nc) || cp_is_letter(nc) || cp_is_digit(nc))
                    {
                        i = sv;
                        break;
                    }
                }
                pieces.emplace_back(s.substr(start, i - start));
            }
        }
        return pieces;
    }
} // namespace

std::vector<int> ClipTokenizer::encode_single(const std::string &text) const
{
    std::vector<int> ids;
    ids.push_back(BOS_ID);

    // NFC would go here in HuggingFace; skipped because prompts are typically
    // already normalized and MNN's CLIP is trained on lowercased text.
    std::string lower;
    lower.reserve(text.size());
    for (unsigned char c : text)
        lower += (char)std::tolower(c);

    // Bug fix (プロンプト無視): CLIP プリトークナイザに合わせて分割する。
    //   詳細は pre_tokenize_clip() のコメント参照。
    auto pieces = pre_tokenize_clip(lower);

    for (const auto &piece : pieces)
    {
        if (piece.empty())
            continue;

        // Byte-level encode: each raw byte -> unicode escape (CLIP/GPT-2 shared table)
        std::string byte_word;
        byte_word.reserve(piece.size() * 2);
        for (unsigned char c : piece)
            byte_word += bytes_to_unicode(c);

        // BPE merge
        std::string bpe_result = bpe(byte_word);

        // Split on space; look up each sub-token in vocab. If a sub-token is
        // missing, fall back one level: replace it with its per-character
        // ByteLevel sub-tokens (which are guaranteed to exist as individual
        // codepoints in the CLIP vocab). This preserves at least the "shape"
        // of the input instead of silently dropping it.
        std::istringstream bpe_ss(bpe_result);
        std::string sub;
        while (bpe_ss >> sub)
        {
            auto it = vocab.find(sub);
            if (it != vocab.end())
            {
                ids.push_back(it->second);
                continue;
            }
            // Fallback: emit each unicode-encoded byte one by one.
            // sub is a UTF-8 string of ByteLevel-escaped codepoints; iterate
            // codepoints and look them up individually.
            size_t p = 0;
            while (p < sub.size())
            {
                size_t before = p;
                uint32_t cp = utf8_next(sub, p);
                std::string one;
                cp_encode_utf8(cp, one);
                auto it2 = vocab.find(one);
                if (it2 != vocab.end())
                    ids.push_back(it2->second);
                // If even the single codepoint is missing, we accept the loss
                // (should not happen for CLIP vocab which contains all 256
                // ByteLevel codepoints). before is unused; kept to make the
                // step semantics obvious to future readers.
                (void)before;
            }
        }
    }

    ids.push_back(EOS_ID);
    // Pad (with EOS_ID, matching xororz/local-dream) or truncate to MAX_LEN
    if ((int)ids.size() > MAX_LEN)
        ids.resize(MAX_LEN);
    while ((int)ids.size() < MAX_LEN)
        ids.push_back(EOS_ID);

    // Diagnostic: log the first few resolved token ids for the initial few
    // prompts of the session so a logcat trace can immediately reveal whether
    // BPE ended up at plausible (>1000, not just <=256) vocab positions.
#if defined(MNN_SD_HAS_MNN)
    {
        static int diag_count = 0;
        if (diag_count < 4)
        {
            ++diag_count;
            char buf[512];
            int off = std::snprintf(buf, sizeof(buf),
                                    "encode_single: text=\"%.60s\" ids[0..15]=",
                                    text.c_str());
            for (int k = 0; k < 16 && k < (int)ids.size() && off < (int)sizeof(buf) - 12; ++k)
                off += std::snprintf(buf + off, sizeof(buf) - off, "%d ", ids[k]);
            PROBE_LOG("%s", buf);
        }
    }
#endif

    return ids;
}

std::vector<int> ClipTokenizer::encode_pair(const std::string &prompt,
                                            const std::string &negative_prompt) const
{
    auto uncond = encode_single(negative_prompt.empty() ? "" : negative_prompt);
    auto cond = encode_single(prompt);
    std::vector<int> out;
    out.insert(out.end(), uncond.begin(), uncond.end());
    out.insert(out.end(), cond.begin(), cond.end());
    return out; // size = 2 * MAX_LEN
}

