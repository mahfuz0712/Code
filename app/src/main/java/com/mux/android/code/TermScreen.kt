package com.mux.android.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tab strip: one chip per session, tap = open, long-press = close, + = new. */
@Composable
fun SessionTabs() {
    val c = Hud.colors
    Row(
        Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Sessions.list.forEachIndexed { i, s ->
            HudButton(
                label = (if (s.running) "● " else "") + "T${s.id}",
                modifier = Modifier.width(66.dp).height(34.dp),
                kind = if (s.running) KeyKind.ACCENT else KeyKind.OP,
                selected = i == Sessions.current,
                fontSize = 11.sp,
                onLongClick = { Sessions.closeSession(s) }
            ) { Sessions.current = i }
        }
        HudButton("+", Modifier.width(44.dp).height(34.dp), KeyKind.GOOD, fontSize = 15.sp) { Sessions.add() }
        Spacer(Modifier.width(6.dp))
        HudText("HOLD TAB TO CLOSE", color = c.dim, size = 8.sp, maxLines = 1)
    }
}

@Composable
fun TermScreen() {
    Sessions.ensure()
    val s = Sessions.active
    val c = Hud.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var stick by remember(s) { mutableStateOf(true) }
    val total = s.lines.size + 1
    val fs = Prefs.fontSize.sp

    LaunchedEffect(s, s.lines, s.live) {
        if (stick) listState.scrollToItem((total - 1).coerceAtLeast(0))
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val li = listState.layoutInfo
            val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0
            stick = last >= li.totalItemsCount - 2
        }
    }

    Column(Modifier.fillMaxSize()) {
        SessionTabs()
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            HudButton("COPY", Modifier.weight(1f).height(32.dp), KeyKind.FN, fontSize = 10.sp) {
                Util.copy(ctx, outputText(s), "OUTPUT COPIED")
            }
            HudButton("SHARE", Modifier.weight(1f).height(32.dp), KeyKind.FN, fontSize = 10.sp) {
                Util.shareText(ctx, outputText(s))
            }
            HudButton("CLEAR", Modifier.weight(1f).height(32.dp), KeyKind.FN, fontSize = 10.sp) { s.clear() }
            HudButton("▼ END", Modifier.weight(1f).height(32.dp), KeyKind.OP, fontSize = 10.sp) {
                stick = true
            }
            HudButton(
                if (s.running) "^C STOP" else "IDLE", Modifier.weight(1f).height(32.dp),
                if (s.running) KeyKind.DANGER else KeyKind.FN, enabled = s.running, fontSize = 10.sp
            ) { s.interrupt() }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 2.dp)
                .background(c.bg.copy(alpha = 0.85f), HudShape(8.dp))
                .border(1.dp, c.primary.copy(alpha = 0.45f), HudShape(8.dp))
                .padding(6.dp)
                .onSizeChanged { sz ->
                    val charW = with(density) { (Prefs.fontSize * 0.6f).sp.toPx() }
                    s.cols = (sz.width / charW).toInt().coerceIn(20, 200)
                }
        ) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(total) { idx ->
                        val line: AnnotatedString = if (idx < s.lines.size) s.lines[idx] else s.live
                        Text(
                            text = if (line.isEmpty()) AnnotatedString(" ") else line,
                            color = c.text,
                            fontSize = fs,
                            lineHeight = (Prefs.fontSize * 1.25f).sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        InputBar(s)
        if (Prefs.extraKeys) ExtraKeys(s)
    }
}

private fun outputText(s: Session): String =
    (s.lines.map { it.text } + s.live.text).joinToString("\n").trimEnd()

@Composable
private fun InputBar(s: Session) {
    val c = Hud.colors
    val shape = HudShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HudText(
            if (s.running) "▶ RUNNING · TYPED TEXT GOES TO THE COMMAND" else s.prompt(),
            color = if (s.running) c.accent else c.dim, size = 10.sp, maxLines = 1
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(c.primary.copy(alpha = 0.06f), shape)
                    .border(1.dp, c.primary.copy(alpha = 0.5f), shape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (s.input.text.isEmpty()) HudText(if (s.running) "stdin..." else "command", color = c.dim, size = 14.sp)
                BasicTextField(
                    value = s.input,
                    onValueChange = { s.input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { s.submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HudButton("RUN", Modifier.width(64.dp).height(46.dp), KeyKind.ACCENT, fontSize = 13.sp) { s.submit() }
        }
    }
}

@Composable
private fun ExtraKeys(s: Session) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        val w = Modifier.width(46.dp).height(36.dp)
        HudButton("TAB", Modifier.width(54.dp).height(36.dp), KeyKind.OP, fontSize = 11.sp) {
            val r = s.complete()
            if (r != null) {
                s.setInput(r.text)
                if (r.options.size > 1) s.note(r.options.joinToString("  "))
            }
        }
        HudButton("▲", w, KeyKind.OP, fontSize = 13.sp) { s.historyUp() }
        HudButton("▼", w, KeyKind.OP, fontSize = 13.sp) { s.historyDown() }
        HudButton("◄", w, KeyKind.OP, fontSize = 13.sp) { s.moveCursor(-1) }
        HudButton("►", w, KeyKind.OP, fontSize = 13.sp) { s.moveCursor(1) }
        HudButton("^C", w, KeyKind.DANGER, fontSize = 12.sp) { if (s.running) s.interrupt() else s.setInput("") }
        HudButton("^D", w, KeyKind.ACCENT, fontSize = 12.sp) { if (s.running) s.eof() else Util.toast(ctx, "NOTHING RUNNING") }
        HudButton("^L", w, KeyKind.ACCENT, fontSize = 12.sp) { s.clear() }
        for (t in listOf("/", "~", "-", "_", ".", "|", "&", ">", "<", "*", "$", "\"", "'", "\\", ";", "=")) {
            HudButton(t, w, KeyKind.NUM, fontSize = 14.sp) { s.insert(t) }
        }
    }
}
