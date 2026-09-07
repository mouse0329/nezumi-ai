package com.nezumi_ai.data.inference.remote;

import android.os.Bundle;
import com.nezumi_ai.data.inference.remote.IRemoteResultCallback;
import com.nezumi_ai.data.inference.remote.IRemoteStringCallback;
import com.nezumi_ai.data.inference.remote.IRemoteTokenCallback;

/**
 * 別プロセスで動作する推論エンジン (GGUF / LiteRT-LM) への共通 AIDL。
 *
 * AIInferenceEngine (Kotlin 側インターフェース) を Binder 越しに再現したもの。
 * エンジン固有の情報は [getEngineStatus] の Bundle 経由、および
 * 各 Remote アダプタの public メソッド経由で取得する。
 *
 * 推論・テンプレート操作など「サービス内のワーカースレッドで完了する処理」は
 * コールバック経由で結果を返す。Binder スレッドで長時間ブロックしないため、
 * Stub 側ではメソッドを抜けてから非同期に処理する。
 */
interface IRemoteInferenceEngine {
    /** モデルをロード。完了 / 失敗は callback で通知。 */
    void loadModel(String modelName, in Bundle config, IRemoteResultCallback callback);

    /** モデルをアンロード。完了 / 失敗は callback で通知。 */
    void unloadModel(IRemoteResultCallback callback);

    /**
     * テキストのみの推論。トークンは callback.onToken で逐次返す。
     * 終了は onComplete / 失敗は onError。
     */
    void inference(long sessionId, String prompt, in Bundle config, IRemoteTokenCallback callback);

    /**
     * マルチモーダル推論。画像・音声は Binder サイズ上限 (1MB) を避けるため
     * バイナリではなく「アプリ内部ストレージ上の一時ファイルパス」で渡す。
     * 受け取ったプロセスは読み終わったらファイルを削除する。
     */
    void inferenceWithMedia(long sessionId, String prompt,
                            in String[] imagePaths, in String[] audioPaths,
                            in Bundle config, IRemoteTokenCallback callback);

    /** 推論キャンセル (fire-and-forget)。 */
    oneway void cancelInference();

    /** エンジンが利用可能か。軽量な判定のみ行い、ネイティブ初期化は行わないこと。 */
    boolean isAvailable();

    /**
     * エンジン固有の状態を Bundle で返す。
     * GGUF: hasGgufChatTemplate / gpuBackendFallbackOccurred / actualGpuBackend 等
     * LiteRT-LM: loadedBackend 等
     */
    Bundle getEngineStatus();

    // ─── GGUF 固有 (LiteRT 側では no-op / デフォルト値) ─────────

    void clearKvCacheIfLoaded();
    void requestForceClearBeforeNextInference();
    void formatWithGgufChatTemplate(String messagesJson, boolean enableThinking,
                                    IRemoteStringCallback callback);
    void formatWithJinjaChatTemplate(String messagesJson, String chatTemplate,
                                     boolean enableThinking, IRemoteStringCallback callback);
    /** parseGgufChatOutput の結果を {"content":..., "reasoning_content":...} JSON で返す。 */
    void parseWithGgufChatTemplate(String output, boolean isPartial,
                                   IRemoteStringCallback callback);

    // ─── LiteRT-LM 固有 (GGUF 側では no-op) ────────────────────

    oneway void markSessionHasMedia(long sessionId);
    oneway void clearSessionMediaHistory(long sessionId);
    /** forceReset() 相当 (Engine.close() を経ない強制無効化)。 */
    void forceReset(IRemoteResultCallback callback);

    // ─── プロセス管理 ──────────────────────────────────────────

    /**
     * このサービスを動かしているプロセスの PID を返す。
     * killRemoteProcess 後の死亡確認 (killProcess 送信済みプロセスの生存監視) に使う。
     */
    int getRemotePid();

    /**
     * プロセス自身に終了を指示する (自殺方式)。
     * Stub 側で unloadModel 相当のクリーンアップ後に
     * Process.killProcess(Process.myPid()) を呼ぶ。
     */
    oneway void requestProcessExit();

    /**
     * GGUF プロセス用: ネイティブ GPU バックエンド (OPENCL / VULKAN) が
     * 実際に初期化可能かを llama.cpp 側でプローブする。
     * :main プロセスで実行するとバックエンド初期化が main に残ってしまうため、
     * 必ず :gguf プロセス内で行う。
     */
    boolean probeGpuBackend(String backend);

    /** GGUF プロセス用: ビルドに含まれる GPU バックエンド名の一覧を返す。 */
    String[] getCompiledGpuBackends();

    /**
     * GGUF プロセス用: Qwen3-TTS のデバッグ合成 (設定画面のデバッグ機能)。
     * llama_bridge をメインプロセスにロードしないため、TTS も :gguf 側で実行する。
     * 結果は {"ok":true,...} / {"ok":false,...} 形式の JSON 文字列で callback に返す。
     */
    void ttsSynthesize(String modelPath, String tokenizerPath, String text,
                       String speakerPath, String outPath, int nThreads,
                       int nPredict, int seed, IRemoteStringCallback callback);
}
