/**
 * iOS 向け MNN SD ブリッジの入口メモ。
 *
 * mnn-sd-engine は既にプラットフォーム非依存の C API (`include/mnn_sd/engine.h`) を持つ。
 * iOS 側はこのヘッダを Objective-C++ から直接呼ぶだけでよい。
 *
 * 完成基準 (Phase 4):
 * - SD1.5 txt2img / CPU
 * - モデルディレクトリレイアウトは Android と同じ
 *
 * 後段:
 * - img2img (init image)
 * - SDXL
 * - Metal バックエンド (現状の MnnSdBackend は CPU / OpenCL のみ。Metal は別途追加)
 *
 * 呼び出し例 (概念):
 *   MnnSdEngine* e = mnn_sd_create();
 *   MnnSdLoadOptions opt = { MNN_SD_BACKEND_CPU, 448 };
 *   mnn_sd_load(e, modelDir, &opt, &err);
 *   mnn_sd_generate(e, &params, progressCb, user, &image, &err);
 *   mnn_sd_free_image(&image);
 *   mnn_sd_destroy(e);
 */
#pragma once

#include "mnn_sd/engine.h"
