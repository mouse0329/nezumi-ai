#pragma once

// Which SD sub-model the schedule bundle is for. Precision must be picked
// per model because SD1.5 VAE cannot survive full FP16 on OpenCL.
//
// 旧 mnn_session.cpp の冒頭にあった SdModelKind enum を、分割された
// セッション構築コードと他パーツが共有できるように独立ヘッダ化した。

namespace mnn_sd_detail
{

    enum class SdModelKind
    {
        CLIP,
        CLIP2, // SDXL's second text encoder (CLIP-G / OpenCLIP ViT-bigG)
        UNET,
        VAE,
        VAE_ENCODER, // img2img専用。decoder と同じ Precision_High/CPU-safe 選択を使う。
    };

} // namespace mnn_sd_detail
