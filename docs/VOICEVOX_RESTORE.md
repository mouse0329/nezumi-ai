# VOICEVOX 音声読み上げ機能の復元手順

## 現在の状況

VOICEVOX 音声読み上げ機能は、Android 15 以降の 16KB ページサイズ端末との互換性問題のため、**一時的に無効化**されています。

### 無効化の理由

- **VOICEVOX CORE 0.16.4** に同梱の **ONNX Runtime 1.17.3** は **4KB ページアライメント**でビルドされている
- Android 15 以降の一部端末（Pixel 6 以降など）は **16KB ページサイズ**を使用
- 4KB アライメントのネイティブライブラリ (`.so`) は 16KB 端末で `dlopen` に失敗してクラッシュ
- Google Play Console は 16KB 非対応 APK の新規アップロードを **2025年8月から拒否**

## 復元に必要な条件

VOICEVOX 機能を再有効化するには、以下のいずれかが必要です：

1. **VOICEVOX CORE の新バージョンリリース**（16KB 対応版 ONNX Runtime 1.18.0+ を含む）
2. **手動で 16KB 対応版 ONNX Runtime をビルド**して AAR に統合

## 復元手順

### 1. VOICEVOX CORE AAR の更新

16KB 対応版の VOICEVOX CORE AAR を入手したら、以下の手順で統合します：

```bash
# 新しい AAR を libs ディレクトリに配置
cp voicevoxcore-android-<新バージョン>.aar app/libs/
```

### 2. build.gradle.kts の修正

`app/build.gradle.kts` を開き、以下の変更を行います：

#### (1) jniLibs の excludes を削除

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
        // 以下の excludes ブロックを削除
        // excludes += setOf(
        //     "lib/arm64-v8a/libvoicevox_onnxruntime.so",
        //     "lib/x86_64/libvoicevox_onnxruntime.so"
        // )
    }
}
```

#### (2) AAR 依存を復元

```kotlin
dependencies {
    // ...
    
    // VOICEVOX integration
    implementation(files("libs/voicevoxcore-android-<新バージョン>.aar"))
}
```

### 3. VoicevoxFeatureFlag の有効化

`app/src/main/java/com/nezumi_ai/voicevox/VoicevoxFeatureFlag.kt` を編集：

```kotlin
object VoicevoxFeatureFlag {
    const val ENABLED = true  // false から true に変更
}
```

### 4. VoicevoxManager.kt の復元

`app/src/main/java/com/nezumi_ai/voicevox/VoicevoxManager.kt` を元の実装版に戻します。

Git 履歴から復元する場合：

```bash
git show <commit-hash>:app/src/main/java/com/nezumi_ai/voicevox/VoicevoxManager.kt > app/src/main/java/com/nezumi_ai/voicevox/VoicevoxManager.kt
```

または、パッチを逆適用：

```bash
git apply -R voicevox_disable_16kb.patch
```

### 5. ビルドとテスト

```bash
# ビルド
./gradlew assembleDebug

# 16KB ページサイズ端末でテスト
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 6. 16KB 互換性の確認

以下のコマンドで `.so` ファイルのアライメントを確認：

```bash
# arm64-v8a
readelf -l app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a/libvoicevox_onnxruntime.so | grep LOAD

# 出力例（16KB 対応）:
#   LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x123456 0x123456 R E 0x4000
#                                                                                        ^^^^^^ ← 0x4000 (16KB) であること
```

`p_align` 値が `0x4000` (16KB) であれば OK。`0x1000` (4KB) の場合は NG。

## トラブルシューティング

### ビルドエラー: "Unresolved reference 'jp.hiroshiba.voicevoxcore'"

- AAR が正しく配置されているか確認
- `build.gradle.kts` の dependencies に AAR の implementation が記載されているか確認

### 実行時クラッシュ: "dlopen failed: ... alignment"

- ONNX Runtime が 16KB アライメントでビルドされているか確認
- `readelf -l` で LOAD セグメントの `p_align` をチェック

### Play Console アップロード拒否

- すべてのネイティブライブラリが 16KB 対応であることを確認
- [Google のガイダンス](https://android-developers.googleblog.com/2023/09/16kb-page-sizes-for-android-15.html) を参照

## 参考資料

- [VOICEVOX CORE リポジトリ](https://github.com/VOICEVOX/voicevox_core)
- [Android 16KB Page Size Support](https://developer.android.com/guide/practices/page-sizes)
- [ONNX Runtime リリース](https://github.com/microsoft/onnxruntime/releases) (1.18.0+ が 16KB 対応)

## 連絡先

質問や問題がある場合は、GitHub Issues で報告してください。
