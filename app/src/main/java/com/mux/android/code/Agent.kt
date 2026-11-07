package com.mux.android.code

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ChatItem(val kind: Char, val text: String) // u = you, a = assistant, t = tool, e = error, i = info

class Pending(val title: String, val detail: String, val answer: CompletableDeferred<Boolean>)

/**
 * The AI agent. It talks to the Anthropic Messages API with your own API key and can use tools:
 * read / list / search / write / edit files in the open folder, run shell commands and git.
 */
object Agent {
    var chat by mutableStateOf<List<ChatItem>>(emptyList())
    var busy by mutableStateOf(false)
    var draft by mutableStateOf("")
    var pending by mutableStateOf<Pending?>(null)
    private var history = JSONArray()
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun add(k: Char, t: String) { chat = chat + ChatItem(k, t) }

    fun newChat() {
        stop()
        chat = emptyList()
        history = JSONArray()
    }

    fun stop() {
        job?.cancel()
        pending?.answer?.complete(false)
        pending = null
        busy = false
    }

    /** Called by the editor: puts the selected code into the chat box. */
    fun fromSelection(path: String, text: String) {
        val name = if (path.isEmpty()) "file" else Ws.rel(path)
        draft = if (text.isBlank()) "About $name: " else "In $name:\n```\n${text.take(4000)}\n```\n"
        Nav.tab = Nav.AI
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        if (Prefs.apiKey.isBlank()) {
            add('e', "No API key. Open MORE > SET > AI AGENT and add your Anthropic API key.")
            return
        }
        add('u', t)
        busy = true
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { run(t) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                add('i', "[stopped]")
            } catch (e: Exception) {
                add('e', e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
                Ws.refresh()
                Ed.reloadAllClean()
            }
        }
    }

    private fun system(): String {
        val active = Ws.active?.let { Ws.rel(it) } ?: "none"
        val open = Ws.open.joinToString(", ") { Ws.rel(it) }.ifEmpty { "none" }
        return """You are the coding agent inside "Code", a VS Code style editor app on an Android phone.
The workspace folder is ${Ws.root.absolutePath}. All file tools take paths relative to it.
Active file: $active. Open files: $open.
The phone shell is Android toolbox (sh): no apt, no npm, no python. Use it for ls, cat, grep, find, sed, etc.
For git use the git tool (init, clone, status, add, commit, log, branch, checkout, pull, push, diff).
Work step by step: look at the files first, make small precise edits (replace_in_file) instead of rewriting whole files,
and say briefly what you changed. Keep answers short. The user may write in Bangla or English; answer in the same language."""
    }

    private fun tools(): JSONArray {
        fun tool(name: String, desc: String, props: Map<String, String>, req: List<String>): JSONObject {
            val p = JSONObject()
            for ((k, v) in props) p.put(k, JSONObject().put("type", "string").put("description", v))
            return JSONObject().put("name", name).put("description", desc).put(
                "input_schema", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(req))
            )
        }
        return JSONArray()
            .put(tool("list_files", "List files and folders in a folder (one level).", mapOf("path" to "folder, default ."), emptyList()))
            .put(tool("read_file", "Read a text file. Optional line range.", mapOf("path" to "file path", "start_line" to "first line (1-based)", "end_line" to "last line"), listOf("path")))
            .put(tool("search", "Search text in all files of the workspace.", mapOf("query" to "text to find (case-insensitive)"), listOf("query")))
            .put(tool("write_file", "Create or overwrite a file with the given full content.", mapOf("path" to "file path", "content" to "full file content"), listOf("path", "content")))
            .put(tool("replace_in_file", "Replace one exact occurrence of old_text with new_text in a file.", mapOf("path" to "file path", "old_text" to "exact text to replace (must be unique)", "new_text" to "replacement"), listOf("path", "old_text", "new_text")))
            .put(tool("run_command", "Run a shell command in the workspace folder (60 s limit). Output is returned.", mapOf("command" to "shell command"), listOf("command")))
            .put(tool("git", "Run a git command, for example: status, add ., commit -m \"msg\", log, diff, push.", mapOf("args" to "arguments after the word git"), listOf("args")))
    }

    private fun post(body: JSONObject): JSONObject {
        val url = URL(Prefs.baseUrl.trimEnd('/') + "/v1/messages")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 180000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", Prefs.apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(txt).optJSONObject("error")?.optString("message") ?: txt } catch (e: Exception) { txt }
            throw IllegalStateException("API error $code: ${msg.take(300)}")
        }
        return JSONObject(txt)
    }

    private fun run(userText: String) {
        history.put(JSONObject().put("role", "user").put("content", userText))
        var steps = 0
        while (steps < Prefs.maxSteps.coerceAtLeast(1)) {
            steps++
            val body = JSONObject()
                .put("model", Prefs.model)
                .put("max_tokens", 4096)
                .put("system", system())
                .put("tools", tools())
                .put("messages", history)
            val resp = post(body)
            val content = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", content))
            val uses = ArrayList<JSONObject>()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> if (b.optString("text").isNotBlank()) postUi { add('a', b.getString("text").trim()) }
                    "tool_use" -> uses.add(b)
                }
            }
            if (resp.optString("stop_reason") != "tool_use" || uses.isEmpty()) {
                if (resp.optString("stop_reason") == "max_tokens") postUi { add('i', "[answer was cut: max length]") }
                return
            }
            val results = JSONArray()
            for (u in uses) {
                val name = u.getString("name")
                val input = u.optJSONObject("input") ?: JSONObject()
                postUi { add('t', "▸ $name ${summ(name, input)}") }
                var err = false
                val out = try { runTool(name, input) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = true; "error: ${e.message}" }
                val r = JSONObject().put("type", "tool_result").put("tool_use_id", u.getString("id")).put("content", out.take(12000))
                if (err) r.put("is_error", true)
                results.put(r)
            }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        postUi { add('i', "[stopped after ${Prefs.maxSteps} steps; send another message to continue]") }
    }

    private fun postUi(f: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { f() }
    }

    private fun summ(name: String, i: JSONObject): String = when (name) {
        "run_command" -> i.optString("command").take(80)
        "git" -> i.optString("args").take(80)
        "search" -> i.optString("query").take(60)
        else -> i.optString("path")
    }

    private fun res(p: String): File {
        val root = Ws.root.canonicalFile
        val f = (if (p.startsWith("/")) File(p) else File(root, p)).canonicalFile
        if (f.path != root.path && !f.path.startsWith(root.path + "/")) throw IllegalArgumentException("path is outside the workspace")
        return f
    }

    private fun approve(title: String, detail: String): Boolean {
        if (Prefs.approveAuto) return true
        val d = CompletableDeferred<Boolean>()
        postUi { pending = Pending(title, detail, d) }
        val ok = kotlinx.coroutines.runBlocking { d.await() }
        postUi { pending = null }
        return ok
    }

    private fun runTool(name: String, i: JSONObject): String {
        when (name) {
            "list_files" -> {
                val d = res(i.optString("path", ".").ifEmpty { "." })
                val kids = d.listFiles() ?: return "not a folder"
                return kids.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .filter { it.name != ".git" }
                    .joinToString("\n") { (if (it.isDirectory) "[dir]  " else "[file] ") + it.name }
                    .ifEmpty { "(empty)" }
            }
            "read_file" -> {
                val f = res(i.getString("path"))
                if (!Ws.isText(f)) return "not a readable text file"
                val lines = f.readLines()
                if (lines.isEmpty()) return "(empty file)"
                val s = (i.optString("start_line").toIntOrNull() ?: 1).coerceIn(1, lines.size)
                val e = (i.optString("end_line").toIntOrNull() ?: lines.size).coerceIn(s, lines.size)
                return lines.subList(s - 1, e).mapIndexed { n, l -> "${s + n}: $l" }.joinToString("\n")
            }
            "search" -> {
                val h = Ws.search(i.getString("query"), 60)
                return if (h.isEmpty()) "no matches" else h.joinToString("\n") { "${Ws.rel(it.path)}:${it.line}: ${it.text}" }
            }
            "write_file" -> {
                val f = res(i.getString("path"))
                val text = i.getString("content")
                if (!approve("WRITE FILE", "${Ws.rel(f.absolutePath)}  (${text.length} chars)")) return "the user declined"
                f.parentFile?.mkdirs()
                f.writeText(text)
                touched(f)
                return "written ${Ws.rel(f.absolutePath)}"
            }
            "replace_in_file" -> {
                val f = res(i.getString("path"))
                val old = i.getString("old_text")
                val nw = i.getString("new_text")
                val src = f.readText()
                val n = src.split(old).size - 1
                if (old.isEmpty() || n == 0) return "old_text not found"
                if (n > 1) return "old_text appears $n times; give more context so it is unique"
                if (!approve("EDIT FILE", "${Ws.rel(f.absolutePath)}\n- ${old.take(300)}\n+ ${nw.take(300)}")) return "the user declined"
                f.writeText(src.replace(old, nw))
                touched(f)
                return "edited ${Ws.rel(f.absolutePath)}"
            }
            "run_command" -> {
                val cmd = i.getString("command")
                if (!approve("RUN COMMAND", cmd)) return "the user declined"
                return shell(cmd)
            }
            "git" -> {
                val args = GitEngine.tokenize(i.getString("args"))
                val writes = args.firstOrNull() in setOf("add", "commit", "push", "pull", "checkout", "reset", "clone", "init", "branch", "fetch")
                if (writes && !approve("GIT", "git " + i.getString("args"))) return "the user declined"
                val out = GitEngine.runCli(Ws.root, args)
                postUi { Ws.refresh(); Ed.reloadAllClean() }
                return out
            }
        }
        return "unknown tool $name"
    }

    private fun touched(f: File) {
        postUi {
            Ws.refresh()
            Ed.reloadFromDisk(f.absolutePath)
        }
    }

    private fun shell(cmd: String): String {
        val first = cmd.trim().split(Regex("\\s+")).firstOrNull()
        if (first == "git" && cmd.none { it in ";&|<>`$" }) return GitEngine.runCli(Ws.root, GitEngine.tokenize(cmd).drop(1))
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
        pb.directory(Ws.root)
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = Env.home
        pb.environment()["TMPDIR"] = Env.cache
        val p = pb.start()
        p.outputStream.close()
        val sb = StringBuilder()
        val t = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (sb.length < 12000) sb.append(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
            }
        }
        t.start()
        val done = p.waitFor(60, TimeUnit.SECONDS)
        if (!done) { p.destroyForcibly(); sb.append("\n[killed after 60 s]") }
        t.join(1500)
        postUi { Ws.refresh(); Ed.reloadAllClean() }
        val code = if (done) p.exitValue() else -1
        return sb.toString().ifBlank { "(no output)" } + if (code != 0) "\n[exit $code]" else ""
    }
}
