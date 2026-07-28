#include "mnn_sd/engine.h"
#include "mnn_sd/model_config.h"
#include "engine_internal.h"

#include <cstdio>
#include <cstring>
#include <string>

namespace
{

    // Bug fix (SDXL + --backend opencl が黙って CPU にフォールバックする問題):
    //   以前は SD1.5 想定の 448px 上限を is_sdxl 判定より前に確定させて
    //   engine->load_options.opencl_safe_max_side へ書き込んでいたため、
    //   mnn_sd_run_pipeline 側にあった「SDXL なら 1024px まで許容する」
    //   という補正が発動する前に値が 448 で埋まってしまい、1024x1024 の
    //   SDXL 生成は常に (max_side=1024) > (safe_max=448) を満たして
    //   effective_backend が CPU に落ちていた。ユーザーが --backend opencl
    //   を明示しても、ログの backend=0 (CPU) しか出ない形で症状が現れる。
    //   ここでは SD1.5 / SDXL 双方のデフォルト値を持っておき、
    //   model_config.is_sdxl が判明した後 (mnn_sd_load_model_config 呼び出し
    //   後) に選択する。
    constexpr int kDefaultOpenClSafeMaxSideSd15 = 448;
    constexpr int kDefaultOpenClSafeMaxSideSdxl = 1024;
    constexpr int kMaxScheduler = 7;

    void set_error(MnnSdErrorInfo *out, MnnSdError code, const char *message, const char *cause = "")
    {
        if (!out)
            return;
        out->code = code;
        std::snprintf(out->message, sizeof(out->message), "%s", message ? message : "");
        std::snprintf(out->cause, sizeof(out->cause), "%s", cause ? cause : "");
    }

    bool validate_generate_params(const MnnSdGenerateParams *params, MnnSdErrorInfo *out_error)
    {
        if (!params || !params->prompt)
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "prompt is required");
            return false;
        }
        if (params->width <= 0 || params->height <= 0)
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "width and height must be positive");
            return false;
        }
        if (params->width % 64 != 0 || params->height % 64 != 0)
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "width and height must be multiples of 64");
            return false;
        }
        if (params->steps <= 0)
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "steps must be positive");
            return false;
        }
        if (params->scheduler < 0 || params->scheduler > kMaxScheduler)
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "invalid scheduler value (must be 0-7)");
            return false;
        }
        return true;
    }

} // namespace

extern "C"
{

    const char *mnn_sd_error_string(MnnSdError code)
    {
        switch (code)
        {
        case MNN_SD_OK:
            return "ok";
        case MNN_SD_ERR_MODEL_NOT_FOUND:
            return "model_not_found";
        case MNN_SD_ERR_MODEL_INVALID:
            return "model_invalid";
        case MNN_SD_ERR_NOT_LOADED:
            return "not_loaded";
        case MNN_SD_ERR_INVALID_PARAMS:
            return "invalid_params";
        case MNN_SD_ERR_OUT_OF_MEMORY:
            return "out_of_memory";
        case MNN_SD_ERR_BACKEND_INIT_FAILED:
            return "backend_init_failed";
        case MNN_SD_ERR_CANCELLED:
            return "cancelled";
        case MNN_SD_ERR_INTERNAL:
            return "internal";
        default:
            return "unknown";
        }
    }

    MnnSdEngine *mnn_sd_create()
    {
        return new MnnSdEngine();
    }

    void mnn_sd_destroy(MnnSdEngine *engine)
    {
        delete engine;
    }

    MnnSdError mnn_sd_load(
        MnnSdEngine *engine,
        const char *model_dir,
        const MnnSdLoadOptions *options,
        MnnSdErrorInfo *out_error)
    {
        if (!engine)
        {
            set_error(out_error, MNN_SD_ERR_INTERNAL, "engine is null");
            return MNN_SD_ERR_INTERNAL;
        }
        if (!model_dir || model_dir[0] == '\0')
        {
            set_error(out_error, MNN_SD_ERR_INVALID_PARAMS, "model_dir is required");
            return MNN_SD_ERR_INVALID_PARAMS;
        }

        engine->load_options = options ? *options : MnnSdLoadOptions{};

        mnn_sd_load_model_config(model_dir, &engine->model_config);

        // See the kDefaultOpenClSafeMaxSideSd15/Sdxl comment above: this must
        // run *after* mnn_sd_load_model_config so engine->model_config.is_sdxl
        // is already known. A caller-supplied positive value always wins.
        if (engine->load_options.opencl_safe_max_side <= 0)
        {
            engine->load_options.opencl_safe_max_side =
                engine->model_config.is_sdxl
                    ? kDefaultOpenClSafeMaxSideSdxl
                    : kDefaultOpenClSafeMaxSideSd15;
        }

        auto check_file = [&](const char *name) -> bool
        {
            char path[1024];
            std::snprintf(path, sizeof(path), "%s/%s", model_dir, name);
            FILE *f = std::fopen(path, "rb");
            if (!f)
                return false;
            std::fclose(f);
            return true;
        };

        if (!check_file(engine->model_config.unet_file))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "unet model missing",
                      engine->model_config.unet_file);
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (!check_file(engine->model_config.clip_file))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "clip model missing",
                      engine->model_config.clip_file);
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (!check_file(engine->model_config.vae_decoder_file))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "vae_decoder missing",
                      engine->model_config.vae_decoder_file);
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (!check_file(engine->model_config.tokenizer_file))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "tokenizer missing",
                      engine->model_config.tokenizer_file);
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (engine->model_config.is_sdxl)
        {
            if (engine->model_config.clip2_file[0] == '\0' ||
                !check_file(engine->model_config.clip2_file))
            {
                set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "clip2 model missing (required for sdxl)",
                          engine->model_config.clip2_file);
                return MNN_SD_ERR_MODEL_NOT_FOUND;
            }
            if (engine->model_config.tokenizer2_file[0] == '\0' ||
                !check_file(engine->model_config.tokenizer2_file))
            {
                set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "tokenizer2 missing (required for sdxl)",
                          engine->model_config.tokenizer2_file);
                return MNN_SD_ERR_MODEL_NOT_FOUND;
            }
        }

        engine->model_dir = model_dir;
        engine->loaded = false;
        std::memset(&engine->caps, 0, sizeof(engine->caps));
        engine->caps.supports_opencl = engine->load_options.backend == MNN_SD_BACKEND_OPENCL ? 1 : 0;
        engine->caps.max_side_px = engine->model_config.is_sdxl ? 1536 : 768;
        engine->caps.default_side_px = engine->model_config.default_size;
        engine->caps.clip_skip = engine->model_config.clip_skip;
        engine->caps.text_embedding_size = engine->model_config.text_embedding_size;
        std::snprintf(engine->caps.format_version, sizeof(engine->caps.format_version), "%s",
                      engine->model_config.format);

        MnnSdError init_error = mnn_sd_initialize_sessions(engine, out_error);
        if (init_error != MNN_SD_OK)
        {
            mnn_sd_release_sessions(engine);
            return init_error;
        }

        engine->loaded = true;
        return MNN_SD_OK;
    }

    void mnn_sd_unload(MnnSdEngine *engine)
    {
        if (!engine)
            return;
        mnn_sd_release_sessions(engine);
        engine->loaded = false;
        engine->model_dir.clear();
        engine->cancel_requested = false;
    }

    int mnn_sd_is_loaded(const MnnSdEngine *engine)
    {
        return engine && engine->loaded ? 1 : 0;
    }

    MnnSdError mnn_sd_generate(
        MnnSdEngine *engine,
        const MnnSdGenerateParams *params,
        MnnSdProgressFn on_progress,
        void *progress_user_data,
        MnnSdImage *out_image,
        MnnSdErrorInfo *out_error)
    {
        if (!engine || !out_image)
        {
            set_error(out_error, MNN_SD_ERR_INTERNAL, "engine or out_image is null");
            return MNN_SD_ERR_INTERNAL;
        }
        if (!engine->loaded)
        {
            set_error(out_error, MNN_SD_ERR_NOT_LOADED, "call mnn_sd_load first");
            return MNN_SD_ERR_NOT_LOADED;
        }
        if (!validate_generate_params(params, out_error))
        {
            return MNN_SD_ERR_INVALID_PARAMS;
        }

        engine->cancel_requested = false;

        return mnn_sd_run_pipeline(engine, params, on_progress, progress_user_data, out_image, out_error);
    }

    void mnn_sd_cancel(MnnSdEngine *engine)
    {
        if (engine)
            engine->cancel_requested = true;
    }

    MnnSdError mnn_sd_get_capabilities(
        const MnnSdEngine *engine,
        MnnSdCapabilities *out_caps,
        MnnSdErrorInfo *out_error)
    {
        if (!engine || !out_caps)
        {
            set_error(out_error, MNN_SD_ERR_INTERNAL, "engine or out_caps is null");
            return MNN_SD_ERR_INTERNAL;
        }
        if (!engine->loaded)
        {
            set_error(out_error, MNN_SD_ERR_NOT_LOADED, "engine not loaded");
            return MNN_SD_ERR_NOT_LOADED;
        }
        *out_caps = engine->caps;
        return MNN_SD_OK;
    }

    void mnn_sd_free_image(MnnSdImage *image)
    {
        if (!image)
            return;
        delete[] image->data;
        image->data = nullptr;
        image->data_size = 0;
    }

} // extern "C"