package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.presentation.viewmodel.platform.PlatformSdModelPathResolver

/**
 * クラスタ F (画像生成ツール連携) のキューイング状態と SD モデルパス解決を切り出す。
 *
 * ファイルシステム走査という Android 依存部分は [PlatformSdModelPathResolver] に押し込み、
 * このクラス自体は「どのパスを使うか」の判断とキュー状態の保持に専念する。
 */
class ImageToolInvoker(
    private val sdModelPathResolver: PlatformSdModelPathResolver
) {
    /** ツール呼び出しでモデル名指定がない場合のデフォルト SD モデルパス。 */
    fun defaultSdModelPath(): String = sdModelPathResolver.findAvailableSdModelPath()

    /** list_sd_models ツールが返す "name" から実パスを解決する。不明なら null。 */
    fun resolveSdModelPathByName(modelName: String): String? =
        sdModelPathResolver.resolveSdModelPathByName(modelName)

    /**
     * ツール引数の model 指定を実パスに正規化する。
     * 空指定はデフォルトパス、名前指定はパス解決、解決失敗時は null。
     */
    fun resolveSdModelPathForToolArg(modelArg: String?): String? {
        val name = modelArg?.trim().orEmpty()
        if (name.isEmpty()) {
            return defaultSdModelPath().takeIf { it.isNotEmpty() }
        }
        return sdModelPathResolver.resolveSdModelPathByName(name)
    }
}
