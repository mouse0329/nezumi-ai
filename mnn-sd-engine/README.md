# mnn-sd-engine

MNN ベースの SD1.5 txt2img エンジン。Phase 0 の土台と、Android から読み込める JNI 統合までを実装した引き継ぎ用ドキュメントです。  
計画書: [docs/MNN_SD_ENGINE_PLAN.md](../docs/MNN_SD_ENGINE_PLAN.md)

## 目的

このモジュールは、既存の LocalDream 依存を段階的に置き換えるための、自前の SD1.5 実行基盤です。現在は「ロードできる」「モデル情報を確認できる」段階で、実際の UNet 推論はまだ未実装です。

## 現在の実装状況

### 実装済み

- C API の骨格
  - create / destroy
  - load / unload
  - generate / cancel
  - get_capabilities
  - probe_model
- モデル設定の自動読み込み
  - model.json からファイル名を解釈
  - clip.mnn / clip_v2.mnn の切り替え
- Android JNI からのロード・プローブ・エラー取得
- ホスト向け probe CLI
- OpenCL 安全閾値の設定項目

### まだ未実装

- MNN セッションの実体生成
- UNet / CLIP / VAE の実推論パイプライン
- 画像生成の実データ返却
- 実機でのモデル I/O 名固定作業
- モデルライセンスの正式転記

> いまの実装では、generate() は Phase 1 へ進むためのスタブで、実際には内部エラーを返します。

## 構成

```text
include/mnn_sd/     C API ヘッダ
src/                コア実装
android/jni/        JNI 実装
optional/http_server/  Local Dream L1 互換の観測用スケルトン
tools/              プローブ CLI・変換メモ
```

## 主要ファイル

- [include/mnn_sd/engine.h](include/mnn_sd/engine.h)
- [include/mnn_sd/types.h](include/mnn_sd/types.h)
- [src/c_api.cpp](src/c_api.cpp)
- [src/model_config.cpp](src/model_config.cpp)
- [src/mnn_session.cpp](src/mnn_session.cpp)
- [android/jni/mnn_sd_jni.cpp](android/jni/mnn_sd_jni.cpp)
- [tools/mnn_sd_probe.cpp](tools/mnn_sd_probe.cpp)

## 引き継ぎ用チェックリスト

- [x] C API ヘッダ草案 (`engine.h`, `types.h`)
- [x] HTTP 観測仕様 (`optional/http_server/protocol.md`)
- [x] CMake + JNI スタブ
- [x] Android arm64 ビルド用の共有ライブラリ骨格
- [x] App 側の MnnSdNative / MnnSdModule 連携入口
- [x] MNN 依存がない環境でもビルドできるようにしたこと
- [ ] `unet.mnn` / `clip.mnn` / `vae_decoder.mnn` の I/O 名を `tools/io_map.md` に固定
- [ ] CuteYuki 元ライセンスを `tools/MODEL_LICENSE.md` に転記
- [ ] MNN セッション生成と実推論を実装する

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

## モデルディレクトリ（MVP）

```text
modelDir/
  clip.mnn
  unet.mnn
  vae_decoder.mnn
  tokenizer.json
  model.json
  NOTICE
```

## 次の着手ポイント

1. [src/mnn_session.cpp](src/mnn_session.cpp) で MNN セッション生成を実装する
2. [tools/io_map.md](tools/io_map.md) に実機プローブ結果を反映する
3. C API の generate() を実際の推論ループへ接続する
4. Android 側から画像生成結果を受け取れるようにする

## CuteYukiMix モデルでのテスト手順

公開モデルのファイル名に合わせて、次のようなディレクトリ構成で配置すると読み込み準備ができます。

```text
models/CuteYukiMix/
  clip_v2.mnn
  unet_asym_block32.mnn
  vae_decoder_fp16.mnn
  tokenizer.json
```

ダウンロード例:

```powershell
& 'C:\Users\mouse\AppData\Local\Programs\Python\Python313\python.exe' .\download_cute_yuki.py
```

その後、モデルディレクトリを引数にしてロードテストを実行します。

## ライセンス

- エンジンコード: MIT を想定
- MNN: Apache-2.0
- 配布時は NOTICE / THIRD_PARTY_NOTICES へ整理する

## 既存 nezumiai との関係

| 現行 | 将来 |
|------|------|
| LocalDreamModule + libstable_diffusion_core.so | MnnSdModule + libmnn_sd_jni.so |
| HTTP :18081 | JNI 直接（HTTP は optional） |

OpenCL 安全閾値 448px は [include/mnn_sd/types.h](include/mnn_sd/types.h) の `opencl_safe_max_side` へ移設済みです。
