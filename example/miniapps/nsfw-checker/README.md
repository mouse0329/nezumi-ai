# 画像コンテンツチェッカー

Nezumi AI Mini App SDK v1.1 の `nezumi.onnx` を使って画像を判定するサンプルです。

## インストール

1. `example/miniapps/nsfw-checker/` の中身を、`manifest.json` がZIPのルートになるように固める
2. Nezumi AIのMini App Managerから開発用Mini Appとしてインストールする
3. Mini App開発者モードを有効にして、画像を選択する

初回起動時に `open_nsfw.onnx` をApp Dataへダウンロードします。モデルは `user-data/open_nsfw.onnx` に保存されます。

## SDK使用箇所

- `nezumi.download` でモデルを取得
- `nezumi.files.exists` でモデルの存在を確認
- `nezumi.onnx.open` / `getInputs` / `createTensor` / `run` で推論
- `__f32b64__:` 形式のFloat32出力をブラウザ側でデコード
