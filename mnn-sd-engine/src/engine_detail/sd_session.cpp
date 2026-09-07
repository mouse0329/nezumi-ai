#include "engine_detail/sd_session.h"

#include "engine_detail/sd_log.h"
#include "engine_detail/sd_path.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <string>
#include <system_error>

#if defined(MNN_SD_HAS_MNN)

namespace mnn_sd_detail
{

    ScheduleBundle::ScheduleBundle(MnnSdBackend backend, SdModelKind kind, bool low_memory_unet)
    {
        backend_config.power = MNN::BackendConfig::Power_High;
        if (backend == MNN_SD_BACKEND_OPENCL && kind == SdModelKind::UNET)
        {
            schedule.type = MNN_FORWARD_OPENCL;
            // Bug fix #8 revert (2026-07): 全モデル TUNING_FAST に戻す。
            schedule.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
            switch (kind)
            {
            case SdModelKind::VAE:
            case SdModelKind::VAE_ENCODER:
                backend_config.precision = MNN::BackendConfig::Precision_High;
                backend_config.memory = MNN::BackendConfig::Memory_Normal;
                break;
            case SdModelKind::UNET:
                backend_config.precision = MNN::BackendConfig::Precision_High;
                backend_config.memory = low_memory_unet
                                            ? MNN::BackendConfig::Memory_Low
                                            : MNN::BackendConfig::Memory_Normal;
                break;
            case SdModelKind::CLIP:
            case SdModelKind::CLIP2:
            default:
                backend_config.precision = MNN::BackendConfig::Precision_Normal;
                backend_config.memory = MNN::BackendConfig::Memory_Normal;
                break;
            }
        }
        else if (backend == MNN_SD_BACKEND_OPENCL)
        {
            // CLIP/VAE on OpenCL: still use OpenCL forward.
            schedule.type = MNN_FORWARD_OPENCL;
            schedule.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
            switch (kind)
            {
            case SdModelKind::VAE:
            case SdModelKind::VAE_ENCODER:
                backend_config.precision = MNN::BackendConfig::Precision_High;
                backend_config.memory = MNN::BackendConfig::Memory_Normal;
                break;
            case SdModelKind::CLIP:
            case SdModelKind::CLIP2:
            default:
                backend_config.precision = MNN::BackendConfig::Precision_Normal;
                backend_config.memory = MNN::BackendConfig::Memory_Normal;
                break;
            }
        }
        else
        {
            schedule.type = MNN_FORWARD_CPU;
            schedule.numThread = 4;
            backend_config.precision = MNN::BackendConfig::Precision_Low;
            backend_config.memory = MNN::BackendConfig::Memory_Low;
        }
        schedule.backendConfig = &backend_config;
    }

    std::string cache_file_for(const std::string &model_path, SdModelKind kind, int width)
    {
        std::string dir = model_path;
        size_t slash = dir.find_last_of("/\\");
        if (slash != std::string::npos)
            dir = dir.substr(0, slash);
        const char *stage = "clip";
        switch (kind)
        {
        case SdModelKind::UNET:        stage = "unet"; break;
        case SdModelKind::VAE:         stage = "vae"; break;
        case SdModelKind::VAE_ENCODER: stage = "vae_enc"; break;
        case SdModelKind::CLIP2:       stage = "clip2"; break;
        case SdModelKind::CLIP:
        default:                       stage = "clip"; break;
        }
        std::string subdir = dir + "/cache";
        {
            std::error_code ec;
            std::filesystem::create_directories(subdir, ec);
            if (ec)
                subdir.clear();
        }
        if (!subdir.empty())
        {
            std::string name = std::string(stage) + ".mnnc";
            if (width > 0)
            {
                name += ".";
                name += std::to_string(width);
            }
            return subdir + "/" + name;
        }
        return build_model_path(dir.c_str(), (std::string("cache_") + stage + ".mnnc").c_str());
    }

    MnnSdError create_interpreter_and_session(
        const std::string &model_path,
        MnnSdBackend backend,
        SdModelKind kind,
        bool low_memory_unet,
        std::shared_ptr<MNN::Interpreter> &interpreter,
        MNN::Session *&session,
        MnnSdErrorInfo *out_error,
        int width)
    {
        if (!file_exists(model_path))
        {
            set_error(out_error, MNN_SD_ERR_MODEL_NOT_FOUND, "model file missing", model_path.c_str());
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }

        interpreter = std::shared_ptr<MNN::Interpreter>(MNN::Interpreter::createFromFile(model_path.c_str()));
        if (!interpreter)
        {
            set_error(out_error, MNN_SD_ERR_MODEL_INVALID, "failed to create MNN interpreter", model_path.c_str());
            return MNN_SD_ERR_MODEL_INVALID;
        }

        std::string cache_file;
        if (backend == MNN_SD_BACKEND_OPENCL)
        {
            cache_file = cache_file_for(model_path, kind, width);
            interpreter->setCacheFile(cache_file.c_str());
        }

        ScheduleBundle bundle(backend, kind, low_memory_unet);
        PROBE_LOG("MNN session: model=%s backend=%d kind=%d unet_low_memory=%d cache=%s",
                  model_path.c_str(), static_cast<int>(backend), static_cast<int>(kind),
                  low_memory_unet ? 1 : 0, cache_file.empty() ? "(none)" : cache_file.c_str());
        session = interpreter->createSession(bundle.schedule);
        if (!session)
        {
            interpreter.reset();
            set_error(out_error, MNN_SD_ERR_BACKEND_INIT_FAILED, "failed to create MNN session", model_path.c_str());
            return MNN_SD_ERR_BACKEND_INIT_FAILED;
        }

        if (backend == MNN_SD_BACKEND_OPENCL && !cache_file.empty())
        {
            interpreter->updateCacheFile(session);
        }
        return MNN_SD_OK;
    }

} // namespace mnn_sd_detail

#endif // MNN_SD_HAS_MNN
