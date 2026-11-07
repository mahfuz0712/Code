package com.mux.android.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AI tab: chat with the coding agent. */
@Composable
fun AgentScreen() {
    val c = Hud.colors
    val list = rememberLazyListState()
    LaunchedEffect(Agent.chat.size, Agent.busy) {
        if (Agent.chat.isNotEmpty()) list.animateScrollToItem(Agent.chat.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HudText("◈ AI AGENT", color = c.primary, size = 14.sp, bold = true)
                HudText("${Prefs.model} · ${if (Prefs.approveAuto) "AUTO" else "ASKS FIRST"}", color = c.dim, size = 9.sp, maxLines = 1)
            }
            HudButton("NEW CHAT", Modifier.width(92.dp).height(36.dp), KeyKind.FN, fontSize = 10.sp) { Agent.newChat() }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(c.bg.copy(alpha = 0.85f), HudShape(8.dp))
                .border(1.dp, c.primary.copy(alpha = 0.4f), HudShape(8.dp))
                .padding(8.dp)
        ) {
            if (Agent.chat.isEmpty()) {
                Column {
                    HudText("Ask anything about your project. The agent can read, search and edit files, run commands and use git in the open folder.", color = c.dim, size = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    HudText("Examples:\n• explain this project\n• add a README.md\n• find the bug in index.js and fix it\n• commit my changes with a good message", color = c.dim, size = 11.sp)
                    if (Prefs.apiKey.isBlank()) {
                        Spacer(Modifier.height(10.dp))
                        HudText("No API key yet: MORE > SET > AI AGENT.", color = c.accent, size = 11.sp, bold = true)
                    }
                }
            }
            SelectionContainer {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(Agent.chat.size) { i ->
                        val m = Agent.chat[i]
                        val (col, label) = when (m.kind) {
                            'u' -> c.accent to "YOU"
                            'a' -> c.primary to "AGENT"
                            'e' -> c.danger to "ERROR"
                            else -> c.dim to ""
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            if (label.isNotEmpty()) HudText(label, color = col, size = 9.sp, bold = true)
                            HudText(m.text, color = if (m.kind == 'a' || m.kind == 'u') c.text else col, size = if (m.kind == 't') 10.sp else 12.sp)
                        }
                    }
                    if (Agent.busy) item { HudText("● working...", color = c.accent, size = 11.sp) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.weight(1f).heightIn(min = 46.dp, max = 130.dp)
                    .background(c.primary.copy(alpha = 0.06f), HudShape(8.dp))
                    .border(1.dp, c.primary.copy(alpha = 0.5f), HudShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (Agent.draft.isEmpty()) HudText("message the agent", color = c.dim, size = 13.sp)
                BasicTextField(
                    value = Agent.draft,
                    onValueChange = { Agent.draft = it },
                    maxLines = 6,
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(c.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (Agent.busy) HudButton("STOP", Modifier.width(66.dp).height(46.dp), KeyKind.DANGER, fontSize = 12.sp) { Agent.stop() }
            else HudButton("SEND", Modifier.width(66.dp).height(46.dp), KeyKind.ACCENT, enabled = Agent.draft.isNotBlank(), fontSize = 12.sp) {
                val t = Agent.draft
                Agent.draft = ""
                Agent.send(t)
            }
        }
    }

    Agent.pending?.let { p ->
        HudDialog(p.title, { p.answer.complete(false) }) {
            Column(Modifier.heightIn(max = 300.dp)) {
                HudText(p.detail, color = c.text, size = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                HudButton("DENY", Modifier.weight(1f).height(44.dp), KeyKind.DANGER, fontSize = 12.sp) { p.answer.complete(false) }
                HudButton("ALLOW", Modifier.weight(1f).height(44.dp), KeyKind.GOOD, fontSize = 12.sp) { p.answer.complete(true) }
            }
        }
    }
}
