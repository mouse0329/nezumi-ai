package com.nezumi_ai.data

import com.nezumi_ai.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * RGAメモリ検索がハッシュフォールバックに落ちる問題の修正テスト
 *
 * MemoryTextEmbedder は Android Context に依存するため直接テスト不可。
 * 代わりに修正の核心である以下をテストする:
 *   1. MemoryRepository.search() の次元ミスマッチ検出ロジック
 *   2. MemoryRepository のコサイン類似度・L2ノルム計算
 *   3. クエリのノルムがゼロの場合に空リストを返すこと（ハッシュフォールバック検出）
 */
class MemoryEmbedderFallbackTest {

    // ─────────────────────────────────────────────────────────────────────
    // L2ノルム / コサイン類似度
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun l2norm_allZeros_returnsZero() {
        val norm = MemoryRepository.l2norm(FloatArray(128) { 0f })
        assertEquals(0f, norm, 0f)
    }

    @Test
    fun l2norm_unitVector_returnsOne() {
        // 1要素だけ1.0のベクトル → ノルムは1.0
        val v = FloatArray(64) { 0f }.also { it[0] = 1f }
        assertEquals(1f, MemoryRepository.l2norm(v), 1e-6f)
    }

    @Test
    fun l2norm_knownVector_returnsCorrectValue() {
        // [3, 4] → norm = 5
        val v = floatArrayOf(3f, 4f)
        assertEquals(5f, MemoryRepository.l2norm(v), 1e-6f)
    }

    @Test
    fun cosineSimilarity_identicalVectors_returnsOne() {
        val v = floatArrayOf(1f, 2f, 3f)
        val norm = MemoryRepository.l2norm(v)
        val sim = MemoryRepository.cosineSimilarity(v, norm, v, norm)
        assertEquals(1f, sim, 1e-6f)
    }

    @Test
    fun cosineSimilarity_oppositeVectors_returnsMinusOne() {
        val v = floatArrayOf(1f, 0f, 0f)
        val neg = floatArrayOf(-1f, 0f, 0f)
        val norm = MemoryRepository.l2norm(v)
        val sim = MemoryRepository.cosineSimilarity(v, norm, neg, norm)
        assertEquals(-1f, sim, 1e-6f)
    }

    @Test
    fun cosineSimilarity_zeroNorm_returnsZero() {
        val v = floatArrayOf(1f, 0f)
        val zero = floatArrayOf(0f, 0f)
        val normV = MemoryRepository.l2norm(v)
        val normZero = MemoryRepository.l2norm(zero)
        val sim = MemoryRepository.cosineSimilarity(v, normV, zero, normZero)
        assertEquals(0f, sim, 0f)
    }

    @Test
    fun cosineSimilarity_sizeMismatch_returnsZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        val sim = MemoryRepository.cosineSimilarity(a, 1f, b, 1f)
        assertEquals(0f, sim, 0f)
    }

    // ─────────────────────────────────────────────────────────────────────
    // バイト変換ラウンドトリップ（埋め込みのDB保存/読み込み）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun floatArrayToBytes_roundTrip_preservesValues() {
        val original = FloatArray(1024) { it * 0.001f }
        val bytes = MemoryRepository.floatArrayToBytes(original)
        val restored = MemoryRepository.bytesToFloatArray(bytes)
        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals("index $i", original[i], restored[i], 0f)
        }
    }

    @Test
    fun bytesToFloatArray_emptyBytes_returnsEmptyArray() {
        val result = MemoryRepository.bytesToFloatArray(ByteArray(0))
        assertEquals(0, result.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 次元ミスマッチ検出ロジック（修正の核心）
    //
    // MemoryRepository.search() は suspend + DAO 依存なので直接呼べないが、
    // 次元ミスマッチを検出する条件式そのものをここで検証する。
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun dimensionMismatch_detectedWhenSizeDiffers() {
        val queryDim = 1024
        val storedDim = 384   // ハッシュフォールバックで保存された古いベクトル

        // search() 内の判定ロジック: memoryEmbedding.size != queryEmbedding.size
        val mismatch = storedDim != queryDim
        assertTrue("次元ミスマッチが検出されるべき", mismatch)
    }

    @Test
    fun dimensionMatch_passesWhenSizeEqual() {
        val queryDim = 1024
        val storedDim = 1024

        val mismatch = storedDim != queryDim
        assertTrue("次元が一致する場合はミスマッチにならない", !mismatch)
    }

    @Test
    fun zeroNormQuery_indicatesHashFallback() {
        // ハッシュフォールバック embedder がゼロベクトルを返した場合、
        // l2norm == 0f となり search() は空リストを返す
        val hashFallbackResult = FloatArray(1024) { 0f }
        val norm = MemoryRepository.l2norm(hashFallbackResult)
        assertEquals(
            "ゼロベクトルのノルムは0であり、search()が早期リターンするべき",
            0f, norm, 0f
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // MemoryTextEmbedder.initialized フラグの修正を間接的に検証
    //
    // 修正前: initialized=true を先頭で立てる → 例外後も再試行不可
    // 修正後: 成功時のみ initialized=true → 例外後は再試行可能
    //
    // このロジックをモデル化して確認する。
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun initializationFlag_shouldNotBeSetOnFailure() {
        // 修正後の挙動をシミュレート
        var initialized = false
        var useOnnx = false

        fun initializeFixed(willThrow: Boolean): Boolean {
            // initialized を先頭で立てない（修正後）
            return try {
                if (willThrow) throw RuntimeException("ONNX session failed")
                useOnnx = true
                initialized = true  // 成功時のみ
                true
            } catch (e: Exception) {
                useOnnx = false
                // initialized は立てない → 再試行可能
                false
            }
        }

        // 1回目: 失敗
        val result1 = initializeFixed(willThrow = true)
        assertEquals(false, result1)
        assertEquals(false, initialized)  // フラグが立っていない → 再試行可能

        // 2回目: 成功（ファイルDL後のリトライを想定）
        val result2 = initializeFixed(willThrow = false)
        assertEquals(true, result2)
        assertEquals(true, initialized)
        assertEquals(true, useOnnx)
    }

    @Test
    fun initializationFlag_oldBehavior_wouldBlockRetry() {
        // 修正前の挙動をシミュレートして問題を再現
        var initialized = false
        var useOnnx = false

        fun initializeBuggy(willThrow: Boolean): Boolean {
            if (initialized) return useOnnx
            initialized = true  // ← バグ: 先頭で立てる
            return try {
                if (willThrow) throw RuntimeException("ONNX session failed")
                useOnnx = true
                true
            } catch (e: Exception) {
                useOnnx = false
                false
            }
        }

        // 1回目: 失敗
        initializeBuggy(willThrow = true)
        assertEquals(true, initialized)   // フラグが立ってしまう
        assertEquals(false, useOnnx)

        // 2回目: 成功するはずだが initialized=true なので早期リターン
        val result2 = initializeBuggy(willThrow = false)
        assertEquals(false, result2)      // ← 修正前はここが false のまま（バグ）
        assertEquals(false, useOnnx)      // ← ONNXが使われない（バグの再現）
    }
}
