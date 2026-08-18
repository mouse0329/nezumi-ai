package com.nezumi_ai.data.inference.cloud

import android.content.Context

/**
 * [CloudUserModelRegistry] の Android Context ベース API (委譲層)。
 * 既存の呼び出し元シグネチャを維持する。
 *
 * 注: PresetModelCatalog のキャッシュ無効化は shared が app 側クラスを直接
 * 参照できない (逆方向依存) ため、commonMain 側の [CloudUserModelRegistry.onModelListChanged]
 * コールバック経由で行う。app 側 (MyApplication.onCreate) がそのコールバックに
 * PresetModelCatalog.invalidateCache() を登録する。ここでは直接呼ばない。
 */

fun CloudUserModelRegistry.list(context: Context): List<String> =
    list(CloudStoresHolder.userModels(context))

fun CloudUserModelRegistry.add(context: Context, modelId: String) {
    add(CloudStoresHolder.userModels(context), modelId)
}

fun CloudUserModelRegistry.remove(context: Context, modelId: String) {
    remove(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId)
}

fun CloudUserModelRegistry.hasOverride(context: Context, modelId: String): Boolean =
    hasOverride(CloudStoresHolder.userModels(context), modelId)

fun CloudUserModelRegistry.getOverrideApiKey(context: Context, modelId: String): String =
    getOverrideApiKey(CloudStoresHolder.secure(context), modelId)

fun CloudUserModelRegistry.getOverrideBaseUrl(context: Context, modelId: String): String =
    getOverrideBaseUrl(CloudStoresHolder.secure(context), modelId)

fun CloudUserModelRegistry.saveOverride(context: Context, modelId: String, apiKey: String, baseUrl: String) {
    saveOverride(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId, apiKey, baseUrl)
}

fun CloudUserModelRegistry.resolveApiKey(context: Context, modelId: String, provider: CloudApiKeyStore.Provider): String =
    resolveApiKey(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId, provider)

fun CloudUserModelRegistry.resolveBaseUrl(context: Context, modelId: String, provider: CloudApiKeyStore.Provider): String =
    resolveBaseUrl(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId, provider)

fun CloudUserModelRegistry.isConfigured(context: Context, modelId: String): Boolean =
    isConfigured(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId)

/** エンジン共通基底へ渡す [com.nezumi_ai.data.inference.CloudModelConfigProvider] を組み立てる。 */
fun CloudUserModelRegistry.configProvider(context: Context): DefaultCloudModelConfigProvider =
    DefaultCloudModelConfigProvider(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context))
