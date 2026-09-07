package com.nezumi_ai.data.inference.remote;

/**
 * トークンストリーミング用コールバック。
 *
 * oneway にして呼び出し元 (推論プロセス) の Binder スレッドをブロックしない。
 * トークンごとの Binder transaction になるため、必要なら将来バッチ化を検討する。
 */
oneway interface IRemoteTokenCallback {
    void onToken(String delta);
    void onComplete();
    void onError(String message);
}
