package com.nezumi_ai.data.inference

object LlamaCppGpuBackend {
    const val CPU = "CPU"
    const val OPENCL = "OPENCL"
    const val VULKAN = "VULKAN"

    fun normalize(value: String?): String {
        return when (value?.uppercase()) {
            OPENCL -> OPENCL
            VULKAN -> VULKAN
            else -> CPU
        }
    }

    fun isGpu(value: String?): Boolean {
        val normalized = normalize(value)
        return normalized == OPENCL || normalized == VULKAN
    }
}
