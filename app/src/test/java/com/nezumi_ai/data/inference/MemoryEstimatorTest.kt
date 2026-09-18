package com.nezumi_ai.data.inference

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryEstimator のうち native 呼び出しに依存しない部分（JSON デコード / フォーマット）
 * を対象にしたユニットテスト。
 *
 * ネイティブ層 (nativeEstimateMemoryUsage) はロード前メモリ見積もりの本体ロジックを持つため、
 * その計算式自体の正しさは計装テスト（instrumented test, JNI 経由）でカバーする想定。
 * ここでは JVM 単体で検証できる契約（JSON スキーマ整合性、表示フォーマット）を担保する。
 */
class MemoryEstimatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rawEstimate_decodesSuccessResponse() {
        val raw = """
            {
                "ok": true,
                "architecture": "llama",
                "n_params": 8030000000,
                "file_size_bytes": 4800000000,
                "n_layer_total": 32,
                "n_layer_offloaded": 0,
                "weights_cpu_bytes": 4800000000,
                "weights_gpu_bytes": 0,
                "n_ctx_used": 4096,
                "kv_cache_bytes": 536870912,
                "compute_buffer_bytes": 67108864,
                "total_ram_bytes": 5470699520,
                "total_vram_bytes": 0,
                "total_bytes_no_gpu": 5470699520
            }
        """.trimIndent()

        val decoded = json.decodeFromString<MemoryEstimator.RawEstimate>(raw)

        assertTrue(decoded.ok)
        assertEquals("llama", decoded.architecture)
        assertEquals(8_030_000_000L, decoded.nParams)
        assertEquals(32, decoded.nLayerTotal)
        assertEquals(0, decoded.nLayerOffloaded)
        assertEquals(4096, decoded.nCtxUsed)
    }

    @Test
    fun rawEstimate_decodesFailureResponse() {
        val raw = """{"ok":false,"error":"gguf_init_from_file failed (invalid or unreadable GGUF file)"}"""

        val decoded = json.decodeFromString<MemoryEstimator.RawEstimate>(raw)

        assertFalse(decoded.ok)
        assertEquals(
            "gguf_init_from_file failed (invalid or unreadable GGUF file)",
            decoded.error
        )
        // ok=false のときは他フィールドが既定値（0 / "unknown"）で埋まることを確認
        assertEquals("unknown", decoded.architecture)
        assertEquals(0L, decoded.fileSizeBytes)
    }

    @Test
    fun rawEstimate_ignoresUnknownKeys() {
        // ネイティブ側にフィールドが追加されても JVM 側テストが壊れないことを保証する
        val raw = """{"ok":true,"architecture":"qwen3","future_field":"xyz"}"""

        val decoded = json.decodeFromString<MemoryEstimator.RawEstimate>(raw)

        assertTrue(decoded.ok)
        assertEquals("qwen3", decoded.architecture)
    }

    @Test
    fun formatBytes_belowKilobyte_showsRawBytes() {
        assertEquals("512 B", MemoryEstimator.formatBytes(512))
    }

    @Test
    fun formatBytes_megabyteRange() {
        val bytes = 256L * 1024 * 1024 // 256 MB
        assertEquals("256.00 MB", MemoryEstimator.formatBytes(bytes))
    }

    @Test
    fun formatBytes_gigabyteRange() {
        val bytes = 5L * 1024 * 1024 * 1024 + 512L * 1024 * 1024 // 5.5 GB
        assertEquals("5.50 GB", MemoryEstimator.formatBytes(bytes))
    }

    @Test
    fun estimateParams_defaultsAreReasonable() {
        val params = MemoryEstimator.EstimateParams()
        assertEquals(4096, params.nCtx)
        assertEquals(0, params.nGpuLayers)
        assertEquals(512, params.nBatch)
        assertEquals(MemoryEstimator.KvCacheType.F16, params.kvCacheType)
    }

    @Test
    fun kvCacheType_nativeValuesMatchExpectedStrings() {
        assertEquals("f16", MemoryEstimator.KvCacheType.F16.nativeValue)
        assertEquals("f32", MemoryEstimator.KvCacheType.F32.nativeValue)
        assertEquals("q8_0", MemoryEstimator.KvCacheType.Q8_0.nativeValue)
        assertEquals("q4_0", MemoryEstimator.KvCacheType.Q4_0.nativeValue)
    }
}
