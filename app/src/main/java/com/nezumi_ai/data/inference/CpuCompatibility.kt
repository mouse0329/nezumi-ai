package com.nezumi_ai.data.inference

import android.os.Build
import java.io.File

object CpuCompatibility {
    private val ARM_V82A_FEATURES = setOf("fphp", "asimdhp")

    data class Warning(
        val userMessage: String,
        val logMessage: String
    )

    fun armV82aWarningOrNull(): Warning? {
        val abis = Build.SUPPORTED_ABIS.orEmpty().map { it.lowercase() }
        val isArm64 = abis.any { it == "arm64-v8a" }
        if (!isArm64) {
            return Warning(
                userMessage = "⚠️ この端末は arm64-v8a ではないため、GGUF推論が遅い/不安定になる可能性があります。",
                logMessage = "Device ABI is below arm64-v8a or non-ARM64: abis=${abis.joinToString()}"
            )
        }

        val features = readCpuFeatures()
        if (features.isEmpty()) return null

        val hasArmV82aSignal = ARM_V82A_FEATURES.any { it in features }
        if (hasArmV82aSignal) return null

        return Warning(
            userMessage = "⚠️ この端末のCPUは armv8.2-a 未満の可能性があります。GGUF推論が遅くなる場合またはアプリがクラッシュする可能性があります。",
            logMessage = "ARM64 CPU does not report armv8.2-a FP16 features: requiredAny=$ARM_V82A_FEATURES features=${features.sorted().joinToString()}"
        )
    }

    private fun readCpuFeatures(): Set<String> {
        return runCatching {
            File("/proc/cpuinfo")
                .readLines()
                .asSequence()
                .filter { it.startsWith("Features", ignoreCase = true) }
                .flatMap { line ->
                    line.substringAfter(':', "")
                        .trim()
                        .lowercase()
                        .splitToSequence(Regex("\\s+"))
                }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }
}
