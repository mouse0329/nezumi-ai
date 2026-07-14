# Local Dream HTTP 互換プロトコル（L1・観測仕様）

**ステータス**: 観測ベース / 実装は optional  
**対象クライアント**: nezumiai `LocalDreamModule`（`app/src/main/java/com/nezumi_ai/sd/LocalDreamModule.kt`）  
**完全互換は約束しない** — 差分は §6 に記載。

---

## 1. 概要

Local Dream 系サーバー（`libstable_diffusion_core.so`）は、子プロセスとして起動し `127.0.0.1:18081` で HTTP + SSE を提供する。  
自前エンジンの HTTP アダプタは **コアの `mnn_sd_generate()` を SSE に翻訳する薄い層** とし、エンジン本体は JNI 前提のまま維持する。

---

## 2. サーバー起動（参考・L2 以降）

nezumiai が現在使用している CLI 引数（MNN/CPU モード）:

```text
libstable_diffusion_core.so \
  --clip    <modelDir>/clip.mnn \
  --unet    <modelDir>/unet.mnn \
  --vae_decoder <modelDir>/vae_decoder.mnn \
  [--vae_encoder <modelDir>/vae_encoder.mnn] \
  --tokenizer <modelDir>/tokenizer.json \
  --port 18081 \
  --text_embedding_size 768 \
  --cpu
```

環境変数（nezumiai が設定）:

| 変数 | 値 | 備考 |
|------|-----|------|
| `LD_LIBRARY_PATH` | runtime + nativeLib + system | QNN 併用時は runtime 優先 |
| `MNN_OPENCL_TUNING` | `WIDE` | OpenCL チューニング幅 |

**L1 互換では CLI 起動は不要**（同一プロセス HTTP または別バイナリ `mnn_sd_server`）。

---

## 3. エンドポイント

### 3.1 `GET /` または `GET /health`

| 項目 | 値 |
|------|-----|
| 成功 | HTTP 200 |
| 失敗 | 接続拒否 / タイムアウト |

nezumiai の `waitForServer()` は **200 または 404** を「起動完了」とみなす（ルート `/` に GET）。

**L1 推奨**: `GET /health` → `200` + body `{"status":"ok"}`（任意）

### 3.2 `POST /generate`

| ヘッダ | 値 |
|--------|-----|
| `Content-Type` | `application/json` |
| `Accept` | `text/event-stream` |

**タイムアウト（クライアント側）**: connect 10s / read 600s（nezumiai 実装）

---

## 4. リクエスト JSON

nezumiai が送信するフィールド（観測）:

```json
{
  "prompt": "string",
  "negative_prompt": "string",
  "width": 512,
  "height": 512,
  "steps": 20,
  "cfg": 7.0,
  "seed": 42,
  "scheduler": "dpm",
  "use_opencl": false,
  "show_diffusion_process": false
}
```

| フィールド | 型 | 必須 | 備考 |
|-----------|-----|------|------|
| `prompt` | string | yes | |
| `negative_prompt` | string | no | 空文字可 |
| `width` | int | yes | 64 の倍数推奨 |
| `height` | int | yes | 64 の倍数推奨 |
| `steps` | int | yes | |
| `cfg` | float | yes | classifier-free guidance |
| `seed` | int | yes | 負値はクライアントがランダム化してから送ることもある |
| `scheduler` | string | no | nezumiai は `"dpm"` 固定 |
| `use_opencl` | bool | no | `max(w,h) > 448` のときクライアントが強制 `false` |
| `show_diffusion_process` | bool | no | `true` で中間プレビュー SSE が増える可能性 |

**L1 で無視してよいフィールド**: `show_diffusion_stride` 等、クライアントが送らないもの。

**L1 でサポートすべき**: 上表の必須 + `use_opencl` + `scheduler`（`dpm` のみでも可）。

---

## 5. レスポンス（SSE）

形式: [Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html)

```
event: <optional>
data: <json>

```

クライアントは `data:` 行の JSON をパースし、`type` フィールド（または直前の `event:`）で分岐する。

### 5.1 進捗 `type: "progress"` または `type: "preview"`

```json
{
  "type": "progress",
  "step": 3,
  "total_steps": 22
}
```

| フィールド | 型 | 備考 |
|-----------|-----|------|
| `step` | int | 現在ステップ（0 始まりのことあり） |
| `total_steps` | int | **リクエスト steps と一致しない場合あり**（セットアップ分が加算） |

nezumiai は `total_steps > requested_steps` のとき正規化する（`LocalDreamModule.normalizeServerProgress`）。

`preview` は `progress` と同様に扱われる。

### 5.2 完了 `type: "complete"`

```json
{
  "type": "complete",
  "image": "<base64-encoded raw RGB>",
  "width": 512,
  "height": 512
}
```

| フィールド | 型 | 備考 |
|-----------|-----|------|
| `image` | string | **raw RGB**（PNG ではない）。Base64、パディングなし可 |
| `width` | int | デコード後の幅 |
| `height` | int | デコード後の高さ |

デコード: `byte[]` 長さ = `width * height * 3`、ピクセル順は row-major RGB。

### 5.3 エラー（観測不足）

Local Dream バイナリは HTTP 非 200 や SSE 内エラーイベントの挙動がビルド依存。  
**L1 自前実装の推奨**:

- パラメータ不正 → HTTP 400 + `{"error":"..."}`（SSE なし）
- 生成中エラー → SSE `{"type":"error","message":"..."}` の後にストリーム終了
- キャンセル → クライアントが接続を切断（nezumiai `cancelGeneration()` は `HttpURLConnection.disconnect()`）

---

## 6. 既知の差分・非互換（意図的）

| 項目 | Local Dream（現行） | 自前エンジン L1 |
|------|---------------------|-----------------|
| プロセス | 子プロセス | 同一プロセス JNI 本命 / HTTP は optional |
| QNN | `--backend libQnnHtp.so` | Phase 0–3 では非対応 |
| `clip_v2.mnn` | 対応 | `model.json` で clip パス規約を固定 |
| `total_steps` 正規化 | サーバー依存 | エンジンは `steps` をそのまま報告する方針 |
| プレビュー画像 | `show_diffusion_process` | L1 では省略可 |
| `/health` | 未使用（`/` で代替） | 実装するなら 200 を返す |

---

## 7. L1 互換テスト手順

1. nezumiai の `LocalDreamModule.SERVER_PORT`（18081）に自前 `mnn_sd_server` をバインド
2. `loadModel()` をスキップし、サーバーを手動起動して `generateImage()` のみ実行
3. 確認項目:
   - [ ] `POST /generate` が SSE で `complete` を返す
   - [ ] Base64 RGB が `width x height x 3` バイトにデコードできる
   - [ ] `progress` が 0→steps 付近まで届く
   - [ ] 接続切断で生成が止まる（または短時間で終了）

---

## 8. 将来拡張（L1 外）

- img2img: `init_image` base64 フィールド（Local Dream 拡張版）
- inpaint: mask フィールド
- WebSocket ではなく SSE のまま維持（クライアント互換のため）
