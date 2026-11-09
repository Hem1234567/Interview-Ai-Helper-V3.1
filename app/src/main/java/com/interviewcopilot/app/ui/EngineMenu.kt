package com.interviewcopilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.interviewcopilot.app.core.Engine
import com.interviewcopilot.app.vm.CopilotVM

/** Which engine writes the answers. Shown at the top left of the Assistant screen. */
@Composable
fun EngineMenu(vm: CopilotVM) {
    val c = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    val chosen = Engine.from(vm.g("provider"))
    val dropOffset = with(LocalDensity.current) { 42.dp.roundToPx() }

    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.surface).pressable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sf(Sym.cpu, c.text, 16.dp)
            Spacer(Modifier.width(6.dp))
            Txt(chosen.label, Type.subhead, c.text, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Sf(Sym.chevronDown, c.text2, 14.dp, description = "Choose answer engine")
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, dropOffset),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier.width(
                        250.dp,
                    ).clip(RoundedCornerShape(Radius.medium)).background(c.surface).border(0.5.dp, c.hairline, RoundedCornerShape(Radius.medium)),
                ) {
                    Column {
                        Engine.values().forEachIndexed { i, e ->
                            ListRow(
                                e.label,
                                subtitle = vm.engineHint(e),
                                divider = i != Engine.values().lastIndex,
                                onClick = {
                                    vm.put("provider", e.name)
                                    vm.prewarmNetwork()
                                    open = false
                                },
                                trailing = { if (e == chosen) Sf(Sym.check, c.text, 18.dp) },
                            )
                        }
                    }
                }
            }
        }
    }
}
