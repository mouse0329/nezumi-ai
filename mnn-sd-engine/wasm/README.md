# mnn-sd-engine WASM

このディレクトリは `mnn-sd-engine` の C API を Emscripten でブラウザから呼び出すためのラッパーです。推論は CPU バックエンドで実行します。OpenCL は Web ブラウザでは利用できないため、ブラウザ経路では `backend=cpu` を使います。

## ビルド

リポジトリルートで次を実行します。

```bash
source /home/ubuntu/emsdk/emsdk_env.sh

emcmake cmake -S third_party/MNN -B build/mnn-wasm -G Ninja \
  -DMNN_BUILD_SHARED_LIBS=OFF \
  -DMNN_SEP_BUILD=OFF \
  -DMNN_BUILD_DEMO=OFF \
  -DMNN_BUILD_TOOLS=OFF \
  -DMNN_BUILD_CONVERTER=OFF \
  -DMNN_BUILD_QUANTOOLS=OFF \
  -DMNN_BUILD_TRAIN=OFF \
  -DMNN_BUILD_DIFFUSION=OFF \
  -DMNN_BUILD_LLM=OFF \
  -DMNN_BUILD_OPENCV=OFF \
  -DMNN_USE_SSE=OFF \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/mnn-wasm -j2

emcmake cmake -S mnn-sd-engine -B build/mnn-sd-wasm -G Ninja \
  -DMNN_ROOT=$PWD/third_party/MNN \
  -DMNN_LIBRARY=$PWD/build/mnn-wasm/libMNN.a \
  -DMNN_SD_BUILD_SHARED=OFF \
  -DMNN_SD_BUILD_PROBE_CLI=OFF \
  -DMNN_SD_BUILD_HTTP_SERVER=OFF \
  -DMNN_SD_BUILD_WASM=ON \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/mnn-sd-wasm -j2
mkdir -p public/nezumiaiweb/wasm
cp build/mnn-sd-wasm/mnn_sd_wasm.js build/mnn-sd-wasm/mnn_sd_wasm.wasm public/nezumiaiweb/wasm/
```

## Web Worker API

`public/nezumiaiweb/mnn-worker.js` は次のメッセージを受け付けます。

```js
const worker = new Worker('./mnn-worker.js', { type: 'module' });
worker.postMessage({ type: 'init' });
worker.postMessage({
  type: 'load',
  backend: 'cpu',
  files: [
    { name: 'clip.mnn', bytes: clipBytes.buffer },
    { name: 'unet.mnn', bytes: unetBytes.buffer },
    { name: 'vae_decoder.mnn', bytes: vaeBytes.buffer },
    { name: 'tokenizer.json', bytes: tokenizerBytes.buffer },
    // model.json や token_emb.bin / pos_emb.bin が必要なモデルでは追加する
  ],
});
worker.postMessage({
  type: 'generate',
  prompt: 'a small mouse in a watercolor forest',
  negativePrompt: '',
  width: 512,
  height: 512,
  steps: 20,
  cfg: 7.5,
  seed: 42,
  scheduler: 0,
});
```

`result` メッセージの `pixels` は RGB の `Uint8Array` バッファです。Canvas へ描画する場合は RGBA に変換して `ImageData` を作成します。

## モデル

SD1.5 の MNN モデルディレクトリをブラウザ側で仮想パス `/model` に展開します。`mnn_sd_load` の実装が要求するファイルは、モデル構成に応じて `unet.mnn`、`clip.mnn`、`vae_decoder.mnn`、`tokenizer.json`、および埋め込みテーブルなどです。モデルは配布元のライセンスを確認してから Web 配布してください。
