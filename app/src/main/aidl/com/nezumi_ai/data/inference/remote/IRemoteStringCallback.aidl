package com.nezumi_ai.data.inference.remote;

/**
 * 文字列を1つ返す非同期コールバック (chat template 適用 / パース結果など)。
 */
oneway interface IRemoteStringCallback {
    void onResult(String value);
    void onError(String message);
}
