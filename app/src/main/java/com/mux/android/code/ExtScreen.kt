package com.mux.android.code

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** EXT sub-tab: install .vsix files, search Open VSX, manage installed extensions. */
@Composable
fun ExtScreen() {
    val c = Hud.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<MarketHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            note = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { Ext.installVsix(it) } ?: "cannot read file"
                } catch (e: Exception) { "install failed: ${e.message}" }
            }
            Ed.loadExtensions()
            busy = false
        }
    }

    fun search() {
        if (query.isBlank() || busy) return
        scope.launch {
            busy = true
            note = "searching open-vsx.org..."
            try {
                hits = withContext(Dispatchers.IO) { Ext.search(query.trim()) }
                note = "${hits.size} result(s)"
            } catch (e: Exception) {
                note = "search failed: ${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HudPanel(Modifier.fillMaxWidth(), "WHAT WORKS") {
            HudText(
                "Colour themes and snippets from VS Code extensions work. Extension code (the part that runs in VS Code's Node.js host: language servers, debuggers, commands) cannot run on Android.",
                color = c.text, size = 11.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        HudPanel(Modifier.fillMaxWidth(), "INSTALL") {
            HudButton("INSTALL FROM .VSIX FILE", Modifier.fillMaxWidth().height(44.dp), KeyKind.ACCENT, enabled = !busy, fontSize = 12.sp) {
                picker.launch("*/*")
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HudField(query, "search Open VSX (theme, snippets...)", Modifier.weight(1f).height(44.dp), onDone = { search() }) { query = it }
                HudButton("GO", Modifier.width(54.dp).height(44.dp), KeyKind.GOOD, enabled = !busy, fontSize = 12.sp) { search() }
            }
            if (note.isNotEmpty()) HudText(note, color = if (busy) c.accent else c.dim, size = 10.sp)
        }
        for (h in hits) {
            Spacer(Modifier.height(6.dp))
            HudItem(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HudText(h.title, color = c.primary, size = 12.sp, bold = true, maxLines = 1)
                        HudText("${h.ns}.${h.name} ${h.version}", color = c.dim, size = 9.sp, maxLines = 1)
                        if (h.desc.isNotBlank()) HudText(h.desc, color = c.text, size = 10.sp, maxLines = 2)
                    }
                    HudButton("GET", Modifier.width(56.dp).height(40.dp), KeyKind.GOOD, enabled = !busy, fontSize = 11.sp) {
                        scope.launch {
                            busy = true
                            note = "downloading ${h.title}..."
                            note = withContext(Dispatchers.IO) {
                                try { Ext.installFromUrl(h.url) } catch (e: Exception) { "install failed: ${e.message}" }
                            }
                            Ed.loadExtensions()
                            busy = false
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HudPanel(Modifier.fillMaxWidth(), "INSTALLED (${Ext.installed.size})") {
            if (Ext.installed.isEmpty()) HudText("nothing installed yet", color = c.dim, size = 11.sp)
            for (e in Ext.installed) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HudText(e.name, color = c.primary, size = 12.sp, bold = true, maxLines = 1)
                        HudText(
                            "${e.publisher} ${e.version} · ${e.themes} theme · ${e.snippets} snippet" + if (e.hasCode) " · code not run" else "",
                            color = c.dim, size = 9.sp, maxLines = 2
                        )
                    }
                    HudButton("✕", Modifier.width(44.dp).height(38.dp), KeyKind.DANGER, fontSize = 13.sp) {
                        if (Prefs.edTheme.startsWith("ext:${e.id}:")) { Prefs.edTheme = "mux"; Prefs.commit() }
                        Ext.uninstall(e)
                        Ed.options()
                        Util.toast(ctx, "REMOVED (RESTART FOR SNIPPETS TO CLEAR)")
                    }
                }
            }
            if (Ext.themeList.isNotEmpty()) HudText("Pick a theme in MORE > SET > EDITOR THEME", color = c.dim, size = 9.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}
