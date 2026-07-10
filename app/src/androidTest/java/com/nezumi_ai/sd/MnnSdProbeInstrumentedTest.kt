package com.nezumi_ai.sd

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 実機 MNN I/O プローブ。
 *
 * ```
 * adb push models/CuteYukiMix /sdcard/nezumi_probe/CuteYukiMix
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.modelPath=/sdcard/nezumi_probe/CuteYukiMix
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MnnSdProbeInstrumentedTest {

    @Test
    fun probeModelDirectory_fromInstrumentationArg() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath")
            ?: return@runBlocking // skip when arg not provided

        val module = MnnSdModule()
        try {
            assertTrue("mnn_sd_jni not loaded", module.isNativeAvailable())
            val result = module.probeModelDirectory(modelPath)
            Log.i(TAG, result.summary())

            val out = File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "mnn_sd_probe_instrumented.txt"
            )
            out.writeText(result.summary())

            assertTrue("probe errors: ${result.errors}", result.ok)
        } finally {
            module.close()
        }
    }

    companion object {
        private const val TAG = "MnnSdProbeTest"
    }
}
