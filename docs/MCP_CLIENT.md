# MCP クライアント統合

nezumi-ai は Model Context Protocol (MCP) のクライアントとして動作し、
外部の MCP サーバーが公開するツールを AI から呼び出せます。

## サポート transport

| Transport | 対応 | 備考 |
|-----------|------|------|
| Streamable HTTP | ✅ | 推奨。単一エンドポイントへ POST。 |
| SSE (Server-Sent Events) | ✅ | `text/event-stream` からの最初の JSON-RPC メッセージを解釈。 |
| stdio | ❌ | Android のプロセス起動制限により未対応。 |

## サーバー登録の流れ

1. サイドメニュー → **プリセット** から編集したいプリセットを開く
2. **ツール呼び出し** を ON にする
3. ツールのチェックリスト直下にある **「MCPを追加」** ボタンを押す
4. モーダルで **「+ 新規追加」** を選び、下記項目を入力

   | 項目 | 内容 |
   |------|------|
   | 表示名 | サーバーの識別名（自由入力） |
   | エンドポイント URL | `https://example.com/mcp` など |
   | Transport | Streamable HTTP / SSE |
   | Authorization ヘッダ | 例: `Bearer sk-xxxx`（任意） |
   | 追加ヘッダ | `key: value` を 1 行に 1 つ（任意） |
   | 有効化 | このサーバーを利用するかどうか |

5. **接続テスト** を押すと `initialize` → `tools/list` を実行し、取得できたツールを表示します
6. **保存** で登録
7. プリセット編集モーダルへ戻り、使いたいサーバーにチェックを入れて **保存**

## AI 側からの呼び出し

- **GGUF / llama.rn** モデル: MCP ツールが `tools/list` から取得され、
  `mcp__<serverPrefix>__<toolName>` の修飾名でシステムプロンプトの `<tools>` に自動追加されます。
- **LiteRT (Gemma)**: 汎用ディスパッチャ `mcp_call(name, argumentsJson)` が公開され、
  内部で該当 MCP サーバーへ委譲されます。修飾名を直接呼ぶ経路もフォールバックとして用意。

## データ保存

- サーバー設定は `SharedPreferences (mcp_preferences)` に JSON 配列で保存されます。
- プリセットが参照する MCP サーバー ID は `preset.mcp_server_ids` 列に JSON 配列で保持されます (DB v29)。
- プリセット切替時に `McpToolRegistry.refresh()` が呼ばれ、`tools/list` を非同期に更新します。

## セキュリティ上の注意

- Authorization トークン等は端末内に平文（SharedPreferences）で保存されます。
- 信頼できるエンドポイント以外に接続しないでください。
- MCP サーバーは任意のツールを公開できるため、実行内容を必ず確認してください。
