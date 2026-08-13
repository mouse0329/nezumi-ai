package com.nezumi_ai

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 現在フォアグラウンドにある Activity への弱参照を保持するシングルトン。
 *
 * 主な用途: web_fetch (JS描画版) が WebView を評価する際、真にウィンドウの
 * View 階層にアタッチしないと evaluateJavascript のコールバックが
 * コンポジタのフレーム生成待ちで返ってこないことがある (Chromium の制約)。
 * applicationContext だけでは Activity の DecorView にアクセスできないため、
 * ここで保持した参照から一時的に addView できるようにする。
 *
 * WeakReference を使うことで Activity のリーク（Application レベルの
 * シングルトンが Activity を強参照し続けてしまう）を防ぐ。
 */
object CurrentActivityHolder {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun clearIfCurrent(activity: Activity) {
        if (ref?.get() === activity) {
            ref = null
        }
    }

    /** 現在保持している Activity。破棄済み (isFinishing/isDestroyed) の場合は null を返す。 */
    fun get(): Activity? {
        val activity = ref?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity
    }
}
