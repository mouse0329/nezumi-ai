#include <cstdio>
#include <cstring>

#include "mnn_sd/engine.h"

int main(int argc, char **argv)
{
    if (argc < 2)
    {
        std::fprintf(stderr, "Usage: %s <model_dir>\n", argv[0]);
        return 1;
    }

    MnnSdEngine *engine = mnn_sd_create();
    MnnSdLoadOptions options{};
    options.backend = MNN_SD_BACKEND_CPU;
    options.precision_low = 1;
    options.opencl_safe_max_side = 448;

    MnnSdErrorInfo error{};
    MnnSdError code = mnn_sd_load(engine, argv[1], &options, &error);
    if (code != MNN_SD_OK)
    {
        std::fprintf(stderr, "load failed: %s (%s)\n",
                     mnn_sd_error_string(code), error.message);
        if (error.cause[0])
        {
            std::fprintf(stderr, "  cause: %s\n", error.cause);
        }
        mnn_sd_destroy(engine);
        return 2;
    }

    std::printf("loaded ok: %s\n", argv[1]);
    mnn_sd_unload(engine);
    mnn_sd_destroy(engine);
    return 0;
}
