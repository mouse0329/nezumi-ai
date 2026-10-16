#pragma once

#include <atomic>
#include <string>
#include <jni.h>

#include "llama.h"
#include "common.h"
#include "mtmd.h"

// ネイティブコンテキストを保持する構造体
struct NezumiLlamaCtx
{
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    common_sampler *sampler = nullptr;
    common_batch batch;

    // マルチモーダル (mtmd)。mmproj 未指定/ロード失敗時は nullptr のまま。
    mtmd_context *mtmd_ctx = nullptr;

    // チャットテンプレート (GGUF 埋め込み)。llamaFree で解放する。
    common_chat_templates *chat_templates = nullptr;

    common_chat_params chat_params;
    bool chat_params_valid = false;
    std::string last_applied_prompt;

    int n_ctx = 0;
    int n_batch = 1024;
    int n_ubatch = 512;
    int n_past = 0;
    int32_t last_prompt_tokens = 0;
    int32_t last_prompt_media_tokens = 0;
    bool context_shift_enabled = true;

    std::string requested_gpu_backend = "CPU";
    std::string actual_gpu_backend = "CPU";
    bool gpu_backend_fallback_occurred = false;

    std::atomic<bool> interrupted{false};
    int repeat_last_n = 64;

    JavaVM *jvm = nullptr;
    jobject token_callback = nullptr;
    jmethodID on_token_mid = nullptr;

    jobject media_progress_callback = nullptr;
    jmethodID on_media_progress_mid = nullptr;

    int32_t seed = -1;
    float cached_temp = -1.0f;
    float cached_top_p = -1.0f;
    int cached_top_k = -1;
    float cached_repeat_penalty = -1.0f;
};

// 生成ループ関連
std::string generate_loop(JNIEnv *env, NezumiLlamaCtx *nc,
                          int n_predict, float temperature, float top_p, int top_k,
                          float repeat_penalty,
                          const std::vector<std::string> &stop_words);

// マルチモーダル関連は llama_mtmd.cpp 側で定義
