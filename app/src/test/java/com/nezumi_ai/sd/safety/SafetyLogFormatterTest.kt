package com.nezumi_ai.sd.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafetyLogFormatterTest {
    @Test
    fun format_recordsAllScoresAndVerdictsWithoutPromptOrImageData() {
        val openNsfw = SafetyResult(normalScore = 0.87654f, nsfwScore = 0.12345f)
        val classifierXs = ImageSafetyClassifierResult(
            nsflScore = 0.125f,
            nsfwScore = 0.9099f,
            sfwScore = 0.0611f
        )

        val message = SafetyLogFormatter.format(
            openNsfw = openNsfw,
            classifierXs = classifierXs,
            finalVerdict = SafetyResult.Verdict.ALLOW
        )

        assertEquals(
            "Safety: scores open_nsfw(normal=0.8765, nsfw=0.1235, verdict=ALLOW), " +
                "classifier_xs(nsfl=0.1250, nsfw=0.9099[ignored], sfw=0.0611, verdict=ALLOW), final=ALLOW",
            message
        )
        assertFalse(message.contains("prompt", ignoreCase = true))
        assertFalse(message.contains("bitmap", ignoreCase = true))
    }

    @Test
    fun classifierXs_usesNsflButIgnoresNsfw() {
        val result = ImageSafetyClassifierResult(
            nsflScore = 0.80f,
            nsfwScore = 0.99f,
            sfwScore = 0.01f
        )

        assertEquals(SafetyResult.Verdict.BLOCK, result.verdict)
    }
}
