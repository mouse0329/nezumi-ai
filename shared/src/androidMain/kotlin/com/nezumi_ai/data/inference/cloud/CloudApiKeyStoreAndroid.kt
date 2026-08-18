package com.nezumi_ai.data.inference.cloud

import android.content.Context

/**
 * [CloudApiKeyStore] の Android Context 版 API (委譲層)。
 *
 * commonMain 本体のメンバー関数と同名にすると Kotlin の解決規則でメンバーが
 * 常に優先され Context 版呼び出しが型不一致になるため、`*ForContext` 名で定義する。
 * 呼び出し元はこの名前に合わせて書き換え済み。
 */

fun CloudApiKeyStore.setApiKeyForContext(context: Context, provider: CloudApiKeyStore.Provider, apiKey: String): Boolean =
    setApiKey(CloudStoresHolder.secure(context), provider, apiKey)

fun CloudApiKeyStore.getApiKeyForContext(context: Context, provider: CloudApiKeyStore.Provider): String =
    getApiKey(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.hasApiKeyForContext(context: Context, provider: CloudApiKeyStore.Provider): Boolean =
    hasApiKey(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.setBaseUrlForContext(context: Context, provider: CloudApiKeyStore.Provider, baseUrl: String): Boolean =
    setBaseUrl(CloudStoresHolder.secure(context), provider, baseUrl)

fun CloudApiKeyStore.getBaseUrlForContext(context: Context, provider: CloudApiKeyStore.Provider): String =
    getBaseUrl(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.isConfiguredForContext(context: Context, provider: CloudApiKeyStore.Provider): Boolean =
    isConfigured(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.clearForContext(context: Context, provider: CloudApiKeyStore.Provider) =
    clear(CloudStoresHolder.secure(context), provider)
