package com.nezumi_ai.presentation.ui.model

/**
 * The single source of truth for the licenses shown by every platform UI.
 * Platform adapters resolve each key to their localized resources.
 */
object LicenseCatalog {
    val entries: List<LicenseCatalogEntry> = listOf(
        "project",
        "androidx",
        "navigation",
        "room",
        "workmanager",
        "lifecycle",
        "biometric",
        "kotlin",
        "kotlin_coroutines",
        "kotlin_serialization",
        "compose",
        "richtext",
        "jlatexmath",
        "litertlm",
        "tflite",
        "onnxruntime",
        "open_nsfw",
        "image_safety_xs",
        "appauth",
        "coil",
        "okhttp",
        "llamacpp",
        "llamarn",
        "ggml",
        "nlohmann",
        "anyascii",
        "mnn",
        "huggingface",
        "gemma",
        "downloaded_models",
        "voicevox",
        "voicevox_libraries",
        "apache_poi",
        "xmlbeans",
        "woodstox",
        "pdfbox_android",
        "chaquopy",
        "markitdown",
        "markitdown_deps",
        "jsoup",
        "flexmark",
    ).map(::LicenseCatalogEntry)
}

data class LicenseCatalogEntry(val key: String)
