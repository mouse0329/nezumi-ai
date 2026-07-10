# safetensors → MNN 変換メモ（Phase 0+）

## 目的

xororz 配布 zip に依存せず、**再現可能な手順**で SD1.5 用 `.mnn` を用意する。

## 前提

- 元チェックポイントのライセンス確認済み（`MODEL_LICENSE.md`）
- MNN 変換ツールチェーン（バージョンを `NOTICE` に記載）

## 手順（ドラフト）

1. safetensors を取得（CivitAI / HF）
2. ONNX または MNN 公式フローで分割エクスポート
   - `clip`（text encoder）
   - `unet`
   - `vae_decoder`（必要なら `vae_encoder`）
3. 各サブモデルを `.mnn` に変換
4. `tokenizer.json` を SD1.5 CLIP 用に配置
5. `model.json` を作成（`format: mnn-sd15-v1`）
6. Phase 0 プローブで I/O 名を確認:

```bash
# Android デバイス or ホストビルド
mnn_sd_probe --backend cpu /path/to/unet.mnn
```

7. 固定 seed スモーク（steps=5, 512x512）で目視確認

## model.json 例

```json
{
  "format": "mnn-sd15-v1",
  "base": "sd1.5",
  "clip": "clip.mnn",
  "unet": "unet.mnn",
  "vae_decoder": "vae_decoder.mnn",
  "tokenizer": "tokenizer.json",
  "clip_skip": 2,
  "text_embedding_size": 768,
  "default_size": 512,
  "license_notice": "See NOTICE"
}
```

## I/O テンソル名

変換パイプラインごとに名前がぶれるため、**変換時に固定**し `model.json` の `io_map` に書く（Phase 1 でエンジンが読む）。

## NOTICE テンプレート

配布 zip には最低限:

- 元モデル名・ライセンス
- MNN バージョン
- 変換日・変換者
