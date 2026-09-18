package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LitertlmMemoryEstimator / LitertlmSectionParser のうち、
 * 実ファイル I/O や Android フレームワーク（ActivityManager）に依存しない部分を
 * 対象にしたユニットテスト。
 *
 * .litertlm ヘッダー（FlatBuffers）のバイナリ構造自体はここでは組み立てない
 * （テスト用の妥当な FlatBuffers を手組みするのはリスクが高く可読性を損なう）。
 * ここでは主に：
 *   - [LitertlmSection] の派生プロパティ（typeName / sizeBytes）
 *   - 不正ファイルに対するエラーハンドリング（マジックバイト欠如・ファイル不在）
 * を検証する。ヘッダーのバイナリ解析ロジック自体の正しさは、実際の .litertlm
 * サンプルファイルを使った計装テストでカバーする想定。
 */
class LitertlmMemoryEstimatorTest {

    @Test
    fun litertlmSection_typeName_mapsKnownTypes() {
        assertEquals("GenericBinaryData", LitertlmSection(1, 0, 0, emptyMap()).typeName)
        assertEquals("TFLiteModel", LitertlmSection(3, 0, 0, emptyMap()).typeName)
        assertEquals("SP_Tokenizer", LitertlmSection(4, 0, 0, emptyMap()).typeName)
        assertEquals("LlmMetadataProto", LitertlmSection(5, 0, 0, emptyMap()).typeName)
        assertEquals("HF_Tokenizer_Zlib", LitertlmSection(6, 0, 0, emptyMap()).typeName)
        assertEquals("TFLiteWeights", LitertlmSection(7, 0, 0, emptyMap()).typeName)
        assertEquals("type(99)", LitertlmSection(99, 0, 0, emptyMap()).typeName)
    }

    @Test
    fun litertlmSection_sizeBytes_computesRange() {
        val section = LitertlmSection(
            type = LitertlmSectionType.TFLITE_WEIGHTS,
            begin = 1_000L,
            end = 5_000_000L,
            items = emptyMap()
        )
        assertEquals(4_999_000L, section.sizeBytes)
    }

    @Test
    fun litertlmSection_sizeBytes_clampsToZeroOnInvalidRange() {
        // begin > end のような壊れたセクションでも例外を投げず 0 を返す
        val section = LitertlmSection(
            type = LitertlmSectionType.TFLITE_MODEL,
            begin = 100L,
            end = 50L,
            items = emptyMap()
        )
        assertEquals(0L, section.sizeBytes)
    }

    @Test
    fun parseLitertlmSections_rejectsMissingMagic() {
        val bytes = "NOTLITERTLM_PADDING_BYTES_______".toByteArray(Charsets.US_ASCII)
        val ex = runCatching { parseLitertlmSections(bytes) }.exceptionOrNull()
        assertTrue(ex is LitertlmParseException)
        assertTrue(ex!!.message!!.contains("magic"))
    }

    @Test
    fun parseLitertlmSections_rejectsTooShortFile() {
        val bytes = "LITERTLM".toByteArray(Charsets.US_ASCII) // 8 bytes, magic だけで32バイト未満
        val ex = runCatching { parseLitertlmSections(bytes) }.exceptionOrNull()
        assertTrue(ex is LitertlmParseException)
        assertTrue(ex!!.message!!.contains("too short"))
    }

    @Test
    fun parseLitertlmSectionsFromFile_failsGracefullyOnMissingFile() {
        val ex = runCatching {
            parseLitertlmSectionsFromFile("/nonexistent/path/model.litertlm")
        }.exceptionOrNull()
        assertTrue(ex is java.io.FileNotFoundException)
    }

    @Test
    fun estimate_returnsFailure_whenFileDoesNotExist() {
        val result = LitertlmMemoryEstimator.estimate("/nonexistent/path/model.litertlm")
        assertTrue(result is LitertlmMemoryEstimator.EstimateResult.Failure)
        val reason = (result as LitertlmMemoryEstimator.EstimateResult.Failure).reason
        assertTrue(reason.contains("not found"))
    }

    @Test
    fun estimate_returnsFailure_forNonLitertlmFile() {
        val tmp = kotlin.io.path.createTempFile(suffix = ".litertlm").toFile()
        try {
            tmp.writeBytes(ByteArray(64) { 0 }) // マジックバイトなしの適当なバイナリ
            val result = LitertlmMemoryEstimator.estimate(tmp.absolutePath)
            assertTrue(result is LitertlmMemoryEstimator.EstimateResult.Failure)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun estimateParams_defaultsAreReasonable() {
        val params = LitertlmMemoryEstimator.EstimateParams()
        assertEquals(4096, params.maxNumTokens)
        assertEquals(LitertlmMemoryEstimator.KvCacheType.F16, params.kvCacheType)
        assertFalse(params.withVisionAudio)
    }

    @Test
    fun kvCacheType_bytesPerElement() {
        assertEquals(2, LitertlmMemoryEstimator.KvCacheType.F16.bytesPerElement)
        assertEquals(4, LitertlmMemoryEstimator.KvCacheType.F32.bytesPerElement)
        assertEquals(1, LitertlmMemoryEstimator.KvCacheType.INT8.bytesPerElement)
    }

    @Test
    fun formatBytes_belowKilobyte_showsRawBytes() {
        assertEquals("512 B", LitertlmMemoryEstimator.formatBytes(512))
    }

    @Test
    fun formatBytes_gigabyteRange() {
        val bytes = 3L * 1024 * 1024 * 1024 + 256L * 1024 * 1024 // 3.25 GB
        assertEquals("3.25 GB", LitertlmMemoryEstimator.formatBytes(bytes))
    }
}
