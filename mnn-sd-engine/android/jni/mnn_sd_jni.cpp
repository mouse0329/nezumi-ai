#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "mnn_sd/engine.h"

#define LOG_TAG "MnnSdJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace
{

    thread_local std::string g_last_error;

    struct ProgressCtx
    {
        JNIEnv *env;
        jobject cb;
        jmethodID method;
    };

    void progress_trampoline(const MnnSdProgress *p, void *user_data)
    {
        if (!p || !user_data)
            return;
        auto *ctx = static_cast<ProgressCtx *>(user_data);
        if (!ctx->env || !ctx->cb || !ctx->method)
            return;
        ctx->env->CallVoidMethod(
            ctx->cb, ctx->method,
            static_cast<jint>(p->step),
            static_cast<jint>(p->total_steps),
            static_cast<jfloat>(p->elapsed_sec));
        if (ctx->env->ExceptionCheck())
        {
            ctx->env->ExceptionDescribe();
            ctx->env->ExceptionClear();
        }
    }

    jlong native_handle(MnnSdEngine *engine)
    {
        return reinterpret_cast<jlong>(engine);
    }

    MnnSdEngine *from_handle(jlong handle)
    {
        return reinterpret_cast<MnnSdEngine *>(handle);
    }

    void set_last_error(const MnnSdErrorInfo &info)
    {
        g_last_error = std::string(mnn_sd_error_string(info.code)) + ": " + info.message;
        if (info.cause[0] != '\0')
        {
            g_last_error += " (";
            g_last_error += info.cause;
            g_last_error += ")";
        }
    }

    MnnSdScheduler to_scheduler(jint scheduler)
    {
        // Bug fix: JNI 側は 0/1/2 の 3 種類しかマップしていなかったため、
        //   Kotlin (SdScheduler) が 8 種類定義しているにも関わらず、
        //   ユーザーが選んだ DPM++ 2M / LCM / Euler a / UniPC などは
        //   default (= DPM) にフォールバックし、事実上「選択されて動いていない」
        //   状態になっていた。types.h の MnnSdScheduler enum に合わせて
        //   0..7 のすべてを正しくマップする。
        //   ※ 現状 engine 内部は PLMS(PNDM) 固定でサンプラ切替は未実装なので
        //   ここで正しい enum を渡しても最終的な数値挙動は当面同じだが、
        //   ログ・診断上「何が要求されたか」が engine に届く点が重要。
        switch (scheduler)
        {
        case 0:
            return MNN_SD_SCHEDULER_EULER;
        case 1:
            return MNN_SD_SCHEDULER_DDIM;
        case 2:
            return MNN_SD_SCHEDULER_DPM;
        case 3:
            return MNN_SD_SCHEDULER_DPM_PP_2M;
        case 4:
            return MNN_SD_SCHEDULER_DPM_PP_2M_KARRAS;
        case 5:
            return MNN_SD_SCHEDULER_LCM;
        case 6:
            return MNN_SD_SCHEDULER_EULER_A;
        case 7:
            return MNN_SD_SCHEDULER_UNIPC;
        default:
            LOGE("to_scheduler: unknown scheduler id=%d, falling back to DPM", scheduler);
            return MNN_SD_SCHEDULER_DPM;
        }
    }

    /**
     * Bug fix: MnnSdEngine には load 時点の backend 情報が保持されているが、
     *   JNI 側では generate() 呼び出しごとに use_opencl を 0 で
     *   ハードコードしていた。これでは engine 内部 (create_interpreter_and_session)
     *   で backend=OPENCL を選択していても、param.use_opencl フラグを見る
     *   コードパス (subprocess/HTTP 互換ヘルパ経由) では常に CPU 扱いになり
     *   ユーザーが GPU を選んだ意図が反映されない。
     *   ネイティブエンジンの capabilities から supports_opencl を取り出し、
     *   それを use_opencl の初期値として使う。
     */
    int32_t resolve_use_opencl(MnnSdEngine *engine)
    {
        if (!engine)
            return 0;
        MnnSdCapabilities caps{};
        MnnSdErrorInfo err{};
        if (mnn_sd_get_capabilities(engine, &caps, &err) != MNN_SD_OK)
            return 0;
        return caps.supports_opencl ? 1 : 0;
    }

} // namespace

extern "C"
{

    JNIEXPORT jlong JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_create(JNIEnv * /*env*/, jobject /*thiz*/)
    {
        return native_handle(mnn_sd_create());
    }

    JNIEXPORT void JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_destroy(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle)
    {
        mnn_sd_destroy(from_handle(handle));
    }

    JNIEXPORT jboolean JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_load(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle,
        jstring model_dir,
        jint backend,
        jint opencl_safe_max_side)
    {
        const char *dir_utf = env->GetStringUTFChars(model_dir, nullptr);
        MnnSdLoadOptions options{};
        options.backend = backend == 1 ? MNN_SD_BACKEND_OPENCL : MNN_SD_BACKEND_CPU;
        options.opencl_safe_max_side = opencl_safe_max_side;
        options.precision_low = 1;

        MnnSdErrorInfo error{};
        MnnSdError code = mnn_sd_load(from_handle(handle), dir_utf, &options, &error);
        env->ReleaseStringUTFChars(model_dir, dir_utf);

        if (code != MNN_SD_OK)
        {
            set_last_error(error);
            LOGE("load failed: %s", g_last_error.c_str());
            return JNI_FALSE;
        }
        g_last_error.clear();
        return JNI_TRUE;
    }

    JNIEXPORT void JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_unload(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle)
    {
        mnn_sd_unload(from_handle(handle));
    }

    JNIEXPORT jstring JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_probeModel(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring mnn_path,
        jint backend)
    {
        const char *path_utf = env->GetStringUTFChars(mnn_path, nullptr);
        char log[65536];
        MnnSdErrorInfo error{};
        MnnSdBackend be = backend == 1 ? MNN_SD_BACKEND_OPENCL : MNN_SD_BACKEND_CPU;
        MnnSdError code = mnn_sd_probe_model(path_utf, be, log, sizeof(log), &error);
        env->ReleaseStringUTFChars(mnn_path, path_utf);

        if (code != MNN_SD_OK)
        {
            set_last_error(error);
            LOGE("probe failed: %s", g_last_error.c_str());
        }

        std::string result = log;
        if (result.empty() && code != MNN_SD_OK)
        {
            result = g_last_error;
        }
        return env->NewStringUTF(result.c_str());
    }

    JNIEXPORT jstring JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_getLastError(JNIEnv *env, jobject /*thiz*/)
    {
        return env->NewStringUTF(g_last_error.c_str());
    }

    JNIEXPORT jboolean JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_isLoaded(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle)
    {
        return mnn_sd_is_loaded(from_handle(handle)) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jbyteArray JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_generateNative(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle,
        jstring prompt,
        jstring negative_prompt,
        jint width,
        jint height,
        jint steps,
        jfloat cfg,
        jlong seed,
        jint scheduler,
        jobject progress_cb)
    {
        const char *prompt_utf = env->GetStringUTFChars(prompt, nullptr);
        const char *neg_utf = env->GetStringUTFChars(negative_prompt, nullptr);

        MnnSdEngine *engine_ptr = from_handle(handle);

        MnnSdGenerateParams params{};
        params.prompt = prompt_utf;
        params.negative_prompt = neg_utf;
        params.width = width;
        params.height = height;
        params.steps = steps;
        params.cfg_scale = cfg;
        params.seed = seed;
        params.scheduler = to_scheduler(scheduler);
        // Bug fix: 以前は 0 ハードコードで GPU 選択が事実上無効化されていた。
        //   load 時の backend が OpenCL なら use_opencl=1 を engine に伝える。
        params.use_opencl = resolve_use_opencl(engine_ptr);
        LOGI("generate: seed=%lld scheduler=%d use_opencl=%d %dx%d steps=%d cfg=%.2f",
             static_cast<long long>(seed), static_cast<int>(params.scheduler),
             params.use_opencl, width, height, steps, cfg);

        ProgressCtx ctx{env, nullptr, nullptr};
        if (progress_cb != nullptr)
        {
            jclass cb_class = env->GetObjectClass(progress_cb);
            if (cb_class != nullptr)
            {
                ctx.method = env->GetMethodID(cb_class, "onNativeProgress", "(IIF)V");
                env->DeleteLocalRef(cb_class);
                if (env->ExceptionCheck())
                {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    ctx.method = nullptr;
                }
            }
            if (ctx.method != nullptr)
            {
                ctx.cb = progress_cb;
            }
        }

        MnnSdImage image{};
        MnnSdErrorInfo error{};
        MnnSdProgressFn cb_fn = (ctx.cb && ctx.method) ? &progress_trampoline : nullptr;
        void *cb_ud = (ctx.cb && ctx.method) ? static_cast<void *>(&ctx) : nullptr;
        MnnSdError code = mnn_sd_generate(
            engine_ptr, &params, cb_fn, cb_ud, &image, &error);

        env->ReleaseStringUTFChars(prompt, prompt_utf);
        env->ReleaseStringUTFChars(negative_prompt, neg_utf);

        if (code != MNN_SD_OK)
        {
            set_last_error(error);
            LOGE("generate failed: %s", g_last_error.c_str());
            return nullptr;
        }

        const size_t header_size = 8;
        const size_t rgb_size = static_cast<size_t>(image.data_size);
        const size_t total = header_size + rgb_size;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(total));
        if (!result)
        {
            mnn_sd_free_image(&image);
            return nullptr;
        }

        jbyte header[8];
        header[0] = static_cast<jbyte>(image.width & 0xFF);
        header[1] = static_cast<jbyte>((image.width >> 8) & 0xFF);
        header[2] = static_cast<jbyte>((image.width >> 16) & 0xFF);
        header[3] = static_cast<jbyte>((image.width >> 24) & 0xFF);
        header[4] = static_cast<jbyte>(image.height & 0xFF);
        header[5] = static_cast<jbyte>((image.height >> 8) & 0xFF);
        header[6] = static_cast<jbyte>((image.height >> 16) & 0xFF);
        header[7] = static_cast<jbyte>((image.height >> 24) & 0xFF);

        env->SetByteArrayRegion(result, 0, 8, header);
        if (rgb_size > 0 && image.data)
        {
            env->SetByteArrayRegion(
                result, 8, static_cast<jsize>(rgb_size),
                reinterpret_cast<const jbyte *>(image.data));
        }
        mnn_sd_free_image(&image);
        g_last_error.clear();
        return result;
    }

    JNIEXPORT void JNICALL
    Java_com_nezumi_1ai_sd_MnnSdNative_cancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle)
    {
        mnn_sd_cancel(from_handle(handle));
    }

} // extern "C"
