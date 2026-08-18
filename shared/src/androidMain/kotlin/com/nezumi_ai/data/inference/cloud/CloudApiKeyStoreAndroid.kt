package com.nezumi_ai.data.inference.cloud

import android.content.Context

/**
 * [CloudApiKeyStore] の Android Context ベース API。
 *
 * KMP 化で commonMain に移設したドメインロジックへの委譲層。
 * 既存の呼び出し元 (`CloudApiKeyStore.getApiKey(context, provider)` 等) の
 * シグネチャを維持するための拡張関数群であり、ロジックは持たない。
 * (app モジュールの旧 object CloudApiKeyStore はこのファイルに置き換わった)
 */

fun CloudApiKeyStore.setApiKey(context: Context, provider: CloudApiKeyStore.Provider, apiKey: String): Boolean =
    setApiKey(CloudStoresHolder.secure(context), provider, apiKey)

fun CloudApiKeyStore.getApiKey(context: Context, provider: CloudApiKeyStore.Provider): String =
    getApiKey(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.hasApiKey(context: Context, provider: CloudApiKeyStore.Provider): Boolean =
    hasApiKey(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.setBaseUrl(context: Context, provider: CloudApiKeyStore.Provider, baseUrl: String): Boolean =
    setBaseUrl(CloudStoresHolder.secure(context), provider, baseUrl)

fun CloudApiKeyStore.getBaseUrl(context: Context, provider: CloudApiKeyStore.Provider): String =
    getBaseUrl(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.isConfigured(context: Context, provider: CloudApiKeyStore.Provider): Boolean =
    isConfigured(CloudStoresHolder.secure(context), provider)

fun CloudApiKeyStore.clear(context: Context, provider: CloudApiKeyStore.Provider) =
    clear(CloudStoresHolder.secure(context), provider)
