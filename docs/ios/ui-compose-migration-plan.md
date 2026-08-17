# UI面のCompose Multiplatform化 移行設計

対象: `presentation/ui/fragment/*`, `presentation/ui/adapter/*`, `presentation/ui/screen/*`, `presentation/ui/composable/*`

## 0. 総評: 想定より条件が良い

実装を精査した結果、**nezumi-aiのUIは「XML＋View」ではなく「XMLは骨組みだけで、中身は徹底してComposeView埋め込み」という構成**であることが確認できました。

- 11個のFragmentのうち10個は、`onCreateView`が丸ごと1枚の`ComposeView`を返すだけ（Fragmentは実質「Composeの入れ物」）
- 唯一例外の`ChatFragment`(3,618行)も、XML内の要素は「RecyclerView 1個 + ComposeView 8個 + ボタン/入力欄などの薄いView」で構成され、メッセージ本文の描画自体(`MessageAdapter`のViewHolder内)も最終的に`setContent{}`でComposeに委譲している
- つまり**画面の「見た目を作るロジック」はほぼ100%既にComposableとして書かれている**

→ Compose Multiplatform化の作業は「新しいUIをゼロから書く」のではなく、**「Fragment/XML/RecyclerViewという配線層を剥がして、既存のComposableを`commonMain`に移す」**作業が主になります。

---

## 1. 画面別の移行難易度

### 🟢 難易度: 低（そのままcommonMain移設に近い）

FragmentがComposeViewの入れ物でしかない画面。Fragment自体を`androidx.navigation.compose`の`NavHost`のComposable destinationに置き換えれば、中身のComposableはほぼ無改造で`commonMain`へ移せます。

| Fragment | 対応するComposable | 備考 |
|---|---|---|
| `ImageGenFragment` | `ImageGenScreen`(既に`ui/screen`配下) | ViewModelはフェーズ順にKMP化必要 |
| `SessionListFragment` | `SessionListScreen`(既に`ui/screen`配下) | 同上 |
| `SettingsComposeFragment` | 設定系Composable | 同上 |
| `PresetSettingsFragment` | プリセット設定Composable | 同上 |
| `ToolsSettingsFragment` | ツール設定Composable | 同上 |
| `ModelSettingsFragment` | モデル設定Composable | 同上 |
| `SetupWizardFragment` | `SetupWizardScreen` | 同上 |
| `HelpFragment` | ヘルプ表示Composable | 静的コンテンツ中心、最も移設しやすい |
| `LogsFragment` | ログ表示Composable | 同上 |
| `LicenseFragment` | ライセンス表示Composable（`LicenseAdapter`はRecyclerView版なので要`LazyColumn`化） | ライセンス一覧は単純なリストなので`LazyColumn`化は容易 |

### 🟡 難易度: 中〜高（RecyclerView→LazyColumn化が必要）

| 画面 | 内容 | 課題 |
|---|---|---|
| `ChatFragment` + `MessageAdapter`(1,414行) | メッセージ一覧のRecyclerView | `RecyclerView`を`LazyColumn`に置き換える必要あり。差分描画・スクロール位置制御・variant切り替えUIなど、RecyclerView特有の最適化(ViewHolderリサイクル、`DiffUtil`等)をComposeの再結合(recomposition)モデルに翻訳する設計が必要（詳細は後述） |
| `LicenseAdapter` | RecyclerViewベース | `LazyColumn`化で解消 |

### 🟢 訂正済み: 当初「純XML実装」としていた画面（実際は違った）

再確認の結果、以下は「純XML/View実装、新規Compose化が必要」という当初の分類が誤りだったことが判明。

| 画面 | 実際の実装 | 訂正内容 |
|---|---|---|
| `ModelManagementFragment` | わずか7行、`class ModelManagementFragment : ModelSettingsFragment()`という継承のみ | 独立した画面ではなく、既にComposeベースの`ModelSettingsFragment`をそのまま流用するエイリアス。新規実装は不要で、`ModelSettingsFragment`の移行が終われば自動的に解決する |
| `ModelErrorDialogFragment` | `AlertDialog.Builder`（素のAndroid Dialog API）。XMLレイアウトも未使用 | XMLベースではなく、そもそもComposeもXMLも使わない最小限のDialog実装。Compose版`AlertDialog`への置き換えは必要だが、実装量はごく小さい |
| `BenchmarkFragment` | （別途確認: 廃止・作り直し予定のためこのプロジェクトの移行対象から除外） | 対象外 |

**「Fragment」という名前だけでは実装方式（View/XML/Compose/素のDialog API等）は判別できない**ため、各画面は個別にコードを確認して判断する必要がある。

---

## 2. `ChatFragment` / `MessageAdapter` の詳細分析

ここが今回のUI移行における最大の難所です。

### 現状の構造

```
fragment_chat.xml
├─ RecyclerView (messages_recycler_view) ← MessageAdapterが管理
│    ├─ UserMessageViewHolder (item_message_user.xml + binding直接操作)
│    └─ AiMessageViewHolder (item_message_ai.xml + 内部でComposeView.setContent{}を複数回呼ぶ)
├─ ComposeView × 8 (ヘッダーアクション、コンテキストメーター、空状態、応答中表示、
│                    ツールコール進捗、下までスクロールボタン、メディアプレビュー、
│                    モデルロードオーバーレイ)
└─ 通常View (入力欄, 送信/マイクボタン, ヘッダー等)
```

`ChatFragment`のbinding参照77箇所の内訳を見ると、`messageInput`(15)や`messagesRecyclerView`(14)、各種ボタン類が中心で、**いずれもCompose標準コンポーネント(`TextField`, `LazyColumn`, `IconButton`等)で素直に置き換えられる要素**です。特殊なAndroid Viewの機能(例えばNestedScrollingの複雑な連携)に依存していないか、後続の実装フェーズで個別に要確認です。

### 移行方針

1. **`MessageAdapter`→`LazyColumn` + `items()`への置き換え**
   - `AiMessageViewHolder`内の`setContent{}`で呼ばれているComposable(Markdown表示、Thinking表示、ツールコールカード等)は`ui/composable`配下に既に部品化されているものが多い(`MarkdownText`, `InlineToolCallCard`, `ThinkingAndMediaComposables`等)。これらは**ほぼそのまま`LazyColumn`の各アイテムComposableとして再利用可能**。
   - `DiffUtil`によるリスト差分計算は、Composeでは`key = { message.id }`を`items()`に指定することで代替される（再結合の最適化はCompose側のメカニズムに任せられる）。
   - スクロール位置制御(「新着メッセージで自動スクロール」等)は`LazyListState`＋`scrollToItem`/`animateScrollToItem`で書き直しが必要。現状`RecyclerView.smoothScrollToPosition`等を使っている箇所を洗い出して置き換える。

2. **8個のComposeViewはそのまま`Column`内の兄弟Composableとして統合**
   - 元々独立したComposeView単位で`setContent{}`されているため、**1つの`ChatScreen()`ルートComposableの中に、対応するComposable呼び出しとして並べるだけ**で済むケースが多い想定。

3. **ChatFragment自体は「画面遷移の受け皿」としての役割のみに縮小し、最終的には`ChatScreen()`という1つのComposable関数に統合**
   - Android側は`Fragment`(またはCompose Navigation destination)がこの`ChatScreen()`を呼ぶだけ
   - iOS側でも将来的に同じ`ChatScreen()`をそのまま呼べる状態を目指す

---

## 3. 推奨する移行の進め方（実務ステップ）

### ステップ1: Navigation基盤の入れ替え（低リスク・小規模）
- `androidx.navigation:navigation-fragment-ktx`を`androidx.navigation:navigation-compose`（Compose Multiplatform対応版）に置き換え
- `NavHost`と`composable("route") { ScreenX() }`の骨組みを作る
- まずは🟢難易度の10画面から着手し、Fragmentを1つずつ`NavHost`のdestinationに差し替える。ViewModelはまだAndroid依存でも、画面の呼び出し方だけ変えれば動く状態を維持できる

### ステップ2: 🟢難易度画面をNavHostへ順次移設
- 各画面は既にComposableとして独立しているため、Fragmentのラッパーを剥がして`composable(...)`ブロックに直接置くだけで完了するケースが多い
- ここで各画面のViewModelがAndroid依存(Context等)を持っていないか同時に点検し、依存があれば前段で設計した`Platform*Provider`化を並行して進める

### ステップ3: `ChatFragment`の`ChatScreen()`統合
- 最初に8個のComposeView部分を`ChatScreen()`内のComposable呼び出しへ統合(RecyclerViewはまだ残したままでOK)
- 次に`MessageAdapter`を`LazyColumn`に置き換える（最大の作業量。スクロール制御・variant切り替えUIの移植を含む）
- 最後にFragment自体を`NavHost`のdestinationへ

### ステップ4: 残りの後始末（当初想定より作業量は小さい）
- `ModelManagementFragment`は`ModelSettingsFragment`の継承のみなので実質作業不要
- `ModelErrorDialogFragment`はComposeの`AlertDialog`への置き換えのみ（小規模）
- `BenchmarkFragment`は対象外（別途確認: 廃止・作り直し予定）

### ステップ5: `commonMain`への切り出し
- ここまででAndroidアプリ内が「Fragmentなし・Compose Navigation・全画面Composable」の状態になる
- この時点で初めて、Composable本体を`commonMain`（Compose Multiplatform）に移す作業に着手できる。ViewModel側のAndroid依存が残っている画面は、ViewModelのKMP化(前回設計したPlatform*Provider分離)と歩調を合わせて進める

---

## 4. この段階でのゴール設定

UI面の作業は2段階に分けて考えるのが安全です。

- **第1段階(今回のスコープ): 「Android内で」Fragment/XML/RecyclerViewを撤去し、全画面Composeに統一する**
  → これ自体はiOS対応と無関係に、Android版の技術的負債解消として単体で価値があります。KMP化しない場合でも着手して損はありません。
- **第2段階: 全Compose化されたコードを`commonMain`（Compose Multiplatform）へ実際に移動する**
  → 第1段階が完了していれば、ここは機械的な移動作業に近くなります。ViewModelのAndroid依存が残っていると画面ごとにブロックされるため、ViewModel分割(前回設計)と両輪で進める必要があります。

まずは**第1段階、特に難易度の低い10画面のNavHost移設から着手する**のが、リスクが低くすぐに成果が見える進め方です。
