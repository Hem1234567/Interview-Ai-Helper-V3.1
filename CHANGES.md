# Version 2.1

* Continuous speech: pauses no longer end listening; sentences accumulate; Whisper uses earlier text as context; hallucination filter (`core/Text.kt`).
* Generate Answer works while listening (finishes capture first). New Clear button.
* Engine drop-down with Auto fallback (`core/Engine.kt`), Groq added, multiple models per engine, cooldown after limits, 4 s budget, connection pre-warm.
* Fixed: retired default models (gemini-2.0-flash) are migrated; Google key now sent in a header.
* Marksheet reader for 10th, 12th and college (`edu/MarksheetReader.kt`, `core/Marks.kt`) with editable marks and CGPA conversion.
* New unit tests: voice flow, transcript cleaner, engine routing, marksheet parsing.

# Summary of changes

## Voice and speech
* New voice state machine (`core/VoiceFlow.kt`): speaking never generates; only **Generate Answer** does. Stop button, disabled rules, auto-reset after an answer.
* Removed auto-generate-on-silence and the question detector.
* On-device Whisper (whisper.cpp JNI) with VAD, 16 kHz mono capture, NS/AEC/AGC, live partials, beam-search final pass, language picker (auto default), editable text box.
* Phone recognizer kept as fallback. Old `Speech.kt` removed.

## Local models
* Replaced the MediaPipe `.task` importer with llama.cpp (GGUF) via JNI. MediaPipe dependency removed.
* Catalog of exactly 10 models, device detection, recommendations, "Too large" marking, download/pause/resume/delete/select, SHA-256 verification.
* Low-end protection: switches to a smaller downloaded model if the selected one is too large.

## Performance
* Warm start at launch, KV prefix reuse, 2048 context, streamed tokens, single dedicated inference thread, per-phone CPU variant selection, reasoning-block hiding.

## UI
* Material 3 and material-icons removed. New HIG-style component set (large titles, grouped lists, switches, segmented control, sheets, alerts, frosted tab bar, spring animations, haptics, custom SF-style icons).
* One palette in `ui/Theme.kt`; unit test and CI script prevent colours elsewhere. Light and dark.
* Home, Practice, Jobs, History, Settings, Local Models, Whisper models, download progress and dialogs all restyled.

## Build and CI
* New Gradle config (NDK/CMake pinned, native build, lint strict, unit-test deps). New workflow with lint, test and build jobs; actions pinned by SHA; sources cached.
* Added `.editorconfig` (ktlint), scripts, 36 unit tests.
