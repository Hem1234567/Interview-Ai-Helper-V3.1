package com.interviewcopilot.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Fallback: Android's built-in recognizer, used until a Whisper model has been downloaded.
 * The phone's recognizer ends by itself after every pause, so each round is reported as a sentence
 * and a new round starts at once. Listening only ends when [stop] is called.
 * Everything runs on the main thread.
 */
class PlatformRecognizer(
    private val ctx: Context,
    private val languageCode: String,
    private val listener: SttListener,
) : SttSession {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var lastPartial = ""
    private var delivered = false
    private var stopping = false
    private var emptyRounds = 0

    private fun tag(): String =
        if (languageCode == "auto") Locale.getDefault().toLanguageTag() else languageCode

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (!t.isNullOrBlank()) {
                lastPartial = t
                listener.onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            roundEnded(first(results) ?: lastPartial)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    fail("Microphone permission is missing.")
                }

                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    roundEnded(lastPartial)
                }

                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    fail("The phone's recognizer needs internet. Download a Whisper model in Settings to work offline.")
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER -> {
                    if (stopping) finish(lastPartial) else restartSoon(400)
                }

                else -> {
                    fail("Speech recognition failed (code $error).")
                }
            }
        }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** One round of the phone's recognizer is over. Report it and, unless the person stopped, listen again. */
    private fun roundEnded(text: String) {
        if (delivered) return
        lastPartial = ""
        val t = text.trim()
        release()
        if (stopping) {
            finish(t)
            return
        }
        if (t.isNotEmpty()) {
            emptyRounds = 0
            listener.onSegment(t)
        } else {
            emptyRounds++
            if (emptyRounds >= MAX_EMPTY_ROUNDS) {
                finish("")
                return
            }
        }
        restartSoon(RESTART_DELAY_MS)
    }

    private fun restartSoon(delayMs: Long) {
        release()
        main.postDelayed({ if (!delivered && !stopping) begin() }, delayMs)
    }

    private fun release() {
        rec?.destroy()
        rec = null
    }

    private fun finish(text: String) {
        if (delivered) return
        delivered = true
        release()
        main.removeCallbacksAndMessages(null)
        listener.onLevel(0f)
        listener.onFinal(text.trim())
    }

    private fun fail(message: String) {
        if (delivered) return
        delivered = true
        release()
        main.removeCallbacksAndMessages(null)
        listener.onError(message)
    }

    private fun begin() {
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(callbacks)
        rec = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Ask the recognizer to wait through pauses; the restart loop covers phones that ignore this.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        }
        r.startListening(intent)
    }

    override fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            fail("Speech recognition is not available on this phone.")
            return
        }
        delivered = false
        stopping = false
        emptyRounds = 0
        lastPartial = ""
        begin()
    }

    override fun stop() {
        if (delivered) return
        stopping = true
        val r = rec
        if (r == null) {
            finish(lastPartial)
            return
        }
        r.stopListening()
        // Some phones never answer stopListening; do not leave the screen waiting.
        main.postDelayed({ finish(lastPartial) }, STOP_GRACE_MS)
    }

    override fun cancel() {
        delivered = true
        main.removeCallbacksAndMessages(null)
        release()
    }

    override fun discard() {
        lastPartial = ""
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
        const val STOP_GRACE_MS = 2_500L
        const val MAX_EMPTY_ROUNDS = 12
    }
}
