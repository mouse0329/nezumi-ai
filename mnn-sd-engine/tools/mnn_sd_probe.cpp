#include <cstdio>
#include <cstring>

#include "mnn_sd/engine.h"

static void print_usage(const char* argv0) {
    std::fprintf(stderr,
        "Usage: %s [--backend cpu|opencl] <model.mnn>\n"
        "  Dumps MNN input/output tensor names and shapes (Phase 0 spike).\n",
        argv0);
}

int main(int argc, char** argv) {
    MnnSdBackend backend = MNN_SD_BACKEND_CPU;
    const char* model_path = nullptr;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--backend") == 0 && i + 1 < argc) {
            ++i;
            if (std::strcmp(argv[i], "opencl") == 0) {
                backend = MNN_SD_BACKEND_OPENCL;
            } else {
                backend = MNN_SD_BACKEND_CPU;
            }
        } else if (argv[i][0] != '-') {
            model_path = argv[i];
        }
    }

    if (!model_path) {
        print_usage(argv[0]);
        return 1;
    }

    char log[65536];
    MnnSdErrorInfo error{};
    MnnSdError code = mnn_sd_probe_model(model_path, backend, log, sizeof(log), &error);

    std::fputs(log, stdout);
    if (!log[0]) std::fputc('\n', stdout);

    if (code != MNN_SD_OK) {
        std::fprintf(stderr, "probe failed: %s (%s)\n",
            mnn_sd_error_string(code), error.message);
        if (error.cause[0]) {
            std::fprintf(stderr, "  cause: %s\n", error.cause);
        }
        return 2;
    }
    return 0;
}
