package com.mux.android.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Nav {
    const val FILES = 0
    const val EDIT = 1
    const val TERM = 2
    const val GIT = 3
    const val AI = 4
    const val MORE = 5
    var tab by mutableIntStateOf(EDIT)
    var more by mutableIntStateOf(0)
    var panel by mutableStateOf(false)
}

private val TABS = listOf("FILES", "EDIT", "TERM", "GIT", "AI", "MORE")
private val MORE_TABS = listOf("EXT", "SET", "SYS")

@Composable
fun CodeRoot() {
    val ctx = LocalContext.current
    MuxTheme(if (Prefs.amber) AmberPalette else CyanPalette) {
        val c = Hud.colors
        var update by remember { mutableStateOf<UpdateInfo?>(null) }
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val compact = imeUp && (Nav.tab == Nav.EDIT || Nav.tab == Nav.TERM || Nav.tab == Nav.AI)

        LaunchedEffect(Unit) {
            if (Ws.open.isEmpty()) Nav.tab = Nav.FILES
            if (isOnline(ctx)) update = checkUpdate(ctx).second
        }

        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { Util.open(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }

        HudBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (!compact) {
                    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        HudText("◈ CODE", color = c.primary, size = 16.sp, bold = true)
                        Spacer(Modifier.width(8.dp))
                        HudText(TABS[Nav.tab], color = c.dim, size = 10.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (Nav.tab) {
                        Nav.FILES -> ExplorerScreen()
                        Nav.EDIT -> EditScreen()
                        Nav.TERM -> TermScreen()
                        Nav.GIT -> GitScreen()
                        Nav.AI -> AgentScreen()
                        else -> MoreScreen { update = it }
                    }
                }
                if (!compact) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().height(44.dp)) {
                        TABS.forEachIndexed { i, t ->
                            HudButton(
                                label = t, modifier = Modifier.weight(1f).fillMaxHeight(), kind = KeyKind.OP,
                                selected = i == Nav.tab, fontSize = 10.sp
                            ) { Nav.tab = i }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoreScreen(onUpdate: (UpdateInfo) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(38.dp)) {
            MORE_TABS.forEachIndexed { i, t ->
                HudButton(t, Modifier.weight(1f).fillMaxHeight(), KeyKind.OP, selected = i == Nav.more, fontSize = 11.sp) { Nav.more = i }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (Nav.more) {
                0 -> ExtScreen()
                1 -> SettingsScreen()
                else -> SystemScreen(false, onUpdate)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditScreen() {
    val c = Hud.colors
    val ctx = LocalContext.current
    var closing by remember { mutableStateOf<String?>(null) }
    val active = Ws.active

    Column(Modifier.fillMaxSize()) {
        // open file tabs
        Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (p in Ws.open) {
                val name = p.substringAfterLast('/')
                val d = Ws.dirty[p] == true
                HudButton(
                    label = name + if (d) " ●" else "", modifier = Modifier.height(34.dp).width((name.length * 8 + 52).coerceIn(80, 190).dp),
                    kind = if (d) KeyKind.ACCENT else KeyKind.OP, selected = p == active, fontSize = 10.sp,
                    onLongClick = { if (Ws.dirty[p] == true) closing = p else Ws.closeFile(p) }
                ) { Ws.show(p) }
            }
            HudText(" HOLD TAB TO CLOSE", color = c.dim, size = 8.sp, maxLines = 1)
        }
        if (active == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HudText("NO FILE OPEN", color = c.dim, size = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    HudButton("OPEN FILES", Modifier.width(150.dp).height(44.dp), KeyKind.ACCENT, fontSize = 12.sp) { Nav.tab = Nav.FILES }
                }
            }
        } else {
            // command toolbar
            Row(Modifier.fillMaxWidth().height(38.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val w = Modifier.width(64.dp).height(34.dp)
                HudButton("SAVE", w, KeyKind.GOOD, fontSize = 10.sp) { Ws.saveActive() }
                HudButton("SAVE ALL", Modifier.width(76.dp).height(34.dp), KeyKind.GOOD, fontSize = 9.sp) { Ws.saveAll() }
                HudButton("PALETTE", Modifier.width(76.dp).height(34.dp), KeyKind.ACCENT, fontSize = 9.sp) { Ed.trigger("editor.action.quickCommand") }
                HudButton("FIND", w, KeyKind.OP, fontSize = 10.sp) { Ed.trigger("actions.find") }
                HudButton("REPLACE", Modifier.width(72.dp).height(34.dp), KeyKind.OP, fontSize = 9.sp) { Ed.trigger("editor.action.startFindReplace") }
                HudButton("GO LINE", Modifier.width(70.dp).height(34.dp), KeyKind.OP, fontSize = 9.sp) { Ed.trigger("editor.action.gotoLine") }
                HudButton("UNDO", w, KeyKind.FN, fontSize = 10.sp) { Ed.trigger("undo") }
                HudButton("REDO", w, KeyKind.FN, fontSize = 10.sp) { Ed.trigger("redo") }
                HudButton("FORMAT", Modifier.width(70.dp).height(34.dp), KeyKind.FN, fontSize = 9.sp) { Ed.trigger("editor.action.formatDocument") }
                HudButton("COMMENT", Modifier.width(74.dp).height(34.dp), KeyKind.FN, fontSize = 9.sp) { Ed.trigger("editor.action.commentLine") }
                HudButton("ASK AI", Modifier.width(66.dp).height(34.dp), KeyKind.ACCENT, fontSize = 9.sp) { Ed.askSelection() }
                HudButton("PANEL", w, KeyKind.OP, selected = Nav.panel, fontSize = 10.sp) { Nav.panel = !Nav.panel }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { EditorView(Modifier.fillMaxSize()) }
            if (Nav.panel) {
                Spacer(Modifier.height(3.dp))
                Box(Modifier.weight(0.8f).fillMaxWidth()) { TermScreen() }
            }
            // symbol row
            Row(Modifier.fillMaxWidth().height(38.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val k = Modifier.width(44.dp).height(34.dp)
                HudButton("TAB", Modifier.width(50.dp).height(34.dp), KeyKind.OP, fontSize = 10.sp) { Ed.key("tab") }
                HudButton("⇤", k, KeyKind.OP, fontSize = 13.sp) { Ed.key("outdent") }
                HudButton("◄", k, KeyKind.OP, fontSize = 12.sp) { Ed.key("left") }
                HudButton("▲", k, KeyKind.OP, fontSize = 12.sp) { Ed.key("up") }
                HudButton("▼", k, KeyKind.OP, fontSize = 12.sp) { Ed.key("down") }
                HudButton("►", k, KeyKind.OP, fontSize = 12.sp) { Ed.key("right") }
                for (t in listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "/", "\\", "=", "+", "-", "*", "&", "|", "!", "?", "#", "$", "_", "`", ".", ",")) {
                    HudButton(t, k, KeyKind.NUM, fontSize = 14.sp) { Ed.type(t) }
                }
            }
        }
    }

    closing?.let { p ->
        HudDialog("UNSAVED CHANGES", { closing = null }) {
            HudText(p.substringAfterLast('/'), color = c.accent, size = 12.sp, bold = true)
            Spacer(Modifier.height(8.dp))
            HudButton("SAVE AND CLOSE", Modifier.fillMaxWidth().height(42.dp), KeyKind.GOOD, fontSize = 12.sp) {
                Ed.requestSave(p)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Ws.closeFile(p) }, 400)
                closing = null
            }
            HudButton("DISCARD", Modifier.fillMaxWidth().height(42.dp), KeyKind.DANGER, fontSize = 12.sp) { Ws.closeFile(p); closing = null }
            HudButton("CANCEL", Modifier.fillMaxWidth().height(42.dp), KeyKind.FN, fontSize = 12.sp) { closing = null }
        }
    }
}
