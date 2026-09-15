# リポジトリラベル定義

Issue テンプレート（`.github/ISSUE_TEMPLATE/*.yml`）が参照するラベルです。
GitHub の Issue テンプレートは、ここに書かれたラベルを自動生成しません。リポジトリの Settings → Labels で事前に作成しておく必要があります。

| ラベル名 | 色 (例) | 用途 |
|---|---|---|
| `bug` | `#d73a4a` | `bug_report.yml` が自動付与 |
| `build` | `#fbca04` | `build_issue.yml` が自動付与 |
| `enhancement` | `#a2eeef` | `feature_request.yml` が自動付与 |

GitHub CLI があれば以下でまとめて作成できます。

```bash
gh label create bug --color d73a4a --description "アプリの不具合" --force
gh label create build --color fbca04 --description "ビルド関連の問題" --force
gh label create enhancement --color a2eeef --description "新機能・改善の提案" --force
```

（このファイルは GitHub Actions によるラベル自動同期を意図したものではなく、手動作成のためのメモです。将来的に `.github/labels.yml` + ラベル同期アクションを導入する場合はこのファイルを置き換えてください。）
