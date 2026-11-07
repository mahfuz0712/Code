package com.mux.android.code

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import java.io.File
import java.io.InputStreamReader

/** Paths and environment shared by all sessions. */
object Env {
    var home: String = "/data/local/tmp"
    var cache: String = "/data/local/tmp"
    var version: String = "1.0.0"
    const val SD = "/storage/emulated/0"

    fun init(ctx: Context) {
        val h = File(ctx.filesDir, "workspace")
        h.mkdirs()
        home = h.absolutePath
        cache = ctx.cacheDir.absolutePath
        version = appVersion(ctx)
    }
}

private val ANSI = intArrayOf(
    0xFF000000.toInt(), 0xFFFF5252.toInt(), 0xFF3DDC84.toInt(), 0xFFFFB000.toInt(),
    0xFF5C8DFF.toInt(), 0xFFD66BFF.toInt(), 0xFF19E3FF.toInt(), 0xFFD6F8FF.toInt(),
    0xFF5A6B73.toInt(), 0xFFFF8A80.toInt(), 0xFF8DF5B8.toInt(), 0xFFFFD54F.toInt(),
    0xFF8CB0FF.toInt(), 0xFFE5A3FF.toInt(), 0xFF84F2FF.toInt(), 0xFFFFFFFF.toInt()
)

private fun color256(n: Int): Int {
    if (n < 16) return ANSI[n.coerceAtLeast(0)]
    if (n < 232) {
        val v = n - 16
        fun comp(k: Int) = if (k == 0) 0 else 55 + 40 * k
        return (0xFF shl 24) or (comp(v / 36) shl 16) or (comp((v / 6) % 6) shl 8) or comp(v % 6)
    }
    val g = (8 + 10 * (n - 232)).coerceAtMost(255)
    return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
}

/**
 * Turns a raw text stream into lines. Understands colours (SGR), carriage return, backspace, tab,
 * erase-line, and silently drops every other escape sequence. Not a full terminal emulator.
 */
class Screen {
    private val ch = StringBuilder()
    private val st = ArrayList<Long>()
    private var col = 0
    private var fg = 0
    private var bold = false
    private var esc: StringBuilder? = null
    val done = ArrayList<AnnotatedString>()

    private fun put(c: Char) {
        val s = (if (bold) 1L shl 32 else 0L) or (fg.toLong() and 0xFFFFFFFFL)
        if (col < ch.length) {
            ch.setCharAt(col, c); st[col] = s
        } else {
            while (ch.length < col) { ch.append(' '); st.add(0L) }
            ch.append(c); st.add(s)
        }
        col++
    }

    fun build(): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < ch.length) {
            val s = st[i]
            var j = i + 1
            while (j < ch.length && st[j] == s) j++
            val color = (s and 0xFFFFFFFFL).toInt()
            val b = (s shr 32) != 0L
            val seg = ch.substring(i, j)
            if (color == 0 && !b) append(seg)
            else withStyle(
                SpanStyle(
                    color = if (color == 0) Color.Unspecified else Color(color),
                    fontWeight = if (b) FontWeight.Bold else null
                )
            ) { append(seg) }
            i = j
        }
    }

    val isEmpty: Boolean get() = ch.isEmpty()

    fun endLine() {
        done.add(build())
        ch.setLength(0); st.clear(); col = 0
    }

    private fun sgr(params: String) {
        val p = if (params.isEmpty()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < p.size) {
            val n = p[i]
            when {
                n == 0 -> { fg = 0; bold = false }
                n == 1 -> bold = true
                n == 22 -> bold = false
                n in 30..37 -> fg = ANSI[n - 30]
                n in 90..97 -> fg = ANSI[n - 90 + 8]
                n == 39 -> fg = 0
                n == 38 || n == 48 -> {
                    val set = n == 38
                    if (p.getOrNull(i + 1) == 5) {
                        if (set) fg = color256(p.getOrElse(i + 2) { 7 })
                        i += 2
                    } else if (p.getOrNull(i + 1) == 2) {
                        if (set) {
                            fg = (0xFF shl 24) or (p.getOrElse(i + 2) { 0 }.coerceIn(0, 255) shl 16) or
                                (p.getOrElse(i + 3) { 0 }.coerceIn(0, 255) shl 8) or p.getOrElse(i + 4) { 0 }.coerceIn(0, 255)
                        }
                        i += 4
                    }
                }
            }
            i++
        }
    }

    fun feed(text: String) {
        for (c in text) {
            val e = esc
            if (e != null) {
                e.append(c)
                if (e.length == 1) {
                    // first char after ESC
                    if (c != '[' && c != ']') esc = null
                    continue
                }
                if (e[0] == '[') {
                    if (c.code in 0x40..0x7E) {
                        val body = e.substring(1, e.length - 1)
                        when (c) {
                            'm' -> sgr(body)
                            'K' -> { if (body == "" || body == "0") { if (col < ch.length) { ch.setLength(col); while (st.size > col) st.removeAt(st.size - 1) } } else if (body == "2") { ch.setLength(0); st.clear(); col = 0 } }
                            'G' -> col = ((body.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
                            'D' -> col = (col - (body.toIntOrNull() ?: 1)).coerceAtLeast(0)
                        }
                        esc = null
                    } else if (e.length > 40) esc = null
                } else {
                    // OSC: ends with BEL or ESC \
                    if (c == '\u0007' || (c == '\\' && e.length >= 2 && e[e.length - 2] == '\u001b')) esc = null
                    else if (e.length > 300) esc = null
                }
                continue
            }
            when (c) {
                '\u001b' -> esc = StringBuilder()
                '\n' -> endLine()
                '\r' -> col = 0
                '\b' -> if (col > 0) col--
                '\t' -> { val n = 8 - (col % 8); repeat(n) { put(' ') } }
                '\u0007' -> {}
                else -> if (c.code >= 32) put(c)
            }
        }
    }
}

class Completion(val text: String, val options: List<String>)

/** One terminal tab: lines of output, working folder and the running command, if any. */
class Session(val id: Int) {
    var lines by mutableStateOf<List<AnnotatedString>>(emptyList())
    var live by mutableStateOf(AnnotatedString(""))
    var cwd by mutableStateOf(Ws.root.absolutePath)
    var oldCwd: String = Ws.root.absolutePath
    var running by mutableStateOf(false)
    var input by mutableStateOf(TextFieldValue(""))
    var cols by mutableIntStateOf(60)
    private var histIdx = -1
    private var draft = ""
    private var proc: Process? = null
    @Volatile private var markerCwd: String? = null
    private val screen = Screen()
    private val main = Handler(Looper.getMainLooper())

    init {
        oldCwd = cwd
        push(listOf(styled("◈ MUX CODE · TERMINAL ${Env.version}", 0xFF19E3FF.toInt(), true),
            styled("sh session · type help for commands", 0xFF3C8A99.toInt(), false)))
    }

    private fun styled(t: String, color: Int, bold: Boolean) = buildAnnotatedString {
        withStyle(SpanStyle(color = Color(color), fontWeight = if (bold) FontWeight.Bold else null)) { append(t) }
    }

    private fun push(add: List<AnnotatedString>) {
        if (add.isEmpty()) return
        val all = lines + add
        val max = Prefs.maxLines.coerceAtLeast(200)
        lines = if (all.size > max) all.subList(all.size - max, all.size).toList() else all
    }

    /** Move this tab to a folder (used when the workspace changes). */
    fun goTo(path: String) {
        if (!running && File(path).isDirectory) { oldCwd = cwd; cwd = path }
    }

    fun note(t: String, color: Int = 0xFF3C8A99.toInt()) = push(listOf(styled(t, color, false)))

    fun clear() { lines = emptyList(); live = AnnotatedString("") }

    fun shortCwd(): String {
        val c = cwd
        return when {
            c == Env.home -> "~"
            c.startsWith(Env.home + "/") -> "~" + c.removePrefix(Env.home)
            c == Env.SD -> "sdcard"
            c.startsWith(Env.SD + "/") -> "sdcard" + c.removePrefix(Env.SD)
            else -> c
        }
    }

    fun prompt(): String = "mux@${shortCwd()}"

    private fun echo(cmd: String) {
        push(listOf(buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF3DDC84), fontWeight = FontWeight.Bold)) { append("mux") }
            withStyle(SpanStyle(color = Color(0xFF3C8A99))) { append("@") }
            withStyle(SpanStyle(color = Color(0xFF19E3FF), fontWeight = FontWeight.Bold)) { append(shortCwd()) }
            withStyle(SpanStyle(color = Color(0xFFFFB000), fontWeight = FontWeight.Bold)) { append(" $ ") }
            append(cmd)
        }))
    }

    fun historyUp() {
        val h = Prefs.history
        if (h.isEmpty()) return
        if (histIdx == -1) { draft = input.text; histIdx = h.size }
        if (histIdx > 0) histIdx--
        setInput(h[histIdx])
    }

    fun historyDown() {
        val h = Prefs.history
        if (histIdx == -1) return
        histIdx++
        if (histIdx >= h.size) { histIdx = -1; setInput(draft) } else setInput(h[histIdx])
    }

    fun setInput(t: String) { input = TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }

    fun insert(t: String) {
        val v = input
        val s = v.selection.min.coerceIn(0, v.text.length)
        val e = v.selection.max.coerceIn(0, v.text.length)
        val nt = v.text.substring(0, s) + t + v.text.substring(e)
        input = TextFieldValue(nt, androidx.compose.ui.text.TextRange(s + t.length))
    }

    fun moveCursor(d: Int) {
        val v = input
        val p = (v.selection.max + d).coerceIn(0, v.text.length)
        input = v.copy(selection = androidx.compose.ui.text.TextRange(p))
    }

    /** Called when the user presses enter / SEND. */
    fun submit() {
        val line = input.text
        setInput("")
        histIdx = -1
        if (running) {
            note("> $line", 0xFFD6F8FF.toInt())
            try {
                proc?.outputStream?.let { it.write((line + "\n").toByteArray()); it.flush() }
            } catch (e: Exception) {
            }
            return
        }
        echo(line)
        if (line.isBlank()) return
        Prefs.addHistory(line)
        runLine(line.trim())
    }

    /** Run a command as if typed (used by saved commands). */
    fun runCommand(cmd: String) {
        if (running) { note("a command is still running (press ^C)", 0xFFFF5252.toInt()); return }
        echo(cmd)
        Prefs.addHistory(cmd)
        runLine(cmd)
    }

    private fun expandAlias(line: String): String {
        val first = line.takeWhile { !it.isWhitespace() }
        val a = Prefs.aliases[first] ?: return line
        return a + line.substring(first.length)
    }

    private val meta = charArrayOf(';', '&', '|', '<', '>', '`', '$', '(', ')', '\n')

    private fun runLine(raw: String): Unit {
        val line = expandAlias(raw)
        val simple = line.indexOfAny(meta) < 0
        val parts = line.split(Regex("\\s+"))
        val name = parts[0]
        if (simple) {
            when (name) {
                "clear", "cls" -> { clear(); return }
                "help", "?" -> { help(); return }
                "history" -> {
                    if (parts.getOrNull(1) == "-c") { Prefs.clearHistory(); note("history cleared"); return }
                    val h = Prefs.history
                    push(h.mapIndexed { i, s -> AnnotatedString("${(i + 1).toString().padStart(4)}  $s") }.takeLast(100))
                    return
                }
                "alias" -> {
                    val rest = line.removePrefix(name).trim()
                    if (rest.isEmpty()) {
                        if (Prefs.aliases.isEmpty()) note("no aliases")
                        else push(Prefs.aliases.map { AnnotatedString("alias ${it.key}='${it.value}'") })
                    } else if (rest.contains('=')) {
                        val k = rest.substringBefore('=').trim()
                        val v = rest.substringAfter('=').trim().removeSurrounding("'").removeSurrounding("\"")
                        if (k.isEmpty() || v.isEmpty()) { note("usage: alias name='command'", 0xFFFF5252.toInt()); return }
                        Prefs.aliases = Prefs.aliases + (k to v); Prefs.commit()
                        note("alias saved: $k")
                    } else {
                        note(Prefs.aliases[rest]?.let { "alias $rest='$it'" } ?: "$rest: no such alias")
                    }
                    return
                }
                "unalias" -> {
                    val k = parts.getOrNull(1) ?: ""
                    if (Prefs.aliases.containsKey(k)) { Prefs.aliases = Prefs.aliases - k; Prefs.commit(); note("alias removed: $k") }
                    else note("$k: no such alias", 0xFFFF5252.toInt())
                    return
                }
                "export" -> {
                    val rest = line.removePrefix(name).trim()
                    if (rest.isEmpty()) {
                        if (Prefs.userEnv.isEmpty()) note("no saved variables") else push(Prefs.userEnv.map { AnnotatedString("export ${it.key}=${it.value}") })
                    } else if (rest.contains('=')) {
                        val k = rest.substringBefore('=').trim()
                        val v = rest.substringAfter('=').trim().removeSurrounding("'").removeSurrounding("\"")
                        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(k)) { note("bad name: $k", 0xFFFF5252.toInt()); return }
                        Prefs.userEnv = Prefs.userEnv + (k to v); Prefs.commit()
                        note("saved: $k (kept for all commands)")
                    } else note("usage: export NAME=value", 0xFFFF5252.toInt())
                    return
                }
                "unset" -> {
                    val k = parts.getOrNull(1) ?: ""
                    Prefs.userEnv = Prefs.userEnv - k; Prefs.commit(); note("removed: $k")
                    return
                }
                "git" -> { gitShim(line); return }
                "exit", "logout" -> { Sessions.closeSession(this); return }
            }
        }
        execute(line)
    }

    private fun gitShim(line: String) {
        running = true
        val dir = File(cwd)
        val args = GitEngine.tokenize(line).drop(1)
        Thread {
            val out = try { GitEngine.runCli(dir, args) } catch (e: Throwable) { "fatal: ${e.message}" }
            main.post {
                push(out.trimEnd().lines().map { AnnotatedString(it) })
                running = false
                Ws.refresh()
                Ed.reloadAllClean()
            }
        }.start()
    }

    private fun help() {
        val t = """
            MUX CODE TERMINAL - commands run in Android's /system/bin/sh
            Built in:  help  clear  history [-c]  alias [n='cmd']  unalias n
                       git (init clone status add commit log branch checkout
                            pull push fetch diff remote reset) - built in, no git binary
                       export NAME=value (saved)  unset NAME  exit (close tab)
            Everything else is the phone's own toolbox: ls cat grep ps ping
            df du find sed head tail wc sort id uname getprop top -n 1 ...
            cd works (also  cd -  cd ~  cd /storage/emulated/0)
            Pipes, redirects, && ; * and ${'$'}VAR all work.
            While a command runs, what you type goes to its stdin.
            Keys: TAB complete, up/down history, ^C stop, ^D end input.
            No PTY: full-screen programs (vi, nano, htop) cannot run.
        """.trimIndent()
        push(t.lines().map { styled(it, 0xFF3C8A99.toInt(), false) })
    }

    private fun flush() {
        if (screen.done.isNotEmpty()) {
            push(ArrayList(screen.done))
            screen.done.clear()
        }
        live = if (screen.isEmpty) AnnotatedString("") else screen.build()
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        main.post { screen.feed(text); flush() }
    }

    private fun execute(cmd: String) {
        running = true
        markerCwd = null
        val t0 = SystemClock.elapsedRealtime()
        val dir = File(cwd).let { if (it.isDirectory) it else File(Env.home) }
        val old = oldCwd
        val colsNow = cols
        Thread {
            var code = -1
            try {
                val wrap = "eval \"\$1\"; ec=\$?; printf '\\001%s\\002' \"\$PWD\"; exit \$ec"
                val pb = ProcessBuilder("/system/bin/sh", "-c", wrap, "mux", cmd)
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["HOME"] = Env.home
                env["TMPDIR"] = Env.cache
                env["USER"] = "mux"
                env["SHELL"] = "/system/bin/sh"
                env["TERM"] = "xterm-256color"
                env["COLUMNS"] = colsNow.toString()
                env["OLDPWD"] = old
                for ((k, v) in Prefs.userEnv) env[k] = v
                val p = pb.start()
                proc = p
                val rd = InputStreamReader(p.inputStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                val pend = StringBuilder()
                while (true) {
                    val n = rd.read(buf)
                    if (n < 0) break
                    pend.append(buf, 0, n)
                    drain(pend)
                }
                drain(pend, true)
                code = p.waitFor()
            } catch (e: Exception) {
                emit("\n" + (e.message ?: "cannot start shell") + "\n")
            }
            val ms = SystemClock.elapsedRealtime() - t0
            val newCwd = markerCwd
            main.post { finish(code, ms, newCwd) }
        }.start()
    }

    /** Pulls the hidden "where am I" marker out of the stream and emits the rest. */
    private fun drain(pend: StringBuilder, last: Boolean = false) {
        while (true) {
            val a = pend.indexOf("\u0001")
            if (a < 0) { emit(pend.toString()); pend.setLength(0); return }
            if (a > 0) { emit(pend.substring(0, a)); pend.delete(0, a) }
            val b = pend.indexOf("\u0002")
            if (b < 0) {
                if (last) pend.setLength(0)
                return
            }
            markerCwd = pend.substring(1, b)
            pend.delete(0, b + 1)
        }
    }

    private fun finish(code: Int, ms: Long, newCwd: String?) {
        if (!screen.isEmpty) screen.endLine()
        flush()
        if (newCwd != null && File(newCwd).isDirectory && newCwd != cwd) { oldCwd = cwd; cwd = newCwd }
        val sec = ms / 1000.0
        if (code != 0 && code != -1) note("[exit $code]", 0xFFFF5252.toInt())
        else if (code == -1) note("[stopped]", 0xFFFFB000.toInt())
        else if (ms > 2000) note("[done in ${"%.1f".format(sec)}s]")
        running = false
        proc = null
    }

    fun eof() {
        try { proc?.outputStream?.close() } catch (e: Exception) { }
        if (running) note("^D", 0xFF3C8A99.toInt())
    }

    fun interrupt() {
        val p = proc ?: return
        note("^C", 0xFFFFB000.toInt())
        Thread {
            try {
                val pid = pidOf(p)
                if (pid > 0) killTree(pid)
                p.destroy()
                Thread.sleep(600)
                p.destroyForcibly()
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun pidOf(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    } catch (e: Throwable) {
        -1
    }

    private fun killTree(root: Int) {
        try {
            val kids = HashMap<Int, MutableList<Int>>()
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                try {
                    val s = File(d, "stat").readText()
                    val rest = s.substring(s.lastIndexOf(')') + 2).split(' ')
                    val pp = rest[1].toInt()
                    kids.getOrPut(pp) { ArrayList() }.add(pid)
                } catch (e: Exception) {
                }
            }
            val all = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                all.add(x)
                kids[x]?.let { stack.addAll(it) }
            }
            for (pid in all.reversed()) {
                try { android.os.Process.killProcess(pid) } catch (e: Exception) { }
            }
        } catch (e: Exception) {
        }
    }

    fun destroy() {
        try { proc?.destroyForcibly() } catch (e: Exception) { }
    }

    /** TAB completion for the last word. */
    fun complete(): Completion? {
        val text = input.text.substring(0, input.selection.max.coerceIn(0, input.text.length))
        val tail = input.text.substring(text.length)
        val sp = text.lastIndexOf(' ')
        val head = text.substring(0, sp + 1)
        val word = text.substring(sp + 1)
        val first = head.isBlank()
        val slash = word.lastIndexOf('/')
        val dirPart = if (slash >= 0) word.substring(0, slash + 1) else ""
        val base = word.substring(slash + 1)
        val dir = when {
            dirPart.startsWith("~") -> File(Env.home, dirPart.removePrefix("~"))
            dirPart.startsWith("/") -> File(dirPart)
            dirPart.isEmpty() -> File(cwd)
            else -> File(cwd, dirPart)
        }
        val cands = sortedSetOf<String>()
        try {
            dir.list()?.forEach { n ->
                if (n.startsWith(base) && (base.startsWith(".") || !n.startsWith("."))) {
                    cands.add(if (File(dir, n).isDirectory) "$n/" else n)
                }
            }
        } catch (e: Exception) {
        }
        if (first && dirPart.isEmpty()) {
            for (b in listOf("help", "clear", "history", "alias", "unalias", "export", "unset", "exit", "cd")) if (b.startsWith(base)) cands.add(b)
            (System.getenv("PATH") ?: "/system/bin").split(':').forEach { d ->
                try { File(d).list()?.forEach { n -> if (n.startsWith(base)) cands.add(n) } } catch (e: Exception) { }
            }
            for (a in Prefs.aliases.keys) if (a.startsWith(base)) cands.add(a)
        }
        if (cands.isEmpty()) return null
        fun esc(s: String) = s.replace(" ", "\\ ")
        val list = cands.toList()
        if (list.size == 1) {
            val one = list[0]
            val add = if (one.endsWith("/")) "" else " "
            return Completion(head + dirPart + esc(one) + add + tail, emptyList())
        }
        var common = list[0]
        for (x in list) { while (!x.startsWith(common)) common = common.dropLast(1) }
        val nt = if (common.length > base.length) head + dirPart + esc(common) + tail else input.text
        return Completion(nt, list)
    }
}

object Sessions {
    var list by mutableStateOf<List<Session>>(emptyList())
    var current by mutableIntStateOf(0)
    private var counter = 0

    fun ensure() {
        if (list.isEmpty()) add()
    }

    fun add() {
        counter++
        list = list + Session(counter)
        current = list.size - 1
    }

    fun closeSession(s: Session) {
        s.destroy()
        val i = list.indexOf(s)
        if (i < 0) return
        if (list.size == 1) {
            list = listOf(Session(++counter)); current = 0
            return
        }
        list = list.filter { it !== s }
        current = current.coerceIn(0, list.size - 1)
        if (i < current) current--
    }

    val active: Session
        get() {
            ensure()
            return list[current.coerceIn(0, list.size - 1)]
        }
}
