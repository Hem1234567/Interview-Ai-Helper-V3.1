# Interview Copilot (Android)

A voice assistant that answers on your phone. You speak, check the text, tap **Generate Answer**, and a local
model streams the answer. Everything (speech and answers) can run fully offline.

Native Android app: Kotlin + Jetpack Compose, with llama.cpp (answers) and whisper.cpp (speech) via JNI.
The UI follows Apple's Human Interface Guidelines in a single pastel palette; there is no Material anywhere.

## How the voice flow works

`Idle -> Listening -> Transcribed -> Generating -> Answered -> Idle`

1. Tap the mic and speak. Live partial text appears in the box while you talk.
2. The final text stays in an editable box. Fix any word you like.
3. Nothing is generated. The app waits.
4. Tap **Generate Answer** (disabled when the box is empty or an answer is running). **Stop** cancels.
5. The answer streams token by token for that text only.
6. When it finishes, the mic and text box reset for the next question.

Each question is independent unless **Settings > Conversation memory** is on. The rules live in one pure,
unit-tested reducer: `core/VoiceFlow.kt`.

## What is new in 2.1

* **Continuous listening.** Pauses never end the capture. Every finished sentence is added to the text box and the mic stays open until you
  tap it again (or tap **Generate Answer**, which finishes the capture and then answers). Nothing is answered before you tap.
* **Clear** button next to Generate Answer empties the text box, also while listening.
* **Engine drop-down** (top left of the Assistant): Auto, Local, OpenRouter, Google, Groq. **Auto** tries Groq, Google and OpenRouter (those with keys),
  skips an engine that fails or hits its limit (it is not retried for a while), and ends on the on-device model.
* **Settings > AI engines**: API keys, several models per engine (tried in order) and a Test button that shows the time to first word.
* **Education** (Settings > Résumé and education): upload 10th, 12th and college marksheets (PDF or photo). Marks are read on the phone (ML Kit),
  or by Gemini when a Google key is set. Check or fix any mark; the percentage (and CGPA conversion) updates and is used in answers.

## Setup

Requirements: JDK 17, Android SDK 35, Android Studio Ladybug or newer (the NDK 27.2 and CMake 3.22.1 install
automatically), `git`.

```sh
git clone <your repo> && cd <your repo>
./scripts/fetch-native-deps.sh     # pinned llama.cpp b10108 and whisper.cpp v1.9.5 (CMake also does this itself)
./gradlew :app:assembleDebug       # first native build takes several minutes
```

Open in Android Studio and run on an arm64 phone (Android 10+). Models are **not** in the repo or the APK; they are
downloaded in the app: **Settings > Local Models** (and **Settings > Speech > Whisper models**).

## Local models (exactly 10)

Quantized GGUF, Q4_K_M unless noted, downloaded from Hugging Face. RAM needed is for a 2048-token context.

| # | Model | Family | Size | RAM | Notes |
|---|-------|--------|------|-----|-------|
| 1 | Qwen3.5 0.8B | Qwen | 0.53 GB | 0.9 GB | Tiny, instant |
| 2 | Llama 3.2 1B Instruct | Meta | 0.81 GB | 1.3 GB | Fast English |
| 3 | DeepSeek R1 Distill Qwen 1.5B | DeepSeek | 1.12 GB | 1.6 GB | Reasoning (thinks first) |
| 4 | Qwen3.5 2B | Qwen | 1.28 GB | 1.8 GB | Balanced |
| 5 | Llama 3.2 3B Instruct | Meta | 2.02 GB | 2.7 GB | Natural speech |
| 6 | Phi-4 mini Instruct | Microsoft | 2.49 GB | 3.3 GB | Logic and code |
| 7 | Qwen3.5 4B | Qwen | 2.74 GB | 3.5 GB | Best all-round on mid-range |
| 8 | Gemma 4 E2B Instruct | Google | 3.11 GB | 4.0 GB | Clear structure |
| 9 | Gemma 4 E4B Instruct | Google | 4.98 GB | 6.2 GB | Highest quality that fits 8 GB phones |
| 10 | gpt-oss 20B | OpenAI | 12.1 GB | 15 GB | MXFP4 (not Q4_K_M). Shown "Too large" below ~21 GB RAM |

Check each model's licence before redistributing (Llama 3.2 has its own community licence; the others are
Apache-2.0 or MIT). The app reads RAM, free storage, CPU features (NEON, dot-product, i8mm), Vulkan/NPU hints and the
OS version, then badges the best fits as **Recommended for your device** and marks the rest **Too large for this device**.
Sizes and speeds shown are estimates; the real byte size comes from the server when you download.

Downloads support Pause, Resume (HTTP Range), Delete and Select with a progress bar. After a download, the file's
SHA-256 is computed and compared with the SHA-256 Hugging Face publishes for that exact file; a mismatch deletes the file.
To pin a file to one exact hash, run `python3 scripts/pin-catalog.py` and paste the value into `ModelCatalog.kt`.

## Speech accuracy

* On-device **Whisper** (whisper.cpp): Tiny, Base (default), Small, Medium. Auto language detection by default, or pick a language.
* Microphone: 16 kHz mono PCM, `VOICE_RECOGNITION` source, noise suppression, echo cancellation and auto gain where the phone supports them.
* Voice activity detection trims silence and background noise; quiet speech is level-normalised.
* Live partial text uses fast greedy decoding; the final text uses a slower beam-search pass over the whole utterance.
* Your custom vocabulary biases Whisper's spelling and fixes known mis-hearings. Before a Whisper model is downloaded the phone's own recognizer is used.

## Speed

Models are loaded at launch (warm start) and stay in memory. The prompt is re-rendered each turn and only tokens that
differ from the cached prefix are evaluated, so the system prompt, résumé and job text are processed once. Context is
limited to 2048 tokens, output defaults to short, and inference and transcription run on background threads. Each
CPU feature level (dot-product, i8mm, ...) ships as a plug-in library and the best one for the phone is chosen at start-up.
The answer card shows the measured time to first word.

### Speed notes

Cloud engines get a short first-word timeout (Groq 2.5 s, Google 3 s, OpenRouter 3.5 s) and the whole cloud phase is limited to about 4 s before Auto
goes to the local model. Secure connections are opened when you tap the mic. Speed depends on your network and, for Local, on the phone.

## CI (GitHub Actions)

`Android CI` runs three jobs: **Lint** (colour/Material rules, ktlint, Android Lint), **Type-check and unit tests**
(Kotlin compile of main and test sources, then JUnit) and **Build APKs** (debug and release, then verifies the native libraries are inside).
Actions are pinned to commit SHAs, Gradle 8.9, JDK 17, NDK 27.2.12479018, CMake 3.22.1, ktlint 1.8.0 (SHA-256 checked).
`Verify model catalog` runs weekly and on demand.

## Layout

```
core/    pure logic: VoiceFlow, Vad, ThinkFilter, Checksum (no Android, unit-tested)
voice/   AudioCapture, WhisperRecognizer, PlatformRecognizer, JNI wrapper
llm/     ModelCatalog, Recommender, DeviceProbe, ModelDownloader, LlamaEngine, JNI wrapper
ui/      Theme (the only place colours are defined), Ios components, screens
cpp/     CMake, llama.cpp + whisper.cpp JNI (sources fetched at build time, pinned)
```
