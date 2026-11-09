package com.interviewcopilot.app

import com.interviewcopilot.app.core.Stage
import com.interviewcopilot.app.core.VoiceEvent
import com.interviewcopilot.app.core.VoiceFlow
import com.interviewcopilot.app.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFlowTest {
    private fun run(vararg events: VoiceEvent, from: VoiceState = VoiceState()): VoiceState =
        events.fold(from) { s, e -> VoiceFlow.reduce(s, e) }

    @Test
    fun speakingNeverStartsGeneration() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.Partial("hello"), VoiceEvent.SpeechFinal("Hello there."))
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("Hello there.", s.text)
        assertEquals("", s.answer)
        assertTrue(s.canGenerate)
    }

    @Test
    fun fullHappyPathEndsIdleWithAnswerKept() {
        val s = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SpeechFinal("What is a thread?"),
            VoiceEvent.GenerateTapped,
            VoiceEvent.Token("A thread"),
            VoiceEvent.Token("A thread is a path of execution."),
            VoiceEvent.GenerationFinished,
            VoiceEvent.ResetDone,
        )
        assertEquals(Stage.Idle, s.stage)
        assertEquals("", s.text)
        assertEquals("What is a thread?", s.question)
        assertEquals("A thread is a path of execution.", s.answer)
    }

    @Test
    fun generateDisabledWhenEmptyOrBusy() {
        assertFalse(VoiceState().canGenerate)
        assertFalse(VoiceState(stage = Stage.Transcribed, text = "   ").canGenerate)
        assertFalse(VoiceState(stage = Stage.Generating, text = "hi").canGenerate)
        assertFalse(VoiceState(stage = Stage.Listening, text = "hi").canGenerate)
        assertTrue(VoiceState(stage = Stage.Transcribed, text = "hi").canGenerate)
    }

    @Test
    fun generateTapIgnoredWhenDisabled() {
        val s = run(VoiceEvent.GenerateTapped)
        assertEquals(Stage.Idle, s.stage)
    }

    @Test
    fun editingTextKeepsItReadyAndEmptyingDisables() {
        var s = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("helo wrld"))
        s = VoiceFlow.reduce(s, VoiceEvent.TextEdited("Hello world"))
        assertEquals("Hello world", s.text)
        assertTrue(s.canGenerate)
        s = VoiceFlow.reduce(s, VoiceEvent.TextEdited(""))
        assertFalse(s.canGenerate)
        assertEquals(Stage.Idle, s.stage)
    }

    @Test
    fun stopKeepsTextForRetry() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Explain REST"), VoiceEvent.GenerateTapped, VoiceEvent.StopTapped)
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("Explain REST", s.text)
        assertTrue(s.canGenerate)
    }

    @Test
    fun emptySpeechReturnsToIdleWithMessage() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("   "))
        assertEquals(Stage.Idle, s.stage)
        assertTrue(s.error != null)
    }

    @Test
    fun editsAreIgnoredWhileGenerating() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Question"), VoiceEvent.GenerateTapped, VoiceEvent.TextEdited("changed"))
        assertEquals("Question", s.text)
    }

    @Test
    fun failureReturnsToTranscribedWithError() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Q"), VoiceEvent.GenerateTapped, VoiceEvent.GenerationFailed("boom"))
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("boom", s.error)
    }

    @Test
    fun secondQuestionIsIndependent() {
        val first = run(
            VoiceEvent.MicTapped,
            VoiceEvent.SpeechFinal("One"),
            VoiceEvent.GenerateTapped,
            VoiceEvent.Token("A1"),
            VoiceEvent.GenerationFinished,
            VoiceEvent.ResetDone,
        )
        val second = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Two"), VoiceEvent.GenerateTapped, from = first)
        assertEquals("Two", second.question)
        assertEquals("", second.answer)
    }

    @Test
    fun pausesNeverEndListeningAndSentencesAccumulate() {
        var s = run(VoiceEvent.MicTapped, VoiceEvent.Partial("what is"))
        assertEquals("what is", s.shown)
        s = VoiceFlow.reduce(s, VoiceEvent.SegmentFinal("What is a thread?"))
        assertEquals(Stage.Listening, s.stage)
        assertEquals("", s.partial)
        // the person pauses, then keeps talking: the first sentence must still be there
        s = VoiceFlow.reduce(s, VoiceEvent.Partial("and a process"))
        assertEquals("What is a thread? and a process", s.shown)
        s = VoiceFlow.reduce(s, VoiceEvent.SegmentFinal("And a process?"))
        s = VoiceFlow.reduce(s, VoiceEvent.SpeechFinal(""))
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("What is a thread? And a process?", s.text)
        assertEquals("", s.answer)
    }

    @Test
    fun finalTailIsAppendedToEarlierSentences() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("First part."), VoiceEvent.SpeechFinal("Second part."))
        assertEquals("First part. Second part.", s.text)
    }

    @Test
    fun speakingAgainKeepsExistingText() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Explain REST."), VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("And SOAP."))
        assertEquals(Stage.Listening, s.stage)
        assertEquals("Explain REST. And SOAP.", s.text)
    }

    @Test
    fun clearEmptiesTheBoxInEveryStageExceptGenerating() {
        val listening = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("Hello"), VoiceEvent.Partial("wor"), VoiceEvent.ClearTapped)
        assertEquals(Stage.Listening, listening.stage)
        assertEquals("", listening.shown)

        val done = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Hello there"), VoiceEvent.ClearTapped)
        assertEquals(Stage.Idle, done.stage)
        assertEquals("", done.text)
        assertFalse(done.canGenerate)

        val busy = run(VoiceEvent.MicTapped, VoiceEvent.SpeechFinal("Hello"), VoiceEvent.GenerateTapped, VoiceEvent.ClearTapped)
        assertEquals(Stage.Generating, busy.stage)
        assertEquals("Hello", busy.text)
    }

    @Test
    fun clearButtonAndGenerateRulesWhileListening() {
        val empty = run(VoiceEvent.MicTapped)
        assertFalse(empty.canClear)
        assertFalse(empty.canTapGenerate)
        val heard = run(VoiceEvent.MicTapped, VoiceEvent.Partial("hello"))
        assertTrue(heard.canClear)
        assertTrue(heard.canTapGenerate)
        assertFalse(heard.canGenerate)
    }

    @Test
    fun microphoneFailureKeepsWhatWasAlreadyHeard() {
        val s = run(VoiceEvent.MicTapped, VoiceEvent.SegmentFinal("Tell me about yourself."), VoiceEvent.SpeechFailed("Microphone stopped responding."))
        assertEquals(Stage.Transcribed, s.stage)
        assertEquals("Tell me about yourself.", s.text)
        assertTrue(s.error != null)
    }
}
