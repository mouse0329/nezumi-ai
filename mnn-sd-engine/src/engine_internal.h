#pragma once

#include "mnn_sd/engine.h"
#include "mnn_sd/model_config.h"

#include <memory>
#include <string>

namespace MNN {
class Interpreter;
class Session;
}  // namespace MNN

struct MnnSdEngine {
    bool loaded = false;
    std::string model_dir;
    MnnSdLoadOptions load_options{};
    MnnSdCapabilities caps{};
    MnnSdModelConfig model_config{};
    volatile bool cancel_requested = false;

    std::shared_ptr<MNN::Interpreter> clip_interpreter;
    std::shared_ptr<MNN::Interpreter> unet_interpreter;
    std::shared_ptr<MNN::Interpreter> vae_interpreter;

    MNN::Session* clip_session = nullptr;
    MNN::Session* unet_session = nullptr;
    MNN::Session* vae_session = nullptr;
};

MnnSdError mnn_sd_initialize_sessions(MnnSdEngine* engine, MnnSdErrorInfo* out_error);
void mnn_sd_release_sessions(MnnSdEngine* engine);
