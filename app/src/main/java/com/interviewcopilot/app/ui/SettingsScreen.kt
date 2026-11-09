package com.interviewcopilot.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.Engine
import com.interviewcopilot.app.core.MarksMath
import com.interviewcopilot.app.core.Marksheet
import com.interviewcopilot.app.core.SubjectMark
import com.interviewcopilot.app.llm.ModelCatalog
import com.interviewcopilot.app.vm.CopilotVM
import com.interviewcopilot.app.voice.SttLanguages

enum class SettingsPage { Root, LocalModels, WhisperModels, Language, Style, Online, Personal }

private val LENGTHS = listOf("Short" to "Fast", "Balanced" to "Standard", "Detailed" to "Deep")
private val STYLES = listOf("Concise", "Confident", "STAR", "Simple English")

@Composable
fun SettingsHost(vm: CopilotVM, initial: SettingsPage, onClose: () -> Unit) {
    var page by remember { mutableStateOf(initial) }
    BackHandler { if (page == SettingsPage.Root) onClose() else page = SettingsPage.Root }
    val back = { page = SettingsPage.Root }
    when (page) {
        SettingsPage.Root -> SettingsRoot(vm, onClose) { page = it }
        SettingsPage.LocalModels -> LocalModelsScreen(vm, back)
        SettingsPage.WhisperModels -> SpeechModelsScreen(vm, back)
        SettingsPage.Language -> LanguagePage(vm, back)
        SettingsPage.Style -> StylePage(vm, back)
        SettingsPage.Online -> OnlinePage(vm, back)
        SettingsPage.Personal -> PersonalPage(vm, back)
    }
}

@Composable
private fun SettingsRoot(vm: CopilotVM, onClose: () -> Unit, go: (SettingsPage) -> Unit) {
    val c = LocalAppColors.current
    var confirmWipe by remember { mutableStateOf(false) }
    val selected = vm.selectedModel?.takeIf { vm.isDownloaded(it) }
    val whisper = vm.selectedSpeech?.takeIf { vm.isDownloaded(it) }
    val lengthLabel = LENGTHS.firstOrNull { it.second == vm.g("speed") }?.first ?: "Short"

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen("Settings", onBack = onClose, backLabel = "Done") {
            SectionHeader("Answers")
            Card {
                ListRow(
                    "Local Models",
                    icon = Sym.cpu,
                    iconBg = c.primary,
                    chevron = true,
                    onClick = { go(SettingsPage.LocalModels) },
                    subtitle = selected?.name ?: "No model downloaded",
                )
                ListRow(
                    "AI engines",
                    icon = Sym.globe,
                    iconBg = c.secondary,
                    chevron = true,
                    onClick = { go(SettingsPage.Online) },
                    subtitle = "Groq, Google, OpenRouter, API keys",
                )
                ListRow(
                    "Answer style",
                    icon = Sym.sparkles,
                    iconBg = c.accent,
                    chevron = true,
                    onClick = { go(SettingsPage.Style) },
                    subtitle = vm.g("style"),
                )
                ListRow(
                    "Résumé and vocabulary",
                    icon = Sym.person,
                    iconBg = c.success,
                    chevron = true,
                    divider = false,
                    onClick = { go(SettingsPage.Personal) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Card {
                Box(Modifier.padding(16.dp)) {
                    Segmented(LENGTHS.map { it.first }, lengthLabel, { l -> vm.put("speed", LENGTHS.first { it.first == l }.second) })
                }
                ListRow("Answer engine", subtitle = "Also in the drop-down at the top left of the Assistant")
                Engine.values().forEachIndexed { i, e ->
                    ListRow(
                        e.label,
                        subtitle = vm.engineHint(e),
                        divider = i != Engine.values().lastIndex,
                        onClick = { vm.put("provider", e.name) },
                        trailing = { if (Engine.from(vm.g("provider")) == e) Sf(Sym.check, c.text, 20.dp) },
                    )
                }
            }
            SectionFooter("Auto tries Groq, Google and OpenRouter (whichever have keys), skips one that fails or hits its limit, and ends on the Local model.")
            SectionFooter("Short answers start fastest. Detailed answers take longer.")
            Spacer(Modifier.height(12.dp))
            Card {
                ListRow("Fallback for a chosen engine", subtitle = "If the engine you picked fails, continue with the others", trailing = {
                    IosSwitch(vm.b("fallback")) { vm.put("fallback", it.toString()) }
                })
                ListRow("Conversation memory", subtitle = "Off: every question is answered on its own", divider = false, trailing = {
                    IosSwitch(vm.b("memory")) { vm.put("memory", it.toString()) }
                })
            }

            SectionHeader("Speech")
            Card {
                Box(Modifier.padding(16.dp)) {
                    Segmented(listOf("Auto", "Whisper", "Phone"), when (vm.g("sttEngine")) { "Platform" -> "Phone"; else -> vm.g("sttEngine") }, {
                        vm.put("sttEngine", if (it == "Phone") "Platform" else it)
                    })
                }
                ListRow(
                    "Whisper models",
                    icon = Sym.waveform,
                    iconBg = c.primary,
                    chevron = true,
                    onClick = { go(SettingsPage.WhisperModels) },
                    subtitle = whisper?.name ?: "None downloaded",
                )
                ListRow(
                    "Language",
                    icon = Sym.globe,
                    iconBg = c.secondary,
                    chevron = true,
                    divider = false,
                    onClick = { go(SettingsPage.Language) },
                    subtitle = SttLanguages.label(vm.g("sttLang")),
                )
            }
            SectionFooter(
                "Auto uses Whisper when a model is downloaded and the phone's recognizer otherwise. The microphone uses noise suppression, echo cancellation and auto gain, and silence is trimmed before transcription.",
            )

            SectionHeader("Appearance")
            Card { Box(Modifier.padding(16.dp)) { Segmented(listOf("System", "Light", "Dark"), vm.g("theme"), { vm.put("theme", it) }) } }

            SectionHeader("Privacy")
            Card {
                ListRow(
                    "Delete all data",
                    subtitle = "Keys, history, jobs, résumé and downloaded models",
                    divider = false,
                    onClick = { confirmWipe = true },
                    trailing = { Pill("Delete", c.danger) },
                )
            }
            SectionFooter("API keys are stored encrypted on this device. On-device models never send your questions anywhere.")

            SectionHeader("About")
            Card {
                ListRow("Interview Copilot", subtitle = "Version 2.1 · ${ModelCatalog.models.size} on-device models", divider = false)
            }
            SectionFooter("Follow the rules of any interview or employer about AI assistance. Best used for practice and preparation.")
        }
        IosAlert(
            visible = confirmWipe,
            title = "Delete all data?",
            message = "This removes keys, history, jobs, your résumé and every downloaded model.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                vm.wipeAll()
                confirmWipe = false
            },
            onDismiss = { confirmWipe = false },
        )
    }
}

@Composable
private fun LanguagePage(vm: CopilotVM, onBack: () -> Unit) {
    val c = LocalAppColors.current
    LargeTitleScreen("Language", onBack = onBack, backLabel = "Settings") {
        Card {
            SttLanguages.all.forEachIndexed { i, l ->
                ListRow(l.label, divider = i != SttLanguages.all.lastIndex, onClick = { vm.put("sttLang", l.code) }, trailing = {
                    if (l.code == vm.g("sttLang")) Sf(Sym.check, c.text, 20.dp)
                })
            }
        }
        SectionFooter("Auto-detect lets Whisper recognise the language you speak. Pick one language for the best accuracy.")
    }
}

@Composable
private fun StylePage(vm: CopilotVM, onBack: () -> Unit) {
    val c = LocalAppColors.current
    LargeTitleScreen("Answer style", onBack = onBack, backLabel = "Settings") {
        Card {
            STYLES.forEachIndexed { i, s ->
                ListRow(s, divider = i != STYLES.lastIndex, onClick = { vm.put("style", s) }, trailing = {
                    if (s == vm.g("style")) Sf(Sym.check, c.text, 20.dp)
                })
            }
        }
    }
}

@Composable
private fun OnlinePage(vm: CopilotVM, onBack: () -> Unit) {
    LargeTitleScreen("AI engines", onBack = onBack, backLabel = "Settings") {
        EngineSection(vm, Engine.Groq, "Groq", "groqKey", "groqModel", "Groq API key (gsk_...)", "Fastest. Free key at console.groq.com")
        EngineSection(
            vm,
            Engine.Google,
            "Google Gemini",
            "geminiKey",
            "geminiModel",
            "Google AI Studio API key",
            "Free key at aistudio.google.com. Also reads marksheets.",
        )
        EngineSection(
            vm,
            Engine.OpenRouter,
            "OpenRouter",
            "orKey",
            "orModel",
            "OpenRouter API key (sk-or-...)",
            "Free key at openrouter.ai. openrouter/free picks a free model.",
        )
        SectionFooter(
            "Keys are stored encrypted. List several models separated by commas: they are tried in order, so a retired or rate-limited model never stops an answer. Questions are sent to a service only when it answers.",
        )
    }
}

@Composable
private fun EngineSection(vm: CopilotVM, engine: Engine, title: String, keyPref: String, modelPref: String, keyHint: String, note: String) {
    val c = LocalAppColors.current
    SectionHeader(title)
    IosField(vm.g(keyPref), { vm.put(keyPref, it.trim()) }, keyHint, secret = true)
    Spacer(Modifier.height(8.dp))
    IosField(vm.g(modelPref), { vm.put(modelPref, it) }, "Models, comma separated")
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IosButton("Test", Modifier.width(96.dp), kind = ButtonKind.Secondary) { vm.testEngine(engine) }
        Spacer(Modifier.width(12.dp))
        Txt(vm.engineTests[engine.name] ?: note, Type.footnote, c.text2, Modifier.weight(1f))
    }
}

@Composable
private fun PersonalPage(vm: CopilotVM, onBack: () -> Unit) {
    val c = LocalAppColors.current
    var pending by remember { mutableStateOf(EduLevel.Tenth) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMarksheet(pending, uri)
    }
    LargeTitleScreen("Résumé and education", onBack = onBack, backLabel = "Settings") {
        SectionHeader("Education")
        EduLevel.values().forEach { level ->
            MarksheetCard(vm, level, onUpload = {
                pending = level
                picker.launch(arrayOf("application/pdf", "image/*"))
            })
            Spacer(Modifier.height(12.dp))
        }
        vm.marksMessage?.let { Txt(it, Type.footnote, c.danger, Modifier.padding(horizontal = 16.dp)) }
        SectionFooter(
            "Upload a PDF or a clear photo. The marks are read on this phone (with your Google key, Gemini reads them too, which is more accurate). " +
                "Check the numbers and fix any that are wrong: the percentage updates at once and is used to personalise answers.",
        )
        SectionHeader("Résumé")
        IosField(vm.g("resume"), { vm.put("resume", it) }, "Paste your résumé text", singleLine = false, minLines = 7)
        SectionFooter("Used to personalise answers. Long text is shortened to keep answers fast.")
        SectionHeader("Custom vocabulary")
        IosField(vm.g("vocab"), { vm.put("vocab", it) }, "spring boot=Spring Boot", singleLine = false, minLines = 4)
        SectionFooter("One rule per line, wrong=correct. Corrects transcription and helps Whisper spell your terms.")
    }
}

@Composable
private fun MarksheetCard(vm: CopilotVM, level: EduLevel, onUpload: () -> Unit) {
    val c = LocalAppColors.current
    val sheet = vm.marks[level.key]
    val busy = vm.marksBusy == level.key
    val pct = sheet?.let { MarksMath.percent(it) }
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(level.label, Type.headline)
                    Txt(
                        when {
                            busy -> "Reading the marks…"
                            sheet == null -> "Not uploaded"
                            else -> sheet.fileName.ifBlank { "Marksheet" } + if (sheet.source.isNotBlank()) " · read by ${sheet.source}" else ""
                        },
                        Type.footnote,
                        c.text2,
                        maxLines = 1,
                    )
                }
                IosButton(if (sheet == null) "Upload" else "Replace", Modifier.width(110.dp), kind = ButtonKind.Primary, enabled = !busy, icon = Sym.arrowUp) {
                    onUpload()
                }
            }
            if (sheet != null) {
                Spacer(Modifier.height(12.dp))
                if (pct != null) {
                    Txt("${MarksMath.fmt(pct.value)}%", Type.title1)
                    Txt(pct.basis, Type.footnote, c.text2)
                    val best = if (level == EduLevel.Twelfth) MarksMath.bestOf(sheet.subjects, 5) else null
                    if (best != null) Txt("Best 5 subjects: ${MarksMath.fmt(best)}%", Type.footnote, c.text2)
                } else {
                    Txt("Percentage not available yet", Type.subhead, c.text2)
                }
                sheet.warnings.forEach { w ->
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(c.warning).padding(10.dp)) {
                        Txt(w, Type.footnote, c.onPastel)
                    }
                }
                Spacer(Modifier.height(12.dp))
                sheet.subjects.forEachIndexed { i, s -> SubjectRow(vm, level, i, s, sheet.subjects.size) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Txt("Add subject", Type.subhead, c.text, Modifier.pressable { vm.addSubject(level) }.padding(4.dp))
                    Txt("Remove", Type.subhead, c.text2, Modifier.pressable { vm.removeMarksheet(level) }.padding(4.dp))
                }
                if (level == EduLevel.College) CollegeExtras(vm, level, sheet)
            }
        }
    }
}

@Composable
private fun SubjectRow(vm: CopilotVM, level: EduLevel, index: Int, s: SubjectMark, count: Int) {
    val c = LocalAppColors.current
    var name by remember(level, index, count) { mutableStateOf(s.name) }
    var got by remember(level, index, count) { mutableStateOf(MarksMath.fmt(s.obtained)) }
    var max by remember(level, index, count) { mutableStateOf(MarksMath.fmt(s.max)) }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IosField(name, { name = it; vm.setSubject(level, index, s.copy(name = it)) }, "Subject", Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        IosField(got, { got = it; it.toDoubleOrNull()?.let { v -> vm.setSubject(level, index, s.copy(obtained = v)) } }, "Got", Modifier.width(68.dp))
        Txt("/", Type.body, c.text2, Modifier.padding(horizontal = 3.dp))
        IosField(max, {
            max = it; it.toDoubleOrNull()?.takeIf { v -> v > 0 }?.let { v -> vm.setSubject(level, index, s.copy(max = v)) }
        }, "Max", Modifier.width(68.dp))
        Sf(Sym.xmark, c.text2, 20.dp, Modifier.pressable { vm.removeSubject(level, index) }.padding(start = 6.dp), "Remove subject")
    }
}

@Composable
private fun CollegeExtras(vm: CopilotVM, level: EduLevel, sheet: Marksheet) {
    val c = LocalAppColors.current
    var cg by remember(level) { mutableStateOf(sheet.cgpa?.let { MarksMath.fmt(it) } ?: "") }
    Spacer(Modifier.height(12.dp))
    IosField(cg, { cg = it; vm.setCgpa(level, it.toDoubleOrNull()?.takeIf { v -> v in 0.0..10.0 }) }, "CGPA (out of 10)")
    Spacer(Modifier.height(8.dp))
    Segmented(listOf("CGPA x 9.5", "CGPA x 10", "VTU"), when (sheet.cgpaFormula) { "x10" -> "CGPA x 10"; "vtu" -> "VTU"; else -> "CGPA x 9.5" }, {
        vm.setCgpaFormula(level, when (it) { "CGPA x 10" -> "x10"; "VTU" -> "vtu"; else -> "x9.5" })
    })
    Txt("Choose how your university turns CGPA into a percentage.", Type.footnote, c.text2, Modifier.padding(top = 6.dp))
}
