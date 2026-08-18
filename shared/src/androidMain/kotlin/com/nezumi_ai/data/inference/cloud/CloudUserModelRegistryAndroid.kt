package com.nezumi_ai.data.inference.cloud

import android.content.Context

/**
 * [CloudUserModelRegistry] の Android Context 版 API (委譲層)。
 * キャッシュ無効化は commonMain の onModelListChanged コールバック経由
 * (app 側 MyApplication が PresetModelCatalog.invalidateCache を登録)。
 * メンバー関数との名前衝突回避のため `*ForContext` 名。
 */

fun CloudUserModelRegistry.listForContext(context: Context): List<String> =
    list(CloudStoresHolder.userModels(context))

fun CloudUserModelRegistry.addForContext(context: Context, modelId: String) =
    add(CloudStoresHolder.userModels(context), modelId)

fun CloudUserModelRegistry.removeForContext(context: Context, modelId: String) =
    remove(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId)

fun CloudUserModelRegistry.hasOverrideForContext(context: Context, modelId: String): Boolean =
    hasOverride(CloudStoresHolder.userModels(context), modelId)

fun CloudUserModelRegistry.getOverrideApiKeyForContext(context: Context, modelId: String): String =
    getOverrideApiKey(CloudStoresHolder.secure(context), modelId)

fun CloudUserModelRegistry.getOverrideBaseUrlForContext(context: Context, modelId: String): String =
    getOverrideBaseUrl(CloudStoresHolder.secure(context), modelId)

fun CloudUserModelRegistry.saveOverrideForContext(context: Context, modelId: String, apiKey: String, baseUrl: String) =
    saveOverride(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId, apiKey, baseUrl)

fun CloudUserModelRegistry.resolveApiKeyForContext(context: Context, modelId: String, provider: CloudApiKeyStore.Provider): String =
    resolveApiKey(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId, provider)

fun CloudUserModelRegistry.resolveBaseUrlForContext(context: Context, modelId: String, provider: CloudApiKeyStore.Provider): String =
    resolveBaseUrl(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId, provider)

fun CloudUserModelRegistry.isConfiguredForContext(context: Context, modelId: String): Boolean =
    isConfigured(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context), modelId)

fun CloudUserModelRegistry.configProvider(context: Context): DefaultCloudModelConfigProvider =
    DefaultCloudModelConfigProvider(CloudStoresHolder.userModels(context), CloudStoresHolder.secure(context))
