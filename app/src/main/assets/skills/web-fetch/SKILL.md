---
name: web-fetch
description: "How to read a web page with web_search and web_fetch, including JavaScript rendering and the static fallback."
---

# web-fetch

ユーザーがページの中身を知りたいときは、検索結果の URL を推測で埋めずに web_fetch で読む。

## 手順

1. 必要な URL がまだ無いときは web_search を使う。
2. 検索結果の実 URL を web_fetch に渡す。http と https だけ。
3. maxChars は必要な分だけ。既定は 4000、最大は 12000。
4. 返ってきた markdown だけを根拠に答える。本文が無いのにページ内容を作らない。

## JavaScript 描画

ツール設定「JavaScript を実行してから取得」がオンのとき、web_fetch は画面外のブラウザで描画してから Markdown にする。オフのときは静的 HTML だけ。

- `jsRendered` が true: ブラウザ描画の本文。
- `jsFallback` が true: 描画できなかったので静的取得に戻した。SPA だと本文が殻だけのことがある。そのときは別の URL を一回だけ試すか、取れなかったと短く伝える。
- 同じ URL を連続で何度も取り直さない。

## 失敗

- `invalid_url`: URL を http(s) に直せるときだけ直して一度だけ取り直す。
- ログイン壁、同意画面、ボット判定だけのページは、その旨を伝えて打ち切る。
- タイムアウトや `empty_content` のあと、同じ URL を繰り返さない。
