package com.interviewcopilot.app.core

import java.io.IOException

/** The answer engines the person can pick from the drop-down. [Auto] tries them in order and ends on the local model. */
enum class Engine(val label: String) {
    Auto("Auto"),
    Local("Local"),
    OpenRouter("OpenRouter"),
    Google("Google"),
    Groq("Groq"),
    ;

    companion object {
        /** Cloud engines in the order Auto tries them: fastest first. */
        val cloud: List<Engine> = listOf(Groq, Google, OpenRouter)

        /** Reads a saved setting. Older versions saved "Gemini" for Google. Unknown values mean Auto. */
        fun from(saved: String): Engine = when (saved) {
            "Gemini" -> Google
            else -> values().firstOrNull { it.name == saved } ?: Auto
        }
    }
}

enum class FailureKind { RateLimit, BadKey, ModelGone, Timeout, Server, Network, Other }

/** Why an engine could not answer. The kind decides how long the engine is skipped afterwards. */
class ProviderFailure(
    val kind: FailureKind,
    val httpCode: Int,
    message: String,
    val retryAfterMs: Long = 0L,
) : IOException(message) {
    companion object {
        fun fromHttp(engine: String, code: Int, body: String, retryAfterSeconds: String?): ProviderFailure {
            val kind = when {
                code == 429 -> FailureKind.RateLimit

                code == 401 || code == 403 -> FailureKind.BadKey

                code == 404 || code == 410 -> FailureKind.ModelGone

                code == 402 -> FailureKind.RateLimit

                // out of credit behaves like a limit
                code == 408 -> FailureKind.Timeout

                code in 500..599 -> FailureKind.Server

                code == 400 && body.contains("quota", ignoreCase = true) -> FailureKind.RateLimit

                else -> FailureKind.Other
            }
            val wait = retryAfterSeconds?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            val brief = body.replace(Regex("\\s+"), " ").take(160)
            val label = when (kind) {
                FailureKind.RateLimit -> "limit reached"
                FailureKind.BadKey -> "key rejected"
                FailureKind.ModelGone -> "model not found"
                FailureKind.Server -> "service busy"
                else -> "error"
            }
            return ProviderFailure(kind, code, "$engine $label ($code) $brief".trim(), wait)
        }
    }
}

/** Remembers which (engine, model) pairs recently failed so Auto does not waste time on them again. */
class Health(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val until = HashMap<String, Long>()

    fun fail(key: String, f: ProviderFailure) {
        val ms = when (f.kind) {
            FailureKind.RateLimit -> if (f.retryAfterMs > 0) f.retryAfterMs.coerceIn(15_000L, 300_000L) else 60_000L
            FailureKind.BadKey -> 600_000L
            FailureKind.ModelGone -> 1_800_000L
            FailureKind.Timeout -> 20_000L
            FailureKind.Server -> 30_000L
            FailureKind.Network -> 15_000L
            FailureKind.Other -> 10_000L
        }
        until[key] = now() + ms
    }

    fun ok(key: String) {
        until.remove(key)
    }

    fun cooling(key: String): Boolean = (until[key] ?: 0L) > now()

    fun reset() = until.clear()
}

data class Candidate(val engine: Engine, val model: String) {
    val key: String get() = "${engine.name}:$model"
    val label: String get() = if (model.isBlank()) engine.label else "${engine.label} · ${model.substringAfterLast('/')}"

    /** How long to wait for the first word before moving to the next engine. */
    val firstTokenTimeoutMs: Long
        get() = when (engine) {
            Engine.Groq -> 2_500L
            Engine.Google -> 3_000L
            Engine.OpenRouter -> 3_500L
            else -> 120_000L
        }
}

object Models {
    /** "a, b ; c" -> [a, b, c]. Engines try the models in this order. */
    fun parse(csv: String): List<String> =
        csv.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

object RoutePlanner {
    /** Every answer must start within this time before Auto jumps to the on-device model. */
    const val BUDGET_MS = 4_000L

    /**
     * The order in which engines are tried.
     *
     * Auto: ready cloud engines (Groq, Google, OpenRouter), then the local model, then engines that are cooling down.
     * A specific engine goes first; with [fallback] on, the Auto order follows it.
     */
    fun plan(
        selected: Engine,
        fallback: Boolean,
        cloud: Map<Engine, List<String>>,
        localReady: Boolean,
        health: Health,
    ): List<Candidate> {
        fun of(e: Engine): List<Candidate> = when (e) {
            Engine.Local -> if (localReady) listOf(Candidate(Engine.Local, "")) else emptyList()
            Engine.Auto -> emptyList()
            else -> cloud[e].orEmpty().map { Candidate(e, it) }
        }

        val autoOrder = Engine.cloud.flatMap { of(it) } + of(Engine.Local)
        val ordered = if (selected == Engine.Auto) {
            autoOrder
        } else {
            val first = of(selected)
            if (fallback) first + autoOrder.filter { it !in first } else first
        }
        val ready = ordered.filter { !health.cooling(it.key) }
        val cooling = ordered.filter { health.cooling(it.key) }
        return ready + cooling
    }

    /** Whether to skip this cloud engine because the time budget is gone and the local model can answer. */
    fun skipCloud(c: Candidate, elapsedMs: Long, localAvailable: Boolean): Boolean =
        c.engine != Engine.Local && localAvailable && elapsedMs >= BUDGET_MS

    /** First-word timeout for this attempt, shrunk so the whole thing stays near the budget. */
    fun timeoutFor(c: Candidate, elapsedMs: Long): Long =
        if (c.engine == Engine.Local) c.firstTokenTimeoutMs else minOf(c.firstTokenTimeoutMs, maxOf(1_500L, BUDGET_MS - elapsedMs))
}
