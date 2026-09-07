package com.nezumi_ai.data.inference.remote;

/**
 * 成功 / 失敗のみを返す単純な非同期コールバック (loadModel / unloadModel 用)。
 */
oneway interface IRemoteResultCallback {
    void onSuccess();
    void onError(String message);
}
