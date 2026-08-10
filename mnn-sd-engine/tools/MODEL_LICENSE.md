# SD1.5 / SDXL 系モデル — ライセンス記録テンプレート

> **開発者向けテンプレート**。新しい変換モデルを Hugging Face の `Mouserat/*-mnn` リポジトリとして
> 公開する際は、このテンプレートをコピーして該当リポジトリの `README.md` に反映すること
> （実際のライセンス全文・要約は各リポジトリの README/LICENSE.md が一次情報）。
>
> **配布方式（現状の実装）**: モデルは APK に同梱しない。ユーザーがアプリ内の「画像生成モデル」
> 画面からモデルを選択すると、ダウンロード直前に `ImageModelBrowser.fetchLicenseInfo()` が
> 対象リポジトリの `LICENSE.md`（無ければ `README.md`）を取得し、ライセンス種別・本文・
> リポジトリへのリンクを確認ダイアログに表示する。ユーザーが同意しない限りダウンロードは
> 開始されない（`ModelSettingsFragment.ImageModelLicenseConfirmDialog`）。
> そのため、このファイル単体を「エンドユーザー向け NOTICE」として同梱する必要はない。

## モデル識別

| 項目 | 値 |
|------|-----|
| 表示名 | （例: CuteYukiMix、Illustrious-XL-v2.0 など、利用するチェックポイント名） |
| ベース | （例: Stable Diffusion 1.5 / SDXL） |
| 形式 | MNN（SD1.5: `clip.mnn`, `unet.mnn`, `vae_decoder.mnn`, `tokenizer.json` / SDXL: `clip1.mnn`, `clip2.mnn`, `unet.mnn`, `vae_decoder_fp16.mnn`） |
| 取得元 URL | _（記入）_ |
| 取得日 | _YYYY-MM-DD_ |
| 変換ツール | `mnn-sd-engine/conversion/convert_hf_to_mnn_sd.py`（SD1.5）または `convert_hf_to_mnn_sdxl.py`（SDXL）（バージョン・実行コマンドを記入） |

## 元モデルライセンス

_（CreativeML Open RAIL-M 等、元配布ページから全文または要約を転記。この内容は公開する
Hugging Face リポジトリの README.md の「License」セクションにもそのまま反映すること —
アプリのダウンロード前確認ダイアログはそこを参照して表示する。）_

## 利用条件（要約）

- 商用利用: _可 / 不可 / 条件付き_
- 再配布: _可 / 不可 / 条件付き_
- クレジット表記: _要 / 不要_
- 用途制限: _（未成年者の性的搾取、偽情報生成、嫌がらせ、差別など、元ライセンスの
  use-based restrictions があれば転記）_

## 変換・再配布について

- 変換元 zip（CivitAI / HuggingFace 配布物）からの単純な再配布は行わない
- 公開する MNN 重みは、上記ライセンスの範囲内で本プロジェクトのスクリプトにより
  **自前変換**（フォーマット変換・量子化のみ、再学習なし）したものとする
- 変換後の重みは `Mouserat/*-mnn` として Hugging Face 上で個別に公開し、
  アプリはそこからユーザーの選択に応じてダウンロードする（APK 非同梱）

## 参照

- [MNN SD エンジン計画](../docs/MNN_SD_ENGINE_PLAN.md)
- アプリ側のライセンス確認実装: `ImageModelBrowser.kt` (`fetchLicenseInfo`) /
  `ModelSettingsFragment.kt` (`ImageModelLicenseConfirmDialog`)
- リポジトリ全体の NOTICE: `/NOTICE`（"ON-DEVICE IMAGE GENERATION MODELS" セクション）

