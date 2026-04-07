Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

Copyright 2026 nezumi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
# License Information

このアプリケーションは複数のオープンソースソフトウェアとモデルを使用しています。  
配布する前に、各依存関係とモデルのライセンス条項を確認してください。

---

## 本体・依存ライセンス

### AndroidX と Material Components
- **ライセンス**: Apache License 2.0
- **リポジトリ**: [androidx](https://github.com/androidx)
- **バージョン**: Core KTX 1.10.1、AppCompat 1.6.1、Material 1.10.0、その他随時
- **説明**: 最新のAndroid開発パターンとMaterial Designコンポーネントを提供する包括的なライブラリスイート

### AndroidX ConstraintLayout
- **ライセンス**: Apache License 2.0
- **バージョン**: 2.1.4
- **リポジトリ**: [androidx](https://github.com/androidx)
- **説明**: レスポンシブで複雑なUIレイアウトの構築を効率的にするレイアウト管理ライブラリ

### AndroidX Navigation
- **ライセンス**: Apache License 2.0
- **バージョン**: 2.6.0
- **リポジトリ**: [androidx](https://github.com/androidx)
- **説明**: アプリ内のフラグメント間のナビゲーションを管理するためのフレームワーク

### AndroidX Room Database
- **ライセンス**: Apache License 2.0
- **バージョン**: 2.7.0
- **リポジトリ**: [androidx](https://github.com/androidx)
- **説明**: SQLiteデータベースに対する抽象化レイヤーを提供し、型安全なデータベースアクセスを実現

### AndroidX WorkManager
- **ライセンス**: Apache License 2.0
- **バージョン**: 2.9.0
- **リポジトリ**: [androidx](https://github.com/androidx)
- **説明**: 遅延実行可能な非同期タスクのスケジューリングと実行管理を行うライブラリ

### AndroidX Lifecycle & ViewModel
- **ライセンス**: Apache License 2.0
- **バージョン**: 2.7.0
- **リポジトリ**: [androidx](https://github.com/androidx)
- **説明**: ライフサイクル対応のコンポーネント管理とUIのステート保存を提供

### Kotlin Standard Library
- **ライセンス**: Apache License 2.0
- **バージョン**: 2.2.0
- **リポジトリ**: [JetBrains/kotlin](https://github.com/JetBrains/kotlin)
- **説明**: Kotlinプログラミング言語とその標準ライブラリ。非同期操作用のコルーチン（1.7.3）を含みます

### MediaPipe Tasks
- **ライセンス**: Apache License 2.0
- **リポジトリ**: [google/mediapipe](https://github.com/google/mediapipe)
- **説明**: Googleの MediaPipeフレームワークで、コンピュータビジョン、オーディオ、テキストタスク用のML機能を提供

### MediaPipe Tasks-GenAI
- **ライセンス**: Apache License 2.0
- **リポジトリ**: [google/mediapipe](https://github.com/google/mediapipe)
- **説明**: MediaPipe GenAIタスク。オンデバイスLLM推論を提供し、Gemmaモデルを最適化されたランタイムでサポート

### Google AI Edge LiteRT-LM
- **ライセンス**: Apache License 2.0
- **バージョン**: 0.10.0
- **ウェブサイト**: [ai.google.dev/edge](https://ai.google.dev/edge)
- **説明**: Google AI Edgeのオンデバイス大規模言語モデル実行エンジン。Gemmaモデルのローカル推論を提供します

### TensorFlow Lite Play Services
- **ライセンス**: Apache License 2.0
- **バージョン**: 16.4.0（Java）、16.4.0（GPU）
- **提供元**: Google Play Services
- **説明**: TensorFlow Liteのオンデバイス推論ライブラリ。CPU およびGPUアクセラレーション対応

### Hugging Face Models
- **ライセンス**: Model Hub and Inference API
- **ウェブサイト**: [huggingface.co](https://huggingface.co)
- **説明**: モデルの配布ページ・モデルカード確認に利用します。モデル規約の一次情報は提供元の公式条項を確認してください。

### Gemma Model
- **ライセンス**: Gemma Community License
- **公式ページ**: [ai.google.dev/gemma](https://ai.google.dev/gemma)
- **説明**: Gemmaモデルの利用条件は Gemma Terms of Use を優先して確認してください。完全なライセンス条項は [Gemma Terms](https://ai.google.dev/gemma/terms) を参照してください。

### Gemma 4 Framework & Tools
- **ライセンス**: Apache License 2.0
- **公式ページ**: [ai.google.dev/gemma](https://ai.google.dev/gemma)
- **説明**: Gemma 4 のマルチターン会話、チャンネル API（シンキング機能）、および関連ツール・フレームワーク。LiteRT-LM で実行される際の推論エンジン部分に適用されます。

### AppAuth for Android
- **ライセンス**: Apache License 2.0
- **バージョン**: 0.11.1
- **リポジトリ**: [openid/AppAuth-Android](https://github.com/openid/AppAuth-Android)
- **説明**: Hugging Face OAuth 認証フローに使用します。

### Markwon
- **ライセンス**: Apache License 2.0
- **バージョン**: 4.6.2
- **リポジトリ**: [noties/Markwon](https://github.com/noties/Markwon)
- **説明**: Markdown レンダリング（テーブル拡張含む）に使用します。

### JUnit
- **ライセンス**: Eclipse Public License (EPL) 1.0
- **バージョン**: 4.13.2
- **リポジトリ**: [junit-team/junit](https://github.com/junit-team/junit)
- **説明**: Java 単体テストフレームワーク

### AndroidX Test (JUnit, Espresso)
- **ライセンス**: Apache License 2.0
- **バージョン**: 1.1.5（JUnit）、3.5.1（Espresso）
- **リポジトリ**: [androidx](https://github.com/androidx)
- **説明**: Android アプリの UI テストと機器テスト用ライブラリ

### Project Repository
- **リポジトリ**: [mouse0329/nezumi-ai](https://github.com/mouse0329/nezumi-ai)
- **説明**: このアプリのソースコードと更新履歴です。

---

## 重要な注意事項

### モデルの使用
- **Gemma**: Google提供のモデルを使用する場合、Gemma Terms of Useに従う必要があります
- **Hugging Face**: ホストされているモデルは、それぞれのライセンスが適用されます
- **LiteRT-LM**: Google AI Edgeのランタイムで実行されるモデルは、各モデルの提供元ライセンスに従う必要があります
- **確認**: 配布前に各モデルの最新ライセンス情報を確認してください

### 著作権表示
このソフトウェアを配布する場合、リリースパッケージに以下の情報を含める必要があります：
- 著作権表示
- ライセンステキスト
- 第三者のライセンス情報

### コンプライアンス
- すべてのライセンスに従ってください
- オープンソースコンポーネントの使用制限に注意してください
- 商用利用の場合、各ライセンスを慎重に確認してください

---

## ライセンスファイルの取得

詳細なライセンスファイルについては、以下のウェブサイトを参照してください：

- AndroidX: [opensource.google/licenses](https://opensource.google/licenses/)
- Kotlin: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- MediaPipe: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- Hugging Face: [huggingface.co](https://huggingface.co)
- Gemma: [Gemma Terms](https://ai.google.dev/gemma/terms)
- AppAuth: [openid/AppAuth-Android](https://github.com/openid/AppAuth-Android)
- Markwon: [noties/Markwon](https://github.com/noties/Markwon)

---

**最終更新**: 2026年4月1日  
**注意**: このドキュメントは参考情報です。法的に有効なライセンス条項については、各プロジェクトの公式ページを参照してください。
