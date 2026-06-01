package com.nezumi_ai.data.inference

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * llama.cpp推論エンジンの最適化設定を管理。
 * 
 * デバイスの性能に応じて自動的に最適なパラメータを選択する。
 * ユーザーが手動で設定を上書きすることも可能。
 */
class OptimizationConfig(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "llama_optimization_config", 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val TAG = "OptimizationConfig"
        
        // 設定キー
        private const val KEY_AUTO_OPTIMIZE = "auto_optimize"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_GPU_LAYERS = "gpu_layers"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_CONTEXT_SIZE = "context_size"
        private const val KEY_USE_MMAP = "use_mmap"
        private const val KEY_USE_MLOCK = "use_mlock"
        
        // デフォルト値
        private const val DEFAULT_AUTO_OPTIMIZE = true
        private const val DEFAULT_BATCH_SIZE = 512
        private const val DEFAULT_CONTEXT_SIZE = 2048
        
        /**
         * デバイスの性能レベルを判定
         */
        enum class DevicePerformance {
            LOW,      // 4GB RAM以下、古いSoC
            MEDIUM,   // 6GB RAM、中程度のSoC
            HIGH,     // 8GB RAM以上、ハイエンドSoC
            FLAGSHIP  // 12GB RAM以上、最新フラッグシップ
        }
        
        fun detectDevicePerformance(): DevicePerformance {
            val runtime = Runtime.getRuntime()
            val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
            val cores = runtime.availableProcessors()
            
            // メモリとコア数から性能レベルを推定
            return when {
                maxMemoryMb >= 12 * 1024 && cores >= 8 -> DevicePerformance.FLAGSHIP
                maxMemoryMb >= 8 * 1024 && cores >= 6 -> DevicePerformance.HIGH
                maxMemoryMb >= 6 * 1024 && cores >= 4 -> DevicePerformance.MEDIUM
                else -> DevicePerformance.LOW
            }
        }
    }
    
    data class Config(
        val threadCount: Int,
        val gpuLayers: Int,
        val batchSize: Int,
        val contextSize: Int,
        val useMmap: Boolean,
        val useMlock: Boolean
    ) {
        fun toLogString(): String {
            return "Config(threads=$threadCount, gpuLayers=$gpuLayers, " +
                   "batch=$batchSize, ctx=$contextSize, " +
                   "mmap=$useMmap, mlock=$useMlock)"
        }
    }
    
    /**
     * 自動最適化が有効かどうか
     */
    var autoOptimize: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OPTIMIZE, DEFAULT_AUTO_OPTIMIZE)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_OPTIMIZE, value).apply()
    
    /**
     * 現在の設定を取得
     */
    fun getConfig(backendType: String = "CPU"): Config {
        return if (autoOptimize) {
            getAutoOptimizedConfig(backendType)
        } else {
            getManualConfig()
        }
    }
    
    /**
     * デバイス性能に基づいて自動最適化された設定を取得
     */
    private fun getAutoOptimizedConfig(backendType: String): Config {
        val performance = detectDevicePerformance()
        val runtime = Runtime.getRuntime()
        val cores = runtime.availableProcessors()
        val physicalCores = (cores / 2).coerceAtLeast(1)
        
        Log.i(TAG, "Device performance: $performance, cores: $cores (physical: ~$physicalCores)")
        
        return when (performance) {
            DevicePerformance.FLAGSHIP -> Config(
                threadCount = (physicalCores - 1).coerceAtLeast(4).coerceAtMost(8),
                gpuLayers = if (backendType.uppercase() == "GPU") 999 else 0,
                batchSize = 512,
                contextSize = 4096,
                useMmap = true,
                useMlock = false  // フラッグシップでもmlockは慎重に
            )
            DevicePerformance.HIGH -> Config(
                threadCount = (physicalCores - 1).coerceAtLeast(3).coerceAtMost(6),
                gpuLayers = if (backendType.uppercase() == "GPU") 35 else 0,
                batchSize = 512,
                contextSize = 2048,
                useMmap = true,
                useMlock = false
            )
            DevicePerformance.MEDIUM -> Config(
                threadCount = (physicalCores - 1).coerceAtLeast(2).coerceAtMost(4),
                gpuLayers = if (backendType.uppercase() == "GPU") 20 else 0,
                batchSize = 256,
                contextSize = 2048,
                useMmap = true,
                useMlock = false
            )
            DevicePerformance.LOW -> Config(
                threadCount = 2,
                gpuLayers = 0,  // ローエンドはCPUのみ
                batchSize = 128,
                contextSize = 1024,
                useMmap = true,
                useMlock = false
            )
        }
    }
    
    /**
     * 手動設定を取得
     */
    private fun getManualConfig(): Config {
        return Config(
            threadCount = prefs.getInt(KEY_THREAD_COUNT, 4),
            gpuLayers = prefs.getInt(KEY_GPU_LAYERS, 0),
            batchSize = prefs.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE),
            contextSize = prefs.getInt(KEY_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE),
            useMmap = prefs.getBoolean(KEY_USE_MMAP, true),
            useMlock = prefs.getBoolean(KEY_USE_MLOCK, false)
        )
    }
    
    /**
     * 手動設定を保存
     */
    fun setManualConfig(config: Config) {
        prefs.edit().apply {
            putInt(KEY_THREAD_COUNT, config.threadCount)
            putInt(KEY_GPU_LAYERS, config.gpuLayers)
            putInt(KEY_BATCH_SIZE, config.batchSize)
            putInt(KEY_CONTEXT_SIZE, config.contextSize)
            putBoolean(KEY_USE_MMAP, config.useMmap)
            putBoolean(KEY_USE_MLOCK, config.useMlock)
            apply()
        }
        Log.i(TAG, "Manual config saved: ${config.toLogString()}")
    }
    
    /**
     * 設定をリセット（自動最適化に戻す）
     */
    fun reset() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Config reset to auto-optimize")
    }
    
    /**
     * 推奨設定を取得（UI表示用）
     */
    fun getRecommendedConfig(): Map<String, String> {
        val performance = detectDevicePerformance()
        val config = getAutoOptimizedConfig("GPU")
        
        return mapOf(
            "デバイス性能" to performance.name,
            "推奨スレッド数" to config.threadCount.toString(),
            "推奨GPU層数" to config.gpuLayers.toString(),
            "推奨バッチサイズ" to config.batchSize.toString(),
            "推奨コンテキスト" to config.contextSize.toString(),
            "メモリマップ" to if (config.useMmap) "有効" else "無効",
            "メモリロック" to if (config.useMlock) "有効" else "無効"
        )
    }
}
