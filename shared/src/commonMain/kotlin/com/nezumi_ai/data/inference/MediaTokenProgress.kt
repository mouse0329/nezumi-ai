package com.nezumi_ai.data.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * マルチモーダル トークン読込のリアルタイム進捗。
 * JNI の MediaProgressCallback から更新される。
 */
object MediaTokenProgress {
    private val _stage = MutableStateFlow("")
    private val _tps = MutableStateFlow(0f)
    private val _tokens = MutableStateFlow(0)

    val stage: StateFlow<String> = _stage.asStateFlow()
    val tps: StateFlow<Float> = _tps.asStateFlow()
    val tokens: StateFlow<Int> = _tokens.asStateFlow()

    fun update(stage: String, tokens: Int, tps: Float) {
        _stage.value = stage
        _tokens.value = tokens
        _tps.value = tps
    }

    fun clear() {
        _stage.value = ""
        _tokens.value = 0
        _tps.value = 0f
    }
}
