# Qwen3 Voice Studio

Nezumi AI Mini App for `wavekat/Qwen3-TTS-0.6B-Base-ONNX`.

## Install

1. `example/miniapps/qwen3-tts/` を `manifest.json` をルートにしたZIPへ固める
2. Nezumi AIのMini App Managerで開発用Mini Appとしてインストールする
3. `モデルを準備`を押し、INT4またはFP32を選ぶ
4. 参照音声と書き起こしを入力する

モデルファイルはHugging FaceからApp Dataへ保存されます。`.onnx` と `.onnx.data` は同じディレクトリに配置されます。

通常の「モデルを準備」では巨大なEmbedding `.npy` はダウンロードしません。`text_embedding.npy` は単体で1GB超あるため、推論実装が必要とする範囲だけを取得する段階で追加します。

## Current scope

- Mini App manifest and permission declaration
- Hugging Face model file download and resume through `nezumi.download`
- Shared-file presence checks for ONNX external data
- Large embedding NPY files are excluded from the default model download and are instead fetched lazily, right before generation, the first time they're needed
- Reference audio selection, preview, validation, and text controls
- Full on-device six-model inference pipeline: `tokenizer_encoder` → `speaker_encoder` → `talker_prefill` → (`talker_decode` + `code_predictor` loop, frame by frame) → `vocoder`, wired through `nezumi.onnx.*`
- Byte-level BPE text tokenization from `tokenizer/tokenizer.json`
- A from-scratch Slaney-mel spectrogram front-end (24kHz / n_fft 1024 / hop 256 / 128 mel bins) for the speaker encoder input
- `files.readRange`-based row lookups against the multi-gigabyte embedding `.npy` files, so `text_embedding.npy` (1.2GB+) is never loaded into memory wholesale
- KV-cache marshaling for both the 28-layer talker and 5-layer code predictor, matching the shapes in `onnx_io_summary.txt`
- WAV export of the generated waveform

### Known limitations / assumptions worth validating

- The mel front-end parameters are taken from third-party reverse-engineering of `Qwen3TTSSpeakerEncoder` (not from an official `preprocessor_config.json`, which does not appear to be published for this repo). If cloning similarity is poor, this is the first place to check.
- The ICL prompt assembly (`buildIclSequence`) follows the structural description in the research notes but has not been validated against the official Python reference tensor-for-tensor. In particular, the exact token IDs for `<think>` / `<think_bos>` / `<language>` / `<think_eos>` are resolved from the tokenizer's `added_tokens` where possible; if they're absent from the shipped `tokenizer.json`, that codec-prefix segment is silently skipped rather than guessed.
- Byte-level BPE encoding here skips the regex-based pre-tokenization step Qwen normally applies before merging; it still produces valid, decodable tokens from the same vocabulary, but token boundaries may differ subtly from the reference tokenizer on some inputs (e.g. around whitespace runs).
- Generation is capped at `MAX_NEW_FRAMES` (600 frames, ~48s) as a safety bound.

## Model

- Repository: https://huggingface.co/wavekat/Qwen3-TTS-0.6B-Base-ONNX
- License: Apache-2.0
- Variants: FP32 and INT4
- Languages: English, Chinese, Japanese, Korean, German, French, Spanish, Italian, Portuguese, Russian
