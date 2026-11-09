package com.interviewcopilot.app.logic

import com.interviewcopilot.app.core.FailureKind
import com.interviewcopilot.app.core.ProviderFailure
import com.interviewcopilot.app.llm.LlamaEngine
import com.interviewcopilot.app.llm.LocalModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface LlmProvider {
    val name: String
    fun stream(system: String, user: String, maxTokens: Int): Flow<String>
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .build()

    /** Opens the secure connections ahead of time so the first answer does not pay for the handshake. */
    fun prewarm(urls: List<String>) {
        for (u in urls) {
            try {
                client.newCall(Request.Builder().url(u).head().build()).enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {}
                        override fun onResponse(call: Call, response: Response) {
                            response.close()
                        }
                    },
                )
            } catch (ignored: Exception) {
                // a bad URL must never break the app
            }
        }
    }
}

const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")

/** Suspends until the response headers arrive. Cancelling the coroutine cancels the request at once. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                val kind = if (e is SocketTimeoutException) FailureKind.Timeout else FailureKind.Network
                cont.resumeWithException(ProviderFailure(kind, 0, e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        },
    )
}

/** Reads a server-sent-event body and emits the text each event carries. Cancelling stops the download immediately. */
private suspend fun FlowCollector<String>.pump(call: Call, resp: Response, engine: String, extract: (JSONObject) -> String) {
    resp.use {
        val src = it.body?.source() ?: throw ProviderFailure(FailureKind.Other, it.code, "$engine sent an empty reply")
        try {
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        ensureActive()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        val obj = try {
                            JSONObject(data)
                        } catch (e: Exception) {
                            continue
                        }
                        if (obj.has("error") && !obj.isNull("error")) {
                            val msg = obj.optJSONObject("error")?.str("message") ?: obj.str("error")
                            throw ProviderFailure(FailureKind.Server, 0, "$engine: ${msg.take(120)}")
                        }
                        val piece = try {
                            extract(obj)
                        } catch (e: Exception) {
                            ""
                        }
                        if (piece.isNotEmpty()) emit(piece)
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: ProviderFailure) {
            throw e
        } catch (e: IOException) {
            throw ProviderFailure(FailureKind.Network, 0, "$engine connection lost")
        }
    }
}

/** Shared by every provider that speaks the OpenAI chat format (OpenRouter and Groq). */
private fun openAiStyle(name: String, url: String, key: String, body: JSONObject, headers: Map<String, String>): Flow<String> = flow {
    val builder = Request.Builder().url(url)
        .addHeader("Authorization", "Bearer $key")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
    headers.forEach { (k, v) -> builder.addHeader(k, v) }
    val call = Http.client.newCall(builder.build())
    val resp = call.await()
    if (!resp.isSuccessful) {
        val text = resp.use { it.body?.string().orEmpty() }
        throw ProviderFailure.fromHttp(name, resp.code, text, resp.header("Retry-After"))
    }
    pump(call, resp, name) { obj ->
        val delta = obj.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        delta.str("content")
    }
}.flowOn(Dispatchers.IO)

private fun chatMessages(system: String, user: String): JSONArray =
    JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))

class OpenRouterProvider(private val key: String, private val model: String) : LlmProvider {
    override val name = "OpenRouter"

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            // Models that think first spend part of the limit on it, so leave room.
            .put("max_tokens", maxTokens + 300)
            .put("temperature", 0.3)
            .put("messages", chatMessages(system, user))
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("provider", JSONObject().put("sort", "latency"))
        val headers = mapOf("HTTP-Referer" to "https://interviewcopilot.app", "X-Title" to "Interview Copilot")
        return openAiStyle(name, OPENROUTER_URL, key, body, headers)
    }
}

class GroqProvider(private val key: String, private val model: String) : LlmProvider {
    override val name = "Groq"

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> {
        val thinks = model.contains("gpt-oss") || model.contains("qwen3")
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", if (thinks) maxTokens + 400 else maxTokens)
            .put("temperature", 0.3)
            .put("messages", chatMessages(system, user))
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
        return openAiStyle(name, GROQ_URL, key, body, emptyMap())
    }
}

class GeminiProvider(private val key: String, private val model: String) : LlmProvider {
    override val name = "Google"

    /** Fast answers need the model's own "thinking" switched down. A model that rejects the setting is retried without it. */
    private fun thinking(): JSONObject? = when {
        model.contains("gemini-3") -> JSONObject().put("thinkingLevel", "minimal")
        model.contains("2.5") && model.contains("flash") -> JSONObject().put("thinkingBudget", 0)
        else -> null
    }

    private fun body(system: String, user: String, maxTokens: Int, withThinking: Boolean): JSONObject {
        val gen = JSONObject().put("maxOutputTokens", maxTokens + 200).put("temperature", 0.3)
        val t = thinking()
        if (withThinking && t != null) gen.put("thinkingConfig", t)
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
            .put("generationConfig", gen)
    }

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> = flow {
        var withThinking = thinking() != null
        while (true) {
            val req = Request.Builder()
                .url("$GEMINI_BASE$model:streamGenerateContent?alt=sse")
                .addHeader("x-goog-api-key", key)
                .post(body(system, user, maxTokens, withThinking).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = Http.client.newCall(req)
            val resp = call.await()
            if (!resp.isSuccessful) {
                val text = resp.use { it.body?.string().orEmpty() }
                if (resp.code == 400 && withThinking) {
                    withThinking = false
                    continue
                }
                throw ProviderFailure.fromHttp(name, resp.code, text, resp.header("Retry-After"))
            }
            pump(call, resp, name) { obj ->
                val parts = obj.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (!p.optBoolean("thought", false)) sb.append(p.str("text"))
                }
                sb.toString()
            }
            return@flow
        }
    }.flowOn(Dispatchers.IO)
}

/** On-device answer engine (llama.cpp). Loads the model on first use if the warm-up has not finished. */
class LocalProvider(
    private val engine: LlamaEngine,
    private val model: LocalModel,
    private val file: java.io.File,
) : LlmProvider {
    override val name = "Local"

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> = flow {
        if (!engine.load(model, file)) throw ProviderFailure(FailureKind.Other, 0, "The on-device model could not be loaded.")
        emitAll(engine.stream(system, user, maxTokens, thinking = false))
    }
}
