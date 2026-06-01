package com.nezumi_ai.data.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptimizationIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var optimizationConfig: OptimizationConfig
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        optimizationConfig = OptimizationConfig(context)
    }
    
    @After
    fun teardown() = runBlocking {
        PerformanceMonitor.clear()
        optimizationConfig.reset()
    }
    
    @Test
    fun testDevicePerformanceDetection() {
        val performance = OptimizationConfig.detectDevicePerformance()
        assertNotNull(performance)
    }
    
    @Test
    fun testAutoOptimizedConfig() {
        optimizationConfig.autoOptimize = true
        val cpuConfig = optimizationConfig.getConfig("CPU")
        assertEquals(0, cpuConfig.gpuLayers)
        assertTrue(cpuConfig.threadCount > 0)
    }
    
    @Test
    fun testPerformanceMonitor() = runBlocking {
        val sessionId = 12345L
        PerformanceMonitor.startInference(sessionId, "GPU", 100)
        repeat(50) { PerformanceMonitor.recordToken(sessionId) }
        val metrics = PerformanceMonitor.endInference(sessionId)
        assertNotNull(metrics)
        assertEquals(50, metrics!!.totalTokens)
    }
}
