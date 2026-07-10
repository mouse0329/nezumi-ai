# MNN モデル I/O マップ（Phase 0 で埋める）

変換パイプライン確定後、各 `.mnn` のテンソル名をここに固定する。

## unet.mnn

| 方向 | 名前 | shape | dtype |
|------|------|-------|-------|
| input | _TBD_ | _TBD_ | _TBD_ |
| output | _TBD_ | _TBD_ | _TBD_ |

## clip.mnn

| 方向 | 名前 | shape | dtype |
|------|------|-------|-------|
| input | _TBD_ | _TBD_ | _TBD_ |
| output | _TBD_ | _TBD_ | _TBD_ |

## vae_decoder.mnn

| 方向 | 名前 | shape | dtype |
|------|------|-------|-------|
| input | _TBD_ | _TBD_ | _TBD_ |
| output | _TBD_ | _TBD_ | _TBD_ |

## 取得コマンド

```bash
mnn_sd_probe /path/to/unet.mnn
mnn_sd_probe /path/to/clip.mnn
mnn_sd_probe /path/to/vae_decoder.mnn
```

Android では `MnnSdNative.probeModel(path, backend)` の戻り値を logcat に出す。
