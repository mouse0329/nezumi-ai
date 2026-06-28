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
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ネイティブコンテキストを保持する構造体
struct NezumiLlamaCtx
{
    llama_model *model = nullptr;
    llama_model *mmproj_model = nullptr; // マルチモーダルプロジェクションモデル
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    llama_batch batch = {};
    int n_ctx = 0;
    int n_batch = 512;
    int n_past = 0; // KVキャッシュに書き込み済みのトークン数（位置オフセット）
    // サンプラーパラメータキャッシュ
    float cached_temp = -1.0f;
    float cached_top_p = -1.0f;
    int cached_top_k = -1;
    float cached_repeat_penalty = -1.0f;
};

// ─── ライフサイクル ───────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaInit(
    JNIEnv *env,
    jobject /* obj */,
    jstring j_model_path,
    jint n_ctx,
    jint n_threads,
    jint n_gpu_layers,
    jint seed,
    jstring j_mmproj_path)
{
    llama_backend_init();

    const char *model_path = env->GetStringUTFChars(j_model_path, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    llama_model *model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(j_model_path, model_path);

    if (!model)
    {
        LOGE("llamaInit: failed to load model");
        return 0L;
    }

    // mmprojモデルのロード（マルチモーダル対応）
    llama_model *mmproj_model = nullptr;
    if (j_mmproj_path != nullptr)
    {
        const char *mmproj_path = env->GetStringUTFChars(j_mmproj_path, nullptr);
        llama_model_params mmproj_params = llama_model_default_params();
        mmproj_params.n_gpu_layers = n_gpu_layers;
        mmproj_model = llama_model_load_from_file(mmproj_path, mmproj_params);
        env->ReleaseStringUTFChars(j_mmproj_path, mmproj_path);

        if (!mmproj_model)
        {
            LOGW("llamaInit: failed to load mmproj model, continuing with text-only mode");
        }
        else
        {
            LOGI("llamaInit: mmproj model loaded successfully");
        }
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_threads = static_cast<int32_t>(n_threads);
    cparams.n_threads_batch = static_cast<int32_t>(n_threads);
    // Note: some versions of llama_context_params do not expose a seed member.
    // Seed handling is optional; keep deterministic behavior by leaving default.

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx)
    {
        LOGE("llamaInit: failed to create context");
        llama_model_free(model);
        if (mmproj_model) llama_model_free(mmproj_model);
        return 0L;
    }

    auto *nc = new NezumiLlamaCtx();
    nc->model = model;
    nc->mmproj_model = mmproj_model;
    nc->ctx = ctx;
    nc->n_ctx = n_ctx;
    nc->n_batch = (n_ctx > 2048) ? 512 : 256;        // コンテキストサイズに応じて調整
    nc->batch = llama_batch_init(nc->n_batch, 0, 1); // バッチを事前確保
    // sampler は llamaSample() の呼び出し時に生成する（パラメータを受け取るため）

    LOGI("llamaInit: OK n_ctx=%d n_gpu_layers=%d n_batch=%d mmproj=%s", n_ctx, n_gpu_layers, nc->n_batch, mmproj_model ? "loaded" : "none");
    return reinterpret_cast<jlong>(nc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaFree(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    if (j_ctx == 0)
        return;
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (nc->sampler)
    {
        llama_sampler_free(nc->sampler);
        nc->sampler = nullptr;
    }
    llama_batch_free(nc->batch); // バッチを解放
    if (nc->ctx)
    {
        llama_free(nc->ctx);
        nc->ctx = nullptr;
    }
    if (nc->mmproj_model)
    {
        llama_model_free(nc->mmproj_model);
        nc->mmproj_model = nullptr;
    }
    if (nc->model)
    {
        llama_model_free(nc->model);
        nc->model = nullptr;
    }
    delete nc;
    llama_backend_free();
    LOGI("llamaFree: done");
}

// ─── トークナイザ ─────────────────────────────────────────────────

extern "C" JNIEXPORT jintArray JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaTokenize(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jstring j_text,
    jboolean add_bos)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    const char *text = env->GetStringUTFChars(j_text, nullptr);
    size_t text_len = strlen(text);

    // 最大トークン数を見積もる（文字数 * 1.5 + 余裕）- より正確な見積もり
    int max_tokens = static_cast<int>(text_len * 1.5f) + 128;
    std::vector<llama_token> tokens(max_tokens);

    int n = llama_tokenize(
        llama_model_get_vocab(nc->model),
        text,
        static_cast<int32_t>(text_len),
        tokens.data(),
        max_tokens,
        add_bos,
        /* special */ true);
    env->ReleaseStringUTFChars(j_text, text);

    if (n < 0)
    {
        LOGE("llamaTokenize: failed n=%d", n);
        return nullptr;
    }

    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaTokenToPiece(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jint token)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    char buf[256] = {};
    int n = llama_token_to_piece(
        llama_model_get_vocab(nc->model),
        static_cast<llama_token>(token),
        buf, sizeof(buf) - 1,
        /* lstrip */ 0,
        /* special */ false);
    if (n <= 0)
        return env->NewStringUTF("");

    // NewStringUTF は Modified UTF-8 のみ受け付けるため、
    // 4バイト UTF-8（絵文字等）を含む場合は NewString (UTF-16) で渡す
    // UTF-8 → UTF-16 変換
    std::vector<jchar> utf16;
    int i = 0;
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(buf[i]);
        uint32_t cp = 0;
        int bytes = 0;
        if (c < 0x80) {
            cp = c; bytes = 1;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F; bytes = 2;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F; bytes = 3;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07; bytes = 4;
        } else {
            i++; continue; // 不正バイトはスキップ
        }
        for (int b = 1; b < bytes && (i + b) < n; b++) {
            cp = (cp << 6) | (static_cast<unsigned char>(buf[i + b]) & 0x3F);
        }
        i += bytes;
        if (cp < 0x10000) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            // サロゲートペア
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// ─── KV キャッシュ ────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaClearKvCache(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->ctx)
        return;
    llama_memory_clear(llama_get_memory(nc->ctx), true);
    nc->n_past = 0; // 位置カウンタもリセット
    LOGI("llamaClearKvCache: done (n_past reset to 0)");
}

// ─── 推論 ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaDecode(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jintArray j_tokens)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    jsize len = env->GetArrayLength(j_tokens);
    jint *raw = env->GetIntArrayElements(j_tokens, nullptr);

    // 事前確保したバッチを再利用してトークン配列を埋める
    if (len > nc->n_batch)
    {
        // エラー: 事前確保したバッファより大きい
        LOGE("llamaDecode: token batch size %d exceeds capacity %d", len, nc->n_batch);
        env->ReleaseIntArrayElements(j_tokens, raw, JNI_ABORT);
        return static_cast<jint>(-1);
    }

    nc->batch.n_tokens = static_cast<int32_t>(len);
    for (int i = 0; i < len; ++i)
    {
        nc->batch.token[i] = static_cast<llama_token>(raw[i]);
        if (nc->batch.pos)
            nc->batch.pos[i] = nc->n_past + i; // ★ KVキャッシュの現在位置から続ける
        if (nc->batch.n_seq_id)
            nc->batch.n_seq_id[i] = 1;
        if (nc->batch.seq_id && nc->batch.seq_id[i])
            nc->batch.seq_id[i][0] = 0;
        if (nc->batch.logits)
            nc->batch.logits[i] = 0;
    }
    if (nc->batch.logits && nc->batch.n_tokens > 0)
        nc->batch.logits[nc->batch.n_tokens - 1] = 1; // 最後のトークンのみlogitsを計算

    int ret = llama_decode(nc->ctx, nc->batch);
    env->ReleaseIntArrayElements(j_tokens, raw, JNI_ABORT);

    if (ret == 0)
        nc->n_past += static_cast<int>(len); // ★ 成功時のみ位置を進める

    return static_cast<jint>(ret);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaGetBatchCapacity(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    return static_cast<jint>(nc ? nc->n_batch : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaSample(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);

    // パラメータが変更された場合のみサンプラーを再構築
    bool params_changed = (nc->cached_temp != temperature ||
                           nc->cached_top_p != top_p ||
                           nc->cached_top_k != top_k ||
                           nc->cached_repeat_penalty != repeat_penalty);

    if (params_changed || !nc->sampler)
    {
        if (nc->sampler)
        {
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
                                    /* presence_penalty */ 0.0f));
        llama_sampler_chain_add(nc->sampler, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(nc->sampler, llama_sampler_init_top_p(top_p, /* min_keep */ 1));
        llama_sampler_chain_add(nc->sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(nc->sampler, llama_sampler_init_greedy());

        // キャッシュを更新
        nc->cached_temp = temperature;
        nc->cached_top_p = top_p;
        nc->cached_top_k = top_k;
        nc->cached_repeat_penalty = repeat_penalty;
    }

    llama_token token = llama_sampler_sample(nc->sampler, nc->ctx, /* idx */ -1);
    llama_sampler_accept(nc->sampler, token);
    return static_cast<jint>(token);
}

// ─── ユーティリティ ──────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaEosToken(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->model)
        return static_cast<jint>(-1);
    const llama_vocab *vocab = llama_model_get_vocab(nc->model);
    return static_cast<jint>(llama_vocab_eos(vocab));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaVersion(
    JNIEnv *env,
    jobject /* obj */)
{
    return env->NewStringUTF(llama_print_system_info());
}
