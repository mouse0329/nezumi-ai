# Compose統一化 実行計画（Fragment撤去 第1段階）

対象: `presentation/ui/fragment/*`, `presentation/ui/adapter/*`, `app/src/main/res/navigation/nav_graph.xml`, `MainActivity.kt`

前回の`ui-compose-migration-plan.md`（画面別の難易度調査）を土台に、実装エージェントに渡せる具体的なタスク単位まで落とし込む。

---

## 0. 現状構成の実測結果

### ナビゲーション
- `androidx.navigation:navigation-fragment`（XMLベースのNavGraph、`nav_graph.xml`）を使用
- `MainActivity`(1,218行)が`NavHostFragment`/`findNavController`経由でFragment遷移を管理

### nav_graph.xml のdestination（11画面、`chatFragment`がstart）

```
chatFragment (start) / helpFragment / imageGenFragment / licenseFragment /
logsFragment / modelManagementFragment / modelSettingsFragment /
presetSettingsFragment / sessionListFragment / settingsFragment / setupWizardFragment
```

### 到達経路が見つからなかった画面2つ（調査完了）

調査の結果、以下2画面は`nav_graph.xml`に**登録されておらず**、コード内からのインスタンス化・トランザクション追加箇所も見つからなかった。ユーザー確認の結果、いずれも移行対象から除外することが確定した。

| Fragment | 状況 |
|---|---|
| `ToolsSettingsFragment` | **確認済み: 過去の遺産（未使用のレガシーコード）**。移行対象外 |
| `BenchmarkFragment` | **確認済み: 廃止され作り直される見込み**。移行対象外 |

**対応方針**:
- 両Fragmentとも**Compose移行の対象から除外**する。今回の移行タスクでは触らない
- 削除するかどうかは別途判断（今回の移行タスクの範囲外。到達不能コードの整理は別タスクとして切り出すのが望ましい）
- `ToolsSettingsFragment`関連のViewModel・レイアウトファイル等が他のどこかから参照されていないかだけは、後片付け（タスク14）のタイミングで最終確認する

### ⚠️ 前提の訂正: 「Fragment」という名前は実装方式を意味しない

再確認の結果、これまで「純XML実装」と分類していた画面の判定に誤りがあったことが判明した。**Fragment/Activityという名前だけでは中身がView/XMLベースかComposeベースかは判別できない**ため、今回改めて全対象画面を実装パターンで再分類した。

| Fragment | 実際の実装 | 訂正内容 |
|---|---|---|
| `HelpFragment`〜`ImageGenFragment`（9画面） | `ComposeView`ベース、XML inflateなし | 前回の分類通り（変更なし） |
| `ChatFragment` | `ComposeView` 8箇所 + XML(RecyclerView等) 3箇所の混在 | 前回の分類通り（変更なし） |
| **`ModelManagementFragment`** | **わずか7行、`class ModelManagementFragment : ModelSettingsFragment()` という継承のみ** | **訂正**: 独立した画面ではなく、既にComposeベースの`ModelSettingsFragment`をそのまま流用するエイリアス。「純XML実装で新規Compose化が必要」としていたのは誤り。実質的な作業は不要で、`ModelSettingsFragment`の移行（タスク7）が終われば自動的に解決する |
| **`ModelErrorDialogFragment`** | `AlertDialog.Builder`（素のAndroid Dialog API）。XMLレイアウトファイルも使っていない | **訂正**: XMLベースではなく、そもそもComposeもXMLも使わない最小限のDialog実装。`AlertDialog`（Compose版）への置き換えは想定通り必要だが、実装量はごく小さい |

**結論**: 当初「新規Compose化が必要な純XML画面」としていた2画面のうち、実際に新規実装が必要なのは`ModelErrorDialogFragment`（それも小規模）のみ。`ModelManagementFragment`は独立タスクとして扱う必要がなくなった。

---

## 1. タスク分割（実装エージェントへ依頼する単位）

前回調査の難易度分類を、実行順に並べ替えたチケット単位に分割する。**1タスク=1コミット=1ビルド確認**を基本单位とする。移行対象は`ToolsSettingsFragment`・`BenchmarkFragment`を除いた9画面 + `ChatFragment` + `ModelManagementFragment`/`ModelErrorDialogFragment`。

### タスク1: Navigation基盤の入れ替え
- `navigation-compose`依存を追加
- `MainActivity`に`NavHost`を新設（既存の`NavHostFragment`とは並行稼働させず、置き換える前提）
- **この時点ではまだ画面の中身は移行しない**。空のComposable destinationを仮に1つ用意して配線が通ることだけ確認する
- ビルド確認必須（Navigationの入れ替えは全画面に影響するため、最初に土台を固める）

### タスク2〜10: 低難易度9画面を1画面ずつNavHostへ移設
優先順位は依存の少なさ・ViewModelの複雑さで並べる。

| 順 | 画面 | 備考 |
|---|---|---|
| 1 | `HelpFragment` | 静的コンテンツ中心、最も安全な第一歩 |
| 2 | `LogsFragment` | 表示のみ、状態変更が少ない |
| 3 | `LicenseFragment` | `LazyColumn`化が必要（`LicenseAdapter`がRecyclerView） |
| 4 | `SessionListFragment` | DB読み取りのみ |
| 5 | `SetupWizardFragment` | 初回起動フローとの絡みを要確認 |
| 6 | `PresetSettingsFragment` | |
| 7 | `ModelSettingsFragment` | |
| 8 | `SettingsComposeFragment` | 設定全体の親画面、依存が広い可能性 |
| 9 | `ImageGenFragment` | `ImageGenViewModel`との連携を含む |

各タスクの中身:
1. 対象FragmentをNavHostの`composable("route") { XxxScreen(...) }`に置き換え
2. Fragment固有の処理（`onCreateView`, `arguments`経由のパラメータ受け渡し、`onViewCreated`のリスナー登録等）をComposable関数の引数・`LaunchedEffect`等に翻訳
3. ViewModelがAndroid依存（`Context`, `Activity`参照等）を持っていないか点検。あれば前回設計の`Platform*Provider`パターンを適用するかこのタスクでは据え置くか判断
4. ビルド確認 → 実機での画面遷移確認（可能なら）

### タスク11: `ChatFragment`の`ChatScreen()`統合（最大の作業量、複数タスクに分割）

サブタスクとしてさらに分割する。

- **11-a**: 8個のComposeView（ヘッダーアクション、コンテキストメーター、空状態、応答中表示、ツールコール進捗、下スクロールボタン、メディアプレビュー、モデルロードオーバーレイ）を1つの`ChatScreen()`内の兄弟Composableとして統合。この時点では`MessageAdapter`(RecyclerView)はまだ残す
- **11-b**: `MessageAdapter` → `LazyColumn`への置き換え
  - `AiMessageViewHolder`内の`setContent{}`呼び出し（Markdown表示・Thinking表示・ツールコールカード）を`LazyColumn`の`items()`内Composable呼び出しに変換
  - `DiffUtil`相当は`items(messages, key = { it.id })`で代替
  - スクロール制御（新着メッセージへの自動スクロール等）を`LazyListState` + `scrollToItem`/`animateScrollToItem`で書き直し
- **11-c**: `ChatFragment`自体をNavHostのdestinationへ移設、Fragment本体を削除

各サブタスクごとにビルド確認を挟む。特に11-bはリグレッションリスクが高いため、実装後に「メッセージ送信→受信→再生成→variant切り替え→スクロール」の一連の手動動作確認をWindows側でお願いする。

### タスク12: 残りの画面の後始末（作業量は小さい見込み）
- `ModelManagementFragment`: **実質作業不要**。中身は`class ModelManagementFragment : ModelSettingsFragment()`のみの継承クラス。タスク7（`ModelSettingsFragment`のNavHost移設）が終わった時点で、このFragment自体を`nav_graph.xml`から`ModelSettingsFragment`と同一destinationとして扱うか、そのまま残すかだけ判断すればよい
- `ModelErrorDialogFragment`: `AlertDialog.Builder`（素のDialog API、XML未使用）を、Compose版の`AlertDialog`に置き換える。小規模な作業

### タスク13: Fragment関連コードの最終清掃
- `nav_graph.xml`の削除
- `NavHostFragment`関連の残骸削除
- 未使用になった`ViewBinding`クラス・XMLレイアウトファイルの削除
- `ToolsSettingsFragment`・`BenchmarkFragment`関連の未使用コードが他から参照されていないか最終確認（削除するかどうかは別途判断、今回は現状維持でも可）
- 全画面が`NavHost` + Composableのみで構成されていることを最終確認

---

## 2. 各タスクでの検証項目（前回のChatViewModel分割と同じサイクルを踏襲）

1. **静的レビュー**（こちらで実施）:
   - 元のFragmentが持っていた処理（ライフサイクルコールバック、`arguments`経由の値、リスナー登録）が抜け落ちていないか
   - 新規に生まれた未配線コード・重複コードがないか
   - `Legacy`的な残骸を残していないか
2. **Windowsビルド確認**（`./gradlew assembleDebug`）
3. **可能な範囲で実機/エミュレータでの動作確認**（特にタスク11は必須）

---

## 3. 進め方の推奨

- タスク1（Navigation基盤）は影響範囲が広いため、単独でコミット・ビルド確認する
- タスク2〜10は難易度順に1画面ずつ、これまでのChatViewModel分割と同じ「小さくコミットして都度確認」のリズムで進める
- タスク11（ChatFragment）は最大の山場なので、他の全タスクが終わってから着手する。サブタスクごとに区切りビルドを挟む
- 全タスク完了後、`commonMain`への実移動（第2段階）に進む

---

## 4. エージェントへの最初の指示（案）

> Compose統一化（Fragment撤去）に着手します。まずタスク1として、`navigation-compose`を導入し、`MainActivity`に`NavHost`を新設してください。この時点では既存Fragmentの中身は移行せず、空のComposable destinationを1つ仮に用意して配線が通ることだけ確認してください。なお`ToolsSettingsFragment`（過去の遺産）と`BenchmarkFragment`（廃止・作り直し予定）は今回の移行タスクの対象外です。
