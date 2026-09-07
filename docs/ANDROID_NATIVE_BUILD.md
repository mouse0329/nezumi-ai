# Android ネイティブビルド手順

## 前提

`app/src/main/vendor/llama.cpp` は Git サブモジュールです。ここは直接編集せず、ビルドに必要な調整は `app/src/main/cpp/CMakeLists.txt` または開発環境側で行います。

変更状態は次で確認できます。

```powershell
git -C app/src/main/vendor/llama.cpp status --short
```

出力がなければ、サブモジュール内にローカル変更はありません。

## OpenCL カーネル生成の CP932 エラー

Windows の既定コードページが CP932 の場合、OpenCL カーネル埋め込み時に次のエラーが発生することがあります。

```text
UnicodeDecodeError: 'cp932' codec can't decode byte ...
```

`embed_kernel.py` はサブモジュール内のファイルなので変更しません。Python の UTF-8 モードをユーザー環境変数で有効にします。

```powershell
[Environment]::SetEnvironmentVariable('PYTHONUTF8', '1', 'User')
$env:PYTHONUTF8 = '1'
```

環境変数を Gradle デーモンへ確実に反映するため、設定後にデーモンを再起動します。

```powershell
.\gradlew --stop
.\gradlew ':app:buildCMakeDebug[arm64-v8a]' --console=plain
```

確認用に、現在のシェルへ反映されているかを確認できます。

```powershell
$env:PYTHONUTF8
```

`1` と表示されれば有効です。OpenCL の `Generate ... .cl.h` が進み、`UnicodeDecodeError` が出なければこの問題は解消されています。

## Vulkan shader generator

Vulkan を有効にした Android クロスコンパイルでは、ホスト側のコンパイラと Ninja が必要です。

必要なもの:

- Visual Studio の C++ ビルドツール
- Windows SDK
- Android SDK CMake 3.22.1 に含まれる Ninja
- Vulkan SDK の `glslc`

Vulkan の C++ ヘッダも必要です。`vulkan.hpp` が存在することを確認します。

```powershell
echo $env:VULKAN_SDK
Test-Path "$env:VULKAN_SDK\Include\vulkan\vulkan.hpp"
```

`True` にならない場合は、Vulkan SDK の `Include` をインストールし、`VULKAN_SDK` を設定し直してください。Android 側の `ggml-vulkan` には、この `Include` ディレクトリを親 CMake から明示的に渡します。

Ninja の場所が親プロセスの `PATH` にない場合は、ユーザー PATH に追加してから Gradle デーモンを再起動します。

```powershell
$cmakeBin = Join-Path $env:LOCALAPPDATA 'Android\Sdk\cmake\3.22.1\bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*$cmakeBin*") {
    [Environment]::SetEnvironmentVariable('Path', "$cmakeBin;$userPath", 'User')
}
$env:Path = "$cmakeBin;$env:Path"
.\gradlew --stop
```

`kernel32.lib` が見つからない場合は、Visual Studio Installer で次を確認します。

- Desktop development with C++
- MSVC v143 以降の C++ build tools
- Windows 10/11 SDK

`Invalid character escape '\P'` や `Invalid character escape '\U'` が出る場合は、Windows のバックスラッシュを含むホストツールチェーンのパスが原因です。プロジェクト側の `app/src/main/cpp/CMakeLists.txt` でホストコンパイラと出力先を `file(TO_CMAKE_PATH ...)` に通し、生成済みの `.cxx` 状態を再構成します。

```powershell
.\gradlew ':app:configureCMakeDebug[arm64-v8a]' --console=plain
.\gradlew ':app:buildCMakeDebug[arm64-v8a]' --console=plain
```

## ビルド後の確認

```powershell
git -C app/src/main/vendor/llama.cpp status --short
git status --short -- app/src/main/cpp/CMakeLists.txt app/src/main/vendor/llama.cpp
```

サブモジュールに変更が表示された場合は、まずビルドスクリプトやパッチがサブモジュール内へ適用されていないか確認します。サブモジュールを `HEAD` に戻すだけでは、CP932 問題が再発するため、`PYTHONUTF8=1` を設定してからビルドしてください。
