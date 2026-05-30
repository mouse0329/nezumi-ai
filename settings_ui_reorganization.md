# 設定UI整理方針

## 問題点
- llama.rn専用とLiteRT-LM専用の設定が混在していて分かりづらい

## 整理後の構成

### 1. 共通推論パラメータ（InferenceParamsCard）
**対象**: 全モデル（LiteRT-LM / GGUF）
- コンテキストサイズ
- 最大トークン数
- バックエンド（CPU/GPU/NPU）
- Temperature
- Top-K
- Top-P
- 自動圧縮
- プリロードメモリ警告閾値

### 2. GGUF / llama.cpp 設定（新規: GgufLlamaCppSettingsCard）
**対象**: インポートしたGGUFモデル専用（llama.rnエンジン）

#### 基本設定（折りたたみ）
- CPUスレッド数
- GPUレイヤー数
- バッチサイズ
- 内部バッチサイズ (n_ubatch)
- kvUnified
- RoPE周波数基数
- RoPE周波数スケール

#### パフォーマンス最適化（折りたたみ）
- MTP（投機的デコーディング）
  - 有効/無効トグル
  - Draft トークン数スライダー（1-16）
- Flash Attention（有効/無効）
- 動的バッチサイズ
  - 有効/無効トグル
  - プロンプト用バッチサイズ
  - 生成用バッチサイズ
- KVキャッシュ最適化（有効/無効）
- コンテキストシフト（有効/無効）

### 3. LiteRT-LM 設定（LiteRtSettingsCard）
**対象**: 組み込みモデル（Gemma 3n/4）専用
- 投機的デコーディング（LiteRT版）
- requireMultimodal

## 表示順序
推論タブ:
1. 共通推論パラメータ
2. GGUF / llama.cpp 設定
3. LiteRT-LM 設定

## 実装方針
- InferenceParamsCard(): 共通設定のみに簡素化
- GgufLlamaCppSettingsCard(): GGUF専用設定を新規作成
  - 「基本設定」と「パフォーマンス最適化」の2つの折りたたみセクション
- LiteRtSettingsCard(): LiteRT専用設定（既存）

## ユーザーへの説明
各カードの冒頭に説明文を追加:
- 共通: "全てのモデル（LiteRT-LM / GGUF）で共通の設定"
- GGUF: "インポートした GGUF モデル専用の設定（llama.rn エンジン）"
- LiteRT: "組み込みモデル（Gemma 3n/4）専用の設定"
