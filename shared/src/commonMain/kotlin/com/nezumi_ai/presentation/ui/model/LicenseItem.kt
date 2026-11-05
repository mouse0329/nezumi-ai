package com.nezumi_ai.presentation.ui.model

/**
 * License画面で表示するリソース識別子。
 *
 * 文字列リソースの解決は各プラットフォームのUI層が担当する。
 */
data class LicenseItem(
    val titleRes: Int,
    val descriptionRes: Int,
    val urlRes: Int
)
