package com.mux.android.code

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ExtInfo(
    val id: String, val name: String, val publisher: String, val version: String, val desc: String,
    val dir: File, val themes: Int, val snippets: Int, val hasCode: Boolean
)

class MarketHit(val ns: String, val name: String, val version: String, val title: String, val desc: String, val url: String)

/**
 * Extensions: installs .vsix files (also from the Open VSX registry) and uses their declarative parts:
 * colour themes and snippets. Extension code (the "main" JavaScript that needs the VS Code / Node host)
 * is NOT executed.
 */
object Ext {
    var installed by mutableStateOf<List<ExtInfo>>(emptyList())
    var themeList by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var base: File? = null

    fun init(ctx: Context) {
        base = File(ctx.filesDir, "ext").also { it.mkdirs() }
        scan()
    }

    private fun rd(dir: File, rel: String): String? = try {
        val f = File(dir, rel.removePrefix("./")).canonicalFile
        if (f.path.startsWith(dir.canonicalPath) && f.isFile) f.readText() else null
    } catch (e: Exception) {
        null
    }

    fun scan() {
        val b = base ?: return
        val list = ArrayList<ExtInfo>()
        val themes = ArrayList<Pair<String, String>>()
        for (d in b.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            try {
                val pj = JSONObject(rd(d, "package.json") ?: continue)
                val c = pj.optJSONObject("contributes")
                val th = c?.optJSONArray("themes")
                val sn = c?.optJSONArray("snippets")
                val pub = pj.optString("publisher", "")
                val nm = pj.optString("name", d.name)
                val disp = pj.optString("displayName", nm).let { if (it.startsWith("%")) nm else it }
                list.add(
                    ExtInfo(
                        d.name, disp, pub, pj.optString("version", ""),
                        pj.optString("description", "").let { if (it.startsWith("%")) "" else it }, d,
                        th?.length() ?: 0, sn?.length() ?: 0,
                        pj.has("main") || pj.has("browser")
                    )
                )
                if (th != null) for (i in 0 until th.length()) {
                    val label = th.getJSONObject(i).optString("label", "theme")
                    themes.add("ext:${d.name}:$label" to "$label")
                }
            } catch (e: Exception) {
            }
        }
        installed = list
        themeList = themes
    }

    /** Removes comments and trailing commas so VS Code style JSON (JSONC) can be parsed. */
    fun stripJsonc(s: String): String {
        val o = StringBuilder()
        var i = 0
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                o.append(c)
                if (c == '\\' && i + 1 < s.length) { o.append(s[i + 1]); i++ } else if (c == '"') inStr = false
            } else if (c == '"') { inStr = true; o.append(c) }
            else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') { while (i < s.length && s[i] != '\n') i++; continue }
            else if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i += 2
                while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                i += 2
                continue
            } else o.append(c)
            i++
        }
        return o.toString().replace(Regex(",\\s*([}\\]])"), "$1").removePrefix("﻿")
    }

    private fun loadTheme(dir: File, rel: String, depth: Int = 0): JSONObject? {
        val txt = rd(dir, rel) ?: return null
        val t = try { JSONObject(stripJsonc(txt)) } catch (e: Exception) { return null }
        val inc = t.optString("include", "")
        if (inc.isNotEmpty() && depth < 3) {
            val parentRel = File(rel).parent?.let { "$it/$inc" } ?: inc
            val b = loadTheme(dir, parentRel, depth + 1)
            if (b != null) {
                val colors = b.optJSONObject("colors") ?: JSONObject()
                t.optJSONObject("colors")?.let { for (k in it.keys()) colors.put(k, it.get(k)) }
                t.put("colors", colors)
                val tc = JSONArray()
                b.optJSONArray("tokenColors")?.let { for (i in 0 until it.length()) tc.put(it.get(i)) }
                t.optJSONArray("tokenColors")?.let { for (i in 0 until it.length()) tc.put(it.get(i)) }
                t.put("tokenColors", tc)
            }
        }
        return t
    }

    /** JSON handed to the editor page: themes and snippets of all installed extensions. */
    fun editorPayload(): String {
        val themes = JSONArray()
        val snippets = JSONArray()
        for (e in installed) {
            try {
                val pj = JSONObject(rd(e.dir, "package.json") ?: continue)
                val c = pj.optJSONObject("contributes") ?: continue
                c.optJSONArray("themes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val th = arr.getJSONObject(i)
                        val label = th.optString("label", "theme")
                        val t = loadTheme(e.dir, th.optString("path")) ?: continue
                        val ui = th.optString("uiTheme", "vs-dark")
                        t.put("id", "ext:${e.id}:$label")
                        t.put("type", when (ui) { "vs" -> "light"; "hc-black" -> "hc"; else -> "dark" })
                        themes.put(t)
                    }
                }
                c.optJSONArray("snippets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val sn = arr.getJSONObject(i)
                        val txt = rd(e.dir, sn.optString("path")) ?: continue
                        val obj = try { JSONObject(stripJsonc(txt)) } catch (x: Exception) { continue }
                        snippets.put(JSONObject().put("lang", sn.optString("language")).put("snippets", obj))
                    }
                }
            } catch (x: Exception) {
            }
        }
        return JSONObject().put("themes", themes).put("snippets", snippets).toString()
    }

    /** Installs a .vsix (a zip with an "extension/" folder). Returns a message. */
    fun installVsix(input: InputStream): String {
        val b = base ?: return "not ready"
        val tmp = File(b, ".tmp-${System.currentTimeMillis()}")
        tmp.mkdirs()
        var total = 0L
        try {
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || !e.name.startsWith("extension/")) continue
                    val rel = e.name.removePrefix("extension/")
                    val out = File(tmp, rel).canonicalFile
                    if (!out.path.startsWith(tmp.canonicalPath + "/")) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 120L * 1024 * 1024) throw IllegalStateException("extension is too big")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            val pj = JSONObject(rd(tmp, "package.json") ?: throw IllegalStateException("not a VS Code extension (no package.json)"))
            val id = "${pj.optString("publisher", "x")}.${pj.optString("name", "ext")}-${pj.optString("version", "0")}".lowercase()
            val dest = File(b, id)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IllegalStateException("cannot save extension")
            scan()
            val c = pj.optJSONObject("contributes")
            val th = c?.optJSONArray("themes")?.length() ?: 0
            val sn = c?.optJSONArray("snippets")?.length() ?: 0
            val code = pj.has("main") || pj.has("browser")
            return "installed ${pj.optString("name")}: $th theme(s), $sn snippet file(s)" +
                if (code) ". Its code part is not run on Android." else ""
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return "install failed: ${e.message}"
        }
    }

    fun uninstall(e: ExtInfo) {
        e.dir.deleteRecursively()
        scan()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.bufferedReader().readText()
    }

    /** Search the Open VSX registry (open-vsx.org), the free VS Code extension marketplace. */
    fun search(q: String): List<MarketHit> {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val j = JSONObject(get("https://open-vsx.org/api/-/search?query=$enc&size=25&sortBy=relevance"))
        val arr = j.optJSONArray("extensions") ?: return emptyList()
        val out = ArrayList<MarketHit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dl = o.optJSONObject("files")?.optString("download", "") ?: ""
            if (dl.isEmpty()) continue
            out.add(
                MarketHit(
                    o.optString("namespace"), o.optString("name"), o.optString("version"),
                    o.optString("displayName", o.optString("name")).ifBlank { o.optString("name") },
                    o.optString("description", ""), dl
                )
            )
        }
        return out
    }

    fun installFromUrl(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) return "download failed: HTTP ${c.responseCode}"
        return c.inputStream.use { installVsix(it) }
    }
}
