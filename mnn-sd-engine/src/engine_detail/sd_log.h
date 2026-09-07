#pragma once

// Logging helpers shared by every translation unit in the engine.
//
// 旧 mnn_session.cpp では PROBE_LOG マクロと set_error / append_log /
// trim_heap_to_os が単一ファイルに同居していた。リファクタで分割した
// 各パーツが同じユーティリティを使えるよう、ここに集約する。

#include <cstdio>
#include <cstring>
#include <cstddef>

#include "mnn_sd/engine.h"

#if defined(MNN_SD_HAS_MNN)
#include <MNN/MNNDefine.h>
#ifdef ANDROID
#include <android/log.h>
#define PROBE_LOG(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "MnnSdJni", fmt, ##__VA_ARGS__)
#else
#define PROBE_LOG(fmt, ...) std::fprintf(stderr, fmt "\n", ##__VA_ARGS__)
#endif
#else
#define PROBE_LOG(fmt, ...) ((void)0)
#endif

namespace mnn_sd_detail
{

    inline void set_error(MnnSdErrorInfo *out, MnnSdError code, const char *message, const char *cause = "")
    {
        if (!out)
            return;
        out->code = code;
        std::snprintf(out->message, sizeof(out->message), "%s", message ? message : "");
        std::snprintf(out->cause, sizeof(out->cause), "%s", cause ? cause : "");
    }

    inline void append_log(char *out_log, size_t capacity, const char *line)
    {
        if (!out_log || capacity == 0 || !line)
            return;
        size_t used = std::strlen(out_log);
        if (used >= capacity - 1)
            return;
        std::snprintf(out_log + used, capacity - used, "%s\n", line);
    }

} // namespace mnn_sd_detail

// Bug fix (SIGABRT during VAE createFromFile after UNet ran):
//   Android bionicのscudoはUNetをreleaseSession/resetしても、
//   一時的に使った大きなチャンクを即座にOSに返さないことがある。
//   直後にVAEをInterpreter::createFromFileすると、scudoが新規を
//   割り当てられず internal map failure -> SIGABRT でプロセスが落ちる。
//   malloc_trim(0) を予備式に呼んで、UNet 解放後のフリーリストをOSに
//   返しておくと VAE ロードが安定する。
//   bionic の malloc_trim は malloc.h にないので、弱参照で宣言し、
//   シンボルがない環境でもリンクが通るようにする。
#if defined(__ANDROID__)
extern "C" int malloc_trim(size_t pad) __attribute__((weak));
namespace mnn_sd_detail
{
    inline void trim_heap_to_os() noexcept
    {
        if (&malloc_trim != nullptr)
        {
            (void)malloc_trim(0);
        }
    }
} // namespace mnn_sd_detail
#else
namespace mnn_sd_detail
{
    inline void trim_heap_to_os() noexcept {}
}
#endif
