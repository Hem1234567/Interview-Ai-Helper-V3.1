package com.interviewcopilot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.interviewcopilot.app.core.Stage
import com.interviewcopilot.app.llm.EngineState
import com.interviewcopilot.app.logic.Prompts
import com.interviewcopilot.app.vm.CopilotVM
import com.interviewcopilot.app.voice.SttLanguages

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: CopilotVM, onOpenSettings: () -> Unit, onOpenModels: () -> Unit) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    val strongHaptic = rememberStrongHaptic()
    val v = vm.voice
    var langOpen by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.micTapped() else vm.micDenied()
    }

    fun onMic() {
        strongHaptic()
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.micTapped() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LargeTitleScreen(
        title = "Assistant",
        leading = { EngineMenu(vm) },
        actions = { CircleButton(Sym.gear, "Settings", size = 36.dp, onClick = onOpenSettings) },
    ) {
        // Mode picker
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prompts.modes.forEach { m ->
                val on = m == vm.mode
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) c.primary else c.surface)
                        .pressable { vm.updateMode(m) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Txt(m, Type.subhead, if (on) c.onPastel else c.text, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (!vm.hasLocalModel && vm.g("orKey").isBlank() && vm.g("geminiKey").isBlank() && vm.g("groqKey").isBlank()) {
            GetModelCard(vm, onOpenModels)
            Spacer(Modifier.height(14.dp))
        }

        AnimatedVisibility(v.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.medium)).background(c.warning)
                    .pressable { vm.dismissError() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sf(Sym.warning, c.onPastel, 20.dp)
                Spacer(Modifier.width(10.dp))
                Txt(v.error.orEmpty(), Type.subhead, c.onPastel, Modifier.weight(1f))
            }
        }

        // Step 1 and 2: speak, then review and edit the text
        Card(Modifier.animateContentSize()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        when (v.stage) {
                            Stage.Listening -> "Listening… pauses are fine, tap the mic to finish"
                            Stage.Transcribed -> "Review, edit, then generate"
                            Stage.Generating -> "Answering"
                            else -> "Your question"
                        },
                        Type.footnote,
                        c.text2,
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val listening = v.stage == Stage.Listening
                IosField(
                    value = if (listening) v.shown else v.text,
                    onChange = { vm.editText(it) },
                    hint = if (listening) "Speak now…" else "Tap the mic and speak, or type here",
                    singleLine = false,
                    minLines = 3,
                    enabled = v.stage == Stage.Idle || v.stage == Stage.Transcribed,
                    fillColor = c.background,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LanguageChip(vm) { langOpen = !langOpen }
                    Spacer(Modifier.weight(1f))
                    MicButton(listening, vm.level, v.micBusy) { onMic() }
                    Spacer(Modifier.weight(1f))
                    Txt(sttLabel(vm), Type.caption, c.text2, Modifier.width(88.dp), maxLines = 2, align = TextAlign.End)
                }
            }
        }
        LanguageList(langOpen, vm) { langOpen = false }
        Spacer(Modifier.height(14.dp))

        // Step 4: only the button generates an answer
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IosButton(
                "Generate Answer",
                Modifier.weight(1f),
                kind = ButtonKind.Success,
                enabled = v.canTapGenerate,
                icon = Sym.sparkles,
            ) { strongHaptic(); vm.generateTapped() }
            // Stop takes the place of Clear while an answer is being written (Clear is not usable then anyway).
            if (v.canStop) {
                IosButton("Stop", Modifier.width(104.dp), kind = ButtonKind.Danger, icon = Sym.stop) {
                    strongHaptic()
                    vm.stopGeneration()
                }
            } else {
                IosButton(
                    "Clear",
                    Modifier.width(104.dp),
                    kind = ButtonKind.Secondary,
                    enabled = v.canClear,
                    icon = Sym.trash,
                ) { strongHaptic(); vm.clearTapped() }
            }
        }
        Spacer(Modifier.height(16.dp))

        AnswerCard(vm)

        AnimatedVisibility(v.answer.isNotBlank() && v.stage == Stage.Idle) {
            Column {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction("New answer") { vm.refine("Give a different answer with a fresh angle and different wording.") }
                    QuickAction("Explain more") { vm.refine("Expand the previous answer with more depth, still in spoken style.") }
                    QuickAction("Give an example") { vm.refine("Add one concrete example.") }
                    QuickAction("Shorter") { vm.refine("Say the same thing in at most two sentences.") }
                }
            }
        }
        if (vm.session.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Txt("New session", Type.subhead, c.text, Modifier.pressable { vm.newSession() }.padding(6.dp))
        }
    }
}

private fun sttLabel(vm: CopilotVM): String {
    val whisper = vm.selectedSpeech?.takeIf { vm.isDownloaded(it) }
    return when (vm.g("sttEngine")) {
        "Platform" -> "Phone recognizer"
        "Whisper" -> whisper?.name ?: "Whisper (not downloaded)"
        else -> whisper?.name ?: "Phone recognizer"
    }
}

@Composable
private fun QuickAction(text: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(Modifier.clip(RoundedCornerShape(50)).background(c.secondary).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Txt(text, Type.subhead, c.onPastel)
    }
}

@Composable
private fun LanguageChip(vm: CopilotVM, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.background).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sf(Sym.globe, c.text, 16.dp)
        Spacer(Modifier.width(6.dp))
        Txt(SttLanguages.label(vm.g("sttLang")), Type.subhead, c.text, maxLines = 1)
    }
}

@Composable
private fun LanguageList(visible: Boolean, vm: CopilotVM, onPicked: () -> Unit) {
    val c = LocalAppColors.current
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(Modifier.padding(top = 12.dp)) {
            Card {
                SttLanguages.all.forEachIndexed { i, l ->
                    ListRow(
                        l.label,
                        divider = i != SttLanguages.all.lastIndex,
                        onClick = {
                            vm.put("sttLang", l.code)
                            onPicked()
                        },
                        trailing = { if (l.code == vm.g("sttLang")) Sf(Sym.check, c.text, 18.dp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, level: Float, disabled: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val wob by pulse.animateFloat(0.9f, 1.1f, InfiniteRepeatableSpec(tween(700), RepeatMode.Reverse), label = "wob")
    val ring by animateFloatAsState(
        if (listening) 1.15f + level * 0.6f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "ring",
    )
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier.size(76.dp)
                    .graphicsLayer { scaleX = ring * wob; scaleY = ring * wob; alpha = 0.55f }
                    .clip(CircleShape).background(c.accent),
            )
        }
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(if (listening) c.accent else c.primary)
                .pressable(enabled = !disabled, haptic = false, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Sf(if (listening) Sym.stop else Sym.mic, c.onPastel, 34.dp, description = if (listening) "Stop listening" else "Start listening") }
    }
}

@Composable
private fun GetModelCard(vm: CopilotVM, onOpen: () -> Unit) {
    val c = LocalAppColors.current
    val rec = vm.recommendedModel
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                    Sf(Sym.cpu, c.onPastel, 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Txt("Get on-device AI", Type.headline)
                    Txt("Private, offline answers", Type.footnote, c.text2)
                }
            }
            Spacer(Modifier.height(10.dp))
            Txt(
                if (rec != null) "Best fit for this phone: ${rec.name} (${"%.1f".format(rec.sizeGb)} GB)." else "Pick a model that fits this phone.",
                Type.subhead,
                c.text,
            )
            Spacer(Modifier.height(12.dp))
            IosButton("Choose a model", Modifier.fillMaxWidth(), kind = ButtonKind.Primary, onClick = onOpen)
        }
    }
}

@Composable
private fun AnswerCard(vm: CopilotVM) {
    val c = LocalAppColors.current
    val v = vm.voice
    val pulse = rememberInfiniteTransition(label = "dots")
    val a by pulse.animateFloat(0.3f, 1f, InfiniteRepeatableSpec(tween(650), RepeatMode.Reverse), label = "a")
    val clipboard = LocalClipboardManager.current
    Card(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Answer", Type.footnote, c.text2, Modifier.weight(1f))
                if (v.answer.isNotBlank() && v.stage != Stage.Generating) {
                    Sf(Sym.copy, c.text, 20.dp, Modifier.pressable { clipboard.setText(AnnotatedString(v.answer)) }, "Copy answer")
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                v.stage == Stage.Generating && v.answer.isBlank() -> {
                    val load = vm.engineState
                    val msg = when {
                        vm.thinking -> "Thinking…"
                        load is EngineState.Loading -> "Loading model…"
                        else -> "Preparing answer…"
                    }
                    Txt(msg, Type.title3, c.text2, Modifier.graphicsLayer { alpha = a })
                }

                v.answer.isBlank() -> {
                    Txt("Your answer will appear here after you tap Generate Answer.", Type.body, c.text2)
                }

                else -> {
                    if (v.question.isNotBlank()) {
                        Txt(v.question, Type.subhead, c.text2, maxLines = 3)
                        Spacer(Modifier.height(10.dp))
                    }
                    AnswerContent(v.answer, v.stage == Stage.Generating)
                    if (vm.firstTokenMs > 0 && vm.engineUsed.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Txt("${vm.engineUsed} · first words in ${"%.1f".format(vm.firstTokenMs / 1000.0)} s", Type.caption, c.text2)
                    }
                }
            }
        }
    }
}

@Composable
fun AnswerContent(text: String, live: Boolean) {
    val c = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val parts = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEachIndexed { i, p ->
            if (i % 2 == 1) {
                val lines = p.lines()
                val lang = lines.firstOrNull()?.trim().orEmpty()
                val code = (if (lines.size > 1) lines.drop(1).joinToString("\n") else p).trim('\n')
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(c.background).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(if (lines.size > 1) lang else "code", Type.caption, c.text2, Modifier.weight(1f))
                        Sf(Sym.copy, c.text2, 18.dp, Modifier.pressable { clipboard.setText(AnnotatedString(code)) }, "Copy code")
                    }
                    Txt(code, Type.footnote.copy(fontFamily = FontFamily.Monospace), c.text, Modifier.horizontalScroll(rememberScrollState()))
                }
            } else if (p.isNotBlank()) {
                val cursor = live && i == parts.lastIndex
                Txt(styled(p.trim('\n'), cursor, c.primary), Type.body.copy(lineHeight = 26.sp), c.text)
            }
        }
    }
}

private fun styled(raw: String, cursor: Boolean, cursorColor: Color): AnnotatedString {
    val text = raw.replace(Regex("(?m)^\\s*[-*] "), "• ")
    return buildAnnotatedString {
        text.split("**").forEachIndexed { i, part ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        }
        if (cursor) withStyle(SpanStyle(color = cursorColor)) { append(" ▍") }
    }
}
