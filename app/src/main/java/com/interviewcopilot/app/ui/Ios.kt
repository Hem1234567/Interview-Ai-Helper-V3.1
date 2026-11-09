package com.interviewcopilot.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------- text and icons ----------

@Composable
fun Txt(
    text: String,
    style: TextStyle = Type.body,
    color: Color = LocalAppColors.current.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

@Composable
fun Txt(
    text: AnnotatedString,
    style: TextStyle = Type.body,
    color: Color = LocalAppColors.current.text,
    modifier: Modifier = Modifier,
) {
    BasicText(text = text, modifier = modifier, style = style.copy(color = color))
}

@Composable
fun Sf(icon: ImageVector, tint: Color = LocalAppColors.current.text, size: Dp = 22.dp, modifier: Modifier = Modifier, description: String? = null) {
    Image(
        imageVector = icon,
        contentDescription = description,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

// ---------- press feedback ----------

/** iOS press feedback: spring scale plus a light haptic tick. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, haptic: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val feedback = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

@Composable
fun rememberStrongHaptic(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    return { feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
}

// ---------- surfaces ----------

/** Frosted-glass surface: translucent fill, hairline edge and a soft shadow. */
@Composable
fun Glass(modifier: Modifier = Modifier, radius: Dp = Radius.large, content: @Composable BoxScope.() -> Unit) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .shadow(12.dp, shape, ambientColor = c.text.copy(alpha = 0.05f), spotColor = c.text.copy(alpha = 0.12f))
            .clip(shape)
            .background(c.glass)
            .border(0.5.dp, c.hairline, shape),
        content = content,
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = Radius.medium, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = c.text.copy(alpha = 0.03f), spotColor = c.text.copy(alpha = 0.07f))
            .clip(shape)
            .background(c.surface),
        content = content,
    )
}

// ---------- buttons ----------

enum class ButtonKind { Primary, Success, Secondary, Accent, Plain, Danger }

@Composable
fun IosButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val fill = when (kind) {
        ButtonKind.Primary -> c.primary
        ButtonKind.Success -> c.success
        ButtonKind.Secondary -> c.secondary
        ButtonKind.Accent -> c.accent
        ButtonKind.Danger -> c.danger
        ButtonKind.Plain -> Color.Transparent
    }
    val bg by animateColorAsState(if (enabled) fill else c.text2.copy(alpha = 0.16f), label = "btnBg")
    val fg by animateColorAsState(
        if (!enabled) {
            c.text2
        } else if (kind == ButtonKind.Plain) {
            c.text
        } else {
            c.onPastel
        },
        label = "btnFg",
    )
    Row(
        modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(Radius.medium))
            .background(bg)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Sf(icon, fg, 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Txt(text, Type.headline, fg, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color = LocalAppColors.current.onPastel, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Txt(text, Type.caption, fg, maxLines = 1)
    }
}

@Composable
fun CircleButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    fill: Color = LocalAppColors.current.surface,
    tint: Color = LocalAppColors.current.text,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Sf(icon, tint, size * 0.5f, description = description) }
}

// ---------- controls ----------

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    val track by animateColorAsState(if (checked) c.success else c.text2.copy(alpha = 0.3f), label = "swTrack")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "swThumb")
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .pressable { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Pastel.Surface),
        )
    }
}

@Composable
fun Segmented(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    val idx = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.text2.copy(alpha = 0.16f)),
    ) {
        val seg = maxWidth / options.size
        val x by animateDpAsState(seg * idx, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium), label = "segThumb")
        Box(
            Modifier
                .offset(x = x + 2.dp, y = 2.dp)
                .width(seg - 4.dp)
                .fillMaxHeight()
                .padding(bottom = 4.dp)
                .shadow(2.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(c.primary),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().pressable(haptic = o != selected) { onSelect(o) },
                    contentAlignment = Alignment.Center,
                ) { Txt(o, Type.subhead, if (o == selected) c.onPastel else c.text, maxLines = 1) }
            }
        }
    }
}

@Composable
fun IosProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalAppColors.current.primary) {
    val c = LocalAppColors.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(250), label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(c.text2.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun IosField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    secret: Boolean = false,
    enabled: Boolean = true,
    fillColor: Color = LocalAppColors.current.surface,
) {
    val c = LocalAppColors.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.medium))
            .background(fillColor)
            .border(0.5.dp, c.hairline, RoundedCornerShape(Radius.medium))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            textStyle = Type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Txt(hint, Type.body, c.text2)
                    inner()
                }
            },
        )
    }
}

// ---------- grouped lists ----------

@Composable
fun SectionHeader(text: String) {
    Txt(
        text.uppercase(),
        Type.footnote,
        LocalAppColors.current.text2,
        Modifier.padding(start = 16.dp, top = 22.dp, bottom = 7.dp),
    )
}

@Composable
fun SectionFooter(text: String) {
    Txt(text, Type.footnote, LocalAppColors.current.text2, Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color? = null,
    chevron: Boolean = false,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalAppColors.current
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconBg ?: c.primary),
                    contentAlignment = Alignment.Center,
                ) { Sf(icon, c.onPastel, 18.dp) }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Txt(title, Type.body, c.text)
                if (subtitle != null) Txt(subtitle, Type.footnote, c.text2)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Sf(Sym.chevronRight, c.text2, 16.dp)
            }
        }
        if (divider) Box(Modifier.padding(start = if (icon != null) 58.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(c.hairline))
    }
}

// ---------- navigation ----------

/** iOS navigation page: back chevron, large title that collapses into a small centred title. */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalAppColors.current
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 56 } }
    val smallAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(180), label = "smallTitle")
    Column(modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (onBack != null) {
                Row(
                    Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sf(Sym.chevronLeft, c.text, 22.dp, description = "Back")
                    Txt(backLabel, Type.body, c.text)
                }
            }
            if (onBack == null && leading != null) {
                Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
            }
            Txt(title, Type.headline, c.text, Modifier.align(Alignment.Center).alpha(smallAlpha), maxLines = 1)
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).alpha(smallAlpha).background(c.hairline))
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Txt(title, Type.largeTitle, c.text, Modifier.padding(top = 4.dp, bottom = 10.dp))
            content()
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
fun TabBarItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val bg by animateColorAsState(if (selected) c.primary else Color.Transparent, spring(stiffness = Spring.StiffnessMedium), label = "tabBg")
    Column(
        modifier.clip(RoundedCornerShape(Radius.small)).background(bg).pressable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sf(icon, if (selected) c.onPastel else c.text2, 24.dp)
        Txt(label, Type.caption, if (selected) c.onPastel else c.text2, maxLines = 1)
    }
}

// ---------- modals ----------

@Composable
fun BottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(c.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it },
            exit = slideOutVertically(tween(220)) { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.large, topEnd = Radius.large))
                    .background(c.background)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Box(
                    Modifier.align(
                        Alignment.CenterHorizontally,
                    ).size(width = 38.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(c.text2.copy(alpha = 0.4f)),
                )
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun IosAlert(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(c.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            )
        }
        AnimatedVisibility(
            visible,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) + fadeIn(),
            exit = scaleOut(tween(150), targetScale = 0.9f) + fadeOut(tween(150)),
        ) {
            Glass(Modifier.width(290.dp)) {
                Column(Modifier.background(c.surface).padding(top = 20.dp)) {
                    Txt(title, Type.headline, c.text, Modifier.padding(horizontal = 20.dp).fillMaxWidth(), align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Txt(message, Type.footnote, c.text2, Modifier.padding(horizontal = 20.dp).fillMaxWidth(), align = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hairline))
                    Row(Modifier.fillMaxWidth().height(48.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight().pressable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                            Txt("Cancel", Type.body, c.text)
                        }
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.hairline))
                        Box(Modifier.weight(1f).fillMaxHeight().pressable(onClick = onConfirm), contentAlignment = Alignment.Center) {
                            Txt(confirmText, Type.headline, if (destructive) c.danger else c.text)
                        }
                    }
                }
            }
        }
    }
}
