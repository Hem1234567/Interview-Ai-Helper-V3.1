package com.interviewcopilot.app.vm

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interviewcopilot.app.core.Candidate
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.Engine
import com.interviewcopilot.app.core.FailureKind
import com.interviewcopilot.app.core.Health
import com.interviewcopilot.app.core.MarksMath
import com.interviewcopilot.app.core.MarksParser
import com.interviewcopilot.app.core.Marksheet
import com.interviewcopilot.app.core.Models
import com.interviewcopilot.app.core.ProviderFailure
import com.interviewcopilot.app.core.RoutePlanner
import com.interviewcopilot.app.core.Stage
import com.interviewcopilot.app.core.SubjectMark
import com.interviewcopilot.app.core.ThinkFilter
import com.interviewcopilot.app.core.VoiceEvent
import com.interviewcopilot.app.core.VoiceFlow
import com.interviewcopilot.app.core.VoiceState
import com.interviewcopilot.app.data.Defaults
import com.interviewcopilot.app.data.JobProfile
import com.interviewcopilot.app.data.QA
import com.interviewcopilot.app.data.Store
import com.interviewcopilot.app.edu.MarksheetReader
import com.interviewcopilot.app.llm.DeviceProbe
import com.interviewcopilot.app.llm.DownloadState
import com.interviewcopilot.app.llm.EngineState
import com.interviewcopilot.app.llm.LlamaEngine
import com.interviewcopilot.app.llm.LocalModel
import com.interviewcopilot.app.llm.ModelAssessment
import com.interviewcopilot.app.llm.ModelCatalog
import com.interviewcopilot.app.llm.ModelDownloader
import com.interviewcopilot.app.llm.ModelStore
import com.interviewcopilot.app.llm.Recommender
import com.interviewcopilot.app.llm.SpeechModel
import com.interviewcopilot.app.logic.GeminiProvider
import com.interviewcopilot.app.logic.GroqProvider
import com.interviewcopilot.app.logic.Http
import com.interviewcopilot.app.logic.LlmProvider
import com.interviewcopilot.app.logic.LocalProvider
import com.interviewcopilot.app.logic.OpenRouterProvider
import com.interviewcopilot.app.logic.Prompts
import com.interviewcopilot.app.logic.Vocab
import com.interviewcopilot.app.voice.NativeWhisper
import com.interviewcopilot.app.voice.PlatformRecognizer
import com.interviewcopilot.app.voice.SttListener
import com.interviewcopilot.app.voice.SttSession
import com.interviewcopilot.app.voice.WhisperRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import java.util.UUID

class CopilotVM(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = Store(ctx)

    // ---------- settings ----------
    val prefs = mutableStateMapOf<String, String>().apply { putAll(store.all()) }
    fun g(key: String): String = prefs[key] ?: Defaults.map[key] ?: ""
    fun b(key: String): Boolean = g(key) == "true"
    fun put(key: String, value: String) {
        prefs[key] = value
        store.put(key, value)
    }

    // ---------- models, device, downloads ----------
    val modelStore = ModelStore(ctx)
    val downloader = ModelDownloader()
    val engine = LlamaEngine(ctx)
    val device = DeviceProbe.read(ctx)

    var assessments by mutableStateOf<List<ModelAssessment>>(emptyList())
        private set
    var downloadStates by mutableStateOf<Map<String, DownloadState>>(emptyMap())
        private set
    var engineState by mutableStateOf<EngineState>(EngineState.Unloaded)
        private set
    private var downloadedTick by mutableStateOf(0)

    // ---------- voice flow ----------
    var voice by mutableStateOf(VoiceState())
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var thinking by mutableStateOf(false)
        private set
    var engineUsed by mutableStateOf("")
        private set
    var firstTokenMs by mutableStateOf(0L)
        private set
    var mode by mutableStateOf("Interview")

    val session = mutableStateListOf<QA>()
    private var sessionId = UUID.randomUUID().toString()
    val history = mutableStateListOf<QA>().apply { addAll(store.loadHistory()) }
    val jobs = mutableStateListOf<JobProfile>().apply { addAll(store.loadJobs()) }

    // ---------- practice / jobs ----------
    var practiceOut by mutableStateOf("")
    var practiceBusy by mutableStateOf(false)
    var jobBusyId by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    // ---------- education (marksheets) ----------
    val marks = mutableStateMapOf<String, Marksheet>().apply { putAll(store.loadMarks()) }
    var marksBusy by mutableStateOf("")
        private set
    var marksMessage by mutableStateOf<String?>(null)

    // ---------- answer engines ----------
    private val health = Health()
    var engineTests by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private var pendingGenerate = false
    private var genJob: Job? = null
    private var stt: SttSession? = null
    private var tts: TextToSpeech? = null
    private var loadedWhisper: String? = null

    init {
        migrateSettings()
        mode = g("mode")
        refreshAssessments()
        ModelCatalog.models.forEach { downloader.restore(modelStore.spec(it)) }
        ModelCatalog.speech.forEach { downloader.restore(modelStore.spec(it)) }
        downloader.states.onEach { st ->
            val finished = st.any { (k, v) -> v is DownloadState.Done && downloadStates[k] !is DownloadState.Done }
            downloadStates = st
            if (finished) {
                downloadedTick++
                onDownloadFinished()
            }
        }.launchIn(viewModelScope)
        engine.state.onEach { engineState = it }.launchIn(viewModelScope)
        applyLowEndFallback()
        warmUpSelected()
        prewarmNetwork()
    }

    /** Models that were retired since an earlier version saved them would fail on every answer. */
    private fun migrateSettings() {
        if (prefs["provider"] == "Gemini") put("provider", "Google")
        if (prefs["geminiModel"] in setOf(
                "gemini-2.0-flash",
                "gemini-2.0-flash-001",
                "gemini-1.5-flash",
            )
        ) {
            put("geminiModel", Defaults.map.getValue("geminiModel"))
        }
        if (prefs["orModel"] == "google/gemini-2.0-flash-001") put("orModel", Defaults.map.getValue("orModel"))
    }

    fun prewarmNetwork() {
        val urls = ArrayList<String>()
        if (g("groqKey").isNotBlank()) urls.add("https://api.groq.com/")
        if (g("geminiKey").isNotBlank()) urls.add("https://generativelanguage.googleapis.com/")
        if (g("orKey").isNotBlank()) urls.add("https://openrouter.ai/")
        if (urls.isNotEmpty()) Http.prewarm(urls)
    }

    // ---------- derived ----------
    fun isDownloaded(m: LocalModel): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        downloadedTick
        return modelStore.isDownloaded(m)
    }

    fun isDownloaded(m: SpeechModel): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        downloadedTick
        return modelStore.isDownloaded(m)
    }

    val selectedModel: LocalModel? get() = ModelCatalog.find(g("localModelId"))
    val selectedSpeech: SpeechModel? get() = ModelCatalog.findSpeech(g("whisperModelId"))
    val hasLocalModel: Boolean get() = selectedModel?.let { isDownloaded(it) } == true
    val recommendedModel: LocalModel? get() = assessments.firstOrNull { it.recommended }?.model

    fun stateOf(id: String): DownloadState = downloadStates[id] ?: DownloadState.Idle

    fun refreshAssessments() {
        assessments = Recommender.assess(device).map { Recommender.withStorage(it, device.freeStorageMb) }
    }

    private fun applyLowEndFallback() {
        val downloaded = ModelCatalog.models.filter { modelStore.isDownloaded(it) }.map { it.id }.toSet()
        val better = Recommender.fallbackFor(g("localModelId").ifBlank { null }, downloaded, device)
        if (better != null) put("localModelId", better.id)
    }

    private fun warmUpSelected() {
        val m = selectedModel ?: return
        // Cloud engines answer first when they have keys; the local model is then loaded only when it is needed.
        val cloudReady = cloudModels().isNotEmpty()
        if (modelStore.isDownloaded(m) && (Engine.from(g("provider")) == Engine.Local || !cloudReady)) engine.warmUp(m, modelStore.file(m))
        val sp = selectedSpeech ?: return
        if (modelStore.isDownloaded(sp)) viewModelScope.launch(Dispatchers.IO) { ensureWhisper(sp) }
    }

    private fun onDownloadFinished() {
        if (selectedModel == null) {
            // First model ever downloaded: select it automatically.
            ModelCatalog.models.firstOrNull { modelStore.isDownloaded(it) }?.let { selectModel(it) }
        }
        val sp = selectedSpeech
        if (sp != null && modelStore.isDownloaded(sp) && loadedWhisper != sp.id) {
            viewModelScope.launch(Dispatchers.IO) { ensureWhisper(sp) }
        }
    }

    // ---------- model actions ----------
    fun download(m: LocalModel) = downloader.start(modelStore.spec(m))
    fun download(m: SpeechModel) = downloader.start(modelStore.spec(m))
    fun pause(id: String) = downloader.pause(id)

    fun selectModel(m: LocalModel) {
        if (!modelStore.isDownloaded(m)) return
        put("localModelId", m.id)
        engine.warmUp(m, modelStore.file(m))
    }

    fun selectSpeech(m: SpeechModel) {
        put("whisperModelId", m.id)
        if (modelStore.isDownloaded(m)) viewModelScope.launch(Dispatchers.IO) { ensureWhisper(m) }
    }

    fun delete(m: LocalModel) {
        if (g("localModelId") == m.id) {
            engine.unload()
            put("localModelId", "")
        }
        downloader.cancelAndDelete(modelStore.spec(m))
        downloadedTick++
    }

    fun delete(m: SpeechModel) {
        if (loadedWhisper == m.id) {
            NativeWhisper.nativeFree()
            loadedWhisper = null
        }
        downloader.cancelAndDelete(modelStore.spec(m))
        downloadedTick++
    }

    private fun ensureWhisper(m: SpeechModel): Boolean {
        if (loadedWhisper == m.id && NativeWhisper.nativeIsLoaded()) return true
        return try {
            NativeWhisper.ensureBackends(ctx.applicationInfo.nativeLibraryDir)
            val ok = NativeWhisper.nativeLoad(modelStore.file(m).absolutePath)
            loadedWhisper = if (ok) m.id else null
            ok
        } catch (e: LinkageError) {
            false
        }
    }

    // ---------- voice: step 1 and 2, speak and edit ----------
    fun updateMode(m: String) {
        mode = m
        put("mode", m)
    }

    private fun dispatch(e: VoiceEvent) {
        voice = VoiceFlow.reduce(voice, e)
    }

    /** Mic button: start listening, or finish the current capture. */
    fun micTapped() {
        when (voice.stage) {
            Stage.Listening -> stt?.stop()
            Stage.Generating -> Unit
            else -> startListening()
        }
    }

    private fun startListening() {
        dispatch(VoiceEvent.MicTapped)
        if (voice.stage != Stage.Listening) return
        prewarmNetwork() // the secure connections are ready by the time the question is asked
        val lang = g("sttLang")
        val listener = object : SttListener {
            override fun onLevel(level: Float) {
                this@CopilotVM.level = level
            }

            override fun onPartial(text: String) = dispatch(VoiceEvent.Partial(Vocab.apply(text, g("vocab"))))

            override fun onSegment(text: String) = dispatch(VoiceEvent.SegmentFinal(Vocab.apply(text, g("vocab"))))

            override fun onFinal(text: String) {
                level = 0f
                stt = null
                dispatch(VoiceEvent.SpeechFinal(Vocab.apply(text, g("vocab"))))
                if (pendingGenerate) {
                    pendingGenerate = false
                    generateTapped()
                }
            }

            override fun onError(message: String) {
                level = 0f
                stt = null
                pendingGenerate = false
                dispatch(VoiceEvent.SpeechFailed(message))
            }
        }
        val whisper = selectedSpeech?.takeIf { modelStore.isDownloaded(it) }
        val wantWhisper = when (g("sttEngine")) {
            "Platform" -> false
            "Whisper" -> true
            else -> whisper != null
        }
        if (wantWhisper && whisper == null) {
            dispatch(VoiceEvent.SpeechFailed("Download a Whisper model in Settings > Speech first, or switch to the phone's recognizer."))
            return
        }
        if (wantWhisper && whisper != null) {
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { ensureWhisper(whisper) }
                if (!ok) {
                    dispatch(VoiceEvent.SpeechFailed("The Whisper model could not be loaded."))
                    return@launch
                }
                if (voice.stage != Stage.Listening) return@launch
                val s = WhisperRecognizer(lang, g("vocab"), listener)
                stt = s
                s.start()
            }
        } else {
            val s = PlatformRecognizer(ctx, lang, listener)
            stt = s
            s.start()
        }
    }

    fun micDenied() = dispatch(VoiceEvent.SpeechFailed("Microphone permission is needed to hear you."))

    fun editText(text: String) = dispatch(VoiceEvent.TextEdited(text))

    /** The Clear button: empties the text box, also while the microphone is open. */
    fun clearTapped() {
        dispatch(VoiceEvent.ClearTapped)
        stt?.discard()
    }

    // ---------- voice: step 4 to 6, generate ----------
    private fun activeJob(): String = jobs.firstOrNull { it.active }?.let { it.title + "\n" + it.description } ?: ""

    /** The ONLY way an answer is started: the user taps Generate Answer. */
    fun generateTapped(extra: String = "") {
        if (voice.stage == Stage.Listening) {
            // Finish the capture first so the last words are included, then answer.
            val session = stt
            if (voice.canTapGenerate && session != null && !pendingGenerate) {
                pendingGenerate = true
                session.stop()
            }
            return
        }
        if (!voice.canGenerate) return
        dispatch(VoiceEvent.GenerateTapped)
        val question = voice.question
        val startedAt = System.currentTimeMillis()
        val currentMode = mode
        thinking = false
        firstTokenMs = 0
        genJob?.cancel()
        genJob = viewModelScope.launch {
            val filter = ThinkFilter()
            try {
                val memory = if (b("memory")) session.map { it.question to it.answer } else emptyList()
                val (sys, user, max) = Prompts.build(
                    currentMode,
                    g("style"),
                    g("speed"),
                    aboutCandidate(),
                    activeJob(),
                    g("vocab"),
                    memory,
                    question,
                    extra,
                )
                var gotFirst = false
                runProviders(sys, user, max, onRestart = { filter.reset() }) { tok ->
                    val shown = filter.push(tok)
                    thinking = filter.thinking
                    if (shown.isNotEmpty() && !gotFirst) {
                        gotFirst = true
                        firstTokenMs = System.currentTimeMillis() - startedAt
                    }
                    dispatch(VoiceEvent.Token(shown))
                }
                val clean = filter.visible.trim()
                if (clean.isBlank()) throw IOException("The model returned an empty answer. Try again.")
                val qa = QA(UUID.randomUUID().toString(), sessionId, question, clean, System.currentTimeMillis(), currentMode)
                session.add(qa)
                history.add(qa)
                store.saveHistory(history.toList())
                thinking = false
                dispatch(VoiceEvent.GenerationFinished)
                dispatch(VoiceEvent.ResetDone) // mic and text box are ready for the next question
            } catch (e: CancellationException) {
                thinking = false
                throw e
            } catch (e: Exception) {
                thinking = false
                dispatch(VoiceEvent.GenerationFailed(e.message ?: "Could not get an answer."))
            }
        }
    }

    fun stopGeneration() {
        genJob?.cancel()
        engine.cancel()
        thinking = false
        dispatch(VoiceEvent.StopTapped)
    }

    /** Quick actions on the last answer ("Explain more" ...). Still requires a tap, never automatic. */
    fun refine(instruction: String) {
        val q = voice.question
        if (q.isBlank() || voice.stage != Stage.Idle) return
        val prev = voice.answer
        val extra = if (prev.isNotBlank()) "$instruction\n\nPrevious answer for reference:\n${prev.take(700)}" else instruction
        dispatch(VoiceEvent.TextEdited(q))
        generateTapped(extra)
    }

    fun newSession() {
        pendingGenerate = false
        stt?.cancel()
        stt = null
        genJob?.cancel()
        engine.cancel()
        voice = VoiceState()
        session.clear()
        sessionId = UUID.randomUUID().toString()
    }

    fun dismissError() {
        voice = voice.copy(error = null)
    }

    // ---------- providers ----------

    /** Résumé plus the marks read from the marksheets, so answers can mention them correctly. */
    private fun aboutCandidate(): String {
        val edu = EduLevel.values().mapNotNull { l -> marks[l.key]?.let { MarksMath.summary(it) }?.takeIf { it.isNotBlank() } }
        val head = if (edu.isEmpty()) "" else "EDUCATION: " + edu.joinToString("; ")
        return listOf(head, g("resume")).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Cloud engines that have an API key, with the models to try for each. */
    private fun cloudModels(): Map<Engine, List<String>> = buildMap {
        if (g("groqKey").isNotBlank()) put(Engine.Groq, Models.parse(g("groqModel")))
        if (g("geminiKey").isNotBlank()) put(Engine.Google, Models.parse(g("geminiModel")))
        if (g("orKey").isNotBlank()) put(Engine.OpenRouter, Models.parse(g("orModel")))
    }

    /** One line under each engine in the drop-down. */
    fun engineHint(e: Engine): String = when (e) {
        Engine.Auto -> "Switches on failure, ends on Local"
        Engine.Local -> if (localReady()) "On this phone" else "No model downloaded"
        Engine.Groq -> if (g("groqKey").isNotBlank()) "Ready" else "No API key"
        Engine.Google -> if (g("geminiKey").isNotBlank()) "Ready" else "No API key"
        Engine.OpenRouter -> if (g("orKey").isNotBlank()) "Ready" else "No API key"
    }

    private fun localReady(): Boolean = selectedModel?.let { modelStore.isDownloaded(it) } == true

    private fun providerFor(c: Candidate): LlmProvider? = when (c.engine) {
        Engine.Groq -> GroqProvider(g("groqKey").trim(), c.model)
        Engine.Google -> GeminiProvider(g("geminiKey").trim(), c.model)
        Engine.OpenRouter -> OpenRouterProvider(g("orKey").trim(), c.model)
        Engine.Local -> selectedModel?.takeIf { modelStore.isDownloaded(it) }?.let { LocalProvider(engine, it, modelStore.file(it)) }
        Engine.Auto -> null
    }

    private fun noEngineMessage(): String {
        val chosen = Engine.from(g("provider"))
        return when (chosen) {
            Engine.Auto -> "No answer engine is ready. Add an API key in Settings > AI engines, or download a model in Settings > Local Models."
            Engine.Local -> "No on-device model is downloaded. Open Settings > Local Models, or pick another engine."
            else -> "${chosen.label} has no API key. Add it in Settings > AI engines, or switch the engine to Auto."
        }
    }

    /** Tries the engines in the order [RoutePlanner] gives, moving on at once when one fails or hits its limit. */
    private suspend fun runProviders(sys: String, user: String, max: Int, onRestart: () -> Unit = {}, onToken: (String) -> Unit) {
        val local = localReady()
        val plan = RoutePlanner.plan(Engine.from(g("provider")), b("fallback"), cloudModels(), local, health)
        if (plan.isEmpty()) throw IOException(noEngineMessage())
        val started = SystemClock.elapsedRealtime()
        val queue = ArrayDeque(plan)
        val skipped = ArrayList<Candidate>()
        var budgetOver = false
        var last: Exception? = null
        while (queue.isNotEmpty() || skipped.isNotEmpty()) {
            if (queue.isEmpty()) {
                budgetOver = true
                queue.addAll(skipped)
                skipped.clear()
            }
            val c = queue.removeFirst()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (!budgetOver && RoutePlanner.skipCloud(c, elapsed, local)) {
                skipped.add(c) // too slow already: go to the on-device model first, try these last
                continue
            }
            val p = providerFor(c) ?: continue
            try {
                engineUsed = c.label
                onRestart()
                attempt(p, RoutePlanner.timeoutFor(c, elapsed), sys, user, max, onToken)
                health.ok(c.key)
                return
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) throw e
                val f = e as? ProviderFailure ?: ProviderFailure(FailureKind.Other, 0, e.message ?: "Could not get an answer.")
                health.fail(c.key, f)
                last = f
            }
        }
        throw last ?: IOException(noEngineMessage())
    }

    private suspend fun attempt(p: LlmProvider, timeoutMs: Long, sys: String, user: String, max: Int, onToken: (String) -> Unit) {
        var got = false
        var timedOut = false
        coroutineScope {
            val job = launch {
                p.stream(sys, user, max).collect {
                    got = true
                    onToken(it)
                }
            }
            val watchdog = launch {
                delay(timeoutMs)
                if (!got) {
                    timedOut = true
                    job.cancel(CancellationException("slow"))
                }
            }
            job.join()
            watchdog.cancel()
        }
        if (timedOut && !got) throw ProviderFailure(FailureKind.Timeout, 0, "${p.name} did not answer in time.")
        if (!got) throw ProviderFailure(FailureKind.Other, 0, "${p.name} returned an empty answer.")
    }

    /** The "Test" button next to each API key. */
    fun testEngine(e: Engine) {
        val model = cloudModels()[e]?.firstOrNull()
        val c = if (model != null) Candidate(e, model) else null
        val p = c?.let { providerFor(it) }
        if (c == null || p == null) {
            engineTests = engineTests + (e.name to "Add an API key first.")
            return
        }
        engineTests = engineTests + (e.name to "Testing…")
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = try {
                withTimeout(8_000L) { p.stream("Reply with the single word OK.", "Say OK.", 8).first { it.isNotBlank() } }
                health.ok(c.key)
                "Working · ${SystemClock.elapsedRealtime() - t0} ms to first word (${c.model})"
            } catch (ex: TimeoutCancellationException) {
                "No answer within 8 seconds (${c.model})"
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                ex.message ?: "Failed"
            }
            engineTests = engineTests + (e.name to result)
        }
    }

    private fun runPlain(sys: String, user: String, max: Int, onText: (String) -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                var acc = ""
                val filter = ThinkFilter()
                runProviders(sys, user, max) { tok ->
                    acc += tok
                    onText(filter.apply { reset() }.push(acc))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
                onText("Error: ${e.message}")
            } finally {
                onDone()
            }
        }
    }

    // ---------- practice ----------
    fun scorePractice(question: String, answer: String) {
        if (answer.isBlank() || practiceBusy) return
        practiceBusy = true
        practiceOut = ""
        runPlain(
            Prompts.SCORE,
            "Interview question: $question\n\nCandidate answer: $answer",
            600,
            onText = { practiceOut = it },
            onDone = {
                practiceBusy = false
                val m = Regex("Score:\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*10").find(practiceOut)
                if (m != null) {
                    val old = g("pscores")
                    put("pscores", if (old.isBlank()) m.groupValues[1] else "$old,${m.groupValues[1]}")
                }
            },
        )
    }

    fun practiceScores(): List<Double> = g("pscores").split(",").mapNotNull { it.trim().toDoubleOrNull() }

    fun speak(text: String) {
        val existing = tts
        if (existing != null) {
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q")
        } else {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q")
                }
            }
        }
    }

    // ---------- jobs ----------
    fun addJob(title: String, description: String) {
        if (title.isBlank() || description.isBlank()) return
        jobs.add(0, JobProfile(UUID.randomUUID().toString(), title.trim(), description.trim()))
        store.saveJobs(jobs.toList())
    }

    fun setActiveJob(id: String) {
        val updated = jobs.map { it.copy(active = it.id == id && !it.active) }
        jobs.clear()
        jobs.addAll(updated)
        store.saveJobs(jobs.toList())
    }

    fun deleteJob(id: String) {
        jobs.removeAll { it.id == id }
        store.saveJobs(jobs.toList())
    }

    fun analyzeJob(id: String) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (jobBusyId.isNotEmpty()) return
        jobBusyId = id
        runPlain(
            Prompts.JOB_ANALYSIS,
            "Job title: ${job.title}\n\n${job.description.take(2500)}",
            600,
            onText = { text ->
                val idx = jobs.indexOfFirst { it.id == id }
                if (idx >= 0) jobs[idx] = jobs[idx].copy(keywords = text)
            },
            onDone = {
                jobBusyId = ""
                store.saveJobs(jobs.toList())
            },
        )
    }

    // ---------- education: marksheets ----------
    fun importMarksheet(level: EduLevel, uri: Uri) {
        if (marksBusy.isNotEmpty()) return
        marksBusy = level.key
        marksMessage = null
        viewModelScope.launch {
            try {
                val sheet = MarksheetReader.read(ctx, uri, level, g("geminiKey").trim(), Models.parse(g("geminiModel")))
                val keep = marks[level.key]
                marks[level.key] = if (keep != null) sheet.copy(cgpaFormula = keep.cgpaFormula) else sheet
                store.saveMarks(marks.toMap())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                marksMessage = e.message ?: "Could not read that file."
            } finally {
                marksBusy = ""
            }
        }
    }

    private fun editSheet(level: EduLevel, change: (Marksheet) -> Marksheet) {
        val old = marks[level.key] ?: Marksheet(level)
        val edited = change(old)
        marks[level.key] = edited.copy(warnings = MarksParser.check(edited))
        store.saveMarks(marks.toMap())
    }

    fun setSubject(level: EduLevel, index: Int, subject: SubjectMark) = editSheet(level) { m ->
        if (index in m.subjects.indices) m.copy(subjects = m.subjects.toMutableList().also { it[index] = subject }) else m
    }

    fun addSubject(level: EduLevel) = editSheet(level) { it.copy(subjects = it.subjects + SubjectMark("Subject ${it.subjects.size + 1}", 0.0, 100.0)) }

    fun removeSubject(level: EduLevel, index: Int) = editSheet(level) { m ->
        if (index in m.subjects.indices) m.copy(subjects = m.subjects.filterIndexed { i, _ -> i != index }) else m
    }

    fun setCgpa(level: EduLevel, cgpa: Double?) = editSheet(level) { it.copy(cgpa = cgpa) }

    fun setCgpaFormula(level: EduLevel, formula: String) = editSheet(level) { it.copy(cgpaFormula = formula) }

    fun removeMarksheet(level: EduLevel) {
        marks.remove(level.key)
        store.saveMarks(marks.toMap())
    }

    // ---------- history ----------
    fun toggleFavorite(id: String) {
        val idx = history.indexOfFirst { it.id == id }
        if (idx >= 0) {
            history[idx] = history[idx].copy(favorite = !history[idx].favorite)
            store.saveHistory(history.toList())
        }
    }

    fun deleteQA(id: String) {
        history.removeAll { it.id == id }
        store.saveHistory(history.toList())
    }

    fun wipeAll() {
        newSession()
        engine.unload()
        NativeWhisperSafe.free()
        loadedWhisper = null
        ModelCatalog.models.forEach { downloader.cancelAndDelete(modelStore.spec(it)) }
        ModelCatalog.speech.forEach { downloader.cancelAndDelete(modelStore.spec(it)) }
        modelStore.wipe()
        store.wipe()
        prefs.clear()
        marks.clear()
        health.reset()
        history.clear()
        jobs.clear()
        downloadedTick++
    }

    override fun onCleared() {
        stt?.cancel()
        tts?.shutdown()
        super.onCleared()
    }
}

/** Frees Whisper without crashing on devices where the native library failed to load. */
private object NativeWhisperSafe {
    fun free() {
        try {
            NativeWhisper.nativeFree()
        } catch (e: LinkageError) {
            // nothing was loaded
        }
    }
}
