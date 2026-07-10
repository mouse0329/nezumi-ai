# MNN 自前エンジン計画（CuteYukiMix / SD1.5 向け）

Local Dream の **バイナリを捨てて**、MNN で SD1.5 を自前実装する前提の計画です。  
目標は **CuteYukiMix（および同形式の `.mnn`）が動くこと** と、必要なら **既存 LocalDreamModule との段階的互換** です。

> **実装の入口**: [`mnn-sd-engine/`](../mnn-sd-engine/)（Phase 0 スキャフォールド）  
> **HTTP 互換仕様（観測ベース）**: [`mnn-sd-engine/optional/http_server/protocol.md`](../mnn-sd-engine/optional/http_server/protocol.md)

---

## 0. ゴール / 非ゴール

### ゴール（MVP → v1）

| 優先 | 内容 |
|------|------|
| Must | txt2img、SD1.5、512 前後、CuteYukiMix 相当の MNN 重み |
| Must | 進捗コールバック、キャンセル、エラーを呼び出し元に返す |
| Must | ライセンスを自分で説明できる（エンジン Apache/MIT 系） |
| Should | OpenCL（Adreno）オプション |
| Should | 既存アプリから差し替えやすい API |
| Could | img2img / inpaint / LoRA |
| Could | Local Dream と **同じ HTTP プロトコル** |

### 非ゴール（最初はやらない）

- QNN / NPU（後続フェーズ）
- SDXL / Flux
- local-dream の `.so` 再利用
- xororz zip の再配布を前提にした製品設計（自前変換 + NOTICE を原則に）

---

## 1. アーキテクチャ方針

**コアは同一プロセス JNI（方式 A）**。HTTP 互換は optional の薄いアダプタ（方式 B）として後付け。

```
App (Kotlin) ──JNI──► libmnn_sd_engine.so ──► MNN (OpenCL/CPU)
                └── optional: HTTP :18081 (移行用)
```

既存 nezumiai との関係:

| 現状 | 移行後 |
|------|--------|
| `LocalDreamModule` → 子プロセス `libstable_diffusion_core.so` → HTTP | `MnnSdModule` → JNI → `libmnn_sd_engine.so` |
| `EngineManager.acquireLocalDream()` | 同 API 形状の `MnnSdModule` に差し替え可能 |
| OpenCL 448px ガード (`OPENCL_SAFE_MAX_SIDE`) | エンジン設定として C API に昇格 |

---

## 2–14. （計画本文はチャット版と同一）

フェーズ・ライブラリ選定・リスク等の詳細は本ドキュメント作成時の計画書を参照。  
実装タスクは `mnn-sd-engine/README.md` のチェックリストに追従する。

---

## 15. 次の成果物（作成済み）

| 成果物 | パス |
|--------|------|
| C API ヘッダ | `mnn-sd-engine/include/mnn_sd/` |
| HTTP L1 仕様（観測） | `mnn-sd-engine/optional/http_server/protocol.md` |
| Phase 0 CMake + JNI | `mnn-sd-engine/CMakeLists.txt`, `android/jni/` |
| モデルライセンス雛形 | `mnn-sd-engine/tools/MODEL_LICENSE.md` |

**Phase 0 の最初のコマンド**: `mnn-sd-engine/README.md` 参照。
