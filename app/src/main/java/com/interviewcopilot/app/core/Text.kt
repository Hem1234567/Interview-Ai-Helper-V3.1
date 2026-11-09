package com.interviewcopilot.app.core

/** Joins two pieces of dictated text with exactly one space. */
fun joinText(a: String, b: String): String {
    val x = a.trim()
    val y = b.trim()
    return when {
        x.isEmpty() -> y
        y.isEmpty() -> x
        else -> "$x $y"
    }
}

/**
 * Cleans what the speech engine returns so the question box contains only what the person said.
 * Whisper sometimes invents text on silence or noise ("Thank you.", "[Music]", a word repeated ten times);
 * this removes those without touching real speech.
 */
object TranscriptCleaner {
    private val bracketTag = Regex("""\[[^\]]{0,40}]""")
    private val parenTag = Regex(
        """\((?:music|applause|laughter|laughs|silence|noise|inaudible|coughing|cough|sighs|sigh|background[^)]*|speaking[^)]*)\)""",
        RegexOption.IGNORE_CASE,
    )
    private val noteSigns = Regex("[\u266A\u266B]+")
    private val spaces = Regex("\\s+")

    /** Phrases Whisper hallucinates from training subtitles. Dropped when they make up the whole segment. */
    private val alwaysDrop = listOf(
        "subtitles by the amara",
        "amara org",
        "please subscribe",
        "like and subscribe",
        "thanks for watching",
        "thank you for watching",
        "transcribed by",
        "translated by",
    )

    /** Short courtesy words that are normal speech, but a hallucination when the audio was almost silent. */
    private val courtesy = setOf(
        "thank you",
        "thanks",
        "thank you very much",
        "bye",
        "bye bye",
        "goodbye",
        "you",
        "okay",
        "ok",
        "yeah",
        "so",
        "uh",
        "um",
        "hmm",
        "mm",
        "mhm",
        "the end",
    )

    fun clean(text: String, quiet: Boolean = false): String {
        var t = bracketTag.replace(text, " ")
        t = parenTag.replace(t, " ")
        t = noteSigns.replace(t, " ")
        t = spaces.replace(t, " ").trim()
        if (t.isEmpty()) return ""

        val norm = t.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(spaces, " ").trim()
        if (norm.isEmpty()) return ""
        val words = norm.split(' ').size
        if (words <= 10 && alwaysDrop.any { norm.contains(it) }) return ""
        if (quiet && norm in courtesy) return ""
        return collapseRepeats(t)
    }

    /** "the the the the" -> "the" and "i mean i mean i mean i mean" -> "i mean". */
    fun collapseRepeats(text: String): String {
        var tokens = text.split(' ').filter { it.isNotEmpty() }
        var changed = true
        while (changed) {
            changed = false
            outer@ for (n in 1..6) {
                val need = if (n == 1) 4 else 3
                var i = 0
                while (i + n * need <= tokens.size) {
                    var reps = 1
                    while (i + n * (reps + 1) <= tokens.size && sameRun(tokens, i, i + n * reps, n)) reps++
                    if (reps >= need) {
                        tokens = tokens.subList(0, i + n) + tokens.subList(i + n * reps, tokens.size)
                        changed = true
                        break@outer
                    }
                    i++
                }
            }
        }
        return tokens.joinToString(" ")
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private fun sameRun(tokens: List<String>, a: Int, b: Int, n: Int): Boolean {
        for (k in 0 until n) if (key(tokens[a + k]) != key(tokens[b + k]) || key(tokens[a + k]).isEmpty()) return false
        return true
    }
}
