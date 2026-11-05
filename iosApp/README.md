# iosApp

Android 版とは別の SwiftUI シェル。Phase 6 でチャット / セッション / 設定のタブ構成を追加した。

- `ContentView` : タブのルート
- チャット画面 : メッセージ一覧 + 入力欄（推論接続は未実装）
- セッション一覧 : プレースホルダ（`ChatSessionRepository` 接続待ち）
- 設定 : モデル / API キー / MCP の入口。API キーは `NezumiIosShell` 経由で Keychain (`IosPlatformSecureStore`) に保存する。

`shared.framework` のリンクは従来どおり。macOS + Xcode で `iosApp/iosApp.xcodeproj` を開く。Run Script が `:shared:embedAndSignAppleFrameworkForXcode` を呼び、`shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` の framework をリンクする。署名チームは Xcode の Signing で選ぶ。シミュレータは Apple Silicon (`iosSimulatorArm64`) のみ。

カメラ・マイク・写真の利用目的は `Info.plist` に記載済み。
