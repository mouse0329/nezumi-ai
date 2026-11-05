/**
 * nezumi_llama_cabi.h
 *
 * Android JNI (llama_bridge.cpp) と iOS Objective-C++ ブリッジが共有する
 * 安定 C-ABI。コアの llama.cpp 呼び出しはこの層の背後に隠す。
 *
 * 方針:
 * - 引数は POD / const char* のみ。C++ 例外や JNI 型を公開しない。
 * - コンテキストは不透明ポインタ (void*)。
 * - 文字列の所有権は呼び出し側。戻り値の char* は nezumi_llama_free_string で解放する。
 * - CPU 推論を最初の完成基準とする。Metal は後段。
 */

#ifndef NEZUMI_LLAMA_CABI_H
#define NEZUMI_LLAMA_CABI_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 不透明な llama コンテキスト。 */
typedef void *NezumiLlamaCtx;

/** トークンストリーミングコールバック。user_data は init 時に渡した値。 */
typedef void (*NezumiLlamaTokenCb)(const char *token, void *user_data);

typedef struct NezumiLlamaInitParams {
    const char *model_path;
    int32_t n_ctx;
    int32_t n_batch;
    int32_t n_ubatch;
    int32_t n_threads;
    int32_t n_threads_batch;
    int32_t n_gpu_layers;          /* 0 = CPU only (Phase 3 の完成基準) */
    int32_t use_mmap;
    int32_t use_mlock;
    float rope_freq_base;
    float rope_freq_scale;
    const char *mmproj_path;       /* NULL でテキストのみ */
    int32_t flash_attention;
    int32_t context_shift;
    int32_t kv_unified;
    int32_t seed;                  /* -1 でランダム */
    int32_t repeat_last_n;
    int32_t offload_kqv;
    const char *cache_type_k;      /* "f16" 等 */
    const char *cache_type_v;
    const char *gpu_backend;       /* "CPU" / "METAL" / "OPENCL" / "VULKAN" */
    int32_t image_max_tokens;
    int32_t projector_use_gpu;
} NezumiLlamaInitParams;

typedef struct NezumiLlamaCompleteParams {
    const char *prompt;
    int32_t max_tokens;
    float temperature;
    float top_p;
    int32_t top_k;
    const char *stop_words;        /* 改行区切り。NULL 可 */
    NezumiLlamaTokenCb on_token;
    void *user_data;
} NezumiLlamaCompleteParams;

/** ライブラリ初期化。複数回呼んでも安全。 */
void nezumi_llama_backend_init(void);

/** モデルをロード。失敗時は NULL。詳細は nezumi_llama_last_error()。 */
NezumiLlamaCtx nezumi_llama_init(const NezumiLlamaInitParams *params);

void nezumi_llama_free(NezumiLlamaCtx ctx);

/** 直近のロード/推論エラー文字列。所有権はライブラリ側（次の呼び出しまで有効）。 */
const char *nezumi_llama_last_error(void);

/** 高レベル補完。成功で 0、中断で 1、失敗で負値。 */
int32_t nezumi_llama_complete(NezumiLlamaCtx ctx, const NezumiLlamaCompleteParams *params);

void nezumi_llama_interrupt(NezumiLlamaCtx ctx);
void nezumi_llama_clear_interrupt(NezumiLlamaCtx ctx);

/** チャットテンプレート適用。戻り値は nezumi_llama_free_string で解放。 */
char *nezumi_llama_apply_chat_template(
    NezumiLlamaCtx ctx,
    const char *messages_json,
    int32_t add_generation_prompt
);

void nezumi_llama_free_string(char *s);

/** コンテキスト使用量（n_past 相当）。未対応時は -1。 */
int32_t nezumi_llama_get_past_token_count(NezumiLlamaCtx ctx);

#ifdef __cplusplus
}
#endif

#endif /* NEZUMI_LLAMA_CABI_H */
