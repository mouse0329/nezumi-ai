# Nezumi AI Mini App SDK ガイド（Platform v1.1）

> Mini App は Web アプリに近い開発体験を持ちつつ、AI・モデル・ファイル・Tool・MCP などの強力な機能を Nezumi Runtime を介して安全に利用できる、Nezumi AI 上で動作するサンドボックス型 Web アプリケーションです。

本ドキュメントは Mini App **開発者向け** の SDK リファレンス＋チュートリアルです。プラットフォーム仕様の網羅的な定義は別紙「Nezumi AI Mini App Platform 仕様 v1.1」を参照してください。

---

## 1. はじめに

### 1.1 Mini App とは

```
Mini App (HTML/CSS/JS, TS, React, Vue, Svelte)
  ↓
WebView
  ↓
Nezumi AI Local Runtime
  ├─ AI（LLM 推論・画像生成）
  ├─ Tools（ビルトイン / MCP）
  └─ Files（App Data）
```

- フレームワークは自由。**ビルド済みの静的成果物**（HTML/CSS/JS）を WebView で実行します
- すべての Mini App は **インストール（installed）必須** です（v1.1 で temporary 実行は廃止）
- ネイティブ機能には `nezumi` JavaScript SDK 経由でのみアクセスします

### 1.2 パッケージ構成

`miniapp.zip` として配布します。

```
miniapp.zip
├── manifest.json      ← 必須。アプリ定義（署名対象）
├── index.html         ← エントリポイント
├── assets/ js/ css/ icons/
└── signature.json     ← 任意。Ed25519 署名（無い場合は Dev Mode が必要）
```

### 1.3 manifest.json

```json
{
  "id": "com.example.aiapp",
  "name": "Example AI App",
  "version": "1.0.0",
  "publisher": "Example",
  "entry": "index.html",
  "permissions": ["ai", "storage", "files.read", "files.write", "image.generate"]
}
```

| フィールド | 必須 | 説明 |
|---|---|---|
| `id` | ✅ | リバースドメイン形式（例: `com.example.aiapp`） |
| `name` | ✅ | 表示名 |
| `version` | ✅ | バージョン文字列 |
| `publisher` | — | 公開者名 |
| `entry` | ✅ | エントリ HTML（例: `index.html`） |
| `permissions` | — | 要求する権限の配列（後述） |

### 1.4 権限（permissions）

Manifest に宣言しない権限は実行時に `PERMISSION_NOT_DECLARED` になります。

| 権限 | 有効にする API |
|---|---|
| `ai` | `nezumi.ai.*`（モデルロード・推論）、`nezumi.onnx.*` |
| `tools.list` / `tools.call` / `tools.register` | `nezumi.tools.*` |
| `models.list` | モデル一覧の参照 |
| `image.generate` | `nezumi.image.generate` |
| `download` | `nezumi.download.create` |
| `storage` | `nezumi.storage` の書き込み系 |
| `files.read` / `files.write` | `nezumi.files` の読み/書き |
| `mcp.list` | `nezumi.mcp.*` |
| `camera` / `microphone` | `getUserMedia`（Web 標準） |

### 1.5 インストールと起動

Mini App のインストール・起動・削除は **Mini App Manager**（本体のハンバーガーメニュー → Mini Apps）からのみ行えます。

- 署名済み・信頼済み鍵 → そのままインストール
- 未署名・未信頼鍵 → 本体設定の「Mini App 開発者モード」が有効な場合のみ、警告ダイアログへの同意後にインストール可能
- 削除すると App Data（設定・キャッシュ・ユーザーデータ）もすべて削除されます

---

## 2. SDK の基本

Mini App のページには実行前に `window.nezumi` が注入されます。すべての API は Promise を返します。

```js
const info = await nezumi.app.getInfo();
// { id, name, version, publisher, mode: "installed" }
```

### エラーハンドリング

失敗時は `code` プロパティ付きの Error で reject されます。

```js
try {
  await nezumi.ai.generate({ model: "...", prompt: "..." });
} catch (e) {
  console.error(e.code, e.message); // 例: PERMISSION_DENIED, MEMORY_PRESSURE_WARNING ...
}
```

主要なエラーコード: `PERMISSION_DENIED` / `PERMISSION_NOT_DECLARED` / `MODEL_NOT_FOUND` / `MODEL_LOAD_FAILED` / `MEMORY_PRESSURE` / `MEMORY_PRESSURE_WARNING` / `CONTENT_POLICY_VIOLATION` / `SAFETY_MODEL_UNAVAILABLE` / `FILE_ACCESS_DENIED` / `FILE_NOT_FOUND` / `RESOLUTION_OUT_OF_RANGE` / `DOWNLOAD_FAILED` / `METHOD_NOT_FOUND`

---

## 3. nezumi.app — アプリ・本体情報

```js
// Mini App 自身の情報
const app = await nezumi.app.getInfo();
// { id: "com.example.aiapp", name: "...", version: "1.0.0", publisher: "...", mode: "installed" }

// 実行ランタイム情報
const rt = await nezumi.app.getRuntimeInfo();
// { appId, appVersion, runtimeId, mode: "installed", origin }

// Nezumi AI クライアント（本体）の情報。機能の対応状況判定に使えます
const host = await nezumi.app.getHostInfo();
// { appName: "Nezumi AI", packageName, versionName: "2.3.3", versionCode: 21,
//   miniAppPlatformVersion: "1.1", sdkInt: 34 }

// アプリを閉じる
await nezumi.app.close();
```

**本体バージョンで機能分岐する例:**

```js
const host = await nezumi.app.getHostInfo();
if (host.miniAppPlatformVersion >= "1.1") {
  // v1.1 の API が使える
}
```

---

## 4. nezumi.ai — LLM 推論

### 4.1 基本的な流れ

**必ず `loadModel` でロードしてから `generate` / `stream` を呼んでください。**

```js
// 1. モデル一覧を取得（端末にダウンロード済みのもののみ）
const models = await nezumi.ai.listModels();
const modelId = models[0].id;

// 2. ロード（メモリ不足時は MEMORY_PRESSURE_WARNING）
try {
  await nezumi.ai.loadModel({ id: modelId });
} catch (e) {
  if (e.code === "MEMORY_PRESSURE_WARNING") {
    // 警告を承知で強行する場合
    await nezumi.ai.loadModel({ id: modelId, allowLowMemory: true, memorySafety: "force" });
  } else {
    throw e;
  }
}

// 3. 生成
const res = await nezumi.ai.generate({
  model: modelId,
  prompt: "こんにちは",
  temperature: 0.7,
  maxTokens: 512
});
console.log(res.text);
```

### 4.2 ストリーミング

```js
const res = await nezumi.ai.stream(
  { model: modelId, prompt: "自己紹介して" },
  (delta, done) => {
    if (!done) process.stdout.write(delta);
  }
);
// res.requestId でキャンセル可能
await nezumi.ai.stop(res.requestId);
```

### 4.3 推論パラメータ（GenerateOptions）

| パラメータ | 説明 |
|---|---|
| `model` | モデル ID（必須） |
| `prompt` | プロンプト（必須） |
| `contextLength` / `maxContextLength` | コンテキスト長 |
| `temperature` / `topP` / `topK` / `minP` / `repeatPenalty` | サンプリング |
| `maxTokens` / `stop` / `seed` | 生成制御 |
| `allowLowMemory` / `memorySafety` | メモリガード（`strict` 既定 / `warn` / `force`） |

省略時は本体アプリのユーザー設定がデフォルトになります（設定優先順位: Factory Default → User Settings → Mini App Config → Per-Request）。

---

## 5. nezumi.image — 画像生成

```js
// モデル一覧（プリインストール/システム提供のみ。Mini App からの DL は不可）
const models = await nezumi.image.listModels();
// [{ id, name, type: "sd1.5"|"sdxl", supportedSchedulers, supportedBackends }]

const result = await nezumi.image.generate({
  model: models[0].id,
  prompt: "a cat sitting on a desk",
  negativePrompt: "blurry, low quality",
  width: 512,          // SD1.5: 256–512 / SDXL: 640–1024（範囲外は RESOLUTION_OUT_OF_RANGE）
  height: 512,
  steps: 28,
  cfgScale: 7.5,
  scheduler: "euler_a" // euler / euler_a / ddim / dpm / dpmpp_2m / dpmpp_2m_karras / lcm / unipc
});
// result.image = "data:image/png;base64,..."（そのまま <img src> に使えます）
document.getElementById("img").src = result.image;
```

進捗イベントの購読:

```js
nezumi.events.on("image.progress", (p) => {
  console.log(`${p.step}/${p.totalSteps}`);
});
```

**Safety Pipeline（重要）:** 生成は必ず本体の安全対策を経由します。ブロック時は `CONTENT_POLICY_VIOLATION`（理由の詳細は開示されません）、安全対策コンポーネントが利用不可の場合は `SAFETY_MODEL_UNAVAILABLE` が返ります。Mini App 側から安全対策をスキップ・調整する方法はありません。

---

## 6. nezumi.storage — キーバリューストア

App Data 内に永続化される小規模 KV ストアです（JSON 値を格納可能）。

```js
await nezumi.storage.set("settings", { theme: "dark", count: 3 });
const v = await nezumi.storage.get("settings");   // { theme: "dark", count: 3 }
await nezumi.storage.has("settings");             // true
await nezumi.storage.keys();                      // ["settings"]
await nezumi.storage.delete("settings");
await nezumi.storage.clear();

// ストレージ使用量の確認
const usage = await nezumi.storage.getUsage();
// { totalBytes, settingsBytes, cacheBytes, userDataBytes }
```

> 本体の Mini App Manager「情報」画面からも使用量の確認と App Data の初期化ができます。

---

## 7. nezumi.files — ファイル API

App Data 内のファイルを操作します。Package への書き込み、他アプリのデータ、Android 内部パスへのアクセスは禁止されています。

```js
// 書き込み（文字列 or ArrayBuffer）
await nezumi.files.write("user-data/notes/hello.txt", "こんにちは");

// 読み込み
const text = await nezumi.files.readText("user-data/notes/hello.txt");
const buf  = await nezumi.files.read("user-data/notes/hello.txt"); // ArrayBuffer

// 一覧・存在確認・削除
const entries = await nezumi.files.list("user-data");  // [{ name, isDirectory, size, lastModified }]
await nezumi.files.exists("user-data/notes/hello.txt");
await nezumi.files.delete("user-data/notes/hello.txt");
await nezumi.files.stat("user-data/notes/hello.txt");
```

- `models/` へのパスは **読み出しのみ** 可能で、グローバルモデルストレージに解決されます。書き込みは `FILE_ACCESS_DENIED` になります（モデルの配置は必ず `nezumi.models` / 本体管理経由）。

---

## 8. nezumi.download — ダウンロード API

App Data 内へのファイルダウンロード。中断・再開に対応します。

```js
const dl = await nezumi.download.create({
  url: "https://example.com/data.zip",
  destPath: "user-data/data.zip"   // App Data 内の保存先
});
await nezumi.download.start(dl.id);

// 進捗イベント
nezumi.events.on("download.progress", (p) => {
  if (p.id === dl.id) console.log(p.state, p.bytesDownloaded, "/", p.totalBytes);
});

await nezumi.download.pause(dl.id);   // 一時停止（Range 再開に対応）
await nezumi.download.resume(dl.id);  // 再開
await nezumi.download.cancel(dl.id);  // キャンセル

const info = await nezumi.download.get(dl.id);
const all = await nezumi.download.list();
```

---

## 9. nezumi.models / nezumi.engines — モデルとエンジン

```js
// モデル一覧（nezumi.ai.listModels と同等）
const models = await nezumi.models.list();
await nezumi.models.get("gemma-4-2b.litertlm");
await nezumi.models.exists("gemma-4-2b.litertlm");

// エンジンとバックエンド
const engines = await nezumi.engines.list();      // llama.cpp / litert / image
const backends = await nezumi.engines.listBackends("llama.cpp");
// [{ id: "cpu", available: true }, { id: "vulkan", available: false, reason: "DRIVER_NOT_FOUND" }]

// メモリプローブ（ロード前の確認用）
const probe = await nezumi.engines.probeMemory();
// { canLoad, warning, pressureLevel: "low"|"medium"|"high"|"critical", memory: { totalMemory, availableMemory } }
```

---

## 10. nezumi.onnx — ONNX ランタイム

低レベル推論が必要な場合の ONNX API です（権限 `ai` が必要）。セッション・テンソルはアプリ終了時に自動解放されます。

```js
// セッションを開く（App Data またはグローバルモデルストレージ内の .onnx）
const sessionId = await nezumi.onnx.open({ model: "models/my-model.onnx" });

// 入出力情報
const inputs  = await nezumi.onnx.getInputs(sessionId);   // [{ name, shape, dtype }]
const outputs = await nezumi.onnx.getOutputs(sessionId);

// テンソル作成（float32, little-endian の ArrayBuffer）
const data = new Float32Array([1, 2, 3, 4]).buffer;
const tensorId = await nezumi.onnx.createTensor(sessionId, [1, 4], data);

// 推論
const result = await nezumi.onnx.run(sessionId, { input: tensorId });

// 解放
await nezumi.onnx.disposeTensor(tensorId);
await nezumi.onnx.close(sessionId);
```

> メモリガード: テンソル確保の合計が上限を超えると `MEMORY_PRESSURE` になります。

---

## 11. nezumi.tools / nezumi.mcp — ツールと MCP

```js
// 利用可能なツール一覧（builtin / mcp / miniapp）
const tools = await nezumi.tools.list();

// ツール呼び出し（例: MCP ツール）
const result = await nezumi.tools.call("mcp__xxxxxxxx__weather", { city: "Tokyo" });

// MCP サーバー・ツールの参照（権限 mcp.list）
const servers = await nezumi.mcp.listServers();
const mcpTools = await nezumi.mcp.listTools(servers[0]?.id);

// Mini App 独自ツールの登録（権限 tools.register、ランタイム中のみ有効）
await nezumi.tools.register({
  name: "myapp.summarize",
  description: "テキストを要約する",
  parameters: { type: "object", properties: { text: { type: "string" } } }
});
```

---

## 12. nezumi.device / nezumi.events

```js
const device = await nezumi.device.getInfo();
// { manufacturer, model, sdkInt, abi }

const mem = await nezumi.device.getMemoryInfo();
// { totalMemory, availableMemory }

// イベント購読（download.progress / image.progress / model.loaded など）
const off = nezumi.events.on("download.progress", (payload) => { /* ... */ });
off(); // 解除
```

---

## 13. nezumi.permissions

```js
const list = await nezumi.permissions.list();        // [{ name, state }]
const state = await nezumi.permissions.get("ai");    // granted | denied | prompt
await nezumi.permissions.request("ai");              // 未許可時は PERMISSION_DENIED
```

> カメラ・マイクは Nezumi 独自 API ではなく Web 標準の `navigator.mediaDevices.getUserMedia()` を使います（manifest に `camera` / `microphone` の宣言が必要）。

---

## 14. セキュリティ上の注意（まとめ）

- Mini App から直接 Android API は呼べません。必ず `nezumi` SDK 経由です
- Package は不変です。自己書き換え・manifest/signature の変更は禁止
- App Data 境界外・他アプリのデータ・Android 内部パスへのアクセスは `FILE_ACCESS_DENIED`
- 外部 URL への画面遷移はサンドボックスによりブロックされます
- 画像生成は必ず本体 Safety Pipeline を経由します

---

## 15. 署名（配布向け）

未署名の Mini App はインストール時に開発者モードが必要です。一般配布する場合は Ed25519 で署名した `signature.json` を同梱してください。

```json
{
  "algorithm": "Ed25519",
  "keyId": "my-key-1",
  "publicKey": "<base64 X.509 Ed25519 public key>",
  "files": {
    "manifest.json": "sha256:<hex>",
    "index.html": "sha256:<hex>"
  },
  "signature": "<base64>"
}
```

- `signature.json` 以外の**すべてのファイル**を `files` に含めてください（欠落は `PACKAGE_TAMPERED`）
- 署名対象は `algorithm` / `keyId` / `publicKey` / `files` を決定的順序で連結した文字列です
- 署名鍵は利用者の端末で「信頼済み鍵」に登録される必要があります（初回は同意ダイアログが表示されます）
