#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>
#include <string>
#include <vector>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <unordered_set>

#include "rn-llama.h"
#include "rn-completion.h"
#include "mtmd.h"

namespace
{
    static constexpr const char *TAG = "NEZUMI_RNLLAMA_JNI";

    struct ContextHolder
    {
        rnllama::llama_rn_context *ctx = nullptr;
        rnllama::llama_rn_context_completion *completion = nullptr;

        JavaVM *jvm = nullptr;
        jobject token_callback = nullptr; // GlobalRef
        std::atomic<bool> is_released{false};
        int active_completions = 0;
    };

    static std::mutex g_mutex;
    static std::condition_variable g_completion_cv;
    static std::unordered_set<ContextHolder *> g_live_holders;

    static bool filePathExists(const char *path)
    {
        if (path == nullptr || path[0] == '\0')
        {
            return false;
        }
        struct stat st;
        return stat(path, &st) == 0;
    }

    static std::string sanitizeUtf8Lossy(const std::string &input)
    {
        std::string out;
        out.reserve(input.size());

        for (size_t i = 0; i < input.size();)
        {
            const unsigned char c = static_cast<unsigned char>(input[i]);
            size_t len = 0;

            if (c <= 0x7F)
            {
                len = 1;
            }
            else if ((c & 0xE0) == 0xC0)
            {
                len = 2;
                if (c < 0xC2)
                    len = 0;
            }
            else if ((c & 0xF0) == 0xE0)
            {
                len = 3;
            }
            else if ((c & 0xF8) == 0xF0)
            {
                len = 4;
                if (c > 0xF4)
                    len = 0;
            }

            bool valid = len != 0 && i + len <= input.size();
            for (size_t j = 1; valid && j < len; ++j)
            {
                const unsigned char cc = static_cast<unsigned char>(input[i + j]);
                if ((cc & 0xC0) != 0x80)
                {
                    valid = false;
                }
            }

            if (valid && len == 3)
            {
                const unsigned char c1 = static_cast<unsigned char>(input[i + 1]);
                if ((c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 >= 0xA0))
                {
                    valid = false;
                }
            }

            if (valid && len == 4)
            {
                const unsigned char c1 = static_cast<unsigned char>(input[i + 1]);
                if ((c == 0xF0 && c1 < 0x90) || (c == 0xF4 && c1 > 0x8F))
                {
                    valid = false;
                }
            }

            if (valid)
            {
                out.append(input, i, len);
                i += len;
            }
            else
            {
                out.push_back('?');
                ++i;
            }
        }

        return out;
    }

    static jstring newSafeJStringUTF(JNIEnv *env, const std::string &value, const char *label)
    {
        jstring result = env->NewStringUTF(value.c_str());
        if (result != nullptr && !env->ExceptionCheck())
        {
            return result;
        }

        if (env->ExceptionCheck())
        {
            env->ExceptionClear();
        }

        const std::string sanitized = sanitizeUtf8Lossy(value);
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "newSafeJStringUTF: sanitized invalid UTF-8 for %s (original=%zu sanitized=%zu)",
            label ? label : "unknown",
            value.size(),
            sanitized.size());

        result = env->NewStringUTF(sanitized.c_str());
        if (result != nullptr && !env->ExceptionCheck())
        {
            return result;
        }

        if (env->ExceptionCheck())
        {
            env->ExceptionClear();
        }

        __android_log_print(
            ANDROID_LOG_ERROR,
            TAG,
            "newSafeJStringUTF: failed to create jstring for %s even after sanitization",
            label ? label : "unknown");
        return env->NewStringUTF("");
    }

    static JNIEnv *getEnv(JavaVM *jvm, bool *didAttach)
    {
        *didAttach = false;
        JNIEnv *env = nullptr;
        if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        {
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK)
            {
                *didAttach = true;
            }
        }
        return env;
    }

    static void sendToken(ContextHolder *holder, const std::string &token)
    {
        if (!holder || !holder->jvm || holder->is_released.load(std::memory_order_acquire))
            return;

        bool didAttach = false;
        JNIEnv *env = getEnv(holder->jvm, &didAttach);
        if (!env)
            return;

        jobject callback = nullptr;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (!holder->is_released.load(std::memory_order_acquire) && holder->token_callback)
            {
                callback = env->NewLocalRef(holder->token_callback);
            }
        }
        if (!callback)
        {
            if (didAttach)
            {
                holder->jvm->DetachCurrentThread();
            }
            return;
        }

        jclass cbCls = env->GetObjectClass(callback);
        if (cbCls)
        {
            jmethodID mid = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");
            if (mid)
            {
                jstring jTok = newSafeJStringUTF(env, token, "token_callback");
                if (jTok)
                {
                    env->CallVoidMethod(callback, mid, jTok);
                    env->DeleteLocalRef(jTok);
                }
            }
            else
            {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    TAG,
                    "token_callback: GetMethodID failed for onToken - callback class may not implement TokenCallback interface");
                if (env->ExceptionCheck())
                {
                    env->ExceptionClear();
                }
            }
            env->DeleteLocalRef(cbCls);
        }
        else
        {
            __android_log_print(
                ANDROID_LOG_ERROR,
                TAG,
                "token_callback: GetObjectClass failed for callback");
        }
        env->DeleteLocalRef(callback);

        if (didAttach)
        {
            holder->jvm->DetachCurrentThread();
        }
    }

    static ContextHolder *fromPtr(jlong ptr)
    {
        return reinterpret_cast<ContextHolder *>(ptr);
    }

    class ActiveCompletionGuard
    {
    public:
        explicit ActiveCompletionGuard(ContextHolder *holder) : holder_(holder), engaged_(false)
        {
            if (!holder_)
                return;

            std::lock_guard<std::mutex> lock(g_mutex);
            if (g_live_holders.find(holder_) == g_live_holders.end() ||
                holder_->is_released.load(std::memory_order_acquire))
                return;

            ++holder_->active_completions;
            engaged_ = true;
        }

        ~ActiveCompletionGuard()
        {
            if (!engaged_)
                return;

            {
                std::lock_guard<std::mutex> lock(g_mutex);
                --holder_->active_completions;
            }
            g_completion_cv.notify_all();
        }

        bool engaged() const
        {
            return engaged_;
        }

    private:
        ContextHolder *holder_;
        bool engaged_;
    };
} // namespace

/** clip/mtmd のデフォルトログは stderr のみ（clip-impl.h の fputs）。Android では logcat に出ないため mtmd_log_set で転送する。 */
static void nezumi_mtmd_log_android(enum lm_ggml_log_level level, const char *text, void * /*user_data*/)
{
    int prio = ANDROID_LOG_INFO;
    switch (level)
    {
    case LM_GGML_LOG_LEVEL_ERROR:
        prio = ANDROID_LOG_ERROR;
        break;
    case LM_GGML_LOG_LEVEL_WARN:
        prio = ANDROID_LOG_WARN;
        break;
    case LM_GGML_LOG_LEVEL_INFO:
        prio = ANDROID_LOG_INFO;
        break;
    case LM_GGML_LOG_LEVEL_DEBUG:
        prio = ANDROID_LOG_DEBUG;
        break;
    default:
        prio = ANDROID_LOG_VERBOSE;
        break;
    }
    __android_log_print(prio, "NEZUMI_MTMD", "%s", text ? text : "");
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
{
    (void)vm;
    mtmd_log_set(nezumi_mtmd_log_android, nullptr);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeCreateContext(
    JNIEnv *env,
    jclass /*clazz*/,
    jstring modelPath,
    jint nCtx,
    jint nBatch,
    jint nThreads,
    jint nGpuLayers,
    jboolean useMmap,
    jboolean useMlock,
    jfloat ropeFreqBase,
    jfloat ropeFreqScale,
    jstring mmprojPath)
{
    if (!modelPath)
        return 0;

    const char *pathChars = env->GetStringUTFChars(modelPath, nullptr);
    std::string path = pathChars ? std::string(pathChars) : std::string();
    if (pathChars)
        env->ReleaseStringUTFChars(modelPath, pathChars);

    auto *holder = new ContextHolder();
    env->GetJavaVM(&holder->jvm);

    holder->ctx = new rnllama::llama_rn_context();
    holder->completion = new rnllama::llama_rn_context_completion(holder->ctx);
    holder->ctx->completion = holder->completion;

    common_params params;
    params.model.path = path;
    params.n_ctx = nCtx;
    params.n_batch = nBatch;
    params.n_ubatch = std::min(512, (int)nBatch);
    params.cpuparams.n_threads = nThreads;
    params.cpuparams_batch.n_threads = nThreads;
    params.n_gpu_layers = nGpuLayers;
    params.use_mmap = (useMmap == JNI_TRUE);
    params.use_mlock = (useMlock == JNI_TRUE);
    params.rope_freq_base = ropeFreqBase;
    params.rope_freq_scale = ropeFreqScale;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    params.n_parallel = 1;
    params.kv_unified = false;
    params.no_perf = false;

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "nativeCreateContext: path='%s' n_ctx=%d n_batch=%d n_threads=%d ngl=%d use_mmap=%d",
                        params.model.path.c_str(),
                        params.n_ctx,
                        params.n_batch,
                        params.cpuparams.n_threads,
                        params.n_gpu_layers,
                        params.use_mmap ? 1 : 0);

    bool ok = false;
    try
    {
        ok = holder->ctx->loadModel(params);
    }
    catch (...)
    {
        ok = false;
    }

    if (!ok || holder->ctx->ctx == nullptr || holder->ctx->model == nullptr)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeCreateContext: FAILED for path='%s'", path.c_str());
        if (holder->token_callback)
            env->DeleteGlobalRef(holder->token_callback);
        delete holder->ctx;
        delete holder;
        return 0;
    }

    // Multimodal: prefer explicit mmproj from settings; otherwise clip/mtmd can load vision tensors from the
    // same GGUF path as the text model ("integrated" multimodal single file).
    std::string mmproj_explicit;
    if (mmprojPath != nullptr)
    {
        const char *mpChars = env->GetStringUTFChars(mmprojPath, nullptr);
        if (mpChars)
        {
            mmproj_explicit.assign(mpChars);
            env->ReleaseStringUTFChars(mmprojPath, mpChars);
        }
    }

    std::string mmproj_effective;
    if (!mmproj_explicit.empty() && filePathExists(mmproj_explicit.c_str()))
    {
        mmproj_effective = std::move(mmproj_explicit);
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "nativeCreateContext: initMultimodal using explicit mmproj path");
    }
    else
    {
        if (!mmproj_explicit.empty())
        {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "nativeCreateContext: explicit mmproj missing or not a readable path; "
                                "trying base model file for embedded vision (integrated GGUF)");
        }
        else
        {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                                "nativeCreateContext: no mmproj path; trying base model file for embedded vision");
        }
        mmproj_effective = path;
    }

    {
        const bool use_gpu_clip = params.n_gpu_layers > 0;
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
                            "nativeCreateContext: [DEBUG] attempting initMultimodal: holder=%p, ctx->ctx=%p, mmproj_effective='%s'",
                            (void *)holder, (void *)holder->ctx->ctx, mmproj_effective.c_str());
        if (!holder->ctx->initMultimodal(mmproj_effective, use_gpu_clip, -1, -1))
        {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "nativeCreateContext: [DEBUG] initMultimodal FAILED path='%s' "
                                "(no vision tensors / mismatch — multimodal disabled for this load)",
                                mmproj_effective.c_str());
        }
        else
        {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                                "nativeCreateContext: [DEBUG] multimodal INITIALIZED SUCCESS path='%s', holder=%p",
                                mmproj_effective.c_str(), (void *)holder);
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_live_holders.insert(holder);
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
                            "nativeCreateContext: [DEBUG] context stored in g_live_holders, holder=%p, total=%zu",
                            (void *)holder, g_live_holders.size());
    }

    return reinterpret_cast<jlong>(holder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeSetTokenCallback(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong ctxPtr,
    jobject callback)
{
    auto *holder = fromPtr(ctxPtr);
    if (!holder)
        return;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_live_holders.find(holder) == g_live_holders.end())
        return;
    if (holder->is_released.load(std::memory_order_acquire))
        return;
    if (holder->token_callback)
    {
        env->DeleteGlobalRef(holder->token_callback);
        holder->token_callback = nullptr;
    }
    if (callback)
    {
        holder->token_callback = env->NewGlobalRef(callback);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeInterrupt(
    JNIEnv * /*env*/,
    jclass /*clazz*/,
    jlong ctxPtr)
{
    auto *holder = fromPtr(ctxPtr);
    if (!holder)
        return;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_live_holders.find(holder) == g_live_holders.end())
        return;
    if (holder->completion)
    {
        holder->completion->is_interrupted = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeReleaseContext(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong ctxPtr)
{
    auto *holder = fromPtr(ctxPtr);
    if (!holder)
        return;

    std::unique_lock<std::mutex> lock(g_mutex);
    if (g_live_holders.find(holder) == g_live_holders.end())
        return;
    const bool wasReleased = holder->is_released.exchange(true, std::memory_order_acq_rel);
    if (wasReleased)
        return;

    if (holder->completion)
    {
        holder->completion->is_interrupted = true;
    }

    g_completion_cv.wait(lock, [holder]
                         { return holder->active_completions == 0; });

    g_live_holders.erase(holder);

    if (holder->token_callback)
    {
        env->DeleteGlobalRef(holder->token_callback);
        holder->token_callback = nullptr;
    }
    if (holder->ctx)
    {
        delete holder->ctx;
        holder->ctx = nullptr;
    }
    holder->completion = nullptr;
    lock.unlock();
    delete holder;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeGetLastTimings(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong ctxPtr)
{
    auto *holder = fromPtr(ctxPtr);
    if (!holder || !holder->ctx || !holder->ctx->ctx)
        return nullptr;

    const auto t = llama_perf_context(holder->ctx->ctx);

    // 配列要素: [t_p_eval_ms, n_p_eval, t_eval_ms, n_eval]
    jfloatArray result = env->NewFloatArray(4);
    if (result == nullptr)
        return nullptr;

    jfloat timings[4] = {
        static_cast<jfloat>(t.t_p_eval_ms), // prefill eval time (ms)
        static_cast<jfloat>(t.n_p_eval),    // prefill token count
        static_cast<jfloat>(t.t_eval_ms),   // decode eval time (ms)
        static_cast<jfloat>(t.n_eval)       // decode token count
    };

    env->SetFloatArrayRegion(result, 0, 4, timings);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeComplete(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong ctxPtr,
    jstring prompt,
    jint nPredict,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jobjectArray stopWords)
{
    auto *holder = fromPtr(ctxPtr);
    if (!holder)
        return newSafeJStringUTF(env, "", "native_complete_empty_context");

    ActiveCompletionGuard completionGuard(holder);
    if (!completionGuard.engaged() || !holder->ctx || !holder->completion)
        return newSafeJStringUTF(env, "", "native_complete_empty_context");

    const char *pChars = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    std::string p = pChars ? std::string(pChars) : std::string();
    if (pChars)
        env->ReleaseStringUTFChars(prompt, pChars);

    holder->ctx->params.prompt = p;
    holder->ctx->params.n_predict = nPredict;
    holder->ctx->params.sampling.temp = temperature;
    holder->ctx->params.sampling.top_p = topP;
    holder->ctx->params.sampling.top_k = topK;
    holder->ctx->params.sampling.n_probs = 0;

    std::string out;
    try
    {
        holder->completion->rewind();
        holder->ctx->params.antiprompt.clear();
        if (stopWords)
        {
            const jsize n = env->GetArrayLength(stopWords);
            holder->ctx->params.antiprompt.reserve((size_t)n);
            for (jsize i = 0; i < n; i++)
            {
                jstring s = (jstring)env->GetObjectArrayElement(stopWords, i);
                if (!s)
                    continue;
                const char *c = env->GetStringUTFChars(s, nullptr);
                if (c)
                {
                    holder->ctx->params.antiprompt.emplace_back(c);
                    env->ReleaseStringUTFChars(s, c);
                }
                env->DeleteLocalRef(s);
            }
        }
        if (!holder->completion->initSampling())
        {
            return newSafeJStringUTF(env, "", "native_complete_init_sampling");
        }
        llama_perf_context_reset(holder->ctx->ctx);
        holder->completion->loadPrompt({});
        holder->completion->beginCompletion();

        size_t sent_count = 0;
        while (holder->completion->has_next_token &&
               !holder->completion->is_interrupted &&
               !holder->is_released.load(std::memory_order_acquire))
        {
            const rnllama::completion_token_output token_with_probs = holder->completion->doCompletion();
            if (token_with_probs.tok == -1 || holder->completion->incomplete)
            {
                continue;
            }

            const std::string token_text = common_token_to_piece(holder->ctx->ctx, token_with_probs.tok);
            size_t pos = std::min(sent_count, holder->completion->generated_text.size());
            const std::string str_test = holder->completion->generated_text.substr(pos);

            bool is_stop_full = false;
            size_t stop_pos = holder->completion->findStoppingStrings(str_test, token_text.size(), rnllama::STOP_FULL);
            if (stop_pos != std::string::npos)
            {
                is_stop_full = true;
                const std::string to_send = holder->completion->generated_text.substr(pos, stop_pos);
                sent_count += to_send.size();
                out += to_send;
                sendToken(holder, to_send);

                holder->completion->generated_text.erase(
                    holder->completion->generated_text.begin() + (long)(pos + stop_pos),
                    holder->completion->generated_text.end());
                break;
            }
            else
            {
                stop_pos = holder->completion->findStoppingStrings(str_test, token_text.size(), rnllama::STOP_PARTIAL);
            }

            if (stop_pos == std::string::npos ||
                (!holder->completion->has_next_token && !is_stop_full && stop_pos > 0))
            {
                const std::string to_send = holder->completion->generated_text.substr(pos);
                sent_count += to_send.size();
                out += to_send;
                sendToken(holder, to_send);
            }
        }

        // is_released の場合は completion / ctx がすでに解放済みの可能性があるため呼ばない
        if (!holder->is_released.load(std::memory_order_acquire))
        {
            holder->completion->endCompletion();
        }
    }
    catch (...)
    {
        // ignore
    }

    // 推論完了後に llama_perf_context() で timings を取得してログ出力
    if (!holder->is_released.load(std::memory_order_acquire) && holder->ctx && holder->ctx->ctx)
    {
        const auto t = llama_perf_context(holder->ctx->ctx);
        if (t.n_eval > 0)
        {
            double decode_tps = (t.n_eval * 1000.0) / t.t_eval_ms;
            double prefill_tps = (t.n_p_eval > 0) ? (t.n_p_eval * 1000.0) / t.t_p_eval_ms : 0.0;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                                "llama timings: decode=%.1f tok/s (%d tokens, %.1fms) prefill=%.1f tok/s (%d tokens)",
                                decode_tps, t.n_eval, t.t_eval_ms, prefill_tps, t.n_p_eval);
        }
    }

    return newSafeJStringUTF(env, out, "native_complete_result");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_data_inference_rnllama_RnLlamaNative_nativeCompleteWithMedia(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong ctxPtr,
    jstring prompt,
    jint nPredict,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jobjectArray stopWords,
    jobjectArray mediaPaths)
{
    auto *holder = fromPtr(ctxPtr);
    if (!holder)
        return newSafeJStringUTF(env, "", "native_complete_media_empty_context");

    ActiveCompletionGuard completionGuard(holder);
    if (!completionGuard.engaged() || !holder->ctx || !holder->completion)
        return newSafeJStringUTF(env, "", "native_complete_media_empty_context");

    const char *pChars = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    std::string p = pChars ? std::string(pChars) : std::string();
    if (pChars)
        env->ReleaseStringUTFChars(prompt, pChars);

    holder->ctx->params.prompt = p;
    holder->ctx->params.n_predict = nPredict;
    holder->ctx->params.sampling.temp = temperature;
    holder->ctx->params.sampling.top_p = topP;
    holder->ctx->params.sampling.top_k = topK;
    holder->ctx->params.sampling.n_probs = 0;

    std::vector<std::string> media_paths;
    if (mediaPaths)
    {
        const jsize mediaCount = env->GetArrayLength(mediaPaths);
        media_paths.reserve((size_t)mediaCount);
        for (jsize i = 0; i < mediaCount; i++)
        {
            jstring s = (jstring)env->GetObjectArrayElement(mediaPaths, i);
            if (!s)
                continue;
            const char *c = env->GetStringUTFChars(s, nullptr);
            if (c && c[0] != '\0')
            {
                media_paths.emplace_back(c);
                env->ReleaseStringUTFChars(s, c);
            }
            else if (c)
            {
                env->ReleaseStringUTFChars(s, c);
            }
            env->DeleteLocalRef(s);
        }
    }

    std::string out;
    try
    {
        holder->completion->rewind();
        holder->ctx->params.antiprompt.clear();
        if (stopWords)
        {
            const jsize n = env->GetArrayLength(stopWords);
            holder->ctx->params.antiprompt.reserve((size_t)n);
            for (jsize i = 0; i < n; i++)
            {
                jstring s = (jstring)env->GetObjectArrayElement(stopWords, i);
                if (!s)
                    continue;
                const char *c = env->GetStringUTFChars(s, nullptr);
                if (c)
                {
                    holder->ctx->params.antiprompt.emplace_back(c);
                    env->ReleaseStringUTFChars(s, c);
                }
                env->DeleteLocalRef(s);
            }
        }
        if (!holder->completion->initSampling())
        {
            return newSafeJStringUTF(env, "", "native_complete_media_init_sampling");
        }
        llama_perf_context_reset(holder->ctx->ctx);
        holder->completion->loadPrompt(media_paths);
        holder->completion->beginCompletion();

        size_t sent_count = 0;
        while (holder->completion->has_next_token &&
               !holder->completion->is_interrupted &&
               !holder->is_released.load(std::memory_order_acquire))
        {
            const rnllama::completion_token_output token_with_probs = holder->completion->doCompletion();
            if (token_with_probs.tok == -1 || holder->completion->incomplete)
            {
                continue;
            }

            const std::string token_text = common_token_to_piece(holder->ctx->ctx, token_with_probs.tok);
            size_t pos = std::min(sent_count, holder->completion->generated_text.size());
            const std::string str_test = holder->completion->generated_text.substr(pos);

            bool is_stop_full = false;
            size_t stop_pos = holder->completion->findStoppingStrings(str_test, token_text.size(), rnllama::STOP_FULL);
            if (stop_pos != std::string::npos)
            {
                is_stop_full = true;
                const std::string to_send = holder->completion->generated_text.substr(pos, stop_pos);
                sent_count += to_send.size();
                out += to_send;
                sendToken(holder, to_send);

                holder->completion->generated_text.erase(
                    holder->completion->generated_text.begin() + (long)(pos + stop_pos),
                    holder->completion->generated_text.end());
                break;
            }
            else
            {
                stop_pos = holder->completion->findStoppingStrings(str_test, token_text.size(), rnllama::STOP_PARTIAL);
            }

            if (stop_pos == std::string::npos ||
                (!holder->completion->has_next_token && !is_stop_full && stop_pos > 0))
            {
                const std::string to_send = holder->completion->generated_text.substr(pos);
                sent_count += to_send.size();
                out += to_send;
                sendToken(holder, to_send);
            }
        }

        if (!holder->is_released.load(std::memory_order_acquire))
        {
            holder->completion->endCompletion();
        }
    }
    catch (const std::exception &e)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeCompleteWithMedia: %s", e.what());
    }
    catch (...)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeCompleteWithMedia: unknown exception");
    }

    return newSafeJStringUTF(env, out, "native_complete_media_result");
}