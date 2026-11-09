package com.interviewcopilot.app.voice

import android.os.Handler
import android.os.Looper
import com.interviewcopilot.app.core.AudioDsp
import com.interviewcopilot.app.core.TranscriptCleaner
import com.interviewcopilot.app.core.Vad
import com.interviewcopilot.app.core.VadEvent
import com.interviewcopilot.app.core.joinText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device Whisper session. It records 16 kHz mono PCM and keeps listening until [stop]: a pause only ends the
 * current sentence, which is then decoded in the background (accurate beam search, in order) and handed over with
 * [SttListener.onSegment] while the microphone stays open. Live partial text is shown for the sentence in progress.
 * Capture and decoding run on background threads; callbacks are posted to the main thread.
 */
class WhisperRecognizer(
    private val language: String,
    private val vocabularyHint: String,
    private val listener: SttListener,
) : SttSession {
    private class Segment(val audio: FloatArray, val epoch: Int)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var discardRequested = false

    /** Raised by [discard]: text decoded for an older epoch is dropped. */
    private val epoch = AtomicInteger(0)

    /** Raised for every finished sentence: a late partial from the previous sentence is ignored. */
    private val utteranceId = AtomicInteger(0)

    /** Sentences waiting to be decoded. Live partials are skipped while this is not zero. */
    private val pending = AtomicInteger(0)

    @Volatile
    private var committed = ""

    private val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    private val terms: String = vocabularyHint.lines().mapNotNull { l -> l.substringAfter('=', "").trim().ifEmpty { null } }
        .distinct().take(30).joinToString(", ")

    /** Whisper reads the prompt as text that came just before: spelling hints plus the end of what was already said. */
    private fun promptFor(context: String): ByteArray {
        val base = "Hello. This is a clear, correctly punctuated and capitalized sentence."
        val withTerms = if (terms.isEmpty()) base else "$base Terms: $terms."
        val tail = context.takeLast(CONTEXT_CHARS).trim()
        return (if (tail.isEmpty()) withTerms else "$withTerms $tail").toByteArray(Charsets.UTF_8)
    }

    override fun start() {
        stopRequested = false
        cancelled = false
        job = scope.launch(Dispatchers.IO) { capture() }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun discard() {
        epoch.incrementAndGet()
        utteranceId.incrementAndGet()
        committed = ""
        discardRequested = true
    }

    override fun cancel() {
        cancelled = true
        stopRequested = true
        NativeWhisper.nativeCancel()
        job?.cancel()
        scope.cancel()
    }

    private fun post(block: () -> Unit) {
        if (!cancelled) main.post { if (!cancelled) block() }
    }

    private suspend fun capture() {
        val mic = AudioCapture()
        if (!mic.open()) {
            mic.close()
            post { listener.onError("Could not open the microphone.") }
            return
        }
        val vad = Vad(endSilenceMs = SENTENCE_GAP_MS)
        val frame = ShortArray(vad.frameSamples)
        val frameF = FloatArray(vad.frameSamples)
        val preRoll = ArrayDeque<FloatArray>()
        val utterance = ArrayList<FloatArray>()
        var speechSeen = false
        var utteranceMs = 0
        var idleMs = 0
        var sinceLastPartialMs = 0
        var partialBusy = false
        var failure: String? = null

        // Finished sentences are decoded one at a time, in the order they were spoken.
        val queue = Channel<Segment>(Channel.UNLIMITED)
        val consumer = scope.launch(Dispatchers.Default) {
            for (seg in queue) {
                val text = if (cancelled) "" else decode(seg.audio, accurate = true)
                pending.decrementAndGet()
                if (cancelled) break
                if (seg.epoch == epoch.get()) {
                    if (text.isNotBlank()) {
                        committed = joinText(committed, text)
                        post { listener.onSegment(text) }
                    } else {
                        post { listener.onPartial("") }
                    }
                }
            }
        }

        fun submit() {
            val audio = flatten(utterance)
            utterance.clear()
            utteranceMs = 0
            sinceLastPartialMs = 0
            utteranceId.incrementAndGet()
            if (audio.size >= MIN_AUDIO_SAMPLES) {
                pending.incrementAndGet()
                queue.trySend(Segment(audio, epoch.get()))
            } else {
                post { listener.onPartial("") }
            }
        }

        try {
            while (scope.isActive && !cancelled && !stopRequested) {
                if (discardRequested) {
                    discardRequested = false
                    utterance.clear()
                    preRoll.clear()
                    utteranceMs = 0
                    sinceLastPartialMs = 0
                    speechSeen = false
                    vad.reset()
                }
                val n = mic.read(frame, frame.size)
                if (n <= 0) {
                    failure = "Microphone stopped responding."
                    break
                }
                AudioDsp.toFloat(frame, n, frameF)
                val ev = vad.process(frameF, n)
                val lvl = vad.level
                post { listener.onLevel(lvl) }
                val copy = frameF.copyOf(n)

                if (!speechSeen) {
                    preRoll.addLast(copy) // 300 ms kept from before speech started, so first syllables are not clipped
                    if (preRoll.size > MAX_PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (ev == VadEvent.SpeechStarted) {
                        speechSeen = true
                        utterance.addAll(preRoll)
                        utteranceMs = utterance.size * FRAME_MS
                        preRoll.clear()
                        idleMs = 0
                    } else {
                        idleMs += FRAME_MS
                        // Nobody is talking for a long time: stop by itself to save the battery.
                        if (idleMs >= MAX_IDLE_MS) break
                    }
                } else {
                    utterance.add(copy)
                    utteranceMs += FRAME_MS
                    sinceLastPartialMs += FRAME_MS
                    if (sinceLastPartialMs >= PARTIAL_EVERY_MS && !partialBusy && pending.get() == 0 && utterance.size > 20) {
                        sinceLastPartialMs = 0
                        partialBusy = true
                        val snapshot = flatten(utterance)
                        val id = utteranceId.get()
                        val ep = epoch.get()
                        scope.launch(Dispatchers.Default) {
                            try {
                                val t = decode(snapshot, accurate = false)
                                if (t.isNotBlank() && id == utteranceId.get() && ep == epoch.get()) post { listener.onPartial(t) }
                            } finally {
                                partialBusy = false
                            }
                        }
                    }
                    if (ev == VadEvent.SpeechEnded) {
                        // A pause ends the sentence, never the session.
                        submit()
                        speechSeen = false
                        idleMs = 0
                    } else if (utteranceMs >= MAX_SEGMENT_MS) {
                        submit() // Whisper reads at most 30 s at a time; cut here and keep listening
                    }
                }
            }
        } finally {
            mic.close()
        }
        if (cancelled) return
        post { listener.onLevel(0f) }
        if (speechSeen && utterance.isNotEmpty()) submit()
        queue.close()
        consumer.join()
        if (cancelled) return
        val problem = failure
        if (problem != null) post { listener.onError(problem) } else post { listener.onFinal("") }
    }

    private fun decode(audio: FloatArray, accurate: Boolean): String {
        val quiet = Vad.rms(audio) < QUIET_RMS
        val pcm = AudioDsp.normalize(audio)
        val bytes = NativeWhisper.nativeTranscribe(pcm, language, promptFor(committed), threads, accurate)
        return TranscriptCleaner.clean(String(bytes, Charsets.UTF_8).trim(), quiet)
    }

    private fun flatten(frames: List<FloatArray>): FloatArray {
        var total = 0
        for (f in frames) total += f.size
        val out = FloatArray(total)
        var pos = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, pos, f.size)
            pos += f.size
        }
        return out
    }

    private companion object {
        const val FRAME_MS = 30
        const val MAX_PRE_ROLL_FRAMES = 10

        /** Quiet needed to end a sentence. Long enough that a thinking pause inside a sentence rarely splits it. */
        const val SENTENCE_GAP_MS = 1_400
        const val MAX_SEGMENT_MS = 25_000
        const val MAX_IDLE_MS = 120_000
        const val PARTIAL_EVERY_MS = 1_200
        const val MIN_AUDIO_SAMPLES = 4_800
        const val CONTEXT_CHARS = 160
        const val QUIET_RMS = 0.012f
    }
}
