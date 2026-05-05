#include <jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_nezumi_1ai_sd_SdEngine_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/, jstring /*model_path*/, jint /*n_threads*/) {
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_nezumi_1ai_sd_SdEngine_nativeGenerate(JNIEnv* /*env*/,
                                               jobject /*thiz*/,
                                               jlong /*ctx_ptr*/,
                                               jstring /*prompt*/,
                                               jstring /*neg_prompt*/,
                                               jint /*width*/,
                                               jint /*height*/,
                                               jint /*steps*/,
                                               jfloat /*cfg*/,
                                               jlong /*seed*/) {
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_nezumi_1ai_sd_SdEngine_nativeCancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong /*ctx_ptr*/) {}

JNIEXPORT void JNICALL
Java_com_nezumi_1ai_sd_SdEngine_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong /*ctx_ptr*/) {}

}
