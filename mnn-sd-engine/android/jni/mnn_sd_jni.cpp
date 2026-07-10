#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "mnn_sd/engine.h"

#define LOG_TAG "MnnSdJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

thread_local std::string g_last_error;

jlong native_handle(MnnSdEngine* engine) {
    return reinterpret_cast<jlong>(engine);
}

MnnSdEngine* from_handle(jlong handle) {
    return reinterpret_cast<MnnSdEngine*>(handle);
}

void set_last_error(const MnnSdErrorInfo& info) {
    g_last_error = std::string(mnn_sd_error_string(info.code)) + ": " + info.message;
    if (info.cause[0] != '\0') {
        g_last_error += " (";
        g_last_error += info.cause;
        g_last_error += ")";
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_create(JNIEnv* /*env*/, jobject /*thiz*/) {
    return native_handle(mnn_sd_create());
}

JNIEXPORT void JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_destroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    mnn_sd_destroy(from_handle(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_load(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring model_dir,
    jint backend,
    jint opencl_safe_max_side) {
    const char* dir_utf = env->GetStringUTFChars(model_dir, nullptr);
    MnnSdLoadOptions options{};
    options.backend = backend == 1 ? MNN_SD_BACKEND_OPENCL : MNN_SD_BACKEND_CPU;
    options.opencl_safe_max_side = opencl_safe_max_side;
    options.precision_low = 1;

    MnnSdErrorInfo error{};
    MnnSdError code = mnn_sd_load(from_handle(handle), dir_utf, &options, &error);
    env->ReleaseStringUTFChars(model_dir, dir_utf);

    if (code != MNN_SD_OK) {
        set_last_error(error);
        LOGE("load failed: %s", g_last_error.c_str());
        return JNI_FALSE;
    }
    g_last_error.clear();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_unload(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    mnn_sd_unload(from_handle(handle));
}

JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_probeModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring mnn_path,
    jint backend) {
    const char* path_utf = env->GetStringUTFChars(mnn_path, nullptr);
    char log[65536];
    MnnSdErrorInfo error{};
    MnnSdBackend be = backend == 1 ? MNN_SD_BACKEND_OPENCL : MNN_SD_BACKEND_CPU;
    MnnSdError code = mnn_sd_probe_model(path_utf, be, log, sizeof(log), &error);
    env->ReleaseStringUTFChars(mnn_path, path_utf);

    if (code != MNN_SD_OK) {
        set_last_error(error);
        LOGE("probe failed: %s", g_last_error.c_str());
    }

  std::string result = log;
    if (result.empty() && code != MNN_SD_OK) {
        result = g_last_error;
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_getLastError(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_isLoaded(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return mnn_sd_is_loaded(from_handle(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_generateNative(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring prompt,
    jstring negative_prompt,
    jint width,
    jint height,
    jint steps,
    jfloat cfg,
    jlong seed) {
    const char* prompt_utf = env->GetStringUTFChars(prompt, nullptr);
    const char* neg_utf = env->GetStringUTFChars(negative_prompt, nullptr);

    MnnSdGenerateParams params{};
    params.prompt = prompt_utf;
    params.negative_prompt = neg_utf;
    params.width = width;
    params.height = height;
    params.steps = steps;
    params.cfg_scale = cfg;
    params.seed = seed;
    params.scheduler = MNN_SD_SCHEDULER_DPM;
    params.use_opencl = 0;

    MnnSdImage image{};
    MnnSdErrorInfo error{};
    MnnSdError code = mnn_sd_generate(
        from_handle(handle), &params, nullptr, nullptr, &image, &error);

    env->ReleaseStringUTFChars(prompt, prompt_utf);
    env->ReleaseStringUTFChars(negative_prompt, neg_utf);

    if (code != MNN_SD_OK) {
        set_last_error(error);
        LOGE("generate failed: %s", g_last_error.c_str());
        return nullptr;
    }

    const size_t header_size = 8;
    const size_t rgb_size = static_cast<size_t>(image.data_size);
    const size_t total = header_size + rgb_size;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(total));
    if (!result) {
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
    if (rgb_size > 0 && image.data) {
        env->SetByteArrayRegion(
            result, 8, static_cast<jsize>(rgb_size),
            reinterpret_cast<const jbyte*>(image.data));
    }
    mnn_sd_free_image(&image);
    g_last_error.clear();
    return result;
}

JNIEXPORT void JNICALL
Java_com_nezumi_1ai_sd_MnnSdNative_cancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    mnn_sd_cancel(from_handle(handle));
}

}  // extern "C"
