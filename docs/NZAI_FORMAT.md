# Nezumi AI セッション交換形式 (`.nzai`)

| 項目 | 値 |
|------|-----|
| 形式名 (`format`) | `nezumi-session-export` |
| 現行バージョン (`version`) | `1` |
| 拡張子 | `.nzai` |
| エンコーディング | UTF-8 |
| 本体 | JSON（整形あり / なしどちらも可） |
| MIME（共有時の目安） | `application/octet-stream`（中身は JSON） |

アプリの書き出し・取り込み実装:

- 書き出し: `app/src/main/java/com/nezumi_ai/data/export/SessionExportWriter.kt`
- 取り込み: `app/src/main/java/com/nezumi_ai/data/export/SessionImportReader.kt`

---

## 1. 目的

チャットセッションを端末外にバックアップ・共有・復元するための交換フォーマットです。

1 ファイルに **1 セッション以上** を格納できます。アプリの複数選択書き出しでは、選んだセッションを **常に 1 つの `.nzai` にまとめます**（セッションごとに個別ファイルは出しません）。

含める情報:

- セッションメタデータ（名前、日時、モデル、固定状態など）
- メッセージ本文（user / assistant）
- Thinking（推論過程テキスト）
- ツールコール結果カード（成功・失敗・payload）
- 添付（画像・音声・テキスト・PDF 等のドキュメント）の **Base64**

---

## 2. トップレベル

```json
{
  "format": "nezumi-session-export",
  "version": 1,
  "exportedAt": 1730000000000,
  "exportedAtIso": "2025-10-18T13:00:00.000+0900",
  "sessions": [ /* Session オブジェクトの配列 */ ]
}
```

| フィールド | 型 | 必須 | 説明 |
|------------|----|------|------|
| `format` | string | 必須 | 必ず `"nezumi-session-export"`。取り込み時に不一致なら拒否する。 |
| `version` | number (int) | 必須 | スキーマ版。現行は `1`。将来の破壊的変更時に増やす。 |
| `exportedAt` | number (int64) | 推奨 | 書き出し時刻（Unix epoch **ミリ秒**）。 |
| `exportedAtIso` | string | 任意 | 人間可読な書き出し時刻（ISO 風文字列）。 |
| `sessions` | array | 必須 | 1 件以上の Session。空配列は取り込み時エラー。 |

未知のトップレベルキーは将来拡張用として無視してよい（前方互換）。

---

## 3. Session オブジェクト

```json
{
  "id": 42,
  "name": "エバー航空とは？",
  "createDate": 1729000000000,
  "lastUpdated": 1729003600000,
  "selectedModel": "E2B",
  "isPinned": false,
  "isIncognito": false,
  "messages": [ /* Message オブジェクトの配列 */ ]
}
```

| フィールド | 型 | 必須 | 説明 |
|------------|----|------|------|
| `id` | number | 任意 | 書き出し元のセッション ID。取り込み時は **新しい ID を採番**し、この値は参照用。 |
| `name` | string | 推奨 | 表示名。空なら取り込み側で「インポートしたチャット」等にフォールバックしてよい。 |
| `createDate` | number | 任意 | 作成時刻（epoch ms）。 |
| `lastUpdated` | number | 任意 | 最終更新（epoch ms）。 |
| `selectedModel` | string | 任意 | 使用モデル識別子（例: `"E2B"`）。欠落時はアプリ既定。 |
| `isPinned` | boolean | 任意 | 固定（ピン）状態。既定 `false`。 |
| `isIncognito` | boolean | 任意 | シークレット。取り込み実装は通常モードで復元してよい。 |
| `messages` | array | 必須 | 時系列のメッセージ。空でもセッション自体は作ってよい。 |

### 取り込み時の ID 方針

- セッション `id` はローカル DB と衝突しないよう **常に新規採番**する。
- メッセージも新規 ID を採番する。
- `parentUserMessageId`（バリアント応答用）がある場合、旧 ID → 新 ID のマップで付け替える。

---

## 4. Message オブジェクト

```json
{
  "id": 1001,
  "role": "assistant",
  "content": "可視の回答本文です。",
  "timestamp": 1729000100000,
  "thinking": "内部の思考過程（あれば）",
  "toolCalls": [
    {
      "toolName": "web_search",
      "success": true,
      "payload": { "query": "…", "result": "…" }
    }
  ],
  "imageDescription": "画像の説明テキスト（あれば）",
  "generationTps": 32.5,
  "generationTimeMs": 1200,
  "ttftMs": 180,
  "parentUserMessageId": 1000,
  "variantIndex": 0,
  "attachments": [ /* Attachment オブジェクト */ ]
}
```

| フィールド | 型 | 必須 | 説明 |
|------------|----|------|------|
| `id` | number | 任意 | 書き出し元メッセージ ID。取り込み時は再採番。 |
| `role` | string | 必須 | `"user"` または `"assistant"`（将来拡張の余地あり）。 |
| `content` | string | 必須 | 表示用本文。Thinking は含めない。 |
| `timestamp` | number | 推奨 | 送信・生成時刻（epoch ms）。 |
| `thinking` | string | 任意 | Thinking / 推論過程。無い場合はキー自体を省略してよい。 |
| `toolCalls` | array | 任意 | ツール結果カードの配列。アプリ内部の `toolResultsJson` に相当。 |
| `toolCallsRaw` | string | 任意 | `toolCalls` が JSON 配列としてパースできなかった場合の生文字列フォールバック。 |
| `imageDescription` | string | 任意 | 画像キャプション等。 |
| `generationTps` | number | 任意 | 生成速度 (tokens/sec)。 |
| `generationTimeMs` | number | 任意 | 生成時間 (ms)。 |
| `ttftMs` | number | 任意 | Time To First Token (ms)。 |
| `parentUserMessageId` | number | 任意 | 同一ユーザー発話に対する複数 assistant 応答（バリアント）の親 user メッセージ ID。 |
| `variantIndex` | number | 任意 | 同一 parent 内の並び（0 始まり）。既定 `0`。 |
| `attachments` | array | 任意 | 添付一覧。無い場合は省略。 |

### 4.1 `toolCalls` 要素

書き出し元はアプリのツール結果 JSON 配列をそのまま載せます。典型フィールド:

| フィールド | 型 | 説明 |
|------------|-----|------|
| `toolName` | string | ツール名 |
| `success` | boolean | 成否 |
| `payload` | object | 引数・結果などの任意 JSON |

リーダは配列全体を文字列化して DB の `toolResultsJson` に保存してよい。

---

## 5. Attachment オブジェクト

```json
{
  "kind": "image",
  "name": "photo.jpg",
  "uri": "content://…（書き出し元の参照。取り込みでは使わない）",
  "mime": "image/jpeg",
  "sizeBytes": 123456,
  "encoding": "base64",
  "base64": "…Base64 本体（改行なし）…"
}
```

| フィールド | 型 | 必須 | 説明 |
|------------|----|------|------|
| `kind` | string | 必須 | 種別。下記参照。 |
| `name` | string | 推奨 | 表示名・ファイル名。 |
| `uri` | string | 任意 | 書き出し元端末上の URI。**ポータブルではない**。復元には使わない。 |
| `mime` | string | 任意 | MIME タイプ。 |
| `sizeBytes` | number | 任意 | バイト数。 |
| `encoding` | string | Base64 があるとき | 現行は `"base64"` のみ。 |
| `base64` | string | 本文があるとき | **改行なし** Base64（`Base64.NO_WRAP`）。 |
| `error` | string | 失敗時 | 例: `"too_large"`, `"read_failed"`, `"invalid_uri"`。 |
| `note` | string | 任意 | 人間向けメモ（例: サイズ上限超過）。 |

### 5.1 `kind` の値

| kind | 意味 | 取り込み時の扱い（目安） |
|------|------|--------------------------|
| `image` | 静止画・動画フレーム | 画像 URI 列へ |
| `audio` | 音声 | `audioUri`（先頭 1 件を優先） |
| `video` | 動画本体 | メディアとして保存 |
| `file` | テキスト等の一般ファイル | `nezumi://txtfile` 相当のテキスト添付マーカーへ |
| `document` | PDF / Office 等（変換済み含む） | テキスト添付マーカー（document フラグ付き）へ |

### 5.2 サイズ上限（書き出し実装）

現行の書き出し実装では、**1 添付あたり約 25 MiB** を超えるバイナリは Base64 を載せず、次のように記録します。

```json
{
  "kind": "video",
  "name": "clip.mp4",
  "sizeBytes": 40000000,
  "error": "too_large",
  "note": "Attachment exceeded 26214400 bytes; base64 omitted"
}
```

取り込み側は `base64` が無い添付をスキップしてよい。

---

## 6. 最小例（添付なし）

```json
{
  "format": "nezumi-session-export",
  "version": 1,
  "exportedAt": 1730000000000,
  "exportedAtIso": "2025-10-18T13:00:00.000+0900",
  "sessions": [
    {
      "id": 1,
      "name": "サンプル",
      "createDate": 1730000000000,
      "lastUpdated": 1730000100000,
      "selectedModel": "E2B",
      "isPinned": false,
      "isIncognito": false,
      "messages": [
        {
          "id": 10,
          "role": "user",
          "content": "こんにちは",
          "timestamp": 1730000001000
        },
        {
          "id": 11,
          "role": "assistant",
          "content": "こんにちは！何かお手伝いできますか？",
          "timestamp": 1730000005000,
          "thinking": "挨拶に対して丁寧に返す。",
          "toolCalls": []
        }
      ]
    }
  ]
}
```

---

## 7. 複数セッションを 1 ファイルにまとめる例

```json
{
  "format": "nezumi-session-export",
  "version": 1,
  "exportedAt": 1730000000000,
  "sessions": [
    { "id": 1, "name": "セッション A", "messages": [ /* … */ ] },
    { "id": 2, "name": "セッション B", "messages": [ /* … */ ] }
  ]
}
```

アプリの UI 仕様:

- サイドバーで **複数選択 → 書き出し** → 上記のように **1 ファイル**。
- 単一セッションの ⋯ メニュー「書き出し」も 1 ファイル（`sessions` 長さ 1）。

---

## 8. ファイル名・拡張子

| ケース | ファイル名の目安（実装） |
|--------|--------------------------|
| 1 セッション | `nezumi_<名前のサニタイズ>_<yyyyMMdd_HHmmss>.nzai` |
| 複数セッション | `nezumi_sessions_<件数>_<yyyyMMdd_HHmmss>.nzai` |

- 拡張子は **必ず `.nzai`**。
- 中身は JSON だが、専用形式であることを示すため拡張子は `.json` にしない。
- ファイル名に使えない文字 `\/:*?"<>|` は `_` に置換する。

---

## 9. 取り込み（インポート）の要件

実装場所: 設定 → **チャット** → 「ファイルから取り込む (.nzai)」

必須チェック:

1. UTF-8 で読めること
2. JSON としてパースできること
3. `format === "nezumi-session-export"`
4. `sessions` が非空の配列であること

推奨動作:

- 各セッションを **新規** として DB に挿入する（上書きしない）
- `thinking` / `toolCalls` / `attachments` を可能な範囲で復元する
- `base64` 欠落やデコード失敗の添付はスキップし、本文・Thinking は残す
- 完了後に取り込んだセッション数・メッセージ数をユーザーに伝える

`version` が未知の場合:

- より新しい版を読んだときは、既知フィールドだけを使い未知キーを無視するか、明示的に「非対応バージョン」とエラーにするかを実装が選べる。現行リーダは `format` のみ厳格に見る。

---

## 10. 互換性・バージョニング

| version | 内容 |
|---------|------|
| `1` | 初版。本文 / Thinking / toolCalls / Base64 添付 / 複数セッション。 |

破壊的変更（必須フィールドの削除・意味の変更など）では `version` を上げ、古いリーダは拒否または部分互換を明記する。

追加フィールドのみの変更は同一 `version` のままとし、リーダは未知キーを無視する。

---

## 11. セキュリティとプライバシー

- `.nzai` には会話全文・Thinking・添付バイナリが含まれる。**機密情報の扱いに注意**すること。
- 書き出し元の `uri` は他端末では無効。復元は `base64` のみを信頼する。
- 共有シート経由で外部アプリに渡す場合、ユーザーの明示操作のもとで行う。

---

## 12. 関連コードパス

| 役割 | パス |
|------|------|
| 書き出し | `SessionExportWriter.kt` |
| 取り込み | `SessionImportReader.kt` |
| サイドバー書き出し UI | `DrawerScreen.kt` / `MainActivity.kt` |
| 設定からの取り込み UI | `SettingsAboutCards.kt`（ChatHistoryCard）/ `SettingsComposeFragment.kt` |
| メッセージ实体 | `MessageEntity`（`thinkingContent`, `toolResultsJson`, `imageUri`, `audioUri` 等） |

---

*Document version: 1 — matches `.nzai` schema `version: 1`.*
