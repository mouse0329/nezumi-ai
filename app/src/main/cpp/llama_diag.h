#pragma once
// 診断ログ (JNI 版と CLI 版で同じ書式を出し、差分比較に使う)。
//
// 有効化:
//   JNI   : LlamaBridge.nativeSetDiagEnabled(true) を llamaInit 前に呼ぶ
//   CLI   : 環境変数 NEZUMI_DIAG=1 を設定して実行する
//
// 出力は全行 "DIAG|" で始まる。logcat では tag "llama_diag" で絞り込む。
//   adb logcat -s llama_diag
// CLI では stderr に出る。
//
// 比較の要点:
//   tokens : 各チャンクのトークン数・ハッシュ・先頭トークン ID
//   embd   : 画像埋め込みの統計 (平均・最大絶対値・NaN/Inf 数)
//   logits : 生成ステップごとの最大ロジット・NaN 数・上位 5 件

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "llama.h"
#include "mtmd.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define NEZUMI_DIAG_OUT(s) __android_log_write(ANDROID_LOG_INFO, "llama_diag", (s))
#else
#define NEZUMI_DIAG_OUT(s) fputs((s), stderr), fputc('\n', stderr)
#endif

namespace nezumi_diag {

inline std::atomic<bool> &flag() {
    static std::atomic<bool> f{false};
    return f;
}

inline bool enabled() { return flag().load(std::memory_order_relaxed); }

// CLI 用: 環境変数 NEZUMI_DIAG=1 で有効化する。JNI 側は nativeSetDiagEnabled を使う。
inline void init_from_env() {
    const char *v = std::getenv("NEZUMI_DIAG");
    flag().store(v != nullptr && v[0] == '1');
}

inline uint64_t fnv1a(const llama_token *t, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; ++i) {
        const uint32_t v = static_cast<uint32_t>(t[i]);
        for (int b = 0; b < 4; ++b) {
            h ^= (v >> (b * 8)) & 0xFF;
            h *= 1099511628211ULL;
        }
    }
    return h;
}

}  // namespace nezumi_diag

// 書式付きの 1 行出力。有効時だけ評価される。
#define NEZUMI_DIAG(fmt, ...)                                              \
    do {                                                                   \
        if (nezumi_diag::enabled()) {                                      \
            char nezumi_diag_buf_[1024];                                   \
            snprintf(nezumi_diag_buf_, sizeof(nezumi_diag_buf_), fmt,      \
                     ##__VA_ARGS__);                                       \
            NEZUMI_DIAG_OUT(nezumi_diag_buf_);                             \
        }                                                                  \
    } while (0)

namespace nezumi_diag {

// DIAG|tokens|<tag>|n=<N>|hash=<hex>|head=<先頭 head 個>
inline void log_tokens(const char *tag, const llama_token *t, size_t n, size_t head = 24) {
    if (!enabled()) return;
    std::string h;
    for (size_t i = 0; i < n && i < head; ++i) {
        if (i) h += ',';
        h += std::to_string(t[i]);
    }
    NEZUMI_DIAG("DIAG|tokens|%s|n=%zu|hash=%016llx|head=%s", tag, n,
                static_cast<unsigned long long>(fnv1a(t, n)), h.c_str());
}

// DIAG|chunk|<tag>|i=<i>|type=<text|image|audio>|n_tok=<N>|n_pos=<P>
inline void log_chunks(const char *tag, const mtmd_input_chunks *chunks) {
    if (!enabled()) return;
    const size_t n = mtmd_input_chunks_size(chunks);
    NEZUMI_DIAG("DIAG|chunks|%s|count=%zu", tag, n);
    for (size_t i = 0; i < n; ++i) {
        const mtmd_input_chunk *c = mtmd_input_chunks_get(chunks, i);
        if (!c) continue;
        const auto type = mtmd_input_chunk_get_type(c);
        const char *tname = type == MTMD_INPUT_CHUNK_TYPE_TEXT   ? "text"
                            : type == MTMD_INPUT_CHUNK_TYPE_IMAGE ? "image"
                                                                   : "audio";
        NEZUMI_DIAG("DIAG|chunk|%s|i=%zu|type=%s|n_tok=%zu|n_pos=%d", tag, i,
                    tname, mtmd_input_chunk_get_n_tokens(c),
                    static_cast<int>(mtmd_input_chunk_get_n_pos(c)));
        if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t nt = 0;
            const llama_token *tok = mtmd_input_chunk_get_tokens_text(c, &nt);
            char t[64];
            snprintf(t, sizeof(t), "%s.%zu", tag, i);
            log_tokens(t, tok, nt);
        }
    }
}

// DIAG|embd|<tag>|n_tok=<N>|n_embd=<D>|mean=..|maxabs=..|nan=..|inf=..
inline void log_embd(const char *tag, const float *e, size_t n_tok, size_t n_embd) {
    if (!enabled() || !e) return;
    const size_t total = n_tok * n_embd;
    double sum = 0.0;
    float maxabs = 0.0f;
    size_t nan_c = 0, inf_c = 0;
    for (size_t i = 0; i < total; ++i) {
        const float v = e[i];
        if (std::isnan(v)) { ++nan_c; continue; }
        if (std::isinf(v)) { ++inf_c; continue; }
        sum += v;
        const float a = std::fabs(v);
        if (a > maxabs) maxabs = a;
    }
    NEZUMI_DIAG("DIAG|embd|%s|n_tok=%zu|n_embd=%zu|mean=%.6f|maxabs=%.4f|nan=%zu|inf=%zu",
                tag, n_tok, n_embd, total ? sum / total : 0.0, maxabs, nan_c, inf_c);
}

// DIAG|logits|<tag>|step=<s>|sampled=<id>|argmax=<id>|max=..|min=..|nan=..|top5=id:logit,...
inline void log_logits(const char *tag, llama_context *ctx, int step, llama_token sampled) {
    if (!enabled()) return;
    const llama_model *model = llama_get_model(ctx);
    const int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(model));
    const float *l = llama_get_logits_ith(ctx, -1);
    if (!l || n_vocab <= 0) {
        NEZUMI_DIAG("DIAG|logits|%s|step=%d|sampled=%d|missing", tag, step, sampled);
        return;
    }
    int argmax = 0;
    float mx = -INFINITY, mn = INFINITY;
    size_t nan_c = 0;
    int top[5] = {-1, -1, -1, -1, -1};
    for (int i = 0; i < n_vocab; ++i) {
        const float v = l[i];
        if (std::isnan(v)) { ++nan_c; continue; }
        if (v > mx) { mx = v; argmax = i; }
        if (v < mn) mn = v;
        // 上位 5 件 (挿入ソート)
        for (int k = 0; k < 5; ++k) {
            if (top[k] < 0 || v > l[top[k]]) {
                for (int j = 4; j > k; --j) top[j] = top[j - 1];
                top[k] = i;
                break;
            }
        }
    }
    std::string t5;
    for (int k = 0; k < 5; ++k) {
        if (top[k] < 0) break;
        if (k) t5 += ',';
        char b[64];
        snprintf(b, sizeof(b), "%d:%.3f", top[k], l[top[k]]);
        t5 += b;
    }
    NEZUMI_DIAG("DIAG|logits|%s|step=%d|sampled=%d|argmax=%d|max=%.3f|min=%.3f|nan=%zu|top5=%s",
                tag, step, sampled, argmax, mx, mn, nan_c, t5.c_str());
}

}  // namespace nezumi_diag
