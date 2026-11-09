package com.interviewcopilot.app

import com.interviewcopilot.app.core.TranscriptCleaner
import com.interviewcopilot.app.core.joinText
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanerTest {
    @Test
    fun keepsNormalSpeechUntouched() {
        assertEquals("What is the difference between a list and a tuple?", TranscriptCleaner.clean("What is the difference between a list and a tuple?"))
    }

    @Test
    fun removesSoundTags() {
        assertEquals("Tell me about yourself.", TranscriptCleaner.clean("[BLANK_AUDIO] Tell me about yourself. (music) \u266A"))
    }

    @Test
    fun dropsSubtitleHallucinations() {
        assertEquals("", TranscriptCleaner.clean("Thanks for watching!"))
        assertEquals("", TranscriptCleaner.clean("Subtitles by the Amara.org community"))
    }

    @Test
    fun courtesyWordsAreOnlyDroppedOnNearSilence() {
        assertEquals("", TranscriptCleaner.clean("Thank you.", quiet = true))
        assertEquals("Thank you.", TranscriptCleaner.clean("Thank you.", quiet = false))
    }

    @Test
    fun collapsesRunawayRepeats() {
        assertEquals("I think the answer is yes", TranscriptCleaner.clean("I think the the the the the answer is yes"))
        assertEquals("so i mean it works", TranscriptCleaner.clean("so i mean i mean i mean i mean it works"))
    }

    @Test
    fun keepsNaturalDoubleWords() {
        assertEquals("that that is fine", TranscriptCleaner.clean("that that is fine"))
    }

    @Test
    fun joinsWithSingleSpace() {
        assertEquals("a b", joinText(" a ", " b "))
        assertEquals("b", joinText("", " b"))
        assertEquals("a", joinText("a", "  "))
    }
}
