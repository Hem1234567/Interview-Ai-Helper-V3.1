package com.interviewcopilot.app.voice

/** Callbacks from a speech session. All are invoked on the main thread. */
interface SttListener {
    fun onLevel(level: Float)

    /** Live text of the sentence being spoken right now. Replaced by [onSegment] when the sentence is finished. */
    fun onPartial(text: String)

    /** One finished sentence. Listening continues: a pause never ends the session. */
    fun onSegment(text: String)

    /** The session ended (the person stopped it). [text] is only what was not already reported through [onSegment]. */
    fun onFinal(text: String)
    fun onError(message: String)
}

/** One capture. It keeps listening through pauses until [stop]; [stop] still delivers a final result. */
interface SttSession {
    fun start()
    fun stop()
    fun cancel()

    /** Forget everything heard so far that has not been reported yet (the Clear button). Listening continues. */
    fun discard() {}
}

/** "auto" lets Whisper detect the language. */
data class SttLanguage(val code: String, val label: String)

object SttLanguages {
    val all = listOf(
        SttLanguage("auto", "Auto-detect"),
        SttLanguage("en", "English"),
        SttLanguage("hi", "Hindi"),
        SttLanguage("ta", "Tamil"),
        SttLanguage("te", "Telugu"),
        SttLanguage("es", "Spanish"),
        SttLanguage("fr", "French"),
        SttLanguage("de", "German"),
        SttLanguage("pt", "Portuguese"),
        SttLanguage("it", "Italian"),
        SttLanguage("ja", "Japanese"),
        SttLanguage("ko", "Korean"),
        SttLanguage("zh", "Chinese"),
        SttLanguage("ar", "Arabic"),
    )

    fun label(code: String) = all.firstOrNull { it.code == code }?.label ?: code
}
