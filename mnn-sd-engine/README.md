# mnn-sd-engine

コードネーム: **Nezumi Kiln**（以下、本エンジンを Nezumi Kiln と呼びます）

MNN ベースの画像生成エンジン。SD1.5 の txt2img / img2img を実装済みで、SDXL は試験対応です。  
計画書: [docs/MNN_SD_ENGINE_PLAN.md](../docs/MNN_SD_ENGINE_PLAN.md)

## 目的

このモジュールは、旧 LocalDream 依存を置き換えた、自前の画像生成実行基盤です。既存の LocalDreamModule + `libstable_diffusion_core.so` から、MnnSdModule + `libmnn_sd_jni.so` への移行を完了しています。

## 現在の実装状況

### 実装済み

- C API 全体（`mnn_sd_create` / `load` / `unload` / `generate` / `cancel` / `get_capabilities` / `probe_model` / `free_image`）
- MNN セッション生成と UNet / CLIP / VAE の実推論パイプライン（`mnn_sd_run_pipeline` 経由、`src/mnn_session.cpp`）
- SD1.5 txt2img / img2img（VAE encoder があれば `supports_img2img=1` になり有効化）
- SDXL 試験対応（`model_config` の `base` フィールドで `sd1.5` / `sdxl` を切り替え、CLIP-L/CLIP-G 等の SDXL 専用フィールドに対応）
- モデル設定の自動読み込み（`model.json` からファイル名を解釈、`clip.mnn` / `clip_v2.mnn` の切り替え）
- Android JNI からのロード・生成・プローブ・エラー取得（`libmnn_sd_jni.so` → `libmnn_sd_engine.so`）
- OpenCL バックエンドと安全閾値（`opencl_safe_max_side`）、失敗時の CPU 自動フォールバック
- ホスト向け probe CLI
- I/O マップの実機確定（[tools/io_map.md](tools/io_map.md)）
- モデルライセンス確認フロー（ダウンロード前に `ImageModelBrowser.fetchLicenseInfo()` がライセンスを取得しユーザーに提示。テンプレートは [tools/MODEL_LICENSE.md](tools/MODEL_LICENSE.md)）
- HuggingFace 形式モデルの変換スクリプト（SD1.5 / SDXL 両対応、[conversion/](conversion/)）

### 今後の課題

- サンプラー選択の完全対応（Euler / DDIM / DPM++ 2M / LCM 等、現在は既定サンプラー固定。`src/mnn_session.cpp` の TODO 参照）
- SDXL の実機検証範囲の拡大（現状は試験対応）

## 構成

```text
include/mnn_sd/     C API ヘッダ
src/                コア実装（mnn_session.cpp が推論パイプライン本体）
android/jni/        JNI 実装
conversion/          HuggingFace → MNN 変換スクリプト (SD1.5 / SDXL)
optional/http_server/  Local Dream L1 互換の観測用スケルトン
tools/              プローブ CLI・I/O マップ・ライセンステンプレート
```

## 主要ファイル

- [include/mnn_sd/engine.h](include/mnn_sd/engine.h)
- [include/mnn_sd/types.h](include/mnn_sd/types.h)
- [include/mnn_sd/model_config.h](include/mnn_sd/model_config.h)
- [src/c_api.cpp](src/c_api.cpp)
- [src/model_config.cpp](src/model_config.cpp)
- [src/mnn_session.cpp](src/mnn_session.cpp) — 推論パイプライン本体
- [android/jni/mnn_sd_jni.cpp](android/jni/mnn_sd_jni.cpp)
- [tools/mnn_sd_probe.cpp](tools/mnn_sd_probe.cpp)
- [conversion/convert_hf_to_mnn_sd.py](conversion/convert_hf_to_mnn_sd.py) — SD1.5 変換
- [conversion/convert_hf_to_mnn_sdxl.py](conversion/convert_hf_to_mnn_sdxl.py) — SDXL 変換

## ビルド（Windows / Android NDK）

PowerShell 例（Android Studio 同梱の cmake / ninja / NDK を使う場合）:

```powershell
$cmake = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.31.6\bin\cmake.exe"
$ninja = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.31.6\bin\ninja.exe"
$ndk   = "$env:LOCALAPPDATA\Android\Sdk\ndk\30.0.14904198"

& $cmake -S mnn-sd-engine -B build/mnn-sd-android -G Ninja `
  -DCMAKE_MAKE_PROGRAM=$ninja `
  -DCMAKE_TOOLCHAIN_FILE="$ndk\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 `
  -DMNN_SD_BUILD_PROBE_CLI=OFF

& $cmake --build build/mnn-sd-android -j
# → build/mnn-sd-android/libmnn_sd_engine.so, libmnn_sd_jni.so
```

ビルド成果物（`libmnn_sd_engine.so` / `libmnn_sd_jni.so`）は `app/src/main/jniLibs/arm64-v8a/` に配置されます。

ホスト向けの probe CLI は Windows では Visual Studio または WSL が必要です。

## ビルド（ホスト・プローブ）

```bash
# MNN をクローン・ビルド後
export MNN_ROOT=/path/to/MNN

cmake -S mnn-sd-engine -B build/mnn-sd -DMNN_ROOT=$MNN_ROOT
cmake --build build/mnn-sd -j

./build/mnn-sd/mnn_sd_probe /path/to/model_dir/unet.mnn
./build/mnn-sd/mnn_sd_probe --backend opencl /path/to/unet.mnn
```

MNN 未指定でも CMake は通りますが、実際の MNN 依存がない場合は probe がスタブメッセージで止まります。

## モデルディレクトリ

### SD1.5

```text
modelDir/
  clip.mnn
  unet.mnn
  vae_decoder.mnn
  vae_encoder.mnn     # img2img を使う場合のみ必須
  tokenizer.json
  model.json
  NOTICE
```

### SDXL（試験対応）

`model.json` の `base` を `"sdxl"` に設定し、CLIP-L / CLIP-G 等の SDXL 専用フィールドを指定します。詳細は [include/mnn_sd/model_config.h](include/mnn_sd/model_config.h) を参照してください。

## モデル変換

HuggingFace 形式のモデルを MNN 形式に変換するスクリプトを同梱しています。

```bash
# SD1.5
python conversion/convert_hf_to_mnn_sd.py --input <hf_model_dir> --output <mnn_output_dir>

# SDXL（試験対応）
python conversion/convert_hf_to_mnn_sdxl.py --input <hf_model_dir> --output <mnn_output_dir>
```

CuteYukiMix 等、公開モデルのファイル名に合わせて配置する場合の例:

```text
models/CuteYukiMix/
  clip_v2.mnn
  unet_asym_block32.mnn
  vae_decoder_fp16.mnn
  tokenizer.json
```

## ライセンス

- エンジンコード: MIT を想定
- MNN: Apache-2.0
- モデルのライセンスは配布元ごとに異なります。アプリはダウンロード前に `ImageModelBrowser.fetchLicenseInfo()` で取得したライセンス情報をユーザーに提示し、同意なしにダウンロードしません。新しい変換モデルを公開する場合は [tools/MODEL_LICENSE.md](tools/MODEL_LICENSE.md) のテンプレートを使用してください。

## 既存 nezumi-ai との関係

| 旧 | 現行 |
|------|------|
| LocalDreamModule + `libstable_diffusion_core.so` | MnnSdModule + `libmnn_sd_jni.so` → `libmnn_sd_engine.so` |
| HTTP :18081 | JNI 直接（HTTP は `optional/http_server/` に観測用スケルトンとして残存） |

OpenCL 安全閾値 448px は [include/mnn_sd/types.h](include/mnn_sd/types.h) の `opencl_safe_max_side` にあります。OpenCL での生成が失敗した場合は CPU バックエンドへ自動フォールバックします。