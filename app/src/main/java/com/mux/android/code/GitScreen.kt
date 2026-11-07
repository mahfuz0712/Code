package com.mux.android.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import java.io.File

/** GIT tab: status, stage, commit, branches, pull / push, clone, history. Works on the open folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GitScreen() {
    val c = Hud.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<RepoStatus?>(null) }
    var logs by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var branches by remember { mutableStateOf<Pair<String, List<String>>>("" to emptyList()) }
    var msg by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var diffText by remember { mutableStateOf<String?>(null) }
    var branchDlg by remember { mutableStateOf(false) }
    var newBranch by remember { mutableStateOf<Boolean>(false) }
    var loaded by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val d = Ws.root
            val r = withContext(Dispatchers.IO) {
                try { Triple(GitEngine.status(d), GitEngine.log(d), GitEngine.branches(d)) } catch (e: Throwable) { null }
            }
            if (r != null) { st = r.first; logs = r.second; branches = r.third }
            loaded = true
        }
    }

    fun task(label: String, f: () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            out = "$label..."
            out = withContext(Dispatchers.IO) { try { f() } catch (e: Throwable) { "error: ${e.message ?: e.javaClass.simpleName}" } }
            busy = false
            Ws.refresh()
            Ed.reloadAllClean()
            reload()
        }
    }

    LaunchedEffect(Ws.root, Ws.tick) { reload() }

    val root = Ws.root
    val s = st

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (s == null) {
            HudPanel(Modifier.fillMaxWidth(), "NO GIT REPOSITORY HERE") {
                HudText(root.absolutePath, color = c.dim, size = 10.sp)
                Spacer(Modifier.height(6.dp))
                HudButton("GIT INIT", Modifier.fillMaxWidth().height(44.dp), KeyKind.ACCENT, enabled = !busy, fontSize = 12.sp) {
                    task("init") { GitEngine.init(root) }
                }
            }
            Spacer(Modifier.height(8.dp))
            HudPanel(Modifier.fillMaxWidth(), "CLONE A REPOSITORY") {
                HudText("HTTPS links only (github.com/user/repo). For private repos set your username and token in MORE > SET > GIT.", color = c.dim, size = 10.sp)
                Spacer(Modifier.height(4.dp))
                HudField(url, "https://github.com/user/repo.git", Modifier.fillMaxWidth().height(46.dp)) { url = it }
                Spacer(Modifier.height(4.dp))
                HudButton(if (busy) "WORKING..." else "CLONE", Modifier.fillMaxWidth().height(44.dp), KeyKind.GOOD, enabled = !busy && url.isNotBlank(), fontSize = 12.sp) {
                    val u = url.trim()
                    task("cloning") {
                        val r = GitEngine.runCli(root, listOf("clone", u))
                        val name = u.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                        val dir = File(root, name)
                        if (File(dir, ".git").isDirectory) {
                            val h = android.os.Handler(android.os.Looper.getMainLooper())
                            h.post { Ws.setRoot(dir) }
                        }
                        r
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HudText("⎇ ${s.branch}", color = c.primary, size = 15.sp, bold = true, maxLines = 1)
                    HudText(s.root.absolutePath, color = c.dim, size = 9.sp, maxLines = 1)
                }
                HudButton("BRANCH", Modifier.width(82.dp).height(38.dp), KeyKind.OP, fontSize = 10.sp) { branchDlg = true }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                HudButton("PULL", Modifier.weight(1f).height(40.dp), KeyKind.OP, enabled = !busy, fontSize = 11.sp) { task("pull") { GitEngine.pull(root) } }
                HudButton("PUSH", Modifier.weight(1f).height(40.dp), KeyKind.ACCENT, enabled = !busy, fontSize = 11.sp) { task("push") { GitEngine.push(root) } }
                HudButton("FETCH", Modifier.weight(1f).height(40.dp), KeyKind.OP, enabled = !busy, fontSize = 11.sp) { task("fetch") { GitEngine.fetch(root) } }
                HudButton("↻", Modifier.width(48.dp).height(40.dp), KeyKind.FN, fontSize = 15.sp) { reload() }
            }
            Spacer(Modifier.height(6.dp))
            HudPanel(Modifier.fillMaxWidth(), "COMMIT") {
                HudField(msg, "commit message", Modifier.fillMaxWidth().height(46.dp)) { msg = it }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    HudButton("STAGE ALL", Modifier.weight(1f).height(42.dp), KeyKind.OP, enabled = !busy && s.changes.isNotEmpty(), fontSize = 11.sp) {
                        task("stage") { GitEngine.stageAll(root); "all changes staged" }
                    }
                    HudButton(
                        "COMMIT", Modifier.weight(1f).height(42.dp), KeyKind.GOOD,
                        enabled = !busy && msg.isNotBlank() && s.changes.any { it.staged }, fontSize = 11.sp
                    ) {
                        val m = msg.trim()
                        task("commit") { GitEngine.commit(root, m).also { msg = "" } }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HudPanel(Modifier.fillMaxWidth(), "CHANGES (${s.changes.size})") {
                if (s.changes.isEmpty()) HudText("working tree clean", color = c.dim, size = 11.sp)
                for (ch in s.changes) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).combinedClickable(onClick = {
                            val f = File(s.root, ch.path)
                            if (f.isFile && Ws.openFile(f.absolutePath)) Nav.tab = Nav.EDIT
                        }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val col = when (ch.code) { 'A', 'U' -> c.good; 'D', 'C' -> c.danger; else -> c.accent }
                        HudText("${ch.code}${if (ch.staged) "●" else " "} ", color = col, size = 12.sp, bold = true)
                        HudText(ch.path, color = c.text, size = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        HudButton("DIFF", Modifier.width(48.dp).height(34.dp), KeyKind.FN, fontSize = 9.sp) {
                            scope.launch { diffText = withContext(Dispatchers.IO) { GitEngine.diff(root, ch.path, ch.staged) } }
                        }
                        HudButton(if (ch.staged) "−" else "+", Modifier.width(38.dp).height(34.dp), if (ch.staged) KeyKind.OP else KeyKind.GOOD, fontSize = 14.sp) {
                            task(if (ch.staged) "unstage" else "stage") {
                                if (ch.staged) GitEngine.unstage(root, ch.path) else GitEngine.stage(root, ch)
                                "${if (ch.staged) "unstaged" else "staged"} ${ch.path}"
                            }
                        }
                        if (!ch.staged) HudButton("✕", Modifier.width(38.dp).height(34.dp), KeyKind.DANGER, fontSize = 12.sp, onLongClick = null) {
                            task("discard") { GitEngine.discard(root, ch); "discarded ${ch.path}" }
                        }
                    }
                }
                if (s.changes.any { !it.staged }) HudText("✕ DISCARDS THE CHANGES OF THAT FILE", color = c.dim, size = 8.sp)
            }
            Spacer(Modifier.height(6.dp))
            HudPanel(Modifier.fillMaxWidth(), "HISTORY") {
                if (logs.isEmpty()) HudText("no commits yet", color = c.dim, size = 11.sp)
                for (l in logs) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row {
                            HudText(l.id, color = c.accent, size = 11.sp, bold = true)
                            Spacer(Modifier.width(8.dp))
                            HudText(l.msg, color = c.text, size = 11.sp, maxLines = 1)
                        }
                        HudText("${l.author} · ${l.time}", color = c.dim, size = 9.sp)
                    }
                }
            }
        }
        if (out.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            HudPanel(Modifier.fillMaxWidth(), "OUTPUT") {
                HudText(out, color = c.text, size = 11.sp)
                Spacer(Modifier.height(4.dp))
                HudButton("COPY", Modifier.fillMaxWidth().height(34.dp), KeyKind.FN, fontSize = 10.sp) { Util.copy(ctx, out, "COPIED") }
            }
        }
        Spacer(Modifier.height(8.dp))
        HudText("Same git is available in the terminal: git status, git add ., git commit -m \"msg\", git push", color = c.dim, size = 9.sp)
        Spacer(Modifier.height(8.dp))
    }

    diffText?.let { d ->
        HudDialog("DIFF", { diffText = null }) {
            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                for (line in d.lines()) {
                    val col = when {
                        line.startsWith("+") && !line.startsWith("+++") -> c.good
                        line.startsWith("-") && !line.startsWith("---") -> c.danger
                        line.startsWith("@@") -> c.primary
                        else -> c.text
                    }
                    HudText(line.ifEmpty { " " }, color = col, size = 10.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            HudButton("CLOSE", Modifier.fillMaxWidth().height(40.dp), KeyKind.FN, fontSize = 12.sp) { diffText = null }
        }
    }

    if (branchDlg) {
        var nb by remember { mutableStateOf("") }
        HudDialog("BRANCHES", { branchDlg = false }) {
            Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                for (b in branches.second) {
                    HudButton(
                        (if (b == branches.first) "● " else "") + b, Modifier.fillMaxWidth().height(40.dp),
                        if (b == branches.first) KeyKind.ACCENT else KeyKind.OP, fontSize = 12.sp
                    ) {
                        branchDlg = false
                        task("checkout") { GitEngine.checkout(root, b, false); "switched to $b" }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HudField(nb, "new branch name", Modifier.fillMaxWidth().height(44.dp)) { nb = it }
            Spacer(Modifier.height(4.dp))
            HudButton("CREATE AND SWITCH", Modifier.fillMaxWidth().height(42.dp), KeyKind.GOOD, enabled = nb.isNotBlank(), fontSize = 12.sp) {
                val n = nb.trim()
                branchDlg = false
                task("branch") { GitEngine.checkout(root, n, true); "created and switched to $n" }
            }
        }
    }
}
