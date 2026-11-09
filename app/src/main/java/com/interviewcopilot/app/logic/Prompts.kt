package com.interviewcopilot.app.logic

object Prompts {
    val modes = listOf(
        "Interview",
        "Coding",
        "HR",
        "Behavioral",
        "System Design",
        "English Communication",
    )

    private const val BASE =
        "You are a real-time interview copilot. The user is the candidate in a live interview. " +
            "Give the exact answer the candidate should say aloud: first person, natural spoken English, correct and concise. " +
            "Start with ONE strong sentence that directly answers the question. Then add 2 to 4 short supporting points. " +
            "Use the candidate's resume, education marks and the job description when relevant. Never invent experience or marks that are not given. " +
            "Be factually correct: if unsure, give the standard textbook answer, never guess. " +
            "If the question is unclear, answer the most likely interpretation. No greetings, no preamble."

    private fun modePrompt(mode: String): String = when (mode) {
        "Coding" -> {
            "MODE: Coding. One line on the approach, then clean correct code in a fenced code block " +
                "(default Java unless another language is named), then time and space complexity and one edge case. " +
                "Keep explanations minimal."
        }

        "HR" -> {
            "MODE: HR. Short, confident, honest answers with a positive tone, 4 to 6 lines."
        }

        "Behavioral" -> {
            "MODE: Behavioral. Use STAR format (Situation, Task, Action, Result) in 5 to 7 short lines, built on the resume when possible."
        }

        "System Design" -> {
            "MODE: System Design. Requirements, high-level components, data flow, scaling and trade-offs as compact bullets."
        }

        "English Communication" -> {
            "MODE: English Communication. Use simple, clear English with short sentences that are easy to speak aloud."
        }

        else -> {
            "MODE: Technical and general interview. 4 to 6 lines."
        }
    }

    private fun styleLine(style: String): String = when (style) {
        "Confident" -> "STYLE: confident and assertive tone."
        "STAR" -> "STYLE: structure answers in STAR format when it fits."
        "Simple English" -> "STYLE: very simple English, short sentences."
        else -> "STYLE: concise."
    }

    private fun lengthLine(speed: String): String = when (speed) {
        "Fast" -> "LENGTH: very short, at most 3 lines."
        "Deep" -> "LENGTH: detailed and thorough, but still spoken style."
        else -> "LENGTH: 3 to 5 lines."
    }

    fun maxTokens(speed: String): Int = when (speed) {
        "Fast" -> 260
        "Deep" -> 1000
        else -> 480
    }

    fun build(
        mode: String,
        style: String,
        speed: String,
        resume: String,
        job: String,
        vocab: String,
        history: List<Pair<String, String>>,
        question: String,
        extra: String,
    ): Triple<String, String, Int> {
        val sys = StringBuilder()
        sys.append(BASE).append("\n").append(modePrompt(mode)).append("\n")
            .append(styleLine(style)).append("\n").append(lengthLine(speed))
        if (resume.isNotBlank()) sys.append("\n\nCANDIDATE RESUME:\n").append(resume.take(1200))
        if (job.isNotBlank()) sys.append("\n\nTARGET JOB:\n").append(job.take(800))
        if (vocab.isNotBlank()) sys.append("\n\nTERMS (correct spellings): ").append(vocab.replace("\n", ", ").take(300))

        val user = StringBuilder()
        if (history.isNotEmpty()) {
            user.append("Earlier in this interview:\n")
            history.takeLast(3).forEach { (q, a) -> user.append("Q: ").append(q.take(200)).append("\nA: ").append(a.take(300)).append("\n") }
            user.append("\n")
        }
        user.append("Interviewer's question: ").append(question)
        if (extra.isNotBlank()) user.append("\n\nExtra instruction: ").append(extra)
        return Triple(sys.toString(), user.toString(), maxTokens(speed))
    }

    const val SCORE =
        "You are a strict but kind interview coach. Score the candidate's answer. " +
            "Reply in this format:\nScore: X/10\nClarity: X/10\nStructure: X/10\nCorrectness: X/10\n" +
            "What was good: one line\nWhat to improve: up to 3 short bullets\nImproved answer: a short model answer in first person. " +
            "Also mention filler words if any."

    const val JOB_ANALYSIS =
        "You analyse job descriptions. Reply with exactly:\nKeywords: comma separated top 12 skills and keywords\n" +
            "Likely questions:\n1. ...\n2. ...\n(8 likely interview questions)\nPrep tip: one line."
}

object Vocab {
    fun apply(text: String, rules: String): String {
        var out = text
        rules.lines().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) {
                val wrong = line.substring(0, i).trim()
                val right = line.substring(i + 1).trim()
                if (wrong.isNotEmpty() && right.isNotEmpty()) {
                    out = Regex("\\b" + Regex.escape(wrong) + "\\b", RegexOption.IGNORE_CASE)
                        .replace(out, Regex.escapeReplacement(right))
                }
            }
        }
        return out
    }
}

object Bank {
    val topics: Map<String, List<String>> = linkedMapOf(
        "Java" to listOf(
            "What is the difference between an abstract class and an interface in Java?",
            "Explain how HashMap works internally.",
            "What is the difference between == and equals() in Java?",
            "Explain the Java memory model and garbage collection.",
        ),
        "Python" to listOf(
            "What is the difference between a list and a tuple in Python?",
            "Explain decorators in Python with an example.",
            "What is the GIL and why does it matter?",
            "How do generators differ from normal functions?",
        ),
        "DSA" to listOf(
            "Explain the difference between an array and a linked list.",
            "How would you detect a cycle in a linked list?",
            "What is the time complexity of quicksort in the best and worst case?",
            "Explain how a hash table handles collisions.",
        ),
        "SQL" to listOf(
            "What is the difference between INNER JOIN and LEFT JOIN?",
            "Explain database normalization.",
            "What is an index and when can it hurt performance?",
            "What is the difference between WHERE and HAVING?",
        ),
        "System Design" to listOf(
            "How would you design a URL shortener?",
            "How would you design a rate limiter?",
            "Explain caching strategies and when to use them.",
            "How do you scale a database for heavy read traffic?",
        ),
        "HR" to listOf(
            "Tell me about yourself.",
            "Why do you want to join our company?",
            "What are your strengths and weaknesses?",
            "Where do you see yourself in five years?",
        ),
        "Behavioral" to listOf(
            "Tell me about a time you faced a difficult challenge.",
            "Describe a time you worked in a team under pressure.",
            "Tell me about a mistake you made and what you learned.",
            "Describe a time you had a conflict with a teammate.",
        ),
        "COBOL" to listOf(
            "What are the four divisions of a COBOL program?",
            "What is the difference between COMP and COMP-3?",
            "Explain the PERFORM statement and its variations.",
            "What is JCL and what is it used for?",
        ),
        "Communication" to listOf(
            "Introduce yourself in one minute.",
            "Explain a technical concept to a non-technical person.",
            "How do you handle disagreement politely?",
            "Describe your best project in simple English.",
        ),
    )
}
