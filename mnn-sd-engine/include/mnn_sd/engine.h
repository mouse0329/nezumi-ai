#pragma once

/**
 * mnn-sd-engine public C API
 *
 * Design goals:
 * - Callable from JNI, optional HTTP adapter, and unit tests via the same surface.
 * - Never hide failures behind null/optional returns without MnnSdErrorInfo.
 * - Sessions are created at load() and reused across generate() calls.
 */

#include "mnn_sd/export.h"
#include "mnn_sd/types.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MnnSdEngine MnnSdEngine;

/** Opaque handle; create with mnn_sd_create, destroy with mnn_sd_destroy. */
MNN_SD_API MnnSdEngine* mnn_sd_create(void);

MNN_SD_API void mnn_sd_destroy(MnnSdEngine* engine);

/**
 * Load model directory (see README for layout).
 * Blocks until CLIP/UNet/VAE MNN sessions are ready.
 */
MNN_SD_API MnnSdError mnn_sd_load(
    MnnSdEngine* engine,
    const char* model_dir,
    const MnnSdLoadOptions* options,
    MnnSdErrorInfo* out_error);

MNN_SD_API void mnn_sd_unload(MnnSdEngine* engine);

MNN_SD_API int mnn_sd_is_loaded(const MnnSdEngine* engine);

/**
 * Blocking txt2img. Checks cancel flag at UNet step boundaries.
 * On success, *out_image is heap-allocated; free with mnn_sd_free_image.
 */
MNN_SD_API MnnSdError mnn_sd_generate(
    MnnSdEngine* engine,
    const MnnSdGenerateParams* params,
    MnnSdProgressFn on_progress,
    void* progress_user_data,
    MnnSdImage* out_image,
    MnnSdErrorInfo* out_error);

/** Request cooperative cancel for the in-flight generate(). */
MNN_SD_API void mnn_sd_cancel(MnnSdEngine* engine);

MNN_SD_API MnnSdError mnn_sd_get_capabilities(
    const MnnSdEngine* engine,
    MnnSdCapabilities* out_caps,
    MnnSdErrorInfo* out_error);

MNN_SD_API void mnn_sd_free_image(MnnSdImage* image);

/** Human-readable label for MnnSdError. */
MNN_SD_API const char* mnn_sd_error_string(MnnSdError code);

/**
 * Phase 0 utility: load one .mnn file and log all input/output tensor names + shapes.
 * Does not require a full model directory. Intended for shape discovery spikes.
 */
MNN_SD_API MnnSdError mnn_sd_probe_model(
    const char* mnn_path,
    MnnSdBackend backend,
    char* out_log,
    size_t out_log_capacity,
    MnnSdErrorInfo* out_error);

#ifdef __cplusplus
}
#endif
