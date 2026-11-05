# nezumi-ai Windows / macOS ビルド到達ロードマップ

更新日: 2026-11-05

> **最新の検証状況 (2026-11-05):** `:shared:compileAndroidMain` と `:app:compileDebugKotlin` はこの更新時点で成功確認できていない。Windows上でGradle Wrapperはユーザー領域の`.zip.lck`へのアクセス拒否で停止し、既存Gradle 9.3.1を直接実行した場合もデーモン接続に失敗した。コンパイラ起動前の環境ブロッカーとして扱い、M1/M2は成功ログを得るまで未達とする。Kotlinエラーとして報告されたToolResultCard参照、WebSearchのreturn、Ktor Acceptヘッダー、`isSuccess` importは修正済みだが、修正後の再コンパイルは未確認。

## 0. 目的と前提

このロードマップの第一目標は、WindowsとmacOSの両方で同じリポジトリを再現可能にビルドできる状態を作ることです。

最初からAndroidとiOSの全機能を同時に完成させるのではなく、次の順でビルドの土台を固めます。

1. WindowsでKMP共有モジュールとAndroid Kotlinコードをビルドできる
2. Windowsで必要なSDK・JDK・Pythonの差異を吸収する
3. macOSでiOS Simulator向けKMPコードをコンパイルできる
4. macOSで`shared.framework`を生成できる
5. Xcodeから`iosApp`を起動できる
6. その後にクラウド機能、DB、UI、ネイティブ推論を段階的に接続する

### 今回の対象外

- llama.cpp / MNN / LiteRT-LM / VOICEVOXのC++・JNI・Objective-C++移植
- iOSでのGGUF CPU推論
- Android NDKを使った完全なネイティブAPKビルド
- AndroidとiOSの全画面・全機能の同時完成

C++/NDKは別フェーズに分離し、まずKMP共有層とUIのコンパイルを成立させます。

## 1. 現在地

### リポジトリ構成

- `:app`: Androidアプリ。Compose、Fragment、RecyclerView、Chaquopy、CMakeを含む
- `:shared`: Kotlin Multiplatform共有モジュール
- `shared`のターゲット:
  - Android
  - `iosArm64`
  - `iosSimulatorArm64`
- `iosApp`: SwiftUIの最小シェル。`shared.framework`をXcodeから組み込む構成

### 実装済み（この更新時点でビルド未検証）

- JDK 21、Gradle 9.3.1を設定
- `gradle.properties`からWindows固有のJDK絶対パスを除去
- Compose Multiplatform依存を`shared/commonMain`へ追加済み
- Android側から共通化候補のドメイン、パーサ、Repository、UI部品をcommonMainへ移設済み
- Ktor CIO / Darwin engineを各プラットフォーム用に設定。OkHttp engine依存を除去
- Android側から移行した共通UI:
  - `MarkdownText`
  - `StatusBarSpacer`
  - `SvgSpinner`
  - `ThemeModeCard`
  - `ThemeModeSpec`
- Android側の元ファイルは移行済みのものについて削除済み

### 現在のビルド阻害要因と検証待ち

1. この実行環境ではGradle Wrapperのユーザー領域ロックにアクセスできず、直接起動したGradleもデーモン接続に失敗する。コードのコンパイル結果は未取得
2. Androidアプリの完全ビルドはChaquopyがPython 3.13を要求する
3. `app`はCMake/NDKを構成上参照するため、完全なAPKビルドにはネイティブ環境が必要
4. macOS側のiOSフレームワーク生成は未確認
5. `shared`はRoom KMP、Ktor Darwin、Compose Multiplatformなど複数のiOS依存を持つため、macOSで実ターゲットの解決確認が必要

## 2. 到達マイルストーン

| ID | 到達点 | 実行環境 | 完了条件 |
|---|---|---|---|
| M0 | ビルド設定のポータブル化 | Windows / macOS | OS固有のJavaパスやSDKパスを持たない |
| M1 | KMP Android共有層ビルド | Windows | `:shared:compileAndroidMain`成功 |
| M2 | Android Kotlin回帰コンパイル | Windows | `:app:compileDebugKotlin`成功 |
| M3 | Windows完全APKビルド | Windows | Python/NDK/CMakeを揃え`assembleDebug`成功 |
| M4 | iOS Simulator KMPコンパイル | macOS | `:shared:compileKotlinIosSimulatorArm64`成功 |
| M5 | iOS framework生成 | macOS | `linkDebugFrameworkIosSimulatorArm64`成功 |
| M6 | Xcode最小アプリ起動 | macOS | `iosApp`がSimulatorで起動しSwiftUIからshared APIを呼べる |
| M7 | 共通Compose UIをiOSへ接続 | macOS | `commonMain` UIをiOS画面から呼び出せる |
| M8 | クラウドチャット最小機能 | Windows / macOS | 1プロバイダーでストリーミング表示できる |
| M9 | Android/iOS初期版 | Windows / macOS | 履歴、APIキー、チャット、主要UIの回帰確認完了 |

## 3. Phase 0: ビルド設定を固定する

### 3.1 Gradle/JDK

両OSで以下を標準にします。

- JDK 21
- Gradle Wrapper 9.3.1
- Kotlin 2.3.20
- AGP 9.1.0
- Android compileSdk 37
- Java/Kotlin JVM target 21

`gradle.properties`や`local.properties`に、個人マシン固有の絶対パスをコミットしないことを完了条件にします。

### 3.2 OS依存値の扱い

以下は環境変数または`local.properties`から読む方式に統一します。

- `JAVA_HOME`
- `ANDROID_HOME` / `ANDROID_SDK_ROOT`
- `python.path`
- Android署名情報
- Sentry DSN
- Xcodeの開発チーム設定

`gradle.properties`にWindows専用のJavaパスを書かない状態を維持します。

### 3.3 ビルドプロファイルを分ける

完全ビルドとKMP検証を混同しないよう、次の2種類を定義します。

#### KMP検証プロファイル

- C++/NDKを起動しない
- Chaquopyのpip処理を起動しない
- `shared`のAndroid/iOSコンパイルを優先
- Androidアプリは`compileDebugKotlin`まで確認

#### Android完全ビルドプロファイル

- CMake/NDKを有効化
- Chaquopy Python 3.13を有効化
- `assembleDebug`または署名なしDebug APKを生成

この分離により、C++をまだ使わない段階でもKMP作業を止めないようにします。

## 4. Phase 1: Windowsビルド到達

### 4.1 Windows開発環境

Windows側に以下を用意します。

- Android StudioまたはAndroid SDK Command-line Tools
- JDK 21
- Android SDK Platform 37
- Android SDK Build-Tools
- Android SDK Platform-Tools
- Python 3.13
- Git
- 必要に応じて Visual Studio Build Tools
- 完全APKビルド時のみ NDK 30.0.14904198 と CMake

### 4.2 Windowsでの確認順

PowerShellで以下の順に実行します。

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

.\gradlew.bat :shared:compileAndroidMain --no-daemon
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

この2つをM1/M2の合格条件にします。

### 4.3 Windows完全APKビルド

Python 3.13を導入し、`local.properties`に必要なら次を設定します。

```properties
python.path=C\\:\\Users\\<user>\\AppData\\Local\\Programs\\Python\\Python313\\python.exe
```

その後、SDK/NDK/CMakeを揃えて実行します。

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

完全ビルドが失敗した場合は、次の順で切り分けます。

1. Java/JDKバージョン
2. Android SDK Platform 37
3. NDK/CMake
4. ChaquopyのPython 3.13
5. pipパッケージのAndroid wheel
6. C++/JNIリンク

### 4.4 Windows完了条件

- `:shared:compileAndroidMain`成功
- `:app:compileDebugKotlin`成功
- KMP移行した元Androidファイルが重複していない
- `:app:assembleDebug`成功
- APKをAndroid Emulatorまたは実機へインストールできる
- チャット画面の起動、履歴表示、設定画面を手動確認できる

## 5. Phase 2: macOSでKMP/iOSビルド到達

### 5.1 macOS開発環境

macOS側に以下を用意します。

- Xcode
- Xcode Command Line Tools
- JDK 21
- Git
- CocoaPodsは現構成では必須ではない。導入依存が追加された場合のみ使用
- Apple Silicon Macを推奨

現在のKMPターゲットは`iosArm64`と`iosSimulatorArm64`です。Intel Macを正式対象にする場合は、別途`iosX64()`追加の判断が必要です。

### 5.2 macOSでのKMPコンパイル

リポジトリルートで実行します。

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 --no-daemon
./gradlew :shared:compileKotlinIosArm64 --no-daemon
```

まずSimulator向けを必須とし、実機向けはApple Developer署名を使う段階で確認します。

### 5.3 framework生成

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon
./gradlew :shared:linkDebugFrameworkIosArm64 --no-daemon
```

期待する成果物は、Simulator向けまたは実機向けの`shared.framework`です。

### 5.4 Xcode接続

`iosApp/iosApp.xcodeproj`をXcodeで開き、Run Scriptが次を実行できることを確認します。

```bash
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

その後、Xcode Simulatorで次を確認します。

- SwiftUIの`ContentView`が表示される
- `shared.framework`のAPIを呼べる
- `NezumiIosShell`の既存接続確認が動く
- Debug/Releaseの両方でframework検索パスが解決する

### 5.5 macOS完了条件

- `compileKotlinIosSimulatorArm64`成功
- `compileKotlinIosArm64`成功
- `linkDebugFrameworkIosSimulatorArm64`成功
- Xcodeから`iosApp`がビルド成功
- Simulatorで起動成功
- SwiftUIからsharedの共通ロジックを1つ以上利用

## 6. Phase 3: iOS shared層の依存問題を解消する

macOSコンパイルで問題が出た場合は、次の順で対応します。

### 6.1 commonMainにAndroid依存が混入していないか

確認対象:

- `android.*`
- `java.*`
- `androidx.compose.ui.res.*`
- Android専用のRoom/SQLite API
- Android専用のファイル・暗号・ライフサイクルAPI

対応方針:

- 共通ロジックは`commonMain`
- Android実装は`androidMain`
- iOS実装は`iosMain`
- 差分は`expect/actual`またはPlatform interface

### 6.2 Room KMP

- Entity/DAOのcommonMainコンパイル
- Android DB builderを`androidMain`へ分離
- iOS DB builderを`iosMain`へ追加
- Schema生成タスクがApple targetでも壊れないことを確認

### 6.3 Ktor

- `commonMain`: `ktor-client-core`
- `androidMain`: CIO engine
- `iosMain`: Darwin engine
- OpenAI互換など1プロバイダーのGET/POST/streamingから確認

### 6.4 Compose Multiplatform

- `commonMain` UIのAndroidコンパイル
- iOS Simulator向けCompose依存解決
- MaterialTheme/Font/ImageなどリソースAPIを共通APIへ寄せる
- Android `R`参照をcommonMainに持ち込まない

## 7. Phase 4: 最初の共通UI画面

ビルド基盤の後、UIは次の順で移行します。

1. `InlineToolCallMessageBody`の表示モデルとPlatform callback分離
2. `ToolResultCardContent`のResourceProvider除去
3. `AttachmentOptionsSheet`、`AuthLockScreen`、`WelcomeDialogContent`
4. `DrawerScreen`、`HistorySearchModal`、`SessionListScreen`
5. `NezumiTheme`の共通ColorScheme化
6. `ChatScreen`のRecyclerViewをCompose LazyColumnへ置換
7. Fragmentを画面シェルへ縮小

この段階では、Androidで動いている画面を一度に消さず、旧画面と新Composableを段階的に切り替えられる構成にします。

## 8. Phase 5: クラウドチャット最小版

ネイティブ推論を後回しにして、まず以下をWindows/macOS双方で通します。

- API keyのPlatform実装
- OpenAI互換またはGeminiの1プロバイダー
- Ktor streaming
- 共通チャット状態
- メッセージ表示
- セッション保存
- Android UI / iOS SwiftUIまたはCompose UIからの呼び出し

### 完了条件

- Windows Androidでクラウド回答をストリーミング表示
- macOS iOS Simulatorで同じ共有UseCaseを呼び出し
- API keyをAndroid側とiOS側で別の安全な保存先へ格納
- AndroidとiOSの両方でセッションを再表示

## 9. Phase 6: ネイティブ推論は最後に接続

C++を使わない初期ビルドの後に、ネイティブ推論を別ロードマップとして開始します。

### Android

- NDK/CMakeの完全ビルド
- llama.cpp JNI
- MNN JNI
- LiteRT-LM/QNN
- VOICEVOX

### iOS

- llama.cppのiOS static libraryまたはxcframework
- Objective-C++/C-ABI bridge
- KMP `iosMain` actual
- CPU推論を最初の完成条件
- Metal最適化はCPU経路安定後

## 10. CIへの展開

ローカルビルドが安定した後にCIを追加します。

### Windows/Android CI

- JDK 21
- Android SDK Platform 37
- `:shared:compileAndroidMain`
- `:app:compileDebugKotlin`
- Python 3.13を用意したジョブで`:app:assembleDebug`

### macOS/iOS CI

- macOS runner
- Xcode固定バージョン
- `:shared:compileKotlinIosSimulatorArm64`
- `:shared:linkDebugFrameworkIosSimulatorArm64`
- 必要に応じてXcode build

### CIの段階

1. Pull Request: KMP共通層コンパイル
2. Pull Request: Android Kotlinコンパイル
3. main merge: Android APK build
4. macOS runner: iOS Simulator framework build
5. release候補: Xcode archiveと実機確認

## 11. リリース前の完成条件

### Windows/Android

- JDK、SDK、Python、NDKのセットアップ手順が再現可能
- Debug APK生成
- 主要画面の起動確認
- KMP共通ロジックのAndroid回帰テスト
- C++を含む完全ビルドは別ゲートとして明示

### macOS/iOS

- Simulator用framework生成
- Xcodeからアプリ起動
- shared API接続
- クラウドチャット最小版
- Keychain/UserDefaults実装
- セッション保存/復元
- 実機での署名・ビルド

## 12. 最初に実行する具体的タスク

### Windows担当

1. JDK 21を設定
2. SDK Platform 37を導入
3. `:shared:compileAndroidMain`を実行
4. `:app:compileDebugKotlin`を実行
5. Python 3.13を導入
6. NDK/CMake導入前にKMP検証プロファイルを完成
7. NDK/CMake導入後に`:app:assembleDebug`を実行

### macOS担当

1. XcodeとCommand Line Toolsを導入
2. JDK 21を設定
3. `:shared:compileKotlinIosSimulatorArm64`を実行
4. `:shared:compileKotlinIosArm64`を実行
5. `:shared:linkDebugFrameworkIosSimulatorArm64`を実行
6. Xcodeで`iosApp`を開いてビルド
7. `ContentView`からshared API接続を確認

### 最初の合格ライン

```text
Windows:
  :shared:compileAndroidMain              PASS
  :app:compileDebugKotlin                 PASS

macOS:
  :shared:compileKotlinIosSimulatorArm64 PASS
  :shared:linkDebugFrameworkIosSimulatorArm64 PASS
  iosApp Xcode build                     PASS
```

この合格ラインを通した後に、クラウドチャットと共通UIの実接続へ進みます。
