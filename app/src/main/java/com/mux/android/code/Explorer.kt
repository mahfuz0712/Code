package com.mux.android.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import java.io.File

private fun flatten(dir: File, depth: Int, expanded: List<String>, out: MutableList<Pair<File, Int>>) {
    val kids = dir.listFiles()?.filter { it.name != ".git" }
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
    for (k in kids) {
        out.add(k to depth)
        if (k.isDirectory && k.absolutePath in expanded) flatten(k, depth + 1, expanded, out)
    }
}

@Composable
fun PromptDialog(title: String, initial: String, hint: String, ok: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    HudDialog(title, onCancel) {
        HudField(v, hint, Modifier.fillMaxWidth().height(46.dp), onDone = { onDone(v) }) { v = it }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            HudButton("CANCEL", Modifier.weight(1f).height(44.dp), KeyKind.FN, fontSize = 12.sp) { onCancel() }
            HudButton(ok, Modifier.weight(1f).height(44.dp), KeyKind.ACCENT, fontSize = 12.sp) { onDone(v) }
        }
    }
}

/** FILES tab: folder tree, file actions, search in files and open-folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen() {
    val c = Hud.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val expanded = remember { mutableStateListOf<String>() }
    var menu by remember { mutableStateOf<File?>(null) }
    var prompt by remember { mutableStateOf<Triple<String, File, String>?>(null) } // kind, target, initial
    var confirmDel by remember { mutableStateOf<File?>(null) }
    var openDlg by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    val rows = remember(Ws.root, Ws.tick, expanded.toList()) {
        val l = ArrayList<Pair<File, Int>>()
        flatten(Ws.root, 0, expanded.toList(), l)
        l
    }

    Column(Modifier.fillMaxSize()) {
        HudText("◈ ${Ws.root.name.ifEmpty { "/" }}", color = c.primary, size = 13.sp, bold = true, maxLines = 1)
        HudText(Ws.root.absolutePath, color = c.dim, size = 9.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            HudButton("OPEN FOLDER", Modifier.width(110.dp).height(38.dp), KeyKind.ACCENT, fontSize = 10.sp) { openDlg = true }
            HudButton("+ FILE", Modifier.width(72.dp).height(38.dp), KeyKind.GOOD, fontSize = 10.sp) { prompt = Triple("file", Ws.root, "") }
            HudButton("+ FOLDER", Modifier.width(84.dp).height(38.dp), KeyKind.GOOD, fontSize = 10.sp) { prompt = Triple("folder", Ws.root, "") }
            HudButton("REFRESH", Modifier.width(80.dp).height(38.dp), KeyKind.FN, fontSize = 10.sp) { Ws.refresh() }
            HudButton("COLLAPSE", Modifier.width(84.dp).height(38.dp), KeyKind.FN, fontSize = 10.sp) { expanded.clear() }
            HudButton("SEARCH", Modifier.width(76.dp).height(38.dp), KeyKind.OP, selected = searching, fontSize = 10.sp) { searching = !searching }
        }
        if (searching) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HudField(query, "find text in all files", Modifier.weight(1f).height(44.dp), onDone = {
                    scope.launch { busy = true; hits = withContext(Dispatchers.IO) { Ws.search(query) }; busy = false }
                }) { query = it }
                HudButton("GO", Modifier.width(56.dp).height(44.dp), KeyKind.ACCENT, fontSize = 12.sp) {
                    scope.launch { busy = true; hits = withContext(Dispatchers.IO) { Ws.search(query) }; busy = false }
                }
            }
            if (busy) HudText("SEARCHING...", color = c.accent, size = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (searching && hits.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item { HudText("${hits.size} RESULT(S)", color = c.dim, size = 10.sp) }
                items(hits) { h ->
                    Column(Modifier.fillMaxWidth().combinedClickable { if (Ws.openFile(h.path, h.line)) Nav.tab = Nav.EDIT }.padding(vertical = 4.dp)) {
                        HudText("${Ws.rel(h.path)}:${h.line}", color = c.primary, size = 11.sp, maxLines = 1)
                        HudText(h.text, color = c.text, size = 11.sp, maxLines = 1)
                    }
                }
            }
        } else {
            if (rows.isEmpty()) EmptyNote("EMPTY FOLDER. USE + FILE, + FOLDER OR GIT > CLONE")
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.first.absolutePath }) { (f, depth) ->
                    val isOpen = f.absolutePath in expanded
                    val sel = Ws.active == f.absolutePath
                    Row(
                        Modifier.fillMaxWidth().height(38.dp)
                            .combinedClickable(
                                onClick = {
                                    if (f.isDirectory) { if (isOpen) expanded.remove(f.absolutePath) else expanded.add(f.absolutePath) }
                                    else if (Ws.openFile(f.absolutePath)) Nav.tab = Nav.EDIT
                                },
                                onLongClick = { menu = f }
                            )
                            .padding(start = (depth * 14).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudText(if (f.isDirectory) (if (isOpen) "▾ " else "▸ ") else "◇ ", color = if (f.isDirectory) c.accent else c.dim, size = 13.sp)
                        HudText(
                            f.name, color = if (sel) c.primary else if (f.isDirectory) c.text else c.text,
                            size = 13.sp, bold = sel || f.isDirectory, maxLines = 1
                        )
                        if (Ws.dirty[f.absolutePath] == true) HudText(" ●", color = c.accent, size = 12.sp)
                    }
                }
            }
        }
    }

    menu?.let { f ->
        HudDialog(f.name, { menu = null }) {
            if (f.isDirectory) {
                HudButton("NEW FILE HERE", Modifier.fillMaxWidth().height(42.dp), KeyKind.GOOD, fontSize = 12.sp) { prompt = Triple("file", f, ""); menu = null }
                HudButton("NEW FOLDER HERE", Modifier.fillMaxWidth().height(42.dp), KeyKind.GOOD, fontSize = 12.sp) { prompt = Triple("folder", f, ""); menu = null }
                HudButton("OPEN AS WORKSPACE", Modifier.fillMaxWidth().height(42.dp), KeyKind.ACCENT, fontSize = 12.sp) { Ws.setRoot(f); menu = null }
                HudButton("TERMINAL HERE", Modifier.fillMaxWidth().height(42.dp), KeyKind.OP, fontSize = 12.sp) {
                    Sessions.active.goTo(f.absolutePath); menu = null; Nav.tab = Nav.TERM
                }
            }
            HudButton("RENAME", Modifier.fillMaxWidth().height(42.dp), KeyKind.OP, fontSize = 12.sp) { prompt = Triple("rename", f, f.name); menu = null }
            HudButton("COPY PATH", Modifier.fillMaxWidth().height(42.dp), KeyKind.FN, fontSize = 12.sp) { Util.copy(ctx, f.absolutePath, "PATH COPIED"); menu = null }
            HudButton("DELETE", Modifier.fillMaxWidth().height(42.dp), KeyKind.DANGER, fontSize = 12.sp) { confirmDel = f; menu = null }
        }
    }

    prompt?.let { (kind, target, initial) ->
        PromptDialog(
            when (kind) { "file" -> "NEW FILE"; "folder" -> "NEW FOLDER"; else -> "RENAME" },
            initial, if (kind == "folder") "folder name" else "name", "OK",
            onDone = { v ->
                val err = when (kind) {
                    "file" -> Ws.create(target, v, false)
                    "folder" -> Ws.create(target, v, true)
                    else -> Ws.rename(target, v)
                }
                if (err != null) Util.toast(ctx, err.uppercase())
                else if (kind != "rename" && target != Ws.root && target.absolutePath !in expanded) expanded.add(target.absolutePath)
                prompt = null
            },
            onCancel = { prompt = null }
        )
    }

    confirmDel?.let { f ->
        HudDialog("DELETE ${f.name}?", { confirmDel = null }) {
            HudText(if (f.isDirectory) "The folder and everything inside it will be deleted." else "The file will be deleted.", color = c.text, size = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                HudButton("CANCEL", Modifier.weight(1f).height(44.dp), KeyKind.FN, fontSize = 12.sp) { confirmDel = null }
                HudButton("DELETE", Modifier.weight(1f).height(44.dp), KeyKind.DANGER, fontSize = 12.sp) { Ws.delete(f); confirmDel = null }
            }
        }
    }

    if (openDlg) OpenFolderDialog(onDismiss = { openDlg = false })
}

@Composable
fun OpenFolderDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf(Ws.root.absolutePath) }
    HudDialog("OPEN FOLDER", onDismiss) {
        HudButton("APP WORKSPACE", Modifier.fillMaxWidth().height(42.dp), KeyKind.ACCENT, fontSize = 12.sp) { Ws.setRoot(File(Env.home)); onDismiss() }
        HudButton("PHONE STORAGE (/sdcard)", Modifier.fillMaxWidth().height(42.dp), KeyKind.OP, fontSize = 12.sp) {
            val f = File(Env.SD)
            if (f.canRead()) { Ws.setRoot(f); onDismiss() } else Util.toast(ctx, "GRANT ALL FILES ACCESS IN MORE > SET")
        }
        Spacer(Modifier.height(6.dp))
        HudField(path, "/storage/emulated/0/MyProject", Modifier.fillMaxWidth().height(46.dp), onDone = {}) { path = it }
        Spacer(Modifier.height(6.dp))
        HudButton("OPEN THIS PATH", Modifier.fillMaxWidth().height(42.dp), KeyKind.GOOD, fontSize = 12.sp) {
            val f = File(path.trim())
            if (f.isDirectory && f.canRead()) { Ws.setRoot(f); onDismiss() } else Util.toast(ctx, "CANNOT OPEN THAT FOLDER")
        }
    }
}
