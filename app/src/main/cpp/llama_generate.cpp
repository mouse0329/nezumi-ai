#include "llama_context.h"

#include "llama_diag.h"
#include "common/common.h"
#include "common/sampling.h"

#include <android/log.h>
#include <vector>
#include <string>

#define LOG_TAG "llama_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr float CONTEXT_SHIFT_DISCARD_RATIO = 0.5f;
static constexpr size_t STOP_WORD_BUFFER_MAX = 1024;

// UTF-8 helpers (duplicated for now to avoid larger refactor)
static size_t utf8_complete_size(const char *buf, size_t n)
{
    if (n == 0) return 0;
    // simplified: assume complete for this extraction
    return n;
}

static jstring utf8_to_jstring(JNIEnv *env, const std::string &s)
{
    return env->NewStringUTF(s.c_str());
}

static bool decode_single_token(NezumiLlamaCtx *nc, llama_token token)
{
    nc->batch.clear();
    nc->batch.add(token, nc->n_past++, 0, true);
    const int process_ret = llama_process(nc->ctx, LLAMA_PROCESS_TYPE_DECODE, nc->batch.get());
    if (process_ret != 0)
    {
        NEZUMI_DIAG("DIAG|decode|fail|token=%d|n_past=%d|ret=%d", token, nc->n_past, process_ret);
        return false;
    }
    llama_memory_t mem = llama_get_memory(nc->ctx);
    const llama_pos pos_max = llama_memory_seq_pos_max(mem, 0);
    NEZUMI_DIAG("DIAG|decode|ok|token=%d|n_past=%d|pos_max=%d|ret=%d", token, nc->n_past, (int)pos_max, process_ret);
    return true;
}

static void context_shift(NezumiLlamaCtx *nc)
{
    // simplified placeholder - full implementation remains in original for now
    LOGI("context_shift called");
}

static void rebuild_sampler(NezumiLlamaCtx *nc, float temperature, float top_p, int top_k, float repeat_penalty)
{
    // placeholder
}

static std::string token_to_piece(NezumiLlamaCtx *nc, llama_token token)
{
    return common_token_to_piece(nc->ctx, token, false);
}

std::string generate_loop(JNIEnv *env, NezumiLlamaCtx *nc,
                          int n_predict, float temperature, float top_p, int top_k,
                          float repeat_penalty,
                          const std::vector<std::string> &stop_words)
{
    // NOTE: This is a skeleton. Full implementation should be moved here.
    // For the patch, we keep the original in llama_bridge.cpp and declare the function.
    LOGE("generate_loop: split skeleton - implement full move");
    return "";
}
