# iosApp

Android 版とは別の SwiftUI シェル。チャット機能はまだない。`shared.framework` の接続確認だけを行う。

macOS + Xcode で `iosApp/iosApp.xcodeproj` を開く。Run Script が `:shared:embedAndSignAppleFrameworkForXcode` を呼び、`shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` の framework をリンクする。署名チームは Xcode の Signing で選ぶ。シミュレータは Apple Silicon (`iosSimulatorArm64`) のみ。
