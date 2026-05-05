#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define SD_JNI_LOG_TAG "nezumi_sd_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, SD_JNI_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SD_JNI_LOG_TAG, __VA_ARGS__)

namespace
{
    static JavaVM *g_jvm = nullptr;

    struct SdJniCtx
    {
        sd_ctx_t *sd = nullptr;
        std::atomic<bool> cancel{false};
        std::atomic<int> current_step{0};
        std::atomic<int> total_steps{0};
        std::atomic<float> current_time{0.0f};
        jobject callback_obj = nullptr;
    };

    static std::string jstring_to_utf8(JNIEnv *env, jstring js)
    {
        if (!js)
        {
            return {};
        }
        const char *c = env->GetStringUTFChars(js, nullptr);
        if (!c)
        {
            return {};
        }
        std::string out(c);
        env->ReleaseStringUTFChars(js, c);
        return out;
    }

    static void progress_cb(int step, int steps, float time, void *data)
    {
        auto *w = static_cast<SdJniCtx *>(data);
        if (!w) return;
        
        w->current_step.store(step, std::memory_order_relaxed);
        w->total_steps.store(steps, std::memory_order_relaxed);
        w->current_time.store(time, std::memory_order_relaxed);
        
        if (w->cancel.load(std::memory_order_relaxed))
        {
            LOGI("[SD] cancel requested during generation");
            return;
        }
        
        // Kotlinコールバックを呼ぶ
        if (g_jvm && w->callback_obj)
        {
            JNIEnv *env = nullptr;
            bool detach = false;
            int status = g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
            if (status == JNI_EDETACHED)
            {
                if (g_jvm->AttachCurrentThread(&env, nullptr) == 0)
                {
                    detach = true;
                }
            }
            if (env)
            {
                jclass cls = env->GetObjectClass(w->callback_obj);
                if (cls)
                {
                    jmethodID mid = env->GetMethodID(cls, "onProgress", "(IIF)V");
                    if (mid)
                    {
                        env->CallVoidMethod(w->callback_obj, mid, step, steps, time);
                    }
                    env->DeleteLocalRef(cls);
                }
                if (detach)
                {
                    g_jvm->DetachCurrentThread();
                }
            }
        }
    }

} // namespace

extern "C"
{

    JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
    {
        g_jvm = vm;
        return JNI_VERSION_1_6;
    }

    JNIEXPORT jlong JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeInit(JNIEnv *env, jobject thiz, jstring model_path, jint n_threads)
    {
        std::string path = jstring_to_utf8(env, model_path);
        if (path.empty())
        {
            LOGE("nativeInit: empty model path");
            return 0;
        }

        sd_ctx_params_t params;
        sd_ctx_params_init(&params);
        params.model_path = path.c_str();
        params.n_threads = n_threads > 0 ? n_threads : 4;
        params.enable_mmap = true;
        params.rng_type = CPU_RNG;
        params.sampler_rng_type = CPU_RNG;

        sd_ctx_t *ctx = new_sd_ctx(&params);
        if (!ctx)
        {
            LOGE("new_sd_ctx failed for path=%s", path.c_str());
            return 0;
        }
        auto *wrap = new SdJniCtx();
        wrap->sd = ctx;
        wrap->callback_obj = env->NewGlobalRef(thiz);
        sd_set_progress_callback(progress_cb, wrap);
        return reinterpret_cast<jlong>(wrap);
    }

    JNIEXPORT jbyteArray JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeGenerate(JNIEnv *env,
                                                   jobject /*thiz*/,
                                                   jlong ctx_ptr,
                                                   jstring prompt,
                                                   jstring neg_prompt,
                                                   jint width,
                                                   jint height,
                                                   jint steps,
                                                   jfloat cfg,
                                                   jlong seed)
    {
        auto *wrap = reinterpret_cast<SdJniCtx *>(ctx_ptr);
        if (!wrap || !wrap->sd)
        {
            LOGE("nativeGenerate: invalid ctx");
            return nullptr;
        }

        std::string p = jstring_to_utf8(env, prompt);
        std::string np = jstring_to_utf8(env, neg_prompt);

        sd_img_gen_params_t img{};
        sd_img_gen_params_init(&img);
        img.prompt = p.c_str();
        img.negative_prompt = np.c_str();
        img.width = static_cast<uint32_t>(width);
        img.height = static_cast<uint32_t>(height);
        img.batch_count = 1;
        img.strength = 0.8f;

        int64_t use_seed = seed;
        if (use_seed < 0)
        {
            std::random_device rd;
            std::mt19937_64 gen(rd());
            std::uniform_int_distribution<int64_t> dist(0, INT64_MAX);
            use_seed = dist(gen);
        }
        img.seed = use_seed;

        img.sample_params.sample_steps = steps > 0 ? steps : 20;
        img.sample_params.guidance.txt_cfg = cfg > 0.f ? cfg : 7.0f;
        img.sample_params.sample_method = sd_get_default_sample_method(wrap->sd);
        img.sample_params.scheduler = sd_get_default_scheduler(wrap->sd, img.sample_params.sample_method);

        wrap->cancel.store(false, std::memory_order_relaxed);
        LOGI("[SD] nativeGenerate: starting generate_image, cancel=%d", wrap->cancel.load(std::memory_order_relaxed));
        sd_image_t *results = generate_image(wrap->sd, &img);
        LOGI("[SD] nativeGenerate: generate_image returned, cancel=%d", wrap->cancel.load(std::memory_order_relaxed));
        if (wrap->cancel.load(std::memory_order_relaxed))
        {
            LOGI("[SD] nativeGenerate: generation was cancelled, discarding results");
            if (results)
            {
                for (int i = 0; i < img.batch_count; ++i)
                {
                    free(results[i].data);
                }
                free(results);
            }
            return nullptr;
        }
        if (!results)
        {
            LOGE("generate_image returned null");
            return nullptr;
        }

        sd_image_t &out = results[0];
        const uint32_t w = out.width;
        const uint32_t h = out.height;
        const uint32_t ch = out.channel;
        if (!out.data || w == 0 || h == 0 || ch < 3)
        {
            LOGE("bad image w=%u h=%u ch=%u", w, h, ch);
            for (int i = 0; i < img.batch_count; ++i)
            {
                free(results[i].data);
            }
            free(results);
            return nullptr;
        }

        const size_t rgba_bytes = static_cast<size_t>(w) * static_cast<size_t>(h) * 4u;
        std::vector<uint8_t> rgba(rgba_bytes);
        const uint8_t *src = out.data;
        uint8_t *dst = rgba.data();
        for (uint32_t i = 0; i < w * h; ++i)
        {
            if (ch == 3)
            {
                dst[i * 4 + 0] = src[i * 3 + 0];
                dst[i * 4 + 1] = src[i * 3 + 1];
                dst[i * 4 + 2] = src[i * 3 + 2];
                dst[i * 4 + 3] = 255;
            }
            else if (ch == 4)
            {
                dst[i * 4 + 0] = src[i * 4 + 0];
                dst[i * 4 + 1] = src[i * 4 + 1];
                dst[i * 4 + 2] = src[i * 4 + 2];
                dst[i * 4 + 3] = src[i * 4 + 3];
            }
            else
            {
                LOGE("unsupported channel count %u", ch);
                for (int j = 0; j < img.batch_count; ++j)
                {
                    free(results[j].data);
                }
                free(results);
                return nullptr;
            }
        }

        for (int i = 0; i < img.batch_count; ++i)
        {
            free(results[i].data);
        }
        free(results);

        jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgba_bytes));
        if (!arr)
        {
            return nullptr;
        }
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgba_bytes), reinterpret_cast<const jbyte *>(dst));
        return arr;
    }

    JNIEXPORT void JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeCancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr)
    {
        auto *wrap = reinterpret_cast<SdJniCtx *>(ctx_ptr);
        if (!wrap)
        {
            LOGE("[SD] nativeCancel: invalid ctx_ptr");
            return;
        }
        LOGI("[SD] nativeCancel: setting cancel flag");
        wrap->cancel.store(true, std::memory_order_relaxed);
        LOGI("[SD] nativeCancel: cancel flag set to true");
    }

    JNIEXPORT jint JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeGetProgress(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr)
    {
        auto *wrap = reinterpret_cast<SdJniCtx *>(ctx_ptr);
        if (!wrap)
        {
            return 0;
        }
        return wrap->current_step.load(std::memory_order_relaxed);
    }

    JNIEXPORT jint JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeGetProgressTotalSteps(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr)
    {
        auto *wrap = reinterpret_cast<SdJniCtx *>(ctx_ptr);
        if (!wrap)
        {
            return 0;
        }
        return wrap->total_steps.load(std::memory_order_relaxed);
    }

    JNIEXPORT jfloat JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeGetProgressTime(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr)
    {
        auto *wrap = reinterpret_cast<SdJniCtx *>(ctx_ptr);
        if (!wrap)
        {
            return 0.0f;
        }
        return wrap->current_time.load(std::memory_order_relaxed);
    }

    JNIEXPORT void JNICALL
    Java_com_nezumi_1ai_sd_SdEngine_nativeFree(JNIEnv *env, jobject /*thiz*/, jlong ctx_ptr)
    {
        auto *wrap = reinterpret_cast<SdJniCtx *>(ctx_ptr);
        if (!wrap)
        {
            return;
        }
        sd_set_progress_callback(nullptr, nullptr);
        if (wrap->callback_obj)
        {
            env->DeleteGlobalRef(wrap->callback_obj);
            wrap->callback_obj = nullptr;
        }
        if (wrap->sd)
        {
            free_sd_ctx(wrap->sd);
            wrap->sd = nullptr;
        }
        delete wrap;
    }

} // extern "C"
