package com.nezumi_ai.data.inference

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelFileManagerValidationTest {

    @Test
    fun validateModelFileForUse_acceptsReadableTaskFileWithoutHashMetadata() {
        val file = File.createTempFile("model-validation", ".task")
        file.deleteOnExit()
        file.writeBytes(
            byteArrayOf(
                0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
            ) + ByteArray(1024)
        )

        val result = ModelFileManager.validateModelFileForUse(file)
        println("result=$result, exception=${result.exceptionOrNull()}")

        assertTrue(result.isSuccess)
        file.delete()
    }
}
