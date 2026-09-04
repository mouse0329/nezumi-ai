/**
 * llama_bridge.cpp
 *
 * 本家 llama.cpp (app/src/main/vendor/llama.cpp, git submodule) 直結の JNI ブリッジ。
 * LlamaBridge.kt の external fun と対応する。
 *
 * rn-llama 中間層 (llama_rn/) を経由せず、llama.h / common/chat.h / mtmd.h を直接叩く。
 * 実装方針・引数構成は旧方式 (RnLlamaNative / NezumiRnLlamaJni.cpp) に揃えてあり、
 * フェーズ10で GgufInferenceEngine からの切り替え時に差分が最小になるようにしている。
 *
 * 提供機能:
 *   - ライフサイクル (llamaInit / llamaFree)
 *     n_batch/n_ubatch/mmap/mlock/rope/flash_attn/context_shift 対応
 *   - トークナイザ (llamaTokenize / llamaTokenToPiece)
 *   - KV キャッシュ (llamaClearKvCache)
 *   - 低レベル推論プリミティブ (llamaDecode / llamaSample)
 *   - トークンストリーミングコールバック (nativeSetTokenCallback)
 *   - 高レベル補完 (nativeComplete / nativeCompleteWithMedia / nativeInterrupt / nativeClearInterrupt)
 *   - チャットテンプレート (nativeApplyGgufChatTemplate / nativeApplyJinjaChatTemplate /
 *     nativeHasGgufChatTemplate / nativeParseGgufChatOutput)
 *   - マルチモーダル (mtmd): nativeIsVisionSupported / nativeIsAudioSupported / nativeGetAudioSampleRate
 *   - タイミング統計 (nativeGetLastTimings)
 *   - GPU バックエンド選択 (CPU / OpenCL / Vulkan)
 */

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "json.hpp"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "llama_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// コンテキスト溢れ時のシフト量（llama.cpp 系ツールの慣例: 半分捨てる）
static constexpr float CONTEXT_SHIFT_DISCARD_RATIO = 0.5f;
// stop word 判定用に保持する生成テキストの最大長
static constexpr size_t STOP_WORD_BUFFER_MAX = 1024;
static std::once_flag g_backend_init_once;

static bool nezumi_iequals(const char *a, const char *b)
{
    if (!a || !b)
        return false;
    while (*a && *b)
    {
        if (std::tolower(static_cast<unsigned char>(*a)) !=
            std::tolower(static_cast<unsigned char>(*b)))
            return false;
        ++a;
        ++b;
    }
    return *a == *b;
}

static void nezumi_prepare_opencl_icd_paths()
{
    if (getenv("OCL_ICD_VENDORS") != nullptr)
        return;
    setenv("OCL_ICD_VENDORS",
           "/vendor/etc/OpenCL/vendors:/system/etc/OpenCL/vendors:/etc/OpenCL/vendors",
           0);
}

static const char *nezumi_backend_reg_want(const char *gpu_backend)
{
    if (gpu_backend == nullptr || gpu_backend[0] == '\0' || nezumi_iequals(gpu_backend, "CPU"))
        return nullptr;
    if (nezumi_iequals(gpu_backend, "OPENCL") || nezumi_iequals(gpu_backend, "CL"))
        return "OpenCL";
    if (nezumi_iequals(gpu_backend, "VULKAN") || nezumi_iequals(gpu_backend, "VK"))
        return "Vulkan";
    return nullptr;
}

// gpu_backend: 要求されたバックエンド文字列 ("CPU" / "OPENCL" / "VULKAN")。
// n_gpu_layers: 失敗時に 0 へ書き換えられる（in/out）。
// fallback_occurred: 要求バックエンドが見つからずCPUにフォールバックした場合 true（out）。
static std::vector<ggml_backend_dev_t> nezumi_collect_devices(const char *gpu_backend, int *n_gpu_layers, bool *fallback_occurred)
{
    std::vector<ggml_backend_dev_t> selected;
    if (fallback_occurred)
        *fallback_occurred = false;

    const char *want = nezumi_backend_reg_want(gpu_backend);
    if (want == nullptr)
    {
        // 最初から CPU が要求された場合はフォールバックではない。
        if (n_gpu_layers)
            *n_gpu_layers = 0;
        selected.push_back(nullptr);
        return selected;
    }

    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i)
    {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
        const char *reg_name = ggml_backend_reg_name(reg);
        if (reg_name && nezumi_iequals(reg_name, want))
        {
            selected.push_back(dev);
            LOGI("llamaInit: selected device %s (%s)", ggml_backend_dev_name(dev), reg_name);
        }
    }

    if (selected.empty())
    {
        LOGW("llamaInit: requested backend %s is not available; falling back to CPU", want);
        if (n_gpu_layers)
            *n_gpu_layers = 0;
        if (fallback_occurred)
            *fallback_occurred = true;
    }
    selected.push_back(nullptr);
    return selected;
}

// ネイティブコンテキストを保持する構造体
struct NezumiLlamaCtx
{
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    llama_batch batch = {};

    // マルチモーダル (mtmd)。mmproj 未指定/ロード失敗時は nullptr のまま。
    mtmd_context *mtmd_ctx = nullptr;

    // チャットテンプレート (GGUF 埋め込み)。llamaFree で解放する。
    common_chat_templates *chat_templates = nullptr;

    // 直近の common_chat_templates_apply() 結果。旧 rnllama 方式
    // (NezumiRnLlamaJni.cpp の gguf_chat_params) と同様にキャッシュし、
    // nativeParseGgufChatOutput でテンプレート対応パーサーによる
    // content / reasoning_content 分離に再利用する。
    common_chat_params chat_params;
    bool chat_params_valid = false;

    int n_ctx = 0;
    int n_batch = 512;
    int n_ubatch = 512;
    int n_past = 0; // KVキャッシュに書き込み済みのトークン数（位置オフセット）
    bool context_shift_enabled = true;

    // 要求バックエンドと実際に使われたバックエンド（フォールバック検知用）。
    std::string requested_gpu_backend = "CPU";
    std::string actual_gpu_backend = "CPU";
    bool gpu_backend_fallback_occurred = false;

    // 生成中断フラグ（nativeInterrupt / nativeClearInterrupt）
    std::atomic<bool> interrupted{false};

    // トークンストリーミングコールバック
    JavaVM *jvm = nullptr;
    jobject token_callback = nullptr; // GlobalRef
    jmethodID on_token_mid = nullptr;

    // サンプラーパラメータキャッシュ
    int32_t seed = -1;
    float cached_temp = -1.0f;
    float cached_top_p = -1.0f;
    int cached_top_k = -1;
    float cached_repeat_penalty = -1.0f;
};

// ─── 内部ヘルパー ─────────────────────────────────────────────────

// mtmd / ggml のログを logcat に流す
static void nezumi_ggml_log_callback(ggml_log_level level, const char *text, void * /* user_data */)
{
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_WARN)
        prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_ERROR)
        prio = ANDROID_LOG_ERROR;
    __android_log_write(prio, LOG_TAG, text);
}

// UTF-8 → jstring (NewStringUTF は Modified UTF-8 のみ対応のため UTF-16 経由にする)
static jstring utf8_to_jstring(JNIEnv *env, const char *buf, size_t n)
{
    std::vector<jchar> utf16;
    size_t i = 0;
    while (i < n)
    {
        unsigned char c = static_cast<unsigned char>(buf[i]);
        uint32_t cp = 0;
        int bytes = 0;
        if (c < 0x80)
        {
            cp = c;
            bytes = 1;
        }
        else if ((c & 0xE0) == 0xC0)
        {
            cp = c & 0x1F;
            bytes = 2;
        }
        else if ((c & 0xF0) == 0xE0)
        {
            cp = c & 0x0F;
            bytes = 3;
        }
        else if ((c & 0xF8) == 0xF0)
        {
            cp = c & 0x07;
            bytes = 4;
        }
        else
        {
            i++;
            continue;
        } // 不正バイトはスキップ
        bool valid = true;
        for (int b = 1; b < bytes; b++)
        {
            if (i + b >= n)
            {
                valid = false;
                break;
            }
            cp = (cp << 6) | (static_cast<unsigned char>(buf[i + b]) & 0x3F);
        }
        if (!valid)
            break;
        i += bytes;
        if (cp < 0x10000)
        {
            utf16.push_back(static_cast<jchar>(cp));
        }
        else
        {
            // サロゲートペア
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

static jstring utf8_to_jstring(JNIEnv *env, const std::string &s)
{
    return utf8_to_jstring(env, s.data(), s.size());
}

// 1トークンを KV キャッシュ末尾にデコードする。成功時 true。
static bool decode_single_token(NezumiLlamaCtx *nc, llama_token token)
{
    nc->batch.n_tokens = 1;
    nc->batch.token[0] = token;
    if (nc->batch.pos)
        nc->batch.pos[0] = nc->n_past;
    if (nc->batch.n_seq_id)
        nc->batch.n_seq_id[0] = 1;
    if (nc->batch.seq_id && nc->batch.seq_id[0])
        nc->batch.seq_id[0][0] = 0;
    if (nc->batch.logits)
        nc->batch.logits[0] = 1;

    if (llama_decode(nc->ctx, nc->batch) != 0)
        return false;
    nc->n_past += 1;
    return true;
}

// コンテキストシフト: 先頭側 (n_keep=0) 以外の半分を捨てて位置を詰める。
// 旧方式 (rn-completion.cpp) の context shift と同等の動作。
static void context_shift(NezumiLlamaCtx *nc)
{
    llama_memory_t mem = llama_get_memory(nc->ctx);
    if (!llama_memory_can_shift(mem))
    {
        LOGW("context_shift: memory does not support shift, clearing KV cache instead");
        llama_memory_clear(mem, false);
        nc->n_past = 0;
        return;
    }
    const int n_keep = 0; // 先頭保護なし（システムプロンプト保持が必要になったら引数化する）
    const int n_discard = (nc->n_past - n_keep) > 0
                              ? static_cast<int>((nc->n_past - n_keep) * CONTEXT_SHIFT_DISCARD_RATIO)
                              : 0;
    if (n_discard <= 0)
        return;

    llama_memory_seq_rm(mem, 0, n_keep, n_keep + n_discard);
    llama_memory_seq_add(mem, 0, n_keep + n_discard, nc->n_past, -n_discard);
    nc->n_past -= n_discard;
    LOGI("context_shift: discarded %d tokens, n_past=%d", n_discard, nc->n_past);
}

// サンプラーを（再）構築する
static void rebuild_sampler(NezumiLlamaCtx *nc, float temperature, float top_p, int top_k, float repeat_penalty)
{
    if (nc->sampler)
    {
        llama_sampler_free(nc->sampler);
        nc->sampler = nullptr;
    }

    // サンプラーチェーン: repeat_penalty → top_k → top_p → temperature → dist/greedy
    nc->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(nc->sampler,
                            llama_sampler_init_penalties(
                                /* n_vocab */ llama_vocab_n_tokens(llama_model_get_vocab(nc->model)),
                                /* last_n */ 64,
                                /* repeat_penalty */ repeat_penalty,
                                /* frequency_penalty */ 0.0f,
                                /* presence_penalty */ 0.0f));
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_top_p(top_p, /* min_keep */ 1));
    llama_sampler_chain_add(nc->sampler, llama_sampler_init_temp(temperature));
    if (nc->seed >= 0)
        llama_sampler_chain_add(nc->sampler, llama_sampler_init_dist(static_cast<uint32_t>(nc->seed)));
    else
        llama_sampler_chain_add(nc->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    nc->cached_temp = temperature;
    nc->cached_top_p = top_p;
    nc->cached_top_k = top_k;
    nc->cached_repeat_penalty = repeat_penalty;
}

// トークンを文字列化（special=false で表示用テキストのみ）
static std::string token_to_piece(NezumiLlamaCtx *nc, llama_token token)
{
    char buf[256] = {};
    int n = llama_token_to_piece(
        llama_model_get_vocab(nc->model), token, buf, sizeof(buf) - 1,
        /* lstrip */ 0, /* special */ false);
    if (n <= 0)
        return "";
    // バッファオーバーラン時は負値が返る実装もあるため防御
    if (n >= static_cast<int>(sizeof(buf)))
        return "";
    return std::string(buf, n);
}

// 生成ループ本体。プロンプトは既に KV キャッシュへデコード済みの前提。
// 戻り値: 生成テキスト（stop word 自体は含まない）。
static std::string generate_loop(JNIEnv *env, NezumiLlamaCtx *nc,
                                 int n_predict, float temperature, float top_p, int top_k,
                                 float repeat_penalty,
                                 const std::vector<std::string> &stop_words)
{
    if (!nc->sampler || nc->cached_temp != temperature || nc->cached_top_p != top_p ||
        nc->cached_top_k != top_k || nc->cached_repeat_penalty != repeat_penalty)
    {
        rebuild_sampler(nc, temperature, top_p, top_k, repeat_penalty);
    }
    llama_sampler_reset(nc->sampler);
    llama_perf_context_reset(nc->ctx);

    const llama_vocab *vocab = llama_model_get_vocab(nc->model);
    std::string result;
    result.reserve(1024);
    // stop word 判定用のローリングバッファ
    std::string sw_buffer;
    // stop word の先頭部分を先行送信しないためのストリーム位置
    size_t streamed_size = 0;

    for (int i = 0; i < n_predict; ++i)
    {
        if (nc->interrupted.load(std::memory_order_acquire))
        {
            LOGI("generate_loop: interrupted");
            break;
        }

        // コンテキスト溢れ対策
        if (nc->n_past + 1 > nc->n_ctx)
        {
            if (!nc->context_shift_enabled)
            {
                LOGI("generate_loop: context full and shift disabled (n_past=%d n_ctx=%d)", nc->n_past, nc->n_ctx);
                break;
            }
            context_shift(nc);
            if (nc->n_past == 0)
            {
                LOGE("generate_loop: context_shift left empty state, abort");
                break;
            }
        }

        llama_token token = llama_sampler_sample(nc->sampler, nc->ctx, /* idx */ -1);
        if (llama_vocab_is_eog(vocab, token))
        {
            LOGI("generate_loop: EOG token %d", token);
            break;
        }
        llama_sampler_accept(nc->sampler, token);

        std::string piece = token_to_piece(nc, token);
        result += piece;

        // stop word 判定（複数トークンに跨る場合を考慮してローリングバッファで判定）
        bool hit = false;
        if (!stop_words.empty())
        {
            sw_buffer += piece;
            if (sw_buffer.size() > STOP_WORD_BUFFER_MAX)
                sw_buffer.erase(0, sw_buffer.size() - STOP_WORD_BUFFER_MAX);
            for (const auto &sw : stop_words)
            {
                if (sw.empty())
                    continue;
                auto pos = sw_buffer.find(sw);
                if (pos != std::string::npos)
                {
                    // result から stop word 以降を除去
                    auto rpos = result.rfind(sw);
                    if (rpos != std::string::npos)
                        result.erase(rpos);
                    LOGI("generate_loop: stop word matched: %s", sw.c_str());
                    hit = true;
                    break;
                }
            }
        }

        // ストップ語の先頭と一致し得る末尾は保留する。
        // これにより、タグが複数トークンに分割されても途中まで UI へ流れない。
        if (nc->token_callback && nc->on_token_mid && !piece.empty())
        {
            size_t safe_end = result.size();
            if (!hit)
            {
                size_t pending = 0;
                for (const auto &sw : stop_words)
                {
                    if (sw.empty())
                        continue;
                    const size_t max_prefix = std::min(result.size(), sw.size() - 1);
                    for (size_t prefix = max_prefix; prefix > pending; --prefix)
                    {
                        if (result.compare(result.size() - prefix, prefix, sw, 0, prefix) == 0)
                        {
                            pending = prefix;
                            break;
                        }
                    }
                }
                safe_end -= pending;
            }
            if (safe_end > streamed_size)
            {
                const std::string safe_piece = result.substr(streamed_size, safe_end - streamed_size);
                streamed_size = safe_end;
                jstring j_piece = utf8_to_jstring(env, safe_piece);
                env->CallVoidMethod(nc->token_callback, nc->on_token_mid, j_piece);
                env->DeleteLocalRef(j_piece);
                if (env->ExceptionCheck())
                {
                    LOGE("generate_loop: exception in token callback, aborting");
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    break;
                }
            }
        }

        if (hit)
            break;

        if (!decode_single_token(nc, token))
        {
            LOGE("generate_loop: llama_decode failed at n_past=%d", nc->n_past);
            break;
        }
    }
    // 通常終了や割り込みで保留していた末尾を送る（stop 語自体は result から除去済み）。
    if (nc->token_callback && nc->on_token_mid && streamed_size < result.size())
    {
        jstring j_piece = utf8_to_jstring(env, result.substr(streamed_size));
        env->CallVoidMethod(nc->token_callback, nc->on_token_mid, j_piece);
        env->DeleteLocalRef(j_piece);
    }
    return result;
}

// messages JSON ([{role, content}, ...]) を common_chat_msg 配列に変換
static std::vector<common_chat_msg> parse_messages_json(const std::string &messages_json)
{
    std::vector<common_chat_msg> msgs;
    try
    {
        auto arr = nlohmann::ordered_json::parse(messages_json);
        if (!arr.is_array())
        {
            LOGE("parse_messages_json: not an array");
            return msgs;
        }
        for (const auto &m : arr)
        {
            common_chat_msg msg;
            msg.role = m.value("role", "");
            msg.content = m.value("content", "");
            msgs.push_back(std::move(msg));
        }
    }
    catch (const std::exception &e)
    {
        LOGE("parse_messages_json: %s", e.what());
    }
    return msgs;
}

// ─── ライフサイクル ───────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaInit(
    JNIEnv *env,
    jobject /* obj */,
    jstring j_model_path,
    jint n_ctx,
    jint n_batch,
    jint n_ubatch,
    jint n_threads,
    jint n_gpu_layers,
    jboolean use_mmap,
    jboolean use_mlock,
    jfloat rope_freq_base,
    jfloat rope_freq_scale,
    jstring j_mmproj_path,
    jboolean flash_attn_enabled,
    jboolean context_shift_enabled,
    jint seed,
    jstring j_gpu_backend)
{
    std::call_once(g_backend_init_once, []()
                   {
                       nezumi_prepare_opencl_icd_paths();
                       llama_backend_init();
                   });

    const char *model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char *gpu_backend_chars = j_gpu_backend ? env->GetStringUTFChars(j_gpu_backend, nullptr) : nullptr;
    const std::string requested_gpu_backend = gpu_backend_chars ? gpu_backend_chars : "CPU";
    int gpu_layers = n_gpu_layers;
    bool gpu_backend_fallback_occurred = false;
    std::vector<ggml_backend_dev_t> devices = nezumi_collect_devices(
        requested_gpu_backend.c_str(), &gpu_layers, &gpu_backend_fallback_occurred);
    // フォールバック時は実際に使われたバックエンドを "CPU" として記録する。
    // ログや呼び出し元 (Kotlin) には「要求値」ではなくこちらを返す。
    const std::string actual_gpu_backend = gpu_backend_fallback_occurred ? "CPU" : requested_gpu_backend;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpu_layers;
    // CPU または要求バックエンド未検出時は nullptr（全デバイス + n_gpu_layers=0）。
    // 空の {nullptr} リストを渡すと llama.cpp がデバイス 0 件でロードに失敗する。
    mparams.devices = (!devices.empty() && devices.front() != nullptr) ? devices.data() : nullptr;
    // mmap/mlock: 新APIでは llama_load_mode で指定する
    if (use_mmap && use_mlock)
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    else if (use_mmap)
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    else if (use_mlock)
        mparams.load_mode = LLAMA_LOAD_MODE_MLOCK;
    else
        mparams.load_mode = LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(j_model_path, model_path);
    if (gpu_backend_chars)
        env->ReleaseStringUTFChars(j_gpu_backend, gpu_backend_chars);

    if (!model)
    {
        LOGE("llamaInit: failed to load model");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    cparams.n_ubatch = static_cast<uint32_t>(n_ubatch > 0 ? n_ubatch : cparams.n_batch);
    cparams.n_threads = static_cast<int32_t>(n_threads);
    cparams.n_threads_batch = static_cast<int32_t>(n_threads);
    cparams.rope_freq_base = rope_freq_base;   // 0 = モデルのデフォルト
    cparams.rope_freq_scale = rope_freq_scale; // 0 = モデルのデフォルト
    cparams.flash_attn_type = flash_attn_enabled ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                                 : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx)
    {
        LOGE("llamaInit: failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto *nc = new NezumiLlamaCtx();
    nc->model = model;
    nc->ctx = ctx;
    nc->n_ctx = n_ctx;
    nc->n_batch = static_cast<int>(cparams.n_batch);
    nc->n_ubatch = static_cast<int>(cparams.n_ubatch);
    nc->context_shift_enabled = context_shift_enabled;
    nc->seed = seed;
    nc->requested_gpu_backend = requested_gpu_backend;
    nc->actual_gpu_backend = actual_gpu_backend;
    nc->gpu_backend_fallback_occurred = gpu_backend_fallback_occurred;
    nc->batch = llama_batch_init(nc->n_batch, 0, 1); // バッチを事前確保
    env->GetJavaVM(&nc->jvm);

    // マルチモーダル (mtmd): mmproj が指定された場合のみ初期化
    if (j_mmproj_path != nullptr)
    {
        const char *mmproj_path = env->GetStringUTFChars(j_mmproj_path, nullptr);
        mtmd_helper_log_set(nezumi_ggml_log_callback, nullptr);

        mtmd_context_params mctx_params = mtmd_context_params_default();
        mctx_params.use_gpu = gpu_layers > 0;
        mctx_params.n_threads = n_threads;
        mctx_params.flash_attn_type = cparams.flash_attn_type;
        mctx_params.warmup = false; // 起動時間短縮のため warmup は行わない

        nc->mtmd_ctx = mtmd_init_from_file(mmproj_path, model, mctx_params);
        env->ReleaseStringUTFChars(j_mmproj_path, mmproj_path);

        if (!nc->mtmd_ctx)
        {
            LOGW("llamaInit: failed to init mtmd (mmproj=%s), continuing with text-only mode", mmproj_path);
        }
        else
        {
            LOGI("llamaInit: mtmd loaded (vision=%d audio=%d)",
                 mtmd_support_vision(nc->mtmd_ctx), mtmd_support_audio(nc->mtmd_ctx));
        }
    }

    // GGUF 埋め込みチャットテンプレートを初期化（失敗しても推論自体は続行可能）
    nc->chat_templates = common_chat_templates_init(model, /* chat_template_override */ "").release();
    if (!nc->chat_templates)
    {
        LOGW("llamaInit: no usable chat template in GGUF");
    }

    // backend= には実際に使われたバックエンドを出す（要求値ではない）。
    // フォールバック発生時は requested= も併記し、ログだけで矛盾なく状況が追える
    // ようにする（以前は backend=OPENCL なのに n_gpu_layers=0 という矛盾ログだった）。
    if (gpu_backend_fallback_occurred)
    {
        LOGI("llamaInit: OK n_ctx=%d n_batch=%d n_ubatch=%d n_gpu_layers=%d backend=%s requested=%s (FALLBACK) flash_attn=%d ctx_shift=%d mtmd=%s chat_tmpl=%s",
             n_ctx, nc->n_batch, nc->n_ubatch, gpu_layers,
             actual_gpu_backend.c_str(), requested_gpu_backend.c_str(),
             flash_attn_enabled, context_shift_enabled,
             nc->mtmd_ctx ? "loaded" : "none",
             nc->chat_templates ? "ok" : "none");
    }
    else
    {
        LOGI("llamaInit: OK n_ctx=%d n_batch=%d n_ubatch=%d n_gpu_layers=%d backend=%s flash_attn=%d ctx_shift=%d mtmd=%s chat_tmpl=%s",
             n_ctx, nc->n_batch, nc->n_ubatch, gpu_layers,
             actual_gpu_backend.c_str(),
             flash_attn_enabled, context_shift_enabled,
             nc->mtmd_ctx ? "loaded" : "none",
             nc->chat_templates ? "ok" : "none");
    }
    return reinterpret_cast<jlong>(nc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_llamaFree(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx)
{
    if (j_ctx == 0)
        return;
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);

    if (nc->token_callback)
    {
        env->DeleteGlobalRef(nc->token_callback);
        nc->token_callback = nullptr;
    }
    if (nc->sampler)
    {
        llama_sampler_free(nc->sampler);
        nc->sampler = nullptr;
    }
    llama_batch_free(nc->batch);
    if (nc->chat_templates)
    {
        common_chat_templates_free(nc->chat_templates);
        nc->chat_templates = nullptr;
    }
    if (nc->mtmd_ctx)
    {
        mtmd_free(nc->mtmd_ctx);
        nc->mtmd_ctx = nullptr;
    }
    if (nc->ctx)
    {
        llama_free(nc->ctx);
        nc->ctx = nullptr;
    }
    if (nc->model)
    {
        llama_model_free(nc->model);
        nc->model = nullptr;
    }
    delete nc;
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
    std::string piece = token_to_piece(nc, static_cast<llama_token>(token));
    return utf8_to_jstring(env, piece);
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

// ─── 推論（低レベルプリミティブ） ─────────────────────────────────

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
        LOGE("llamaDecode: token batch size %d exceeds capacity %d", len, nc->n_batch);
        env->ReleaseIntArrayElements(j_tokens, raw, JNI_ABORT);
        return static_cast<jint>(-1);
    }

    nc->batch.n_tokens = static_cast<int32_t>(len);
    for (int i = 0; i < len; ++i)
    {
        nc->batch.token[i] = static_cast<llama_token>(raw[i]);
        if (nc->batch.pos)
            nc->batch.pos[i] = nc->n_past + i; // KVキャッシュの現在位置から続ける
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
        nc->n_past += static_cast<int>(len); // 成功時のみ位置を進める

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

    if (!nc->sampler || nc->cached_temp != temperature || nc->cached_top_p != top_p ||
        nc->cached_top_k != top_k || nc->cached_repeat_penalty != repeat_penalty)
    {
        rebuild_sampler(nc, temperature, top_p, top_k, repeat_penalty);
    }

    llama_token token = llama_sampler_sample(nc->sampler, nc->ctx, /* idx */ -1);
    llama_sampler_accept(nc->sampler, token);
    return static_cast<jint>(token);
}

// ─── トークンストリーミングコールバック ───────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeSetTokenCallback(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jobject j_callback)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc)
        return;

    if (nc->token_callback)
    {
        env->DeleteGlobalRef(nc->token_callback);
        nc->token_callback = nullptr;
        nc->on_token_mid = nullptr;
    }

    if (j_callback)
    {
        jclass cls = env->GetObjectClass(j_callback);
        nc->on_token_mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!nc->on_token_mid)
        {
            LOGE("nativeSetTokenCallback: onToken method not found");
            return;
        }
        nc->token_callback = env->NewGlobalRef(j_callback);
        LOGI("nativeSetTokenCallback: callback installed");
    }
}

// ─── 生成中断 ─────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeInterrupt(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (nc)
        nc->interrupted.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeClearInterrupt(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (nc)
        nc->interrupted.store(false, std::memory_order_release);
}

// ─── 高レベル補完 API ─────────────────────────────────────────────

// stop words 配列を std::vector に変換
static std::vector<std::string> jstring_array_to_vector(JNIEnv *env, jobjectArray arr)
{
    std::vector<std::string> out;
    if (!arr)
        return out;
    jsize len = env->GetArrayLength(arr);
    for (jsize i = 0; i < len; ++i)
    {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (js)
        {
            const char *s = env->GetStringUTFChars(js, nullptr);
            out.emplace_back(s);
            env->ReleaseStringUTFChars(js, s);
            env->DeleteLocalRef(js);
        }
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeComplete(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jstring j_prompt,
    jint n_predict,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jobjectArray j_stop_words)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->ctx)
        return env->NewStringUTF("");

    nc->interrupted.store(false, std::memory_order_release);

    const char *prompt = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(j_prompt, prompt);
    std::vector<std::string> stop_words = jstring_array_to_vector(env, j_stop_words);

    // プロンプトをトークナイズしてバッチデコード
    // add_special=false: チャットテンプレート適用後の文字列が渡る前提（旧 rn-completion と同じ）
    const llama_vocab *vocab = llama_model_get_vocab(nc->model);
    int n_tokens_max = static_cast<int>(prompt_str.size()) + 256;
    std::vector<llama_token> tokens(n_tokens_max);
    int n_tokens = llama_tokenize(vocab, prompt_str.data(), static_cast<int32_t>(prompt_str.size()),
                                  tokens.data(), n_tokens_max,
                                  /* add_special */ false, /* parse_special */ true);
    if (n_tokens < 0)
    {
        // バッファ不足: 必要数が -n_tokens で返る
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt_str.data(), static_cast<int32_t>(prompt_str.size()),
                                  tokens.data(), -n_tokens, false, true);
    }
    if (n_tokens <= 0)
    {
        LOGE("nativeComplete: tokenize failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // プロンプトがコンテキストに収まらない場合は先にシフト/クリア
    if (nc->n_past + n_tokens > nc->n_ctx)
    {
        if (nc->context_shift_enabled)
        {
            context_shift(nc);
        }
        if (nc->n_past + n_tokens > nc->n_ctx)
        {
            LOGW("nativeComplete: prompt does not fit, clearing KV cache (n_past=%d tokens=%d n_ctx=%d)",
                 nc->n_past, n_tokens, nc->n_ctx);
            llama_memory_clear(llama_get_memory(nc->ctx), true);
            nc->n_past = 0;
        }
    }

    // n_batch ずつ分割してデコード
    for (int i = 0; i < n_tokens; i += nc->n_batch)
    {
        if (nc->interrupted.load(std::memory_order_acquire))
            break;
        int chunk = std::min(nc->n_batch, n_tokens - i);
        nc->batch.n_tokens = chunk;
        for (int j = 0; j < chunk; ++j)
        {
            nc->batch.token[j] = tokens[i + j];
            if (nc->batch.pos)
                nc->batch.pos[j] = nc->n_past + j;
            if (nc->batch.n_seq_id)
                nc->batch.n_seq_id[j] = 1;
            if (nc->batch.seq_id && nc->batch.seq_id[j])
                nc->batch.seq_id[j][0] = 0;
            if (nc->batch.logits)
                nc->batch.logits[j] = 0;
        }
        bool last_chunk = (i + chunk >= n_tokens);
        if (nc->batch.logits && last_chunk)
            nc->batch.logits[chunk - 1] = 1; // 最終トークンのみ logits
        if (llama_decode(nc->ctx, nc->batch) != 0)
        {
            LOGE("nativeComplete: prompt decode failed at offset %d", i);
            return utf8_to_jstring(env, "");
        }
        nc->n_past += chunk;
    }

    std::string result = generate_loop(env, nc, n_predict, temperature, top_p, top_k,
                                       /* repeat_penalty */ repeat_penalty, stop_words);
    return utf8_to_jstring(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeCompleteWithMedia(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jstring j_prompt,
    jint n_predict,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jobjectArray j_stop_words,
    jobjectArray j_media_paths)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->ctx)
        return env->NewStringUTF("");

    // メディアなし or mtmd 未初期化ならテキストのみの complete にフォールバック
    jsize n_media = j_media_paths ? env->GetArrayLength(j_media_paths) : 0;
    if (n_media == 0 || !nc->mtmd_ctx)
    {
        if (n_media > 0 && !nc->mtmd_ctx)
            LOGW("nativeCompleteWithMedia: media given but mtmd not initialized, falling back to text-only");
        return Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeComplete(
            env, nullptr, j_ctx, j_prompt, n_predict, temperature, top_p, top_k, repeat_penalty, j_stop_words);
    }

    nc->interrupted.store(false, std::memory_order_release);

    const char *prompt = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(j_prompt, prompt);
    std::vector<std::string> stop_words = jstring_array_to_vector(env, j_stop_words);

    // メディアファイルを bitmap に変換
    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(n_media);
    mtmd_helper_init_opt helper_opt = mtmd_helper_init_opt_default();
    for (jsize i = 0; i < n_media; ++i)
    {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(j_media_paths, i));
        if (!js)
            continue;
        const char *path = env->GetStringUTFChars(js, nullptr);
        auto wrapper = mtmd_helper_bitmap_init_from_file(nc->mtmd_ctx, path, /* placeholder */ false, helper_opt);
        env->ReleaseStringUTFChars(js, path);
        env->DeleteLocalRef(js);
        if (wrapper.bitmap)
        {
            bitmaps.push_back(wrapper.bitmap);
        }
        else
        {
            LOGE("nativeCompleteWithMedia: failed to load media file");
        }
    }

    if (bitmaps.empty())
    {
        LOGE("nativeCompleteWithMedia: no media could be loaded, aborting");
        return env->NewStringUTF("");
    }

    // プロンプト＋メディアマーカーをチャンク化
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text input_text;
    input_text.text = prompt_str.c_str();
    input_text.text_len = prompt_str.size();
    input_text.add_special = true; // BOS 等を付与（チャットテンプレート経由で二重付与になる場合は調整）
    input_text.parse_special = true;

    std::vector<const mtmd_bitmap *> bitmap_ptrs(bitmaps.begin(), bitmaps.end());
    int32_t tok_ret = mtmd_tokenize(nc->mtmd_ctx, chunks, &input_text, bitmap_ptrs.data(), bitmap_ptrs.size());
    // bitmap は tokenize 後に解放してよい（チャンクが必要情報を保持）
    for (auto *bmp : bitmaps)
        mtmd_bitmap_free(bmp);

    if (tok_ret != 0)
    {
        LOGE("nativeCompleteWithMedia: mtmd_tokenize failed ret=%d", tok_ret);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("");
    }

    // コンテキストに収まるか確認（超過時はクリア）
    size_t total_pos = mtmd_helper_get_n_pos(chunks);
    if (nc->n_past + static_cast<int>(total_pos) > nc->n_ctx)
    {
        LOGW("nativeCompleteWithMedia: prompt+media too large, clearing KV cache");
        llama_memory_clear(llama_get_memory(nc->ctx), true);
        nc->n_past = 0;
    }

    // チャンクを評価（テキストのデコードと画像エンコードを一括処理）
    llama_pos new_n_past = nc->n_past;
    int32_t eval_ret = mtmd_helper_eval_chunks(nc->mtmd_ctx, nc->ctx, chunks,
                                               nc->n_past, /* seq_id */ 0,
                                               nc->n_batch, /* logits_last */ true,
                                               &new_n_past);
    mtmd_input_chunks_free(chunks);
    if (eval_ret != 0)
    {
        LOGE("nativeCompleteWithMedia: eval_chunks failed ret=%d", eval_ret);
        return env->NewStringUTF("");
    }
    nc->n_past = new_n_past;

    std::string result = generate_loop(env, nc, n_predict, temperature, top_p, top_k,
                                       repeat_penalty, stop_words);
    return utf8_to_jstring(env, result);
}

// ─── チャットテンプレート ─────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeApplyGgufChatTemplate(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jstring j_messages_json,
    jboolean enable_thinking,
    jboolean add_generation_prompt)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->chat_templates)
    {
        LOGE("nativeApplyGgufChatTemplate: no chat templates");
        return env->NewStringUTF("");
    }

    const char *json = env->GetStringUTFChars(j_messages_json, nullptr);
    std::string messages_json(json);
    env->ReleaseStringUTFChars(j_messages_json, json);

    common_chat_templates_inputs inputs;
    inputs.messages = parse_messages_json(messages_json);
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.use_jinja = true;
    inputs.enable_thinking = enable_thinking;
    inputs.now = std::chrono::system_clock::now();

    try
    {
        common_chat_params params = common_chat_templates_apply(nc->chat_templates, inputs);
        // パース用に params 全体をキャッシュ (旧 rnllama の gguf_chat_params と同等)
        nc->chat_params = params;
        nc->chat_params_valid = true;
        return utf8_to_jstring(env, params.prompt);
    }
    catch (const std::exception &e)
    {
        LOGE("nativeApplyGgufChatTemplate: %s", e.what());
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeApplyJinjaChatTemplate(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jstring j_messages_json,
    jstring j_chat_template,
    jboolean enable_thinking,
    jboolean add_generation_prompt)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->model)
        return env->NewStringUTF("");

    const char *json = env->GetStringUTFChars(j_messages_json, nullptr);
    std::string messages_json(json);
    env->ReleaseStringUTFChars(j_messages_json, json);
    const char *tmpl = env->GetStringUTFChars(j_chat_template, nullptr);
    std::string chat_template(tmpl);
    env->ReleaseStringUTFChars(j_chat_template, tmpl);

    common_chat_templates_inputs inputs;
    inputs.messages = parse_messages_json(messages_json);
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.use_jinja = true;
    inputs.enable_thinking = enable_thinking;
    inputs.now = std::chrono::system_clock::now();

    try
    {
        // 明示テンプレートで一時的なテンプレートセットを構築
        common_chat_templates_ptr tmpls = common_chat_templates_init(nc->model, chat_template);
        if (!tmpls)
        {
            LOGE("nativeApplyJinjaChatTemplate: failed to init template");
            return env->NewStringUTF("");
        }
        common_chat_params params = common_chat_templates_apply(tmpls.get(), inputs);
        // 明示テンプレートでも同様にキャッシュし、対応パーサーで分離できるようにする
        nc->chat_params = params;
        nc->chat_params_valid = true;
        return utf8_to_jstring(env, params.prompt);
    }
    catch (const std::exception &e)
    {
        LOGE("nativeApplyJinjaChatTemplate: %s", e.what());
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeHasGgufChatTemplate(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    return (nc && nc->chat_params_valid) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeParseGgufChatOutput(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx,
    jstring j_output,
    jboolean is_partial)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);

    const char *output = env->GetStringUTFChars(j_output, nullptr);
    std::string output_str(output);
    env->ReleaseStringUTFChars(j_output, output);

    nlohmann::ordered_json result;
    try
    {
        if (nc && nc->chat_params_valid)
        {
            // テンプレート適用時にキャッシュした params から、テンプレートが選択した
            // フォーマット/パーサーで content / reasoning_content を分離する
            // (旧 rnllama 方式 nativeParseGgufChatOutput と同等)。
            common_chat_parser_params parser_params(nc->chat_params);
            parser_params.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            parser_params.parse_tool_calls = true;
            if (!nc->chat_params.parser.empty())
            {
                // PEG ベースのフォーマット (COMMON_CHAT_FORMAT_PEG_*) に対応
                parser_params.parser.load(nc->chat_params.parser);
            }

            common_chat_msg msg = common_chat_parse(output_str, is_partial, parser_params);
            result["content"] = msg.content;
            result["reasoning_content"] = msg.reasoning_content;
        }
        else
        {
            // テンプレート無し: そのまま content として返す
            result["content"] = output_str;
            result["reasoning_content"] = "";
        }
    }
    catch (const std::exception &e)
    {
        LOGE("nativeParseGgufChatOutput: %s", e.what());
        result["content"] = output_str;
        result["reasoning_content"] = "";
    }
    std::string out = result.dump();
    return utf8_to_jstring(env, out);
}

// ─── マルチモーダル情報 ───────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeIsVisionSupported(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    return (nc && nc->mtmd_ctx && mtmd_support_vision(nc->mtmd_ctx)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeIsAudioSupported(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    return (nc && nc->mtmd_ctx && mtmd_support_audio(nc->mtmd_ctx)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeGetAudioSampleRate(
    JNIEnv * /* env */,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->mtmd_ctx)
        return static_cast<jint>(-1);
    return static_cast<jint>(mtmd_get_audio_sample_rate(nc->mtmd_ctx));
}

// ─── タイミング統計 ───────────────────────────────────────────────

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeGetLastTimings(
    JNIEnv *env,
    jobject /* obj */,
    jlong j_ctx)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(j_ctx);
    if (!nc || !nc->ctx)
        return nullptr;

    const llama_perf_context_data t = llama_perf_context(nc->ctx);
    // 旧方式 nativeGetLastTimings と同じ並び:
    // [promptMs, promptTokens, decodeMs, decodeTokens]
    jfloat timings[4] = {
        static_cast<jfloat>(t.t_p_eval_ms),
        static_cast<jfloat>(t.n_p_eval),
        static_cast<jfloat>(t.t_eval_ms),
        static_cast<jfloat>(t.n_eval),
    };
    jfloatArray result = env->NewFloatArray(4);
    env->SetFloatArrayRegion(result, 0, 4, timings);
    return result;
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

/**
 * モデルをロードせずに、要求バックエンド ("OPENCL" / "VULKAN") が実行時に
 * 本当に使える（ggml_backend_registry にデバイスが1つ以上ある）かどうかを判定する。
 *
 * 設定画面はこれまで libOpenCL.so / libvulkan.so の「ファイルの有無」だけで
 * 選択可否 (enabled) を決めていたため、ライブラリはあってもICD/ドライバが
 * 機能しない端末では「選択できるのに実際は動かない」状態になっていた。
 * この関数は llamaInit と同じ ggml_backend_dev_* API で実デバイスを問い合わせる
 * ため、設定画面の選択可否判定にはファイル存在チェックではなくこちらを使うべき。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeProbeGpuBackendAvailable(
    JNIEnv *env,
    jobject /* obj */,
    jstring j_gpu_backend)
{
    std::call_once(g_backend_init_once, []()
                   {
                       nezumi_prepare_opencl_icd_paths();
                       llama_backend_init();
                   });

    if (j_gpu_backend == nullptr)
        return JNI_FALSE;

    const char *gpu_backend_chars = env->GetStringUTFChars(j_gpu_backend, nullptr);
    const char *want = nezumi_backend_reg_want(gpu_backend_chars);
    if (want == nullptr)
    {
        env->ReleaseStringUTFChars(j_gpu_backend, gpu_backend_chars);
        return JNI_FALSE;
    }

    bool found = false;
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i)
    {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
        const char *reg_name = ggml_backend_reg_name(reg);
        if (reg_name && nezumi_iequals(reg_name, want))
        {
            found = true;
            break;
        }
    }

    env->ReleaseStringUTFChars(j_gpu_backend, gpu_backend_chars);
    return found ? JNI_TRUE : JNI_FALSE;
}

// llamaInit 完了後、実際にロードされたバックエンドを問い合わせる。
// UI 側でリクエストと異なる場合にフォールバック通知ダイアログを出すために使う。
extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeGetActualGpuBackend(
    JNIEnv *env,
    jobject /* obj */,
    jlong ctx_ptr)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(ctx_ptr);
    if (!nc)
        return env->NewStringUTF("CPU");
    return env->NewStringUTF(nc->actual_gpu_backend.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeGpuBackendFallbackOccurred(
    JNIEnv *env,
    jobject /* obj */,
    jlong ctx_ptr)
{
    auto *nc = reinterpret_cast<NezumiLlamaCtx *>(ctx_ptr);
    if (!nc)
        return JNI_FALSE;
    return nc->gpu_backend_fallback_occurred ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nezumi_1ai_data_inference_LlamaBridge_nativeCompiledGpuBackends(
    JNIEnv *env,
    jobject /* obj */)
{
    std::vector<const char *> names;
#ifdef NEZUMI_LLAMA_OPENCL
    names.push_back("OPENCL");
#endif
#ifdef NEZUMI_LLAMA_VULKAN
    names.push_back("VULKAN");
#endif
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(names.size()), string_class, nullptr);
    for (size_t i = 0; i < names.size(); ++i)
    {
        jstring item = env->NewStringUTF(names[i]);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return result;
}
