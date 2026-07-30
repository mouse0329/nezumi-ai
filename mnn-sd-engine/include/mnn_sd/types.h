#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

    /** Stable error codes returned by the public C API. */
    typedef enum MnnSdError
    {
        MNN_SD_OK = 0,
        MNN_SD_ERR_MODEL_NOT_FOUND = 1,
        MNN_SD_ERR_MODEL_INVALID = 2,
        MNN_SD_ERR_NOT_LOADED = 3,
        MNN_SD_ERR_INVALID_PARAMS = 4,
        MNN_SD_ERR_OUT_OF_MEMORY = 5,
        MNN_SD_ERR_BACKEND_INIT_FAILED = 6,
        MNN_SD_ERR_CANCELLED = 7,
        MNN_SD_ERR_INTERNAL = 8,
    } MnnSdError;

    typedef enum MnnSdBackend
    {
        MNN_SD_BACKEND_CPU = 0,
        MNN_SD_BACKEND_OPENCL = 1,
    } MnnSdBackend;

    /**
     * Scheduler (sampler) types for the denoising process.
     * These control the noise schedule and step algorithm in the UNet sampling loop.
     */
    typedef enum MnnSdScheduler
    {
        MNN_SD_SCHEDULER_EULER = 0,
        MNN_SD_SCHEDULER_DDIM = 1,
        MNN_SD_SCHEDULER_DPM = 2,
        MNN_SD_SCHEDULER_DPM_PP_2M = 3,
        MNN_SD_SCHEDULER_DPM_PP_2M_KARRAS = 4,
        MNN_SD_SCHEDULER_LCM = 5,
        MNN_SD_SCHEDULER_EULER_A = 6,
        MNN_SD_SCHEDULER_UNIPC = 7,
    } MnnSdScheduler;

    typedef struct MnnSdLoadOptions
    {
        MnnSdBackend backend;
        int32_t opencl_safe_max_side; /**< 0 = engine default (448) */
        /**
         * OpenCL の UNet に MNN Memory_Low を要求する。
         *
         * CuteYukiMix の UNet は一部のモバイル GPU で FP16 overflow を起こす
         * ため、演算精度は FP32 のまま維持する。
         */
        int32_t precision_low;
        const char *opencl_tuning;    /**< e.g. "WIDE", "FAST"; may be NULL */
    } MnnSdLoadOptions;

    typedef struct MnnSdGenerateParams
    {
        const char *prompt;
        const char *negative_prompt;
        int32_t width;
        int32_t height;
        int32_t steps;
        float cfg_scale;
        int64_t seed; /**< negative = random */
        MnnSdScheduler scheduler;
        int32_t use_opencl; /**< honored only if loaded with OpenCL-capable backend */

        /**
         * img2img (Stable Diffusion 1.5) 用パラメータ。
         *
         * `init_image_rgb` が非 NULL かつ `init_image_width` / `init_image_height`
         * が正のとき、パイプラインは img2img モードで走る:
         *   1. init_image を VAE encoder で latent に変換
         *   2. denoise_strength(0..1) に応じたステップ数 (steps * strength)
         *      だけ denoise
         *   3. 通常の VAE decoder で RGB 化
         *
         * init_image のサイズは `width` / `height` と一致し、8 の倍数であること。
         * ユーザー側 (Kotlin) が事前にリサイズ/トリミングして揃える。
         *
         * VAE encoder がロードされていないモデル (vae_encoder_fp16.mnn が
         * 存在しない) では `MNN_SD_ERR_MODEL_NOT_FOUND` が返る。
         *   → 呼び出し側は mnn_sd_get_capabilities().supports_img2img を
         *   事前確認し、0 なら init_image を渡さないこと。
         */
        const uint8_t *init_image_rgb;  /**< row-major RGB, tightly packed (w*h*3 bytes). NULL = txt2img. */
        int32_t init_image_width;
        int32_t init_image_height;
        float denoise_strength;         /**< 0.0 = init そのまま, 1.0 = 完全な txt2img。範囲 [0, 1]。 */
    } MnnSdGenerateParams;

    typedef struct MnnSdProgress
    {
        int32_t step;
        int32_t total_steps;
        float elapsed_sec;
    } MnnSdProgress;

    typedef void (*MnnSdProgressFn)(const MnnSdProgress *progress, void *user_data);

    typedef struct MnnSdImage
    {
        int32_t width;
        int32_t height;
        int32_t channels; /**< always 3 (RGB) */
        uint8_t *data;    /**< row-major RGB; owned by caller after mnn_sd_free_image */
        size_t data_size;
    } MnnSdImage;

    typedef struct MnnSdCapabilities
    {
        int32_t supports_opencl;
        int32_t max_side_px;
        int32_t default_side_px;
        int32_t clip_skip;
        int32_t text_embedding_size;
        char format_version[32];
        /**
         * 1: vae_encoder が見つかっており img2img が使える
         * 0: エンコーダなし → txt2img のみ (Kotlin/UI 側で img2img UI を無効化する)
         */
        int32_t supports_img2img;
    } MnnSdCapabilities;

    typedef struct MnnSdErrorInfo
    {
        MnnSdError code;
        char message[256];
        char cause[256];
    } MnnSdErrorInfo;

#ifdef __cplusplus
}
#endif
