package com.mux.android.code

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class Hit(val path: String, val line: Int, val text: String)

/** The open folder (workspace), the open files and the file helpers. */
object Ws {
    var root by mutableStateOf(File("/"))
    val open = mutableStateListOf<String>()
    var active by mutableStateOf<String?>(null)
    val dirty = mutableStateMapOf<String, Boolean>()
    var tick by mutableIntStateOf(0)

    fun init() {
        val r = File(if (Prefs.rootPath.isNotEmpty()) Prefs.rootPath else Env.home)
        root = if (r.isDirectory) r else File(Env.home)
        open.clear()
        for (p in Prefs.openPaths) if (File(p).isFile) open.add(p)
        active = Prefs.activePath.takeIf { it in open } ?: open.firstOrNull()
    }

    fun persist() {
        Prefs.rootPath = root.absolutePath
        Prefs.openPaths = open.toList()
        Prefs.activePath = active ?: ""
        Prefs.commit()
    }

    fun setWorkspaceRoot(f: File) {
        if (!f.isDirectory) return
        root = f
        tick++
        persist()
        try { Sessions.active.goTo(f.absolutePath) } catch (e: Exception) { }
    }

    fun refresh() { tick++ }

    fun lang(name: String): String {
        val n = name.lowercase()
        if (n == "dockerfile") return "dockerfile"
        if (n == "makefile") return "plaintext"
        return when (n.substringAfterLast('.', "")) {
            "js", "mjs", "cjs", "jsx" -> "javascript"
            "ts", "tsx", "mts" -> "typescript"
            "json", "jsonc", "map" -> "json"
            "html", "htm", "vue" -> "html"
            "css" -> "css"
            "scss" -> "scss"
            "less" -> "less"
            "md", "markdown" -> "markdown"
            "py" -> "python"
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "cs" -> "csharp"
            "go" -> "go"
            "rs" -> "rust"
            "rb" -> "ruby"
            "php" -> "php"
            "sh", "bash", "zsh" -> "shell"
            "yml", "yaml" -> "yaml"
            "xml", "svg", "xaml", "plist" -> "xml"
            "sql" -> "sql"
            "swift" -> "swift"
            "lua" -> "lua"
            "r" -> "r"
            "gradle", "groovy" -> "groovy"
            "ini", "toml", "properties", "conf" -> "ini"
            "bat", "cmd" -> "bat"
            "ps1" -> "powershell"
            else -> "plaintext"
        }
    }

    fun isText(f: File): Boolean {
        if (!f.isFile || f.length() > 3L * 1024 * 1024) return false
        return try {
            f.inputStream().use { ins ->
                val b = ByteArray(4096)
                val n = ins.read(b)
                for (i in 0 until maxOf(n, 0)) if (b[i].toInt() == 0) return false
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun openFile(path: String, line: Int = 0): Boolean {
        val f = File(path)
        if (!f.isFile) { Util.toastApp("FILE NOT FOUND"); return false }
        if (path !in open) {
            if (!isText(f)) { Util.toastApp("BINARY OR TOO BIG (3 MB MAX)"); return false }
            open.add(path)
        }
        active = path
        Ed.openInJs(path)
        if (line > 0) Ed.goto(line)
        persist()
        return true
    }

    fun show(path: String) {
        active = path
        Ed.show(path)
        persist()
    }

    fun closeFile(path: String) {
        val i = open.indexOf(path)
        if (i < 0) return
        open.removeAt(i)
        dirty.remove(path)
        Ed.close(path)
        if (active == path) {
            active = open.getOrNull(minOf(i, open.size - 1))
            active?.let { Ed.show(it) }
        }
        persist()
    }

    /** Called when the editor sends the text to save. */
    fun saved(path: String, text: String, ver: String) {
        try {
            File(path).writeText(text)
            Ed.markSaved(path, ver)
            tick++
        } catch (e: Exception) {
            Util.toastApp("SAVE FAILED: ${e.message}")
        }
    }

    fun saveActive() { active?.let { Ed.requestSave(it) } }
    fun saveAll() { for (p in open) if (dirty[p] == true) Ed.requestSave(p) }

    fun create(parent: File, name: String, folder: Boolean): String? {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) return "bad name"
        val f = File(parent, n)
        if (f.exists()) return "already exists"
        return try {
            if (folder) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
            tick++
            if (!folder) openFile(f.absolutePath)
            null
        } catch (e: Exception) {
            e.message ?: "failed"
        }
    }

    fun rename(f: File, name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) return "bad name"
        val t = File(f.parentFile, n)
        if (t.exists()) return "already exists"
        val oldPaths = open.filter { it == f.absolutePath || it.startsWith(f.absolutePath + "/") }
        for (p in oldPaths) closeFile(p)
        if (!f.renameTo(t)) return "rename failed"
        tick++
        if (t.isFile) openFile(t.absolutePath)
        return null
    }

    fun delete(f: File) {
        val victims = open.filter { it == f.absolutePath || it.startsWith(f.absolutePath + "/") }
        for (p in victims) closeFile(p)
        f.deleteRecursively()
        tick++
    }

    private val skip = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist")

    fun search(query: String, maxHits: Int = 200): List<Hit> {
        val out = ArrayList<Hit>()
        if (query.isBlank()) return out
        val q = query.lowercase()
        fun walk(d: File) {
            if (out.size >= maxHits) return
            val kids = d.listFiles() ?: return
            for (k in kids.sortedBy { it.name }) {
                if (out.size >= maxHits) return
                if (k.isDirectory) { if (k.name !in skip) walk(k) }
                else if (k.length() < 1024 * 1024 && isText(k)) {
                    try {
                        var n = 0
                        k.forEachLine { l ->
                            n++
                            if (out.size < maxHits && l.lowercase().contains(q)) out.add(Hit(k.absolutePath, n, l.trim().take(120)))
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
        walk(root)
        return out
    }

    fun rel(path: String): String {
        val r = root.absolutePath
        return if (path.startsWith("$r/")) path.removePrefix("$r/") else path
    }
}
