#pragma once

// Session construction (ScheduleBundle / cache_file_for / create_interpreter_and_session).
//
// 旧 mnn_session.cpp のセッション構築ブロックをここへ移動した。

#include <memory>
#include <string>

#if defined(MNN_SD_HAS_MNN)
#include <MNN/Interpreter.hpp>
#endif

#include "mnn_sd/types.h"
#include "engine_detail/sd_model_kind.h"

namespace mnn_sd_detail
{

#if defined(MNN_SD_HAS_MNN)

    // Bug fix: OpenCL 推論が起動直後に abort する / 出力が真っ黒になる問題は
    // 立てていなかったこと (MNN_GPU_MEMORY_BUFFER + MNN_GPU_TUNING_FAST) と、
    // CPU 側にスレッド数 / Memory_Low ヒントを与えていなかったことが原因。
    //
    // Bug fix (GPU で真っ白画像になる問題 / 2026-07):
    //   これまで OpenCL でも Precision_Low (=全 FP16) を全モデルに強制していた。
    //   SD1.5 の VAE decoder は FP16 で中間活性が 65504 を超えて +Inf 化しやすく
    //   (madebyollin/sdxl-vae-fp16-fix が対処している SDXL の症状と同型)、
    //   最終段の [-1, 1] クランプで全ピクセルが +1.0 に潰れ、RGB=255 の真っ白
    //   画像になる。CPU バックエンドは Precision_Low でも実質 fp32 で回るため
    //   症状が出ず、「CPU では動くが GPU では真っ白」の挙動と一致する。
    //
    //   さらに UNet を Precision_Low で回すと、一部端末で cross-attention の
    //   softmax 前 logits がオーバーフローして NaN を吐き、そのまま VAE 出口で
    //   真っ白になるパスもある。
    //
    //   対処:
    //     - VAE: Precision_High (fp32) 固定。速度低下は decode 1 回分だけなので許容。
    //     - UNet: Precision_Normal (mixed: matmul は fp16、蓄積・正規化は fp32)。
    //     - CLIP: Precision_Normal (十分安定・十分速い)。
    //     - Memory_Normal / TUNING_NORMAL に緩める (Memory_Low + TUNING_FAST の
    //       組み合わせは Adreno 系で稀に activation を丸めすぎる)。
    struct ScheduleBundle
    {
        MNN::BackendConfig backend_config{};
        MNN::ScheduleConfig schedule{};

        ScheduleBundle(MnnSdBackend backend, SdModelKind kind, bool low_memory_unet);
    };

    std::string cache_file_for(const std::string &model_path, SdModelKind kind, int width = 0);

    MnnSdError create_interpreter_and_session(
        const std::string &model_path,
        MnnSdBackend backend,
        SdModelKind kind,
        bool low_memory_unet,
        std::shared_ptr<MNN::Interpreter> &interpreter,
        MNN::Session *&session,
        MnnSdErrorInfo *out_error,
        int width = 0);

#endif // MNN_SD_HAS_MNN

} // namespace mnn_sd_detail
