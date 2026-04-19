package com.nezumi_ai.data.inference

import android.graphics.Bitmap
import android.util.Log
import androidx.collection.LruCache
import java.io.ByteArrayOutputStream

/**
 * Bitmap のメモリプール管理。
 * - LRU キャッシュで Bitmap を再利用
 * - OutOfMemoryError の予防
 * - 厳密な recycle() 管理
 */
class BitmapMemoryPool(maxMemorySizeBytes: Long = 50 * 1024 * 1024) {
    companion object {
        private const val TAG = "BitmapMemoryPool"
    }
    
    private val pool: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (maxMemorySizeBytes / (1024 * 1024)).toInt()
    ) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // Bitmap のメモリサイズを MB 単位で返す
            return (bitmap.allocationByteCount / (1024 * 1024))
        }
        
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                Log.d(TAG, "Evicting bitmap from pool: $key")
                if (!oldValue.isRecycled) {
                    oldValue.recycle()
                }
            }
        }
    }
    
    /**
     * キャッシュに Bitmap を追加
     */
    fun put(key: String, bitmap: Bitmap) {
        try {
            pool.put(key, bitmap)
            Log.d(TAG, "Bitmap cached: $key (${bitmap.allocationByteCount / (1024 * 1024)} MB)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache bitmap", e)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
    
    /**
     * キャッシュから Bitmap を取得（存在する場合）
     */
    fun get(key: String): Bitmap? {
        return pool.get(key)
    }
    
    /**
     * キャッシュをクリア
     */
    fun clear() {
        Log.d(TAG, "Clearing bitmap pool")
        pool.evictAll()
    }
    
    /**
     * キャッシュのメモリ使用量（MB）
     */
    fun getMemoryUsageMB(): Int {
        return pool.size()
    }
    
    /**
     * Bitmap を PNG バイト配列に変換（メモリ効率重視）
     */
    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, this)  // 85% 圧縮品質
        }.toByteArray()
    }
}

/**
 * Bitmap リソースの安全なクリーンアップを支援
 */
object BitmapRecycleHelper {
    private const val TAG = "BitmapRecycleHelper"
    
    /**
     * Bitmap リストを安全にクリア
     */
    fun recycleBitmaps(bitmaps: Collection<Bitmap>) {
        bitmaps.forEach {
            if (!it.isRecycled) {
                try {
                    it.recycle()
                    Log.d(TAG, "Bitmap recycled: ${it.width}x${it.height}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to recycle bitmap", e)
                }
            }
        }
    }
    
    /**
     * 安全な Bitmap リサイズ（メモリ不足対応）
     * OutOfMemoryError 時は段階的に品質を下げて再試行
     */
    fun safeScaleBitmap(
        bitmap: Bitmap,
        maxWidth: Int = 1024,
        maxHeight: Int = 1024,
        initialQuality: Int = 100
    ): Bitmap {
        // 既にサイズ内なら、そのまま返す
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) {
            return bitmap
        }
        
        var quality = initialQuality
        var scaledBitmap: Bitmap? = null
        
        // 段階的にスケール品質を下げて再試行
        while (quality >= 50) {
            try {
                val scaleX = maxWidth.toFloat() / bitmap.width
                val scaleY = maxHeight.toFloat() / bitmap.height
                val scale = minOf(scaleX, scaleY)
                
                val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                Log.d(TAG, "Bitmap scaled: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
                return scaledBitmap
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "OutOfMemoryError during scaling (quality=$quality%), retrying with lower quality", e)
                scaledBitmap?.recycle()
                quality -= 10
                
                // ガベージコレクション実行
                System.gc()
            }
        }
        
        // 全て失敗した場合、元のビットマップを返す
        Log.e(TAG, "Failed to scale bitmap after multiple attempts, returning original")
        return bitmap
    }
}
