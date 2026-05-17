/**
 * llama_bridge.cpp
 *
 * llama.cpp JNI ブリッジ。LlamaBridge.kt の external fun と対応する。
 *
 * ── ビルド設定 (app/CMakeLists.txt) ──────────────────────────────
 *
 * cmake_minimum_required(VERSION 3.22.1)
 * project(nezumi_ai_gguf)
 *
 * # llama.cpp サブモジュール (git submodule add https://github.com/ggerganov/llama.cpp vendor/llama.cpp)
 * set(LLAMA_BUILD_TESTS OFF)
 * set(LLAMA_BUILD_EXAMPLES OFF)
 * add_subdirectory(${CMAKE_SOURCE_DIR}/../vendor/llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)
 *
 * add_library(llama_bridge SHARED llama_bridge.cpp)
 *
 * target_include_directories(llama_bridge PRIVATE
 *     ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp/include
 *     ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp/ggml/include
 * )
 *
 * target_link_libraries(llama_bridge PRIVATE llama ggml android log)
 *
 * ── build.gradle.kts (app) ───────────────────────────────────────
 *
 * android {
 *     defaultConfig {
 *         externalNativeBuild { cmake { arguments("-DANDROID_STL=c++_shared") } }
 *     }
 *     externalNativeBuild {
 *         cmake { path("CMakeLists.txt"); version("3.22.1") }
 *     }
 * }
 *
 * ─────────────────────────────────────────────────────────────────
 *
 * GPU オフロード (Vulkan) を有効にする場合:
 *   cmake argument に -DGGML_VULKAN=ON を追加し、
 *   nGpuLayers に 999 を渡す。
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ネイティブコンテキストを保持する構造体
struct NezumiLlamaCtx {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    llama_sampler* sampler = nullptr;
    int            n_ctx   = 0;
};

// ─── ライフサイクル ───────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaInit(
        JNIEnv* env,
        jobject /* obj */,
        jstring j_model_path,
        jint    n_ctx,
        jint    n_threads,
        jint    n_gpu_layers,
        jint    seed)
{
    llama_backend_init();

    const char* model_path = env->GetStringUTFChars(j_model_path, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    llama_model* model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(j_model_path, model_path);

    if (!model) {
        LOGE("llamaInit: failed to load model");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = static_cast<uint32_t>(n_ctx);
    cparams.n_threads   = static_cast<int32_t>(n_threads);
    cparams.n_threads_batch = static_cast<int32_t>(n_threads);
    cparams.seed        = (seed < 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llamaInit: failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto* nc = new NezumiLlamaCtx();
    nc->model = model;
    nc->ctx   = ctx;
    nc->n_ctx = n_ctx;
    // sampler は llamaSample() の呼び出し時に生成する（パラメータを受け取るため）

    LOGI("llamaInit: OK n_ctx=%d n_gpu_layers=%d", n_ctx, n_gpu_layers);
    return reinterpret_cast<jlong>(nc);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaFree(
        JNIEnv* /* env */,
        jobject /* obj */,
        jlong j_ctx)
{
    if (j_ctx == 0) return;
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);
    if (nc->sampler) { llama_sampler_free(nc->sampler); nc->sampler = nullptr; }
    if (nc->ctx)     { llama_free(nc->ctx);              nc->ctx     = nullptr; }
    if (nc->model)   { llama_model_free(nc->model);      nc->model   = nullptr; }
    delete nc;
    llama_backend_free();
    LOGI("llamaFree: done");
}

// ─── トークナイザ ─────────────────────────────────────────────────

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaTokenize(
        JNIEnv* env,
        jobject /* obj */,
        jlong   j_ctx,
        jstring j_text,
        jboolean add_bos)
{
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);
    const char* text = env->GetStringUTFChars(j_text, nullptr);

    // 最大トークン数を見積もる（文字数 + 余裕）
    int max_tokens = static_cast<int>(strlen(text)) + 64;
    std::vector<llama_token> tokens(max_tokens);

    int n = llama_tokenize(
        llama_model_get_vocab(nc->model),
        text,
        static_cast<int32_t>(strlen(text)),
        tokens.data(),
        max_tokens,
        add_bos,
        /* special */ true
    );
    env->ReleaseStringUTFChars(j_text, text);

    if (n < 0) {
        LOGE("llamaTokenize: failed n=%d", n);
        return nullptr;
    }

    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint*>(tokens.data()));
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaTokenToPiece(
        JNIEnv* env,
        jobject /* obj */,
        jlong j_ctx,
        jint  token)
{
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);
    char buf[256] = {};
    int n = llama_token_to_piece(
        llama_model_get_vocab(nc->model),
        static_cast<llama_token>(token),
        buf, sizeof(buf) - 1,
        /* lstrip */ 0,
        /* special */ false
    );
    if (n < 0) return env->NewStringUTF("");
    buf[n] = '\0';
    return env->NewStringUTF(buf);
}

// ─── KV キャッシュ ────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaClearKvCache(
        JNIEnv* /* env */,
        jobject /* obj */,
        jlong j_ctx)
{
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);
    llama_kv_self_clear(nc->ctx);
    LOGI("llamaClearKvCache: done");
}

// ─── 推論 ─────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaDecode(
        JNIEnv* env,
        jobject /* obj */,
        jlong      j_ctx,
        jintArray  j_tokens)
{
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);
    jsize len = env->GetArrayLength(j_tokens);
    jint* raw = env->GetIntArrayElements(j_tokens, nullptr);

    llama_batch batch = llama_batch_get_one(
        reinterpret_cast<llama_token*>(raw),
        static_cast<int32_t>(len)
    );
    int ret = llama_decode(nc->ctx, batch);
    env->ReleaseIntArrayElements(j_tokens, raw, JNI_ABORT);
    return static_cast<jint>(ret);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaSample(
        JNIEnv* /* env */,
        jobject /* obj */,
        jlong  j_ctx,
        jfloat temperature,
        jfloat top_p,
        jint   top_k,
        jfloat repeat_penalty)
{
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);

    // サンプラーを毎回再構築（パラメータが変わる可能性があるため）
    if (nc->sampler) {
        llama_sampler_free(nc->sampler);
        nc->sampler = nullptr;
    }

    // サンプラーチェーン: repeat_penalty → top_k → top_p → temperature → greedy
    nc->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(nc->sampler,
        llama_sampler_init_penalties(
            /* last_n */ 64,
            /* repeat_penalty */ repeat_penalty,
            /* frequency_penalty */ 0.0f,
            /* presence_penalty */ 0.0f
        )
    );
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_top_p(top_p, /* min_keep */ 1));
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_greedy());

    llama_token token = llama_sampler_sample(nc->sampler, nc->ctx, /* idx */ -1);
    llama_sampler_accept(nc->sampler, token);
    return static_cast<jint>(token);
}

// ─── ユーティリティ ──────────────────────────────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaEosToken(
        JNIEnv* /* env */,
        jobject /* obj */,
        jlong j_ctx)
{
    auto* nc = reinterpret_cast<NezumiLlamaCtx*>(j_ctx);
    return static_cast<jint>(llama_model_eos(nc->model));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaVersion(
        JNIEnv* env,
        jobject /* obj */)
{
    return env->NewStringUTF(llama_print_system_info());
}
